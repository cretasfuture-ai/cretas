# -*- coding: utf-8 -*-
"""剥掉 LLM 给的 `ingredient` 维度，⛔ 不许只凭 6 个中文词。

## 被守的行为

`_build_spec` 里那段「点了菜名 + 句子里没有食材/原料/配料/采购/领料/库存
⇒ 剥掉 LLM 给的 `ingredient`」是 `audit_restaurant_wordlists --vetoes`
点名的 4 个「词表**单独**否决 LLM 槽位」决策点之一。

2026-08-20 收窄成「**再加一票能力表**」：resolver 真能按食材出时 ⛔ 不许剥。

## ⛔ 两个更激进的版本，都被读数否掉（写下来免得下一个人重来）

**① 直接删掉。** 论据是「删了之后由下游带依据的
`_drop_unanswerable_mislabeled_dimensions` 剥并由 `_partial_grain_note` 说出来」。
**实测这条链接不上** —— 见 `test_why_not_just_delete_it`：
`_query_names_an_ingredient('水煮牛肉的成本是多少')` 返回 **True**
（「牛肉」是「水煮牛肉」的子串，假阳性）⇒ 下游判成「老板点名了食材」⇒ 不剥
⇒ `dropped=()` ⇒ note 空 ⇒ 落到执行前口径闸的整份拒答。
▎删它 = 把「答非所问」换成「不回答」。

**② 把 6 个词换成能力表**（而不是再加一票）。**实测当场制造回归** ——
见 `test_why_the_six_words_cannot_go`：`extract_dish_candidate` 在纯食材问句上
返回 `'按食材拆一下'` / `'食材'` 这种**假菜名**，今天正是那 6 个词在挡它。

## 每条正向断言都配一条能让它红的对照

    正向  resolver 真能出 ingredient → **不许剥**
    对照① resolver 出不了            → 逐字保持原行为（仍剥）
    对照② 句子里说了「食材」          → 逐字保持原行为（逃生口，仍不剥）
    对照③ 没有 deterministic_dish     → 这条 if 根本不进
"""
from __future__ import annotations

import os
import sys

sys.path.insert(
    0,
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(
        os.path.abspath(__file__))))),
)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402

#: 一句会让 `extract_dish_candidate` 抽出真菜名、且不含那 6 个词的问句。
DISH_COST_QUERY = "水煮牛肉的成本是多少"
#: 只多了「食材」二字 —— 逃生口。
DISH_COST_QUERY_WITH_ESCAPE = "水煮牛肉的食材成本是多少"


def _plan(**overrides):
    plan = {
        "intent": "RESTAURANT_OPS_RECIPE_COST",
        "time_range": None,
        "wants_margin": False,
        "asks_profitability": False,
        "requested_metrics": ["food_cost"],
        "analysis_action": "lookup",
        "aggregation": None,
        "dimensions": ["ingredient"],
        "comparison": None,
        "dish": "水煮牛肉",
        "store": None,
        "stores": [],
        "store_scope": None,
        "confidence": 0.95,
        "clarification_needed": False,
        "missing_fields": [],
        "clarification_question": None,
        "clarification_options": [],
    }
    plan.update(overrides)
    return plan


# ══ 前提：判据的三半**真的**在这些输入上分得开 ═══════════════════════════
def test_the_pieces_of_the_criterion_are_not_constants():
    """⛔ 少了这条，下面全绿可能只是「这些输入根本不进那条 if」。"""
    from smartbi.gold.restaurant.restaurant_ops_router import extract_dish_candidate

    assert extract_dish_candidate(DISH_COST_QUERY) == "水煮牛肉"
    # 能力表两侧都要活：一个服务不了、一个真能服务
    assert not ri._resolver_serves_dimensions(
        "RESTAURANT_OPS_RECIPE_COST", ("ingredient",))
    assert ri._resolver_serves_dimensions(
        "RESTAURANT_OPS_SUPPLIER_PRICE", ("ingredient",))


# ══ 一 · 承重：能力表说能出，就不许剥 ═══════════════════════════════════
def test_a_resolver_that_can_serve_ingredient_keeps_the_llm_dimension():
    """🔴 承重：LLM 给了 ingredient 而 resolver 真能按食材出 ⇒ ⛔ 不许剥。"""
    spec = ri._semantic_spec_from_t3(
        _plan(intent="RESTAURANT_OPS_SUPPLIER_PRICE"), DISH_COST_QUERY)
    assert "ingredient" in spec.dimensions, (
        f"resolver 真能按食材出, 维度却被 6 个词剥掉了: {spec.dimensions}")


# ══ 二 · 对照：能让上面变红的那些输入 ═══════════════════════════════════
class TestTheControls:

    def test_control_1_a_resolver_that_cannot_serve_it_still_strips(self):
        """resolver 出不了 ⇒ 逐字保持原行为（仍剥）。

        ⛔ 这一格**不是**「修好了」—— 它是「这一刀没碰到的那一半」。
        """
        spec = ri._semantic_spec_from_t3(_plan(), DISH_COST_QUERY)
        assert "ingredient" not in spec.dimensions
        assert "dish" in spec.dimensions

    def test_control_2_the_escape_words_still_work(self):
        """句子里说了「食材」⇒ 逃生口照旧，两侧逐字相同。"""
        spec = ri._semantic_spec_from_t3(_plan(), DISH_COST_QUERY_WITH_ESCAPE)
        assert "ingredient" in spec.dimensions

    def test_control_3_no_deterministic_dish_means_this_branch_never_runs(self):
        """纯食材问句：这条 `if` 的第一半不成立 ⇒ 与本改动无关。"""
        query = "哪些原料最费钱"
        from smartbi.gold.restaurant.restaurant_ops_router import extract_dish_candidate
        assert extract_dish_candidate(query) is None
        spec = ri._semantic_spec_from_t3(_plan(dish=None), query)
        assert "ingredient" in spec.dimensions


# ══ 三 · 把两个被否掉的方案钉住（⛔ 别再走一遍）═════════════════════════
class TestTwoRejectedAlternatives:

    def test_why_not_just_delete_it(self):
        """🔴 删掉整段**接不上**下游那条「剥了要说」的路 —— 钉住这个事实。

        它是纯读数，⛔ 不是断言产品该怎样：如果哪天
        `_query_names_an_ingredient` 不再把菜名里的食材子串当成点名，
        这条会红，那时**才**可以重新考虑删掉。⇒ 它同时是解冻条件的载体。
        """
        from smartbi.gold.restaurant.restaurant_intent import (
            _query_names_an_ingredient,
        )

        assert _query_names_an_ingredient(DISH_COST_QUERY) is True, (
            "上游的点名假阳性没有了 —— 现在可以重新评估「直接删掉」那个方案了，"
            "见 docs/decisions/2026-08-20-食材维度的否决加一票能力表.md 的解冻条件")

    def test_why_the_six_words_cannot_go(self):
        """🔴 那 6 个词在**补上游抽取器的假阳性** —— 换掉它们当场制造回归。

        同上：这两个读数变了就该回来重新评估。
        """
        from smartbi.gold.restaurant.restaurant_ops_router import extract_dish_candidate

        assert extract_dish_candidate("按食材拆一下这个月的成本") == "按食材拆一下", (
            "上游抽取器不再把「按食材拆一下」当菜名了 —— 那 6 个词可以重新评估")
        assert extract_dish_candidate("这个月食材成本是多少") == "食材"
