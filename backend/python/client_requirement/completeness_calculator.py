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
import httpx
import os
import json
import logging

logger = logging.getLogger(__name__)

router = APIRouter()


class CompletenessResult(BaseModel):
    entity_type: str
    total_records: int
    field_completeness: Dict[str, float]  # field_name -> completion %
    overall_completeness: Optional[float] = None
    # Optional per-entity metadata (e.g. date range, unique counts) — kept
    # optional so manufacturing entities don't have to populate it.
    metadata: Optional[Dict[str, Any]] = None


class CompletenessRequest(BaseModel):
    # 工厂取登录身份; 传了就必须一致 (旧前端会传自己的工厂)
    factory_id: Optional[str] = None
    entity_types: Optional[List[str]] = None  # if None, compute all (manufacturing only)


# 联系人/电话: 写入口径不一 (供应商表单与客户 Excel 导入只写 contact_person/phone,
# 客户表单双写 contact_name/contact_phone), 任一列有非空白值就算「有」。
# 值是 SQL 表达式, 有值时非 NULL、缺失时 NULL; 只在本文件里写死, 不拼用户输入。
_CONTACT_EXPRS = {
    "contact": "COALESCE(NULLIF(BTRIM(contact_person), ''), NULLIF(BTRIM(contact_name), ''))",
    "phone": "COALESCE(NULLIF(BTRIM(phone), ''), NULLIF(BTRIM(contact_phone), ''))",
}

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
    },
    # ── 主数据 (G4): 物料 / 客户 / 供应商 —— 设置页「主数据：条数与缺口」的数据源 ──
    # text_fields: 文本列, 空串 / 全空白按「缺」算 (主数据里手填空格很常见, 只数 NOT NULL 会虚高)。
    "MATERIAL_TYPE": {
        "table": "raw_material_types",
        "factory_filter": "factory_id",
        "label_field": "name",
        "fields": ["code", "name", "category", "unit", "unit_price", "storage_type", "shelf_life_days", "tax_rate"],
        "text_fields": ["code", "name", "category", "unit", "storage_type", "tax_rate"],
    },
    "CUSTOMER": {
        "table": "customers",
        "factory_filter": "factory_id",
        "label_field": "name",
        "fields": ["code", "name", "type", "contact", "phone", "shipping_address",
                   "tax_number", "payment_terms", "credit_limit", "assigned_sales_user_id"],
        "text_fields": ["code", "name", "type", "shipping_address", "tax_number", "payment_terms"],
        "exprs": dict(_CONTACT_EXPRS),
    },
    "SUPPLIER": {
        "table": "suppliers",
        "factory_filter": "factory_id",
        "label_field": "name",
        "fields": ["code", "name", "contact", "phone", "address",
                   "tax_number", "payment_terms", "bank_name", "bank_account"],
        "text_fields": ["code", "name", "address", "tax_number", "payment_terms", "bank_name", "bank_account"],
        "exprs": dict(_CONTACT_EXPRS),
    },
}

MASTER_DATA_ENTITY_TYPES = ["MATERIAL_TYPE", "CUSTOMER", "SUPPLIER"]

# 主数据明细下钻 (id + 名称) 等同于吐名单, 判据必须和 Java 对应名册接口**同一个权限码**, 不在 Python 写死角色:
# 谁有这些权限由 Java 的 L2 工厂覆盖 → L1 角色权限 → 兜底三层配置决定 (工厂改了权限立即生效),
# Python 这里只负责把权限码交给 Java 判 (见 _java_role_has_any_permission)。
# 码与 Java 的对应关系 (tests/test_completeness_master_data_endpoint.py 会读 Java 源码核对, 改一边另一边必须跟):
#  * CUSTOMER      -> CustomerController 名单的 ROSTER_SALES_RW/R + ROSTER_FINANCE_RW/R
#  * SUPPLIER      -> SupplierController 导出的 @RequirePermission (供应商列表本身对所有人开放, 导出是最严的一档)
#  * MATERIAL_TYPE -> PermissionService.MATERIAL_TYPE_MAINTAIN_PERMISSION (原料类型维护)
MASTER_DATA_DRILLDOWN_PERMISSIONS = {
    "CUSTOMER": ["sales:read_write", "sales:read", "finance:read_write", "finance:read"],
    "SUPPLIER": ["procurement:read", "procurement:read_write", "finance:read", "finance:read_write"],
    "MATERIAL_TYPE": ["material_type:maintain"],
}

JAVA_API_BASE = os.getenv("JAVA_API_BASE", "http://localhost:10010")


async def _java_role_has_any_permission(factory_id: str, role: str, codes: List[str]) -> bool:
    """问 Java: 这个工厂里这个岗位有没有 codes 里任意一个权限 (Java 合并 L2/L1/兜底后回答)。

    Java 不可达 / 回错时抛 503, ⛔ 不当成「有权」放行, 也不退回写死的角色表。
    """
    headers = {"X-Internal-Key": os.getenv("INTERNAL_API_SECRET", "")}
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.get(
                f"{JAVA_API_BASE}/api/internal/permissions/role-check",
                params={"factoryId": factory_id, "role": role, "code": codes},
                headers=headers,
            )
        resp.raise_for_status()
        return bool(resp.json()["data"]["allowed"])
    except Exception as exc:  # noqa: BLE001 - 任何失败都按「判不了」处理
        logger.error(f"[completeness] 权限校验服务不可用: {exc}")
        raise HTTPException(status_code=503, detail="权限校验服务暂不可用, 请稍后重试")
# 默认只算原来的 5 个生产实体, 主数据由调用方显式要 (设置页), 不改变既有页面的返回行数。
DEFAULT_ENTITY_TYPES = [t for t in ENTITY_FIELD_MAP if t not in MASTER_DATA_ENTITY_TYPES]


def _count_expr(config: Dict[str, Any], field: str) -> str:
    """COUNT 表达式: 文本列把空白当缺失。field 只来自 ENTITY_FIELD_MAP 白名单, 不拼用户输入。"""
    expr = config.get("exprs", {}).get(field)
    if expr:
        return f"COUNT({expr}) as cnt_{field}"
    if field in config.get("text_fields", []):
        return f"COUNT(NULLIF(BTRIM({field}), '')) as cnt_{field}"
    return f"COUNT({field}) as cnt_{field}"


def build_completeness_query(entity_type: str) -> str:
    config = ENTITY_FIELD_MAP[entity_type]
    count_exprs = ", ".join(_count_expr(config, f) for f in config["fields"])
    return f"""
                        SELECT COUNT(*) as total, {count_exprs}
                        FROM {config["table"]}
                        WHERE {config["factory_filter"]} = $1 AND deleted_at IS NULL
                    """


def build_missing_query(entity_type: str, field: str, count: bool = False) -> str:
    """缺失记录明细查询: entity_type / field 必须在白名单内, 否则抛 ValueError (不拼任意列名)。"""
    config = ENTITY_FIELD_MAP.get(entity_type)
    if config is None:
        raise ValueError(f"unknown entity_type: {entity_type}")
    if field not in config["fields"]:
        raise ValueError(f"unknown field for {entity_type}: {field}")
    label = config.get("label_field")
    label_expr = f"{label}::text AS label" if label else "NULL::text AS label"
    expr = config.get("exprs", {}).get(field)
    if expr:
        missing = f"({expr}) IS NULL"
    elif field in config.get("text_fields", []):
        missing = f"({field} IS NULL OR BTRIM({field}) = '')"
    else:
        missing = f"{field} IS NULL"
    if count:
        return (f"SELECT COUNT(*) FROM {config['table']} "
                f"WHERE {config['factory_filter']} = $1 AND deleted_at IS NULL AND {missing}")
    return (f"SELECT id::text AS id, {label_expr} FROM {config['table']} "
            f"WHERE {config['factory_filter']} = $1 AND deleted_at IS NULL AND {missing} "
            f"ORDER BY id LIMIT $2")


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
    types_to_check = entity_types or DEFAULT_ENTITY_TYPES

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
                    fields = config["fields"]

                    # Build query: COUNT(*) and COUNT(field) for each field
                    query = build_completeness_query(entity_type)

                    row = await conn.fetchrow(query, factory_id)
                    total = row["total"] if row else 0

                    field_completeness = {}
                    if total > 0:
                        for f in fields:
                            cnt = row[f"cnt_{f}"]
                            field_completeness[f] = round(cnt / total * 100, 1)

                    # 没有记录 (total=0) 时没有可算的完整度: 主数据返回 None 以区别于「全部缺失」的 0%;
                    # 既有生产实体保持旧行为 (0)。
                    overall = (round(sum(field_completeness.values()) / len(field_completeness), 1)
                               if field_completeness
                               else (None if entity_type in MASTER_DATA_ENTITY_TYPES else 0))  # noqa: E501

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


class MissingRecordsRequest(BaseModel):
    factory_id: Optional[str] = None
    entity_type: str
    field: str
    limit: int = 50


@router.post("/completeness/missing")
async def api_missing_records(req: MissingRecordsRequest, request: Request):
    """缺失记录明细下钻 (G7): 返回某实体某字段缺失的记录 id + 名称, 最多 limit 条 (上限 200)。

    返回 total_missing 是真实缺失总数 (不是 limit 截断后的条数), 前端据此显示「还有 N 条」。
    """
    factory_id = require_same_factory(request, req.factory_id)
    limit = max(1, min(req.limit, 200))
    try:
        query = build_missing_query(req.entity_type, req.field)
    except ValueError:
        raise HTTPException(status_code=400, detail="不支持的实体类型或字段")
    # 主数据三类按权限码判 (在任何取库之前); 其余 (生产实体: 批次等) 沿用本厂可见性, 有意不加岗位门。
    codes = MASTER_DATA_DRILLDOWN_PERMISSIONS.get(req.entity_type)
    if codes is not None:
        role = str(getattr(request.state, "role", "") or "").lower()
        if not role or not await _java_role_has_any_permission(factory_id, role, codes):
            raise HTTPException(status_code=403, detail="当前岗位无权查看该名单的缺失明细")
    count_query = build_missing_query(req.entity_type, req.field, count=True)
    pool = await _get_db_pool()
    try:
        async with pool.acquire() as conn:
            rows = await conn.fetch(query, factory_id, limit)
            total = await conn.fetchval(count_query, factory_id)
        return {"success": True, "data": {
            "entity_type": req.entity_type, "field": req.field,
            "total_missing": int(total or 0),
            "records": [{"id": r["id"], "label": r["label"]} for r in rows],
        }}
    except Exception as e:
        logger.error(f"Completeness missing-records failed: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail="缺失记录查询失败，详见服务端日志")
    finally:
        await pool.close()


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
