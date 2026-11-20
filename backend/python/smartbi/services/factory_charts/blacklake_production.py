"""Blacklake candidates keep production stages and units explicitly scoped."""
from datetime import timezone
import logging

import pandas as pd

from smartbi import config
from smartbi.gold.external_production_etl import DEMO_FACTORY_PREFIX
from smartbi.services.external_production import (
    FINISHED_PROCESS,
    SOURCE_KEY,
    SOURCE_LABEL,
    UNAVAILABLE_MESSAGE,
    defect_rate,
    load_external_production,
)

from .engine import Dataset, series_candidate
from .metrics import Series

_STAGE_SCOPE = "工序单独统计；同一批物料经过多个工序时，各工序的产量不相加"
logger = logging.getLogger(__name__)


class SourceEligibilityUnavailable(RuntimeError):
    """The tenant's external production source could not be verified."""


async def _has_blacklake_snapshot(factory_id: str) -> bool:
    """Verify active authorization and snapshots in the Cretas source."""
    pool = await config.get_cretas_pool()
    if pool is None:
        raise RuntimeError("Cretas source pool unavailable")
    async with pool.acquire() as conn:
        return await conn.fetchval(
            """SELECT EXISTS (
                   SELECT 1 FROM external_source_snapshots AS s
                   JOIN factory_external_integrations AS i
                     ON i.factory_id = s.factory_id AND i.external_system = s.external_system
                   WHERE s.factory_id = $1 AND s.external_system = 'blacklake'
                     AND s.dataset = 'report_output' AND i.status = 'ACTIVE'
               )""",
            factory_id,
        )


async def load(factory_id, request):
    from .sources import dates

    if factory_id.startswith(DEMO_FACTORY_PREFIX):
        return None, None
    try:
        has_snapshot = await _has_blacklake_snapshot(factory_id)
    except Exception as exc:
        logger.warning("External production source discovery failed for %s", factory_id, exc_info=True)
        raise SourceEligibilityUnavailable from exc
    if not has_snapshot:
        return None, None

    start, end = dates(request)
    try:
        data = await load_external_production(factory_id, start, end)
    except ValueError as exc:
        return None, {"source": SOURCE_LABEL, "message": str(exc)}
    except Exception:
        return None, {"source": SOURCE_LABEL, "message": "黑湖生产数据读取失败，请重试"}
    if data is None or not data.rows:
        return None, None
    collected = data.collected_at.astimezone(timezone.utc).isoformat(timespec="minutes")
    freshness = " · 数据未更新" if data.stale else ""
    source = f"{SOURCE_KEY} · {SOURCE_LABEL}生产报工 · 快照采集 {collected}{freshness}"
    frame = pd.DataFrame([
        {
            "日期": row["report_date"].isoformat(),
            # External SKU codes belong to their source's namespace.
            "产品ID": f"blacklake:{row['product_code']}" if row["product_code"] else None,
            "产品": row["product_name"] or row["product_code"] or None,
            "产品编码": row["product_code"],
            "工序": row["process_name"],
            "单位": row["unit"],
            "良品数": float(row["good_qty"]),
            "不良数": float(row["defect_qty"]),
        }
        for row in data.rows
    ])
    return Dataset(frame, source), None


def build(dataset):
    frame = dataset.frame
    candidates = []
    gaps = [{"id": "inputs", "title": "黑湖出成率和生产成本", "missing": [UNAVAILABLE_MESSAGE]}]

    def add(key, title, unit, points, kind, *, scope, source_unit=None,
            aggregate="sum", weights=(None, None)):
        if not points:
            return
        series = Series(key, title, "生产", unit, points, dataset.source, aggregate, *weights)
        candidate = series_candidate(series, kind)
        candidate["engine"] = {"pointDrilldown": False}
        if source_unit:
            candidate["engine"]["sourceQuantityUnit"] = source_unit
            candidate["title"] = f"{title}（{source_unit}）"
        candidate["findings"]["limitations"] = [scope, _STAGE_SCOPE, UNAVAILABLE_MESSAGE]
        candidates.append(candidate)

    for unit, unit_rows in frame[frame["单位"].ne("")].groupby("单位"):
        # Separate stage comparison: the points are comparable within a unit,
        # but summing across stages would count some production more than once.
        stages = unit_rows[unit_rows["工序"].ne("")].groupby("工序")[["良品数", "不良数"]].sum(numeric_only=False)
        for metric, key in (("良品数", "good"), ("不良数", "defects")):
            add(f"process.{key}@{unit}", f"按工序{metric}", unit,
                [(stage, float(qty)) for stage, qty in stages[metric].items()],
                "bar_horizontal", scope="按相同单位比较各工序", aggregate="nonadditive")
        add(f"process.defect_rate@{unit}", "按工序报工不良率", "%",
            [(stage, float(rate)) for stage, row in stages.iterrows()
             if (rate := defect_rate(row["良品数"], row["不良数"])) is not None],
            "bar_horizontal", scope=f"按 {unit} 计算各工序的不良÷（良品＋不良）", source_unit=unit,
            aggregate="nonadditive")

        finished = unit_rows[unit_rows["工序"].eq(FINISHED_PROCESS)]
        if finished.empty:
            continue
        daily = finished.groupby("日期")[["良品数", "不良数"]].sum(numeric_only=False)
        for metric, key in (("良品数", "good"), ("不良数", "defects")):
            add(f"finished.{key}@{unit}", f"成品入库每日{metric}", unit,
                [(day, float(qty)) for day, qty in daily[metric].items()],
                "line", scope="仅纳入成品入库工序的报工")
        if not daily.empty:
            good, defect = daily["良品数"].sum(), daily["不良数"].sum()
            add(f"finished.defect_rate@{unit}", "成品入库报工不良率趋势", "%",
                [(day, float(rate)) for day, row in daily.iterrows()
                 if (rate := defect_rate(row["良品数"], row["不良数"])) is not None],
                "line", scope=f"仅纳入成品入库工序，按 {unit} 计算不良÷（良品＋不良）", source_unit=unit,
                aggregate="ratio", weights=(float(defect), float(good + defect)))

        ranking = finished[finished["产品编码"].ne("")].groupby("产品编码")["良品数"].sum(numeric_only=False)
        names = finished.groupby("产品编码")["产品"].first()
        points = [(f"{names[code]} · {code}" if names[code] != code else code, float(qty))
                  for code, qty in ranking.sort_values(ascending=False, kind="stable").items()]
        add(f"finished.ranking@{unit}", "成品入库良品排行", unit, points, "bar_horizontal",
            scope="按产品编码汇总成品入库良品；无编码记录不参与排行")
    if frame["单位"].eq("").any():
        gaps.append({"id": "unit", "title": "黑湖报工单位", "missing": ["未登记单位的记录不参与数量汇总"]})
    return candidates, gaps
