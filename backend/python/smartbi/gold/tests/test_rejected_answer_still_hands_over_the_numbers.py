# -*- coding: utf-8 -*-
"""产出被接地闸驳回时，**手边的确定数也一起给他** —— ⛔ 不整份扣下。

## prod 实测（`_rejected_but_kpis_in_hand`，钩在拒答现场）

```
kpis=4  unsupported=[]  ← 最近30天营收为什么下滑
kpis=3  unsupported=[]  ← 模拟·徐汇美罗城店最近30天为什么毛利低
kpis=2  unsupported=[]  ← 最近30天哪个时段毛利最高
```

▎**3/3 手边都有数，老板一个都没拿到。**

## ⛔ 那条纪律没有被推翻

```python
# ⚠️ 只在能算出「为什么」时才走这条路: 说不出理由的数字贴在拒答旁边就是顶替。
```

▎它禁止的是「**说不出理由**的数字」。而这一档**说得出理由** ——
▎理由就是「叙述那部分没过『不把推测说成结论』这道闸」。

原来的代码只认**一种**理由来源（`unsupported_requirements` 能力缺口），
实测它全空 ⇒ **明明知道原因，却走了「说不出理由」那条路**。
⇒ 补的是**理由来源**，⛔ 不是放宽纪律。

## 两处写死也一并参数化（既有调用点**逐字不变**）

- `headline`：第一句写死成「**X 算不出来**」，而这一档它是**假话**
  （算出来了，只是没敢发）。2026-08-17 已因同一句假话订正过一次兜底文案，
  ▎**模板本身没动，于是它换个入口继续说。**
- `scope_note`：「能算的是（<限定语>）」写死成毛利专用的 `MARGIN_NOT_PROFIT`，
  对「营收为什么下滑」是答非所问的限定。

## 断言各守什么（⛔ 不是同义的）

    承重1  被驳回 + 手边有数 ⇒ 数字发出去了
    承重2  第一句**不说**「算不出来」（那是假话）
    承重3  限定语**不是**毛利那句
    阴性1  ⛔ 数字只从 `kpis` 来 —— 被驳回的 `answer_text` 一个字都不许出现
    阴性2  真的没算出来（`kpis` 空）⇒ 仍然整份拒答
    阴性3  既有调用点（传两个位参）⇒ 输出**逐字不变**
"""
from __future__ import annotations

from smartbi.gold.restaurant.capability_answer import (
    MARGIN_NOT_PROFIT,
    partial_coverage_answer,
)

_REJ_FACTS = [
    {"label": "营收", "value": "¥21,048,374", "unit": ""},
    {"label": "订单数", "value": "58,472", "unit": " 单"},
    {"label": "客单价", "value": "¥360.0", "unit": ""},
]
_REJ_HEADLINE = (
    "本轮要求的原因分析我这次跑出来了，但里面有几句话没达到我们"
    "「不把推测说成结论」的要求，所以那部分我没发给你。"
)
_REJ_SCOPE = "实测数，不含我没发的那部分推断"
#: 被驳回的那份文本里的一句 —— ⛔ 一个字都不许进最终答案。
_REJ_LLM_SENTENCE = "营收下滑是因为周边写字楼装修导致客流减少"


def test_the_numbers_are_handed_over():
    """承重1。"""
    out = partial_coverage_answer(
        "本轮要求的原因分析", "", _REJ_FACTS,
        headline=_REJ_HEADLINE, scope_note=_REJ_SCOPE)
    assert out, "手边有 3 个数却一个都没给"
    for f in _REJ_FACTS:
        assert f["value"] in out, f"{f['label']} 没发出去：{out!r}"


def test_it_does_not_say_it_could_not_compute():
    """🔴 承重2：第一句不许说「算不出来」—— 那是假话，分析跑出来了。

    2026-08-17 已经因为同一句假话订正过一次兜底文案，而**模板本身没动**，
    于是它换个入口继续说。这条断言钉的就是那个模板。
    """
    out = partial_coverage_answer(
        "本轮要求的原因分析", "", _REJ_FACTS,
        headline=_REJ_HEADLINE, scope_note=_REJ_SCOPE)
    assert "算不出来" not in out.split("\n")[0], (
        f"第一句仍在说「算不出来」：{out.split(chr(10))[0]!r}")
    assert "跑出来了" in out


def test_the_scope_note_is_not_the_margin_one():
    """承重3：限定语由这一档的实情算出来，⛔ 不用毛利专用那句。"""
    out = partial_coverage_answer(
        "本轮要求的原因分析", "", _REJ_FACTS,
        headline=_REJ_HEADLINE, scope_note=_REJ_SCOPE)
    assert _REJ_SCOPE in out
    assert MARGIN_NOT_PROFIT not in out, (
        "对「营收为什么下滑」用了毛利专用的限定语 —— 答非所问")


def test_no_sentence_from_the_rejected_text_survives():
    """🔴 阴性1：数字**只从 kpis 来**。

    被驳回的那份文本有一部分是 LLM 叙述的；原样留用等于把它重新放行，
    而且是在一个专门声明「我不拿别的数据凑」的答案里。
    """
    out = partial_coverage_answer(
        "本轮要求的原因分析", "", _REJ_FACTS,
        headline=_REJ_HEADLINE, scope_note=_REJ_SCOPE)
    assert _REJ_LLM_SENTENCE not in out
    assert "因为" not in out, f"混进了一句因果断言：{out!r}"


def test_nothing_to_give_still_refuses_wholesale():
    """阴性2：真的没算出来 ⇒ `None` ⇒ 调用方走整份拒答。

    ⛔ 空着的「能算的是：」是一句看起来给了东西、实际什么都没有的话。
    """
    assert partial_coverage_answer(
        "本轮要求的原因分析", "", [],
        headline=_REJ_HEADLINE, scope_note=_REJ_SCOPE) is None
    # 值被脱敏 / 占位的也不算数
    masked = [{"label": "营收", "value": "***"}, {"label": "毛利", "value": "暂无"}]
    assert partial_coverage_answer(
        "x", "", masked, headline=_REJ_HEADLINE, scope_note=_REJ_SCOPE) is None


def test_existing_callers_are_byte_for_byte_unchanged():
    """🔴 阴性3：既有调用点**不传**那两个关键字 ⇒ 输出逐字不变。

    ⚠️ 这条守的是「参数化没有顺手改掉别人的行为」——
    本仓记过多次「只改一半调用点」，这次是反过来：**一处都不许被改到**。
    """
    out = partial_coverage_answer("是否赚钱的判断", "缺成本卡", _REJ_FACTS)
    assert out.split("\n")[0] == "**是否赚钱的判断算不出来**：缺成本卡。"
    assert f"能算的是（{MARGIN_NOT_PROFIT}）：" in out


def test_the_order_is_explanation_then_numbers():
    """顺序是硬性的（owner 2026-08-12）：说明在前，数字在后。

    ⛔ 反过来写用户读到的就是「赚了这么多」—— 那是相邻指标顶替换个位置重演。
    """
    out = partial_coverage_answer(
        "本轮要求的原因分析", "", _REJ_FACTS,
        headline=_REJ_HEADLINE, scope_note=_REJ_SCOPE)
    assert out.index("跑出来了") < out.index("21,048,374")


# ══ 🔴 `title` vs `label` —— 这条契约从来就对不上 ═════════════════════
#
# prod 实测（钩在拒答现场把 kpis 的**实际结构**打出来）:
#     {'title': '已覆盖毛利率', 'value': '68.0%', 'rawValue': '0.679…'}
# 而本函数只认 `label` ⇒ 恒被过滤 ⇒ 恒返回 None
# ⇒ §9.2 第二档「部分能做」**可能从来没生效过**。
#
# ▎最阴的一层：它**静默**返回 None ——「没有可给的数」和「结构对不上」
# ▎在返回值上长得一模一样。

_REJ_REAL_KPIS = [
    {"title": "已覆盖毛利率", "value": "68.0%", "rawValue": "0.6797947165916566"},
    {"title": "毛利率排名", "value": "1", "rawValue": "1"},
]


def test_the_real_kpi_shape_from_prod_renders():
    """🔴 承重：prod 上**真实的** kpi 结构（`title`）必须发得出去。

    ⛔ 这条断言用的是**实测抄下来的**结构，不是我构造的 ——
    构造一个 `label` 的样本只会让断言恒绿（那正是它一直没被发现的原因）。
    """
    out = partial_coverage_answer(
        "本轮要求的原因分析", "", _REJ_REAL_KPIS,
        headline=_REJ_HEADLINE, scope_note=_REJ_SCOPE)
    assert out, "prod 上真实的 kpi 结构一条都渲染不出来"
    assert "已覆盖毛利率" in out and "68.0%" in out


def test_label_shape_still_works():
    """阴性：老结构（`label`）不许被改坏。"""
    out = partial_coverage_answer("x", "y", [{"label": "营收", "value": "¥1"}])
    assert "营收：¥1" in out


def test_a_shape_mismatch_is_not_silent(caplog):
    """🔴 「没有可给的数」和「结构对不上」必须分得开。

    ⛔ 静默返回 None 让调用方一直以为是前者 —— 那正是这条契约
    从来没被发现对不上的原因。
    """
    import logging
    with caplog.at_level(logging.WARNING):
        assert partial_coverage_answer(
            "x", "y", [{"名字": "营收", "value": "¥1"}]) is None
    # ⚠️ 用 `getMessage()` —— `r.message` 是未格式化的模板，且我第一版写的
    #    三元表达式优先级是错的（`x in a % b if c else ...`）。
    assert any("渲染不出来" in r.getMessage() for r in caplog.records), (
        "结构对不上时一个字都没说")


def test_truly_empty_stays_quiet(caplog):
    """阴性：真的没数 ⇒ ⛔ 不该报「结构对不上」。

    这一条把告警**收窄到该喊的那一类**：`facts` 本来就是空的时候
    再喊一句「键名可能对不上」就是误报，而误报会让人把它关掉（形态 E）。
    """
    import logging
    with caplog.at_level(logging.WARNING):
        assert partial_coverage_answer("x", "y", []) is None
    assert not any("渲染不出来" in r.getMessage() for r in caplog.records), (
        "真的没数也喊「结构对不上」—— 那是误报")
