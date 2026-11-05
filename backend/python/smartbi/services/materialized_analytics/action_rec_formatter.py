"""Neutral deterministic action text and measure aggregation helpers."""
from __future__ import annotations


def format_action_rec(object_target: str, benefit_range: str, prerequisite: str, timeline: str) -> str:
    return f"建议: 针对【{object_target}】{benefit_range} (前置: {prerequisite}, {timeline}完成)"


def dim_aware_top_n_action_rec(primary_dim: str, measure: str, top_label: str, bottom_label: str, gap_pct: float) -> str:
    return format_action_rec(
        object_target=f"末位{primary_dim}「{bottom_label}」 (与 Top「{top_label}」差距 {gap_pct:.0f}%)",
        benefit_range=f"复制 Top「{top_label}」的标准作业可提升 {measure} 5-10%",
        prerequisite=f"对标 Top「{top_label}」并复盘末位{primary_dim}",
        timeline="本季度",
    )


def aggregation_for_measure(col: str | None) -> str:
    if not col:
        return "sum"
    if any(token in col for token in ("率", "比率", "占比", "百分比", "星级", "评分", "客单价", "人均")):
        return "avg"
    return "sum"


def measure_unit(col: str | None) -> str:
    if not col:
        return ""
    if any(token in col for token in ("星级", "评分", "分数", "得分")):
        return "分"
    if any(token in col for token in ("率", "比率", "占比", "百分比")):
        return "%"
    return "元" if any(token in col for token in ("金额", "营收", "销售额", "营业额", "成本")) else ""
