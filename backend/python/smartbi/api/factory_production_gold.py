"""Factory Production Gold read endpoints (Phase 1 of Factory BI).

Reads from smartbi_db.agg_factory_batch_daily (Gold) built by
factory_production_etl.py.  All dates optional — omit for full history.

Endpoints (registered under /api/smartbi/factory prefix in main.py):
    GET /factory/cost-structure     — cost breakdown (material/labor/equip/other)
    GET /factory/yield-trend        — daily/weekly yield rate trend
    GET /factory/product-cost-compare — per-product cost comparison

RBAC:
    Cost fields (all NUMERIC cost columns) visible only to PRICE_VIEW_ROLES.
    Non-price roles get cost fields stripped to null (belt-and-suspenders via
    strip_price_for_role + explicit cost-key sweep, same pattern as
    restaurant_cost_card.py).

RLS:
    All queries set app.factory_id GUC inside a transaction so FORCE ROW LEVEL
    SECURITY on Gold tables is satisfied.

Honest empty-state:
    No Gold rows → success:true, data:[], message "暂无生产数据" (not 500).
    Missing factory context → success:false.
"""
from __future__ import annotations

import logging
from datetime import date
from typing import Any, List, Optional

from fastapi import APIRouter, Query, Request

from smartbi.services.factory_charts import cost_scope as process_secret
from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES, strip_price_for_role

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/factory", tags=["Factory Production Gold"])

# Cost field names in our response dicts (for explicit strip in addition to _MONEY_PATTERN)
_COST_KEYS: frozenset[str] = frozenset({
    "totalMaterialCost", "totalLaborCost", "totalEquipmentCost", "totalOtherCost",
    "totalCost", "materialCost", "laborCost", "equipmentCost", "otherCost",
    "costPerUnit",
})


# ── Internal helpers ──────────────────────────────────────────────────────────

def _get_factory_id(request: Request) -> Optional[str]:
    return getattr(request.state, "factory_id", None)


def _get_role(request: Request) -> Optional[str]:
    return getattr(request.state, "role", None)


def _can_view_cost(role: Optional[str]) -> bool:
    return bool(role and role in PRICE_VIEW_ROLES)


def _strip_cost_fields(node: Any) -> None:
    """Depth-first null of cost money keys the shared regex misses."""
    if isinstance(node, dict):
        for k in list(node.keys()):
            if k in _COST_KEYS and not isinstance(node[k], (dict, list)):
                node[k] = None
            else:
                _strip_cost_fields(node[k])
    elif isinstance(node, list):
        for item in node:
            _strip_cost_fields(item)


def _apply_rbac(data: Any, role: Optional[str]) -> Any:
    """Apply RBAC cost stripping if role is not price-view-capable."""
    if _can_view_cost(role):
        return data
    strip_price_for_role(data, role)
    _strip_cost_fields(data)
    return data


# ── Process-secret (工艺保密) cost scope ──────────────────────────────────────
#
# Java decides which products' manufacturing costs this caller may see
# (smartbi/services/factory_charts/cost_scope.py).  Per-product cost fields of
# a protected product the caller may not see are hidden; factory-wide rollups
# are rebuilt by SUMMING ONLY the allowed products' per-product Gold rows.
# ⛔ Never "rollup − secret rows": two published numbers would let anyone back
# out the secret part.  production_batches.product_type_id is NOT NULL, so the
# all-product rollup is exactly the sum of the per-product rows (ETL
# _AGG_PRODUCT_DAILY_SQL / _AGG_ALL_DAILY_SQL read the same fact rows).

_PRODUCT_COST_FIELDS: tuple[str, ...] = (
    "totalMaterialCost", "totalLaborCost", "totalEquipmentCost", "totalOtherCost",
    "totalCost", "costPerUnit",
)
_ROLLUP_COST_COLUMNS: dict[str, str] = {
    "totalMaterialCost":  "total_material_cost",
    "totalLaborCost":     "total_labor_cost",
    "totalEquipmentCost": "total_equipment_cost",
    "totalOtherCost":     "total_other_cost",
    "totalCost":          "total_cost",
}
_PROCESS_COST_FIELDS: tuple[str, ...] = ("laborCost", "materialCost")


def _hide_fields(item: dict, fields, scope) -> None:
    for name in fields:
        item[name] = None
    item[process_secret.MARKER] = process_secret.hidden_marker(fields, scope)


async def _scope_rollup_costs(
    request: Request,
    factory_id: str,
    start_date: Optional[date],
    end_date: Optional[date],
    items: List[dict],
    fields: tuple[str, ...],
) -> Optional[dict]:
    """Make the factory-rollup cost fields of ``items`` safe for this caller.

    Returns the ``costScope`` annotation (``None`` when nothing was left out).
    FULL callers keep the Gold rollup values unchanged; SCOPED callers get each
    day's value rebuilt from the allowed products' rows only; when the
    authority (or the per-product read) is unavailable every cost is hidden.
    """
    product_rows, err = await _query_gold(factory_id, start_date, end_date)
    if err or product_rows is None:
        scope = process_secret.unavailable()
        product_rows = []
    else:
        scope = await process_secret.resolve_cost_scope(
            request, factory_id, [r["product_type_id"] for r in product_rows])
    if scope.is_full:
        return None
    if scope.is_unavailable:
        for item in items:
            for name in fields:
                item[name] = None
        return process_secret.rollup_annotation(scope, 0)
    excluded = scope.excluded_product_ids(r["product_type_id"] for r in product_rows)
    if not excluded:
        return None
    allowed_by_day: dict[Optional[str], list] = {}
    for row in product_rows:
        if not scope.allows(row["product_type_id"]):
            continue
        day = row["stat_date"].isoformat() if row["stat_date"] else None
        allowed_by_day.setdefault(day, []).append(row)
    for item in items:
        day_rows = allowed_by_day.get(item.get("statDate"), [])
        for name in fields:
            column = _ROLLUP_COST_COLUMNS[name]
            values = [float(r[column]) for r in day_rows if r[column] is not None]
            # Honest null: a day without any allowed costed row stays null.
            item[name] = round(sum(values), 2) if values else None
    return process_secret.rollup_annotation(scope, len(excluded))


async def _query_gold(
    factory_id: str,
    start_date: Optional[date],
    end_date: Optional[date],
    product_type_id: Optional[str] = None,
    all_products_only: bool = False,
):
    """Shared query helper for agg_factory_batch_daily.

    all_products_only=True → only NULL product_type_id rows (daily factory total).
    product_type_id set   → only that product's per-day rows.
    both None             → all rows (per-product).
    """
    from smartbi.config import get_pg_pool
    pool = await get_pg_pool()
    if pool is None:
        return None, "db pool unavailable"

    conditions = ["factory_id = $1"]
    params: list = [factory_id]
    idx = 2

    if start_date is not None:
        conditions.append(f"stat_date >= ${idx}")
        params.append(start_date)
        idx += 1

    if end_date is not None:
        conditions.append(f"stat_date <= ${idx}")
        params.append(end_date)
        idx += 1

    if all_products_only:
        conditions.append("product_type_id IS NULL")
    elif product_type_id is not None:
        conditions.append(f"product_type_id = ${idx}")
        params.append(product_type_id)
        idx += 1
    else:
        conditions.append("product_type_id IS NOT NULL")

    where_clause = " AND ".join(conditions)

    try:
        async with pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, false)", factory_id
                )
                rows = await conn.fetch(
                    f"""
                    SELECT
                        factory_id, stat_date, product_type_id,
                        batch_count,
                        total_planned_qty, total_actual_qty, total_good_qty,
                        avg_yield_rate,
                        total_material_cost, total_labor_cost,
                        total_equipment_cost, total_other_cost, total_cost,
                        version, updated_at
                    FROM agg_factory_batch_daily
                    WHERE {where_clause}
                    ORDER BY stat_date, product_type_id NULLS FIRST
                    """,
                    *params,
                )
        return rows, None
    except Exception as exc:
        logger.warning("[factory-gold] query failed for %s: %s", factory_id, exc)
        return None, str(exc)


def _row_to_dict(row) -> dict:
    """Convert asyncpg Row to camelCase dict with honest null preservation."""
    return {
        "factoryId":           row["factory_id"],
        "statDate":            row["stat_date"].isoformat() if row["stat_date"] else None,
        "productTypeId":       row["product_type_id"],
        "batchCount":          row["batch_count"],
        "totalPlannedQty":     float(row["total_planned_qty"]) if row["total_planned_qty"] is not None else None,
        "totalActualQty":      float(row["total_actual_qty"]) if row["total_actual_qty"] is not None else None,
        "totalGoodQty":        float(row["total_good_qty"]) if row["total_good_qty"] is not None else None,
        "avgYieldRate":        float(row["avg_yield_rate"]) if row["avg_yield_rate"] is not None else None,
        "totalMaterialCost":   float(row["total_material_cost"]) if row["total_material_cost"] is not None else None,
        "totalLaborCost":      float(row["total_labor_cost"]) if row["total_labor_cost"] is not None else None,
        "totalEquipmentCost":  float(row["total_equipment_cost"]) if row["total_equipment_cost"] is not None else None,
        "totalOtherCost":      float(row["total_other_cost"]) if row["total_other_cost"] is not None else None,
        "totalCost":           float(row["total_cost"]) if row["total_cost"] is not None else None,
        "version":             row["version"],
        "updatedAt":           row["updated_at"].isoformat() if row["updated_at"] else None,
    }


# ── Endpoints ─────────────────────────────────────────────────────────────────

@router.get("/cost-structure")
async def cost_structure(
    request: Request,
    startDate: Optional[date] = Query(None, description="起始日期 (默认: 全部历史)"),
    endDate:   Optional[date] = Query(None, description="截止日期 (默认: 全部历史)"),
):
    """Factory production cost breakdown by component (material / labor / equipment / other).

    Returns all-product daily rollup rows (product_type_id = NULL) aggregated
    over the requested date range.  Cost fields are null for non-price roles.

    Response:
        {
            success: bool,
            data: {
                rows: [...],            // per-day cost structure
                summary: {
                    totalMaterialCost, totalLaborCost, totalEquipmentCost,
                    totalOtherCost, totalCost,
                    batchCount, totalActualQty
                }
            },
            message: str
        }
    """
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "data": None, "message": "missing factory context"}

    role = _get_role(request)

    rows, err = await _query_gold(factory_id, startDate, endDate, all_products_only=True)
    if err:
        return {"success": False, "data": None, "message": err}
    if not rows:
        return {"success": True, "data": {"rows": [], "summary": {}}, "message": "暂无生产数据"}

    items = [_row_to_dict(r) for r in rows]
    cost_scope = None
    if _can_view_cost(role):
        # Rebuild per-day costs BEFORE the summary so the summary is the sum of
        # the caller's visible (allowed-products-only) days.
        cost_scope = await _scope_rollup_costs(
            request, factory_id, startDate, endDate, items, tuple(_ROLLUP_COST_COLUMNS))

    # Summary aggregation (honest null: only sum non-null slices)
    def _sum_field(key: str) -> Optional[float]:
        vals = [item[key] for item in items if item[key] is not None]
        return round(sum(vals), 2) if vals else None

    summary = {
        "totalMaterialCost":  _sum_field("totalMaterialCost"),
        "totalLaborCost":     _sum_field("totalLaborCost"),
        "totalEquipmentCost": _sum_field("totalEquipmentCost"),
        "totalOtherCost":     _sum_field("totalOtherCost"),
        "totalCost":          _sum_field("totalCost"),
        "batchCount":         sum(item["batchCount"] for item in items),
        "totalActualQty":     _sum_field("totalActualQty"),
    }

    data = {"rows": items, "summary": summary}
    if cost_scope is not None:
        data["costScope"] = cost_scope
    _apply_rbac(data, role)
    return {"success": True, "data": data, "message": ""}


@router.get("/yield-trend")
async def yield_trend(
    request: Request,
    startDate:    Optional[date] = Query(None, description="起始日期 (默认: 全部历史)"),
    endDate:      Optional[date] = Query(None, description="截止日期 (默认: 全部历史)"),
    productTypeId: Optional[str] = Query(None, description="产品类型 ID (不传=全产品汇总)"),
):
    """Daily yield-rate trend for the factory (or a specific product type).

    Yield = good_qty / actual_qty × 100 (%).
    When productTypeId omitted, returns all-product daily rollup (avg across products).
    Cost fields included but stripped for non-price roles.
    """
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "data": None, "message": "missing factory context"}

    role = _get_role(request)

    if productTypeId:
        rows, err = await _query_gold(factory_id, startDate, endDate, product_type_id=productTypeId)
    else:
        rows, err = await _query_gold(factory_id, startDate, endDate, all_products_only=True)

    if err:
        return {"success": False, "data": None, "message": err}
    if not rows:
        return {"success": True, "data": {"trend": []}, "message": "暂无生产数据"}

    trend = [
        {
            "statDate":      r["stat_date"].isoformat() if r["stat_date"] else None,
            "avgYieldRate":  float(r["avg_yield_rate"]) if r["avg_yield_rate"] is not None else None,
            "batchCount":    r["batch_count"],
            "totalActualQty": float(r["total_actual_qty"]) if r["total_actual_qty"] is not None else None,
            "totalGoodQty":   float(r["total_good_qty"]) if r["total_good_qty"] is not None else None,
            "totalCost":      float(r["total_cost"]) if r["total_cost"] is not None else None,
        }
        for r in rows
    ]

    data = {"trend": trend, "productTypeId": productTypeId}
    if _can_view_cost(role):
        if productTypeId:
            scope = await process_secret.resolve_cost_scope(request, factory_id, [productTypeId])
            if not scope.allows(productTypeId):
                for point in trend:
                    point["totalCost"] = None
                data[process_secret.MARKER] = process_secret.hidden_marker(("totalCost",), scope)
        else:
            cost_scope = await _scope_rollup_costs(
                request, factory_id, startDate, endDate, trend, ("totalCost",))
            if cost_scope is not None:
                data["costScope"] = cost_scope
    _apply_rbac(data, role)
    return {"success": True, "data": data, "message": ""}


@router.get("/product-cost-compare")
async def product_cost_compare(
    request: Request,
    startDate: Optional[date] = Query(None, description="起始日期 (默认: 全部历史)"),
    endDate:   Optional[date] = Query(None, description="截止日期 (默认: 全部历史)"),
):
    """Per-product-type cost comparison aggregated over the requested period.

    Returns one row per product_type_id with summed costs and average yield.
    Sorted by total_cost DESC (highest cost products first).
    Cost fields stripped for non-price roles.

    Response:
        {
            success: bool,
            data: {
                products: [{
                    productTypeId, batchCount,
                    totalActualQty, totalGoodQty, avgYieldRate,
                    totalMaterialCost, totalLaborCost, totalEquipmentCost,
                    totalOtherCost, totalCost,
                    costPerUnit   // totalCost / totalActualQty (null if qty=0 or costs null)
                }]
            }
        }
    """
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "data": None, "message": "missing factory context"}

    role = _get_role(request)

    rows, err = await _query_gold(factory_id, startDate, endDate)
    if err:
        return {"success": False, "data": None, "message": err}
    if not rows:
        return {"success": True, "data": {"products": []}, "message": "暂无生产数据"}

    # Aggregate across days per product_type_id
    from collections import defaultdict
    agg: dict = defaultdict(lambda: {
        "batchCount": 0,
        "totalPlannedQty": None, "totalActualQty": None, "totalGoodQty": None,
        "yieldRateNumerator": 0.0, "yieldRateDenominator": 0,
        "totalMaterialCost": None, "totalLaborCost": None,
        "totalEquipmentCost": None, "totalOtherCost": None, "totalCost": None,
    })

    def _null_add(a, b):
        """Add two nullable numbers: None+None=None, None+x=x, x+y=x+y."""
        if a is None and b is None:
            return None
        return (a or 0.0) + (b or 0.0)

    for row in rows:
        pid = row["product_type_id"]
        if pid is None:
            continue  # skip all-products rollup rows
        bucket = agg[pid]
        bucket["batchCount"] += row["batch_count"]
        bucket["totalPlannedQty"] = _null_add(bucket["totalPlannedQty"],    float(
            row["total_planned_qty"]) if row["total_planned_qty"] is not None else None)
        bucket["totalActualQty"] = _null_add(bucket["totalActualQty"],     float(
            row["total_actual_qty"]) if row["total_actual_qty"] is not None else None)
        bucket["totalGoodQty"] = _null_add(bucket["totalGoodQty"],       float(
            row["total_good_qty"]) if row["total_good_qty"] is not None else None)
        bucket["totalMaterialCost"] = _null_add(bucket["totalMaterialCost"],  float(
            row["total_material_cost"]) if row["total_material_cost"] is not None else None)
        bucket["totalLaborCost"] = _null_add(bucket["totalLaborCost"],     float(
            row["total_labor_cost"]) if row["total_labor_cost"] is not None else None)
        bucket["totalEquipmentCost"] = _null_add(bucket["totalEquipmentCost"], float(
            row["total_equipment_cost"]) if row["total_equipment_cost"] is not None else None)
        bucket["totalOtherCost"] = _null_add(bucket["totalOtherCost"],     float(
            row["total_other_cost"]) if row["total_other_cost"] is not None else None)
        bucket["totalCost"] = _null_add(bucket["totalCost"],          float(
            row["total_cost"]) if row["total_cost"] is not None else None)
        # Weighted yield: weight by batch_count
        if row["avg_yield_rate"] is not None:
            bucket["yieldRateNumerator"] += float(row["avg_yield_rate"]) * row["batch_count"]
            bucket["yieldRateDenominator"] += row["batch_count"]

    products: List[dict] = []
    for pid, bucket in agg.items():
        avg_yield = (
            round(bucket["yieldRateNumerator"] / bucket["yieldRateDenominator"], 2)
            if bucket["yieldRateDenominator"] > 0 else None
        )
        cost_per_unit: Optional[float] = None
        if bucket["totalCost"] is not None and bucket["totalActualQty"] and bucket["totalActualQty"] > 0:
            cost_per_unit = round(bucket["totalCost"] / bucket["totalActualQty"], 4)

        products.append({
            "productTypeId":     pid,
            "batchCount":        bucket["batchCount"],
            "totalActualQty":    round(bucket["totalActualQty"],  2) if bucket["totalActualQty"] is not None else None,
            "totalGoodQty":      round(bucket["totalGoodQty"],    2) if bucket["totalGoodQty"] is not None else None,
            "avgYieldRate":      avg_yield,
            "totalMaterialCost": round(bucket["totalMaterialCost"],  2) if bucket["totalMaterialCost"] is not None else None,  # noqa: E501
            "totalLaborCost":    round(bucket["totalLaborCost"],     2) if bucket["totalLaborCost"] is not None else None,  # noqa: E501
            "totalEquipmentCost": round(bucket["totalEquipmentCost"], 2) if bucket["totalEquipmentCost"] is not None else None,  # noqa: E501
            "totalOtherCost":    round(bucket["totalOtherCost"],     2) if bucket["totalOtherCost"] is not None else None,  # noqa: E501
            "totalCost":         round(bucket["totalCost"],          2) if bucket["totalCost"] is not None else None,
            "costPerUnit":       cost_per_unit,
        })

    hidden: List[dict] = []
    if _can_view_cost(role):
        scope = await process_secret.resolve_cost_scope(
            request, factory_id, [p["productTypeId"] for p in products])
        if not scope.is_full:
            visible = []
            for product in products:
                if scope.allows(product["productTypeId"]):
                    visible.append(product)
                else:
                    _hide_fields(product, _PRODUCT_COST_FIELDS, scope)
                    hidden.append(product)
            products = visible

    # Sort by totalCost DESC (nulls last).  Hidden products go after every
    # visible one in a cost-independent order, so their position does not
    # reveal how their (hidden) cost ranks.
    products.sort(key=lambda p: p["totalCost"] if p["totalCost"] is not None else -1, reverse=True)
    hidden.sort(key=lambda p: str(p["productTypeId"]))
    products = products + hidden

    data = {"products": products}
    _apply_rbac(data, role)
    return {"success": True, "data": data, "message": ""}


@router.get("/process-cost")
async def process_cost(
    request: Request,
    batchId:   Optional[str] = Query(None, description="生产批次 ID (不传=全厂所有批次)"),
    startDate: Optional[date] = Query(None, description="起始日期 (默认: 全部历史)"),
    endDate:   Optional[date] = Query(None, description="截止日期 (默认: 全部历史)"),
    costOnly:  bool = Query(False, description="仅返回有成本数据的工序 (cost_source='reported')"),
):
    """Process-level cost and yield for factory production reports.

    Source: fact_production_report (Silver — grain: one row per production_reports YIELD).

    Honesty: ~90% of prod reports have NULL labor_cost / material_cost.
    - costOnly=false (default): returns all reports; cost fields are null when unreported.
    - costOnly=true: filters cost_source = 'reported' rows only (the ~10% with real data).

    Response:
        {
            success: bool,
            data: {
                reports: [
                    {
                        sourcePk, batchId, processOrder, processCategory,
                        reportDate, reportKind,
                        inputQty, outputQty, goodQty, yieldRate,
                        totalWorkMinutes, totalWorkers,
                        laborCost,    // null when not reported
                        materialCost, // null when not reported
                        costSource,   // 'reported' or null
                    }
                ],
                meta: {
                    totalReports, reportsWithCost, costCoverageRate
                }
            }
        }

    Cost fields are stripped to null for non-price roles.
    """
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "data": None, "message": "missing factory context"}

    role = _get_role(request)

    from smartbi.config import get_pg_pool
    pool = await get_pg_pool()
    if pool is None:
        return {"success": False, "data": None, "message": "db pool unavailable"}

    conditions = ["r.factory_id = $1"]
    params: list = [factory_id]
    idx = 2

    if batchId is not None:
        conditions.append(f"r.batch_id = ${idx}")
        params.append(batchId)
        idx += 1

    if startDate is not None:
        conditions.append(f"r.report_date >= ${idx}")
        params.append(startDate)
        idx += 1

    if endDate is not None:
        conditions.append(f"r.report_date <= ${idx}")
        params.append(endDate)
        idx += 1

    if costOnly:
        conditions.append("r.cost_source = 'reported'")

    where_clause = " AND ".join(conditions)

    try:
        async with pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, false)", factory_id
                )
                rows = await conn.fetch(
                    f"""
                    SELECT
                        r.source_pk, r.batch_id, r.process_order, r.process_category,
                        r.report_date, r.report_kind,
                        r.input_qty, r.output_qty, r.good_qty, r.yield_rate,
                        r.total_work_minutes, r.total_workers,
                        r.labor_cost, r.material_cost, r.cost_source,
                        b.product_type_id AS product_type_id
                    FROM fact_production_report r
                    LEFT JOIN fact_production_batch b
                      ON b.factory_id = r.factory_id AND b.source_pk = r.batch_id
                    WHERE {where_clause}
                    ORDER BY r.report_date, r.batch_id NULLS LAST, r.process_order NULLS LAST
                    """,
                    *params,
                )
    except Exception as exc:
        logger.warning("[factory-gold] process-cost query failed for %s: %s", factory_id, exc)
        return {"success": False, "data": None, "message": str(exc)}

    if not rows:
        return {
            "success": True,
            "data": {"reports": [], "meta": {"totalReports": 0, "reportsWithCost": 0, "costCoverageRate": None}},
            "message": "暂无工序报工数据",
        }

    reports = []
    for r in rows:
        reports.append({
            "sourcePk":          r["source_pk"],
            "batchId":           r["batch_id"],
            "processOrder":      r["process_order"],
            "processCategory":   r["process_category"],
            "reportDate":        r["report_date"].isoformat() if r["report_date"] else None,
            "reportKind":        r["report_kind"],
            "inputQty":          float(r["input_qty"]) if r["input_qty"] is not None else None,
            "outputQty":         float(r["output_qty"]) if r["output_qty"] is not None else None,
            "goodQty":           float(r["good_qty"]) if r["good_qty"] is not None else None,
            "yieldRate":         float(r["yield_rate"]) if r["yield_rate"] is not None else None,
            "totalWorkMinutes":  r["total_work_minutes"],
            "totalWorkers":      r["total_workers"],
            "laborCost":         float(r["labor_cost"]) if r["labor_cost"] is not None else None,
            "materialCost":      float(r["material_cost"]) if r["material_cost"] is not None else None,
            "costSource":        r["cost_source"],
        })

    hidden_count = 0
    cost_scope = None
    if _can_view_cost(role):
        product_ids = [r["product_type_id"] for r in rows]
        scope = await process_secret.resolve_cost_scope(request, factory_id, product_ids)
        if not scope.is_full:
            for row, report in zip(rows, reports):
                # A report whose batch is not in fact_production_batch (e.g. not
                # completed yet) has no provable product: fail closed, its cost
                # could belong to a protected product.
                pid = row["product_type_id"]
                if pid is None or not scope.allows(pid):
                    _hide_fields(report, ("laborCost", "materialCost", "costSource"), scope)
                    hidden_count += 1
            excluded = scope.excluded_product_ids(product_ids)
            cost_scope = process_secret.rollup_annotation(scope, len(excluded))
            if cost_scope is None and hidden_count:
                cost_scope = {"status": "ALLOWED_PRODUCTS_ONLY", "excludedProtectedProductCount": 0,
                              "note": f"{hidden_count} 条报工无法确认所属产品，成本已隐藏"}

    total = len(reports)
    # Coverage is computed over the reports whose cost this caller can see, so
    # hidden (protected-product) reports neither count nor dilute the rate.
    visible_total = total - hidden_count
    with_cost = sum(1 for rep in reports if rep["costSource"] == "reported")
    coverage = round(with_cost / visible_total * 100, 2) if visible_total > 0 else None

    meta = {
        "totalReports":      total,
        "reportsWithCost":   with_cost,
        "costCoverageRate":  coverage,
    }
    if hidden_count:
        meta["hiddenCostReports"] = hidden_count
    data = {"reports": reports, "meta": meta}
    if cost_scope is not None:
        data["costScope"] = cost_scope
    _apply_rbac(data, role)
    return {"success": True, "data": data, "message": ""}


# ── T6 Phase-2: Fused timeseries (production domain) ─────────────────────────


_VALID_FUSED_DOMAINS: frozenset[str] = frozenset({"production", "sales", "purchase", "inventory"})


@router.get("/production-fused")
async def production_fused(
    request: Request,
    start:  Optional[str] = Query(None, description="起始日期 YYYY-MM-DD (默认: 一年前)"),
    end:    Optional[str] = Query(None, description="截止日期 YYYY-MM-DD (默认: 今天)"),
    domain: str           = Query("production", description="时序域: production / sales / purchase / inventory"),
) -> Any:
    """Fused timeseries via resolver (T6 Phase-2), extended to all domains.

    Calls ``query_timeseries(factory_id, domain=domain, start, end)`` and
    returns the raw resolver rows.  Fail-open: empty list when resolver
    returns no data or on any internal error.

    ``domain`` defaults to ``"production"`` so existing callers that omit
    the parameter continue to work unchanged (backward-compatible).

    Valid values: ``production``, ``sales``, ``purchase``, ``inventory``.
    Any other value returns HTTP 400.

    Response shape::

        {
            "success": true,
            "data": [
                {
                    "period":          "2026-01",
                    "canonical_field": "total_actual_qty",
                    "value_num":       1234.5,
                    "dims":            {}
                },
                ...
            ],
            "message": ""
        }

    Auth: JWT required (factory_id from JWT claims via auth middleware).
    """
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "data": None, "message": "missing factory context"}

    if domain not in _VALID_FUSED_DOMAINS:
        from fastapi.responses import JSONResponse  # noqa: PLC0415
        return JSONResponse(
            status_code=400,
            content={
                "success": False,
                "data": None,
                "message": f"invalid domain '{domain}'; valid values: {sorted(_VALID_FUSED_DOMAINS)}",
            },
        )

    # Default date window: [today-1year, today]
    from datetime import datetime  # noqa: PLC0415
    today = datetime.utcnow().date()
    default_start = today.replace(year=today.year - 1)

    start_str = start or default_start.isoformat()
    end_str = end or today.isoformat()

    try:
        from smartbi.services.timeseries_resolver import query_timeseries  # noqa: PLC0415
        rows = await query_timeseries(
            factory_id,
            domain=domain,
            start=start_str,
            end=end_str,
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning(
            "[production-fused] resolver failed factory=%s domain=%s: %s",
            factory_id, domain, exc,
        )
        rows = []

    return {"success": True, "data": rows, "message": ""}
