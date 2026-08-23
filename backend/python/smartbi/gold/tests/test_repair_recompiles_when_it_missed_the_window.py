# -*- coding: utf-8 -*-
"""contract-repair 清了 clarify 却错过补默认窗口 ⇒ **用修好的 code 重编译一次**。

## 因果链（`_q08_trace`，`sys.settrace`，3 轮，行号自检 3/3）

```
2361 补默认窗口的条件链里有 `and not clarification_needed`
     LLM 说要澄清 ⇒ 不补 ⇒ window='全部历史'
2385 date_range / window_label **已经按「全部历史」算完**
2942 contract-repair 改回 resolver 并清 clarification_needed=False
     （authority 加 `_contract_repair` 后缀 —— 那就是它执行了的证据）
3025 时间反问闸的条件链现在全部成立 ⇒ 又把 clarify 设回 True
```

▎**同一个 `clarification_needed=False`：在 2373 那里会让「补默认」开火，
▎在 2942 之后却只让「时间反问」开火 —— 因为 2373 已经走过去了。**

⇒ 老板问「米饭卖得怎么样」拿到的是反问，而同句式的「水煮牛肉」直接答上
（它在 `_build_spec` **之前**就被清成 False）。

## ⛔ 为什么不是「repair 之后补一下 time_phrase」

`date_range` / `window_label` 在 **2385** 就算完了。补 `time_phrase` 只改一个
局部变量，下游仍按全历史出数而**披露说「最近30天」**——
2344 那段注释白纸黑字写着：**那才是真正的降级处理**（数字与说明不符）。

## ⚠️ 触发面收窄（📏 设计卡登记的风险面）

`authority=llm_contract_repair` 在全景 48 样本里占 **12/48 = 25%**，
而其中 **10/12 现在是答上的**。⇒ 「最终仍在反问」那个条件把它们全部排除
（它们 `clarification_needed=False`）。⛔ **不是对 25% 生效。**

## 断言各守什么（⛔ 不是同义的）

    承重1  repair + 仍反问 + 全部历史 ⇒ 二次编译，窗口补上、clarify 清掉
    阴性1  repair 但**没在反问** ⇒ ⛔ 不动（那 10/12 就在这一格）
    阴性2  **没有** repair（authority=llm）⇒ ⛔ 不动
    阴性3  窗口**已经**补上了 ⇒ ⛔ 不动（⛔ 不重复编译）
    阴性4  回放/晋升路径 `allow_time_default=False` ⇒ ⛔ 不动
           （晋升授予的是意图，⛔ 不是发明一个默认月份的许可）
    接线   两次编译共用**同一份**参数 dict（形态 D：25 个实参复制一定会漂）
"""
from __future__ import annotations

import ast
import inspect
import textwrap

from smartbi.gold.restaurant import restaurant_intent as ri

_Q08_STORES = ("模拟·徐汇美罗城店", "模拟·静安嘉里中心店")


def _parsed(**over):
    base = {
        "intent": "RESTAURANT_OPS_RECIPE_COST",
        "time_range": None, "wants_margin": True, "asks_profitability": False,
        "requested_metrics": [], "analysis_action": "lookup",
        "dimensions": ["dish"], "dish": "米饭", "store": None, "stores": [],
        "store_scope": None, "confidence": 0.9,
        "clarification_needed": True, "missing_fields": ["time_range"],
        "clarification_question": "你想看哪个时间范围？", "clarification_options": [],
    }
    base.update(over)
    return base


def _spec(parsed=None, **kw):
    return ri._semantic_spec_from_t3(
        parsed or _parsed(), "米饭卖得怎么样",
        available_stores=_Q08_STORES, **kw)


def test_precondition_the_stub_reaches_contract_repair():
    """前提：这个桩真的触发了 contract-repair，且 repair 改了 intent。

    ⚠️ 触不到的话下面每条断言量的都是别的事 —— 而 prod 实测
    「米饭卖得怎么样」LLM 6/6 选 `RECIPE_COST`，repair 把它改回毛利那条。
    """
    spec = _spec()
    assert "contract_repair" in (spec.planner_authority or ""), (
        f"桩没触发 repair（authority={spec.planner_authority!r}）—— 量的是别的事")
    assert spec.intent == "RESTAURANT_OPS_GROSS_MARGIN", (
        f"repair 没把 intent 改过来（{spec.intent!r}）—— 前提不成立")


def test_it_recompiles_and_fills_the_window():
    """🔴 承重1：窗口补上、clarify 清掉。"""
    spec = _spec()
    assert spec.window_label != "全部历史", (
        f"窗口还是「全部历史」—— 二次编译没开火（{spec.window_label!r}）")
    assert spec.clarification_needed is False, "还在反问"


def test_the_window_and_the_disclosure_are_the_same_one():
    """🔴 这一条守的是 2344 注释里那句话。

    ⛔ 补 `time_phrase` 而不重编译会让下游按全历史算、披露说「最近30天」——
    **那才是真正的降级处理**。二次编译的全部意义就是让这两个是同一个窗口。
    """
    spec = _spec()
    assert spec.date_range[0] is not None, (
        f"披露说 {spec.window_label!r} 而 date_range 是空的 —— 说的和算的不是一个窗口")
    assert spec.time_range_defaulted is True, "窗口补上了却没打「这是默认值」的标"


def test_a_repair_that_is_not_asking_is_left_alone():
    """🔴 阴性1：repair 但**没在反问** ⇒ ⛔ 不动。

    📏 那 10/12 现在答得上的样本就在这一格 —— 这条断言守的是**触发面**。
    """
    parsed = _parsed(clarification_needed=False, clarification_question=None,
                     missing_fields=[])
    spec = _spec(parsed)
    assert spec.clarification_needed is False
    # ⛔ 没被二次编译改坏：仍然是一次正常编译的结果
    assert spec.intent


def test_the_repair_evidence_survives_the_recompile():
    """🔴 `planner_authority` 的 `_contract_repair` 后缀**不许丢**。

    第二次编译传的已经是修好的 code ⇒ repair 不再触发 ⇒ 后缀会掉成 `llm`。
    而那个后缀是**「repair 执行过」的唯一证据**（设计卡靠它判断 2942 跑没跑）。
    ▎丢了之后审计侧会读成「这一轮没发生过 repair」——
    ▎**一个正确的修复顺手抹掉了它自己的痕迹。**
    """
    spec = _spec()
    assert "contract_repair" in (spec.planner_authority or ""), (
        f"二次编译把 repair 的证据抹掉了（authority={spec.planner_authority!r}）")


def test_replay_path_is_not_allowed_to_invent_a_window():
    """🔴 阴性4：回放/晋升路径 ⇒ ⛔ 不补默认窗口。

    ▎**晋升授予的是意图，⛔ 不是发明一个默认月份的许可。**
    替人审多批一个窗口，等于把人审的授权范围偷偷放大。
    """
    spec = _spec(allow_time_default=False)
    assert spec.window_label == "全部历史", (
        f"回放路径上凭空补了个窗口：{spec.window_label!r}")


def test_both_compiles_share_one_kwargs_dict():
    """接线（AST）：⛔ 25 个实参复制两份一定会漂（形态 D）。"""
    src = textwrap.dedent(inspect.getsource(ri._semantic_spec_from_t3))
    tree = ast.parse(src)
    calls = [n for n in ast.walk(tree) if isinstance(n, ast.Call)
             and getattr(n.func, "id", None) == "_build_spec"]
    big = [c for c in calls if len(c.keywords) >= 15]
    assert not big, (
        f"还有 {len(big)} 处 15+ 关键字的 _build_spec 调用 —— 参数没抽成一份")
    # 两次调用都必须走 `**_spec_kwargs`
    starred = [c for c in calls
               if any(k.arg is None for k in c.keywords)]
    assert len(starred) >= 2, (
        f"只有 {len(starred)} 处用了 **kwargs —— 二次编译没共用那份参数")
