# -*- coding: utf-8 -*-
"""老板打的菜名比菜单里的长时，说清「菜单里没查到」，⛔ 不让它走进执行链。

## prod 实测（`_audit_nearmiss`，2 轮一致）

问「最近30天水煮牛肉丝卖得怎么样」（菜单里只有「水煮牛肉」），老板拿到：

    具体菜品我这次跑出来了，但里面有几句话没达到我们「不把推测说成结论」的
    要求，所以我没发给你 …… 你可以把问题问得更小一点（限定到某家店、
    **某道菜**或某个时间段），那条路我能给你确定的数。

⛔ **不是**静默顶替（它没把水煮牛肉的数说成水煮牛肉丝的）—— 这点是好的。
🔴 但：① 那是一句讲**产品自己内部流程**的话；② 真正的原因（菜单里没这道菜）
产品**手上就有**；③ 出路「限定到某道菜」老板**已经做了** ——
**一条他做不到的指引**（与 2026-08-18 建厂那次同形）。

而「具体菜品我这次跑出来了」说明执行链**真的拿另一道菜的数据算了一遍** ——
接地闸只是最后一道保险，拦下的理由还是别的。⇒ 修法必须在更前面。

## ⛔ 为什么另起判据，不改共用的那个（硬约束 8）

`_catalogue_says_not_a_dish` 有 **5 个调用点**，4 个是**收集候选**用的
（`_extract_dish_candidate_single` / `extract_dish_candidates` 两处 /
 `dish_slot_after_catalogue_check`）。在那里收窄会让「水煮牛肉丝」
**根本进不了 `dish_slot`**，目录反馈那条路也就走不到 —— **修法适得其反**。
⇒ 新判据只接在**措辞**那一侧的**两处**，另 5 处（含 `_match_dish_rows` 的
宽匹配、`boss_decision_brief._name_hits`）**登记为故意不改**。

## ⛔ 为什么不新建口语后缀词表

词表棘轮 `MAX_UNREGISTERED = 88`，**当前正好 88（零余量）**，
且 `test_the_limit_is_not_slack` 容差 3 不许调高。
⇒ 复用**已登记的** `_DISH_GENERIC_TOKENS`，只对**已经与菜名切开的尾巴**用
（直接贪心剥尾会把「干锅花**菜**」剥成「干锅花」）。

## 断言各守什么（⛔ 不是同义的）

    承重1  比菜单里长的候选 ⇒ 判「另一道菜」
    承重2  真入口上产出目录反馈（措辞里出现那个名字 + 在售菜名）
    阴性1  真菜名本身 / 半个菜名 ⇒ 放行（⛔ 不误伤）
    阴性2  **真菜名 + 口语后缀**（「凉拌木耳这道菜」）⇒ 放行
           —— 这一条是本刀最容易做坏的那格，实测 P1 在它上面误伤 10/10
    阴性3  目录不可用 / 菜单为空 ⇒ 恒 False（fail-open 纪律）
    阴性4  完全不像的假菜由**现有**判据管，新判据对它返回 False
           （两条互补，⛔ 谁也不包含谁）
    接线   两处消费点用的是**同一个组合**（形态 D：判定与措辞不许漂）

设计卡 `docs/decisions/2026-08-19-菜名比菜单里的长-设计卡.md`。
"""
from __future__ import annotations

import ast
import inspect
import textwrap

import pytest

from smartbi.gold.restaurant import restaurant_intent as ri
from smartbi.gold.restaurant import restaurant_ops_router as router
from smartbi.gold.restaurant.restaurant_ops_router import (
    _catalogue_says_a_longer_name,
    _catalogue_says_not_a_dish,
    _generic_tail_consumable,
    dish_not_found_answer_text,
)

#: 逐字照抄 prod MOCK_REST 的菜单（`_dish_longer_than_menu` 打印出来的那 10 道）。
_LONGER_MENU = frozenset({
    "凉拌木耳", "娃娃菜", "干锅花菜", "水煮牛肉", "米饭",
    "红糖糍粑", "罗氏虾", "藤椒鸡", "酸梅汤", "鲈鱼",
})
_LONGER_STORES = ("模拟·徐汇美罗城店", "模拟·静安嘉里中心店", "模拟·杨浦五角场店")
#: 比菜单里的长一个字 —— 本刀的靶子。
_LONGER_NEARMISS = "水煮牛肉丝"
_LONGER_REAL = "水煮牛肉"
#: 完全不像的假菜 —— 归**现有**判据管。
_LONGER_ALIEN = "麻婆豆腐"


@pytest.fixture
def menu_loaded():
    token = router.set_dish_catalogue(_LONGER_MENU)
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


# ══ 一 · 纯判据 ═══════════════════════════════════════════════════════
class TestTheLongerNameCriterion:

    def test_precondition_the_menu_has_no_internal_substrings(self, menu_loaded):
        """前提：菜单里没有互为子串的两道菜。

        有的话本判据会误伤真菜名，而下面每一条阴性对照都会**恰好仍然通过**
        （它们测的是「真菜名本身」，不是「A 是 B 的前缀」这种形态）。
        ⇒ 这个前提不写下来，误伤会静默发生。prod 实测当前为空。
        """
        inner = [(a, b) for a in _LONGER_MENU for b in _LONGER_MENU
                 if a != b and a in b]
        assert inner == [], f"菜单内部有子串关系，本判据会误伤：{inner}"

    def test_a_longer_name_is_a_different_dish(self, menu_loaded):
        assert _catalogue_says_a_longer_name(_LONGER_NEARMISS) is True
        assert _catalogue_says_a_longer_name("米饭套餐") is True
        assert _catalogue_says_a_longer_name("藤椒鸡腿堡") is True

    def test_a_real_dish_is_let_through(self, menu_loaded):
        """阴性1：真菜名本身 + 半个菜名。"""
        for name in sorted(_LONGER_MENU):
            assert _catalogue_says_a_longer_name(name) is False, name
            if len(name) >= 3:
                assert _catalogue_says_a_longer_name(name[:2]) is False, name[:2]

    def test_a_real_dish_with_a_spoken_tail_is_let_through(self, menu_loaded):
        """🔴 阴性2：这一条是本刀最容易做坏的那格。

        实测：只留「老板说了半个菜名」那个方向（探针里的 P1）在这组上
        **误伤 10/10** —— 而抽取器确实会把口语后缀带进槽位
        （`extract_dish_candidate("凉拌木耳这道菜卖得怎么样") == "凉拌木耳这道菜"`）。
        """
        for name in sorted(_LONGER_MENU):
            spoken = f"{name}这道菜"
            assert _catalogue_says_a_longer_name(spoken) is False, spoken

    def test_a_menu_name_in_the_middle_is_conservatively_let_through(
            self, menu_loaded):
        """源码里写着「菜名出现在中间/末尾而不是开头 ⇒ 保守放行，⛔ 不下断言」——
        **这条行为原本没有断言守着**。

        是变异抓出来的（M6：把 `startswith` 放宽成 `name in candidate`）：
        变异落进文件却一条都不红，查清是**打在断言看不见的地方**（形态 C″ 第 3 类）
        —— 我的样本里菜名永远在开头。
        ⚠️ 放宽之后 `candidate[len(name):]` 切出来的还是**错位**的尾巴，
        于是一句正常的话会被判成「另一道菜」。
        """
        assert _catalogue_says_a_longer_name("这道菜水煮牛肉") is False
        assert _catalogue_says_a_longer_name("我想看水煮牛肉") is False

    def test_fail_open_when_the_menu_is_unavailable(self, menu_unavailable):
        """阴性3：fail-open —— 目录都没加载就敢说「这是另一道菜」是拿缺席当证据。"""
        assert _catalogue_says_a_longer_name(_LONGER_NEARMISS) is False
        assert _catalogue_says_a_longer_name(_LONGER_ALIEN) is False

    def test_the_two_criteria_are_complementary(self, menu_loaded):
        """阴性4：两条判据**互补**，⛔ 谁也不包含谁。

        prod 实测：现有判据单独 1/4、新判据单独 3/4、合起来 4/4。
        这条一旦变成「新判据也拒了麻婆豆腐」，说明它被写宽了 ——
        宽的那部分正是**现有判据已经守着**的，重复守 = 形态 D。
        """
        assert _catalogue_says_not_a_dish(_LONGER_ALIEN) is True
        assert _catalogue_says_a_longer_name(_LONGER_ALIEN) is False
        assert _catalogue_says_not_a_dish(_LONGER_NEARMISS) is False
        assert _catalogue_says_a_longer_name(_LONGER_NEARMISS) is True


class TestTheGenericTailHelper:
    """⚠️ 它**只对已经与菜名切开的尾巴**用 —— 直接对整个候选贪心剥尾，
    「干锅花菜」会被剥成「干锅花」（「菜」就在 `_DISH_GENERIC_TOKENS` 里）。"""

    def test_a_spoken_tail_is_consumable(self):
        assert _generic_tail_consumable("这道菜") is True
        assert _generic_tail_consumable("") is True

    def test_a_real_word_is_not_consumable(self):
        assert _generic_tail_consumable("丝") is False
        assert _generic_tail_consumable("套餐") is False


# ══ 二 · 措辞（真入口）════════════════════════════════════════════════
class TestTheWording:

    def test_the_longer_name_gets_the_catalogue_note(self, menu_loaded):
        text = dish_not_found_answer_text(_LONGER_NEARMISS)
        assert _LONGER_NEARMISS in text, f"没说出老板打的那个名字：{text!r}"
        shown = [n for n in _LONGER_MENU if n in text]
        assert len(shown) >= 3, f"只报出 {len(shown)} 道在售菜名：{text!r}"
        assert "新菜" in text, "没给「是不是刚上架的新菜」这条出路"

    def test_it_no_longer_claims_the_dish_is_on_the_menu(self, menu_loaded):
        """🔴 改动前这里会说「**「水煮牛肉丝」在菜单里**，但这段时间一份没卖」——
        一句**不成立**的话。撞反目标最重那条。"""
        text = dish_not_found_answer_text(_LONGER_NEARMISS)
        assert "在菜单里，但" not in text, f"还在断言它在菜单里：{text!r}"

    def test_a_real_dish_still_gets_the_sold_nothing_wording(self, menu_loaded):
        """阴性：真菜名 ⇒ **逐字**维持改动前那条「在菜单里、这段没卖」的分支。"""
        text = dish_not_found_answer_text(_LONGER_REAL)
        assert "在菜单里" in text and "一份都没卖出去" in text, text


# ══ 三 · 判定侧够得着（真入口）════════════════════════════════════════
def _spec_named_dish(dish):
    """LLM 已经把菜名填进槽位、时间也给全的那个形状。"""
    return ri._build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        f"最近30天{dish}卖得怎么样",
        confidence=0.95, tier="llm", planner_authority="llm",
        llm_semantics_authoritative=True, llm_dish=dish,
        llm_dimensions=("dish",),
        clarification_needed=True,
        clarification_question=ri.TIME_CLARIFICATION_QUESTION,
        store_options=_LONGER_STORES,
    )


def test_the_clarification_path_now_reaches_the_longer_name(menu_loaded):
    """承重2：判定侧（`_catalogue_miss_clarification`）也要认这一类。

    ⛔ 只改措辞函数是不够的 —— prod 实测老板**根本没走到**那个措辞函数，
    他拿到的是接地闸那句话。判定侧够不着，措辞再对也发不出去。
    """
    spec = _spec_named_dish(_LONGER_NEARMISS)
    assert spec.dish_slot == _LONGER_NEARMISS, f"前提：菜名槽位没进去（{spec.dish_slot!r}）"
    note = ri._catalogue_miss_clarification(spec)
    assert note, "近似菜名仍然够不着目录反馈"
    assert _LONGER_NEARMISS in note


def test_the_clarification_path_still_lets_a_real_dish_through(menu_loaded):
    """阴性：真菜名 ⇒ 判定侧放行（⛔ 不把在售的菜拦成「查无此菜」）。"""
    assert ri._catalogue_miss_clarification(_spec_named_dish(_LONGER_REAL)) is None


def test_the_clarification_path_still_lets_a_spoken_tail_through(menu_loaded):
    """阴性2 在真入口上的那一半。"""
    assert ri._catalogue_miss_clarification(
        _spec_named_dish(f"{_LONGER_REAL}这道菜")) is None


# ══ 四 · 接线（AST）══════════════════════════════════════════════════
def test_both_consumers_use_the_same_pair_of_criteria():
    """形态 D：判定与措辞用的必须是**同一个组合**。

    漂了的表现是「判定说没有、措辞说有」—— 不报错，只是老板拿到
    一句自相矛盾的话。
    """
    for fn in (ri._catalogue_miss_clarification, dish_not_found_answer_text):
        tree = ast.parse(textwrap.dedent(inspect.getsource(fn)))
        called = {getattr(n.func, "id", None) for n in ast.walk(tree)
                  if isinstance(n, ast.Call)}
        assert "_catalogue_says_not_a_dish" in called, fn.__name__
        assert "_catalogue_says_a_longer_name" in called, (
            f"{fn.__name__} 没接上新判据 —— 两处会漂")


def test_the_shared_criterion_call_sites_are_registered():
    """硬约束 8：`_catalogue_says_not_a_dish` 的调用点数没变。

    ⚠️ 它挡的是「有人顺手把新判据也塞进那 4 个**收集候选**的调用点」——
    那会让近似菜名根本进不了 `dish_slot`，目录反馈也就走不到（修法适得其反）。
    """
    src = inspect.getsource(router)
    tree = ast.parse(src)
    longer = [n for n in ast.walk(tree) if isinstance(n, ast.Call)
              and getattr(n.func, "id", None) == "_catalogue_says_a_longer_name"]
    assert len(longer) == 1, (
        f"新判据在 router 里被调用 {len(longer)} 次（只该在措辞那一处）"
        " —— 塞进收集候选的调用点会让目录反馈够不着")
