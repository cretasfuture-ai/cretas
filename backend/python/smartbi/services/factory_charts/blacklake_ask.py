"""Answer explicit Blacklake report questions from the authorized chart points."""
import re
from dataclasses import dataclass

from smartbi.services.external_production import FINISHED_PROCESS, SOURCE_KEY, UNAVAILABLE_MESSAGE, defect_rate

from .planner_catalog import _UNIT_SCOPE, _unit

UNIT_COST_LIMIT = "黑湖成本不含制造费用"

_CODE = re.compile(r"(?:产品(?:编码)?|SKU)\s*[:：]?\s*([A-Z]+(?: [A-Z]+)?\d{5,})(?![A-Z0-9])")
_LABELS = {"good": "良品量", "defects": "不良数量", "defect_rate": "不良率", "ranking": "良品量最多的产品"}


@dataclass(frozen=True)
class ReportQuestion:
    kind: str
    metric: str = ""
    unit: str = ""

    def select(self, catalog):
        family = f"{SOURCE_KEY}.{'process' if self.kind == 'compare' else self.kind}.{self.metric}"
        return [
            row["id"] for row in catalog
            if row["familyId"] == family and (not self.unit or _unit(_source_unit(row)) == self.unit)
        ]


def recognize(question):
    unit_match = _UNIT_SCOPE.search(question)
    unit = _unit(unit_match[1]) if unit_match else ""
    finished = FINISHED_PROCESS in question
    stages = bool(re.search(r"各工序|每道工序|按工序", question))
    if re.search(r"应收|应付|利润|营收|收入|毛利|销售|库存|投入量.*出成率", question):
        return None
    if "单位成本" in question:
        return ReportQuestion("cost") if finished else None
    if "出成率" in question:
        return ReportQuestion("yield") if not stages else None
    if "成本" in question:
        return None
    metric = ("defect_rate" if "不良率" in question else "defects" if "不良" in question
              else "good" if re.search(r"良品|入库量", question) else "")
    if not metric:
        return None
    if re.search(r"出料.{0,16}比成品入库", question) and metric == "good":
        return ReportQuestion("compare", metric, unit)
    if stages and not finished:
        return ReportQuestion("process", metric, unit)
    if not finished or stages:
        return None
    if metric == "good" and re.search(r"最多的产品|产品排行|产品排名", question):
        return ReportQuestion("finished", "ranking", unit)
    if re.search(r"每日|每天|逐日|哪天|趋势", question) or (
        metric == "defect_rate" and re.search(r"累计|整月|总不良率", question)
    ):
        return ReportQuestion("finished", metric, unit)
    return None


def explicit_code(question):
    match = _CODE.search(question)
    return match[1] if match else None


def period_name(period):
    start, end = period.get("startMonth"), period.get("endMonth")
    return f"{start} 月" if start and start == end else period.get("label") or "所选期间"


def limited_answer(intent, period, stages, product):
    """Report source limits and verified empty stages before choosing charts."""
    if intent.kind == "cost":
        return (
            f"{period}没有可按成品入库报工量核算的单位成本。{UNIT_COST_LIMIT}。"
            "有权限时可在生产成本页查看黑湖已提供的工单单位成本。"
        )
    if intent.kind == "yield":
        return UNAVAILABLE_MESSAGE + "出成率。"
    if product and intent.kind in {"finished", "compare"} and not ({FINISHED_PROCESS, "出料"} & stages):
        return f"{period}产品 {product} 在黑湖快照里没有成品入库/出料记录。"
    if intent.kind in {"finished", "compare"} and FINISHED_PROCESS not in stages:
        return f"{period}在黑湖快照里没有{'该产品的' if product else ''}成品入库记录。"
    if intent.kind == "compare" and "出料" not in stages:
        return f"{period}在黑湖快照里没有该产品的出料记录，无法与成品入库比较。"
    return None


def _source_unit(candidate):
    return candidate.get("sourceQuantityUnit") or candidate["unit"]


def _rows_by_unit(candidates, family=None, scoped_unit=""):
    result = {}
    for item in candidates:
        metadata = item.get("analysis") or {}
        if family and metadata.get("familyId") != f"{SOURCE_KEY}.{family}":
            continue
        unit = metadata.get("sourceQuantityUnit") or item["unit"]
        if not scoped_unit or _unit(unit) == scoped_unit:
            result[unit] = item["tableData"]
    return result


def _value(value, unit, metric):
    if value is None:
        return "无可计算的报工量"
    if metric == "defect_rate":
        return f"{value:.3f}".rstrip("0").rstrip(".") + "%"
    return f"{value:,.3f}".rstrip("0").rstrip(".") + f" {unit}"


def render(question, intent, selected, catalog, period):
    rows = _rows_by_unit(selected)
    metric = _LABELS[intent.metric]
    if intent.kind == "compare":
        differences = []
        for unit, points in rows.items():
            values = {row["label"]: row["value"] for row in points}
            if "出料" in values and FINISHED_PROCESS in values:
                delta = round(values["出料"] - values[FINISHED_PROCESS], 3)
                comparison = ("相同" if not delta else
                              f"{'多' if delta > 0 else '少'}报 {_value(abs(delta), unit, intent.metric)}")
                differences.append(f"按 {unit} 计，出料良品量比成品入库{comparison}")
        if not rows:
            return None
        return "；".join(differences) + "。" if differences else f"{period}两道工序没有相同计量单位，无法比较良品量。"
    if "哪天" in question and "最多" in question:
        readings = []
        for unit, points in rows.items():
            value = max(row["value"] for row in points)
            dates = "、".join(row["label"] for row in points if row["value"] == value)
            readings.append(f"按 {unit} 计是 {dates}，为 {_value(value, unit, intent.metric)}")
        return f"{period}成品入库不良数量最多的日期：" + "；".join(readings) + "。" if readings else None
    if intent.metric == "defect_rate":
        # The chart excludes undefined rate points. The matching count chart
        # identifies the recorded groups for which there is no valid rate.
        counts = _rows_by_unit(catalog, f"{intent.kind}.good", intent.unit)
        if intent.kind == "finished" and re.search(r"累计|整月|总不良率", question):
            defects = _rows_by_unit(catalog, "finished.defects", intent.unit)
            readings = [
                f"按 {unit} 计为 "
                + _value(defect_rate(sum(row["value"] for row in points),
                                     sum(row["value"] for row in defects[unit])), unit, intent.metric)
                for unit, points in counts.items() if unit in defects
            ]
            return f"{period}成品入库总体不良率：" + "；".join(readings) + "。" if readings else None
        rates = {unit: {row["label"]: row["value"] for row in points} for unit, points in rows.items()}
        rows = {unit: [{"label": row["label"], "value": rates.get(unit, {}).get(row["label"])}
                       for row in points] for unit, points in counts.items()}
    if not rows:
        return None
    if intent.metric == "ranking":
        leaders = []
        for unit, points in rows.items():
            top = points[0]["value"]
            names = "、".join(row["label"].split(" · ", 1)[0] for row in points if row["value"] == top)
            leaders.append(f"{unit}组是{names}（{_value(top, unit, 'good')}）")
        first = f"{period}成品入库{metric}：{'；'.join(leaders)}。"
    elif intent.kind == "process":
        first = f"{period}各工序{metric}按 {len(rows)} 种单位列出，共 {sum(map(len, rows.values()))} 个分项。"
    else:
        first = f"{period}成品入库每日{metric}按 {len(rows)} 种单位列出。"
        if intent.metric == "defect_rate" and all(
            row["value"] == 0 for points in rows.values() for row in points
        ):
            first = f"{period}有报工的日期，成品入库每日不良率均为 0%。"
    detail = [
        f"{unit}："
        + "；".join(f"{row['label']} {_value(row['value'], unit, intent.metric)}" for row in points)
        + "。"
        for unit, points in rows.items()
    ]
    if intent.kind == "process" and intent.metric in {"defects", "defect_rate"}:
        for unit, points in rows.items():
            high = max((row for row in points if row["value"] is not None),
                       key=lambda row: row["value"], default=None)
            if high and high["value"] > 0:
                detail.append(f"按 {unit} 计，{high['label']}的{metric}最高，可先核对{period}这道工序的黑湖不良报工单。")
    return "\n".join([first, *detail])
