"""
Data Completeness Calculator
Computes field-level completeness for each entity type by querying PostgreSQL.

Manufacturing entity types (PROCESSING_BATCH / WORK_SESSION / MATERIAL_BATCH /
QUALITY_INSPECTION / EQUIPMENT) live in cretas_db.

Restaurant POS entity types (POS_TRANSACTION / POS_ITEM) live in smartbi_db
silver fact tables (fact_pos_transaction / fact_pos_item). These tables are
RLS-protected by ``app.factory_id`` GUC — we acquire the smartbi pool via
``smartbi.config.get_pg_pool`` whose ``setup`` callback applies the GUC from
``smartbi.tenant_ctx.current_factory_id`` (set by JWTAuthMiddleware). We also
defensively call ``set_config`` inside a transaction in case the middleware
context didn't propagate.
"""
from fastapi import APIRouter, HTTPException, Request

from common.tenant_identity import require_same_factory
from pydantic import BaseModel
from typing import Any, Dict, List, Optional
import asyncpg
import os
import json
import logging

logger = logging.getLogger(__name__)

router = APIRouter()


class CompletenessResult(BaseModel):
    entity_type: str
    total_records: int
    field_completeness: Dict[str, float]  # field_name -> completion %
    overall_completeness: float
    # Optional per-entity metadata (e.g. date range, unique counts) — kept
    # optional so manufacturing entities don't have to populate it.
    metadata: Optional[Dict[str, Any]] = None


class CompletenessRequest(BaseModel):
    # 工厂取登录身份; 传了就必须一致 (旧前端会传自己的工厂)
    factory_id: Optional[str] = None
    entity_types: Optional[List[str]] = None  # if None, compute all (manufacturing only)


# Entity type to table/field mapping
ENTITY_FIELD_MAP = {
    "PROCESSING_BATCH": {
        "table": "production_batches",
        "factory_filter": "factory_id",
        "fields": [
            "batch_number", "product_name", "planned_quantity", "actual_quantity",
            "good_quantity", "defect_quantity", "yield_rate", "material_cost",
            "labor_cost", "equipment_cost", "total_cost", "unit_cost",
            "equipment_id", "supervisor_id", "worker_count", "start_time", "end_time"
        ]
    },
    "WORK_SESSION": {
        "table": "employee_work_sessions",
        "factory_filter": "factory_id",
        "fields": [
            "user_id", "work_type_id", "start_time", "end_time",
            "break_minutes", "actual_work_minutes", "hourly_rate", "labor_cost"
        ]
    },
    "MATERIAL_BATCH": {
        "table": "material_batches",
        "factory_filter": "factory_id",
        "fields": [
            "batch_number", "material_type_id", "receipt_quantity", "unit_price",
            "supplier_id", "expire_date", "storage_location"
        ]
    },
    "QUALITY_INSPECTION": {
        "table": "quality_inspections",
        "factory_filter": "factory_id",
        "fields": [
            "production_batch_id", "inspector_id", "inspection_date", "result",
            "pass_count", "fail_count", "sample_size", "pass_rate"
        ]
    },
    "EQUIPMENT": {
        "table": "factory_equipment",
        "factory_filter": "factory_id",
        "fields": [
            "equipment_name", "type", "total_running_hours",
            "last_maintenance_date", "status"
        ]
    }
}


# Restaurant POS entity types — live in smartbi_db silver layer, RLS-protected.
# Schema reference: backend/python/smartbi/database/migrations/2026_04_29_silver_facts.sql
# return_qty added via V20260511_03; meal_period added via V20260513_01.
POS_ENTITY_FIELD_MAP = {
    "POS_TRANSACTION": {
        "table": "fact_pos_transaction",
        "fields": [
            "gross_amount", "actual_receive", "customer_count",
            "order_type", "meal_period", "store_id", "staff_id",
        ],
        "date_field": "date",
    },
    "POS_ITEM": {
        "table": "fact_pos_item",
        "fields": [
            "qty", "unit_price", "amount", "return_qty",
        ],
        # fact_pos_item has no date column directly — items inherit date from
        # parent transaction. No date range reported for POS_ITEM.
        "date_field": None,
    },
}

# Entity types that should be reported by default for restaurant tenants. We
# keep this opt-in (callers must pass entity_types explicitly OR the frontend
# selects them based on tenant type) so manufacturing tenants don't get
# spurious zero-record POS rows.
POS_ENTITY_TYPES = list(POS_ENTITY_FIELD_MAP.keys())


def _pool_kwargs() -> Dict[str, Any]:
    """连接参数 —— **分参数传**，⛔ 不拼 DSN 字符串。

    🔴 2026-08-28 生产实测：原来这里拼
    ``f"postgresql://{user}:{password}@{host}:{port}/{db}"``，而生产密码里含 ``/``。
    ``/`` 会把 netloc 在 ``@`` **之前**就截断::

        postgresql://cretas_user:AbC/xxxx@host:5432/db
                     └─ netloc 只到第一个 / ─┘   @host:5432 掉进了 path

    asyncpg 于是解析出 ``host=cretas_user`` / ``port="AbC"``，``int(port)`` 抛
    ``invalid literal for int() with base 10: 'AbC'``。
    ⇒ 这条路 **100% 起不来**（不是偶发），而且报错把**数据库密码的首段**
    一路回显给了调用方。

    ⚠️ 修法不是「补一个 ``quote()``」——那只是把同一个决定复制成第 4 份。
    分参数传之后，「哪个字符要转义」这个问题从物理上不存在。
    （本仓 rule: 拼 DSN 前先想密码里有没有特殊字符 ⇒ 一律分参数传。）
    """
    return {
        "host": os.getenv("FOOD_KB_POSTGRES_HOST", "localhost"),
        "port": int(os.getenv("FOOD_KB_POSTGRES_PORT", "5432")),
        "database": os.getenv("FOOD_KB_POSTGRES_DB", "cretas_db"),
        "user": os.getenv("FOOD_KB_POSTGRES_USER", "cretas_user"),
        "password": os.getenv("FOOD_KB_POSTGRES_PASSWORD", ""),
    }


async def _get_db_pool():
    """Get asyncpg connection pool using cretas database config.
    Reuses the FOOD_KB_POSTGRES_* env vars (same DB, set in systemd)."""
    db_url = os.getenv("COMPLETENESS_DB_URL")
    if db_url:
        # 显式给了整条 DSN 就照用 —— 那是运维自己拼的，责任在配置侧。
        return await asyncpg.create_pool(db_url, min_size=1, max_size=3)
    return await asyncpg.create_pool(min_size=1, max_size=3, **_pool_kwargs())


async def _compute_pos_entity(
    factory_id: str, entity_type: str
) -> Optional[CompletenessResult]:
    """Compute completeness for a POS silver fact entity (smartbi_db).

    Queries the singleton smartbi pool from ``smartbi.config.get_pg_pool``.
    The pool's ``setup`` callback applies ``app.factory_id`` GUC from
    ``smartbi.tenant_ctx.current_factory_id`` so RLS bites. We also defensively
    set the GUC inside a transaction (mirrors restaurant_completeness pattern)
    in case the contextvar wasn't set on this task tree.

    Returns ``None`` if the smartbi pool is unavailable (config missing) — the
    caller logs and continues with the remaining entity types.
    """
    config = POS_ENTITY_FIELD_MAP.get(entity_type)
    if config is None:
        return None

    table = config["table"]
    fields = config["fields"]
    date_field = config.get("date_field")

    # Lazy import — avoids pulling smartbi package at module load for callers
    # that don't query POS entities (e.g. manufacturing-only deployments).
    try:
        from smartbi.config import get_pg_pool
        from smartbi.tenant_ctx import set_factory_id, reset_factory_id
    except ImportError as exc:
        logger.warning(
            f"[completeness] smartbi package unavailable, skipping {entity_type}: {exc}"
        )
        return None

    pool = await get_pg_pool()
    if pool is None:
        logger.warning(
            f"[completeness] smartbi pool unavailable (POSTGRES_URL not set), skipping {entity_type}"
        )
        return None

    # Ensure tenant ctx is set on this task tree — defensive in case middleware
    # didn't propagate (e.g. compute_completeness called from a non-request
    # context). Reset after the query so we don't leak factory_id.
    token = set_factory_id(factory_id)
    try:
        count_exprs = ", ".join([f"COUNT({f}) AS cnt_{f}" for f in fields])
        select_extras = ""
        if date_field:
            select_extras += f", MIN({date_field}) AS min_date, MAX({date_field}) AS max_date"
        if entity_type == "POS_ITEM":
            select_extras += ", COUNT(DISTINCT product_id) AS unique_products"

        query = (
            f"SELECT COUNT(*) AS total, {count_exprs}{select_extras} "
            f"FROM {table} WHERE factory_id = $1"
        )

        async with pool.acquire() as conn:
            # Defensive: re-apply GUC inside a transaction. The pool setup
            # callback already does this at borrow time, but tenant_ctx may
            # not have been set by middleware on this task (e.g. internal
            # call). SET LOCAL scopes to the transaction.
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, false)", factory_id
                )
                row = await conn.fetchrow(query, factory_id)
    finally:
        reset_factory_id(token)

    total = (row["total"] if row else 0) or 0
    field_completeness: Dict[str, float] = {}
    if total > 0:
        for f in fields:
            cnt = row[f"cnt_{f}"] or 0
            field_completeness[f] = round(cnt / total * 100, 1)
    overall = (
        round(sum(field_completeness.values()) / len(field_completeness), 1)
        if field_completeness
        else 0.0
    )

    # Build metadata. Use _java_isoformat for any date values so Java parity
    # is preserved (Rule 11). For POS entities here Java is N/A but keeping
    # the helper future-proofs the response.
    metadata: Dict[str, Any] = {}
    if date_field and row is not None:
        # asyncpg.Record uses __getitem__ (string keys) — no .get() method.
        # Keys we requested are guaranteed in the row, so direct access is safe.
        min_d = row["min_date"]
        max_d = row["max_date"]
        metadata["min_date"] = _format_date_for_response(min_d)
        metadata["max_date"] = _format_date_for_response(max_d)
    if entity_type == "POS_ITEM" and row is not None:
        unique = row["unique_products"] or 0
        metadata["unique_products"] = int(unique)

    return CompletenessResult(
        entity_type=entity_type,
        total_records=int(total),
        field_completeness=field_completeness,
        overall_completeness=overall,
        metadata=metadata or None,
    )


def _format_date_for_response(value) -> Optional[str]:
    """Format a date/datetime for JSON response.

    Mirrors Rule 11 _java_isoformat semantics for future Java parity. For pure
    date values (asyncpg returns datetime.date for DATE columns) we emit ISO
    yyyy-mm-dd. For datetime values we drop trailing-zero microseconds.
    """
    if value is None:
        return None
    # Lazy import — avoid hard dep on smartbi_compat from manufacturing-only paths.
    try:
        from smartbi_compat.schema_compat import _java_isoformat
        return _java_isoformat(value)
    except ImportError:
        # Best effort: rely on str() which gives ISO-8601 for date/datetime.
        return str(value)


async def compute_completeness(factory_id: str, entity_types: Optional[List[str]] = None) -> List[CompletenessResult]:
    """Compute field-level null rates for specified entity types.

    Manufacturing entity types (PROCESSING_BATCH etc.) query cretas_db; POS
    entity types (POS_TRANSACTION / POS_ITEM) query smartbi_db silver facts.
    Both pools are managed independently so a missing smartbi config doesn't
    break manufacturing-only computation and vice-versa.
    """
    results: List[CompletenessResult] = []
    # Default to manufacturing entities only — POS entities must be opted in
    # explicitly by the caller (frontend selects them when tenant is restaurant)
    # so manufacturing tenants don't see spurious zero-record POS rows.
    types_to_check = entity_types or list(ENTITY_FIELD_MAP.keys())

    # Split entity types by datasource — manufacturing (cretas_db) vs POS (smartbi_db).
    manufacturing_types = [t for t in types_to_check if t in ENTITY_FIELD_MAP]
    pos_types = [t for t in types_to_check if t in POS_ENTITY_FIELD_MAP]

    # ── Manufacturing entities ─────────────────────────────────────────
    if manufacturing_types:
        pool = await _get_db_pool()
        try:
            async with pool.acquire() as conn:
                for entity_type in manufacturing_types:
                    config = ENTITY_FIELD_MAP[entity_type]
                    table = config["table"]
                    factory_col = config["factory_filter"]
                    fields = config["fields"]

                    # Build query: COUNT(*) and COUNT(field) for each field
                    count_exprs = ", ".join([f"COUNT({f}) as cnt_{f}" for f in fields])
                    query = f"""
                        SELECT COUNT(*) as total, {count_exprs}
                        FROM {table}
                        WHERE {factory_col} = $1 AND deleted_at IS NULL
                    """

                    row = await conn.fetchrow(query, factory_id)
                    total = row["total"] if row else 0

                    field_completeness = {}
                    if total > 0:
                        for f in fields:
                            cnt = row[f"cnt_{f}"]
                            field_completeness[f] = round(cnt / total * 100, 1)

                    overall = round(sum(field_completeness.values()) / len(field_completeness), 1) if field_completeness else 0  # noqa: E501

                    results.append(CompletenessResult(
                        entity_type=entity_type,
                        total_records=total,
                        field_completeness=field_completeness,
                        overall_completeness=overall
                    ))
        finally:
            await pool.close()

    # ── POS entities (smartbi_db) ───────────────────────────────────────
    # Each entity is wrapped in its own try/except so a missing fact table
    # (e.g. RLS denies, smartbi pool down) returns nothing for that entity
    # rather than failing the whole request.
    for entity_type in pos_types:
        try:
            result = await _compute_pos_entity(factory_id, entity_type)
            if result is not None:
                results.append(result)
        except Exception as exc:
            logger.warning(
                f"[completeness] POS entity {entity_type} failed for factory={factory_id}: {exc}"
            )

    return results


@router.post("/completeness/compute")
async def api_compute_completeness(req: CompletenessRequest, request: Request):
    """Compute data completeness for a factory."""
    factory_id = require_same_factory(request, req.factory_id)
    try:
        results = await compute_completeness(factory_id, req.entity_types)
        return {"success": True, "data": [r.dict() for r in results]}
    except Exception as e:
        logger.error(f"Completeness computation failed: {e}", exc_info=True)
        # ⛔ 不把原始异常文本回给客户端。这不是洁癖：本次生产 500 的 message 就是
        #    `invalid literal for int() with base 10: '<数据库密码的首段>'`
        #    —— 异常文本里裹着凭证，被原样返给了任何已认证调用方。
        #    详情留在服务端日志（上面这行 exc_info=True 已经记全）。
        raise HTTPException(status_code=500, detail="数据完整度计算失败，详见服务端日志")


@router.get("/completeness/trend")
async def api_completeness_trend(request: Request, factory_id: Optional[str] = None,
                                 entity_type: str = "PROCESSING_BATCH", months: int = 6):
    """Get completeness trend from snapshots table."""
    factory_id = require_same_factory(request, factory_id)
    pool = await _get_db_pool()
    try:
        async with pool.acquire() as conn:
            rows = await conn.fetch("""
                SELECT snapshot_date, overall_completeness, field_completeness
                FROM data_completeness_snapshots
                WHERE factory_id = $1 AND entity_type = $2
                ORDER BY snapshot_date DESC
                LIMIT $3
            """, factory_id, entity_type, months)

            data = [{
                "date": str(r["snapshot_date"]),
                "overall": r["overall_completeness"],
                "fields": json.loads(r["field_completeness"]) if r["field_completeness"] else {}
            } for r in rows]

            return {"success": True, "data": data}
    finally:
        await pool.close()
