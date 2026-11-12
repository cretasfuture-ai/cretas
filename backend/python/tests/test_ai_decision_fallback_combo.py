"""Luna 之后的组合退路 (INTENT-DISTILL 批次 2) 的契约闸: 每一跳的切换、写意图不因退路直接执行、计数。

链: Luna → combo (阿里云意图 ‖ qwen 动作/先问) → qwen_only → intent_ask (保守先问) → buttons。
走真实 Orchestrator + 真实 DecisionMatcher 的 choose_luna / 真实 fallback_combo.run_fallback / 真实 qwen_act 提示词;
只替换最外层的网络: Luna 的 call_decision_choice、阿里云的 call_aliyun_decision_choice、qwen 的 ``_call``。

⛔ OpenAI 一次都不调: autouse 夹具清掉出口与 key, 并在每条用例结束时断言假 Luna 之外没有任何 Decisions 请求。
"""
import asyncio
import math

import pytest
from prometheus_client import REGISTRY

from ai import legacy_exit_metrics as em
from ai import registry
from ai.config import AIConfig
from ai.dto import CandidateIntentDto, MatchMethod
from ai.matcher import fallback_combo as fc
from ai.matcher.decision import CLARIFY_KEY, LETTERS, NONE_KEY, DecisionMatcher, RequestPurpose
from ai.orchestrator import CANCEL_REPLY, Orchestrator
from common import llm_router

ROWS = [
    {"id": "1", "intent_code": "ORDER_LIST", "intent_name": "订单列表", "description": "查订单", "keywords": None,
     "intent_category": "SALES", "is_active": True, "sensitivity_level": "LOW"},
    {"id": "2", "intent_code": "ORDER_STATUS", "intent_name": "订单状态", "description": "查订单状态", "keywords": None,
     "intent_category": "SALES", "is_active": True, "sensitivity_level": "LOW"},
    {"id": "3", "intent_code": "RETURN_ORDER_CREATE", "intent_name": "创建退货单", "description": "客户退货",
     "keywords": None, "intent_category": "SALES", "is_active": True, "sensitivity_level": "HIGH"},
]
CODES = [r["intent_code"] for r in ROWS]
FACTORY = "F_FB_COMBO"
CFG = AIConfig(decision_business_types="FACTORY", decision_special_options=True, decision_fallback="combo")

ACT_PROMPT_MARK = "这句话在对话中是什么动作"


def _v(name, **labels):
    return REGISTRY.get_sample_value(name, labels) or 0.0


def _act_body(dist):
    tokens = [(L, dist[a]) for L, a in zip(LETTERS, fc.ACTS) if dist.get(a, 0) > 0]
    return {"choices": [{"logprobs": {"content": [{"token": tokens[0][0], "top_logprobs": [
        {"token": t, "logprob": math.log(p)} for t, p in tokens]}]}}]}


def _boom(reason):
    return llm_router.LLMChainExhausted("x", [f"acct/model: {reason}"])


class ScriptedMatcher(DecisionMatcher):
    """真实 DecisionMatcher; 只替换候选粗选 / 交际目的 (不在本闸范围) 与最外层 qwen 调用。"""

    def __init__(self, act=None, qwen_intent=None):
        super().__init__()
        self.act, self.qwen_intent = act, qwen_intent
        self.calls = {"act": 0, "qwen_intent": 0}

    async def shortlist(self, query, role, visible, history=None):
        return list(CODES)

    async def request_purpose(self, query, role, history=None):
        return RequestPurpose.CURRENT

    async def _call(self, payload, body_validator=None, content_validator=None):
        user = payload["messages"][1]["content"]
        if ACT_PROMPT_MARK in user:
            self.calls["act"] += 1
            if isinstance(self.act, BaseException):
                raise self.act
            return _act_body(self.act)
        raise AssertionError("unexpected _call: " + user[:80])

    async def choose_qwen(self, query, role, options, history=None, special_options=False):
        self.calls["qwen_intent"] += 1
        if isinstance(self.qwen_intent, BaseException):
            raise self.qwen_intent
        return dict(self.qwen_intent)


class FakeSemantic:
    async def match(self, query, factoryId=None, businessType=None, top_k=None):
        return []


class LegacyMustNotRun:
    async def match(self, *a, **k):
        raise AssertionError("退路必须自己给出结论, ⛔ 不回落旧四级")


@pytest.fixture(autouse=True)
def _isolate(monkeypatch, egress_as_factory_request):
    registry.set_registry(registry.RegistrySnapshot())
    for k in ("LLM_EGRESS_PROXY", "LLM_OPENAI_API_KEY", "LLM_ANTHROPIC_API_KEY", "LLM_PAID_GENERATIVE",
              "AI_DECISION_FALLBACK_CLARIFY_THRESHOLD", "AI_DECISION_FALLBACK_READ_THRESHOLD"):
        monkeypatch.delenv(k, raising=False)
    assert llm_router.decision_choice_enabled() is False  # 默认环境下 Luna 本就不会发请求
    state = {"luna_calls": 0, "aliyun_calls": 0}

    async def luna_never(*a, **k):
        state["luna_calls"] += 1
        raise AssertionError("这条用例没打开 Luna, 却发了 Decisions 请求")
    monkeypatch.setattr(llm_router, "call_decision_choice", luna_never)
    em.FALLBACK_ALARM._reset(0.0)
    yield state
    registry.set_registry(registry.RegistrySnapshot())


def _luna(monkeypatch, *, raises=None, dist=None):
    monkeypatch.setattr(llm_router, "decision_choice_enabled", lambda: True)

    async def native(messages, choices, *, name="intent", timeout=6.0):
        if raises is not None:
            raise raises
        # Luna 的选项值是字母, 说明以「意图码：」开头
        return {v: dist.get(d.split("：", 1)[0], 0.0) for v, d in choices}
    monkeypatch.setattr(llm_router, "call_decision_choice", native)


def _aliyun(monkeypatch, state, *, raises=None, dist=None, enabled=True):
    monkeypatch.setattr(llm_router, "aliyun_decision_enabled", lambda: enabled)

    async def call(st, choices, *, name="intent", timeout=2.0):
        state["aliyun_calls"] += 1
        state["aliyun_state"] = st
        if raises is not None:
            raise raises
        return {v: dist.get(v, 0.0) for v, _d in choices}
    monkeypatch.setattr(llm_router, "call_aliyun_decision_choice", call)


def _run(matcher, query="已完成的订单", cfg=CFG):
    orch = Orchestrator(semantic_matcher=FakeSemantic(), classifier_matcher=FakeSemantic(),
                        llm_matcher=LegacyMustNotRun(), decision_matcher=matcher,
                        candidate_retriever=FakeSemantic(), config=cfg)
    return asyncio.run(orch.match(query=query, factoryId=FACTORY, businessType="FACTORY", userId="u", role="operator",
                                  visible_intents=ROWS, history=[], min_confidence=0.7, enable_llm=True))


NEW = {"NEW_TASK": 0.9, "NEED_CLARIFY": 0.05, "CHANGE_PARAM": 0.05}
ALI_READ = {"ORDER_LIST": 0.7, "ORDER_STATUS": 0.2, NONE_KEY: 0.05, CLARIFY_KEY: 0.05}
QWEN_READ = {"ORDER_STATUS": 0.6, "ORDER_LIST": 0.3, NONE_KEY: 0.05, CLARIFY_KEY: 0.05}


def _hop(hop, outcome):
    return _v("intent_decision_fallback_total", hop=hop, outcome=outcome)


# ── 跳 0: Luna 能答就只用 Luna ──────────────────────────────────────────────────────────────────────────
def test_luna_answers_and_no_fallback_side_is_called(monkeypatch, _isolate):
    _luna(monkeypatch, dist={"ORDER_LIST": 0.95})
    _aliyun(monkeypatch, _isolate, dist=ALI_READ)
    m = ScriptedMatcher(act=NEW, qwen_intent=QWEN_READ)
    res = _run(m)
    assert res.bestMatch.intentCode == "ORDER_LIST" and res.requiresConfirmation is False
    assert "fallbackHop" not in res.timingMs
    assert _isolate["aliyun_calls"] == 0 and m.calls == {"act": 0, "qwen_intent": 0}


# ── 跳 1: Luna 失败 → 组合 ─────────────────────────────────────────────────────────────────────────────
def test_luna_failure_goes_to_combo_aliyun_intent_plus_qwen_act(monkeypatch, _isolate):
    _luna(monkeypatch, raises=_boom("http 503"))
    _aliyun(monkeypatch, _isolate, dist=ALI_READ)
    m = ScriptedMatcher(act=NEW, qwen_intent=QWEN_READ)
    combo_before = _hop("combo", "EXECUTE")
    luna_before = _v("intent_decision_luna_fallback_total", reason="http_503", qwen_outcome="fallback_combo")
    res = _run(m)
    # 意图来自阿里云 (ORDER_LIST), 不是 qwen 意图 (ORDER_STATUS); qwen 意图根本没被调用
    assert res.bestMatch.intentCode == "ORDER_LIST" and res.requiresConfirmation is False
    assert res.timingMs["fallbackHop"] == 1 and m.calls == {"act": 1, "qwen_intent": 0}
    assert _hop("combo", "EXECUTE") == combo_before + 1
    assert _v("intent_decision_luna_fallback_total", reason="http_503", qwen_outcome="fallback_combo") == luna_before + 1
    # 阿里云看到的正文与离线重放同形: 用户原话 + 候选清单
    assert "用户说：已完成的订单" in _isolate["aliyun_state"] and "ORDER_LIST：" in _isolate["aliyun_state"]


def test_luna_not_enabled_goes_straight_to_combo_without_counting_a_luna_failure(monkeypatch, _isolate):
    _aliyun(monkeypatch, _isolate, dist=ALI_READ)
    req_before = _v("intent_decision_luna_requests_total")
    res = _run(ScriptedMatcher(act=NEW, qwen_intent=QWEN_READ))
    assert res.timingMs["fallbackHop"] == 1 and res.bestMatch.intentCode == "ORDER_LIST"
    assert _v("intent_decision_luna_requests_total") == req_before
    assert _isolate["luna_calls"] == 0


def test_clarify_threshold_comes_from_the_fallback_config_not_luna(monkeypatch, _isolate):
    """qwen P(NEED_CLARIFY)=0.35: 缺省门槛 0.3 ⇒ 先问; 门槛调到 0.5 ⇒ 按默认值直接办。阴阳两侧同一输入。"""
    _aliyun(monkeypatch, _isolate, dist=ALI_READ)
    act = {"NEW_TASK": 0.6, "NEED_CLARIFY": 0.35, "CHANGE_PARAM": 0.05}
    asked = _run(ScriptedMatcher(act=act, qwen_intent=QWEN_READ))
    assert asked.requiresConfirmation is True and asked.timingMs["fallbackClarifyP"] == 350
    monkeypatch.setenv("AI_DECISION_FALLBACK_CLARIFY_THRESHOLD", "0.5")
    done = _run(ScriptedMatcher(act=act, qwen_intent=QWEN_READ))
    assert done.requiresConfirmation is False and done.bestMatch.intentCode == "ORDER_LIST"


def test_cancel_act_is_not_executed_and_not_asked(monkeypatch, _isolate):
    _aliyun(monkeypatch, _isolate, dist=ALI_READ)
    res = _run(ScriptedMatcher(act={"CANCEL": 0.8, "NEW_TASK": 0.2}, qwen_intent=QWEN_READ), query="算了不查了")
    assert res.bestMatch is None and res.conversationMessage == CANCEL_REPLY
    assert res.timingMs["decisionNone"] == 1


# ── 跳 2: 阿里云挂了 → 整轮 qwen ───────────────────────────────────────────────────────────────────────
def test_aliyun_failure_falls_to_qwen_only_and_counts_the_reason(monkeypatch, _isolate):
    _aliyun(monkeypatch, _isolate, raises=_boom("rate_limited"))
    m = ScriptedMatcher(act=NEW, qwen_intent=QWEN_READ)
    side_before = _v("intent_decision_fallback_side_failure_total", side="aliyun_intent", reason="rate_limited")
    res = _run(m)
    assert res.bestMatch.intentCode == "ORDER_STATUS"  # qwen 的意图
    assert res.timingMs["fallbackHop"] == 2 and res.timingMs["fallbackFail_aliyun_intent"] == 1
    assert m.calls == {"act": 1, "qwen_intent": 1}
    assert _v("intent_decision_fallback_side_failure_total", side="aliyun_intent", reason="rate_limited") == side_before + 1
    assert em.FALLBACK_ALARM._hits == 1  # 真故障进告警分子


def test_aliyun_not_configured_is_qwen_only_but_not_an_alarm_hit(monkeypatch, _isolate):
    _aliyun(monkeypatch, _isolate, dist=ALI_READ, enabled=False)
    res = _run(ScriptedMatcher(act=NEW, qwen_intent=QWEN_READ))
    assert res.timingMs["fallbackHop"] == 2 and _isolate["aliyun_calls"] == 0
    assert em.FALLBACK_ALARM._requests == 1 and em.FALLBACK_ALARM._hits == 0


# ── 跳 3: qwen 动作挂了 → 阿里云意图 + 保守先问 ─────────────────────────────────────────────────────────
def test_qwen_act_failure_uses_aliyun_intent_but_always_asks(monkeypatch, _isolate):
    _aliyun(monkeypatch, _isolate, dist={"ORDER_LIST": 0.97, "ORDER_STATUS": 0.01, NONE_KEY: 0.01, CLARIFY_KEY: 0.01})
    m = ScriptedMatcher(act=_boom("timeout"), qwen_intent=QWEN_READ)
    res = _run(m)
    # 把握 0.97 的只读意图也不直接办: 不知道这是不是续句 / 取消 / 该先问
    assert res.requiresConfirmation is True and res.bestMatch.intentCode == "ORDER_LIST"
    assert res.timingMs["fallbackHop"] == 3 and res.timingMs["fallbackFail_qwen_act"] == 1
    assert m.calls["qwen_intent"] == 0


# ── 跳 4: 都拿不到 → 候选做按钮 ───────────────────────────────────────────────────────────────────────
def test_everything_fails_gives_candidate_buttons_with_zero_confidence(monkeypatch, _isolate):
    _luna(monkeypatch, raises=asyncio.TimeoutError())
    _aliyun(monkeypatch, _isolate, raises=_boom("http 500"))
    res = _run(ScriptedMatcher(act=_boom("quota 403"), qwen_intent=_boom("quota 403")))
    assert res.requiresConfirmation is True
    assert [c.intentCode for c in res.topCandidates] == CODES  # 粗选原顺序, 全部出按钮
    assert all(c.confidence == 0.0 for c in res.topCandidates)  # ⛔ 不编概率
    assert res.timingMs["fallbackHop"] == 4 and res.timingMs["fallbackNoSignal"] == 1
    assert "decisionFallback" not in res.timingMs  # 没有回落旧四级 (LegacyMustNotRun 也会炸)


# ── 写意图: 任何一跳都不因退路被直接执行 ───────────────────────────────────────────────────────────────
@pytest.mark.parametrize("aliyun_ok", [True, False], ids=["combo", "qwen_only"])
def test_write_intent_is_never_auto_executed_on_the_fallback(monkeypatch, _isolate, aliyun_ok):
    write = {"RETURN_ORDER_CREATE": 0.95, "ORDER_LIST": 0.03, NONE_KEY: 0.01, CLARIFY_KEY: 0.01}
    if aliyun_ok:
        _aliyun(monkeypatch, _isolate, dist=write)
    else:
        _aliyun(monkeypatch, _isolate, raises=_boom("timeout"))
    res = _run(ScriptedMatcher(act=NEW, qwen_intent=write), query="把这单给客户退了")
    assert res.bestMatch.intentCode == "RETURN_ORDER_CREATE"
    assert res.requiresConfirmation is True and res.timingMs["fallbackWriteHeld"] == 1
    assert res.timingMs["fallbackHop"] == (1 if aliyun_ok else 2)


def test_legacy_mode_keeps_the_old_chain(monkeypatch, _isolate):
    """回滚开关: AI_DECISION_FALLBACK=legacy ⇒ 阿里云与 qwen 动作都不调, 走原来的 qwen 读 logprobs。"""
    from dataclasses import replace
    _aliyun(monkeypatch, _isolate, dist=ALI_READ)
    m = ScriptedMatcher(act=NEW, qwen_intent=QWEN_READ)
    res = _run(m, cfg=replace(CFG, decision_fallback="legacy"))
    assert "fallbackHop" not in res.timingMs and _isolate["aliyun_calls"] == 0 and m.calls["act"] == 0
    assert res.bestMatch.intentCode == "ORDER_STATUS"


# ── 阿里云调用本身 (call_aliyun_decision_choice) ────────────────────────────────────────────────────────
class _Resp:
    def __init__(self, status, body):
        self.status_code, self._body = status, body
        self.text = str(body)

    def json(self):
        return self._body


class _Client:
    def __init__(self, responses, delay=0.0):
        self.responses, self.delay = list(responses), delay
        self.sent, self.inflight, self.max_inflight = [], 0, 0

    async def redact_chat_payload(self, payload):
        return payload, {}

    async def post(self, url, headers=None, json=None, timeout=None):
        self.inflight += 1
        self.max_inflight = max(self.max_inflight, self.inflight)
        try:
            await asyncio.sleep(self.delay)
            self.sent.append((url, headers, json))
            return self.responses.pop(0) if len(self.responses) > 1 else self.responses[0]
        finally:
            self.inflight -= 1


URL = "https://ws-test.example/compatible-mode/v1/systemone"
CHOICES = [("ORDER_LIST", "订单列表"), (NONE_KEY, "以上都不是")]


@pytest.fixture
def aliyun_env(monkeypatch):
    monkeypatch.setenv("LLM_ALIYUN_DECISION_URL", URL)
    monkeypatch.setenv("LLM_ALIYUN_B_API_KEY", "test-key-not-real")
    with llm_router._CB_LOCK:
        llm_router._CB_FAILURES.pop("aliyun_b_vl/decision-model-preview", None)
    monkeypatch.setattr(llm_router, "_quota_should_skip", lambda k: False)
    yield
    with llm_router._CB_LOCK:
        llm_router._CB_FAILURES.pop("aliyun_b_vl/decision-model-preview", None)


def _use(monkeypatch, client):
    monkeypatch.setattr(llm_router, "_client_for", lambda account: client)


def test_aliyun_call_sends_systemone_shape_and_returns_probabilities(monkeypatch, aliyun_env):
    ok = _Resp(200, {"answers": {"intent": {"choice": "ORDER_LIST", "probabilities": {"ORDER_LIST": 0.8, NONE_KEY: 0.2}}},
                     "usage": {"input_tokens": 100}, "latency_ms": 90})
    client = _Client([ok])
    _use(monkeypatch, client)
    dist = asyncio.run(llm_router.call_aliyun_decision_choice("用户说：查订单", CHOICES))
    assert dist == {"ORDER_LIST": 0.8, NONE_KEY: 0.2}
    url, headers, body = client.sent[0]
    assert url == URL and headers["Authorization"] == "Bearer test-key-not-real"
    assert body["model"] == "decision-model-preview" and body["state"] == "用户说：查订单"
    assert body["questions"] == {"intent": {"type": "choice", "criteria": dict(CHOICES)}}


def test_aliyun_call_unconfigured_sends_nothing(monkeypatch, aliyun_env):
    monkeypatch.delenv("LLM_ALIYUN_DECISION_URL")
    client = _Client([_Resp(200, {})])
    _use(monkeypatch, client)
    with pytest.raises(llm_router.LLMChainExhausted) as ei:
        asyncio.run(llm_router.call_aliyun_decision_choice("x", CHOICES))
    assert em.classify_exception(ei.value) == "not_configured" and client.sent == []
    assert llm_router.aliyun_decision_enabled() is False


def test_aliyun_429_is_rate_limited_and_opens_the_breaker(monkeypatch, aliyun_env):
    client = _Client([_Resp(429, {"message": "Allocated quota exceeded, please increase your quota limit."})])
    _use(monkeypatch, client)
    for _ in range(llm_router.CB_THRESHOLD):
        with pytest.raises(llm_router.LLMChainExhausted) as ei:
            asyncio.run(llm_router.call_aliyun_decision_choice("x", CHOICES))
        assert em.classify_exception(ei.value) == "rate_limited"
    with pytest.raises(llm_router.LLMChainExhausted) as ei:  # 熔断开着: 不再发
        asyncio.run(llm_router.call_aliyun_decision_choice("x", CHOICES))
    assert em.classify_exception(ei.value) == "cb_open" and len(client.sent) == llm_router.CB_THRESHOLD


def test_aliyun_option_set_mismatch_is_rejected_not_patched(monkeypatch, aliyun_env):
    bad = _Resp(200, {"answers": {"intent": {"probabilities": {"ORDER_LIST": 1.0}}}})
    _use(monkeypatch, _Client([bad]))
    with pytest.raises(llm_router.LLMChainExhausted) as ei:
        asyncio.run(llm_router.call_aliyun_decision_choice("x", CHOICES))
    assert em.classify_exception(ei.value) == "invalid_choice_distribution"


def test_aliyun_concurrency_is_capped_at_two_per_worker(monkeypatch, aliyun_env):
    ok = _Resp(200, {"answers": {"intent": {"probabilities": {"ORDER_LIST": 0.8, NONE_KEY: 0.2}}}})
    client = _Client([ok], delay=0.05)
    _use(monkeypatch, client)

    async def five():
        return await asyncio.gather(*(llm_router.call_aliyun_decision_choice("x", CHOICES) for _ in range(5)))
    assert len(asyncio.run(five())) == 5
    assert client.max_inflight == 2


def test_aliyun_pair_is_registered_but_on_no_chain():
    pair = llm_router.ALIYUN_DECISION_PAIR
    assert pair in llm_router._SAFE_MODELS and llm_router._refuse_reason(*pair) is None
    assert all(pair not in chain for chain in llm_router.SLOT_MODELS.values())


def test_fallback_thresholds_default_to_the_report_calibration():
    """报告第 4 节 E 线半集校准, 「阿里云意图 + qwen 动作/先问（v3）」: clarify 0.3 / exec 0.05。"""
    t = fc.FallbackThresholds()
    assert (t.clarify, t.read, t.margin, t.write) == (0.3, 0.05, 0.3, 0.4)
    assert fc.FallbackThresholds.from_env() == t


def test_semantic_read_prototype_uses_the_production_rule():
    """形态 D: E 线评测与线上共用一份 decide_v3 / 提示词, 不许各拷一份。"""
    import pathlib
    import sys
    here = pathlib.Path(__file__).resolve().parents[3] / "tests" / "semantic-read"
    sys.path.insert(0, str(here))
    try:
        import nogen_pipeline
    finally:
        sys.path.remove(str(here))
    assert nogen_pipeline.decide_v3 is fc.decide_v3
    assert nogen_pipeline.CLARIFY_RULES is fc.CLARIFY_RULES and nogen_pipeline.ACT_TEXT is fc.ACT_TEXT
