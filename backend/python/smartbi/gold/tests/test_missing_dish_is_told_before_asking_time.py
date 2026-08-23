# -*- coding: utf-8 -*-
"""菜名不在菜单里时，先说菜单的事，⛔ 不先问一个答完也没用的时间。

## prod 实测（MOCK_REST，`_dish_catalogue_behaviour`，2 轮逐轮一致）

| 问句 | `clarification_needed` | 老板拿到 |
|---|---|---|
| 佛跳墙卖得怎么样 | True | 56 字「这项分析要看哪一组门店、哪个时间范围？」——**一个字没提菜单** |
| 最近30天佛跳墙卖得怎么样 | False | 150 字「**菜单里没查到「佛跳墙」这道菜**…在售的菜有：…（共 10 道）」 |

▎**产品手上就有菜单，却先问一个答完也给不出的问题。**
▎老板答完「全部门店 最近30天」才被告知这道菜不存在 —— 白答一轮。

## 断在哪

`dish_not_found_answer_text` 全仓调用点：router 1 / restaurant_intent 0 / service 0
⇒ 目录反馈**只在执行期产出**，而缺时间时被 `_apply_store_scope_guard` 里那个
`store+time` 反问挡在执行之前。
▎**不是目录核对坏了，是它够不着**（与 Q09 同族：算出来的东西没投影到决策点）。

## ⛔ 它不撞「有默认值就压掉澄清」

红线守「⛔ 不要因为有默认值就不问」。本刀做的是「问之前先看这问题问了有没有用」。
判据差别可检验 —— 压掉澄清 ⇒ 老板拿到一个**数**；本刀 ⇒ 老板拿到**另一个澄清**。
⇒ **本刀不让任何一个原本要问的问题消失，只是换成该问的那一个。**

## 断言各守什么（⛔ 不是同义的）

    承重   假菜 ⇒ 产出目录反馈；且**在真入口上**替换掉时间反问
    阴性1  **真菜** ⇒ 放行（⛔ 不误伤在售菜）
    阴性2  目录不可用 ⇒ 放行（fail-open 纪律）
    阴性3  没点名菜 ⇒ 放行
    阴性4  措辞必须来自 `dish_not_found_answer_text`（⛔ 不新造第二份，形态 D）
    接线   AST 数调用点（⚠️ 挡得住删接线，⛔ 挡不住 if False —— 行为那半在 prod 真跑）

设计卡 `docs/decisions/2026-08-19-菜名不存在却先问时间-设计卡.md`。
"""
from __future__ import annotations

import ast
import inspect

import pytest

from smartbi.gold.restaurant import restaurant_intent as ri
from smartbi.gold.restaurant import restaurant_ops_router as router
from smartbi.gold.restaurant.restaurant_intent import (
    COMBINED_SCOPE_TIME_CLARIFICATION_QUESTION,
    _catalogue_miss_clarification,
)

_DISHFIRST_MENU = frozenset({"凉拌木耳", "娃娃菜", "干锅花菜", "水煮牛肉", "米饭",
                   "红糖糍粑", "罗氏虾", "藤椒鸡"})
_DISHFIRST_STORES = ("模拟·徐汇美罗城店", "模拟·静安嘉里中心店", "模拟·杨浦五角场店")
_DISHFIRST_FAKE = "佛跳墙"
_DISHFIRST_REAL = "水煮牛肉"


@pytest.fixture
def menu_loaded():
    """把租户菜单绑上 —— 生产上由 `dish_catalogue_scope` 在执行入口绑。"""
    token = router.set_dish_catalogue(_DISHFIRST_MENU)
    try:
        yield
    finally:
        router._DISH_CATALOGUE.reset(token)


@pytest.fixture
def menu_unavailable():
    token = router.set_dish_catalogue(None)
    try:
        yield
    finally:
        router._DISH_CATALOGUE.reset(token)


def _spec_missing_time(dish):
    """LLM 说「缺时间」的那个形状 —— 逐格照抄 prod 实测。

    📏 `_q08_shape`（MOCK_REST，3 轮）：
       clarification_needed=True / dish 槽位有值 / dims=('dish',) / action='lookup'

    ⚠️ 桩必须是真实上游产得出的形状（形态 B‴）。第一版我照抄了 LLM 自撰的那句
       「你想看哪个时间范围？」，结果 `_slots_of_clarification` **返回空集** ——
       它只认三个固定常量，而 `TIME_CLARIFICATION_QUESTION` 的真身是
       「你想看哪个时间范围？**请选择本月、上个月、最近…**」，LLM 那句只是前缀。
       ▎**LLM 说的那句话和产品最后发的那句话不是同一个字符串。**
       ⇒ 桩用常量，⛔ 不用 LLM 原句。
    """
    return ri._build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        f"{dish}卖得怎么样" if dish else "卖得怎么样",
        confidence=0.95,
        tier="llm",
        planner_authority="llm",
        llm_semantics_authoritative=True,
        llm_dish=dish,
        llm_dimensions=("dish",) if dish else (),
        clarification_needed=True,
        clarification_question=ri.TIME_CLARIFICATION_QUESTION,
        # ⚠️ guard 里是 `spec.store_options or await _load_store_options(...)` ——
        #    桩里给上就**不碰 IO**，⛔ 比 monkeypatch 干净（少一层「钩对了吗」）。
        #    第一版我 patch 了 `_load_relevant_store_options`，那是**另一个函数**，
        #    于是真入口那三条红在 `'NoneType' object has no attribute 'acquire'` 上
        #    —— 红在 IO 上的失败**不算数**（它不是被守的行为）。
        store_options=_DISHFIRST_STORES,
    )


# ── 前提：桩必须真的落在被测的那一支上 ────────────────────────────────
def test_precondition_the_stub_lands_on_the_missing_time_branch(menu_loaded):
    spec = _spec_missing_time(_DISHFIRST_REAL)
    assert spec.clarification_needed is True
    assert spec.dish_slot == _DISHFIRST_REAL, f"前提：菜名槽位没进去（{spec.dish_slot!r}）"
    assert "time" in ri._slots_of_clarification(spec.clarification_question), (
        "前提：这句反问必须被判成缺时间，否则本文件量的是另一件事")


class TestTheCriterion:
    """纯判据 —— 快，且四种输入各有一条。"""

    def test_a_dish_not_on_the_menu_produces_the_catalogue_note(self, menu_loaded):
        note = _catalogue_miss_clarification(_spec_missing_time(_DISHFIRST_FAKE))
        assert note, "菜单里没有这道菜，判据却说没事"
        assert _DISHFIRST_FAKE in note, f"没把老板打的那个名字说出来：{note!r}"
        assert "新菜" in note, (
            "没给「是不是刚上架的新菜」这条出路 —— 会把一个对的输入当成错的")

    def test_a_dish_on_the_menu_is_let_through(self, menu_loaded):
        """阴性1：在售的菜 ⇒ 放行，⛔ 不误伤。"""
        assert _catalogue_miss_clarification(_spec_missing_time(_DISHFIRST_REAL)) is None

    def test_fail_open_when_the_menu_is_unavailable(self, menu_unavailable):
        """阴性2：目录不可用 ⇒ 放行（fail-open 是既有纪律）。"""
        assert _catalogue_miss_clarification(_spec_missing_time(_DISHFIRST_FAKE)) is None, (
            "目录都没加载就敢说「菜单里没这道菜」—— 那是拿缺席当证据")

    def test_no_named_dish_is_let_through(self, menu_loaded):
        """阴性3：没点名菜 ⇒ 放行。"""
        assert _catalogue_miss_clarification(_spec_missing_time(None)) is None

    def test_the_wording_comes_from_the_existing_producer(self, menu_loaded):
        """阴性4：措辞必须是现成那份，⛔ 不新造第二份（形态 D）。

        ⚠️ 顺带守住词表棘轮 —— 它是 88/88 **零余量**，新造一句就会红。
        """
        note = _catalogue_miss_clarification(_spec_missing_time(_DISHFIRST_FAKE))
        assert note == router.dish_not_found_answer_text(_DISHFIRST_FAKE), (
            "反问措辞不是现成那份 —— 新造了第二份说法\n"
            f"判据给的: {note!r}\n现成那份: {router.dish_not_found_answer_text(_DISHFIRST_FAKE)!r}")


class TestOnTheRealEntry:
    """🔴 上面全是纯判据 —— 它们证明不了「生产上谁保证它被调用」。

    这一组走**真入口** `_apply_store_scope_guard`，门店清单由桩的
    `store_options` 直接给（guard 里是 `spec.store_options or await ...`），
    ⇒ 整条路**不碰 IO**，pool 传 None 也走得通。
    """

    @pytest.mark.asyncio
    async def test_the_fake_dish_gets_the_menu_note_not_the_time_question(
            self, menu_loaded):
        """承重（真入口）：假菜 ⇒ 问句被换成目录反馈。"""
        out = await ri._apply_store_scope_guard(
            None, "MOCK_REST", _spec_missing_time(_DISHFIRST_FAKE), history=None)
        assert out.clarification_question != COMBINED_SCOPE_TIME_CLARIFICATION_QUESTION, (
            "菜单里根本没有这道菜，却先问「要看哪一组门店、哪个时间范围」—— "
            "老板答完了产品照样给不出，白答一轮")
        assert out.clarification_question == router.dish_not_found_answer_text(_DISHFIRST_FAKE)
        assert out.missing_slot == "dish", (
            f"缺的那一格标错了：{out.missing_slot!r} —— 缺的是菜不是门店/时间")

    @pytest.mark.asyncio
    async def test_the_real_dish_still_gets_the_time_question(self, menu_loaded):
        """阴性1（真入口）：在售的菜 ⇒ 时间反问**逐字**不变。"""
        out = await ri._apply_store_scope_guard(
            None, "MOCK_REST", _spec_missing_time(_DISHFIRST_REAL), history=None)
        assert out.clarification_question == COMBINED_SCOPE_TIME_CLARIFICATION_QUESTION
        assert out.missing_slot == "store+time"

    @pytest.mark.asyncio
    async def test_fail_open_on_the_real_entry(self, menu_unavailable):
        """阴性2（真入口）：目录不可用 ⇒ 行为**逐字**不变。"""
        out = await ri._apply_store_scope_guard(
            None, "MOCK_REST", _spec_missing_time(_DISHFIRST_FAKE), history=None)
        assert out.clarification_question == COMBINED_SCOPE_TIME_CLARIFICATION_QUESTION
        assert out.missing_slot == "store+time"


class TestTheWiringIsActuallyThere:
    """AST 接线断言。

    ✅ 挡得住「删掉接线 / 改名」
    ⛔ 挡不住「`if False` 绕过」—— 行为那半由 **prod 真跑一次**补（交付判据②）。
    ⚠️ 硬约束 8：这两个数变了就来这里过一眼。
    """

    def _calls(self, name):
        tree = ast.parse(inspect.getsource(ri))
        return sum(1 for x in ast.walk(tree)
                   if isinstance(x, ast.Call)
                   and getattr(x.func, "attr", getattr(x.func, "id", None)) == name)

    def test_the_criterion_is_called_from_the_guard(self):
        assert self._calls("_catalogue_miss_clarification") == 1, (
            "规划期调用判据的次数不是 1 —— 0 说明没接上；>1 说明有第二个接线点")

    def test_the_criterion_reuses_the_existing_check(self):
        assert self._calls("_catalogue_says_not_a_dish") == 1, (
            "`_catalogue_says_not_a_dish` 在规划期的调用次数不是 1 —— "
            "⛔ 别另写一份「像不像菜名」的判据（形态 D）")

    def test_the_existing_wording_producer_is_reused(self):
        assert self._calls("dish_not_found_answer_text") == 1, (
            "措辞产出点不是 1 处 —— 0 说明没接上；>1 说明措辞在多处产出")
