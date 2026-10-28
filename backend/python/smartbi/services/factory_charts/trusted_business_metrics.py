"""Named catalogue metrics for the relationship-backed business sources.

The normal factory builder intentionally supports broad uploaded reports.  It
must not reinterpret trusted source IDs or opaque lineage values as generic
numeric metrics, so these four source families have an explicit catalogue
surface with a fact, detail, role, period, and unit contract.
"""
from __future__ import annotations

from collections import defaultdict
from typing import Any

import pandas as pd

from .metrics import Series, number


TRUSTED_SOURCE_PREFIXES = frozenset({
    "customer-source-sales",
    "purchase-batch-lineage",
    "finished-goods-shipment",
    "finished-goods-inventory",
})


def _period(request: Any) -> str:
    if str(getattr(request, "period_type", "all")).lower() == "all":
        return "全部历史（当前库存仍仅为今天快照）"
    return f"{request.year}年{request.start_month}–{request.end_month}月"


def _metadata(source: str, request: Any, unit: str, *, fact: str, detail: str, role: str,
              limitations: list[str]) -> dict[str, Any]:
    return {
        "trusted": True,
        "source": source,
        "fact": fact,
        "detail": detail,
        "role": role,
        "period": _period(request),
        "unit": unit,
        "limitations": list(limitations),
    }


def _candidate(frame: pd.DataFrame, source: str, request: Any, key: str, title: str,
               domain: str, unit: str, points: list[tuple[str, float]], *, fact: str,
               detail: str, role: str, limitations: list[str], point_dimension: str,
               detail_unit_policy: str | None = None,
               kind: str = "bar_horizontal"):
    from .engine import series_candidate

    series = Series(key, title, domain, unit, points, source)
    candidate = series_candidate(series, kind)
    candidate["metadata"] = _metadata(source, request, unit, fact=fact, detail=detail,
                                       role=role, limitations=limitations)
    candidate.setdefault("engine", {})["trustedSource"] = True
    candidate["engine"]["pointDimension"] = point_dimension
    if detail_unit_policy:
        candidate["engine"]["detailUnitPolicy"] = detail_unit_policy
    candidate["engine"]["fact"] = fact
    candidate["engine"]["detail"] = detail
    candidate["engine"]["role"] = role
    candidate["engine"]["period"] = candidate["metadata"]["period"]
    candidate["engine"]["unit"] = unit
    candidate["findings"].setdefault("limitations", []).extend(limitations)
    candidate["findings"]["attention"].extend(limitations)
    return candidate


def _gap(source: str, key: str, title: str, missing: list[str]) -> dict[str, Any]:
    return {"id": key, "title": title, "missing": missing, "source": source}


def _numeric(frame: pd.DataFrame, column: str) -> pd.Series | None:
    if column not in frame:
        return None
    result = frame[column].map(number)
    return result if result.notna().all() else None


def _period_guard(frame: pd.DataFrame, request: Any) -> str | None:
    if str(getattr(request, "period_type", "all")).lower() == "all":
        return None
    if "日期" not in frame:
        return "日期列"
    parsed = pd.to_datetime(frame["日期"], errors="coerce")
    if parsed.isna().any():
        return "全部日期必须可解析，不能把无日期行声称为期间内事实"
    if not (parsed.dt.year.eq(int(request.year))
            & parsed.dt.month.between(int(request.start_month), int(request.end_month))).all():
        return "来源行必须落在请求期间内"
    return None


def _customer_source(frame: pd.DataFrame, source: str, request: Any, can_price: bool,
                     dataset: Any) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    title = "客户来源销售金额（获客归因，非订单销售渠道）"
    if not can_price or getattr(dataset, "currency", "") != "CNY":
        return [], [_gap(source, "customer_source.sales", title,
                          ["金额查看权限与明确CNY单位；Customer.source不是captured order channel"])]
    required = ["客户来源", "订单行小计", "订单ID", "订单行ID", "日期"]
    missing = [column for column in required if column not in frame]
    period_gap = _period_guard(frame, request)
    if period_gap:
        missing.append(period_gap)
    if missing:
        return [], [_gap(source, "customer_source.sales", title, missing)]
    if frame["客户来源"].isna().any() or frame["客户来源"].astype(str).str.strip().eq("").any():
        return [], [_gap(source, "customer_source.sales", title, ["每行必须有已登记Customer.source"])]
    amount = _numeric(frame, "订单行小计")
    if amount is None or amount.lt(0).any() or frame["订单行ID"].duplicated().any():
        return [], [_gap(source, "customer_source.sales", title,
                          ["唯一订单行ID与已逐单对账的非负订单行小计"])]
    work = frame.copy()
    work["订单行小计"] = amount
    points = [(str(key), float(group["订单行小计"].sum()))
              for key, group in work.groupby("客户来源", sort=True)]
    if not points:
        return [], [_gap(source, "customer_source.sales", title, ["客户来源销售明细"])]
    limitation = "客户来源来自Customer.source，仅表示获客归因；非订单销售渠道，不能解释为订单动态字段"
    candidate = _candidate(
        work, source, request, "sales.customer_source", title, "销售", "元", points,
        fact="customers.source + sales_orders.customer_id + sales_order_items.sales_order_id",
        detail="订单行级已对账金额；详情仍受同一source/期间/角色上下文约束",
        role="price-sensitive", limitations=[limitation], point_dimension="客户来源", kind="bar_horizontal",
    )
    return [candidate], []


def _purchase_lineage(frame: pd.DataFrame, source: str, request: Any, can_price: bool,
                      dataset: Any) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    title = "确认收货物料批次入库数量（快照守恒）"
    required = ["收货单ID", "收货行ID", "收货状态", "收货数量", "收货单位",
                "物料ID", "批次物料ID", "批次工厂ID", "批次来源类型", "批次来源单据ID",
                "批次数量单位", "批次入库数量", "包装单位快照", "包装换算因子快照",
                "库存数量快照", "库存基础单位快照", "数量", "数量单位", "物料（单位）",
                "事实限定"]
    missing = [column for column in required if column not in frame]
    period_gap = _period_guard(frame, request)
    if period_gap:
        missing.append(period_gap)
    if missing:
        return [], [_gap(source, "purchase.material_batch.receipt", title, missing)]
    if frame["收货状态"].ne("CONFIRMED").any() or frame["批次来源类型"].ne("PURCHASE_RECEIVE").any():
        return [], [_gap(source, "purchase.material_batch.receipt", title, ["仅CONFIRMED且反向来源为PURCHASE_RECEIVE"])]
    if frame["物料ID"].ne(frame["批次物料ID"]).any():
        return [], [_gap(source, "purchase.material_batch.receipt", title, ["收货行与物料批次物料类型必须一致"])]
    if frame[["收货单ID", "收货行ID"]].duplicated().any():
        return [], [_gap(source, "purchase.material_batch.receipt", title, ["每个收货单/行只能有一条已验证批次事实"])]
    quantity = _numeric(frame, "数量")
    batch_qty = _numeric(frame, "批次入库数量")
    snapshot = _numeric(frame, "库存数量快照")
    factor = _numeric(frame, "包装换算因子快照")
    if any(value is None for value in (quantity, batch_qty, snapshot, factor)):
        return [], [_gap(source, "purchase.material_batch.receipt", title, ["收货原值、规范化数量、批次入库与库存快照必须为有限数"])]
    valid = quantity.gt(0) & batch_qty.gt(0) & snapshot.gt(0) & factor.gt(0)
    received = _numeric(frame, "收货数量")
    if received is None:
        return [], [_gap(source, "purchase.material_batch.receipt", title, ["收货原值数量必须为有限数"])]
    valid &= received.mul(factor).eq(snapshot)
    valid &= snapshot.eq(batch_qty)
    valid &= frame["收货单位"].notna() & frame["批次数量单位"].notna() & frame["数量单位"].eq(frame["批次数量单位"])
    valid &= frame["库存基础单位快照"].eq(frame["批次数量单位"])
    if not valid.all():
        return [], [_gap(source, "purchase.material_batch.receipt", title,
                          ["每行必须满足收货数量×已记录换算因子=库存快照=批次入库数量，且单位一致"])]
    work = frame.copy()
    points = [(str(material_unit), float(group["数量"].sum()))
              for material_unit, group in work.groupby("物料（单位）", dropna=False)]
    candidate = _candidate(
        work, source, request, "purchase.material_batch.receipt", title, "采购", "数量", points,
        fact="purchase_receive_items.material_batch_id ↔ material_batches.source_doc_type/source_doc_id",
        detail="收货行与单个物料批次数量快照；不分摊采购金额或跨单位求和",
        role="quantity-read", limitations=["仅确认收货；数量快照守恒，未知换算不补推"],
        point_dimension="物料（单位）",
        detail_unit_policy="trusted_quantity_rows",
    )
    return [candidate], []


def _shipment(frame: pd.DataFrame, source: str, request: Any, can_price: bool,
              dataset: Any) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    title = "成品批次已记录发货数量（单位守恒）"
    required = ["发货行ID", "发货单ID", "成品批次ID", "产品ID", "发货数量", "发货单位", "发货状态",
                "批次工厂ID", "批次产品ID", "批次单位", "数量", "数量单位", "产品（单位）",
                "关系来源", "事实限定"]
    missing = [column for column in required if column not in frame]
    period_gap = _period_guard(frame, request)
    if period_gap:
        missing.append(period_gap)
    if missing:
        return [], [_gap(source, "finished_goods.shipment", title, missing)]
    if frame["发货状态"].isin(["SHIPPED", "DELIVERED"]).eq(False).any():
        return [], [_gap(source, "finished_goods.shipment", title, ["仅SHIPPED或DELIVERED发货单"])]
    if frame["产品ID"].ne(frame["批次产品ID"]).any() or frame["发货单位"].ne(frame["批次单位"]).any():
        return [], [_gap(source, "finished_goods.shipment", title, ["发货行与成品批次必须同SKU同单位，不做换算"])]
    quantity = _numeric(frame, "数量")
    delivered = _numeric(frame, "发货数量")
    if quantity is None or delivered is None or quantity.le(0).any() or quantity.ne(delivered).any():
        return [], [_gap(source, "finished_goods.shipment", title, ["正发货数量"])]
    work = frame.copy()
    points = [(str(product_unit), float(group["数量"].sum()))
              for product_unit, group in work.groupby("产品（单位）", dropna=False)]
    candidate = _candidate(
        work, source, request, "finished_goods.shipment", title, "库存", "数量", points,
        fact="sales_delivery_items.finished_goods_batch_id OR sales_delivery_item_batch_allocations.finished_goods_batch_id",
        detail="按已记录发货关系；allocation先按发货行守恒，不作为销售金额分摊",
        role="quantity-read", limitations=["allocation合计必须等于申报发货数量；不做单位换算"],
        point_dimension="产品（单位）",
        detail_unit_policy="trusted_quantity_rows",
    )
    return [candidate], []


def _finished_goods(frame: pd.DataFrame, source: str, request: Any, can_price: bool,
                    dataset: Any) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    title = "独立成品库存当前快照（不倒推历史）"
    required = ["日期", "成品批次ID", "工厂ID", "产品ID", "单位", "已生产数量",
                "已发货数量", "已预留数量", "可用数量", "状态", "数量", "数量单位",
                "产品（单位）", "事实限定"]
    missing = [column for column in required if column not in frame]
    if missing:
        return [], [_gap(source, "finished_goods.inventory.current", title, missing)]
    if frame["状态"].ne("AVAILABLE").any():
        return [], [_gap(source, "finished_goods.inventory.current", title, ["仅AVAILABLE成品批次"])]
    amounts = {column: _numeric(frame, column) for column in ("已生产数量", "已发货数量", "已预留数量", "可用数量")}
    if any(value is None for value in amounts.values()):
        return [], [_gap(source, "finished_goods.inventory.current", title, ["生产/发货/预留/可用数量必须为有限数"])]
    valid = amounts["已生产数量"].gt(0) & amounts["已发货数量"].ge(0) & amounts["已预留数量"].ge(0)
    valid &= amounts["可用数量"].gt(0)
    valid &= amounts["已生产数量"].sub(amounts["已发货数量"]).sub(amounts["已预留数量"]).eq(amounts["可用数量"])
    valid &= _numeric(frame, "数量").eq(amounts["可用数量"])
    valid &= frame["数量单位"].eq(frame["单位"])
    if not valid.all() or frame["成品批次ID"].duplicated().any():
        return [], [_gap(source, "finished_goods.inventory.current", title,
                          ["每个当前批次必须满足生产−发货−预留=正可用余额且批次唯一"])]
    work = frame.copy()
    points = [(str(product_unit), float(group["数量"].sum()))
              for product_unit, group in work.groupby("产品（单位）", dropna=False)]
    candidate = _candidate(
        work, source, request, "finished_goods.inventory.current", title, "库存", "数量", points,
        fact="finished_goods_batches.produced_quantity − shipped_quantity − reserved_quantity",
        detail="仅当前AVAILABLE批次余额；不使用source_sales_order_id作外键、不重建历史余额",
        role="quantity-read", limitations=["当前快照，不代表历史期间库存；不同单位不相加"],
        point_dimension="产品（单位）",
        detail_unit_policy="trusted_quantity_rows",
    )
    return [candidate], []


def build(frame: pd.DataFrame, cols: dict[str, Any], request: Any, can_price: bool,
          source: str, dataset: Any = None) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Build only named metrics for the four trusted adapter datasets."""
    prefix = str(source).split(" · ", 1)[0]
    if prefix == "customer-source-sales":
        return _customer_source(frame, source, request, can_price, dataset)
    if prefix == "purchase-batch-lineage":
        return _purchase_lineage(frame, source, request, can_price, dataset)
    if prefix == "finished-goods-shipment":
        return _shipment(frame, source, request, can_price, dataset)
    if prefix == "finished-goods-inventory":
        return _finished_goods(frame, source, request, can_price, dataset)
    return [], []
