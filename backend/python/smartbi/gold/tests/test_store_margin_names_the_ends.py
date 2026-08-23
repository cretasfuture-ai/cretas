# -*- coding: utf-8 -*-
"""老板问「哪一家」，⛔ 不能只把一张表推给他 —— 更不能加粗**相反**的那一家。

## 缺陷（prod 实测，MOCK_REST，2026-08-19，2 轮**逐字相同**，n=1347）

探针 `smartbi.scripts._nameit_repro`，问「**拖后腿**的那家店是哪家」：

```
kind=answer  code=RESTAURANT_OPS_STORE_MARGIN  ranking_direction=None
表按毛利从高到低排:
  | 1  | **模拟·陆家嘴正大店**   | … | ¥1,004,887.82 |   ← 无条件加粗第一名
  …
  | 10 | 模拟·闵行莘庄社区店     | … | ¥977,700.84   |   ← 他问的那一家，一个字没提
```

▎**加粗是一个断言**（「这家就是答案」），而系统并不知道他问的是哪一头。
▎老板会照着**最好的那家**去做动作 —— 这比「答不上」糟得多。

## 两半修法（各自可单独变异）

| 半 | 做法 | 守什么 |
|---|---|---|
| ① `_rows_to_point_at` | 加粗**只在** `ranking_direction` 已知时出现 | 方向未知 ⇒ 不许断言 |
| ② `_name_the_ends_line` | 表前面把**两端各点一次名**，⛔ 口径不许省 | 他问的那一头一定被点到 |

⛔ **一个词表都没加。** `docs/decisions/2026-08-18-店维度被词表剥掉-设计卡.md`
   已裁定「往 `_detect_ranking_direction` 里补『拖后腿 / 垫底』」是猜、且永远补不完；
   闸 `test_a_wordlist_alone_cannot_veto_an_llm_slot` 守着这条分界。
   ② 更是**完全不看问句措辞** —— 它对每一屏门店毛利排行都成立。

## ⚠️ 按**显示精度**判两端

实测 `模拟·宝山大场社区店`(0.676929) 与 `模拟·闵行莘庄社区店`(0.676566)
在表里**都显示 67.7%**。按浮点挑一家写「毛利率最低的是 X」，老板一对表就发现
另一家也是 67.7% —— ▎一条经不起他去查的提示，烧掉的是「这东西说的话能信」。
"""
from __future__ import annotations

import ast
import inspect

import pytest

from smartbi.gold.restaurant.restaurant_ops_router import (
    _display_extremes,
    _name_the_ends_line,
    _rows_to_point_at,
)

#: 📏 prod 实测（MOCK_REST，2026-08-19，最近30天）那一屏的**原始十行**。
#:    ⛔ 不编数字 —— 并列（68.0% 两家 / 67.7% 两家）正是这份数据的性质，
#:    编出来的整数会把这一格测没。
_PROD_ROWS = (
    ("模拟·陆家嘴正大店", 1483202.00, 1004887.82),
    ("模拟·徐汇美罗城店", 1476602.00, 1003560.59),
    ("模拟·普陀真如社区店", 1471340.00, 997554.61),
    ("模拟·静安嘉里中心店", 1460152.00, 990667.81),
    ("模拟·宝山大场社区店", 1461168.00, 989104.84),
    ("模拟·长宁龙之梦店", 1457152.00, 988404.85),
    ("模拟·浦东金桥社区店", 1447942.00, 983987.16),
    ("模拟·打浦桥日月光店", 1446006.00, 980294.74),
    ("模拟·杨浦五角场店", 1442578.00, 979326.46),
    ("模拟·闵行莘庄社区店", 1444946.00, 977700.84),
)


def _entries():
    """⚠️ 与 `resolve_store_margin` 里 `ranked_store_list` 同序（毛利降序）。"""
    return [
        {"name": name, "revenue_with_cost": rev, "gross_profit": profit,
         "margin_rate": profit / rev}
        for name, rev, profit in _PROD_ROWS
    ]


def _profit(entry):
    return entry.get("gross_profit")


def _rate(entry):
    return entry.get("margin_rate")


def _money(value):
    return f"¥{value:,.2f}"


def _pct(value):
    return f"{value * 100:.1f}%"


def _resolver_src():
    from smartbi.gold.restaurant import restaurant_ops_router as router
    return inspect.getsource(router.resolve_store_margin)


def _names_referenced_by_assignment(target_name):
    """`<target_name> = …` 这个赋值里引用了哪些名字（AST，⛔ 不 grep）。

    🔴 用 AST 而不是文本：本文件正文里就写着 `_bold_idx` / `ends_text`，
       grep 会把**注释里提到**的算成接线（形态 C⁸，本仓记过三次）。
    """
    tree = ast.parse(_resolver_src())
    names = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Assign) and any(
                getattr(t, "id", None) == target_name for t in node.targets):
            names |= {
                x.id for x in ast.walk(node.value) if isinstance(x, ast.Name)}
    return names


# ══ 一 · 承重：他问的那一头一定被点到名 ═══════════════════════════════════
class TestBothEndsGetNamed:

    def test_the_worst_store_is_named_by_absolute_profit(self):
        """🔴 承重：`模拟·闵行莘庄社区店` —— 实测里他问「拖后腿」而产品没提的那家。"""
        line = _name_the_ends_line(_entries(), "绝对毛利", _profit, _money)
        assert "模拟·闵行莘庄社区店" in line, (
            f"最低那一端没被点名，老板问「拖后腿」照样看不到他要的那家：{line!r}")
        assert "¥977,700.84" in line, f"点了名却没带值：{line!r}"

    def test_the_caliber_is_always_stated(self):
        """⛔ 口径不许省 —— 同一屏上两个口径指的不是同一家。"""
        profit_line = _name_the_ends_line(_entries(), "绝对毛利", _profit, _money)
        rate_line = _name_the_ends_line(_entries(), "毛利率", _rate, _pct)
        assert "按**绝对毛利**" in profit_line and "按**毛利率**" in rate_line
        # 🔴 这两行**真的**指向不同的店 —— 否则「说两个口径」是句废话
        assert "模拟·陆家嘴正大店" in profit_line
        assert "模拟·陆家嘴正大店" not in rate_line, (
            f"两个口径的最高位撞在一起了，本数据的性质变了：{rate_line!r}")

    def test_ties_at_display_precision_list_every_tied_name(self):
        """⚠️ 表里两家都显示 67.7% ⇒ 正文必须两家都说，⛔ 不许替他挑一个。"""
        line = _name_the_ends_line(_entries(), "毛利率", _rate, _pct)
        assert "模拟·宝山大场社区店" in line and "模拟·闵行莘庄社区店" in line, (
            f"显示值并列的两家只点了一家 —— 老板一对表就发现对不上：{line!r}")
        assert "模拟·徐汇美罗城店" in line and "模拟·浦东金桥社区店" in line, (
            f"最高位那一对并列也要全列：{line!r}")


# ══ 二 · 阴性对照：判据 ⛔ 没变成「见排行就点名」═══════════════════════════
class TestItStaysQuietWhenItShould:

    def test_a_single_store_gets_no_identification(self):
        """只有一家店时「最高 / 最低」是句废话。"""
        assert _name_the_ends_line(
            _entries()[:1], "绝对毛利", _profit, _money) == ""

    def test_all_equal_at_display_precision_means_no_ends(self):
        """🔴 全部显示同一个值 ⇒ 「最高是这 10 家、最低也是这 10 家」是废话。

        ⛔ 这一格不能返回「随便挑一家」—— 那正是形态 D′ 的那种提示。
        """
        flat = [{"name": "A", "gross_profit": 1.0},
                {"name": "B", "gross_profit": 1.0004}]
        assert _display_extremes(flat, _profit, _money) == ((), "", (), "")
        assert _name_the_ends_line(flat, "绝对毛利", _profit, _money) == ""

    def test_none_values_are_not_read_as_zero(self):
        """`gross_profit is None` 是「算不出」，⛔ 不是「最低」（形态 A¹⁰）。"""
        rows = [{"name": "算不出", "gross_profit": None},
                {"name": "高", "gross_profit": 9.0},
                {"name": "低", "gross_profit": 1.0}]
        line = _name_the_ends_line(rows, "绝对毛利", _profit, _money)
        assert "**低**" in line and "**算不出**" not in line, line


# ══ 三 · 加粗是一个断言 —— 方向未知就不许断言 ════════════════════════════
class TestBoldOnlyWhenTheDirectionIsKnown:

    def test_unknown_direction_points_at_nobody(self):
        """🔴 承重：这正是实测那一格 —— `ranking_direction=None` 却加粗了第一名。"""
        assert _rows_to_point_at(None, (0,), (9,), 10) == set()
        assert _rows_to_point_at("", (0,), (9,), 10) == set()

    def test_best_points_at_the_top_and_worst_at_the_bottom(self):
        """⛔ 阳性对照：方向已知时它**真的**指得出来，否则上一条恒真。"""
        assert _rows_to_point_at("best", (0,), (9,), 10) == {0}
        assert _rows_to_point_at("worst", (0,), (9,), 10) == {9}

    def test_a_truncated_table_does_not_fake_the_worst_row(self):
        """⚠️ 表被 `top_n` 截断时真正的最低不在表里 ⇒ 不加粗，⛔ 不拿末行顶替。"""
        assert _rows_to_point_at("worst", (0,), (29,), 10) == set()
        assert _rows_to_point_at("best", (0,), (29,), 10) == {0}

    def test_every_tied_extreme_gets_pointed_at(self):
        assert _rows_to_point_at("worst", (0, 1), (8, 9), 10) == {8, 9}


# ══ 四 · 接线：两半都必须真的被拼进 answer / 表格 ════════════════════════
#
# 🔴 本仓最常犯的是「测了 helper，没测接线」。把 `ends_text` 从 f-string 里
#    删掉之后，上面每一条断言都照样全绿。
class TestItIsActuallyWired:

    def test_the_identification_reaches_the_answer(self):
        assert "ends_text" in _names_referenced_by_assignment("answer"), (
            "answer 的 f-string 里没有引用 ends_text —— 指认那句构造了但没接上")

    def test_the_identification_is_built_from_the_full_ranking(self):
        """⛔ 读完整排行，⛔ 不读被 `top_n` 截断的 `top_slice`。"""
        referenced = _names_referenced_by_assignment("ends_lines")
        assert "ranked_store_list" in referenced, (
            f"指认没读完整排行（拿到 {sorted(referenced)}）")
        assert "top_slice" not in referenced, (
            "指认读了被截断的 top_slice —— 那会把「第 N 名」说成「最低」")

    def test_the_table_bold_is_driven_by_the_direction_slot(self):
        """表格那一格的加粗必须来自 `_bold_idx`，⛔ 不再是无条件的 `i == 0`。"""
        referenced = _names_referenced_by_assignment("top_text")
        assert "_bold_idx" in referenced, (
            f"表格加粗没接上方向槽位（拿到 {sorted(referenced)}）")
        assert "_rows_to_point_at" in _names_referenced_by_assignment("_bold_idx")

    def test_no_unconditional_first_row_bold_survives(self):
        """🔴 阴性：源码里不许再有「下标为 0 就加粗」这个形状。

        ⚠️ 只扫 `resolve_store_margin`，⛔ 不扫全模块 —— `resolve_gross_margin`
           里还有一处同形（菜品毛利前 5 名的「1. **鲈鱼**」），本轮
           **有意不改**：那个函数正被另一条并行线改着，同轮双改必冲突。
           🔴 解冻条件：那条线合入之后，拿「毛利最低的那道菜是哪道」
           重跑 `smartbi.scripts._nameit_repro`，若它仍加粗绝对毛利第一名 ⇒ 该修了。
        """
        tree = ast.parse(_resolver_src())
        bad = [
            node for node in ast.walk(tree)
            if isinstance(node, ast.Compare)
            and isinstance(node.left, ast.Name) and node.left.id == "i"
            and len(node.ops) == 1 and isinstance(node.ops[0], ast.Eq)
            and isinstance(node.comparators[0], ast.Constant)
            and node.comparators[0].value == 0
        ]
        assert not bad, (
            "`resolve_store_margin` 里还有 `i == 0` 的无条件点名 —— "
            "方向未知时它就是一句没有依据的断言")


# ══ 五 · 前提：仪器真的看得见东西 ════════════════════════════════════════
def test_the_probe_data_still_has_the_shape_these_assertions_need():
    """⚠️ 上面几条全押在「这份数据有并列、两个口径不同家」上 —— 先证明它成立。

    ⛔ 少了这条，改坏 `_entries()` 会让一串断言变成恒真式。
    """
    entries = _entries()
    assert len(entries) == 10
    hi_p, _, lo_p, _ = _display_extremes(entries, _profit, _money)
    hi_r, _, lo_r, _ = _display_extremes(entries, _rate, _pct)
    assert (len(hi_p), len(lo_p)) == (1, 1), "绝对毛利这一侧本来就不该有并列"
    assert len(hi_r) == 2 and len(lo_r) == 2, "毛利率这一侧的并列没了，第三条断言会空转"
    assert set(hi_p) & set(hi_r) == set(), "两个口径的最高位撞在一起了"


@pytest.mark.parametrize("direction", ["best", "worst"])
def test_the_two_directions_are_not_the_same_row(direction):
    """⚠️ 参数化的那一维**真的不同** —— 否则「两个方向都过」只是同一个样本跑两遍。"""
    assert _rows_to_point_at(direction, (0,), (9,), 10) == (
        {0} if direction == "best" else {9})
