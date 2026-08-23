# -*- coding: utf-8 -*-
"""模型给出一个**没登记的维度名**时，不许把整份计划一起扔掉。

## 被守的行为（⛔ 不是「supplier 这个词」）

2026-08-19 prod 实测（MOCK_REST，`smartbi.scripts._flipflop_layers`，冷编译）：
「哪个供应商报价最贵」这一句，模型返回的 JSON **除了 `dimensions` 一格
全都逐字相同**（`intent=RESTAURANT_OPS_SUPPLIER_PRICE` / `confidence=0.95` /
`aggregation=extremes`），只有那一格在

    ["ingredient"]  /  []  /  ["supplier"]

之间摆动。前两种作答且**答案指纹完全一样**（b216e623）；第三种整份计划作废，
老板收到 45 字的「我还没有完整理解这句话，本次没有按关键词猜测…」。

▎**那句话是假的** —— 系统理解得好好的，它只是不认识 `supplier` 这个词。

## 判据挂在哪（⛔ 不挂在文案上）

一律读 `planner_authority` / `intent` / `dimensions` 这些**结构字段**，
⛔ 不读那句拒答文本（本仓有前科：按文案写的判据在文案一改时静默失效）。

## 每条正向断言都配一条能让它红的对照（⛔ 不许只有正向）

    正向  未登记的维度值 → 计划照编，那一维被丢掉
    对照① 形状坏了（不是列表 / 元素不是字符串）→ **仍然** fail closed
    对照② `requested_metrics` 的未知值      → **仍然** fail closed
    对照③ `missing_fields` 的未知值          → **仍然** fail closed
    对照④ 已登记的维度值                     → 逐字保持原行为
"""
from __future__ import annotations

import os
import sys

import pytest

sys.path.insert(
    0,
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(
        os.path.abspath(__file__))))),
)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402

#: prod 实测抓到的那份模型输出（`_flipflop_layers` 的 L0 原文，逐字）。
#: ⛔ 不手搓一个「差不多的」—— 手搓的那份会漂，而漂的方向恰好是「测不到真缺陷」。
UNKNOWN_DIMENSION_PLAN = {
    "intent": "RESTAURANT_OPS_SUPPLIER_PRICE",
    "time_range": None,
    "wants_margin": False,
    "asks_profitability": False,
    "requested_metrics": [],
    "analysis_action": "lookup",
    "aggregation": "extremes",
    "dimensions": ["supplier"],
    "comparison": None,
    "dish": None,
    "store": None,
    "stores": [],
    "store_scope": None,
    "confidence": 0.95,
    "clarification_needed": False,
    "missing_fields": [],
    "clarification_question": None,
    "clarification_options": [],
}

UNKNOWN_DIMENSION_QUERY = "哪个供应商报价最贵"

#: 契约不完整时 `_semantic_spec_from_t3` 打在 spec 上的来源标记。
CONTRACT_INCOMPLETE_AUTHORITY = "llm_contract_incomplete"


def _compile(**overrides):
    plan = dict(UNKNOWN_DIMENSION_PLAN)
    plan.update(overrides)
    return ri._semantic_spec_from_t3(plan, UNKNOWN_DIMENSION_QUERY)


def test_registry_really_does_not_know_this_dimension():
    """阳性对照：`supplier` 真的不在取值域里。

    ⛔ 没有这一条，下面所有断言都可能是在测一个**已经合法**的值 ——
       那时它们全绿，而它们一件事都没守住。
    """
    assert "supplier" not in ri._SEMANTIC_DIMENSIONS
    assert "ingredient" in ri._SEMANTIC_DIMENSIONS   # 阴性侧：词表本身是活的


def test_unknown_dimension_does_not_discard_the_whole_plan():
    spec = _compile()
    assert spec.planner_authority != CONTRACT_INCOMPLETE_AUTHORITY, (
        "未登记的维度值让整份计划作废了 —— 老板会收到一句"
        "「我还没有完整理解这句话」，而系统其实理解得好好的")
    assert spec.intent == "RESTAURANT_OPS_SUPPLIER_PRICE"
    assert spec.confidence == pytest.approx(0.95)
    assert spec.clarification_needed is False


def test_unknown_dimension_value_is_dropped_while_the_plan_stays_alive():
    """丢掉那一维，⛔ 不把一个执行不了的维度带进计划。

    ⚠️ 判据**必须带上「计划还活着」这个前提**。这条第一版写的是

        assert "supplier" not in tuple(_compile().dimensions)

    ——那是**恒真式**：整份计划作废时 `dimensions` 同样是空的，两条路都满足它。
    2026-08-19 变异 M1（把 `dimensions` 挪回成员闸）当场抓到：
    行为可观测量已经证明变了（`llm` → `llm_contract_incomplete`），
    而这条断言纹丝不动。⛔ 不是变异没生效，是**这条断言在守空气**。
    """
    spec = _compile()
    assert spec.planner_authority != CONTRACT_INCOMPLETE_AUTHORITY
    assert tuple(spec.dimensions) == ()
    assert spec.intent == "RESTAURANT_OPS_SUPPLIER_PRICE"


def test_known_dimension_value_survives_alongside_an_unknown_one():
    """混着给时，登记过的那一维要留下 —— ⛔ 不是「有一个坏的就全丢」。"""
    spec = _compile(dimensions=["ingredient", "supplier"])
    assert tuple(spec.dimensions) == ("ingredient",)


def test_unknown_dimension_compiles_to_the_same_plan_as_an_empty_one():
    """净效果 = 与模型自己另外那种输出**同一个计划**。

    这是本次改动「没有发明新行为」的机械证据：`plan_hash` 相同。
    """
    assert _compile(dimensions=[]).plan_hash == _compile().plan_hash


@pytest.mark.parametrize("broken", ["supplier", ["supplier", 7], {"a": 1}, None])
def test_broken_dimension_shape_still_fails_closed(broken):
    """对照①：**形状**坏了仍然 fail closed —— 放宽的是成员，⛔ 不是形状。"""
    assert _compile(dimensions=broken).planner_authority == (
        CONTRACT_INCOMPLETE_AUTHORITY)


def test_unknown_requested_metric_still_fails_closed():
    """对照②：指标的未知值**仍然**作废整份计划。

    ⛔ 指标不能丢 —— 丢了就是「少算一项还装作算全了」（降级处理）。
    """
    assert _compile(
        requested_metrics=["mystery_metric"],
    ).planner_authority == CONTRACT_INCOMPLETE_AUTHORITY


def test_unknown_missing_field_still_fails_closed():
    """对照③：缺项的未知值**仍然**作废整份计划。"""
    assert _compile(
        missing_fields=["mystery_slot"],
    ).planner_authority == CONTRACT_INCOMPLETE_AUTHORITY


def test_registered_dimension_keeps_the_previous_behaviour():
    """对照④：已登记的维度值逐字保持原行为。"""
    spec = _compile(dimensions=["ingredient"])
    assert spec.planner_authority != CONTRACT_INCOMPLETE_AUTHORITY
    assert tuple(spec.dimensions) == ("ingredient",)
