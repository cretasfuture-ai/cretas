"""老板用「最差的那家店」指定了门店，产品却反问「是指哪家店」。

## 现象（prod 实测，MOCK_REST，3 轮 × 8 句 = 24 次，2026-08-18）

    怎么让最差的那家店好起来 → 「你想看哪个时间范围？以及具体是哪家店？」
    垫底那家怎么救           → 「你想看哪个时间范围？以及具体是哪家垫底的门店？」

老板**已经指定**了是哪家 —— 按某个指标排最后的那家。

## 判据（⛔ 不是词表）

模型给的 `dimensions` 含 `store`，在 S 组 **24/24** 出现，在对照组
（「生意下滑了怎么办」「怎么提升营收」）**6/6** 为空。
⇒ 那是模型自己下的语义判断：「这是一个按门店拆的分析」。

▎按门店拆的计划对**每一家**店都出数，排序本身就指出「最差的那家」。
▎它不可能同时缺「你指的是哪家店」—— 模型输出的两半在打架。

代码要做的是**查**（本租户门店目录 ≥2 家）和**核对**（选中的 resolver 真的
服务 store 维度），⛔ 不是往词表里加「最差/垫底/拖后腿」。

## 每条正向断言都配一条能让它红的阴性对照

    对照①  dimensions 为空（真的没说门店）      → 必须仍然反问
    对照②  模型点名了具体门店                    → 必须是 single，⛔ 不许改成 all
    对照③  resolver 服务不了 store 维度          → 逐字维持既有行为
    对照④  租户只有一家门店                      → 逐字维持既有行为（下游推 single）
    对照⑤  还缺指标（没有安全默认值）            → 必须仍然反问
    对照⑥  澄清延续轮                            → 逐字维持既有行为（按钮链）
"""
from __future__ import annotations

import pytest

from smartbi.gold.restaurant.restaurant_intent import (
    _resolver_serves_dimensions,
    _semantic_spec_from_t3,
)

#: MOCK_REST 的门店目录量级（prod 实测 available_stores=10，30/30 次调用）。
SUPERLATIVE_TENANT_STORES = tuple("模拟·门店%d" % i for i in range(1, 11))


def superlative_payload(**overrides):
    """一份**照抄 prod 实测**的模型输出（「怎么让最差的那家店好起来」r1）。"""
    payload = {
        "intent": "RESTAURANT_OPS_BUSINESS_OPTIMIZATION",
        "time_range": None,
        "wants_margin": False,
        "asks_profitability": False,
        "requested_metrics": ["revenue"],
        "analysis_action": "optimize",
        "aggregation": None,
        "dimensions": ["store"],
        "comparison": None,
        "dish": None,
        "store": None,
        "stores": [],
        "store_scope": None,
        "confidence": 0.9,
        "clarification_needed": True,
        "missing_fields": ["time_range", "store_scope"],
        "clarification_question": "你想看哪个时间范围？以及具体是哪家店？",
        "clarification_options": [],
    }
    payload.update(overrides)
    return payload


def _spec(payload, query, **kw):
    kw.setdefault("available_stores", SUPERLATIVE_TENANT_STORES)
    return _semantic_spec_from_t3(payload, query, **kw)


# ── 正向：老板已经指定了门店，⛔ 不许再问「是指哪家店」 ────────────────────

@pytest.mark.parametrize("query, missing", [
    ("怎么让最差的那家店好起来", ["time_range", "store_scope"]),
    ("最好的那家店做对了什么", ["time_range", "store_scope"]),
    # prod 实测模型两种顺序都出过 —— 判据不能依赖顺序
    ("垫底那家怎么救", ["store_scope", "time_range"]),
])
def test_计算式指代不再被问是指哪家店(query, missing):
    spec = _spec(superlative_payload(missing_fields=missing), query)

    assert spec.store_scope == "all", "按门店拆的计划, 门店范围就是全部门店"
    assert spec.clarification_needed is False, (
        "老板已经用「最差的那家」指定了门店, 只剩时间 —— "
        "时间有安全默认值且会被显式披露")
    assert spec.time_range_defaulted is True, "时间走既有的默认窗口 + 明示"
    assert spec.store_scope_defaulted is True, (
        "⛔ 必须留下披露标记 —— 偷偷替老板选口径才是降级, 选了并说出来不是")


def test_模型没说缺门店时同样把按门店拆读成全部门店():
    """prod 实测「拖后腿的那家店是哪家」: 模型 `missing_fields=('time_range',)`,

    门店范围留空 → 下游 `_apply_store_scope_guard` 自己问了一句
    「这项分析要看哪一组门店？」。⇒ 判据挂在 `dimensions` 上而不是挂在
    `missing_fields` 上, 两条路才都接得住。
    """
    spec = _spec(
        superlative_payload(
            intent="RESTAURANT_OPS_STORE_MARGIN",
            analysis_action="lookup",
            missing_fields=["time_range"],
            clarification_question="你想看哪个时间范围？",
        ),
        "拖后腿的那家店是哪家",
    )

    assert spec.store_scope == "all"
    assert spec.clarification_needed is False


# ── 阴性对照：每一条都在守一个具体行为，破坏那个行为它就该红 ───────────────

def test_对照1_没有门店维度时反问必须留着():
    """「生意下滑了怎么办」—— 模型 6/6 给的 `dimensions` 是空的。

    守的行为: **没有门店维度 = 模型没判定这是按门店拆的分析** ⇒ 反问是对的。
    """
    spec = _spec(
        superlative_payload(dimensions=[], requested_metrics=[]),
        "生意下滑了怎么办",
    )

    assert spec.clarification_needed is True, "真的没说门店时, ⛔ 不许替他决定"
    assert spec.store_scope is None


def test_对照2_模型点名了门店时不许被改写成全部门店():
    """守的行为: 老板点名了一家店, 范围就是那一家。"""
    named = SUPERLATIVE_TENANT_STORES[0]
    spec = _spec(
        superlative_payload(
            store=named, stores=[named], missing_fields=["time_range"]),
        "%s最近怎么样" % named,
    )

    assert spec.store_scope == "single"
    assert spec.store_slots == (named,)


def test_对照3_resolver服务不了store维度时逐字维持既有行为():
    """守的行为: 能力表说这个 resolver 出不了按门店的数, 改了范围也白改。

    `RESTAURANT_OPS_RECIPE_COST` 只服务 dish（成本卡按菜录）——
    这是本仓登记过的实例:「哪家店成本最高」反问 6/6 稳定, 真因是能力边界。
    """
    assert _resolver_serves_dimensions(
        "RESTAURANT_OPS_RECIPE_COST", ("store",)) is False, (
        "阳性对照: 这个 resolver 必须真的服务不了 store, 否则本条恒真")

    spec = _spec(
        superlative_payload(
            intent="RESTAURANT_OPS_RECIPE_COST",
            requested_metrics=["recipe_cost"],
            analysis_action="lookup",
        ),
        "最差的那家店成本高在哪",
    )

    assert spec.store_scope != "all"
    assert spec.clarification_needed is True


def test_对照4_单店租户逐字维持既有行为():
    """守的行为: 只有一家店时「全部门店」是个没有意义的范围，
    下游 `_apply_store_scope_guard` 会把它推成 single。⛔ 不在这里抢答。
    """
    spec = _spec(
        superlative_payload(),
        "怎么让最差的那家店好起来",
        available_stores=("模拟·唯一店",),
    )

    assert spec.store_scope != "all"
    assert spec.clarification_needed is True


def test_对照5_还缺指标时反问必须留着():
    """守的行为: 指标**没有**无歧义的默认值, 补错等于给一个看着像答案的错答案。

    prod 实测出过 `missing_fields=('time_range', 'metric')`。
    """
    spec = _spec(
        superlative_payload(missing_fields=["time_range", "metric"]),
        "我要不要关掉最差的那家店",
    )

    assert spec.clarification_needed is True
    assert spec.store_scope == "all", (
        "门店那一格照样封定 —— 但只剩的那个缺项是指标, 所以还要问")


def test_对照6_澄清延续轮逐字维持既有行为():
    """守的行为: 延续轮里老板正在**回答**槽位, 替他答就是抢答。

    「时间 → 门店按钮 → 答案」是零 LLM 的确定性链, 也是 T3 不可用时唯一
    还走得通的路径。既有的两个默认块都写着 `and not is_continuation`。
    """
    spec = _spec(
        superlative_payload(),
        "怎么让最差的那家店好起来 最近30天",
        is_continuation=True,
    )

    assert spec.store_scope != "all"
