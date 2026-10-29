"""Optional confirmed-receipt to current material-batch inventory relation."""
from __future__ import annotations

from decimal import Decimal, InvalidOperation
from typing import Any

import pandas as pd

from .metrics import number


def build(frame: pd.DataFrame, request: Any, can_price: bool, source: str):
    title = "采购入库与批次库存"
    if not getattr(request, "include_optional", False):
        return [], []
    required = {
        "收货状态", "收货数量", "收货单位", "收货单ID", "收货行ID", "物料批次ID", "物料（单位）",
        "批次入库数量", "批次已用数量", "批次预留数量", "批次数量单位", "数量", "数量单位",
        "当前可用数量", "事实限定", "批次来源类型", "批次来源单据ID",
    }
    missing = sorted(required - set(frame.columns))
    if missing:
        return [], [{"id": "purchase.inventory", "title": title, "missing": missing}]
    if frame[["收货单ID", "收货行ID"]].duplicated().any() or frame["物料批次ID"].isna().any() or frame["物料批次ID"].astype(str).str.strip().eq("").any():
        return [], [{"id": "purchase.inventory", "title": title, "missing": ["收货单/行与物料批次关系必须唯一"]}]
    if frame["物料批次ID"].duplicated().any():
        return [], [{"id": "purchase.inventory", "title": title, "missing": ["一个物料批次不能被多个收货行重复计入"]}]
    if frame["收货状态"].ne("CONFIRMED").any() or frame["批次来源类型"].ne("PURCHASE_RECEIVE").any():
        return [], [{"id": "purchase.inventory", "title": title, "missing": ["仅已确认收货且批次反向来源为PURCHASE_RECEIVE"]}]
    quantity = frame["数量"].map(number)
    receipt = frame["批次入库数量"].map(number)
    used = frame["批次已用数量"].map(number)
    reserved = frame["批次预留数量"].map(number)
    current = frame["当前可用数量"].map(number)
    if any(value.isna().any() for value in (quantity, receipt, used, reserved, current)):
        return [], [{"id": "purchase.inventory", "title": title, "missing": ["入库/已用/预留数量必须为有限数"]}]
    available = receipt - used - reserved
    # A zero balance is a valid, fully consumed/reserved batch.  Negative
    # balance is the fail-closed case; it cannot be presented as inventory.
    valid = receipt.gt(0) & quantity.gt(0) & used.ge(0) & reserved.ge(0) & available.ge(0)
    valid &= frame["数量单位"].eq(frame["批次数量单位"])
    valid &= (current - available).abs().le(1e-9)
    if not valid.all():
        return [], [{"id": "purchase.inventory", "title": title,
                     "missing": ["批次库存必须满足入库−已用−预留≥0且单位一致"]}]
    work = frame.copy()
    work["当前可用数量"] = current
    candidates = []
    for unit, unit_frame in work.groupby("数量单位", sort=True):
        unit = str(unit).strip()
        if not unit:
            continue
        grouped = unit_frame.groupby("物料（单位）", sort=True)
        points = [(str(label), float(group["当前可用数量"].sum())) for label, group in grouped]
        if not points:
            continue
        series = []
        for label, group in grouped:
            series.append({
                "label": str(label),
                "入库": float(group["批次入库数量"].sum()),
                "已用": float(group["批次已用数量"].sum()),
                "预留": float(group["批次预留数量"].sum()),
                "可用": float(group["当前可用数量"].sum()),
            })
        labels = [item["label"] for item in series]
        option = {
            "animation": False,
            "tooltip": {"trigger": "axis"},
            "legend": {"data": ["已确认入库", "已用", "预留", "当前可用"], "bottom": 0},
            "grid": {"left": 12, "right": 40, "top": 40, "bottom": 72, "containLabel": True},
            "xAxis": {"type": "category", "data": labels},
            "yAxis": {"type": "value", "name": unit},
            "series": [
                {"name": "已确认入库", "type": "bar", "data": [item["入库"] for item in series]},
                {"name": "已用", "type": "bar", "stack": "库存分段", "data": [item["已用"] for item in series]},
                {"name": "预留", "type": "bar", "stack": "库存分段", "data": [item["预留"] for item in series]},
                {"name": "当前可用", "type": "bar", "stack": "库存分段", "data": [item["可用"] for item in series]},
            ],
        }
        totals = {key: sum(item[key] for item in series) for key in ("入库", "已用", "预留", "可用")}
        candidate = {
            "id": f"purchase.inventory@{unit}:bar",
            "title": f"{title}（{unit}）",
            "metric": f"{title}（{unit}）",
            "domain": "库存",
            "priority": "optional",
            "reason": "已确认收货关联批次；入库、已用、预留与当前可用库存逐批核对",
            "source": source,
            "unit": unit,
            "chart": {"chartType": "bar", "echartsOption": option},
            "kpis": [
                {"label": "已确认入库量", "value": totals["入库"], "unit": unit},
                {"label": "已用量", "value": totals["已用"], "unit": unit},
                {"label": "预留量", "value": totals["预留"], "unit": unit},
                {"label": "当前可用量", "value": totals["可用"], "unit": unit},
            ],
            "tableData": {"headers": ["物料（单位）", "已确认入库", "已用", "预留", "当前可用"],
                          "rows": [[item["label"], item["入库"], item["已用"], item["预留"], item["可用"]]
                                   for item in series]},
            "findings": {"good": [], "attention": [], "limitations": [], "suggestions": []},
        }
        candidate["findings"]["attention"].extend([
            "仅包含已确认收货且已关联的物料批次；不包含未到货采购订单或全厂其它库存",
            "期间筛选仅作用于收货日期；批次库存为查询当前时点快照，不代表历史期末余额",
            "当前可用量=批次入库−已用−预留，不是采购计划量、原料投入总量或采购金额",
        ])
        candidate["findings"]["limitations"] = list(candidate["findings"]["attention"])
        candidate["engine"] = {
            "trustedSource": True, "pointDimension": "物料（单位）", "pointDrilldown": False,
            "fact": "confirmed purchase receipt ↔ material batch",
            "detail": "收货单/行、物料批次、入库/已用/预留/当前可用数量；不含价格",
            "role": "quantity-read", "unit": unit,
        }
        candidate["metadata"] = {
            "trusted": True, "fact": "CONFIRMED purchase_receive_items ↔ material_batches",
            "role": "quantity-read", "unit": unit,
            "limitations": ["仅已确认收货关联批次", "不包含未到货订单和全厂其它库存", "库存为当前时点快照"],
        }
        candidates.append(candidate)
    return candidates, []
