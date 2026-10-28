"""Tenant-scoped sales/stock and customer/channel relationship charts.

This module only joins keys that are stored by the Java sales and inventory
model.  It never matches by product name, assigns stock to an order by
quantity, or repeats an order total for every product line.
"""
from __future__ import annotations

from collections import defaultdict
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from typing import Any, Iterable

import pandas as pd

from .trusted_business_sources import (
    COMMERCE_JOINT_SOURCE,
    qualify_finished_goods_rows,
    qualify_finished_goods_shipments,
)
from .operational_sources import reconcile_sales_rows


SOURCE = "commerce-joint"
DATASET_SOURCE = COMMERCE_JOINT_SOURCE
SALES_STOCK_FAMILY = "sales.stock"
CUSTOMER_CHANNEL_FAMILY = "customer.channel"
_CENT = Decimal("0.01")


def _key(value: Any) -> str | None:
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return None
    text = str(value).strip()
    return text or None


def _decimal(value: Any) -> Decimal | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        result = Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError):
        return None
    return result if result.is_finite() else None


def _period_ok(rows: list[dict[str, Any]], request: Any) -> bool:
    if str(getattr(request, "period_type", "all")).lower() == "all":
        return True
    start = pd.Timestamp(year=int(request.year), month=int(request.start_month), day=1)
    end = pd.Timestamp(year=int(request.year), month=int(request.end_month), day=1) + pd.offsets.MonthEnd(1)
    dates = pd.to_datetime([row.get("日期") for row in rows], errors="coerce")
    return bool(len(dates) and dates.notna().all() and (dates >= start).all() and (dates <= end).all())


def _gap(family: str, message: str) -> dict[str, Any]:
    return {"id": family, "title": "销售-库存/客户-渠道联合分析", "missing": [message], "source": SOURCE}


def _sales_rows(raw_rows: Iterable[dict[str, Any]], factory_id: str, can_price: bool):
    """Validate order lines and reconcile prices without using Customer.source."""
    rows = [dict(row) for row in raw_rows]
    required = {"订单行ID", "订单ID", "工厂ID", "客户ID", "产品ID", "单位", "数量"}
    if any(not required.issubset(row) for row in rows):
        return [], ["订单行缺少工厂、订单行、客户、SKU、单位或数量字段"]
    line_ids = [_key(row.get("订单行ID")) for row in rows]
    if any(value is None for value in line_ids) or len(line_ids) != len(set(line_ids)):
        return [], ["订单行ID缺失或重复，拒绝串联不同订单行"]
    groups: dict[Any, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        if row.get("工厂ID") != factory_id:
            continue
        order_id = _key(row.get("订单ID"))
        customer_id = _key(row.get("客户ID"))
        product_id = _key(row.get("产品ID"))
        unit = _key(row.get("单位"))
        channel = _key(row.get("渠道"))
        quantity = _decimal(row.get("数量"))
        if (not all((order_id, customer_id, product_id, unit)) or quantity is None or quantity <= 0):
            continue
        if _key(row.get("渠道定义列")) != "cf_smartbi_sales_channel":
            channel = None
        clean = dict(row)
        clean.update({"订单行ID": line_ids[rows.index(row)], "订单ID": order_id,
                      "客户ID": customer_id, "产品ID": product_id, "单位": unit,
                      "渠道": channel, "数量": quantity})
        if not can_price:
            for field in ("单价", "折扣率", "订单总额", "订单行小计"):
                clean.pop(field, None)
        groups[order_id].append(clean)
    if not groups:
        return [], ["没有同时具备同厂订单、客户、SKU、单位、数量和ACTIVE显式渠道的订单行"]
    candidates: list[dict[str, Any]] = []
    rejected = len(rows) - sum(len(group) for group in groups.values())
    gaps = [f"{rejected}行订单关系缺少同厂客户、SKU、单位、数量或显式渠道" ] if rejected else []
    valid_rows = [row for group in groups.values() for row in group]
    missing_channel = sum(not row.get("渠道") for row in valid_rows)
    if missing_channel:
        gaps.append(f"{missing_channel}行缺少可核验的显式订单渠道，仅保留销售-库存事实")
    if can_price:
        reconciled, reconcile_gaps = reconcile_sales_rows(valid_rows)
        by_line = {str(row.get("订单行ID")): row for row in reconciled}
        gaps.extend(item for gap in reconcile_gaps for item in gap.get("missing", []))
        for row in valid_rows:
            line = by_line.get(str(row["订单行ID"]))
            if line is None:
                continue
            row["订单行小计"] = line["订单行小计"]
            row["订单总额"] = line["订单总额"]
            candidates.append(row)
    else:
        candidates = valid_rows
    return candidates, gaps


def prepare(
    sales_rows: Iterable[dict[str, Any]],
    shipment_rows: Iterable[dict[str, Any]],
    inventory_rows: Iterable[dict[str, Any]],
    factory_id: str,
    request: Any,
    can_price: bool,
) -> tuple[pd.DataFrame | None, list[dict[str, Any]]]:
    """Return one joined detail frame, or gaps when the relation is unprovable."""
    gaps: list[dict[str, Any]] = []
    sales, sales_gaps = _sales_rows(sales_rows, factory_id, can_price)
    if sales_gaps:
        gaps.append(_gap("commerce.sales", "; ".join(sales_gaps)))
    if not sales or not _period_ok(sales, request):
        gaps.append(_gap("commerce.sales", "销售订单行缺少可核对的请求期间日期"))
        return None, gaps
    shipments, shipment_gaps = qualify_finished_goods_shipments(shipment_rows, factory_id)
    if shipment_gaps:
        gaps.extend(_gap("commerce.shipment", item)
                    for group in shipment_gaps for item in group.get("missing", []))
    inventory, inventory_gaps = qualify_finished_goods_rows(inventory_rows, factory_id)
    if inventory_gaps:
        gaps.extend(_gap("commerce.inventory", item)
                    for group in inventory_gaps for item in group.get("missing", []))
    inventory_ready = bool(inventory) and not inventory_gaps and str(getattr(request, "period_type", "all")).lower() == "all"
    if not inventory_ready:
        gaps.append(_gap("commerce.inventory", "当前AVAILABLE成品库存仅作为今天快照；历史期间或库存缺失不影响已核实的订单-发货/客户-渠道事实"))

    sales_by_line = {str(row["订单行ID"]): row for row in sales}
    batches: dict[str, dict[str, Any]] = {}
    for row in inventory:
        batch_id = _key(row.get("成品批次ID"))
        if batch_id:
            batches[batch_id] = row
    shipments_by_line: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in shipments:
        line_id = _key(row.get("销售订单行ID"))
        if line_id:
            shipments_by_line[line_id].append(row)
        else:
            gaps.append(_gap("commerce.relationship", "发货关系缺少销售订单行ID，未静默归入任何订单"))
    orphan_lines = sorted(set(shipments_by_line) - set(sales_by_line))
    if orphan_lines:
        gaps.append(_gap("commerce.relationship", f"{len(orphan_lines)}个发货关系的销售订单行ID未在同厂订单行中找到"))
    missing_lines = [line_id for line_id in sales_by_line if line_id not in shipments_by_line]
    if missing_lines:
        gaps.append(_gap("commerce.relationship", f"{len(missing_lines)}个订单行缺少可核对的成品发货关系"))
    stock_totals: defaultdict[tuple[str, str], Decimal] = defaultdict(lambda: Decimal(0))
    stock_batch_ids: defaultdict[tuple[str, str], list[str]] = defaultdict(list)
    if inventory_ready:
        for batch_id, batch in batches.items():
            product = _key(batch.get("产品ID"))
            unit = _key(batch.get("单位"))
            quantity = _decimal(batch.get("数量"))
            if product and unit and quantity is not None:
                stock_totals[(product, unit)] += quantity
                stock_batch_ids[(product, unit)].append(batch_id)
    joined: list[dict[str, Any]] = []
    for line_id, sale in sales_by_line.items():
        relations = shipments_by_line.get(line_id, [])
        if not relations:
            continue
        shipped_qty = Decimal(0)
        stock_rows: list[str] = []
        relation_batch_ids: list[str] = []
        relation_valid = True
        for relation in relations:
            if (_key(relation.get("订单ID")) != sale["订单ID"]
                    or _key(relation.get("客户ID")) != sale["客户ID"]):
                relation_valid = False
                break
            if (_key(relation.get("产品ID")) != sale["产品ID"]
                    or _key(relation.get("批次产品ID")) != sale["产品ID"]
                    or _key(relation.get("发货单位")) != sale["单位"]
                    or _key(relation.get("批次单位")) != sale["单位"]):
                relation_valid = False
                break
            batch_id = _key(relation.get("成品批次ID"))
            if (_key(relation.get("批次产品ID")) != sale["产品ID"]
                    or _key(relation.get("批次单位")) != sale["单位"]):
                relation_valid = False
                break
            batch = batches.get(batch_id)
            ownership = _key(relation.get("所有权"))
            owner_customer = _key(relation.get("所有客户ID"))
            if batch is not None:
                ownership = ownership or _key(batch.get("所有权"))
                owner_customer = owner_customer or _key(batch.get("所有客户ID"))
            if ownership == "CUSTOMER_OWNED" and owner_customer != sale["客户ID"]:
                relation_valid = False
                break
            qty = _decimal(relation.get("数量"))
            if qty is None or qty <= 0:
                relation_valid = False
                break
            shipped_qty += qty
            stock_rows.append(batch_id)
            relation_batch_ids.append(batch_id)
        if relation_valid and shipped_qty > sale["数量"]:
            relation_valid = False
        if not relation_valid:
            gaps.append(_gap("commerce.relationship", f"订单行{line_id}的发货关系未通过同厂同订单行、客户、SKU、单位、数量守恒或成品批次校验"))
            continue
        joined.append({**sale, "事实类型": "ORDER_LINE", "已核对发货数量": shipped_qty,
                       "发货数量": None,
                       "当前可用数量": None,
                       "关联成品批次ID": ",".join(sorted(set(stock_rows))),
                       "库存事实批次ID": None,
                       "产品（单位）": f"{sale.get('产品') or sale['产品ID']}（{sale['单位']}）",
                       "客户渠道产品": f"{sale.get('客户') or sale['客户ID']} / {sale['渠道']} / {sale.get('产品') or sale['产品ID']}（{sale['单位']}）",
                       "事实限定": "同厂订单行ID→已确认成品发货行→同SKU同单位成品批次；库存为当前快照"})
        for relation in relations:
            joined.append({**relation, "事实类型": "SHIPMENT_RELATION",
                           "订单行ID": line_id, "产品ID": sale["产品ID"],
                           "产品": sale.get("产品"), "单位": sale["单位"],
                           "日期": relation.get("发货日期"), "数量": None,
                           "发货数量": _decimal(relation.get("数量")),
                           "当前可用数量": None,
                           "产品（单位）": f"{sale.get('产品') or sale['产品ID']}（{sale['单位']}）",
                           "事实限定": "独立发货关系原始数量；订单与库存事实不得在此行重复累计"})
    if not any(row["事实类型"] == "ORDER_LINE" for row in joined):
        gaps.append(_gap("commerce.relationship", "没有可同时证明订单行、发货行、成品批次、SKU和单位的联合事实"))
        return None, gaps
    # Current stock is a separate, auditable grain. Never attach a SKU total
    # to an arbitrary order line or repeat a batch balance for each shipment.
    eligible_products = {(row["产品ID"], row["单位"]) for row in joined}
    if inventory_ready:
        for batch_id, batch in batches.items():
            product_unit = (_key(batch.get("产品ID")), _key(batch.get("单位")))
            if product_unit not in eligible_products:
                continue
            joined.append({**batch, "事实类型": "INVENTORY_BATCH",
                           "数量": None, "发货数量": None,
                           "当前可用数量": _decimal(batch.get("数量")),
                           "订单行ID": None, "订单ID": None, "客户ID": None,
                           "渠道": None, "库存事实批次ID": batch_id,
                           "产品（单位）": f"{batch.get('产品') or product_unit[0]}（{product_unit[1]}）",
                           "事实限定": "当前库存独立批次快照；不归属于某张订单，不代表可用于该订单履约"})
    result = pd.DataFrame(joined)
    result.attrs["commerce_trusted"] = True
    result.attrs["commerce_stock_totals"] = dict(stock_totals)
    result.attrs["commerce_stock_batches"] = {key: list(value) for key, value in stock_batch_ids.items()}
    result.attrs["commerce_inventory_ready"] = inventory_ready
    return result, gaps


def _candidate(frame: pd.DataFrame, request: Any, unit: str, family: str,
               title: str, point_dimension: str, groups: list[dict[str, Any]],
               series: list[dict[str, Any]], limitations: list[str],
               source_quantity_unit: str | None = None) -> dict[str, Any]:
    labels = [row["label"] for row in groups]
    table_dimension = point_dimension or ("客户渠道产品" if family == CUSTOMER_CHANNEL_FAMILY else "")
    option = {
        "animation": False,
        "tooltip": {"trigger": "axis", "renderMode": "richText"},
        "legend": {"data": [item["name"] for item in series], "bottom": 0},
        "grid": {"left": 12, "right": 40, "top": 40, "bottom": 72, "containLabel": True},
        "xAxis": {"type": "category", "data": labels, "axisLabel": {"rotate": 35}},
        "yAxis": {"type": "value", "name": unit},
        "series": [{"name": item["name"], "type": "bar", "data": item["data"]} for item in series],
    }
    kpis = [{"label": item["name"], "value": item["total"], "unit": item.get("unit", unit)} for item in series]
    if str(getattr(request, "period_type", "all")).lower() == "all":
        period_label = "全部历史（当前库存为今天快照）"
    else:
        period_label = f"{request.year}年{request.start_month}–{request.end_month}月"
    engine = {"trustedSource": True, "pointDimension": None, "pointDrilldown": False,
              "fact": "sales_order_item_id + finished_goods_batch_id + factory_id",
              "detail": "详情保留订单行、客户、显式渠道、发货关系和当前库存批次",
              "role": "quantity-read"}
    if source_quantity_unit:
        engine["sourceQuantityUnit"] = str(source_quantity_unit).strip()
    return {
        "id": f"{family}@{source_quantity_unit or unit}|{unit}:bar",
        "title": title,
        "metric": title,
        "domain": "销售库存" if family == SALES_STOCK_FAMILY else "销售渠道",
        "priority": "optional",
        "reason": "仅使用同厂订单行、显式渠道、发货行与成品批次关系",
        "source": DATASET_SOURCE,
        "unit": unit,
        "chart": {"chartType": "bar", "echartsOption": option},
        "kpis": kpis,
        "tableData": {"headers": [table_dimension] + [item["name"] for item in series],
                       "rows": [[row["label"]] + [item["data"][index] for item in series]
                                for index, row in enumerate(groups)]},
        "engine": engine,
        "metadata": {"trusted": True, "source": SOURCE, "fact": "同厂关系键联合",
                     "detail": "订单行级，不重复订单总额；当前库存不代表历史",
                     "role": "quantity-read", "period": period_label,
                     "unit": unit, "limitations": list(limitations)},
        "sourceMetadata": {"sourceKey": SOURCE, "sources": ["commerce-joint-sales", "captured_sources.sales_channel",
                                                                  "finished-goods-shipment", "finished-goods-inventory"],
                            "joinKey": "factory_id + sales_order_item_id + finished_goods_batch_id",
                            "channelDefinition": "ACTIVE canvas_dynamic_field sales_channel.v1",
                            "provenanceFrozen": True,
                            "currentInventorySnapshot": bool(frame.attrs.get("commerce_inventory_ready", False))},
        "findings": {"good": [], "attention": list(limitations), "limitations": list(limitations), "suggestions": []},
    }


def build(frame: pd.DataFrame, request: Any, can_price: bool) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Build two controlled charts from one already validated joint frame."""
    if frame is None or frame.empty:
        return [], [_gap("commerce.relationship", "联合明细为空，未补造销售或库存数据")]
    required = {"产品ID", "单位", "数量", "发货数量", "客户ID", "渠道", "客户渠道产品", "事实类型"}
    missing = sorted(required - set(frame.columns))
    if missing:
        return [], [_gap("commerce.relationship", "联合明细缺少" + ",".join(missing))]
    limitations = ["不同SKU或单位不合并；同一订单总额不按产品行重复累计",
                   "发货必须通过sales_order_item_id精确关联；当前库存仅为AVAILABLE快照，不倒推历史"]
    candidates: list[dict[str, Any]] = []
    gaps: list[dict[str, Any]] = []
    too_many = False
    inventory_ready = bool(frame.attrs.get("commerce_inventory_ready", "commerce_stock_totals" in frame.attrs))
    stock_totals = frame.attrs.get("commerce_stock_totals", {})
    stock_gap_added = False
    order_frame = frame[frame["事实类型"].eq("ORDER_LINE")]
    for unit, unit_frame in order_frame.groupby(order_frame["单位"].map(lambda value: str(value).strip()), sort=True):
        unit_frame = unit_frame.copy()
        product_groups: list[dict[str, Any]] = []
        product_keys: list[tuple[str, pd.DataFrame]] = []
        for product_id, group in unit_frame.groupby(unit_frame["产品ID"].map(_key), dropna=False, sort=True):
            if not product_id:
                too_many = True
                continue
            product_keys.append((product_id, group))
        labels_by_text: defaultdict[str, int] = defaultdict(int)
        for _, group in product_keys:
            labels_by_text[str(group.iloc[0].get("产品（单位）") or group.iloc[0].get("产品ID"))] += 1
        for product_id, group in product_keys:
            base_label = str(group.iloc[0].get("产品（单位）") or group.iloc[0].get("产品ID"))
            label = base_label if labels_by_text[base_label] == 1 else f"{base_label}（{product_id}）"
            product_groups.append({"label": label, "frame": group})
        if len(product_groups) > 100:
            too_many = True
        elif product_groups:
            product_units = [(_key(group["frame"].iloc[0].get("产品ID")), unit) for group in product_groups]
            stock_ready = inventory_ready and all(key in stock_totals for key in product_units)
            if stock_ready:
                stock_data = [float(stock_totals[key]) for key in product_units]
            else:
                if not stock_gap_added:
                    stock_gap_added = True
                    limitations.append("当前成品库存缺失或仅为历史期间时，不构造销售-库存KPI；订单-渠道事实仍可查看")
                    gaps.append(_gap("commerce.inventory", "当前成品库存缺失、历史期间不可用或未覆盖全部销售SKU/单位；未补零"))
                stock_data = []
            if stock_ready:
                candidates.append(_candidate(
                    unit_frame, request, unit, SALES_STOCK_FAMILY, "销售-发货-当前库存联合",
                    "产品（单位）", product_groups,
                    [{"name": "订单数量", "data": [float(g["frame"]["数量"].sum()) for g in product_groups],
                      "total": float(unit_frame["数量"].sum()), "unit": unit},
                     {"name": "已发货数量", "data": [float(g["frame"]["已核对发货数量"].sum()) for g in product_groups],
                      "total": float(unit_frame["已核对发货数量"].sum()), "unit": unit},
                     {"name": "当前可用库存", "data": stock_data,
                      "total": float(sum(stock_data)), "unit": unit}], limitations))
        channel_groups: list[dict[str, Any]] = []
        channel_keys: list[tuple[tuple[str, str, str], pd.DataFrame]] = []
        channel_frame = unit_frame[unit_frame["渠道"].map(_key).notna()]
        for key, group in channel_frame.groupby(
                [channel_frame["客户ID"].map(_key), channel_frame["渠道"].map(_key), channel_frame["产品ID"].map(_key)],
                dropna=False, sort=True):
            if not all(key):
                too_many = True
                continue
            channel_keys.append((tuple(key), group))
        labels_by_text = defaultdict(int)
        for _, group in channel_keys:
            labels_by_text[str(group.iloc[0].get("客户渠道产品"))] += 1
        for key, group in channel_keys:
            base_label = str(group.iloc[0].get("客户渠道产品"))
            label = base_label if labels_by_text[base_label] == 1 else f"{base_label}（{key[0]}|{key[2]}）"
            channel_groups.append({"label": label, "frame": group})
        if len(channel_groups) > 100:
            too_many = True
        elif channel_groups:
            value_column = "订单行小计" if can_price and "订单行小计" in unit_frame else "数量"
            value_unit = "元" if value_column == "订单行小计" else unit
            channel_title = "客户-显式渠道销售金额联合" if value_column == "订单行小计" else "客户-显式渠道销售数量联合"
            display_unit = value_unit
            candidates.append(_candidate(
                unit_frame, request, display_unit, CUSTOMER_CHANNEL_FAMILY, channel_title,
                None, channel_groups,
                [{"name": "销售金额" if value_column == "订单行小计" else "销售数量",
                  "data": [float(g["frame"][value_column].sum()) for g in channel_groups],
                  "total": float(channel_frame[value_column].sum()), "unit": value_unit}], limitations,
                source_quantity_unit=unit))
    if too_many or len(candidates) > 100:
        return [], [_gap("commerce.limit", "联合候选超过100个安全上限，请缩小产品、客户或渠道范围")]
    return candidates, gaps
