"""答案末尾的「换时间范围」按钮。

门店范围有显式出口(`_store_scope_switch_followups`)，时间范围没有 —— 用户想
「同一个问题看上个月」只能自己重新打一遍问句。

这件事**必须排在 PR #2076 之后**。在那之前做，按钮只会放大缺陷: 用户点
「看上个月」，拿到一个标着「近 30 天」的七月数据。

## 闸不能用 `_RESOLVER_DIMENSIONS['time']`

那张表里的 `time` 意思是「能不能**按**时间拆」(把结果按时间分组)，不是
「能不能**换**时间窗」(换一个查询区间)。两件事无关，实测在**两个方向**都判错:

    WASTAGE_TOP      dims 无 time, 但真能换窗口   → 维度表误拒
    STAFFING_ADVICE  dims 有 time, 但换不了       → 维度表误放(更危险)

正确判据是 resolver 签名里有没有 `date_range` —— 那正是 `resolve_by_code`
过滤 kwargs 所用的同一条判据(没声明的参数被**静默丢弃**，悄悄退回滚动窗口)，
所以按钮承诺的和分发层真交付的不可能漂。
"""
from __future__ import annotations

import pytest

from smartbi.gold.restaurant.restaurant_intent_service import _suggested_followups


def _questions(context) -> list:
    return [item["question"] for item in _suggested_followups(context)]


def _labels(context) -> list:
    return [item["label"] for item in _suggested_followups(context)]


# ── 能力闸 ────────────────────────────────────────────────────────────


def test_time_buttons_appear_for_a_window_aware_resolver():
    """WASTAGE_TOP 自 #2076 起真正按请求的窗口取数, 所以该给按钮。

    注意它的 dims 里**没有** time —— 用维度表当判据就会误拒它。
    """
    got = _questions({
        "intent": "RESTAURANT_OPS_WASTAGE_TOP",
        "window_label": "上个月",
        "question_seed": "全部门店上个月损耗金额最高的食材",
        "store_scope": "all",
        "store_options": ["A店", "B店"],
    })
    assert got, "window-aware resolver 却一个时间按钮都没给"
    assert all("损耗金额最高的食材" in q for q in got), got


def test_no_time_buttons_when_the_resolver_ignores_the_requested_window():
    """STAFFING_ADVICE 的 dims **有** time, 但它拿不到 date_range。

    给它按钮 = 提供一个「点了看起来有反应、其实答的是另一个时间窗」的操作,
    比按钮报错更隐蔽。
    """
    assert _questions({
        "intent": "RESTAURANT_OPS_STAFFING_ADVICE",
        "window_label": "上个月",
        "question_seed": "上个月排班建议",
        "store_scope": "all",
        "store_options": ["A店", "B店"],
    }) == []




def test_unknown_intent_gets_no_time_buttons():
    assert _questions({
        "intent": "RESTAURANT_OPS_NOT_A_REAL_CODE",
        "window_label": "本月",
        "question_seed": "本月营收",
    }) == []


# ── 拼问句 ────────────────────────────────────────────────────────────








def test_current_window_is_not_offered_again():
    """当前就是本月, 就别给一颗「看本月」。"""
    got = _questions({
        "intent": "RESTAURANT_OPS_WASTAGE_TOP",
        "window_label": "本月",
        "question_seed": "本月损耗金额最高的食材",
    })
    assert not any(q.startswith("本月") for q in got), got


def test_window_in_the_seed_is_not_offered_even_without_a_label():
    """window_label 缺失时, 也不能把问句里已有的那个窗口再给一遍。"""
    got = _labels({
        "intent": "RESTAURANT_OPS_WASTAGE_TOP",
        "question_seed": "最近7天损耗金额最高的食材",
    })
    assert "看最近7天" not in got, got


def test_no_question_seed_means_no_time_buttons():
    """拼不出完整问句就不给按钮 —— 与换范围按钮同一条纪律。"""
    assert _questions({
        "intent": "RESTAURANT_OPS_WASTAGE_TOP",
        "window_label": "上个月",
    }) == []


# ── 与既有按钮合流 ────────────────────────────────────────────────────
