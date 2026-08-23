# -*- coding: utf-8 -*-
"""门店口径拒答不许是死路 —— 没有按钮的反问在 UI 上就是终点。

## 缺陷长相 (2026-08-18 端到端复测, MOCK_REST, 3/3)

    第1步  模拟店怎么样          -> clarification  4 个候选门店按钮
    第2步  模拟·徐汇美罗城店      -> clarification  「你想看哪个时间范围？」4 个按钮
    第3步  本月                 -> clarification  **0 个按钮**   ← 死路

老板在第三轮看到(逐字):

    你问的是某家门店，我这次挑到的却是全店合计，所以这次我没敢算。你是想看某道菜、
    某家门店，还是全店合计？说一个就行，我不会拿别的数据凑。

他已经**逐轮答完**了「哪家店」和「哪个时间范围」, 第三轮被又问了一遍, 而且
**一个可点的东西都没有**。离线复现证实返回体里连 `followups` 键都不存在。

## 本轮做了什么 / ⛔ 没做什么

✅ 做了: 给这条拒答**一个**出口 ——「看全部门店」。
   它去的正是规划层这次**已经挑好**的那个全店合计 resolver, 所以「点了会不会
   兑现」不用猜: 与不点它、直接问全部门店走同一条路。

⛔ **没做**: 「延续轮里 intent 被重规划成与已确认槽位矛盾时, 继承上一轮已封的
   intent」。原因是**载体不在**, 不是没想到 ——
     · `restaurant_pending_clarifications` 只有 `original_query` /
       `clarification_question` 两列, **没有 intent**(该表的注释自己写着这件事);
     · 唯一够得着上一轮 intent 的是 `history[-1]["context"]["intent"]`, 而
       `turns_history` 由 Java 侧调用方拼, 离线**证不出**澄清轮会不会进去;
     · 在执行期改写已封计划, 与 `_execution_mismatch` 自己的契约
       (“Reject any execution-time reinterpretation of an immutable v2 plan”)
       正面冲突, 且 `_store_disambiguation` 的 docstring 已经裁定过
       「恰好 1 家…在这里顺手修好会把它藏起来」。
   解冻条件: ① pending 记录带上已封 intent(要动规划层 + 迁移), 或
             ② 证明澄清轮确实进 `turns_history` 且带 `context.intent`。

## ⛔ 为什么不复用 `_store_scope_switch_followups`

实测它在这个 context 上给出的是**另外三家店**(它按 `store_names` 排除了老板点名
的那家) —— 请他去看他没问的店, 而他问的那家不在里面。
⛔ 也不把他点名的那家做成按钮: 那个问句刚刚就是被这道闸拦下的, 做成按钮是哑弹。
"""
from __future__ import annotations

import asyncio

import pytest

from smartbi.gold.restaurant import restaurant_intent_service as svc
from smartbi.gold.restaurant.restaurant_intent import RestaurantQuerySpec

STORE = "模拟·徐汇美罗城店"
OPTIONS = (STORE, "模拟·静安嘉里中心店", "模拟·浦东陆家嘴店")
PLAN = ("RESTAURANT_OPS_SALES_SUMMARY",)


def _spec(*, seed=f"{STORE}怎么样 本月", intent="RESTAURANT_OPS_SALES_SUMMARY",
          dish_slot=None, store_slot=STORE):
    return RestaurantQuerySpec(
        intent=intent, domain="restaurant_ops",
        date_range=(None, None), window_label="本月", relative_window=True,
        metrics=("revenue",), wants_margin=False, asks_profitability=False,
        dimensions=(), comparison=None, confidence=0.9, source_tier="llm",
        planned_intents=PLAN, plan_hash="probe-hash",
        plan_version="restaurant-query-plan-v2", planner_authority="llm",
        resolver_query_seed=seed, store_scope="all",
        store_slot=store_slot, store_slots=(store_slot,) if store_slot else (),
        store_options=OPTIONS, dish_slot=dish_slot,
        is_clarification_continuation=True,
    )


class _DeadPool:
    pass


@pytest.fixture
def entry(monkeypatch):
    async def _no_gap(*_a, **_kw):
        return None

    async def _cat(*_a, **_kw):
        return list(OPTIONS)

    monkeypatch.setattr(svc, "_known_data_gap", _no_gap)
    monkeypatch.setattr(svc, "load_dish_catalogue", _cat)

    def _call(spec, query="本月"):
        # ⛔ 不用 `asyncio.get_event_loop()` —— 单跑绿、进全量套件红:
        #    `RuntimeError: There is no current event loop in thread 'MainThread'`
        #    (同套件里别的用例把那个 loop 关掉了)。仓里 65 处用 `asyncio.run`,
        #    只有 4 处是旧写法 —— 跟多数派, 它自带 loop 且用完即弃。
        return asyncio.run(svc.tiered_answer(
            query, _DeadPool(), "MOCK_REST", "restaurant_manager",
            precomputed_spec=spec, allow_decompose=False,
        ))
    return _call


# ── 前提 ──────────────────────────────────────────────────────────────

def test_precondition_this_really_is_the_store_scope_gate(entry):
    """⛔ 前提没了, 下面每条断言都没量到东西。"""
    assert svc._execution_mismatch(
        _spec(), PLAN, dish_mention=None, store_mention=STORE, store_dish=None,
    ) == svc._STORE_SCOPE_MISMATCH, "撞的不是门店口径那道闸"


# ── 承重 ──────────────────────────────────────────────────────────────

def test_the_dead_end_now_has_an_exit(entry):
    res = entry(_spec())
    buttons = res.get("followups") or []
    assert buttons, (
        "门店口径拒答仍然 0 个按钮 —— 在 UI 上那就是终点, "
        "老板逐轮答完之后无路可走")
    assert buttons == [{"label": "看全部门店", "question": "全部门店怎么样 本月"}], (
        f"出口不是那一个, 或者问句拼歪了: {buttons}")


def test_the_exit_question_stands_on_its_own(entry):
    """按钮发的必须是**能独立成立的完整问句**, ⛔ 不是裸范围词。

    2026-07-31 实测: 只发「模拟·徐汇美罗城店」回来的是另一句拒答 —— 哑弹。
    """
    q = (entry(_spec())["followups"] or [{}])[0].get("question", "")
    assert q.startswith("全部门店"), f"范围没拼在开头: {q!r}"
    assert len(q) > len("全部门店"), f"发的是裸范围词, 那是哑弹: {q!r}"
    assert STORE not in q, (
        f"老板点名的那家店还在问句里 —— 那正是刚被这道闸拦下的问句, "
        f"做成按钮就是第二颗哑弹: {q!r}")


# ── 阴性对照 ──────────────────────────────────────────────────────────

def test_no_exit_when_there_is_no_question_to_rebuild(entry):
    """拼不出完整问句就**不给**按钮 —— 宁可不给, 也不给一个点了会出错的。"""
    res = entry(_spec(seed=STORE))          # 整句就是店名, 剥完什么都不剩
    assert not (res.get("followups") or []), (
        f"没有可拼的问句却给了按钮: {res.get('followups')}")


def test_the_dish_flavoured_mismatch_is_left_alone(entry):
    """阴性对照(边界): 这个出口**只**给门店口径那一支。

    「你问的是某道菜，我这次挑到的却是全店合计」是另一条拒答, 它的出口不是
    「看全部门店」—— 本轮 ⛔ 不动它。少了这条, 把闸放宽到所有 mismatch 也全绿。
    """
    spec = _spec(store_slot=None, dish_slot="米饭",
                 seed="米饭怎么样 本月")
    mismatch = svc._execution_mismatch(
        spec, PLAN, dish_mention="米饭", store_mention=None, store_dish=None)
    # 前提: 它撞的确实是**菜品**那一支, 不是门店那一支
    assert mismatch == "你问的是某道菜，我这次挑到的却是全店合计", mismatch
    assert mismatch != svc._STORE_SCOPE_MISMATCH
    res = entry(spec, query="米饭怎么样")
    assert not (res.get("followups") or []), (
        f"菜品那一支也被塞了「看全部门店」—— 越界了: {res.get('followups')}")
