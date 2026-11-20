"""旧出口计数与回落告警 (INTENT-DISTILL-20261111): 每个出口命中时计数 +1, 结果与不计数时一模一样。

读数走 prometheus_client 的默认 REGISTRY (就是 /metrics 暴露的那一份), 断言的是增量。
每条「计数 +1」都配「行为不变」(同一输入的 bestMatch / matchMethod / decisionFallback 与原测试口径一致)。
"""
import asyncio
import logging
import math

import pytest
from prometheus_client import REGISTRY

from ai import legacy_exit_metrics as em
from ai import registry
from ai.config import AIConfig
from ai.dto import CandidateIntentDto, MatchMethod
from ai.matcher.decision import DecisionMatcher, RequestPurpose
from ai.orchestrator import Orchestrator
from common import llm_router

ROWS = [
    {"id": "1", "intent_code": "CLOCK_OUT", "intent_name": "下班打卡", "description": "下班签退", "keywords": None,
     "intent_category": "HR", "is_active": True},
    {"id": "2", "intent_code": "RETURN_ORDER_CREATE", "intent_name": "创建退货单", "description": "客户退货",
     "keywords": None, "intent_category": "SALES", "is_active": True, "sensitivity_level": "HIGH"},
    {"id": "3", "intent_code": "MATERIAL_BATCH_QUERY", "intent_name": "原料批次查询", "description": "查原料库存",
     "keywords": None, "intent_category": "MATERIAL", "is_active": True},
]
# 旧链的计数 (Luna → qwen 读 logprobs); 组合退路的计数在 test_ai_decision_fallback_combo.py
CFG = AIConfig(decision_business_types="FACTORY", decision_auto_threshold=0.7, decision_fallback="legacy")
FACTORY = "F-EXIT-TEST"


def _v(name, **labels):
    return REGISTRY.get_sample_value(name, labels) or 0.0


def _exit(exit_name, rw, reason):
    return _v("intent_legacy_exit_total", exit=exit_name, factory=FACTORY, rw=rw, reason=reason)


class FakeSemantic:
    def __init__(self, cands):
        self.cands = cands

    async def match(self, query, factoryId=None, businessType=None):
        return [CandidateIntentDto(intentCode=c, confidence=p, matchMethod=MatchMethod.SEMANTIC) for c, p in self.cands]


class FakeDecision:
    def __init__(self, short, probs, raise_on_choose=None):
        self.short, self.probs, self.raise_on_choose = short, probs, raise_on_choose

    async def request_purpose(self, query, role, history=None):
        return RequestPurpose.CURRENT

    async def shortlist(self, query, role, visible, history=None):
        return self.short

    async def choose(self, query, role, options, history=None, special_options=False):
        if self.raise_on_choose:
            raise self.raise_on_choose
        return None if self.probs is None else dict(self.probs)


class LegacyLlm:
    def __init__(self, code="MATERIAL_BATCH_QUERY"):
        self.code, self.calls = code, 0

    async def match(self, *a, **k):
        self.calls += 1
        return [CandidateIntentDto(intentCode=self.code, confidence=0.9, matchMethod=MatchMethod.LLM)]


class EmptyClassifier:
    async def match(self, *a, **k):
        return []


def _orch(decision, legacy_sem=None, llm=None):
    return Orchestrator(semantic_matcher=legacy_sem or FakeSemantic([]), classifier_matcher=EmptyClassifier(),
                        llm_matcher=llm or LegacyLlm(), decision_matcher=decision,
                        candidate_retriever=FakeSemantic([]), config=CFG)


def _run(orch, business="FACTORY"):
    return asyncio.run(orch.match(query="嗯 签个退", factoryId=FACTORY, businessType=business, userId="u",
                                  role="operator", visible_intents=ROWS, history=[], min_confidence=0.7,
                                  enable_llm=True))


@pytest.fixture(autouse=True)
def _clean():
    registry.set_registry(registry.RegistrySnapshot())
    em.PY_LEGACY_ALARM._reset(0.0)
    em.LUNA_ALARM._reset(0.0)
    yield
    registry.set_registry(registry.RegistrySnapshot())


# ---------------- Python 旧四级: 决策层回落 ----------------

def test_no_probs_fallback_to_legacy_llm_counts_once_and_keeps_result():
    before = _exit("py_legacy_llm", "read", "decision_no_probs_qwen_only")
    site_before = _v("intent_legacy_exit_site_requests_total", site="py_decision")
    orch = _orch(FakeDecision(["CLOCK_OUT"], None))
    res = _run(orch)
    # 行为不变: 旧 LLM 答、带 decisionFallback 留痕 (与 test_ai_decision_layer 同口径)
    assert orch.llm_matcher.calls == 1
    assert res.bestMatch.intentCode == "MATERIAL_BATCH_QUERY"
    assert res.timingMs.get("decisionFallback") == 1
    assert _exit("py_legacy_llm", "read", "decision_no_probs_qwen_only") == before + 1
    assert _v("intent_legacy_exit_site_requests_total", site="py_decision") == site_before + 1


def test_decision_exception_fallback_to_vector_counts_with_reason_and_write_flag():
    """决策层抛错 + 旧 Stage 5 向量 0.95 直出一个写意图: 出口=向量, 原因=异常类型, rw=write。"""
    before = _exit("py_semantic_vector", "write", "decision_exception_RuntimeError")
    orch = _orch(FakeDecision(None, None, raise_on_choose=RuntimeError("boom")),
                 legacy_sem=FakeSemantic([("RETURN_ORDER_CREATE", 0.95)]))
    # 粗选给了候选才会走到 choose
    orch.decision_matcher.short = ["CLOCK_OUT"]
    res = _run(orch)
    assert res.bestMatch.intentCode == "RETURN_ORDER_CREATE"
    assert res.matchMethod == MatchMethod.SEMANTIC
    assert orch.llm_matcher.calls == 0
    assert _exit("py_semantic_vector", "write", "decision_exception_RuntimeError") == before + 1


def test_no_candidates_reason_is_recorded():
    before = _exit("py_legacy_llm", "read", "decision_no_candidates")
    res = _run(_orch(FakeDecision(None, {"CLOCK_OUT": 0.9})))  # 粗选 None, 向量也空 ⇒ 没有候选
    assert res.timingMs.get("decisionFallback") == 1
    assert _exit("py_legacy_llm", "read", "decision_no_candidates") == before + 1


def test_decision_answered_counts_no_exit():
    snap = {r: _exit("py_legacy_llm", "read", r) for r in ("decision_no_probs_qwen_only", "decision_no_candidates")}
    res = _run(_orch(FakeDecision(["CLOCK_OUT"], {"CLOCK_OUT": 0.95})))
    assert res.bestMatch.intentCode == "CLOCK_OUT"
    assert "decisionFallback" not in (res.timingMs or {})
    for r, v in snap.items():
        assert _exit("py_legacy_llm", "read", r) == v
    assert em.PY_LEGACY_ALARM._requests == 1 and em.PY_LEGACY_ALARM._hits == 0


def test_business_type_without_decision_layer_counts_as_non_fault():
    before = _exit("py_legacy_llm", "read", "decision_not_enabled")
    res = _run(_orch(FakeDecision(["CLOCK_OUT"], {"CLOCK_OUT": 0.9})), business="RESTAURANT")
    assert "decisionFallback" not in (res.timingMs or {})
    assert _exit("py_legacy_llm", "read", "decision_not_enabled") == before + 1
    assert em.PY_LEGACY_ALARM._requests == 0  # 配置使然的主路径不进告警


def test_counter_failure_never_changes_the_result(monkeypatch):
    class Broken:
        def labels(self, **k):
            raise RuntimeError("metrics down")

    monkeypatch.setattr(em, "LEGACY_EXIT", Broken())
    monkeypatch.setattr(em, "LEGACY_SITE_REQUESTS", Broken())
    res = _run(_orch(FakeDecision(["CLOCK_OUT"], None)))
    assert res.bestMatch.intentCode == "MATERIAL_BATCH_QUERY"
    assert res.timingMs.get("decisionFallback") == 1


# ---------------- Luna -> 千问 回落 ----------------

ROWS2 = [{"intent_code": "INVENTORY", "intent_name": "库存"}, {"intent_code": "COLD", "intent_name": "冷库"}]


async def _qwen_ok(self, payload, body_validator=None, content_validator=None):
    top = [{"token": "A", "logprob": math.log(0.7)}, {"token": "B", "logprob": math.log(0.3)}]
    return {"choices": [{"message": {"content": "A"}, "logprobs": {"content": [{"top_logprobs": top}]}}]}


async def _qwen_fails(self, payload, body_validator=None, content_validator=None):
    raise llm_router.LLMChainExhausted("decision", ["dashscope/qwen: quota 403"])


@pytest.fixture
def luna_on(monkeypatch, egress_as_factory_request):
    monkeypatch.setattr(llm_router, "decision_choice_enabled", lambda: True)


def _luna_raises(exc):
    async def native(*a, **k):
        raise exc
    return native


def test_luna_503_then_qwen_ok_counts_fallback(monkeypatch, luna_on):
    labels = dict(reason="http_503", qwen_outcome="ok")
    before = _v("intent_decision_luna_fallback_total", **labels)
    req_before = _v("intent_decision_luna_requests_total")
    monkeypatch.setattr(llm_router, "call_decision_choice",
                        _luna_raises(llm_router.LLMChainExhausted("decision_choice", ["openai/gpt-6-luna: http 503"])))
    monkeypatch.setattr(DecisionMatcher, "_call", _qwen_ok)
    out = asyncio.run(DecisionMatcher().choose("查冷库", "operator", ROWS2))
    assert sorted(out.values()) == pytest.approx([0.3, 0.7])  # 行为不变: 千问的概率照用
    assert _v("intent_decision_luna_fallback_total", **labels) == before + 1
    assert _v("intent_decision_luna_requests_total") == req_before + 1


def test_luna_timeout_then_qwen_fails_counts_both_failures(monkeypatch, luna_on):
    labels = dict(reason="timeout", qwen_outcome="failed_quota_403")
    before = _v("intent_decision_luna_fallback_total", **labels)
    monkeypatch.setattr(llm_router, "call_decision_choice", _luna_raises(asyncio.TimeoutError()))
    monkeypatch.setattr(DecisionMatcher, "_call", _qwen_fails)
    assert asyncio.run(DecisionMatcher().choose("查冷库", "operator", ROWS2)) is None
    assert _v("intent_decision_luna_fallback_total", **labels) == before + 1


def test_luna_answers_no_fallback_counted(monkeypatch, luna_on):
    async def native(messages, choices, *, name="intent", timeout=6.0):
        return {choices[0][0]: 0.2, choices[1][0]: 0.8}

    async def must_not_call(self, *a, **k):
        raise AssertionError("Luna 成功时不应再问千问")

    req_before = _v("intent_decision_luna_requests_total")
    monkeypatch.setattr(llm_router, "call_decision_choice", native)
    monkeypatch.setattr(DecisionMatcher, "_call", must_not_call)
    out = asyncio.run(DecisionMatcher().choose("查冷库", "operator", ROWS2))
    assert sorted(out.values()) == pytest.approx([0.2, 0.8])
    assert _v("intent_decision_luna_requests_total") == req_before + 1
    assert em.LUNA_ALARM._requests == 1 and em.LUNA_ALARM._hits == 0


def test_luna_not_configured_is_not_a_fallback(monkeypatch, egress_as_factory_request):
    monkeypatch.setattr(llm_router, "decision_choice_enabled", lambda: False)
    monkeypatch.setattr(DecisionMatcher, "_call", _qwen_ok)
    req_before = _v("intent_decision_luna_requests_total")
    asyncio.run(DecisionMatcher().choose("查冷库", "operator", ROWS2))
    assert _v("intent_decision_luna_requests_total") == req_before
    assert em.LUNA_ALARM._requests == 0


def test_orchestrator_reason_says_qwen_also_failed_after_luna(monkeypatch, luna_on):
    """真 DecisionMatcher.choose: Luna 503 + 千问也失败 ⇒ 旧四级接住, 原因带 after_luna。"""
    monkeypatch.setattr(llm_router, "call_decision_choice",
                        _luna_raises(llm_router.LLMChainExhausted("decision_choice", ["openai/gpt-6-luna: http 503"])))
    monkeypatch.setattr(DecisionMatcher, "_call", _qwen_fails)
    dm = DecisionMatcher()

    async def shortlist(*a, **k):
        return ["CLOCK_OUT", "MATERIAL_BATCH_QUERY"]

    async def purpose(*a, **k):
        return RequestPurpose.CURRENT

    monkeypatch.setattr(dm, "shortlist", shortlist)
    monkeypatch.setattr(dm, "request_purpose", purpose)
    before = _exit("py_legacy_llm", "read", "decision_no_probs_after_luna")
    res = _run(_orch(dm))
    assert res.bestMatch.intentCode == "MATERIAL_BATCH_QUERY"
    assert _exit("py_legacy_llm", "read", "decision_no_probs_after_luna") == before + 1


# ---------------- 告警 ----------------

def test_alarm_fires_once_per_window_above_threshold(caplog):
    alarm = em.RateAlarm("t", threshold=0.05, window_s=900, min_samples=20)
    alarm._reset(0.0)  # 窗口起点钉在 0, 下面的 now 都是相对它的秒数
    with caplog.at_level(logging.WARNING, logger="ai.legacy_exit_metrics"):
        for i in range(19):
            assert alarm.observe(hit=True, now=100.0 + i) is None  # 分母不够, 不喊
        fired = alarm.observe(hit=True, now=120.0)
        assert fired is not None and fired["rate"] == 1.0
        assert alarm.observe(hit=True, now=121.0) is None  # 同一窗只喊一次
    lines = [r.getMessage() for r in caplog.records if r.getMessage().startswith(em.ALERT_LOG_PREFIX)]
    assert len(lines) == 1 and '"site": "t"' in lines[0]


def test_alarm_quiet_at_or_below_threshold_and_resets_by_window():
    alarm = em.RateAlarm("t", threshold=0.05, window_s=900, min_samples=20)
    alarm._reset(0.0)  # 窗口起点钉在 0, 下面的 now 都是相对它的秒数
    for i in range(19):
        alarm.observe(hit=False, now=float(i))
    assert alarm.observe(hit=True, now=19.0) is None  # 1/20 = 5%, 不超过
    assert alarm.observe(hit=True, now=2000.0) is None  # 新窗口, 分母从 1 开始
    assert alarm._requests == 1


def test_classify_exception_bounds_label_values():
    E = llm_router.LLMChainExhausted
    assert em.classify_exception(E("s", ["k: http 503"])) == "http_503"
    assert em.classify_exception(E("s", ["k: quota 429"])) == "quota_429"
    assert em.classify_exception(E("s", ["k: timeout"])) == "timeout"
    assert em.classify_exception(E("s", ["k: something odd"])) == "chain_other"
    assert em.classify_exception(asyncio.TimeoutError()) == "timeout"
