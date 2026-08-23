# -*- coding: utf-8 -*-
"""一条门店名录正则，⛔ 不许覆盖模型已经给出的**完整**计划。

## 被守的行为（⛔ 不是「哪几家店」这四个字）

`_is_explicit_store_directory_query` 是一条 `re.fullmatch`，它**只看这一轮的字**。
老板上一轮问「有没有店是亏钱的」，这一轮说「哪几家店」——
那条正则看不见上一轮，于是把一句正确的追问短路成了花名册。

📏 prod 实测（MOCK_REST，`smartbi.scripts._veto_fix_probe`，⛔ 评测题库非真实流量）
   模型对「哪几家店」的原始输出（逐字，就是下面的 `LOSS_FOLLOWUP_PLAN`）:

       intent=RESTAURANT_OPS_STORE_MARGIN  dimensions=["store"]
       requested_metrics=["gross_profit"]  wants_margin=true
       asks_profitability=true            aggregation="bottom"
       time_range=最近30天                 clarification_needed=**false**

   —— **完全正确**。而老板拿到的是 154 字的
   「当前账号共有 **10 家门店** 1. 模拟·宝山大场社区店 …」。

   同一轮里 4 个**标准名录问句**（「我们一共有几家门店」「有哪些门店」
   「门店名单」「现在有几家店」）的模型输出是
   `intent=RESTAURANT_OPS_STORE_DIRECTORY / requested_metrics=[] /
   clarification_needed=false / confidence=0.99`，`authority=llm`
   —— ▎**模型自己就答对了，短路与否结果逐字相同（同一个 md5 84a5fff5e9）。**

⇒ 那条正则的立身之本（「模型把明确的名录问题变成无关的指标反问」）
  只在**模型自己说不准**时才成立。判据于是收窄成那一个条件。

## 每条正向断言都配一条能让它红的对照

    正向  完整计划 + 名录措辞 → 槽位**一个都不许**被清掉
    对照① 模型说不准(`clarification_needed=True`) → 正则**照旧**接管（立身之本）
    对照② 纯名录问句（模型自己给 STORE_DIRECTORY）→ 逐字保持原行为
    对照③ 措辞不匹配那条正则 → 与本改动无关，逐字保持原行为
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

#: prod 实测抓到的那份模型输出（逐字）。⛔ 不手搓「差不多的」——
#: 手搓的那份会漂，而漂的方向恰好是「测不到真缺陷」。
LOSS_FOLLOWUP_PLAN = {
    "intent": "RESTAURANT_OPS_STORE_MARGIN",
    "time_range": {"type": "relative", "unit": "day", "count": 30},
    "wants_margin": True,
    "asks_profitability": True,
    "requested_metrics": ["gross_profit"],
    "analysis_action": "lookup",
    "aggregation": "bottom",
    "dimensions": ["store"],
    "comparison": None,
    "dish": None,
    "store": None,
    "stores": [],
    "store_scope": "all",
    "confidence": 0.9,
    "clarification_needed": False,
    "missing_fields": [],
    "clarification_question": None,
    "clarification_options": [],
}

#: 同一轮实测抓到的**纯名录**问句的模型输出（逐字）。
DIRECTORY_PLAN = {
    "intent": "RESTAURANT_OPS_STORE_DIRECTORY",
    "time_range": None,
    "wants_margin": False,
    "asks_profitability": False,
    "requested_metrics": [],
    "analysis_action": "lookup",
    "aggregation": None,
    "dimensions": ["store"],
    "comparison": None,
    "dish": None,
    "store": None,
    "stores": [],
    "store_scope": "all",
    "confidence": 0.99,
    "clarification_needed": False,
    "missing_fields": [],
    "clarification_question": None,
    "clarification_options": [],
}

FOLLOWUP_QUERY = "哪几家店"
DIRECTORY_QUERY = "我们一共有几家门店"


def _compile(plan, query, **overrides):
    merged = dict(plan)
    merged.update(overrides)
    return ri._semantic_spec_from_t3(merged, query)


# ══ 前提：这两句话**真的**都被那条正则认成名录问句 ═══════════════════════
def test_the_regex_really_matches_both_sentences():
    """⛔ 少了这条，下面所有断言都可能是在测一个**正则根本不匹配**的句子 ——
    那时它们全绿，而它们一件事都没守住。"""
    assert ri._is_explicit_store_directory_query(FOLLOWUP_QUERY)
    assert ri._is_explicit_store_directory_query(DIRECTORY_QUERY)
    # 阴性侧：正则本身是活的，⛔ 不是恒真
    assert not ri._is_explicit_store_directory_query("最近30天营收多少")


# ══ 一 · 承重：完整计划不许被清空 ═══════════════════════════════════════
class TestACompletePlanIsNotOverridden:

    def test_the_metric_slots_survive(self):
        """🔴 承重：模型填好的 3 个槽位一个都不许被那条正则清掉。"""
        spec = _compile(LOSS_FOLLOWUP_PLAN, FOLLOWUP_QUERY)
        assert spec is not None
        assert spec.requested_metrics == ("gross_profit",), (
            f"`requested_metrics` 被清空了：{spec.requested_metrics}")
        assert spec.wants_margin is True, "`wants_margin` 被撤掉了"
        assert spec.asks_profitability is True, "`asks_profitability` 被撤掉了"

    def test_the_intent_is_not_rewritten_to_the_directory(self):
        """🔴 承重：老板问的是「哪几家店在亏钱」，⛔ 不是「一共几家店」。"""
        spec = _compile(LOSS_FOLLOWUP_PLAN, FOLLOWUP_QUERY)
        assert spec.intent != "RESTAURANT_OPS_STORE_DIRECTORY", (
            "正确的门店毛利计划被改写成了花名册")

    def test_the_window_survives(self):
        """模型说了最近 30 天 —— 那条正则原来连 `time_phrase` 一起清。"""
        spec = _compile(LOSS_FOLLOWUP_PLAN, FOLLOWUP_QUERY)
        assert spec.window_label and spec.window_label != "全部历史", (
            f"时间窗被清掉了：{spec.window_label!r}")


# ══ 二 · 对照：能让上面变红的那些输入 ═══════════════════════════════════
class TestTheControls:

    def test_control_1_an_unsure_model_still_gets_overridden(self):
        """🔴 立身之本还在：模型自己说不准时，那条正则**照旧**接管。

        ⛔ 少了这条，我这一刀就等于把整条正则删掉，而它是有用处的 ——
           只是用处在「模型说不准」这一格，不在「模型说准了」那一格。
        """
        spec = _compile(
            LOSS_FOLLOWUP_PLAN, FOLLOWUP_QUERY,
            clarification_needed=True,
            clarification_question="你想看哪个指标？",
            missing_fields=["metric"],
        )
        assert spec.intent == "RESTAURANT_OPS_STORE_DIRECTORY", (
            "模型说不准时正则没有接管 —— 那条正则的立身之本没了")
        assert spec.requested_metrics == ()
        assert spec.clarification_needed is False, (
            "名录问题被接管之后还在反问 —— 正是它当初要消灭的那件事")

    def test_control_2_a_pure_directory_question_is_unchanged(self):
        """纯名录问句：模型自己给 STORE_DIRECTORY ⇒ 与本改动无关，行为不变。"""
        spec = _compile(DIRECTORY_PLAN, DIRECTORY_QUERY)
        assert spec.intent == "RESTAURANT_OPS_STORE_DIRECTORY"
        assert spec.requested_metrics == ()
        assert spec.wants_margin is False
        assert spec.store_scope == "all"
        assert spec.clarification_needed is False

    def test_control_3_a_sentence_the_regex_does_not_match(self):
        """措辞不匹配 ⇒ 名录那条路够不着它。

        ⚠️ ⛔ 这里**不能**断言 `requested_metrics == ('gross_profit',)`:
           这句话带「亏钱」，`allow_explicit_slot_repair` 那条**另外的**路会把
           它改写成 `gross_margin`（实测）。那与本改动无关 ——
           拿它当判据就是把别人的行为算到自己头上。
           ⇒ 只断言**本改动关心的那件事**：槽位没被清空、意图没被换成花名册。
        """
        query = "最近30天哪几家店在亏钱"
        assert not ri._is_explicit_store_directory_query(query)
        spec = _compile(LOSS_FOLLOWUP_PLAN, query)
        assert spec.requested_metrics, "槽位被清空了"
        assert spec.intent != "RESTAURANT_OPS_STORE_DIRECTORY"
        assert spec.wants_margin is True


# ══ 三 · 判据挂在**结构**上，⛔ 不挂在措辞上 ═════════════════════════════
def test_the_narrowing_reads_a_structured_field_not_wording():
    """⛔ 判据必须是 `parsed["clarification_needed"]` 这个结构字段。

    本仓有前科：拿**问句字符串**当类型系统用，模型自撰的措辞每次都不同，
    于是那些比较**恒为假**，闸静默失效。
    """
    import ast
    import inspect

    src = inspect.getsource(ri._semantic_spec_from_t3)
    tree = ast.parse(src.lstrip())
    assign = [
        node for node in ast.walk(tree)
        if isinstance(node, ast.Assign)
        and any(isinstance(t, ast.Name) and t.id == "explicit_store_directory"
                for t in node.targets)
    ]
    assert len(assign) == 1, f"`explicit_store_directory` 被赋值 {len(assign)} 次 —— 两份一定会漂"
    text = ast.dump(assign[0])
    assert "clarification_needed" in text, (
        "收窄条件不见了 —— 那条正则又变回了可以单独否决 LLM 槽位的决策点")
