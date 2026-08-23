# -*- coding: utf-8 -*-
"""点名单菜时，同一屏的 4 张 KPI 卡各说各的范围。

## prod 实测（`_kpi_says_cannot_table_says_can`，MOCK_REST，3 轮逐轮一致）

「罗氏虾卖得怎么样」这一屏：

```
KPI 总毛利 = 暂不可计算(rawValue=None)   最赚菜品 = —(0)
同一屏的表 = 10 行，其中 **6 行有真毛利**，鲈鱼 ¥2,894,006.16 最高
```

合取式判据（`总毛利.rawValue is None` **AND** 表里有毛利的行数 > 0）**3/3 成立**。

## 成因（读码，两层）

**第一层：表根本不是这个 resolver 出的。**
`resolve_gross_margin` 里 `pos_rows = matched_rows` 把 `enriched` 削成**那一道**；
而那张 10 行的表来自**另一个模块** `daily_table.py`（`_row(["菜品","营收","成本","毛利"])`），
按**全菜品**出。两个产出被拼进同一个答案，没有一个字说它们不是一回事。

**第二层：KPI 块自己内部也混着范围。**

| 卡 | 来源 | 范围 |
|---|---|---|
| 总营收 | `total_rev = total_paid_rev` | **全店全菜** |
| 总毛利 / 平均毛利率 / 最赚菜品 | `total_profit` / `avg_margin` / `top_slice` | **点名那一道** |

## 修法：标范围，⛔ 不删卡片

brief 明写「⛔ 不要把 KPI 直接删掉 —— 那是把矛盾藏起来，不是解决它」。

⚠️ 「最赚菜品」在点名单菜时是**同义反复**（可比的只有那一道，结果要么是它
自己、要么是「—」）。⇒ 把这件事**说出来**：「最赚菜品（只比了这一道）」。

## 🔴 这一刀安全的**全部依据**，就是下面那道闸

有 **3 个消费方**按 `title` **精确相等**读 KPI：

```
smartbi/api/restaurant_ops_gold.py:576/578/580              总营收 / 总毛利 / 平均毛利率
smartbi/services/restaurant/sections/store_kpi_dashboard.py:233   平均毛利率
```

【读码】这三处**都不传 `dish_mention`** ⇒ 永远走不到点名那条路 ⇒
只在点名时改 title 对它们无影响。
▎**这个「走不到」是本刀的前提，所以它必须被钉住** ——
▎哪天有人给这些调用加上 `dish_mention`，前提就没了，而症状是
▎消费方**静默**读不到 KPI（`for` 循环一条都进不去，`margin_totals` 原样全 0），
▎同一个文件的注释里记着这个坑已经发生过一次。

## 断言各守什么（⛔ 不是同义的）

    承重1  范围措辞只有**一处**定义，正文与 KPI 卡共用它（形态 D）
    承重2  点名时 4 张卡**都**带范围
    🔴阴性1 **不点名时 4 个 title 逐字不变** —— 三个字面量消费方靠它活着
    🔴阴性2 那三个消费方**不许**开始传 dish_mention（本刀前提，⛔ 不是形式）
    阴性3  ⛔ 卡片一张都不许少（删卡 = 藏矛盾）
"""
from __future__ import annotations

import ast
import inspect
import textwrap
from pathlib import Path

from smartbi.gold.restaurant import restaurant_ops_router as router

_SRC = inspect.getsource(router.resolve_gross_margin)
_TREE = ast.parse(textwrap.dedent(_SRC))

#: 不点名时这 4 个 title **必须逐字是这些**。
_UNSCOPED_TITLES = ("总营收", "总毛利", "平均毛利率", "最赚菜品")

#: 按 title 精确相等消费 KPI 的文件（相对 `backend/python`）。
_LITERAL_CONSUMERS = (
    "smartbi/api/restaurant_ops_gold.py",
    "smartbi/services/restaurant/sections/store_kpi_dashboard.py",
)

_PY_ROOT = Path(inspect.getfile(router)).resolve().parents[3]


def _kpi_list_node():
    """`response_kpis = [...]` 那个列表字面量。"""
    for n in ast.walk(_TREE):
        if isinstance(n, ast.Assign):
            for t in n.targets:
                if isinstance(t, ast.Name) and t.id == "response_kpis":
                    return n.value
    return None


def _title_node(d: ast.Dict):
    for k, v in zip(d.keys, d.values):
        if isinstance(k, ast.Constant) and k.value == "title":
            return v
    return None


def _unscoped_title(node) -> str:
    """把 title 表达式在「**没点名**」那一侧求值成字面量。

    ⛔ 不是 eval —— 只认三种形状，其余一律判失败（宁可红，不要蒙对）：
        Constant                无条件，原样
        IfExp                   取 `orelse`（`if _scoped_dish_name else <这里>`）
        JoinedStr(f"…{_dish_tag}")   `_dish_tag` 不点名时是空串 ⇒ 取字面量部分
    """
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    if isinstance(node, ast.IfExp):
        return _unscoped_title(node.orelse)
    if isinstance(node, ast.JoinedStr):
        out = []
        for part in node.values:
            if isinstance(part, ast.Constant) and isinstance(part.value, str):
                out.append(part.value)
            elif isinstance(part, ast.FormattedValue):
                names = {n.id for n in ast.walk(part.value)
                         if isinstance(n, ast.Name)}
                # 只有「不点名时恒为空串」的那个名字可以出现在 title 里
                assert names == {"_dish_tag"}, (
                    f"title 的 f-string 里插了 {sorted(names)} —— "
                    "只有不点名时恒为空串的 `_dish_tag` 允许出现，"
                    "别的名字会让不点名那一侧的 title 也变掉")
            else:
                raise AssertionError(f"看不懂的 title 片段: {ast.dump(part)}")
        return "".join(out)
    raise AssertionError(f"看不懂的 title 表达式: {ast.dump(node)}")


def test_precondition_the_kpi_list_is_where_we_think():
    """前提：找得到那个 KPI 列表，且恰好 4 张卡。

    ⚠️ 找不到的话下面每条断言都会以「找不到」的方式**假绿**。
    """
    node = _kpi_list_node()
    assert node is not None, "找不到 `response_kpis` 的赋值"
    assert isinstance(node, ast.List), "`response_kpis` 不是列表字面量"
    dicts = [e for e in node.elts if isinstance(e, ast.Dict)]
    assert len(dicts) == len(_UNSCOPED_TITLES), (
        f"KPI 卡有 {len(dicts)} 张（登记的是 {len(_UNSCOPED_TITLES)} 张）")


def test_the_scope_wording_has_one_source():
    """承重1：范围措辞只有一处定义，正文与 KPI 共用（形态 D）。

    ⛔ 各写各的，过几天就会变成正文说「全部门店」而卡片说「全店」——
    老板看到两种说法，会以为它们是**两个范围**。
    """
    mod_tree = ast.parse(inspect.getsource(router))
    assigns = [
        n for n in ast.walk(mod_tree)
        if isinstance(n, ast.Assign)
        for t in n.targets
        if isinstance(t, ast.Name) and t.id == "_ALL_SCOPE_LABEL"
    ]
    assert len(assigns) == 1, f"`_ALL_SCOPE_LABEL` 被赋值 {len(assigns)} 次（应为 1）"
    used = [
        n for n in ast.walk(_TREE)
        if isinstance(n, ast.Name) and n.id == "_ALL_SCOPE_LABEL"
    ]
    assert len(used) >= 2, (
        f"`_ALL_SCOPE_LABEL` 只被用了 {len(used)} 处 —— "
        "正文那行和 KPI 卡应该**都**用它，少一处就是有人在就地写字符串")


def test_every_card_carries_its_scope_when_a_dish_is_named():
    """承重2：点名时 4 张卡**都**带范围。

    ⛔ 只标一部分，剩下的那张仍然会被读成「这道菜的」或「全店的」，
    而它是哪一个恰恰是老板判断不了的。
    """
    dicts = [e for e in _kpi_list_node().elts if isinstance(e, ast.Dict)]
    bare = []
    for d in dicts:
        t = _title_node(d)
        # 带范围 = 它不是一个无条件的常量（要么 IfExp 要么 f-string 插 _dish_tag）
        if isinstance(t, ast.Constant):
            bare.append(t.value)
    assert not bare, (
        f"这 {len(bare)} 张卡在点名单菜时**不标范围**: {bare} —— "
        "同一屏上它们和别的卡不是一个范围")


def test_the_unscoped_titles_are_unchanged_to_the_byte():
    """🔴 阴性1：**不点名时 4 个 title 逐字不变。**

    三个消费方按 `title` **精确相等**读它们；title 一变，
    它们的 `for` 循环一条都进不去，`margin_totals` 原样保持全 0 ——
    ▎**静默**，不报错。同一个文件的注释里记着这个坑已经发生过一次。
    """
    dicts = [e for e in _kpi_list_node().elts if isinstance(e, ast.Dict)]
    got = tuple(_unscoped_title(_title_node(d)) for d in dicts)
    assert got == _UNSCOPED_TITLES, (
        f"不点名时的 title 变了: {got}\n"
        f"应为: {_UNSCOPED_TITLES}\n"
        "—— 三个按 title 精确相等读的消费方会静默读不到任何 KPI")


def test_the_literal_consumers_still_never_name_a_dish():
    """🔴 阴性2：那三个消费方**不许**开始传 dish_mention。

    ▎「它们走不到点名那条路」是本刀安全的**全部依据**。
    依据没了而代码还在，就是形态 B 的反面：机制没坏，是前提被人挪走了。
    """
    offenders = []
    for rel in _LITERAL_CONSUMERS:
        path = _PY_ROOT / rel
        assert path.exists(), f"消费方文件不在了: {path}"
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if not isinstance(node, ast.Call):
                continue
            fn = node.func
            name = (fn.id if isinstance(fn, ast.Name)
                    else fn.attr if isinstance(fn, ast.Attribute) else None)
            if name not in ("resolve_gross_margin", "_resolve_gross_margin"):
                continue
            kws = {k.arg for k in node.keywords if k.arg}
            bad = kws & {"dish_mention", "dish_mentions"}
            if bad:
                offenders.append(f"{rel}:{node.lineno} 传了 {sorted(bad)}")
    assert not offenders, (
        "按 title 精确相等读 KPI 的消费方开始点名单菜了:\n  "
        + "\n  ".join(offenders)
        + "\n—— 那样它们会拿到带范围后缀的 title，然后**静默**读不到任何 KPI")


def test_no_card_was_deleted_to_hide_the_contradiction():
    """阴性3：⛔ 一张卡都不许少。

    brief 明写「不要把 KPI 直接删掉 —— 那是把矛盾藏起来，不是解决它」。
    这一条守的是**不能做的那件事**，与承重2「都要标」不是同一回事。
    """
    dicts = [e for e in _kpi_list_node().elts if isinstance(e, ast.Dict)]
    got = {_unscoped_title(_title_node(d)) for d in dicts}
    assert got == set(_UNSCOPED_TITLES), (
        f"KPI 卡少了: {sorted(set(_UNSCOPED_TITLES) - got)}")
