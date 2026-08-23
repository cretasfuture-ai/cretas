# -*- coding: utf-8 -*-
"""老板要的**指标**没给出数时，说一句 —— ⛔ 不要把他的原问题当建议抛回去。

## prod 实测（`_metric_not_served`，MOCK_REST，4 轮逐轮一致）

「最近30天外卖和堂食的**毛利**分别是多少」→ `RESTAURANT_OPS_CHANNEL_MIX`：

    requested_metrics = ['gross_margin']   wants_margin = True
    系统**知道**他要毛利 = 4/4
    最终**没给毛利的数** = 4/4          （阳性对照：能给毛利的问句 2/2 真给了）

而老板拿到的是营收/单量表，全文 1031 字里「毛利」只出现 1 次、在第 942 字：

    往下可以拆开看：堂食和外卖各自的每单平均消费、**毛利分别是多少**。

▎**产品把他刚问的那个问题，当成「下一步可以问什么」推荐回给了他。**

## 🔴 既有的契约闸为什么放行

`answer_contract._request_coverage_present` 已经在遍历 `requested_metrics`，
判据却是 `_contains_any(answer_text, tokens)` —— **「提到了这个词」**。
上面那句**建议**里有「毛利」两个字，闸就过了。
▎同一个子串陷阱，这次在**生产代码**里：**「提到」≠「给了数」**。

⛔ 本刀**不动那道闸**（它管着全部指标与全部答案；闸红 ⇒ 老板拿到的是拒答，
   而不是「营收表 + 一句说明」）。这里只做**投影**。

## 断言各守什么（⛔ 不是同义的）

    承重   prod 那份真实答案 ⇒ 说一句「这条路给不了毛利的数」
    阴性1  毛利在**表头** ⇒ 闭嘴
    阴性2  毛利**行内给了数** ⇒ 闭嘴
    阴性3  只问营收、营收给了 ⇒ 闭嘴
    阴性4  问句里根本没有指标词 ⇒ 闭嘴
    阴性5  ⛔ 措辞取**他自己问句里的那个词**（问「毛利率」就说「毛利率」）
    接线   token 复用 `answer_contract._REQUEST_TEXT_TOKENS`，⛔ 不造第二份
"""
from __future__ import annotations

import ast
import inspect
import textwrap

from dataclasses import replace

from smartbi.gold.restaurant import restaurant_intent as ri
from smartbi.gold.restaurant import restaurant_intent_service as svc
from smartbi.gold.restaurant.restaurant_intent_service import _metric_gap_note

#: 逐字照抄 prod 那份答案的骨架（`_margin_by_channel_full` 贴出来的）。
_MG_PROD_BAD = """**堂食 vs 外卖（2026-07-21 至 2026-08-19）：**

| 渠道 | 营收 | 营收占比 | 单量 | 单量占比 |
| --- | ---: | ---: | ---: | ---: |
| 堂食 | ¥13,386,440 | 63.6% | 36,381 | 62.2% |
| 外卖 | ¥5,661,016 | 26.9% | 16,250 | 27.8% |

往下可以拆开看：堂食和外卖各自的每单平均消费、毛利分别是多少。"""

_MG_TABLE_HAS_MARGIN = """**各门店毛利（最近30天）：**

| 门店 | 营收 | 毛利 | 毛利率 |
| --- | ---: | ---: | ---: |
| 模拟·徐汇美罗城店 | ¥2,222,669 | ¥800,000 | 36.0% |"""

_MG_INLINE_MARGIN = "堂食毛利 ¥5,012,345（37.4%），外卖毛利 ¥1,880,000。"
_MG_Q_MARGIN = "最近30天外卖和堂食的毛利分别是多少"
_MG_Q_RATE = "最近30天各渠道的毛利率是多少"


def _spec(query):
    """⚠️ `requested_metrics` 是 `_build_spec` 从**问句**推导的 ——
    传空也会被填上（实测）。所以「没有指标」那一格只能靠**换问句**造，
    ⛔ 不能靠传 `llm_requested_metrics=()`。
    （第一版我就是那么写的，阴性 4 当场红，而红的是**桩**不是判据。）
    """
    spec = ri._build_spec(
        "RESTAURANT_OPS_CHANNEL_MIX", query, confidence=0.9, tier="llm",
        planner_authority="llm", llm_semantics_authoritative=True,
        clarification_needed=False, store_options=("模拟·徐汇美罗城店",))
    return replace(spec, resolver_query_seed=query)


def test_precondition_the_system_knows_he_wants_margin():
    """前提：`requested_metrics` 真的含 `gross_margin`。

    不含的话下面每条断言量的都是别的事 —— 而 prod 实测它 4/4 都含。
    """
    assert "gross_margin" in _spec(_MG_Q_MARGIN).requested_metrics


def test_it_says_the_metric_is_not_available(  ):
    """🔴 承重：prod 那份真实答案 ⇒ 说一句。"""
    note = _metric_gap_note(_spec(_MG_Q_MARGIN), _MG_PROD_BAD)
    assert note, "系统知道他要毛利、也没给毛利的数，却一个字不说"
    assert "毛利" in note


def test_it_does_not_promise_a_way_to_get_it():
    """⛔ 不给假出路 —— 我不知道哪条路能给它，就不承诺。

    （今天刚栽过一次「建议他做他已经做过的事」。）
    """
    note = _metric_gap_note(_spec(_MG_Q_MARGIN), _MG_PROD_BAD)
    for fake in ("换个问法", "问「", "你可以问"):
        assert fake not in note, f"给了一条我担保不了的出路：{note!r}"


def test_it_does_not_invent_a_reason():
    """⛔ 不编机制 —— 只说能力，不说「因为渠道表没有成本」。"""
    note = _metric_gap_note(_spec(_MG_Q_MARGIN), _MG_PROD_BAD)
    for invented in ("因为", "不在同一张表", "没有成本"):
        assert invented not in note, f"编了一个机制：{note!r}"


def test_margin_in_the_table_header_says_nothing():
    """阴性1。"""
    assert _metric_gap_note(_spec(_MG_Q_MARGIN), _MG_TABLE_HAS_MARGIN) == ""


def test_margin_given_inline_says_nothing():
    """阴性2 —— ⚠️ 不是所有答案都用表格。"""
    assert _metric_gap_note(_spec(_MG_Q_MARGIN), _MG_INLINE_MARGIN) == ""


def test_asking_only_revenue_says_nothing():
    """阴性3：他只问营收，而营收就在表头里。"""
    spec = _spec("最近30天外卖和堂食的营收分别是多少")
    assert "revenue" in spec.requested_metrics, "前提：营收没进 requested_metrics"
    assert _metric_gap_note(spec, _MG_PROD_BAD) == ""


def test_a_question_without_any_metric_word_says_nothing():
    """阴性4：问句里根本没有指标词 ⇒ 闭嘴。

    ⚠️ 这一格只能靠**换问句**造 —— `_build_spec` 会从问句推导 metrics，
    传 `llm_requested_metrics=()` 是造不出来的（见 `_spec` 的说明）。
    """
    spec = _spec("最近30天外卖和堂食各占多少")
    assert not [m for m in spec.requested_metrics
                if m in ("gross_margin", "recipe_cost", "wastage")], (
        f"前提：这句被推导出了指标 {spec.requested_metrics}，换一句")
    assert _metric_gap_note(spec, _MG_PROD_BAD) == ""


def test_a_metric_without_tokens_is_left_alone():
    """🔴 阴性4b：**判不了的指标一律不动**（token 表里没有它）。

    这条是**变异抓出来的**：M6（把「没有 token 就跳过」改成「没有就硬用毛利」）
    第一版**一条都不红** —— 查清是**变异没改变行为**（形态 C″ 第 2 类）：
    我原来的阴性 4 用的问句推导出的 `requested_metrics` 全都有 token，
    那一支根本没被执行。

    ⚠️ 而它在生产上**真的可达**：`actual_receive` / `platform_fee` /
    `tax_amount` 都在模型可选的指标里，却**不在** `_REQUEST_TEXT_TOKENS`
    的 15 项里（那两套 key 空间不重合 —— 又一处既有的形态 D，本刀不动它）。
    ⇒ 判不了就闭嘴，⛔ 不拿别的指标的词去猜。
    """
    from smartbi.gold.restaurant.answer_contract import _REQUEST_TEXT_TOKENS
    spec = replace(
        ri._build_spec(
            "RESTAURANT_OPS_CHANNEL_MIX", "最近30天外卖和堂食各占多少",
            confidence=0.9, tier="llm", planner_authority="llm",
            llm_semantics_authoritative=True,
            llm_requested_metrics=("actual_receive",),
            clarification_needed=False, store_options=("模拟·徐汇美罗城店",)),
        resolver_query_seed="最近30天外卖和堂食各占多少")
    assert spec.requested_metrics == ("actual_receive",), (
        f"前提：这个指标没进 spec（{spec.requested_metrics}）")
    assert not _REQUEST_TEXT_TOKENS.get("actual_receive"), (
        "前提：token 表里居然有它了 —— 换一个没有 token 的指标")
    assert _metric_gap_note(spec, _MG_PROD_BAD) == "", (
        "对一个判不了的指标硬说了一句 —— 那是拿别的指标的词去猜")


def test_the_label_comes_from_his_own_wording():
    """🔴 阴性5：他问「毛利率」就说「毛利率」，⛔ 不硬编码一个词。

    ⚠️ 也⛔ 不用 `METRICS.label` —— 实测 `METRICS` 里**根本没有**
    `gross_margin` 这个键（14 项，与 `requested_metrics` 的 key 空间
    不是同一套）。那是既有的形态 D，本刀不动它，但也不能依赖它。
    """
    note = _metric_gap_note(_spec(_MG_Q_RATE), _MG_PROD_BAD)
    assert "毛利率" in note, f"没用他自己的说法：{note!r}"


def test_it_reuses_the_contract_token_table():
    """接线（AST）：⛔ 不造第二份指标词表（形态 D）。"""
    src = inspect.getsource(_metric_gap_note)
    tree = ast.parse(textwrap.dedent(src))
    names = {a.name for n in ast.walk(tree)
             if isinstance(n, ast.ImportFrom) for a in n.names}
    assert "_REQUEST_TEXT_TOKENS" in names, (
        "没有复用 answer_contract 那份 token 表 —— 两份词表一定会漂")


def test_the_answer_path_projects_it():
    """接线：兜底之外的**正常答案**那条路真的拼了这句。"""
    src = inspect.getsource(svc.tiered_answer)
    tree = ast.parse(textwrap.dedent(src))
    called = {getattr(n.func, "id", None) for n in ast.walk(tree)
              if isinstance(n, ast.Call)}
    assert "_metric_gap_note" in called, "算出来了但没投影到用户侧（形态 B）"
