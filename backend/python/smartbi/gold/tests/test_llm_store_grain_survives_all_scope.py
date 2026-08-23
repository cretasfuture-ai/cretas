# -*- coding: utf-8 -*-
"""LLM 明确要的「按店拆」，不许被一张词表剥掉。

## prod 实测（MOCK_REST，2026-08-18，3 轮零抖动）

老板问「有几家店是亏钱的」，拿到的是 **菜品毛利分析**：

    「5 道有成本卡的菜都还赚钱，没有卖一份亏一份的」

而 LLM **判对了** —— `_lossmaking_llm_raw` 钩 `_build_spec` 拿到的原始输出，
目标句与已知答对的对照句「有没有店是亏钱的」**完全相同**：

    code = RESTAURANT_OPS_STORE_MARGIN   llm_dimensions = ('store',)
    llm_store = None                      llm_store_scope = 'all'

差异 100% 在 `restaurant_intent.py:2505-2510` 那段确定性覆盖里：
`_STORE_BREAKDOWN_SCOPE_TOKENS` 有「有没有店」、**没有**「有几家店」，
于是 store 维度被删 → `dimensions=()` → contract-repair 的能力闸
（`if repair_candidate and dimensions:`）因输入为空整段跳过 →
STORE_MARGIN 被无阻拦改写成 GROSS_MARGIN（它的能力是 `{dish, time}`，
**根本不服务 store**）。

▎闸没坏，是**维度先被删掉让闸失去了输入**。

## 这三条测试分别守什么（⛔ 不是三条同义的断言）

    承重  LLM 给的 store 维度 + 它选的 resolver 真能按店出 ⇒ **保留**
    阴性1 store 是**代码从槽位派生**的（LLM 没给）        ⇒ 仍然剥掉
    阴性2 LLM 给了、但它选的 resolver **不服务** store    ⇒ 仍然剥掉（安全阀）

阴性两条是「没有改过头」的证据：一个**无条件豁免**同样能让承重那条变绿，
但会同时弄红这两条。⇒ 三条必须一起看。

## 变异对照（设计卡 M1/M2/M3，见 docs/decisions/2026-08-18-店维度被词表剥掉-设计卡.md）

    M1 删掉整个新条件            → 承重红
    M2 去掉「LLM 给过」这一半     → 阴性1 红
    M3 去掉「resolver 服务得了」   → 阴性2 红
"""
from __future__ import annotations

import pytest

from smartbi.gold.restaurant.restaurant_intent import _build_spec

#: prod 实测的 LLM 原始输出，逐格照抄（⛔ 不是我编的形状 —— 见模块 docstring）。
_LLM_SAYS_BY_STORE = dict(
    confidence=0.95,
    tier="llm",
    planner_authority="llm",
    llm_semantics_authoritative=True,
    llm_dimensions=("store",),
    llm_store=None,
    llm_store_scope="all",
    clarification_needed=False,
)


def _spec(code, query, **over):
    kwargs = dict(_LLM_SAYS_BY_STORE)
    kwargs.update(over)
    return _build_spec(code, query, **kwargs)


# ── 前提：这几句必须**真的**落在那段覆盖的触发条件上 ────────────────────
# ⛔ 否则「断言绿」可能只是因为它压根没走到那个分支（本仓的活标本：
#    test_auto_defaultable_slots 里那条靠 `and` 短路侥幸没炸的实现）。
def test_precondition_the_override_branch_is_actually_reachable():
    from smartbi.gold.restaurant.restaurant_intent import _asks_store_breakdown

    assert _asks_store_breakdown("有几家店是亏钱的") is False, (
        "前提：这句必须**不在**那张词表里，否则本文件三条测试都没量到东西")
    assert _asks_store_breakdown("有没有店是亏钱的") is True, (
        "前提：对照句在词表里 —— 它正是「词表决定成败」的证据")


def test_llm_asked_store_grain_survives_all_store_scope():
    """承重：LLM 说了按店拆，且 STORE_MARGIN 真能按店出 ⇒ 维度必须活下来。"""
    spec = _spec("RESTAURANT_OPS_STORE_MARGIN", "有几家店是亏钱的")

    assert "store" in spec.dimensions, (
        "LLM 明确给了 dimensions=('store',)，被一张词表剥掉了 —— "
        "老板问「几家店亏钱」会拿到菜品毛利分析")
    # 🔴 维度活下来只是手段。真正要守的是**下游选的 resolver**：
    #    GROSS_MARGIN 的能力是 {dish, time}，答的是菜不是店。
    assert spec.intent == "RESTAURANT_OPS_STORE_MARGIN", (
        f"resolver 被改写成 {spec.intent} —— 那个 resolver 不服务 store 粒度")
    assert "RESTAURANT_OPS_GROSS_MARGIN" not in (spec.planned_intents or ()), (
        "计划里还留着按菜的 resolver，答案会答成菜品毛利")


@pytest.mark.parametrize("query", [
    "多少家店在亏钱",
    "有多少门店不赚钱",
    "亏钱的店有几家",
    "几间店在亏损",
])
def test_other_store_count_phrasings_also_survive(query):
    """同一形状的另外几种措辞 —— 「补词」修不好它们。

    ⛔ 它们不是「顺手多测几条」：本次采纳的修法是**按来源收窄**而不是
       **往 `_STORE_BREAKDOWN_SCOPE_TOKENS` 补词**，
       而这几句正是那个方案修不好的证据 ——
       补「有几家店」救不了「多少家店在亏钱」，补那个又救不了「几间店在亏损」。
       ▎一张要穷举措辞的词表，永远差最后一个措辞。
    """
    spec = _spec("RESTAURANT_OPS_STORE_MARGIN", query)
    assert "store" in spec.dimensions
    assert spec.intent == "RESTAURANT_OPS_STORE_MARGIN"


def test_user_saying_all_stores_still_means_scope_not_grain():
    """阴性3（这次收窄的那一半）：**用户自己**说了「全部门店」⇒ store 是范围。

    🔴 这一条是我**第一版改动弄回归**的那个形状，被既有用例
       `tests/test_restaurant_intent.py::
        test_named_dish_low_sales_diagnosis_repairs_broad_optimization_to_dish_resolver`
       当场抓住：

           「**全部门店**卤炸牛肉串本月销量为什么低」
           LLM 给 dimensions=['dish','store']

       那个 `store` 是**用户原话「全部门店」的回声**，不是粒度 ——
       真粒度是被点名的那道菜。保留它会让计划从菜品 resolver
       漂到宽泛的经营优化合成路径。

    ▎**LLM 不是永远对的。** 我的 prod 样本（24 次）里恰好没有这一类输入
    ▎—— 形态 B⁴：验收环境不含能让判据红的那种输入。

    ⇒ 判据用**用户原话**（`_detect_store_scope`），⛔ 不用 LLM 推的
       `store_scope`：拿一个猜出来的东西去否决另一个猜出来的东西，两边都没依据。
    """
    from smartbi.gold.restaurant.restaurant_intent import _detect_store_scope

    query = "全部门店卤炸牛肉串本月销量为什么低"
    assert _detect_store_scope(query)[0] == "all", (
        "前提：用户原话必须被判成全店 scope，否则本条没量到那半个判据")
    assert _detect_store_scope("有几家店是亏钱的")[0] != "all", (
        "前提：数店类问句的原话**不是** all —— 这正是两者分得开的那个信号")

    spec = _spec(
        "RESTAURANT_OPS_BUSINESS_OPTIMIZATION", query,
        llm_dimensions=("dish", "store"),
    )
    assert "store" not in spec.dimensions, (
        "用户自己说的「全部门店」被当成了按店拆的粒度 —— "
        "那是范围，真粒度是被点名的那道菜")


def test_derived_store_grain_is_still_stripped():
    """阴性1：store 是**代码从槽位派生**的（LLM 给的是空）⇒ 行为必须不变。

    这一格是 `restaurant_intent.py:2485`「有店名槽位就补 store 维度」造出来的，
    ⛔ 不是 LLM 的语义判断。收窄覆盖时它必须仍然被剥掉，否则就是改过头 ——
    「全部门店卖了多少钱」会从全店汇总变成一张按店的表。

    ⚠️ 变异 M2（去掉「LLM 给过」这一半）会让本条红。

    🔴 **问句里不许出现「全部门店/所有门店/全店」** —— 那会让
       `explicit_store_scope == "all"` 成立，于是 AND 链**在轮到 M2 那一半之前
       就短路了**，本条断言变成恒真式。
       📏 实测：第一版用的正是「全部门店最近30天卖了多少钱」，
          M2 变异后**一条都没红** —— 而变异确实落了盘、语法正常、锚点唯一。
       ▎**AND 链里新增一条，会让原有对照被新条件短路。**
    """
    from smartbi.gold.restaurant.restaurant_intent import _detect_store_scope

    query = "最近30天卖了多少钱"
    assert _detect_store_scope(query)[0] != "all", (
        "前提：问句原话**不能**是全店 scope，否则 AND 链短路，本条量不到 M2 那一半")

    spec = _spec(
        "RESTAURANT_OPS_SALES_SUMMARY",
        query,
        llm_dimensions=(),                       # ← LLM **没给** store
        llm_store="模拟·徐汇美罗城店",            # ← 2485 会据此派生出 store 维度
        llm_store_scope="all",                   # ← LLM 推的 all（≠ 用户原话）
    )
    assert "store" not in spec.dimensions, (
        "代码自己派生的 store 维度被豁免了 —— 这是改过头，"
        "不是 LLM 要求的按店拆")


def test_llm_store_grain_is_stripped_when_resolver_cannot_serve_it():
    """阴性2（安全阀）：LLM 给了 store，但它选的 resolver 服务不了 ⇒ 仍然剥掉。

    `RESTAURANT_OPS_DISCOUNT_SUMMARY` 的能力表是**空集** —— 折扣总额来自
    全店日汇总，压根没有门店粒度。保留 store 会让下游撞能力闸拒答，
    ▎**把「答非所问」换成「不回答」不是修复。**

    这正是设计卡里「选项 2（整段删除）」被否掉的那个理由 ——
    它拆掉的就是这个安全阀。

    ⚠️ 变异 M3（去掉能力表核对）会让本条红。

    🔴 **问句里同样不许出现「全部门店」** —— 理由与上一条一样：
       AND 链会在轮到安全阀那一半之前短路。
       📏 实测：第一版用的是「全部门店的折扣一共多少」，M3 变异后一条都没红。
    """
    from smartbi.gold.restaurant.restaurant_intent import _detect_store_scope
    from smartbi.gold.restaurant.restaurant_intent_service import (
        _RESOLVER_DIMENSIONS,
    )

    assert "store" not in _RESOLVER_DIMENSIONS["RESTAURANT_OPS_DISCOUNT_SUMMARY"], (
        "前提：这个 resolver 必须服务不了 store，否则本条没量到安全阀")

    query = "折扣一共多少"
    assert _detect_store_scope(query)[0] != "all", (
        "前提：问句原话**不能**是全店 scope，否则 AND 链短路，本条量不到安全阀")

    spec = _spec("RESTAURANT_OPS_DISCOUNT_SUMMARY", query)
    assert "store" not in spec.dimensions, (
        "resolver 按店出不来，却保留了 store 维度 —— 下游会拒答")
