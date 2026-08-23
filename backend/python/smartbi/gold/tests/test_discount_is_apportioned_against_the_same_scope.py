# -*- coding: utf-8 -*-
"""折扣摊派的分母，必须和实收营收**同范围**。

## prod 实测（`_dish_margin_arithmetic`，MOCK_REST，2 轮逐轮一致）

| | `total_rev_items` | `paid`(全店) | `_discount` | KPI 总毛利 | 表内真实毛利 |
|---|---|---|---|---|---|
| 鲈鱼（点名） | 4,625,280 | 21,497,363.94 | **−16,872,083.94** 🔴 | **¥19,766,090** | ¥2,894,006 |
| 罗氏虾（点名） | 5,505,792 | 21,497,363.94 | **−15,991,571.94** 🔴 | None | 无卡 |
| 不点名（对照） | 22,457,139 | 21,497,363.94 | +959,775.06 ✅ | ¥9,271,894 | — |

▎**鲈鱼的毛利报成了真实值的 6.8 倍。**

## 成因（读码 + 实测，⛔ 我先推翻过自己一次）

我最初的候选成因是「`dish_candidate` 只在候选恰好 1 个时才过滤 ⇒ 鲈鱼没被过滤」。
`_kpi_scope_trace` **实测推翻了它**：4/4 两侧 `pos_rows=1`、`dish_scope_row`
都落上了 —— 过滤**生效了**，鲈鱼是**单行**却算出 19.77M。

真因在算式本身：

```python
total_paid_rev = await _paid_revenue_in_window(pool, factory_id, w0, w1)
#   ↑ 查 fact_pos_transaction，只按 factory_id + date 过滤，**没有菜品维度**
#     （结构上也过滤不了 —— 它刻意不 join 明细以免扇出，见其 docstring）
_discount = total_rev_items - total_paid_rev     # 单菜 − 全店 = **负数**
_share    = total_rev_with_cost / total_rev_items
covered_net_rev = total_rev_with_cost - _discount * _share   # 减负数 = 膨胀
```

⚠️ `margin_invariant_pass`（`total_profit <= covered_net_rev`）**拦不住** ——
分子分母一起膨胀，闸的两边同向移动。
▎形态 B⁴：闸没坏，是这种输入让它在这里恒真。

## 修法：分母换成 `benchmark_pos_rows`（**过滤之前**那一份拷贝）

⛔ `total_rev_items` 本身**不动**。硬约束 8 对账：

    改前 `total_rev_items` **8 处使用**
    改后 = 5 处迁到 `all_items_rev` + **3 处显式登记「故意不改」**

「故意不改」的 3 处及理由（⛔ 登记是留痕，不是豁免）：

    coverage_ratio 分子/分母（2 处）  它问的是「**这道菜**有没有卡」，按单菜是对的
    CellResult 末位参数（1 处）        与 coverage_ratio 配套，同一个 cell 的覆盖信息

⚠️ 不点名时 `all_items_rev == total_rev_items` ⇒ 那条路的行为**逐字不变**
（prod 阳性对照：不点名那句的全文 md5 修前修后必须相同）。

## 断言各守什么（⛔ 不是同义的）

    承重1  `all_items_rev` 只有**一处**来源，且来自 `benchmark_pos_rows`
    承重2  `_discount` / `_share` 用 `all_items_rev`，⛔ 不用 `total_rev_items`
    承重3  实收取不到时的 `total_rev` 兜底也用全菜那一份
           （它打出来时带的标签是「全部门店、全部菜品」）
    承重4  「有没有折扣」那句限定语的比较也同范围
           —— 否则它在点名单菜时**恒出现**，哪怕一分钱折扣都没有
    阴性1  `coverage_ratio` **仍然**用 `total_rev_items`（⛔ 不许顺手一起换掉）
    阴性2  硬约束 8 的账要平：`total_rev_items` 剩下的使用点恰好 3 处
"""
from __future__ import annotations

import ast
import inspect
import textwrap

from smartbi.gold.restaurant import restaurant_ops_router as router

_SRC = inspect.getsource(router.resolve_gross_margin)
_TREE = ast.parse(textwrap.dedent(_SRC))

#: 【实测】改后 `total_rev_items` 剩下的使用点数（⛔ 不含它自己的赋值）。
_KEPT_ON_PURPOSE = 3


def _names_of(node) -> set:
    return {n.id for n in ast.walk(node) if isinstance(n, ast.Name)}


def _assigns_to(name: str):
    return [
        n for n in ast.walk(_TREE)
        if isinstance(n, ast.Assign)
        for t in n.targets
        if isinstance(t, ast.Name) and t.id == name
    ]


def test_precondition_both_names_exist():
    """前提：两个名字都在。

    ⚠️ 名字对不上的话，下面每条断言都会以「找不到」的方式**假绿**。
    """
    assert _assigns_to("all_items_rev"), "`all_items_rev` 不在源码里"
    assert _assigns_to("total_rev_items"), "`total_rev_items` 不在源码里"


def test_the_whole_menu_revenue_has_one_source():
    """承重1：`all_items_rev` 只有一处来源，且来自过滤前那份拷贝。

    形态 D：同一个东西两份就会漂 —— 这里漂的后果是折扣摊派的分母
    和实收营收又变成两个范围。
    """
    assigns = _assigns_to("all_items_rev")
    assert len(assigns) == 1, f"`all_items_rev` 被赋值 {len(assigns)} 次（应为 1）"
    assert "benchmark_pos_rows" in _names_of(assigns[0].value), (
        "`all_items_rev` 不是从 `benchmark_pos_rows`（过滤前那份拷贝）算的")


def test_the_discount_denominator_matches_the_paid_revenue_scope():
    """🔴 承重2：`_discount` / `_share` 用全菜那一份。

    ⛔ 用 `total_rev_items` 的话，点名单菜时它是「一道菜 − 全店」= 负数。
    """
    for name in ("_discount", "_share"):
        assigns = _assigns_to(name)
        assert assigns, f"找不到 `{name}` 的赋值"
        for a in assigns:
            names = _names_of(a.value)
            assert "all_items_rev" in names, (
                f"`{name}` 没有用 `all_items_rev`（用到的名字: {sorted(names)}）")
            assert "total_rev_items" not in names, (
                f"`{name}` 还在用被削成单菜的 `total_rev_items` —— "
                "点名单菜时它会算出一个负的折扣")


def test_the_fallback_total_revenue_is_also_whole_menu():
    """承重3：实收取不到时的兜底也用全菜那一份。

    它打出来时带的标签是「实收营收（全部门店、全部菜品）」——
    标签说「全部菜品」而数是一道菜的，就是上一刀刚修掉的那个形态又长回来。
    """
    assigns = _assigns_to("total_rev")
    assert assigns, "找不到 `total_rev` 的赋值"
    used = set()
    for a in assigns:
        used |= _names_of(a.value)
    assert "total_rev_items" not in used, (
        f"`total_rev` 还在用被削成单菜的 `total_rev_items`（名字: {sorted(used)}）")


def test_the_has_discount_check_is_same_scope():
    """承重4：「这段时间有没有折扣」的比较也同范围。

    ⛔ 不同范围的话，点名单菜时差额恒为一个巨大的负数 ⇒
    那句「没扣折扣的单菜价」限定语**恒出现**，哪怕当天一分钱折扣都没有。
    ▎一句永远出现的限定语，和没有限定语一样没有信息。
    """
    full = inspect.getsource(router.resolve_gross_margin)
    tree = ast.parse(textwrap.dedent(full))
    found = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        if not (isinstance(node.func, ast.Name) and node.func.id == "abs"):
            continue
        names = _names_of(node)
        if "total_paid_rev" in names:
            found.append(names)
    assert found, "找不到那个 `abs(… - total_paid_rev)` 的折扣存在性判断"
    for names in found:
        assert "all_items_rev" in names and "total_rev_items" not in names, (
            f"折扣存在性判断两边不同范围（名字: {sorted(names)}）")


def test_coverage_ratio_is_deliberately_still_per_dish():
    """🔴 阴性1：`coverage_ratio` **仍然**按单菜算。

    ⛔ 这一条守的是**不能改的那一半**：覆盖率问的是
    「**这道菜**有没有成本卡」，点名单菜时它就该是 1.0 或 0.0。
    顺手把它一起换成全菜，会让「罗氏虾没有卡」被稀释成「全店覆盖 65%」。
    """
    assigns = _assigns_to("coverage_ratio")
    assert assigns, "找不到 `coverage_ratio` 的赋值"
    names = set()
    for a in assigns:
        names |= _names_of(a.value)
    assert "total_rev_items" in names, (
        "`coverage_ratio` 不再用 `total_rev_items` —— "
        "覆盖率被从「这道菜」稀释成了「全店」")
    assert "all_items_rev" not in names, (
        "`coverage_ratio` 被顺手换成了全菜口径")


def test_hard_constraint_8_the_count_balances():
    """阴性2：硬约束 8 的账要平。

    改前 `total_rev_items` **8 处使用**；改后应恰好剩
    `_KEPT_ON_PURPOSE` 处，其余全部迁走。
    ⛔ 数的是 `ast.Name` 的**读**（Load），⛔ 不是 grep 字符串 ——
    本仓已经因为「grep 把注释也数进去」栽过三次，而这次注释里
    正好**大量提到** `total_rev_items` 这个名字。
    """
    loads = [
        n for n in ast.walk(_TREE)
        if isinstance(n, ast.Name) and n.id == "total_rev_items"
        and isinstance(n.ctx, ast.Load)
    ]
    assert len(loads) == _KEPT_ON_PURPOSE, (
        f"`total_rev_items` 还有 {len(loads)} 处读取（登记的是 {_KEPT_ON_PURPOSE} 处）"
        " —— 多出来的那处多半是漏迁的，去读这份 docstring 的对账表")
