"""Tenant-scoped operational sources for the factory chart catalogue.

This module deliberately stays separate from ``sources.py``.  The latter reads
the existing fact snapshots; these adapters read the Java application's
authoritative operational rows that are needed for SKU reconciliation,
department snapshots, and WIP lineage.  No costs are inferred from WIP rows.
"""
from __future__ import annotations

import asyncio
import calendar
from collections import defaultdict
from datetime import date
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from typing import Any, Iterable

import pandas as pd

import smartbi.config as config
from .engine import Dataset
from .wip_plan_flow import WIP_PLAN_FLOW_SQL, assemble_wip_plan_flows


_CENT = Decimal("0.01")
_EXCLUDED_STATUSES = ("CANCELLED", "DRAFT", "FINANCE_REJECTED")
_SALES_LINE_ID = "订单行ID"

# Required columns are intentionally explicit.  They mirror SalesOrderItem and
# SalesOrder; the quote column is selected only as provenance and is never used
# for the untaxed SKU amount (Java uses it only in getLineAmountWithTax()).
SALES_SKU_SQL = """
SELECT i.id AS "订单行ID", so.id AS "订单ID", so.order_date AS "日期",
       i.product_type_id AS "产品ID",
       COALESCE(NULLIF(BTRIM(pt.name), ''), NULLIF(BTRIM(i.product_name), '')) AS "产品",
       i.unit AS "单位",
       i.quantity AS "数量", i.unit_price AS "单价",
       COALESCE(i.discount_rate, 0) AS "折扣率",
       i.quoted_gross_unit_price AS "报价含税单价", so.total_amount AS "订单总额"
FROM sales_order_items i
JOIN sales_orders so ON so.id = i.sales_order_id AND so.factory_id = $1
LEFT JOIN product_types pt
  ON pt.id = i.product_type_id AND pt.factory_id = so.factory_id AND pt.deleted_at IS NULL
WHERE so.factory_id = $1 AND so.deleted_at IS NULL AND i.deleted_at IS NULL
  AND so.status NOT IN ('CANCELLED', 'DRAFT', 'FINANCE_REJECTED')
  AND ($2::date IS NULL OR so.order_date >= $2::date)
  AND ($3::date IS NULL OR so.order_date < ($3::date + INTERVAL '1 day'))
ORDER BY so.order_date, so.id, i.id
LIMIT 10001
"""

# A non-price role can still see operational SKU quantities and units.  It does
# not receive any price/discount/order-total column from this adapter.
SALES_SKU_NO_PRICE_SQL = """
SELECT i.id AS "订单行ID", so.id AS "订单ID", so.order_date AS "日期",
       i.product_type_id AS "产品ID",
       COALESCE(NULLIF(BTRIM(pt.name), ''), NULLIF(BTRIM(i.product_name), '')) AS "产品",
       i.unit AS "单位",
       i.quantity AS "数量"
FROM sales_order_items i
JOIN sales_orders so ON so.id = i.sales_order_id AND so.factory_id = $1
LEFT JOIN product_types pt
  ON pt.id = i.product_type_id AND pt.factory_id = so.factory_id AND pt.deleted_at IS NULL
WHERE so.factory_id = $1 AND so.deleted_at IS NULL AND i.deleted_at IS NULL
  AND so.status NOT IN ('CANCELLED', 'DRAFT', 'FINANCE_REJECTED')
  AND ($2::date IS NULL OR so.order_date >= $2::date)
  AND ($3::date IS NULL OR so.order_date < ($3::date + INTERVAL '1 day'))
ORDER BY so.order_date, so.id, i.id
LIMIT 10001
"""

# These are imported SmartBI snapshots.  The import path documents the amounts
# as yuan (see data_sync._extract_department), so the dataset may advertise CNY
# when price access is allowed.  No attribution to an operational department is
# inferred from production or sales rows.
DEPARTMENT_SQL = """
SELECT record_date AS "日期", department AS "部门", department_id AS "部门ID",
       headcount AS "人数", sales_amount AS "销售金额", sales_target AS "销售目标",
       cost_amount AS "部门成本"
FROM smart_bi_department_data
WHERE factory_id = $1 AND deleted_at IS NULL
  AND ($2::date IS NULL OR record_date >= $2::date)
  AND ($3::date IS NULL OR record_date < ($3::date + INTERVAL '1 day'))
ORDER BY record_date, department, id
LIMIT 10001
"""

DEPARTMENT_NO_PRICE_SQL = """
SELECT record_date AS "日期", department AS "部门", department_id AS "部门ID",
       headcount AS "人数"
FROM smart_bi_department_data
WHERE factory_id = $1 AND deleted_at IS NULL
  AND ($2::date IS NULL OR record_date >= $2::date)
  AND ($3::date IS NULL OR record_date < ($3::date + INTERVAL '1 day'))
ORDER BY record_date, department, id
LIMIT 10001
"""

# ProductionReport is an operational fact table, not a cost allocation table.
WIP_REPORT_SQL = """
SELECT pr.id AS "报工ID", pr.factory_id AS "__factory_id", pr.report_date AS "日期", pr.batch_id AS "批次ID",
       pr.process_category AS "工序", pr.process_order AS "工序序",
       pr.product_type_id AS "产品ID", pr.product_name AS "产品",
       pr.input_quantity AS "投入量", pr.input_unit AS "投入单位",
       pr.output_quantity AS "产出量", pr.output_unit AS "产出单位",
       pr.source_wip_no AS "来源WIP号", pr.intermediate_batch_no AS "工序批次号",
       pr.semi_output_quantity AS "半成品产出量",
       pr.semi_output_unit AS "半成品产出单位", pr.semi_code AS "半成品编码"
FROM production_reports pr
WHERE pr.factory_id = $1
  AND pr.deleted_at IS NULL
  AND pr.report_type = 'YIELD'
  AND pr.status IN ('SUBMITTED', 'APPROVED')
  AND ($2::date IS NULL OR pr.report_date >= $2::date)
  AND ($3::date IS NULL OR pr.report_date < ($3::date + INTERVAL '1 day'))
ORDER BY pr.report_date, pr.id
LIMIT 10001
"""

WIP_INVENTORY_SQL = """
SELECT sfi.id AS "WIP库存ID", sfi.factory_id AS "__factory_id", sfi.intermediate_batch_no AS "工序批次号",
       sfi.batch_id AS "批次ID", sfi.source_work_process_task_id AS "来源工序任务ID",
       sfi.process_order AS "工序序", sfi.product_type_id AS "产品ID",
       sfi.produced_quantity AS "累计产出量", sfi.consumed_quantity AS "累计领用量",
       sfi.available_quantity AS "可用量", sfi.unit AS "单位", sfi.status AS "状态"
FROM semi_finished_inventory sfi
WHERE sfi.factory_id = $1 AND sfi.deleted_at IS NULL
  AND sfi.status IN ('AVAILABLE', 'DEPLETED', 'RETURNED')
ORDER BY sfi.id
LIMIT 10001
"""

WIP_TRANSACTION_SQL = """
SELECT tx.id AS "WIP流水ID", tx.factory_id AS "__factory_id", tx.semi_finished_id AS "WIP库存ID",
       tx.txn_type AS "流水类型", tx.source_type AS "来源类型",
       tx.source_ref AS "来源引用", tx.quantity AS "流水数量",
       sfi.intermediate_batch_no AS "工序批次号", sfi.unit AS "单位",
       tx.report_id AS "报工ID", tx.created_at AS "发生时间"
FROM semi_finished_inventory_transactions tx
LEFT JOIN semi_finished_inventory sfi
  ON sfi.id = tx.semi_finished_id AND sfi.factory_id = tx.factory_id
 AND sfi.deleted_at IS NULL
WHERE tx.factory_id = $1
  AND ($2::date IS NULL OR tx.created_at >= $2::date)
  AND ($3::date IS NULL OR tx.created_at < ($3::date + INTERVAL '1 day'))
ORDER BY tx.created_at, tx.id
LIMIT 10001
"""


def _dates(request: Any) -> tuple[date | None, date | None]:
    if request.period_type == "all":
        return None, None
    return (
        date(request.year, request.start_month, 1),
        date(request.year, request.end_month, calendar.monthrange(request.year, request.end_month)[1]),
    )


def _decimal(value: Any) -> Decimal | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        result = Decimal(str(value))
    except (InvalidOperation, ValueError, TypeError):
        return None
    return result if result.is_finite() else None


def _java_line_amount(quantity: Any, unit_price: Any, discount_rate: Any) -> Decimal | None:
    quantity_d = _decimal(quantity)
    price_d = _decimal(unit_price)
    discount_d = _decimal(discount_rate)
    if quantity_d is None or price_d is None or discount_d is None:
        return None
    if quantity_d <= 0 or price_d < 0 or discount_d < 0 or discount_d > 100:
        return None
    amount = (quantity_d * price_d).quantize(_CENT, rounding=ROUND_HALF_UP)
    if discount_d > 0:
        amount = (amount * (Decimal(1) - discount_d / Decimal(100))).quantize(
            _CENT, rounding=ROUND_HALF_UP
        )
    return amount


def reconcile_sales_rows(rows: Iterable[dict[str, Any]]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Return only SKU rows whose order can be reconciled without allocation.

    Every returned row has an actual unique line id.  Reconciliation uses the
    rounded Java line amount and compares the rounded sum to the saved order
    total.  Orders with missing/invalid values or a duplicate line id are
    quarantined as a gap; their difference is never spread over SKU rows.
    """
    materialized = [dict(row) for row in rows]
    groups: dict[Any, list[dict[str, Any]]] = defaultdict(list)
    invalid: dict[Any, str] = {}
    seen: dict[Any, Any] = {}
    for row in materialized:
        order_id = row.get("订单ID")
        line_id = row.get(_SALES_LINE_ID)
        if order_id is None:
            invalid[order_id] = "missing order id"
            continue
        if line_id is None or str(line_id).strip() == "":
            invalid[order_id] = "missing line id"
            groups[order_id].append(row)
            continue
        if line_id in seen:
            invalid[order_id] = "duplicate line id"
            invalid[seen[line_id]] = "duplicate line id"
        else:
            seen[line_id] = order_id
        groups[order_id].append(row)

    output: list[dict[str, Any]] = []
    reasons: defaultdict[str, int] = defaultdict(int)
    for order_id, order_rows in groups.items():
        total = _decimal(order_rows[0].get("订单总额"))
        if total is None:
            invalid[order_id] = "missing or invalid order total"
        line_amounts = [_java_line_amount(r.get("数量"), r.get("单价"), r.get("折扣率")) for r in order_rows]
        if any(amount is None for amount in line_amounts):
            invalid[order_id] = "missing or invalid line price/quantity/discount"
        if any(r.get("订单总额") != order_rows[0].get("订单总额") for r in order_rows):
            invalid[order_id] = "inconsistent order total"
        if order_id not in invalid:
            rounded_total = total.quantize(_CENT, rounding=ROUND_HALF_UP)
            rounded_sum = sum(line_amounts, Decimal(0)).quantize(_CENT, rounding=ROUND_HALF_UP)
            if rounded_sum != rounded_total:
                invalid[order_id] = "line sum does not reconcile to order total"
        if order_id in invalid:
            reasons[invalid[order_id]] += 1
            continue
        for row, amount in zip(order_rows, line_amounts):
            output.append({
                "日期": row.get("日期"),
                "订单ID": order_id,
                _SALES_LINE_ID: row.get(_SALES_LINE_ID),
                "产品ID": row.get("产品ID"),
                "产品": row.get("产品"),
                "单位": row.get("单位"),
                "数量": row.get("数量"),
                "订单行小计": amount,
                "订单总额": total.quantize(_CENT, rounding=ROUND_HALF_UP),
            })
    gaps = [{
        "id": "sales.sku.reconciliation",
        "title": "SKU销售金额（逐单对账）",
        "missing": [f"{count}张订单{reason}，未分摊订单差额" for reason, count in reasons.items()],
    }] if reasons else []
    return output, gaps


def assemble_wip_flows(
    report_rows: Iterable[dict[str, Any]],
    inventory_rows: Iterable[dict[str, Any]],
    transaction_rows: Iterable[dict[str, Any]],
    factory_id: str | None = None,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Build only ledger-backed, quantity-conserving WIP flow edges.

    A report is linked to an inventory snapshot by ``source_wip_no`` and then
    to one or more negative OUT transactions by ``report_id``.  Every edge is
    emitted only after the transaction quantities sum exactly to the report's
    input quantity and all IDs, tenant values, and units agree.  This keeps
    loss, conversion, and cost allocation out of the adapter when the source
    system has not recorded those facts.
    """
    reports = [dict(row) for row in report_rows]
    inventories = [dict(row) for row in inventory_rows]
    transactions = [dict(row) for row in transaction_rows]
    report_by_id: dict[Any, dict[str, Any]] = {}
    duplicate_reports: set[Any] = set()
    missing_report_ids = 0
    for row in reports:
        report_id = row.get("报工ID")
        if report_id is None:
            missing_report_ids += 1
        elif report_id in report_by_id:
            duplicate_reports.add(report_id)
        else:
            report_by_id[report_id] = row

    inventory_by_id: dict[Any, dict[str, Any]] = {}
    duplicate_inventory: set[Any] = set()
    for row in inventories:
        inventory_id = row.get("WIP库存ID")
        if inventory_id is None or inventory_id in inventory_by_id:
            duplicate_inventory.add(inventory_id)
        else:
            inventory_by_id[inventory_id] = row

    tx_by_report: dict[Any, list[dict[str, Any]]] = defaultdict(list)
    for row in transactions:
        report_id = row.get("报工ID")
        if report_id is not None:
            tx_by_report[report_id].append(row)

    edges: list[dict[str, Any]] = []
    missing: list[str] = []
    if missing_report_ids:
        missing.append(f"{missing_report_ids}条报工缺少报工ID")
    seen_tx_ids: set[Any] = set()
    for report_id, report in report_by_id.items():
        reasons: list[str] = []
        report_factory = report.get("__factory_id")
        if factory_id is not None and report_factory not in (None, factory_id):
            reasons.append("报工租户不一致")
        if report_id in duplicate_reports:
            reasons.append("报工ID不唯一")
        source = report.get("来源WIP号")
        target = report.get("工序批次号")
        input_qty = _decimal(report.get("投入量"))
        input_unit = report.get("投入单位")
        if not source or not target or source == target:
            reasons.append("来源或去向WIP号缺失/相同")
        if input_qty is None or input_qty <= 0 or not input_unit:
            reasons.append("投入量或投入单位无效")
        tx_rows = tx_by_report.get(report_id, [])
        if not tx_rows:
            reasons.append("缺少按报工关联的库存流水")
        amounts: list[Decimal] = []
        valid_tx: list[dict[str, Any]] = []
        for tx in tx_rows:
            tx_factory = tx.get("__factory_id")
            if factory_id is not None and tx_factory not in (None, factory_id):
                reasons.append("库存流水租户不一致")
            if tx.get("流水类型") != "OUT" or tx.get("来源类型") != "SECONDARY_CONSUME":
                reasons.append("库存流水不是报工领用OUT")
                continue
            tx_id = tx.get("WIP流水ID")
            amount = _decimal(tx.get("流水数量"))
            if tx_id is None or tx_id in seen_tx_ids or amount is None or amount >= 0:
                reasons.append("库存流水ID或负向数量无效")
                continue
            seen_tx_ids.add(tx_id)
            if tx.get("单位") != input_unit:
                reasons.append("报工与库存流水单位不一致")
                continue
            inventory_id = tx.get("WIP库存ID")
            inventory = inventory_by_id.get(inventory_id)
            if inventory is None or inventory_id in duplicate_inventory:
                reasons.append("库存流水引用的库存ID缺失/不唯一")
                continue
            inventory_factory = inventory.get("__factory_id")
            if factory_id is not None and inventory_factory not in (None, factory_id):
                reasons.append("库存快照租户不一致")
            if inventory.get("工序批次号") != source or inventory.get("单位") != input_unit:
                reasons.append("库存快照未正向证明来源WIP和单位")
                continue
            amounts.append(abs(amount))
            valid_tx.append(tx)
        if input_qty is not None and amounts and sum(amounts, Decimal(0)) != input_qty:
            reasons.append("库存流水合计未守恒到报工投入量")
        if reasons or not valid_tx:
            missing.append(f"报工{report_id}: " + "；".join(dict.fromkeys(reasons or ["缺少可验证流转"]))
                          )
            continue
        for tx in valid_tx:
            edges.append({
                "日期": report.get("日期"),
                "来源节点": source,
                "去向节点": target,
                "流量": abs(_decimal(tx.get("流水数量")) or Decimal(0)),
                "单位": input_unit,
                "流转记录ID": tx.get("WIP流水ID"),
                "报工ID": report_id,
            })
    if reports and missing:
        return edges, [{
            "id": "wip.flow.conservation",
            "title": "WIP已记录守恒流转",
            "missing": missing,
        }]
    return edges, []


def _strip_money(frame: pd.DataFrame) -> pd.DataFrame:
    return frame.drop(columns=["单价", "折扣率", "报价含税单价", "订单总额", "订单行小计",
                               "销售金额", "销售目标", "部门成本"], errors="ignore")


async def load(factory_id: str, request: Any, can_price: bool):
    """Load authoritative operational datasets with explicit errors and gaps."""
    start, end = _dates(request)
    gaps: list[dict[str, Any]] = []
    if not can_price:
        gaps.append({"id": "sales.sku.reconciliation", "title": "SKU销售金额（逐单对账）",
                     "missing": ["当前身份没有金额查看权限，未读取或推导SKU金额"]})

    sources = [
        ("sales-sku", "销售订单行 · 逐单对账", config.get_cretas_pool,
         SALES_SKU_SQL if can_price else SALES_SKU_NO_PRICE_SQL, True, True),
        ("department", "部门经营快照 · 上传来源金额（人民币元）", config.get_cretas_pool,
         DEPARTMENT_SQL if can_price else DEPARTMENT_NO_PRICE_SQL, True, True),
        ("wip-report", "生产报工WIP引用 · 数量/单位事实", config.get_cretas_pool, WIP_REPORT_SQL, True, True),
        ("wip-plan-flow", "工序在制品流转 · 已过账数量/单位事实", config.get_cretas_pool,
         WIP_PLAN_FLOW_SQL, True, True),
    ]
    if request.period_type == "all":
        sources.extend([
            ("wip-inventory", "半成品库存余额 · 当前快照", config.get_cretas_pool, WIP_INVENTORY_SQL, False, True),
        ])
    else:
        gaps.append({"id": "wip.inventory.snapshot", "title": "历史WIP库存",
                     "missing": ["半成品库存只有当前余额快照，不能倒推所选期间"]})
    sources.append(("wip-ledger", "半成品库存流水 · 数量/单位事实", config.get_cretas_pool, WIP_TRANSACTION_SQL, True, True))
    raw_rows: dict[str, list[dict[str, Any]]] = {}

    async def read(name: str, title: str, get_pool: Any, sql: str, ranged: bool, emit_dataset: bool):
        try:
            pool = await get_pool()
            if pool is None:
                raise RuntimeError("pool unavailable")
            params = [factory_id, start, end] if ranged else [factory_id]
            async with pool.acquire() as conn:
                async with conn.transaction():
                    await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
                    rows = await conn.fetch(sql, *params)
            if len(rows) > 10000:
                return None, {"source": name, "message": "明细超过安全上限，请缩小期间后重试"}, []
            raw_rows[name] = [dict(row) for row in rows]
            frame = pd.DataFrame(raw_rows[name]).drop(columns=["__factory_id"], errors="ignore")
            local_gaps: list[dict[str, Any]] = []
            currency = ""
            financial = False
            if name == "sales-sku" and can_price:
                reconciled, local_gaps = reconcile_sales_rows([dict(row) for row in rows])
                frame = pd.DataFrame(reconciled)
                currency = "CNY"
            elif name == "sales-sku":
                frame = _strip_money(frame)
            elif name == "department":
                if can_price:
                    frame = frame.rename(columns={
                        "销售金额": "实际（元）",
                        "销售目标": "预算（元）",
                        "部门成本": "部门成本（元）",
                    })
                    currency = "CNY"
                    financial = True
                else:
                    frame = _strip_money(frame)
            elif name == "wip-plan-flow":
                validated, local_gaps = assemble_wip_plan_flows(raw_rows[name], factory_id)
                frame = pd.DataFrame(validated)
                if frame.empty:
                    return None, None, local_gaps
            if not emit_dataset:
                return None, None, local_gaps
            return Dataset(frame, name + " · " + title, financial=financial, currency=currency), None, local_gaps
        except Exception:
            return None, {"source": name, "message": "来源读取失败，请重试或联系管理员核对数据服务"}, []

    results = await asyncio.gather(*(read(*source) for source in sources))
    datasets = [dataset for dataset, _, _ in results if dataset is not None]
    errors = [error for _, error, _ in results if error]
    for _, _, local_gaps in results:
        gaps.extend(local_gaps)
    flow_rows, flow_gaps = assemble_wip_flows(
        raw_rows.get("wip-report", []),
        raw_rows.get("wip-inventory", []),
        raw_rows.get("wip-ledger", []),
        factory_id,
    )
    gaps.extend(flow_gaps)
    if flow_rows:
        datasets.append(Dataset(pd.DataFrame(flow_rows), "wip-flow · 已记录守恒流转"))
    return datasets, errors, gaps
