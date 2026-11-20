"""2026-11-12 LLM-PROVIDER-SWITCH: OpenAI / Anthropic providers in the router.

What is pinned here (each with a control so it cannot pass vacuously):
  · Anthropic egress is redacted BEFORE translation, through the same shared choke point, and the
    answer is restored — non-stream and stream.
  · OpenAI Decisions (gpt-6-luna) choice: redacted, distribution validated, failures explicit.
  · Per-provider params: what GPT-5.x / GPT-4.1 / Claude each must and must not receive.
  · Paid-tier guard: every paid registry entry is priced and stays in the cheap tier.
  · Keys are read only from the LLM_* names (eval keys under generic names are never picked up).
"""
import asyncio
import json

import httpx
import pytest

from common import llm_client, llm_router
from common.llm_redactor import RedactionMeta
from common.llm_router import SLOT

pytestmark = pytest.mark.usefixtures("egress_as_factory_request")

REAL, TOKEN = "张三", "[人员A]"


@pytest.fixture(autouse=True)
def _clean(monkeypatch):
    for d in (llm_router._CB_FAILURES, llm_router._CB_LAST_FAIL, llm_router._QUOTA_EXHAUSTED_UNTIL):
        d.clear()
    monkeypatch.setenv("LLM_ANTHROPIC_API_KEY", "ak-fake")
    monkeypatch.setenv("LLM_OPENAI_API_KEY", "ok-fake")
    # 2026-11-12: 付费两家是可选启用的; 本文件测的是启用之后的行为, 所以把出口配上
    monkeypatch.setenv("LLM_EGRESS_PROXY", "socks5h://127.0.0.1:11438")
    monkeypatch.setattr(llm_router, "_log_cache_and_record_budget", lambda *a, **k: None)
    yield
    for d in (llm_router._CB_FAILURES, llm_router._CB_LAST_FAIL, llm_router._QUOTA_EXHAUSTED_UNTIL):
        d.clear()


def _fake_redactor(monkeypatch):
    """Replace REAL with TOKEN in every message text; report the token so the answer can be restored."""
    def redact(payload, _names=None):
        out = json.loads(json.dumps(payload, ensure_ascii=False).replace(REAL, TOKEN))
        return out, RedactionMeta(sanitized=True, redacted_count=1, egress_tokens={TOKEN: REAL})
    monkeypatch.setattr("common.llm_redactor.redact_payload_for_egress", redact)


class _Inner:
    """Stands in for the shared httpx client underneath the REAL redacting wrapper."""

    def __init__(self, answer=None, lines=None, status=200):
        self.posts, self.streams = [], []
        self._answer, self._lines, self._status = answer, lines, status

    async def post(self, url, json=None, headers=None, timeout=None):  # noqa: A002
        self.posts.append((str(url), json, dict(headers or {})))
        return httpx.Response(self._status, json=self._answer, request=httpx.Request("POST", str(url)))

    def stream(self, method, url, json=None, headers=None, timeout=None):  # noqa: A002
        self.streams.append((str(url), json, dict(headers or {})))
        inner = self

        class _Resp:
            status_code = 200

            async def aiter_lines(self):
                for line in inner._lines:
                    yield line

            async def aread(self):
                return b""

        class _CM:
            async def __aenter__(self):
                return _Resp()

            async def __aexit__(self, *_):
                return False

        return _CM()


def _install(monkeypatch, inner):
    wrapper = llm_client._RedactingLLMClient(inner)
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: wrapper)
    monkeypatch.setattr(llm_router, "get_llm_proxied_http_client", lambda: wrapper)
    return inner


def _only(monkeypatch, slot, pair):
    monkeypatch.setitem(llm_router.SLOT_MODELS, slot, [pair])


# ── Anthropic: redact → translate → post → translate back → restore ─────────────────────
def test_anthropic_egress_is_redacted_before_translation_and_answer_restored(monkeypatch):
    _fake_redactor(monkeypatch)
    inner = _install(monkeypatch, _Inner(answer={
        "id": "m", "model": "claude-haiku-5-5", "stop_reason": "end_turn",
        "content": [{"type": "text", "text": f"{TOKEN} 的冷库是 5 度"}],
        "usage": {"input_tokens": 12, "output_tokens": 5}}))
    _only(monkeypatch, SLOT.CHAT, ("anthropic", "claude-haiku-5-5"))
    out = asyncio.run(llm_router.call_chain(SLOT.CHAT, {
        "messages": [{"role": "system", "content": "简短"}, {"role": "user", "content": f"{REAL} 的冷库几度"}],
        "max_tokens": 50, "temperature": 0.3}))
    assert len(inner.posts) == 1
    url, body, headers = inner.posts[0]
    assert url == "https://api.anthropic.com/v1/messages"
    sent = json.dumps(body, ensure_ascii=False)
    assert TOKEN in sent and REAL not in sent, "真名出境了 —— 翻译在脱敏之前, 或根本没脱敏"
    assert headers["x-api-key"] == "ak-fake" and "Authorization" not in headers
    assert body["system"] == "简短" and body["thinking"] == {"type": "disabled"}
    assert "temperature" not in body
    assert out["choices"][0]["message"]["content"] == f"{REAL} 的冷库是 5 度"
    assert out["usage"]["total_tokens"] == 17


def test_anthropic_redaction_failure_sends_nothing(monkeypatch):
    def boom(_payload, _names=None):
        raise RuntimeError("redactor exploded")
    monkeypatch.setattr("common.llm_redactor.redact_payload_for_egress", boom)
    inner = _install(monkeypatch, _Inner(answer={}))
    _only(monkeypatch, SLOT.CHAT, ("anthropic", "claude-haiku-5-5"))
    with pytest.raises(llm_client.EgressRedactionError):
        asyncio.run(llm_router.call_chain(SLOT.CHAT, {"messages": [{"role": "user", "content": "x"}]}))
    assert inner.posts == []


def test_anthropic_quota_body_is_classified_and_falls_back(monkeypatch):
    inner = _install(monkeypatch, _Inner(status=400, answer={
        "type": "error", "error": {"type": "invalid_request_error",
                                   "message": "Your credit balance is too low to access the Anthropic API."}}))
    _only(monkeypatch, SLOT.CHAT, ("anthropic", "claude-haiku-5-5"))
    with pytest.raises(llm_router.LLMChainExhausted) as ei:
        asyncio.run(llm_router.call_chain(SLOT.CHAT, {"messages": [{"role": "user", "content": "x"}]}))
    assert "anthropic/claude-haiku-5-5: quota 400" in str(ei.value)
    assert llm_router._quota_should_skip("anthropic/claude-haiku-5-5")
    assert len(inner.posts) == 1


def test_anthropic_untranslatable_request_moves_on_without_circuit_breaker(monkeypatch):
    inner = _install(monkeypatch, _Inner(answer={"choices": [{"message": {"content": "openai 答"}}]}))
    monkeypatch.setitem(llm_router.SLOT_MODELS, SLOT.CHAT,
                        [("anthropic", "claude-haiku-5-5"), ("openai", "gpt-4.1-mini")])
    tools = [{"type": "function", "function": {"name": "q", "parameters": {"type": "object"}}}]
    out = asyncio.run(llm_router.call_chain(SLOT.CHAT, {
        "messages": [{"role": "user", "content": "x"}], "tools": tools, "tool_choice": "required"}))
    assert out["choices"][0]["message"]["content"] == "openai 答"
    assert [p[0] for p in inner.posts] == ["https://api.openai.com/v1/chat/completions"]
    assert llm_router._CB_FAILURES.get("anthropic/claude-haiku-5-5", 0) == 0


def test_anthropic_stream_is_redacted_translated_and_restored(monkeypatch):
    _fake_redactor(monkeypatch)
    lines = [
        "event: message_start",
        'data: {"type":"message_start","message":{"usage":{"input_tokens":11}}}',
        'data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"' + TOKEN + ' 的"}}',
        'data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"冷库是 5 度"}}',
        'data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}',
        'data: {"type":"message_stop"}',
    ]
    inner = _install(monkeypatch, _Inner(lines=lines))
    _only(monkeypatch, SLOT.CHAT, ("anthropic", "claude-haiku-5-5"))

    async def collect():
        return [ev async for ev in llm_router.call_chain_stream(
            SLOT.CHAT, {"messages": [{"role": "user", "content": f"{REAL} 的冷库几度"}]})]

    events = asyncio.run(collect())
    text = "".join(e["text"] for e in events if e["type"] == "delta")
    assert text == f"{REAL} 的冷库是 5 度"
    assert {"type": "usage", "tokens": 18} in events
    url, body, headers = inner.streams[0]
    assert url == "https://api.anthropic.com/v1/messages" and body["stream"] is True
    sent = json.dumps(body, ensure_ascii=False)
    assert TOKEN in sent and REAL not in sent


# ── OpenAI: plain chat/completions, provider-specific params ────────────────────────────
def test_openai_reasoning_model_params():
    p = llm_router._apply_slot_params(SLOT.CHAT, "openai", "gpt-5.4-nano", llm_router._normalize_payload_for_provider({
        "model": "gpt-5.4-nano", "messages": [{"role": "user", "content": "x"},
                                              {"role": "assistant", "content": "答案：", "partial": True}],
        "max_tokens": 64, "temperature": 0.7, "enable_thinking": True, "extra_body": {"a": 1}}, "openai"))
    assert p["max_completion_tokens"] == 64 and "max_tokens" not in p
    assert "temperature" not in p and "enable_thinking" not in p and "extra_body" not in p
    assert p["reasoning_effort"] == "none"
    assert p["messages"] == [{"role": "user", "content": "x"}], "partial 前缀必须去掉(OpenAI 不认)"


def test_openai_strict_json_model_keeps_determinism_params():
    p = llm_router._apply_slot_params(SLOT.MAPPER, "openai", "gpt-4.1-mini", {
        "model": "gpt-4.1-mini", "messages": [{"role": "user", "content": "输出 json"}], "max_tokens": 64})
    assert p["response_format"] == {"type": "json_object"}
    assert p["temperature"] == 0 and p["seed"] == 1234
    assert "reasoning_effort" not in p and "enable_thinking" not in p


def test_dashscope_params_still_apply_to_dashscope_accounts():
    """对照: 同一个 profile 对阿里云账号仍下发 enable_thinking —— 上面的「不下发」来自供应商分支。"""
    p = llm_router._apply_slot_params(SLOT.CHAT, "aliyun_a_vl", "qwen3.8-flash", {"messages": []})
    assert p["enable_thinking"] is False


def test_openai_project_header_only_when_configured(monkeypatch):
    monkeypatch.delenv("LLM_OPENAI_PROJECT_ID", raising=False)
    assert "OpenAI-Project" not in llm_router._auth_headers("openai", "k")
    monkeypatch.setenv("LLM_OPENAI_PROJECT_ID", "proj_x")
    assert llm_router._auth_headers("openai", "k")["OpenAI-Project"] == "proj_x"
    assert llm_router._auth_headers("anthropic", "k")["x-api-key"] == "k"


@pytest.mark.parametrize("status,body,quota", [
    (429, '{"error":{"code":"insufficient_quota"}}', True),
    (429, '{"error":{"code":"rate_limit_exceeded"}}', False),   # 普通限流 = 短熔断
    (400, '{"error":{"message":"Your credit balance is too low"}}', True),
    (400, '{"error":{"message":"You have reached your specified API usage limits"}}', True),
    (400, '{"error":{"message":"max_tokens: field required"}}', False),
])
def test_paid_provider_quota_classification(status, body, quota):
    assert llm_router._is_quota_exhausted(status, body) is quota


def test_keys_come_only_from_llm_prefixed_names(monkeypatch):
    monkeypatch.delenv("LLM_OPENAI_API_KEY", raising=False)
    monkeypatch.delenv("LLM_ANTHROPIC_API_KEY", raising=False)
    monkeypatch.setenv("OPENAI_API_KEY", "eval-key-must-not-be-used")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "eval-key-must-not-be-used")
    assert llm_router._provider_config("openai")[1] == ""
    assert llm_router._provider_config("anthropic")[1] == ""
    monkeypatch.setenv("LLM_OPENAI_API_KEY", "prod")
    assert llm_router._provider_config("openai")[1] == "prod"


# ── Paid-tier guard ──────────────────────────────────────────────────────────────────────
_PAID_ACCOUNTS = {"openai", "anthropic"}
_MAX_INPUT_PER_MTOK, _MAX_OUTPUT_PER_MTOK = 2.00, 10.00


def test_paid_models_stay_in_the_cheap_tier():
    paid = [p for p in llm_router._SAFE_MODELS if p[0] in _PAID_ACCOUNTS]
    assert paid, "没有付费条目 —— 闸空转"
    for pair in paid:
        assert pair in llm_router._PRICE_PER_MTOK, f"{pair} 没有标价, 无法估算费用"
        i, o = llm_router._PRICE_PER_MTOK[pair]
        assert i <= _MAX_INPUT_PER_MTOK and o <= _MAX_OUTPUT_PER_MTOK, f"{pair} 超出便宜档: {i}/{o}"
    assert set(llm_router._PRICE_PER_MTOK) == set(paid)


def test_every_paid_pair_has_a_probe_reading_and_vision_slots_use_verified_pairs():
    for pair in llm_router._SAFE_MODELS:
        if pair[0] in _PAID_ACCOUNTS:
            assert "ok 200" in llm_router._REGISTRY_AUDIT_PROBE[pair], pair
    for slot in (SLOT.VL, SLOT.WAREHOUSE_VL):
        for pair in llm_router.SLOT_MODELS[slot]:
            assert pair in llm_router._VISION_VERIFIED, f"{slot.value}: {pair} 没有识图证据"


def test_vl_env_cannot_pick_unverified_disabled_or_unenabled_paid_pairs(monkeypatch):
    chain = [("openai", "gpt-5.4-nano"),          # 未登记识图
             ("aliyun_a_vl", "qwen3.8-flash"),     # 合格(阿里云)
             ("openai", "gpt-4.1-mini")]           # 登记了识图, 但付费: 只在可选启用时可选
    monkeypatch.setattr(llm_router, "_VL_CHAIN", chain)
    monkeypatch.delenv("LLM_PAID_GENERATIVE", raising=False)
    assert llm_router._vl_pool() == [("aliyun_a_vl", "qwen3.8-flash")]
    monkeypatch.setenv("LLM_PAID_GENERATIVE", "1")       # 出口 + key 由 fixture 配好
    assert llm_router._vl_pool() == [("aliyun_a_vl", "qwen3.8-flash"), ("openai", "gpt-4.1-mini")]
    monkeypatch.setattr(llm_router, "_DISABLED_ACCOUNTS", frozenset({"aliyun_a_vl"}))
    assert llm_router._vl_pool() == [("openai", "gpt-4.1-mini")]
    monkeypatch.setattr(llm_router, "_VL_CHAIN", [("openai", "gpt-5.4-nano")])
    assert llm_router._vl_pool() == []
    assert llm_router._parse_pairs(" anthropic/claude-haiku-5-5 , bad, openai/ ,openai/gpt-4.1-mini") == [
        ("anthropic", "claude-haiku-5-5"), ("openai", "gpt-4.1-mini")]


# ── OpenAI Decisions (gpt-6-luna) ───────────────────────────────────────────────────────
_MSGS = [{"role": "system", "content": "只输出字母"},
         {"role": "user", "content": f"{REAL} 说：查冷库温度\n选项：A. 库存\nB. 冷库温度"},
         {"role": "assistant", "content": "答案：", "partial": True}]
_CHOICES = [("A", "INVENTORY：库存"), ("B", "COLD：冷库温度")]


def _decision_answer(rows):
    return {"answers": [{"type": "choice", "name": "intent", "choice": "B", "probabilities": rows}],
            "usage": {"input_tokens": 50, "output_tokens": 0}}


def test_decision_choice_redacts_and_returns_native_probabilities(monkeypatch):
    _fake_redactor(monkeypatch)
    inner = _install(monkeypatch, _Inner(answer=_decision_answer(
        [{"value": "A", "probability": 0.1}, {"value": "B", "probability": 0.9}])))
    out = asyncio.run(llm_router.call_decision_choice(_MSGS, _CHOICES))
    assert out == {"A": 0.1, "B": 0.9}
    url, body, headers = inner.posts[0]
    assert url == "https://api.openai.com/v1/decisions" and headers["Authorization"] == "Bearer ok-fake"
    assert body["model"] == "gpt-6-luna"
    assert TOKEN in body["input"] and REAL not in json.dumps(body, ensure_ascii=False)
    assert body["questions"][0]["instructions"] == "只输出字母"
    assert body["questions"][0]["choices"] == [{"value": "A", "description": "INVENTORY：库存"},
                                               {"value": "B", "description": "COLD：冷库温度"}]


@pytest.mark.parametrize("rows", [
    [{"value": "A", "probability": 0.1}],                                       # 少一个选项
    [{"value": "A", "probability": 0.1}, {"value": "Z", "probability": 0.9}],   # 多出不认识的
    [{"value": "A", "probability": 0.1}, {"value": "B", "probability": 1.5}],   # 越界
    [{"value": "A", "probability": True}, {"value": "B", "probability": 0.9}],  # 布尔冒充
])
def test_decision_choice_rejects_a_bad_distribution(monkeypatch, rows):
    _install(monkeypatch, _Inner(answer=_decision_answer(rows)))
    with pytest.raises(llm_router.LLMChainExhausted, match="invalid_choice_distribution"):
        asyncio.run(llm_router.call_decision_choice(_MSGS, _CHOICES))


@pytest.mark.parametrize("unset", ["LLM_OPENAI_API_KEY", "LLM_EGRESS_PROXY"])
def test_decision_choice_without_key_or_egress_sends_nothing(monkeypatch, unset):
    """2026-11-12: 没有加拿大出口或没有 key 时 Luna 不可用 —— 不发请求, 直接让调用方走阿里云读概率链。"""
    monkeypatch.delenv(unset)
    inner = _install(monkeypatch, _Inner(answer={}))
    assert llm_router.decision_choice_enabled() is False
    with pytest.raises(llm_router.LLMChainExhausted, match="not_enabled"):
        asyncio.run(llm_router.call_decision_choice(_MSGS, _CHOICES))
    assert inner.posts == []


def _predicate_answer(prob, kind="predicate", name="needs_clarification"):
    return {"answers": [{"type": kind, "name": name, "probability": prob}],
            "usage": {"input_tokens": 40, "output_tokens": 0}}


def test_decision_predicate_redacts_and_returns_native_probability(monkeypatch):
    """第三批: 「这一轮是否必须先问」判断题走同一个出口 / 同一套脱敏, 请求里是 predicate 题, 不是 choice。"""
    _fake_redactor(monkeypatch)
    inner = _install(monkeypatch, _Inner(answer=_predicate_answer(0.73)))
    out = asyncio.run(llm_router.call_decision_predicate("先问规则", f"{REAL} 说：查下这批可可粉"))
    assert out == 0.73
    url, body, headers = inner.posts[0]
    assert url == "https://api.openai.com/v1/decisions" and body["model"] == "gpt-6-luna"
    assert TOKEN in body["input"] and REAL not in json.dumps(body, ensure_ascii=False)
    assert body["questions"] == [{"type": "predicate", "name": "needs_clarification", "instructions": "先问规则"}]


@pytest.mark.parametrize("answer", [
    _predicate_answer(1.2),                      # 越界
    _predicate_answer(True),                     # 布尔冒充
    _predicate_answer(None),                     # 缺概率
    _predicate_answer(0.4, kind="choice"),       # 题型对不上
    _predicate_answer(0.4, name="other"),        # 题名对不上
    {"answers": []},
])
def test_decision_predicate_rejects_a_bad_answer(monkeypatch, answer):
    _install(monkeypatch, _Inner(answer=answer))
    with pytest.raises(llm_router.LLMChainExhausted, match="invalid_predicate_probability"):
        asyncio.run(llm_router.call_decision_predicate("先问规则", "查下这批可可粉"))


def test_decision_predicate_without_egress_sends_nothing(monkeypatch):
    monkeypatch.delenv("LLM_EGRESS_PROXY")
    inner = _install(monkeypatch, _Inner(answer=_predicate_answer(0.5)))
    with pytest.raises(llm_router.LLMChainExhausted, match="not_enabled"):
        asyncio.run(llm_router.call_decision_predicate("先问规则", "查下这批可可粉"))
    assert inner.posts == []


def test_matcher_skips_native_choice_entirely_when_not_enabled(monkeypatch):
    from ai.matcher.decision import DecisionMatcher
    monkeypatch.delenv("LLM_EGRESS_PROXY")
    called = []

    async def native(*a, **k):
        called.append(1)
        return {}

    monkeypatch.setattr(llm_router, "call_decision_choice", native)
    rows = [{"intent_code": "A1", "intent_name": "甲"}, {"intent_code": "B1", "intent_name": "乙"}]
    assert asyncio.run(DecisionMatcher()._choose_native([], "AB", rows)) is None
    assert called == []


def test_decision_choice_pair_is_billing_registered_but_on_no_chat_chain():
    pair = llm_router.DECISION_CHOICE_PAIR
    assert pair in llm_router._SAFE_MODELS and pair in llm_router._MINIMAL_SAFE_SET
    assert all(pair not in chain for chain in llm_router.SLOT_MODELS.values())


# ── decision.py: native first, logprob chain as fallback ────────────────────────────────
def test_matcher_choose_uses_native_choice_first_and_maps_codes(monkeypatch):
    from ai.matcher.decision import DecisionMatcher
    rows = [{"intent_code": "INVENTORY", "intent_name": "库存"}, {"intent_code": "COLD", "intent_name": "冷库"}]
    seen = {}

    async def native(messages, choices, *, name="intent", timeout=6.0):
        seen["choices"] = choices
        return {choices[0][0]: 0.2, choices[1][0]: 0.8}

    async def must_not_call(self, *a, **k):
        raise AssertionError("native 成功时不应再走读 logprobs 链")

    monkeypatch.setattr(llm_router, "call_decision_choice", native)
    monkeypatch.setattr(DecisionMatcher, "_call", must_not_call)
    out = asyncio.run(DecisionMatcher().choose("查冷库", "operator", rows))
    letter_to_code = {L: d.split("：", 1)[0] for L, d in seen["choices"]}
    assert out == {letter_to_code[seen["choices"][0][0]]: 0.2, letter_to_code[seen["choices"][1][0]]: 0.8}


def test_matcher_choose_falls_back_to_logprobs_when_native_fails(monkeypatch):
    import math
    from ai.matcher.decision import DecisionMatcher
    rows = [{"intent_code": "INVENTORY", "intent_name": "库存"}, {"intent_code": "COLD", "intent_name": "冷库"}]
    calls = []

    async def native_fails(*a, **k):
        raise llm_router.LLMChainExhausted("decision_choice", ["openai/gpt-6-luna: timeout"])

    async def logprob_call(self, payload, body_validator=None, content_validator=None):
        calls.append(payload)
        top = [{"token": "A", "logprob": math.log(0.7)}, {"token": "B", "logprob": math.log(0.3)}]
        return {"choices": [{"message": {"content": "A"}, "logprobs": {"content": [{"top_logprobs": top}]}}]}

    monkeypatch.setattr(llm_router, "call_decision_choice", native_fails)
    monkeypatch.setattr(DecisionMatcher, "_call", logprob_call)
    out = asyncio.run(DecisionMatcher().choose("查冷库", "operator", rows))
    assert len(calls) == 1 and calls[0]["logprobs"] is True
    assert sorted(out.values()) == pytest.approx([0.3, 0.7])
