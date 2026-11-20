"""Expose authorized Blacklake work-order cost to the existing chart planner."""
from datetime import datetime, timedelta, timezone
import logging

import pandas as pd

from smartbi.services.external_production import load_external_cost

from . import cost_scope
from .engine import Dataset, series_candidate
from .metrics import Series

SOURCE_KEY = "blacklake-cost"
SOURCE_LABEL = "黑湖工单成本"
_LIMITS = [
    "按黑湖工单成本日期汇总，不含制费",
    "缺价和待核领料行不计入领料金额，有产出的零单价工单不计入平均单位成本",
    "趋势排除含负材料或负总成本的日期，产品对比排除含该类工单的产品",
    "仅包含有黑湖总成本的工单",
]
logger = logging.getLogger(__name__)


async def load(factory_id, start, end, auth_request):
    try:
        data = await load_external_cost(factory_id, start, end)
        if not data or not data.work_orders:
            return None, None
        access = await cost_scope.resolve_cost_scope(auth_request, factory_id, [])
        if not access.is_full:
            return None, None
    except Exception:
        logger.warning("Blacklake cost chart source unavailable", exc_info=True)
        return None, {"source": "外部工单成本", "message": "暂时无法核实或读取本厂外部工单成本"}

    collected = data.snapshots.get("project_cost")
    timestamp = f" · 快照采集 {collected.astimezone(timezone.utc).isoformat(timespec='minutes')}" if collected else ""
    freshness = " · 数据未更新" if collected and datetime.now(timezone.utc) - collected > timedelta(hours=36) else ""
    frame = pd.DataFrame([
        {
            "日期": row["cost_date"].isoformat(),
            "产品ID": f"blacklake:{row['product_code']}",
            "产品编码": row["product_code"],
            "产品": row["product_name"] or row["product_code"],
            "总成本": row["total_cost"],
            "负成本": (row["material_cost"] or 0) < 0 or (row["total_cost"] or 0) < 0,
        }
        for row in data.work_orders
    ])
    return Dataset(frame, f"{SOURCE_KEY} · {SOURCE_LABEL}{timestamp}{freshness}", financial=True, currency="CNY"), None


def build(dataset):
    frame = dataset.frame
    candidates = []
    gaps = []
    for column, key, title, kind in (
        ("日期", "trend", "黑湖每日工单总成本趋势", "line"),
        ("产品编码", "product", "黑湖产品工单总成本对比", "bar_horizontal"),
    ):
        excluded = set(frame.loc[frame["负成本"], column])
        rows = frame.loc[~frame[column].isin(excluded) & frame["总成本"].notna()]
        points = []
        for identity, group in rows.groupby(column, sort=True):
            name = group["产品"].iloc[0]
            label = f"{name} · {identity}" if column == "产品编码" and name != identity else identity
            points.append((label, float(group["总成本"].sum())))
        if not points:
            gap = ("所选记录涉及负材料成本或负工单总成本，排除后无可分析数据"
                   if excluded else "所选工单未提供黑湖总成本")
            gaps.append({"id": f"work-order-total.{key}", "title": title, "missing": [gap]})
            continue
        series = Series(f"work-order-total.{key}", title, "成本", "元", points, dataset.source)
        candidate = series_candidate(series, kind)
        candidate["engine"] = {"pointDrilldown": False}
        candidate["findings"].setdefault("limitations", []).extend(_LIMITS)
        candidates.append(candidate)
    return candidates, gaps
