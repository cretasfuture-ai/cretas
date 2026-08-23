# -*- coding: utf-8 -*-
"""一道成本卡都没有时，⛔ 不把毛利小节的格式撑满。

## prod 实测（`_single_dish_answer_shape`，MOCK_REST，2 轮逐轮一致）

「罗氏虾卖得怎么样」正文 1825 字里，「¥0.00」**4 次**、「暂无」**2 次**：

```
毛利合计 **¥0.00**（估算：只算了 0.0% 的营收…）     ← ⚠️ **假数**，不是空壳
计算过程：`毛利 ¥0.00 = 净营收 ¥0.00 − 成本 ¥0.00`
毛利前 0 名菜品:  - 暂无成本完整、可计算毛利的菜品。
需要关注的低毛利菜品:  - 暂无明显低毛利菜品。
```

⚠️ 第一处**超出 brief 点名的三处**，登记在此：它比空壳更糟 ——
空壳是「没内容」，那是**一个看起来像结论的假数**。

⇒ 违反交付定义 ⑤（答不了时说清缺什么），而正文里 `_dish_verdict` 那句
（「N 道菜都没有成本卡，**赚没赚钱这次算不出来**」）**已经把话说清了**。

## 同一刀里的任务 2

抬头是**罗氏虾**的「营收 ¥5,432,704.00」，紧接着下一行是
**¥21,234,013.18** —— 全店全菜的实收，而范围说明在 **30 行之后**才出现。
▎老板读到的是「罗氏虾的下一行」。⇒ 修法选 (a) 加范围标签。

## 硬约束 8：改共享结构前先数

【实测】`render_headline` 在 `resolve_gross_margin` 里有 **2 处**调用点
（正文抬头 1 处 + `prohibited_actions_requested` 那支的「判断依据」1 处）。
第一版只圈了 1 处 —— 那支会把 ¥0.00 当**判断依据**摆出来，比正文那处更糟。

## 机器可读侧要跟着改，⛔ 不能只改正文

`response_kpis` 里的「总毛利 ¥0」是同一个假数的另一半。
⛔ **不删那个条目**：`smartbi/api/restaurant_ops_gold.py:578` 按 `title`
精确相等读 `rawValue`，删掉之后它落到 `0.0` 默认值 ——
「算不出」和「真的是 0」在消费方长得一模一样（形态 A¹⁰）。
⇒ 照抄**同一个列表里已有的**做法（「平均毛利率」→ `"暂不可计算"` / `None`），
⛔ 不新造第二套写法（形态 D）。

## 断言各守什么（⛔ 不是同义的）

    承重1  没成本卡 ⇒ 「计算过程」整段不渲染
    承重2  没成本卡 ⇒ 「毛利前 N 名 / 需要关注的低毛利」整段不渲染
    承重3  没成本卡 ⇒ **两处** `render_headline` 都不渲染（硬约束 8）
    承重4  没成本卡 ⇒ kpis 的「总毛利」说人话，且 `rawValue` 给 None
    承重5  任务 2：实收营收那一行**自带范围标签**
    阴性1  「N/M 个销售菜品有完整成本数据」**任何时候**都留着
           —— 它正是「缺什么」那句实话，⛔ 不跟着一起删
    阴性2  与成本无关的段落（联合优先级 / 禁止动作）在没成本卡时**照发**
    阴性3  ⛔ kpis 的「总毛利」条目不许被删掉（消费方按 title 精确相等读）

## ⚠️ 这一组是**结构**断言

真实入口的行为证据在 prod 探针 `_single_dish_answer_shape` 上（形态 B：
至少一条断言跑在产品真实入口）。这里守的是「圈没圈住」这个结构，
因为 `resolve_gross_margin` 要 pool + 真实 POS 数据，
而给它造桩会掉进形态 B‴（桩的形状在生产上永不出现）。
"""
from __future__ import annotations

import ast
import inspect
import textwrap

from smartbi.gold.restaurant import restaurant_ops_router as router

_SRC = inspect.getsource(router.resolve_gross_margin)
_TREE = ast.parse(textwrap.dedent(_SRC))

#: 那个基数守卫的名字。⚠️ 前提断言在下面单独一条 —— 名字错了的话
#: 下面每条 AST 断言都会以「找不到」的方式**假绿**。
_GUARD = "_has_any_cost"


def _fenced_ifexps():
    """所有 test 里提到 `_has_any_cost` 的条件表达式（含 `ast.If`）。"""
    out = []
    for node in ast.walk(_TREE):
        if isinstance(node, (ast.IfExp, ast.If)):
            if _GUARD in ast.dump(node.test):
                out.append(node)
    return out


def _fenced_strings() -> set:
    """被守卫圈住的所有字符串字面量。"""
    got = set()
    for node in _fenced_ifexps():
        for sub in ast.walk(node):
            if isinstance(sub, ast.Constant) and isinstance(sub.value, str):
                got.add(sub.value)
    return got


def _all_strings() -> set:
    got = set()
    for sub in ast.walk(_TREE):
        if isinstance(sub, ast.Constant) and isinstance(sub.value, str):
            got.add(sub.value)
    return got


def _has_fragment(pool, needle: str) -> bool:
    return any(needle in s for s in pool)


def test_precondition_the_guard_exists():
    """前提：基数守卫真的在源码里，且它读的是「有成本卡的菜」。

    ⚠️ 不在的话下面每条断言都会假绿。
    """
    assert _GUARD in _SRC, "守卫名字对不上 —— 下面的断言全部会假绿"
    assigns = [
        n for n in ast.walk(_TREE)
        if isinstance(n, ast.Assign)
        for t in n.targets
        if isinstance(t, ast.Name) and t.id == _GUARD
    ]
    assert len(assigns) == 1, f"`{_GUARD}` 被赋值 {len(assigns)} 次（应为 1）"
    assert "with_cost" in ast.dump(assigns[0].value), (
        "守卫读的不是「有成本卡的菜」")
    assert _fenced_ifexps(), "没有任何条件表达式用到这个守卫"


def test_the_calculation_walkthrough_is_fenced():
    """承重1：「计算过程」整段被守卫圈住。"""
    assert _has_fragment(_fenced_strings(), "计算过程："), (
        "「计算过程：毛利 ¥0.00 = 净营收 ¥0.00 − 成本 ¥0.00」没有被圈住")


def test_the_rankings_are_fenced():
    """承重2：「毛利前 N 名」「需要关注的低毛利菜品」整段被圈住。"""
    fenced = _fenced_strings()
    assert _has_fragment(fenced, "名菜品（按绝对毛利）"), (
        "「毛利前 0 名菜品: - 暂无…」没有被圈住")
    assert _has_fragment(fenced, "需要关注的低毛利菜品"), (
        "「需要关注的低毛利菜品: - 暂无…」没有被圈住")


def test_every_headline_call_is_fenced():
    """🔴 承重3（硬约束 8）：**两处** `render_headline` 都被圈住。

    ⛔ 只圈一处 = 改了一半。没圈的那处（`prohibited_actions_requested` 支）
    把「毛利合计 ¥0.00」当**判断依据**摆出来 —— 比正文那处更糟。
    """
    def _calls(tree):
        return [
            n for n in ast.walk(tree)
            if isinstance(n, ast.Call)
            and isinstance(n.func, ast.Name)
            and n.func.id == "render_headline"
        ]

    total = len(_calls(_TREE))
    fenced = sum(len(_calls(node)) for node in _fenced_ifexps())
    assert total >= 2, f"`render_headline` 调用点只数到 {total} 处（预期 >= 2）"
    assert fenced == total, (
        f"`render_headline` 有 {total} 处调用点，只有 {fenced} 处被守卫圈住"
        " —— 漏掉的那处会把 ¥0.00 当判断依据发出去")


def test_the_machine_readable_side_says_the_same_thing():
    """承重4：kpis 的「总毛利」在没成本卡时说人话，`rawValue` 给 None。

    ⛔ 只改正文的话，前端 KPI 卡照旧显示「总毛利 ¥0」。
    """
    fenced = _fenced_strings()
    assert "暂不可计算" in fenced, (
        "kpis 的「总毛利」没有被守卫圈住 —— 前端仍会显示 ¥0")
    ok = False
    for node in _fenced_ifexps():
        if not isinstance(node, ast.IfExp):
            continue
        if isinstance(node.orelse, ast.Constant) and node.orelse.value is None:
            ok = True
            break
    assert ok, "没有任何一处在守卫为假时把 `rawValue` 给成 None"


def test_the_revenue_line_carries_its_own_scope():
    """🔴 承重5（任务 2）：实收营收那一行**自带范围标签**。

    ⛔ 判据不是「正文某处提到过全部门店」—— 那句范围说明本来就在 30 行之后。
    要的是**这一行自己**带着它，因为老板读到的是「罗氏虾的下一行」。

    ## ⚠️ 判据升级（2026-08-19，留痕，⛔ 不删）

    第一版断言落在**字符串字面量**上（`"全部门店" in line`）。
    下一刀把那段措辞抽成了模块常量 `_ALL_SCOPE_LABEL`（因为 KPI 卡也要标范围，
    两处各写各的一定会漂 —— 形态 D），于是字面量里不再有那几个字，
    **这条断言当场红了**。

    ▎它红得**正确** —— 是判据守的层次太低，⛔ 不是代码错了。
    （形态 C‴：断言守的是「历史」还是「需求」？这里是历史。）

    ⇒ 把守的东西从**字面**抬到**性质**：
      ① 那一行只有一份（形态 D）
      ② 它引用的是那个**唯一**的范围常量，⛔ 不是就地写死
      ③ 那个常量本身两半都在
    ⛔ 改断言最容易滑成删断言 —— 这里守的东西比原来**更多**，不是更少。
    """
    tree = ast.parse(textwrap.dedent(_SRC))
    lines = [
        n for n in ast.walk(tree)
        if isinstance(n, ast.JoinedStr)
        and any(isinstance(p, ast.Constant) and isinstance(p.value, str)
                and p.value.startswith("- 实收营收") for p in n.values)
    ]
    assert lines, "找不到那行实收营收"
    assert len(lines) == 1, f"实收营收那一行有 {len(lines)} 份 —— 它一定会漂（形态 D）"
    names = {n.id for n in ast.walk(lines[0]) if isinstance(n, ast.Name)}
    assert "_ALL_SCOPE_LABEL" in names, (
        f"实收营收那一行没有引用那个唯一的范围常量（用到的名字: {sorted(names)}）"
        " —— 就地写死的话，KPI 卡那一份和它一定会漂")
    label = router._ALL_SCOPE_LABEL
    assert "全部门店" in label and "全部菜品" in label, (
        f"范围常量本身缺了一半: {label!r}")


def test_what_is_missing_is_always_said():
    """🔴 阴性1：「N/M 个销售菜品有完整成本数据」**任何时候**都留着。

    它正是「缺什么」那句实话（0/1 也说明问题）——
    ⛔ 不许跟着空壳一起被删掉。这一条守的是**不能删的那一半**。
    """
    assert _has_fragment(_all_strings(), "个销售菜品有完整成本数据"), (
        "那句「缺什么」的实话不见了")
    assert not _has_fragment(_fenced_strings(), "个销售菜品有完整成本数据"), (
        "那句「缺什么」的实话被守卫圈住了 —— 没成本卡时它反而不说了，"
        "而那正是最该说的时候")


def test_cost_independent_paragraphs_still_ship():
    """阴性2：与成本无关的段落在没成本卡时**照发**。

    ⛔ 「删空壳」不等于「删掉那一整块」—— 联合优先级 / 禁止动作
    不依赖成本卡，删了它们是**顺手砍掉了能用的东西**。
    """
    ranking = [
        n for n in ast.walk(_TREE)
        if isinstance(n, ast.Assign)
        for t in n.targets
        if isinstance(t, ast.Name) and t.id == "_ranking_block"
    ]
    assert len(ranking) == 1, f"`_ranking_block` 被赋值 {len(ranking)} 次（应为 1）"
    value = ranking[0].value
    assert isinstance(value, ast.IfExp), "`_ranking_block` 不是条件表达式"
    names = {
        n.id for n in ast.walk(value.orelse) if isinstance(n, ast.Name)
    }
    assert "joint_priority_text" in names and "prohibited_actions_text" in names, (
        f"没成本卡那一支把与成本无关的段落也砍掉了: {sorted(names)}")


def test_the_gross_profit_kpi_entry_is_not_deleted():
    """阴性3：⛔ 「总毛利」这个 kpi **条目**不许被删掉。

    `smartbi/api/restaurant_ops_gold.py:578` 按 `title` 精确相等读它的
    `rawValue`；删掉之后它落到 `0.0` 默认值 ——
    「算不出」和「真的是 0」在消费方长得一模一样（形态 A¹⁰）。
    ⇒ 守的是**条目还在**，而不是「值对不对」（那是承重4）。
    """
    assert "总毛利" in _all_strings(), (
        "「总毛利」这个 kpi 条目被删了 —— 消费方会读到 0.0，"
        "而「算不出」和「真的是 0」在它那里长得一样")
