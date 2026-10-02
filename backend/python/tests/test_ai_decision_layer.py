"""三层识别 (精确表 → 读概率决策 → 按钮澄清) 的编排闸。

每条正向断言都配一条能让它红的对照: 不配对照的「没调 LLM」「没短路」都可能是恒真式。
"""
import asyncio
import math
from dataclasses import replace

import pytest

from ai import registry
from ai.config import AIConfig
from ai.dto import CandidateIntentDto, MatchMethod
from ai.matcher.decision import (
    LETTERS,
    DecisionMatcher,
    build_choice_messages,
    letter_probs,
    parse_shortlist,
)
from ai.orchestrator import Orchestrator

ROWS = [
    {"id": "1", "intent_code": "CLOCK_OUT", "intent_name": "下班打卡", "description": "下班签退", "keywords": '["签退","下班"]',
     "intent_category": "HR", "is_active": True},
    {"id": "2", "intent_code": "RETURN_ORDER_CREATE", "intent_name": "创建退货单", "description": "客户退货", "keywords": None,
     "intent_category": "SALES", "is_active": True},
    {"id": "3", "intent_code": "MATERIAL_BATCH_QUERY", "intent_name": "原料批次查询", "description": "查原料库存",
     "keywords": '["库存"]', "intent_category": "MATERIAL", "is_active": True},
]
CFG = AIConfig(decision_business_types="FACTORY", decision_auto_threshold=0.7)


class FakeSemantic:
    def __init__(self, cands):
        self.cands, self.calls = cands, 0

    async def match(self, query, factoryId=None, businessType=None):
        self.calls += 1
        return [CandidateIntentDto(intentCode=c, confidence=p, matchMethod=MatchMethod.SEMANTIC) for c, p in self.cands]


class FakeDecision:
    def __init__(self, short, probs):
        self.short, self.probs = short, probs
        self.short_calls = self.choose_calls = 0
        self.seen_options = None

    async def shortlist(self, query, role, visible):
        self.short_calls += 1
        return self.short

    async def choose(self, query, role, options):
        self.choose_calls += 1
        self.seen_options = [o["intent_code"] for o in options]
        return self.probs


class FakeLegacyLlm:
    def __init__(self):
        self.calls = 0

    async def match(self, *a, **k):
        self.calls += 1
        return [CandidateIntentDto(intentCode="MATERIAL_BATCH_QUERY", confidence=0.9, matchMethod=MatchMethod.LLM)]


class EmptyClassifier:
    async def match(self, *a, **k):
        return []


def _orch(decision, sem=None, legacy_sem=None, cfg=CFG):
    return Orchestrator(
        semantic_matcher=legacy_sem or FakeSemantic([]), classifier_matcher=EmptyClassifier(),
        llm_matcher=FakeLegacyLlm(), decision_matcher=decision,
        candidate_retriever=sem or FakeSemantic([]), config=cfg,
    )


def _run(orch, query="嗯 签个退", business="FACTORY"):
    return asyncio.run(orch.match(query=query, factoryId="F006", businessType=business, userId="u", role="operator",
                                  visible_intents=ROWS, history=[], min_confidence=0.7, enable_llm=True))


@pytest.fixture(autouse=True)
def _empty_registry():
    registry.set_registry(registry.RegistrySnapshot())
    yield
    registry.set_registry(registry.RegistrySnapshot())


# ---------------- ① 精确表 ----------------

def test_registry_hit_answers_without_any_llm_call():
    registry.set_registry(registry.build_snapshot(
        [{"factory_id": "F006", "business_type": "FACTORY", "normalized_text": "嗯签个退", "intent_code": "CLOCK_OUT"}], []))
    dec = FakeDecision(["RETURN_ORDER_CREATE"], {"RETURN_ORDER_CREATE": 0.99})
    res = _run(_orch(dec))
    assert res.bestMatch.intentCode == "CLOCK_OUT"
    assert res.matchMethod == MatchMethod.EXACT and res.requiresConfirmation is False
    assert res.timingMs.get("registryHit") == 1
    assert dec.short_calls == 0 and dec.choose_calls == 0


def test_registry_is_scoped_by_factory():
    """对照: 同一句话、别的工厂的条目不命中 —— 证明上一条命中来自 (工厂, 业态) 键, 不是任何条目都中。"""
    registry.set_registry(registry.build_snapshot(
        [{"factory_id": "F999", "business_type": "FACTORY", "normalized_text": "嗯签个退", "intent_code": "CLOCK_OUT"}], []))
    dec = FakeDecision(["CLOCK_OUT"], {"CLOCK_OUT": 0.95})
    res = _run(_orch(dec))
    assert res.matchMethod == MatchMethod.LLM and dec.choose_calls == 1


def test_registry_entry_for_an_invisible_intent_is_ignored():
    registry.set_registry(registry.build_snapshot(
        [{"factory_id": "F006", "business_type": "FACTORY", "normalized_text": "嗯签个退", "intent_code": "GONE_INTENT"}], []))
    dec = FakeDecision(["CLOCK_OUT"], {"CLOCK_OUT": 0.95})
    res = _run(_orch(dec))
    assert res.bestMatch.intentCode == "CLOCK_OUT" and res.matchMethod == MatchMethod.LLM


def test_registry_is_whole_sentence_not_substring():
    registry.set_registry(registry.build_snapshot(
        [{"factory_id": "F006", "business_type": "FACTORY", "normalized_text": "签退", "intent_code": "CLOCK_OUT"}], []))
    dec = FakeDecision(["RETURN_ORDER_CREATE"], {"RETURN_ORDER_CREATE": 0.9})
    res = _run(_orch(dec), query="客户签退货单")  # 包含「签退」但不是整句
    assert res.matchMethod == MatchMethod.LLM


# ---------------- ② 决策 + ③ 澄清 ----------------

def test_confident_decision_executes_without_confirmation():
    dec = FakeDecision(["CLOCK_OUT", "RETURN_ORDER_CREATE"], {"CLOCK_OUT": 0.92, "RETURN_ORDER_CREATE": 0.08})
    res = _run(_orch(dec))
    assert res.bestMatch.intentCode == "CLOCK_OUT"
    assert res.requiresConfirmation is False
    assert res.timingMs.get("decisionLayer") == 1


def test_unsure_decision_asks_with_at_least_two_buttons():
    dec = FakeDecision(["CLOCK_OUT", "RETURN_ORDER_CREATE"], {"CLOCK_OUT": 0.55, "RETURN_ORDER_CREATE": 0.45})
    res = _run(_orch(dec))
    assert res.requiresConfirmation is True
    assert [c.intentCode for c in res.topCandidates][:2] == ["CLOCK_OUT", "RETURN_ORDER_CREATE"]


def test_threshold_is_what_decides_confirmation():
    """对照: 同一个 0.55, 门槛降到 0.5 就不再要确认 —— 证明确认来自门槛而不是别的条件。"""
    dec = FakeDecision(["CLOCK_OUT", "RETURN_ORDER_CREATE"], {"CLOCK_OUT": 0.55, "RETURN_ORDER_CREATE": 0.45})
    res = _run(_orch(dec, cfg=replace(CFG, decision_auto_threshold=0.5)))
    assert res.requiresConfirmation is False


def test_a_strong_vector_hit_never_decides_alone():
    """向量只给候选: 向量 0.99 指向退货单, 决策选打卡 —— 结果必须是决策的。"""
    sem = FakeSemantic([("RETURN_ORDER_CREATE", 0.99)])
    dec = FakeDecision(None, {"CLOCK_OUT": 0.9, "RETURN_ORDER_CREATE": 0.1})
    res = _run(_orch(dec, sem=sem))
    assert res.bestMatch.intentCode == "CLOCK_OUT"
    assert dec.seen_options == ["RETURN_ORDER_CREATE"]  # 粗选失败时向量候选仍然进了选项


def test_shortlist_first_then_vector_fill_and_invisible_codes_dropped():
    sem = FakeSemantic([("MATERIAL_BATCH_QUERY", 0.5), ("NOT_VISIBLE", 0.4), ("CLOCK_OUT", 0.3)])
    dec = FakeDecision(["CLOCK_OUT"], {"CLOCK_OUT": 0.9})
    _run(_orch(dec, sem=sem))
    assert dec.seen_options == ["CLOCK_OUT", "MATERIAL_BATCH_QUERY"]


def test_no_probabilities_falls_back_to_legacy_and_says_so():
    dec = FakeDecision(["CLOCK_OUT"], None)
    orch = _orch(dec)
    res = _run(orch)
    assert orch.llm_matcher.calls == 1
    assert res.timingMs.get("decisionFallback") == 1


def test_other_business_types_keep_the_legacy_path():
    dec = FakeDecision(["CLOCK_OUT"], {"CLOCK_OUT": 0.9})
    orch = _orch(dec)
    res = _run(orch, business="RESTAURANT")
    assert dec.short_calls == 0 and dec.choose_calls == 0
    assert "decisionFallback" not in (res.timingMs or {})


def test_disabled_by_default():
    assert AIConfig().decision_enabled_for("FACTORY") is False
    assert CFG.decision_enabled_for("factory") is True


# ---------------- 纯函数 ----------------

def _body(tokens):
    return {"choices": [{"logprobs": {"content": [{"token": tokens[0][0],
                                                   "top_logprobs": [{"token": t, "logprob": math.log(p)} for t, p in tokens]}]}}]}


def test_letter_probs_reads_letters_even_when_first_token_is_a_newline():
    probs = letter_probs(_body([("\n", 0.3), ("B", 0.5), ("A", 0.2)]), 3)
    assert probs == pytest.approx({"B": 0.5 / 0.7, "A": 0.2 / 0.7})


def test_letter_probs_ignores_letters_outside_the_options_and_returns_none_without_letters():
    assert letter_probs(_body([("D", 0.9), ("A", 0.1)]), 3) == {"A": 1.0}
    assert letter_probs(_body([("\n", 1.0)]), 3) is None
    assert letter_probs({"choices": [{"message": {"content": "B"}}]}, 3) is None  # 200 但没有 logprobs


def test_parse_shortlist_keeps_only_visible_codes_in_order():
    assert parse_shortlist('{"candidates": ["X", "CLOCK_OUT", "CLOCK_OUT", "RETURN_ORDER_CREATE"]}',
                           {"CLOCK_OUT", "RETURN_ORDER_CREATE"}) == ["CLOCK_OUT", "RETURN_ORDER_CREATE"]
    assert parse_shortlist("```json\n{\"candidates\": []}\n```", {"A"}) == []
    assert parse_shortlist("不是 JSON", {"A"}) is None


def test_choice_prompt_letters_and_partial_prefix():
    msgs = build_choice_messages("签个退", "operator", ROWS)
    assert msgs[-1] == {"role": "assistant", "content": "答案：", "partial": True}
    user = msgs[1]["content"]
    for L, r in zip(LETTERS, ROWS):
        assert f"{L}. {r['intent_code']}：" in user
    assert "食品加工厂操作工" in user


def test_choose_with_single_option_skips_the_call():
    out = asyncio.run(DecisionMatcher().choose("x", "operator", ROWS[:1]))
    assert out == {"CLOCK_OUT": 1.0}


# ---------------- 归一化 (与离线评测 N2 一致) ----------------

def test_alias_in_two_entity_types_resolves_to_one_tag_regardless_of_order():
    """「酱牛肉」既是物料又是产品: 结果必须与输入顺序 (以及进程的哈希种子) 无关。"""
    rows = [{"factory_id": "F006", "entity_type": "PRODUCT", "alias": "酱牛肉"},
            {"factory_id": "F006", "entity_type": "MATERIAL", "alias": "酱牛肉"}]
    a = registry.build_snapshot([], rows).lexicon["F006"]
    b = registry.build_snapshot([], list(reversed(rows))).lexicon["F006"]
    assert a == b == [("酱牛肉", "<物料>")]
    assert registry.normalize("报个酱牛肉", a) == "报个<物料>"


def test_normalization_is_identical_across_hash_seeds():
    """真正的病是跨进程: 用不同 PYTHONHASHSEED 起子进程各算一次, 键必须相同。"""
    import os
    import subprocess
    import sys
    code = (
        "from ai import registry as R;"
        "rows=[{'factory_id':'F','entity_type':t,'alias':a} for t in ('PRODUCT','MATERIAL','PROCESS') "
        "for a in ('酱牛肉','牛肉','卤牛肉','包装')];"
        "print(R.normalize('报个酱牛肉包装', R.build_snapshot([], rows).lexicon['F']))"
    )
    outs = set()
    for seed in ("1", "2", "3", "4"):
        env = {**os.environ, "PYTHONHASHSEED": seed}
        outs.add(subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, env=env,
                                cwd=os.path.dirname(os.path.dirname(os.path.abspath(__file__)))).stdout.strip())
    assert len(outs) == 1 and "" not in outs, outs

def test_normalize_matches_offline_n2():
    lex = registry.build_snapshot([], [
        {"factory_id": "F006", "entity_type": "MATERIAL", "alias": "淀粉"},
        {"factory_id": "F006", "entity_type": "MATERIAL", "alias": "玉米淀粉"},
    ]).lexicon["F006"]
    assert registry.normalize("玉米淀粉 二十六袋！", lex) == "<物料><量>"   # 长名优先
    assert registry.normalize("淀粉还剩多少", lex) == "<物料>还剩多少"
    assert registry.normalize("B20260911 那批明天发", lex) == "<号>那批<日期>发"
    assert registry.normalize("PO-12345 到了没", lex) == "<号>到了没"
