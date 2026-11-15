"""图表混单位闸（原型 fusion-smartbi 的台账规则，正式版）。

规则：数据里带「单位」列、并且要画的是数量类字段（数量/重量/用量/库存/耗用…）时，
所有行必须是同一个明确的单位。出现多个单位，或有行没填单位，一律拒绝绘图并写明原因，
**不静默折算、不把不同单位画进同一张图、不把缺失画成 0**。调用方应按单位筛选后再绘图。

没有单位列的数据、以及只画金额/比率等非数量字段的图，不受此闸影响。
"""
from __future__ import annotations

import re
from typing import Any, Dict, Iterable, List, Optional

UNIT_FIELD_NAMES = ("单位", "计量单位", "unit", "uom")
_QUANTITY_RE = re.compile(
    r"数量|重量|用量|耗用|库存|已发|需求|退回|产量|投入|产出|qty|quantity|weight|amount_kg|stock",
    re.IGNORECASE,
)


def find_unit_field(rows: Iterable[Dict[str, Any]]) -> Optional[str]:
    """返回第一行里出现的单位列名（按 UNIT_FIELD_NAMES 优先级），没有则 None。"""
    keys: set = set()
    for row in rows:
        if isinstance(row, dict):
            keys.update(row.keys())
    lowered = {str(k).lower(): k for k in keys}
    for name in UNIT_FIELD_NAMES:
        if name in lowered:
            return lowered[name]
    return None


def is_quantity_field(name: Optional[str]) -> bool:
    return bool(name) and bool(_QUANTITY_RE.search(str(name)))


def mixed_unit_error(
    rows: List[Dict[str, Any]],
    y_fields: Optional[List[str]],
) -> Optional[str]:
    """违反规则返回给用户看的中文原因，通过返回 None。"""
    if not rows:
        return None
    # 未指定 yFields 时图表构建器会自动挑数值列，这里按全部列名判断，不能因为没点名就放行。
    candidates = list(y_fields) if y_fields else sorted({k for r in rows if isinstance(r, dict) for k in r})
    quantity_fields = [f for f in candidates if is_quantity_field(f)]
    if not quantity_fields:
        return None
    unit_field = find_unit_field(rows)
    if unit_field is None:
        return None
    units = set()
    blank = 0
    for row in rows:
        raw = row.get(unit_field) if isinstance(row, dict) else None
        text = str(raw).strip() if raw is not None else ""
        if text:
            units.add(text)
        else:
            blank += 1
    if blank:
        return (
            f"数量类字段（{'、'.join(quantity_fields)}）有 {blank} 行没有填写“{unit_field}”，"
            "无法确定单位。请补齐单位或按明确的单一单位筛选后再绘图。"
        )
    if len(units) > 1:
        return (
            f"数据包含多个单位（{'、'.join(sorted(units))}），数量类字段（{'、'.join(quantity_fields)}）"
            "不能画进同一张图。请按明确的单一单位筛选后绘图，系统不会合并或折算不同单位。"
        )
    return None
