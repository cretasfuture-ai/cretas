# -*- coding: utf-8 -*-
"""拒答的正文里印了数，机器可读侧就得有那份数 —— 印了什么就发什么。

## prod 实测（`_numbers_only_in_prose`，MOCK_REST，2 轮逐轮一致）

```
kind='answer'         6 轮   🔴手边有数却不给 0   ⚠️无 kpis 键 0
kind='clarification'  6 轮   🔴手边有数却不给 4   ⚠️无 kpis 键 **6**
     营收为什么下滑                kpis=None 金额 1 (¥21,320,682.41) 🔴
     徐汇美罗城店最近30天为什么毛利低    kpis=None 百分比 2            🔴
     各个渠道的毛利分别是多少          kpis=None 金额 0 百分比 0
```

▎**拒答路径 6/6 顶层没有 `kpis` 键**，而其中 4 轮正文里数得到数。

## brief 说「前端拿不到」是推断 ⇒ 先读消费方（【读码】，订正一条）

```
smartbi/api/gold_reads.py:1356    "kpis": result.get("kpis") or [],
```

▎**缺键到不了前端** —— 这一层把它兜成 `[]`。
对前端等价（都渲染不出 KPI 卡），⛔ 但对成因不等价：
前者像「漏了透传」，后者是「那条路根本没往里放数」。
🔴 读数对 ≠ 成因对（纪律 10）。

## 硬约束 8：改共享结构前先数

【实测·AST】`kind: "clarification"` 的构造点共 **6 处**。
⚠️ `grep` 数出来是 8 行 —— 多出的 2 行一个在 docstring、一个在注释里。
▎这正是本仓记过三次的「字符串计数把注释也数进去」，所以这里用 AST。

手边有没有数，逐处判（⛔ 不是「都加上」）：

| 来源标记 | 走到这里时 | 给不给数 |
|---|---|---|
| `store_ambiguous`       | 只消解了门店名歧义，resolver 一个没跑 | ⛔ 没有 |
| `planner_clarification` | 规划阶段就返回（缺时间/缺门店/能力缺口） | ⛔ 没有 |
| `dimension_mismatch`    | 正因为维度对不上才没敢跑 | ⛔ 没有 |
| `no_usable_result`      | 前提就是 `not tiered_result` | ⛔ 没有 |
| **`guard_decline`**     | `tiered_result` 在，`kpis` 下一行就被取走 | ✅ 给 |
| **`contract_rejected`** | 产出过又被驳回，正文已经印了这份数 | ✅ 给 |

⛔ 手边没有数的那四处**不放空数组**：`[]` 和「这条路不给数」在消费方
（`result.get("kpis") or []`）长得一模一样，放一个空数组只会让下次读数
分不清（形态 A¹⁰：兜底默认值把「我不知道」翻译成「是 0」）。

## ⛔ 只在正文真的用了这份数时才发

`safe_text = partial_text or (整份拒答文案)`。`partial_text is None` 时正文说的是
「这次没算出 X」——那时挂上 KPI 卡就是**正文和机器可读侧打架**，比现在更糟。
▎机器可读侧跟着正文走，⛔ 不各说各的。
（这与「最高月/最低月」那一刀的承重2 是同一条：两边必须说同一件事。）

## 断言各守什么（⛔ 不是同义的）

    承重1  6 处构造点**全部**带 `clarification_source`，且互不相同
    承重2  挂 `kpis` 的恰好 **2** 处，且都**带条件**（⛔ 不是无条件挂上）
    承重3  那份数只有**一处**来源（`_facts_in_hand`），⛔ 不是各取各的
    阴性1  ⛔ 6 处不许全挂 —— 全挂等于把「没有数」也说成「有数没给」
    阴性2  ⛔ 构造点不许悄悄变多（变多 = 又长出一处没人管的拒答出口）
"""
from __future__ import annotations

import ast
import inspect
import textwrap

from smartbi.gold.restaurant import restaurant_intent_service as svc

_SRC = inspect.getsource(svc)
_TREE = ast.parse(_SRC)

#: 【实测·AST】当天的构造点数。⚠️ 这是**冻结值**，变了要来读这份 docstring。
_EXPECTED_SITES = 6
#: 手边确实有数、因而挂 kpis 的那两处
_EXPECTED_WITH_FACTS = 2


def _clarification_dicts():
    """所有 `{"kind": "clarification", …}` 字面量。

    ⚠️ 用 AST，⛔ 不用 grep —— grep 会把 docstring 和注释里的那两处也数进来
    （本仓已经因为同一个形状栽过三次）。
    """
    out = []
    for node in ast.walk(_TREE):
        if not isinstance(node, ast.Dict):
            continue
        for k, v in zip(node.keys, node.values):
            if (isinstance(k, ast.Constant) and k.value == "kind"
                    and isinstance(v, ast.Constant) and v.value == "clarification"):
                out.append(node)
                break
    return out


def _source_marker(d: ast.Dict):
    for k, v in zip(d.keys, d.values):
        if isinstance(k, ast.Constant) and k.value == "clarification_source":
            return v.value if isinstance(v, ast.Constant) else "<非字面量>"
    return None


def _kpi_subscript_assigns():
    """所有 `<某个结果>["kpis"] = …` 的赋值节点。"""
    out = []
    for node in ast.walk(_TREE):
        if not isinstance(node, ast.Assign):
            continue
        for t in node.targets:
            if (isinstance(t, ast.Subscript)
                    and isinstance(t.slice, ast.Constant)
                    and t.slice.value == "kpis"):
                out.append(node)
    return out


def test_precondition_the_site_count_is_what_the_docstring_says():
    """前提：构造点数没变。

    ⚠️ 变了的话下面每条计数断言都在量一个和 docstring 不同的东西 ——
    那正是「读数对 ≠ 成因对」的入口。
    """
    sites = _clarification_dicts()
    assert len(sites) == _EXPECTED_SITES, (
        f"clarification 构造点从 {_EXPECTED_SITES} 变成了 {len(sites)} —— "
        "多出来的那处多半是又一个没人管的拒答出口，先去读它手边有没有数")


def test_every_refusal_says_which_one_it_is():
    """承重1：6 处**全部**带来源标记，且互不相同。

    形态 A¹³：每条读数带来源标记，否则事后分不清是哪一档拒答 ——
    而「哪一档」正好决定了它手边有没有数。
    """
    markers = [_source_marker(d) for d in _clarification_dicts()]
    missing = [i for i, m in enumerate(markers) if m is None]
    assert not missing, f"有 {len(missing)} 处拒答没说自己是哪一档: 下标 {missing}"
    assert len(set(markers)) == len(markers), (
        f"来源标记有重名，事后分不开: {markers}")


def test_exactly_the_two_sites_with_facts_ship_them():
    """🔴 承重2：挂 `kpis` 的恰好 2 处，且**都带条件**。

    ⛔ 无条件挂上 = 正文说「什么都没算出来」而 KPI 卡摆着数字。
    """
    assigns = _kpi_subscript_assigns()
    assert len(assigns) == _EXPECTED_WITH_FACTS, (
        f"挂 kpis 的地方有 {len(assigns)} 处（应为 {_EXPECTED_WITH_FACTS}）")

    # 每一处都必须由**「手边这份数真的能用」这个条件本身**管着。
    #
    # ⚠️ 判据订正两次，都留痕（⛔ 不删）：
    #   第一版 `guarded += 1` 累加 —— `if` 是嵌套的，同一个赋值被外层和内层
    #     各数一次，读出 `guarded=4 > len(assigns)=2`。
    #     ▎想知道「有几个 assign 在 if 里」，实际量的是「(If, assign) 配对数」。
    #   第二版按身份去重，绿了 —— **然后变异 M4 打进去它还是绿的**。
    #     把条件整个删掉（无条件挂 kpis）之后判据纹丝不动，因为这一整段
    #     本来就在一个大 `if`（契约失败分支）里，`ast.walk` 照样找得到。
    #     ▎**判据太松**：它问的是「在不在某个 if 里」，
    #     ▎而我想知道的是「是不是**这个条件**在管它」。
    #   ⇒ 第三版：取**直接父 `If`**（赋值在它的 `body` 里，⛔ 不是 walk 到），
    #     并要求那个 `if` 的条件**提到手边这份数**。
    #     🔴 这个漏是变异抓出来的，⛔ 不是我想到的 —— 那正是变异的价值。
    direct = {}
    for node in ast.walk(_TREE):
        if not isinstance(node, ast.If):
            continue
        for stmt in list(node.body) + list(node.orelse):
            if stmt in assigns:
                direct[id(stmt)] = node.test
    assert len(direct) == len(assigns), (
        f"{len(assigns)} 处挂 kpis，只有 {len(direct)} 处有**直接**条件 —— "
        "无条件挂上会让正文和机器可读侧说两件事")
    for aid, test in direct.items():
        names = {n.id for n in ast.walk(test) if isinstance(n, ast.Name)}
        assert names & {"partial_text", "_facts_in_hand", "_guard_facts"}, (
            f"挂 kpis 的条件里没提到手边这份数（条件用到的名字: {sorted(names)}）"
            " —— 那它管的就不是「这份数能不能用」")


def test_the_numbers_have_exactly_one_source():
    """承重3：那份数只有**一处**来源。

    改之前它在**三处**各取一次 `getattr(tiered_result, "kpis", …)`
    （两处喂正文 + 机器可读侧本该用的第三处）——
    形态 D：同一个东西有几份就会漂，而这里漂的后果是
    「正文里的数」和「KPI 卡里的数」不是同一批。
    """
    assigns = [
        n for n in ast.walk(_TREE)
        if isinstance(n, ast.Assign)
        for t in n.targets
        if isinstance(t, ast.Name) and t.id == "_facts_in_hand"
    ]
    assert len(assigns) == 1, (
        f"`_facts_in_hand` 被赋值 {len(assigns)} 次（应为 1）—— 它又分叉了")


def test_the_empty_handed_refusals_stay_empty_handed():
    """🔴 阴性1：⛔ 6 处**不许全挂**。

    手边没有数的那四处放一个空数组，会让「没有数」和「有数没给」
    在下次读数里长得一模一样（形态 A¹⁰）。
    ▎这一条守的是**不能加的那一半** —— 它和承重2 不是同一件事：
      承重2 说「至少这两处要挂」，这一条说「⛔ 另外四处不许挂」。
    """
    assert len(_kpi_subscript_assigns()) < _EXPECTED_SITES, (
        "每一档拒答都挂上了 kpis —— 那等于把「手边真的没有数」"
        "也说成「有数没给」，下次读数就分不开了")


def test_the_two_that_ship_are_the_two_that_have():
    """阴性2：挂数的那两处，就是表里标着「有」的那两档。

    ⛔ 判据落在**来源标记**上，而不是「哪一行代码」——
    行号会变，标记不会。
    """
    markers = {_source_marker(d) for d in _clarification_dicts()}
    assert {"guard_decline", "contract_rejected"} <= markers, (
        f"有数的那两档不在来源标记里: {sorted(m for m in markers if m)}")
    assert {"store_ambiguous", "planner_clarification",
            "dimension_mismatch", "no_usable_result"} <= markers, (
        f"没数的那四档不在来源标记里: {sorted(m for m in markers if m)}")
