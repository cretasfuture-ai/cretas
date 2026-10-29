"""Tenant-scoped, relationship-backed business facts for factory SmartBI.

This adapter deliberately exposes only relationships that are stored by the
Java application.  Customer ``source`` is acquisition attribution (not an
order sales channel), material-batch lineage requires both directions of the
receipt link, and finished-goods inventory is a current balance snapshot.
Rows that cannot prove tenant, identity, status, unit, or quantity facts are
returned as gaps instead of being guessed or silently allocated.
"""
from __future__ import annotations

import asyncio
import calendar
from collections import defaultdict
from datetime import date
from decimal import Decimal, InvalidOperation
from typing import Any, Iterable

import pandas as pd

import smartbi.config as config
from .engine import Dataset
from .operational_sources import reconcile_sales_rows


_LIMIT = 10000
COMMERCE_JOINT_SOURCE = "commerce-joint · 订单-发货-当前库存/客户-显式渠道联合"
COMMERCE_CHANNEL_SCHEMA_SQL = """
SELECT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = current_schema()
      AND table_name = 'sales_orders'
      AND column_name = 'cf_smartbi_sales_channel'
)
AND EXISTS (
    SELECT 1
    FROM canvas_dynamic_field
    WHERE factory_id = $1
      AND module_code = 'sales_order'
      AND status = 'ACTIVE'
      AND field_code = 'smartbi_sales_channel'
    GROUP BY factory_id
    HAVING count(*) = 1 AND bool_and(field_type = 'TEXT'
      AND column_name = 'cf_smartbi_sales_channel'
      AND config->>'smartbiSemantic' = 'sales_channel.v1')
) AS channel_ready
"""

# Customer.source is intentionally named "客户来源" and never "渠道".  The
# custom SmartBI order-channel field lives in captured_sources.py.
CUSTOMER_SOURCE_SALES_SQL = """
SELECT i.id AS "订单行ID", so.id AS "订单ID", so.order_date AS "日期",
       so.customer_id AS "客户ID", c.source AS "客户来源",
       i.product_type_id AS "产品ID",
       COALESCE(NULLIF(BTRIM(pt.name), ''), NULLIF(BTRIM(i.product_name), '')) AS "产品",
       i.unit AS "单位", i.quantity AS "数量", i.unit_price AS "单价",
       COALESCE(i.discount_rate, 0) AS "折扣率", so.total_amount AS "订单总额"
FROM sales_order_items i
JOIN sales_orders so ON so.id = i.sales_order_id AND so.factory_id = $1
JOIN customers c ON c.id = so.customer_id AND c.factory_id = so.factory_id
 AND c.deleted_at IS NULL
LEFT JOIN product_types pt
  ON pt.id = i.product_type_id AND pt.factory_id = so.factory_id AND pt.deleted_at IS NULL
WHERE so.factory_id = $1 AND so.deleted_at IS NULL AND i.deleted_at IS NULL
  AND so.status NOT IN ('CANCELLED', 'DRAFT', 'FINANCE_REJECTED')
  AND ($2::date IS NULL OR so.order_date >= $2::date)
  AND ($3::date IS NULL OR so.order_date < ($3::date + INTERVAL '1 day'))
ORDER BY so.order_date, so.id, i.id
LIMIT 10001
"""

CUSTOMER_SOURCE_SALES_NO_PRICE_SQL = """
SELECT i.id AS "订单行ID", so.id AS "订单ID", so.order_date AS "日期",
       so.customer_id AS "客户ID", c.source AS "客户来源",
       i.product_type_id AS "产品ID",
       COALESCE(NULLIF(BTRIM(pt.name), ''), NULLIF(BTRIM(i.product_name), '')) AS "产品",
       i.unit AS "单位", i.quantity AS "数量"
FROM sales_order_items i
JOIN sales_orders so ON so.id = i.sales_order_id AND so.factory_id = $1
JOIN customers c ON c.id = so.customer_id AND c.factory_id = so.factory_id
 AND c.deleted_at IS NULL
LEFT JOIN product_types pt
  ON pt.id = i.product_type_id AND pt.factory_id = so.factory_id AND pt.deleted_at IS NULL
WHERE so.factory_id = $1 AND so.deleted_at IS NULL AND i.deleted_at IS NULL
  AND so.status NOT IN ('CANCELLED', 'DRAFT', 'FINANCE_REJECTED')
  AND ($2::date IS NULL OR so.order_date >= $2::date)
  AND ($3::date IS NULL OR so.order_date < ($3::date + INTERVAL '1 day'))
ORDER BY so.order_date, so.id, i.id
LIMIT 10001
"""

# This is deliberately a separate source from Customer.source sales.  The
# channel is admitted only when the one ACTIVE SmartBI sales-channel field
# definition exists; Customer.source remains acquisition attribution.
COMMERCE_BASE_SALES_SQL = CUSTOMER_SOURCE_SALES_SQL.replace(
    'so.id AS "订单ID", so.order_date',
    'so.id AS "订单ID", so.factory_id AS "工厂ID", so.order_date',
).replace('c.source AS "客户来源"', 'c.name AS "客户"')
COMMERCE_BASE_SALES_NO_PRICE_SQL = CUSTOMER_SOURCE_SALES_NO_PRICE_SQL.replace(
    'so.id AS "订单ID", so.order_date',
    'so.id AS "订单ID", so.factory_id AS "工厂ID", so.order_date',
).replace('c.source AS "客户来源"', 'c.name AS "客户"')

COMMERCE_JOINT_SALES_SQL = """
WITH channel_definition AS (
    SELECT factory_id
    FROM canvas_dynamic_field
    WHERE factory_id = $1 AND module_code = 'sales_order' AND status = 'ACTIVE'
      AND field_code = 'smartbi_sales_channel'
    GROUP BY factory_id
    HAVING count(*) = 1 AND bool_and(field_type = 'TEXT'
      AND column_name = 'cf_smartbi_sales_channel'
      AND config->>'smartbiSemantic' = 'sales_channel.v1')
)
SELECT i.id AS "订单行ID", so.id AS "订单ID", so.factory_id AS "工厂ID", so.order_date AS "日期",
       so.customer_id AS "客户ID", c.name AS "客户",
       so.cf_smartbi_sales_channel AS "渠道",
       'cf_smartbi_sales_channel' AS "渠道定义列", i.product_type_id AS "产品ID",
       COALESCE(NULLIF(BTRIM(pt.name), ''), NULLIF(BTRIM(i.product_name), '')) AS "产品",
       i.unit AS "单位", i.quantity AS "数量", i.unit_price AS "单价",
       COALESCE(i.discount_rate, 0) AS "折扣率", so.total_amount AS "订单总额"
FROM sales_order_items i
JOIN sales_orders so ON so.id = i.sales_order_id AND so.factory_id = $1
JOIN customers c ON c.id = so.customer_id AND c.factory_id = so.factory_id AND c.deleted_at IS NULL
JOIN channel_definition cd ON cd.factory_id = so.factory_id
LEFT JOIN product_types pt ON pt.id = i.product_type_id AND pt.factory_id = so.factory_id AND pt.deleted_at IS NULL
WHERE so.factory_id = $1 AND so.deleted_at IS NULL AND i.deleted_at IS NULL
  AND so.status NOT IN ('CANCELLED', 'DRAFT', 'FINANCE_REJECTED')
  AND ($2::date IS NULL OR so.order_date >= $2::date)
  AND ($3::date IS NULL OR so.order_date < ($3::date + INTERVAL '1 day'))
ORDER BY so.order_date, so.id, i.id
LIMIT 10001
"""

COMMERCE_JOINT_SALES_NO_PRICE_SQL = """
WITH channel_definition AS (
    SELECT factory_id
    FROM canvas_dynamic_field
    WHERE factory_id = $1 AND module_code = 'sales_order' AND status = 'ACTIVE'
      AND field_code = 'smartbi_sales_channel'
    GROUP BY factory_id
    HAVING count(*) = 1 AND bool_and(field_type = 'TEXT'
      AND column_name = 'cf_smartbi_sales_channel'
      AND config->>'smartbiSemantic' = 'sales_channel.v1')
)
SELECT i.id AS "订单行ID", so.id AS "订单ID", so.factory_id AS "工厂ID", so.order_date AS "日期",
       so.customer_id AS "客户ID", c.name AS "客户",
       so.cf_smartbi_sales_channel AS "渠道",
       'cf_smartbi_sales_channel' AS "渠道定义列", i.product_type_id AS "产品ID",
       COALESCE(NULLIF(BTRIM(pt.name), ''), NULLIF(BTRIM(i.product_name), '')) AS "产品",
       i.unit AS "单位", i.quantity AS "数量"
FROM sales_order_items i
JOIN sales_orders so ON so.id = i.sales_order_id AND so.factory_id = $1
JOIN customers c ON c.id = so.customer_id AND c.factory_id = so.factory_id AND c.deleted_at IS NULL
JOIN channel_definition cd ON cd.factory_id = so.factory_id
LEFT JOIN product_types pt ON pt.id = i.product_type_id AND pt.factory_id = so.factory_id AND pt.deleted_at IS NULL
WHERE so.factory_id = $1 AND so.deleted_at IS NULL AND i.deleted_at IS NULL
  AND so.status NOT IN ('CANCELLED', 'DRAFT', 'FINANCE_REJECTED')
  AND ($2::date IS NULL OR so.order_date >= $2::date)
  AND ($3::date IS NULL OR so.order_date < ($3::date + INTERVAL '1 day'))
ORDER BY so.order_date, so.id, i.id
LIMIT 10001
"""

# The LEFT JOIN intentionally keeps an unverifiable item visible to the
# qualification function, which emits a gap rather than treating it as absent.
PURCHASE_BATCH_LINEAGE_SQL = """
SELECT pr.id AS "收货单ID", pr.factory_id AS "收货工厂ID", pr.receive_date AS "日期",
       pr.status AS "收货状态", pri.id AS "收货行ID", pri.material_type_id AS "物料ID",
       pri.material_name AS "物料", pri.material_batch_id AS "物料批次ID",
       pri.received_quantity AS "收货数量", pri.unit AS "收货单位",
       pri.receive_package_unit_snapshot AS "包装单位快照",
       pri.inventory_base_unit_snapshot AS "库存基础单位快照",
       pri.package_to_base_factor_snapshot AS "包装换算因子快照",
       pri.inventory_quantity_snapshot AS "库存数量快照",
       mb.factory_id AS "批次工厂ID", mb.material_type_id AS "批次物料ID",
       rmt.id AS "物料主数据ID", rmt.factory_id AS "物料主数据工厂ID",
       mb.source_doc_type AS "批次来源类型",
       mb.source_doc_id AS "批次来源单据ID", mb.quantity_unit AS "批次数量单位",
       mb.receipt_quantity AS "批次入库数量", mb.used_quantity AS "批次已用数量",
       mb.reserved_quantity AS "批次预留数量"
FROM purchase_receive_items pri
JOIN purchase_receive_records pr ON pr.id = pri.receive_record_id
 AND pr.factory_id = $1 AND pr.deleted_at IS NULL
LEFT JOIN material_batches mb ON mb.id = pri.material_batch_id
 AND mb.factory_id = pr.factory_id AND mb.deleted_at IS NULL
LEFT JOIN raw_material_types rmt
  ON rmt.id = pri.material_type_id AND rmt.factory_id = pr.factory_id
 AND rmt.deleted_at IS NULL
WHERE pr.factory_id = $1 AND pri.deleted_at IS NULL
  AND ($2::date IS NULL OR pr.receive_date >= $2::date)
  AND ($3::date IS NULL OR pr.receive_date < ($3::date + INTERVAL '1 day'))
ORDER BY pr.receive_date, pr.id, pri.id
LIMIT 10001
"""

FINISHED_GOODS_INVENTORY_SQL = """
SELECT CURRENT_DATE AS "日期", fgb.id AS "成品批次ID", fgb.factory_id AS "工厂ID",
       fgb.batch_number AS "批次", fgb.product_type_id AS "产品ID",
       COALESCE(NULLIF(BTRIM(pt.name), ''), NULLIF(BTRIM(fgb.product_name), '')) AS "产品",
       fgb.unit AS "单位", fgb.produced_quantity AS "已生产数量",
       fgb.shipped_quantity AS "已发货数量", fgb.reserved_quantity AS "已预留数量",
       (fgb.produced_quantity - COALESCE(fgb.shipped_quantity, 0)
        - COALESCE(fgb.reserved_quantity, 0)) AS "可用数量",
       fgb.status AS "状态",
       fgb.ownership AS "所有权", fgb.owner_customer_id AS "所有客户ID",
       fgb.source_sales_order_id AS "来源订单快照ID",
       fgb.source_sales_order_item_id AS "来源订单行快照ID"
FROM finished_goods_batches fgb
LEFT JOIN product_types pt
  ON pt.id = fgb.product_type_id AND pt.factory_id = fgb.factory_id AND pt.deleted_at IS NULL
WHERE fgb.factory_id = $1 AND fgb.deleted_at IS NULL AND fgb.status = 'AVAILABLE'
  AND fgb.produced_quantity > 0
  AND fgb.produced_quantity - COALESCE(fgb.shipped_quantity, 0)
      - COALESCE(fgb.reserved_quantity, 0) > 0
ORDER BY fgb.id
LIMIT 10001
"""

# This relation is audit-supporting evidence, not a sales allocation.  Both
# paths are supported, but active allocations are authoritative. Java's
# recordDeductedBatch also keeps the first allocated batch on the item for
# traceability; that pointer is not a second shipment. An invalid allocation
# must not reactivate the legacy pointer. The qualifier still rejects duplicate
# or cross-unit relationships rather than summing them.
FINISHED_GOODS_SHIPMENT_SQL = """
SELECT sdi.id::text AS "发货行ID", sdi.sales_order_item_id AS "销售订单行ID", sdr.id AS "发货单ID", sdr.sales_order_id AS "订单ID",
       sdr.customer_id AS "客户ID", sdr.delivery_date AS "发货日期", sdr.status AS "发货状态",
       sdi.finished_goods_batch_id AS "成品批次ID", sdi.product_type_id AS "产品ID",
       sdi.delivered_quantity AS "发货数量", sdi.unit AS "发货单位",
       sdi.delivered_quantity AS "申报发货数量", sdi.unit AS "申报发货单位",
       sdi.id::text AS "发货关系ID", 'DELIVERY_ITEM_FK' AS "关系来源",
       fgb.factory_id AS "批次工厂ID", fgb.product_type_id AS "批次产品ID", fgb.unit AS "批次单位",
       fgb.ownership AS "所有权", fgb.owner_customer_id AS "所有客户ID"
FROM sales_delivery_items sdi
JOIN sales_delivery_records sdr ON sdr.id = sdi.delivery_record_id
 AND sdr.factory_id = $1 AND sdr.deleted_at IS NULL
JOIN finished_goods_batches fgb ON fgb.id = sdi.finished_goods_batch_id
 AND fgb.factory_id = sdr.factory_id AND fgb.deleted_at IS NULL
WHERE sdr.factory_id = $1 AND sdi.deleted_at IS NULL
  AND sdr.status IN ('SHIPPED', 'DELIVERED')
  AND COALESCE(sdr.record_role, 'LEGACY') <> 'MASTER'
  AND sdi.finished_goods_batch_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM sales_delivery_item_batch_allocations active_allocation
      WHERE active_allocation.factory_id = sdr.factory_id
        AND active_allocation.delivery_item_id = sdi.id::text
        AND active_allocation.deleted_at IS NULL
  )
  AND ($2::date IS NULL OR sdr.delivery_date >= $2::date)
  AND ($3::date IS NULL OR sdr.delivery_date < ($3::date + INTERVAL '1 day'))
UNION ALL
SELECT sdi.id::text AS "发货行ID", sdi.sales_order_item_id AS "销售订单行ID", sdr.id AS "发货单ID", sdr.sales_order_id AS "订单ID",
       sdr.customer_id AS "客户ID", sdr.delivery_date AS "发货日期", sdr.status AS "发货状态",
       a.finished_goods_batch_id AS "成品批次ID", sdi.product_type_id AS "产品ID",
       a.allocated_qty AS "发货数量", COALESCE(a.unit, sdi.unit) AS "发货单位",
       sdi.delivered_quantity AS "申报发货数量", sdi.unit AS "申报发货单位",
       a.id AS "发货关系ID", 'BATCH_ALLOCATION' AS "关系来源",
       fgb.factory_id AS "批次工厂ID", fgb.product_type_id AS "批次产品ID", fgb.unit AS "批次单位",
       fgb.ownership AS "所有权", fgb.owner_customer_id AS "所有客户ID"
FROM sales_delivery_item_batch_allocations a
JOIN sales_delivery_items sdi ON sdi.id::text = a.delivery_item_id
 AND sdi.deleted_at IS NULL
JOIN sales_delivery_records sdr ON sdr.id = sdi.delivery_record_id
 AND sdr.factory_id = $1 AND sdr.deleted_at IS NULL
JOIN finished_goods_batches fgb ON fgb.id = a.finished_goods_batch_id
 AND fgb.factory_id = sdr.factory_id AND fgb.deleted_at IS NULL
WHERE a.factory_id = $1 AND a.deleted_at IS NULL
  AND sdr.status IN ('SHIPPED', 'DELIVERED')
  AND COALESCE(sdr.record_role, 'LEGACY') <> 'MASTER'
  AND ($2::date IS NULL OR sdr.delivery_date >= $2::date)
  AND ($3::date IS NULL OR sdr.delivery_date < ($3::date + INTERVAL '1 day'))
ORDER BY "发货日期", "发货单ID", "发货关系ID"
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
    except (InvalidOperation, TypeError, ValueError):
        return None
    return result if result.is_finite() else None


def qualify_purchase_batch_rows(
    rows: Iterable[dict[str, Any]], factory_id: str,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Keep only receipt rows with bidirectional, same-tenant batch lineage.

    Quantity snapshots are compared only when the receipt row supplied them.
    A package-to-base factor is never invented; absent snapshots require the
    receipt and material-batch units to match exactly.
    """
    materialized = [dict(row) for row in rows]
    key_counts: defaultdict[tuple[Any, Any], int] = defaultdict(int)
    batch_counts: defaultdict[Any, int] = defaultdict(int)
    for row in materialized:
        key_counts[(row.get("收货单ID"), row.get("收货行ID"))] += 1
        if row.get("物料批次ID"):
            batch_counts[row.get("物料批次ID")] += 1
    duplicate_keys = {key for key, count in key_counts.items() if count > 1}
    duplicate_batches = {batch for batch, count in batch_counts.items() if count > 1}
    qualified: list[dict[str, Any]] = []
    reasons: defaultdict[str, int] = defaultdict(int)
    for row in materialized:
        key = (row.get("收货单ID"), row.get("收货行ID"))
        reason: str | None = None
        if key in duplicate_keys:
            reason = "收货行重复"
        if row.get("物料批次ID") in duplicate_batches:
            reason = reason or "物料批次被多个收货行重复关联"
        if row.get("收货工厂ID") != factory_id or row.get("批次工厂ID") != factory_id:
            reason = reason or "收货或批次租户不一致"
        if row.get("收货状态") != "CONFIRMED":
            reason = reason or "收货单未确认"
        if not row.get("物料批次ID"):
            reason = reason or "收货行缺少物料批次"
        if not row.get("物料ID") or not row.get("批次物料ID"):
            reason = reason or "收货行或物料批次缺少物料类型"
        elif row.get("物料ID") != row.get("批次物料ID"):
            reason = reason or "收货行与物料批次物料类型不一致"
        if (row.get("物料主数据ID") != row.get("物料ID")
                or row.get("物料主数据工厂ID") != factory_id):
            reason = reason or "物料主数据缺失、删除或跨工厂"
        if row.get("批次来源类型") != "PURCHASE_RECEIVE":
            reason = reason or "批次来源类型不是PURCHASE_RECEIVE"
        if row.get("批次来源单据ID") != row.get("收货单ID"):
            reason = reason or "批次来源单据未反向指向收货单"
        received = _decimal(row.get("收货数量"))
        batch_qty = _decimal(row.get("批次入库数量"))
        if received is None or received <= 0 or batch_qty is None or batch_qty <= 0:
            reason = reason or "收货或批次入库数量无效"
        receipt_unit = row.get("收货单位")
        batch_unit = row.get("批次数量单位")
        if not receipt_unit or not batch_unit:
            reason = reason or "收货或批次单位缺失"
        snapshot_qty = _decimal(row.get("库存数量快照"))
        factor = _decimal(row.get("包装换算因子快照"))
        package_unit = row.get("包装单位快照")
        base_unit = row.get("库存基础单位快照")
        if snapshot_qty is not None:
            if snapshot_qty <= 0 or factor is None or factor <= 0 or not base_unit:
                reason = reason or "库存数量快照缺少有效单位或换算因子"
            elif (received * factor).compare(snapshot_qty) != 0:
                reason = reason or "库存数量快照未与收货数量和换算因子一致"
            elif base_unit != batch_unit:
                reason = reason or "库存基础单位与物料批次单位不一致"
            elif snapshot_qty != batch_qty:
                reason = reason or "库存数量快照与批次入库数量不一致"
        elif receipt_unit != batch_unit:
            reason = reason or "无库存快照时收货单位与批次单位不一致"
        if package_unit is not None and package_unit != receipt_unit:
            reason = reason or "包装单位快照与收货单位不一致"
        if reason:
            reasons[reason] += 1
            continue
        clean = dict(row)
        canonical_qty = snapshot_qty if snapshot_qty is not None else batch_qty
        canonical_unit = base_unit if snapshot_qty is not None else batch_unit
        material_label = row.get("物料") or row.get("物料ID")
        clean["数量"] = canonical_qty
        clean["数量单位"] = canonical_unit
        if "批次已用数量" in row and "批次预留数量" in row:
            used_qty = _decimal(row.get("批次已用数量"))
            reserved_qty = _decimal(row.get("批次预留数量"))
            available_qty = (canonical_qty - used_qty - reserved_qty
                             if used_qty is not None and reserved_qty is not None else None)
            clean["当前可用数量"] = available_qty
        clean["物料（单位）"] = f"{material_label}（{canonical_unit}）"
        clean["事实限定"] = "已确认收货 + 收货行物料批次双向来源校验"
        qualified.append(clean)
    gaps = ([{"id": "purchase.material_batch.lineage", "title": "确认收货与物料批次来源",
               "missing": [f"{count}行{reason}" for reason, count in reasons.items()]}]
             if reasons else [])
    return qualified, gaps


def qualify_finished_goods_rows(
    rows: Iterable[dict[str, Any]], factory_id: str,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Validate current finished-goods balances without reconstructing history."""
    materialized = [dict(row) for row in rows]
    for row in materialized:
        for field in ("成品批次ID", "产品ID", "单位"):
            value = row.get(field)
            row[field] = (str(value).strip() or None) if value is not None and not pd.isna(value) else None
    id_counts: defaultdict[Any, int] = defaultdict(int)
    for row in materialized:
        id_counts[row.get("成品批次ID")] += 1
    duplicate_ids = {batch_id for batch_id, count in id_counts.items() if count > 1}
    qualified: list[dict[str, Any]] = []
    reasons: defaultdict[str, int] = defaultdict(int)
    for row in materialized:
        batch_id = row.get("成品批次ID")
        reason: str | None = None
        if batch_id in duplicate_ids:
            reason = "成品批次重复"
        if not batch_id or not row.get("产品ID") or row.get("工厂ID") != factory_id:
            reason = reason or "成品批次或SKU缺失或租户不一致"
        if "状态" not in row or row.get("状态") != "AVAILABLE":
            reason = reason or "成品批次非AVAILABLE"
        unit = row.get("单位")
        produced = _decimal(row.get("已生产数量"))
        shipped = _decimal(row.get("已发货数量"))
        reserved = _decimal(row.get("已预留数量"))
        available = _decimal(row.get("可用数量"))
        if not unit or produced is None or shipped is None or reserved is None or available is None:
            reason = reason or "成品库存数量或单位缺失"
        elif produced <= 0 or shipped < 0 or reserved < 0 or available <= 0:
            reason = reason or "成品库存数量非正或负数"
        elif produced - shipped - reserved != available:
            reason = reason or "成品库存余额未守恒到产出减发货减预留"
        if reason:
            reasons[reason] += 1
            continue
        clean = dict(row)
        clean["数量"] = available
        clean["数量单位"] = unit
        product_label = row.get("产品") or row.get("产品ID")
        clean["产品（单位）"] = f"{product_label}（{unit}）"
        clean["事实限定"] = "当前AVAILABLE成品批次余额；非历史倒推"
        qualified.append(clean)
    gaps = ([{"id": "finished_goods.current_snapshot", "title": "当前独立成品库存",
               "missing": [f"{count}行{reason}" for reason, count in reasons.items()]}]
             if reasons else [])
    return qualified, gaps


def qualify_finished_goods_shipments(
    rows: Iterable[dict[str, Any]], factory_id: str,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Validate delivery-to-FG relationships without unit conversion or allocation."""
    materialized = [dict(row) for row in rows]
    identity_fields = ("发货关系ID", "发货行ID", "发货单ID", "成品批次ID", "产品ID",
                       "批次产品ID", "发货单位", "批次单位", "申报发货单位", "销售订单行ID")
    for row in materialized:
        for field in identity_fields:
            value = row.get(field)
            row[field] = (str(value).strip() or None) if value is not None and not pd.isna(value) else None
    relation_counts: defaultdict[Any, int] = defaultdict(int)
    allocation_by_item: defaultdict[Any, list[dict[str, Any]]] = defaultdict(list)
    for row in materialized:
        relation_counts[row.get("发货关系ID")] += 1
        if row.get("关系来源") == "BATCH_ALLOCATION":
            allocation_by_item[row.get("发货行ID")].append(row)
    duplicate_relations = {relation for relation, count in relation_counts.items() if count > 1}
    paths_by_item: defaultdict[Any, set[Any]] = defaultdict(set)
    for row in materialized:
        paths_by_item[row.get("发货行ID")].add(row.get("关系来源"))
    ambiguous_items = {
        item_id for item_id, paths in paths_by_item.items()
        if "DELIVERY_ITEM_FK" in paths and "BATCH_ALLOCATION" in paths
    }
    allocation_gaps: dict[Any, str] = {}
    for item_id, item_rows in allocation_by_item.items():
        declared_qtys = {_decimal(row.get("申报发货数量")) for row in item_rows}
        declared_units = {row.get("申报发货单位") for row in item_rows}
        declared_qtys.discard(None)
        declared_units.discard(None)
        allocated_qtys = [_decimal(row.get("发货数量")) for row in item_rows]
        relation_ids = [row.get("发货关系ID") for row in item_rows]
        invalid_relation = any(
            any(not row.get(field) for field in identity_fields[:8])
            or not row.get("关系来源") == "BATCH_ALLOCATION"
            or not row.get("批次工厂ID") == factory_id
            or row.get("产品ID") != row.get("批次产品ID")
            or not row.get("发货单位") or row.get("发货单位") != row.get("批次单位")
            or row.get("申报发货单位") != row.get("批次单位")
            for row in item_rows
        )
        if (len(declared_qtys) != 1 or len(declared_units) != 1
                or any(qty is None or qty <= 0 for qty in allocated_qtys)
                or len(relation_ids) != len(set(relation_ids))
                or invalid_relation):
            allocation_gaps[item_id] = "allocation缺少一致的申报发货数量/单位"
            continue
        declared = next(iter(declared_qtys))
        allocated = sum(allocated_qtys, Decimal(0))
        if allocated != declared:
            allocation_gaps[item_id] = "allocation合计未守恒到申报发货数量"
    qualified: list[dict[str, Any]] = []
    reasons: defaultdict[str, int] = defaultdict(int)
    for row in materialized:
        relation = row.get("发货关系ID")
        reason: str | None = None
        item_id = row.get("发货行ID")
        if any(not row.get(field) for field in identity_fields[:8]):
            reason = "发货关系业务ID、SKU或物理单位缺失"
        if item_id in ambiguous_items:
            reason = "同一发货行同时存在直接批次和allocation路径"
        if item_id in allocation_gaps:
            reason = allocation_gaps[row.get("发货行ID")]
        if relation in duplicate_relations:
            reason = "发货关系重复"
        if row.get("发货状态") not in {"SHIPPED", "DELIVERED"}:
            reason = reason or "发货单状态不是SHIPPED或DELIVERED"
        if not relation or row.get("批次工厂ID") != factory_id:
            reason = reason or "发货关系缺失或租户不一致"
        if not row.get("成品批次ID") or not row.get("发货单ID") or not row.get("发货行ID"):
            reason = reason or "发货关系缺少单据或批次ID"
        if row.get("产品ID") != row.get("批次产品ID"):
            reason = reason or "发货行与成品批次SKU不一致"
        if not row.get("发货单位") or row.get("发货单位") != row.get("批次单位"):
            reason = reason or "发货单位与成品批次单位不一致；未做换算"
        declared_unit = row.get("申报发货单位")
        declared_qty = _decimal(row.get("申报发货数量"))
        if declared_unit is not None and declared_unit != row.get("批次单位"):
            reason = reason or "申报发货单位与成品批次单位不一致；未做换算"
        if declared_qty is not None and declared_qty <= 0:
            reason = reason or "申报发货数量无效"
        qty = _decimal(row.get("发货数量"))
        if qty is None or qty <= 0:
            reason = reason or "发货数量无效"
        if (row.get("关系来源") == "DELIVERY_ITEM_FK"
                and declared_qty is not None and qty != declared_qty):
            reason = reason or "直接发货关系未守恒到申报发货数量"
        if reason:
            reasons[reason] += 1
            continue
        clean = dict(row)
        clean["数量"] = qty
        clean["数量单位"] = row.get("批次单位")
        clean["产品（单位）"] = f"{row.get('产品ID')}（{row.get('批次单位')}）"
        clean["事实限定"] = "已记录发货行/批次关系；未用于销售金额或跨单位分配"
        qualified.append(clean)
    gaps = ([{"id": "finished_goods.shipment.relationship", "title": "成品批次发货关系",
               "missing": [f"{count}行{reason}" for reason, count in reasons.items()]}]
             if reasons else [])
    return qualified, gaps


def qualify_customer_source_sales_rows(
    rows: Iterable[dict[str, Any]], can_price: bool,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Require an explicit customer acquisition source before exposing sales."""
    materialized = [dict(row) for row in rows]
    source_rows: list[dict[str, Any]] = []
    missing_source = 0
    for row in materialized:
        source = row.get("客户来源")
        if source is None or not str(source).strip():
            missing_source += 1
            continue
        source_rows.append(row)
    gaps: list[dict[str, Any]] = []
    if missing_source:
        gaps.append({"id": "sales.customer_source.coverage", "title": "客户来源获客归因",
                     "missing": [f"{missing_source}行客户未登记source，未归入获客来源销售"]})
    if not can_price:
        return source_rows, gaps
    reconciled, reconcile_gaps = reconcile_sales_rows(source_rows)
    gaps.extend(reconcile_gaps)
    source_by_line = {row.get("订单行ID"): row.get("客户来源") for row in source_rows}
    for row in reconciled:
        row["客户来源"] = source_by_line.get(row.get("订单行ID"))
    return reconciled, gaps


def _strip_money(frame: pd.DataFrame) -> pd.DataFrame:
    return frame.drop(columns=["单价", "折扣率", "订单总额", "订单行小计"], errors="ignore")


async def load(factory_id: str, request: Any, can_price: bool):
    """Load trusted business facts; failures and unverifiable rows stay visible."""
    start, end = _dates(request)
    gaps: list[dict[str, Any]] = []
    if request.period_type != "all":
        gaps.append({"id": "finished_goods.historical_snapshot", "title": "历史成品库存",
                     "missing": ["仅有当前成品批次余额，不能从产生/发货/预留快照倒推历史余额"]})
    source_specs = [
        ("customer-source-sales", "客户来源获客归因（非订单销售渠道）",
         CUSTOMER_SOURCE_SALES_SQL if can_price else CUSTOMER_SOURCE_SALES_NO_PRICE_SQL, True),
        ("purchase-batch-lineage", "已确认收货与物料批次双向来源",
         PURCHASE_BATCH_LINEAGE_SQL, True),
        ("finished-goods-shipment", "已记录成品批次发货关系（不做单位换算）",
         FINISHED_GOODS_SHIPMENT_SQL, True),
    ]
    if request.period_type == "all":
        source_specs.append(("finished-goods-inventory", "独立成品库存当前快照（不倒推历史）",
                             FINISHED_GOODS_INVENTORY_SQL, False))
    raw_rows: dict[str, list[dict[str, Any]]] = {}

    async def read(name: str, title: str, sql: str, ranged: bool):
        try:
            pool = await config.get_cretas_pool()
            if pool is None:
                raise RuntimeError("pool unavailable")
            params = [factory_id, start, end] if ranged else [factory_id]
            async with pool.acquire() as conn:
                async with conn.transaction():
                    await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
                    rows = await conn.fetch(sql, *params)
            if len(rows) > _LIMIT:
                return None, {"source": name, "message": "明细超过安全上限，请缩小期间后重试"}, []
            materialized = [dict(row) for row in rows]
            raw_rows[name] = materialized
            local_gaps: list[dict[str, Any]] = []
            if name in {"commerce-joint-sales", "commerce-base-sales"}:
                # This raw frame is consumed only by commerce_joint.prepare;
                # exposing it as a normal dataset would create generic charts
                # before the relation and price guards have run.
                return None, None, []
            if name == "customer-source-sales":
                sales_rows, local_gaps = qualify_customer_source_sales_rows(materialized, can_price)
                frame = pd.DataFrame(sales_rows)
                if not can_price:
                    frame = _strip_money(frame)
                currency = "CNY" if can_price else ""
                return Dataset(frame, name + " · " + title, financial=can_price, currency=currency), None, local_gaps
            if name == "purchase-batch-lineage":
                qualified, local_gaps = qualify_purchase_batch_rows(materialized, factory_id)
                return Dataset(pd.DataFrame(qualified), name + " · " + title), None, local_gaps
            if name == "finished-goods-inventory":
                qualified, local_gaps = qualify_finished_goods_rows(materialized, factory_id)
                return Dataset(pd.DataFrame(qualified), name + " · " + title), None, local_gaps
            qualified, local_gaps = qualify_finished_goods_shipments(materialized, factory_id)
            return Dataset(pd.DataFrame(qualified), name + " · " + title), None, local_gaps
        except Exception:
            return None, {"source": name, "message": "来源读取失败，请重试或联系管理员核对数据服务"}, []

    results = await asyncio.gather(*(read(*spec) for spec in source_specs))
    datasets = [dataset for dataset, _, _ in results if dataset is not None]
    errors = [error for _, error, _ in results if error]
    for _, _, local_gaps in results:
        gaps.extend(local_gaps)
    # A joint sales query is only needed when there is at least one real
    # delivery relationship to join.  This keeps the existing trusted source
    # read path unchanged for factories with no shipment facts.
    commerce_schema_ready = False
    if raw_rows.get("finished-goods-shipment"):
        _, base_error, _ = await read(
            "commerce-base-sales", "同厂销售订单行（独立于渠道配置）",
            COMMERCE_BASE_SALES_SQL if can_price else COMMERCE_BASE_SALES_NO_PRICE_SQL, True)
        if base_error:
            errors.append(base_error)
        try:
            pool = await config.get_cretas_pool()
            if pool is None:
                raise RuntimeError("pool unavailable")
            async with pool.acquire() as conn:
                async with conn.transaction():
                    await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
                    schema_row = await conn.fetchrow(COMMERCE_CHANNEL_SCHEMA_SQL, factory_id)
            commerce_schema_ready = bool(schema_row and schema_row["channel_ready"])
        except Exception:
            commerce_schema_ready = False
        if commerce_schema_ready:
            _, commerce_error, _ = await read(
                "commerce-joint-sales", "订单客户与ACTIVE显式渠道联合原始行",
                COMMERCE_JOINT_SALES_NO_PRICE_SQL, True)
            if commerce_error:
                errors.append(commerce_error)
        else:
            gaps.append({"id": "commerce.channel", "title": "客户-显式渠道联合",
                         "missing": ["sales_orders.cf_smartbi_sales_channel列或唯一ACTIVE显式渠道定义不可核验，未读取客户渠道/金额"]})
    # The joint adapter consumes the same tenant-scoped raw rows as the four
    # named trusted datasets.  It validates the stored sales_order_item_id →
    # delivery → finished_goods_batch relationship before exposing one merged
    # detail frame; it never uses Customer.source or an FGB source snapshot as
    # an order-line foreign key.
    if raw_rows.get("commerce-base-sales"):
        from . import commerce_joint
        channel_by_id = {str(row["订单行ID"]): row
                         for row in raw_rows.get("commerce-joint-sales", [])}
        joint_sales = []
        for original in raw_rows["commerce-base-sales"]:
            row = dict(original)
            channel_row = channel_by_id.get(str(row["订单行ID"]))
            if channel_row is not None and all(
                    str(channel_row.get(key)) == str(row.get(key))
                    for key in ("订单ID", "工厂ID", "客户ID", "产品ID", "单位", "数量")):
                row["渠道"] = channel_row.get("渠道")
                row["渠道定义列"] = channel_row.get("渠道定义列")
            joint_sales.append(row)
        joint_frame, joint_gaps = commerce_joint.prepare(
            joint_sales,
            raw_rows.get("finished-goods-shipment", []),
            raw_rows.get("finished-goods-inventory", []),
            factory_id, request, can_price,
        )
        gaps.extend(joint_gaps)
        if joint_frame is not None and not joint_frame.empty:
            datasets.append(Dataset(
                joint_frame,
                COMMERCE_JOINT_SOURCE,
                financial=can_price,
                currency="CNY" if can_price else "",
            ))
    return datasets, errors, gaps
