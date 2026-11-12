"""LLM_EGRESS_PROXY (2026-11-12): only openai / anthropic leave through the SOCKS egress.

prod 47 cannot reach api.openai.com / api.anthropic.com directly (403 region block / timeout); the
egress is a reverse SOCKS exit on the canada mini, listening on 47's 127.0.0.1. Pinned here:
  · with the variable set, those two providers use the proxied client on every path (chat,
    stream, Decisions); local_canada and everything else stay on the direct client;
  · without the variable, nothing changes (the proxied client is never even built);
  · the proxied client really is a SOCKS transport and still sits behind the redaction wrapper.
"""
import asyncio

import httpx
import pytest

from common import llm_client, llm_router
from common.llm_router import SLOT

pytestmark = pytest.mark.usefixtures("egress_as_factory_request")

PROXY = "socks5h://127.0.0.1:11438"


class _Recorder:
    def __init__(self, name, answer):
        self.name, self.answer, self.urls = name, answer, []

    async def redact_chat_payload(self, payload):
        return payload, {}

    async def post(self, url, json=None, headers=None, timeout=None):  # noqa: A002
        self.urls.append(str(url))
        return httpx.Response(200, json=self.answer, request=httpx.Request("POST", str(url)))


@pytest.fixture(autouse=True)
def _env(monkeypatch):
    for d in (llm_router._CB_FAILURES, llm_router._CB_LAST_FAIL, llm_router._QUOTA_EXHAUSTED_UNTIL):
        d.clear()
    monkeypatch.setenv("LLM_OPENAI_API_KEY", "ok-fake")
    monkeypatch.setenv("LLM_ANTHROPIC_API_KEY", "ak-fake")
    monkeypatch.setenv("LLM_LOCAL_CANADA_API_KEY", "lk-fake")
    monkeypatch.setattr(llm_router, "_log_cache_and_record_budget", lambda *a, **k: None)
    yield
    for d in (llm_router._CB_FAILURES, llm_router._CB_LAST_FAIL, llm_router._QUOTA_EXHAUSTED_UNTIL):
        d.clear()


def _install(monkeypatch, answer):
    direct, proxied = _Recorder("direct", answer), _Recorder("proxied", answer)
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: direct)
    monkeypatch.setattr(llm_router, "get_llm_proxied_http_client", lambda: proxied)
    return direct, proxied


_CHAT_OK = {"choices": [{"message": {"content": "好的，收到"}}]}
_MSG = {"messages": [{"role": "user", "content": "x"}], "max_tokens": 8}


@pytest.mark.parametrize("pair,expect_proxy", [
    (("openai", "gpt-4.1-mini"), True),
    (("local_canada", "qwen3:8b"), False),
])
def test_chat_goes_through_the_proxy_only_for_the_paid_providers(monkeypatch, pair, expect_proxy):
    monkeypatch.setenv("LLM_EGRESS_PROXY", PROXY)
    direct, proxied = _install(monkeypatch, _CHAT_OK)
    monkeypatch.setitem(llm_router.SLOT_MODELS, SLOT.CHAT, [pair])
    asyncio.run(llm_router.call_chain(SLOT.CHAT, dict(_MSG)))
    used, unused = (proxied, direct) if expect_proxy else (direct, proxied)
    assert len(used.urls) == 1 and unused.urls == [], (pair, direct.urls, proxied.urls)


def test_anthropic_chat_and_luna_decision_go_through_the_proxy(monkeypatch):
    monkeypatch.setenv("LLM_EGRESS_PROXY", PROXY)
    anthropic_answer = {"content": [{"type": "text", "text": "好的，收到"}], "stop_reason": "end_turn", "usage": {}}
    direct, proxied = _install(monkeypatch, anthropic_answer)
    monkeypatch.setitem(llm_router.SLOT_MODELS, SLOT.CHAT, [("anthropic", "claude-haiku-5-5")])
    asyncio.run(llm_router.call_chain(SLOT.CHAT, dict(_MSG)))
    assert proxied.urls == ["https://api.anthropic.com/v1/messages"] and direct.urls == []

    proxied.answer = {"answers": [{"type": "choice", "name": "intent",
                                   "probabilities": [{"value": "A", "probability": 1.0}]}], "usage": {}}
    asyncio.run(llm_router.call_decision_choice([{"role": "user", "content": "x"}], [("A", "唯一")]))
    assert proxied.urls[-1] == "https://api.openai.com/v1/decisions" and direct.urls == []


def test_without_the_variable_everything_stays_direct(monkeypatch):
    monkeypatch.delenv("LLM_EGRESS_PROXY", raising=False)
    direct, proxied = _install(monkeypatch, _CHAT_OK)
    monkeypatch.setitem(llm_router.SLOT_MODELS, SLOT.CHAT, [("openai", "gpt-4.1-mini")])
    asyncio.run(llm_router.call_chain(SLOT.CHAT, dict(_MSG)))
    assert len(direct.urls) == 1 and proxied.urls == []


def test_client_selection_table(monkeypatch):
    sentinel_direct, sentinel_proxied = object(), object()
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: sentinel_direct)
    monkeypatch.setattr(llm_router, "get_llm_proxied_http_client", lambda: sentinel_proxied)
    monkeypatch.setenv("LLM_EGRESS_PROXY", PROXY)
    got = {a: llm_router._client_for(a) is sentinel_proxied
           for a in ("openai", "anthropic", "local_canada", "aliyun_a_vl")}
    assert got == {"openai": True, "anthropic": True, "local_canada": False, "aliyun_a_vl": False}
    monkeypatch.setenv("LLM_EGRESS_PROXY", "  ")
    assert llm_router._client_for("openai") is sentinel_direct


def test_proxied_client_is_a_socks_transport_behind_the_redaction_wrapper(monkeypatch):
    pytest.importorskip("socksio")
    monkeypatch.setenv("LLM_EGRESS_PROXY", PROXY)
    monkeypatch.setattr(llm_client, "_proxied_client", None)
    monkeypatch.setattr(llm_client, "_proxied_wrapper", None)
    monkeypatch.setattr(llm_client, "_proxied_url", "")
    wrapper = llm_client.get_llm_proxied_http_client()
    try:
        assert isinstance(wrapper, llm_client._RedactingLLMClient)
        pool = wrapper._inner._transport_for_url(httpx.URL("https://api.openai.com"))._pool
        assert type(pool).__name__ == "AsyncSOCKSProxy"
        # control: the ordinary shared client has no proxy
        plain = llm_client._ensure_inner_client()._transport_for_url(httpx.URL("https://api.openai.com"))._pool
        assert type(plain).__name__ != "AsyncSOCKSProxy"
    finally:
        asyncio.run(wrapper._inner.aclose())


def test_without_the_variable_the_proxied_getter_falls_back_to_the_shared_client(monkeypatch):
    monkeypatch.delenv("LLM_EGRESS_PROXY", raising=False)
    assert llm_client.get_llm_proxied_http_client() is llm_client.get_llm_http_client()
