"""Verify the actual chart router chain and its dedicated Aurora fallback."""

import asyncio
import datetime
import logging

import httpx
import pytest

from common import llm_router
from llm import f006_mini_provider

pytestmark = pytest.mark.usefixtures("egress_as_factory_request")

AURORA = ("aurora_chart", "qwen3:14b")
ALIYUN_A = ("aliyun_a_vl", "qwen3.8-flash")
BASE_URL = "http://127.0.0.1:11440/v1"
PAYLOAD = {"messages": [{"role": "user", "content": "return a JSON object"}]}
CONTENT = '{"selected_ids":["chart_approved"]}'


@pytest.fixture
def configured_router(monkeypatch):
    monkeypatch.setattr(llm_router, "_CB_FAILURES", {})
    monkeypatch.setattr(llm_router, "_CB_LAST_FAIL", {})
    monkeypatch.setattr(llm_router, "_QUOTA_EXHAUSTED_UNTIL", {})
    monkeypatch.setattr(llm_router, "_EMPTY_KEY_WARNED", set())
    monkeypatch.setattr(llm_router, "_today", lambda: datetime.date(2026, 11, 20))
    monkeypatch.setenv("LLM_ALIYUN_A_API_KEY", "a-test")
    monkeypatch.setenv("LLM_ALIYUN_B_API_KEY", "b-test")
    monkeypatch.setenv("LLM_LOCAL_CANADA_API_KEY", "canada-test")
    monkeypatch.setenv("LLM_AURORA_CHART_BASE_URL", BASE_URL)
    monkeypatch.setenv("LLM_AURORA_CHART_TOKEN", "aurora-test")


def test_chart_chain_has_only_aurora_then_aliyun_a_and_f006_stays_dedicated():
    assert llm_router.SLOT_MODELS[llm_router.SLOT.CHART] == [AURORA, ALIYUN_A]
    assert all(AURORA not in chain for slot, chain in llm_router.SLOT_MODELS.items()
               if slot is not llm_router.SLOT.CHART)
    assert llm_router.SLOT_MODELS[llm_router.SLOT.CHAT][:3] == [
        ("local_canada", "qwen3:8b"), ALIYUN_A, ("aliyun_b_vl", "qwen3.8-flash"),
    ]
    assert f006_mini_provider.PROVIDER == "mac-mini"
    assert f006_mini_provider.MODEL == "qwen3.8:27b"
    assert f006_mini_provider.OLLAMA_URL == "http://127.0.0.1:11435/api/chat"
    assert AURORA not in llm_router._SAFE_MODELS
    assert llm_router._refuse_reason(AURORA[0], "unapproved-model") == "not_allowlisted"


class _ChartClient:
    def __init__(self, aurora_result=200):
        self.calls = []
        self.aurora_result = aurora_result

    async def post(self, url, *, headers, json, timeout):
        self.calls.append((url, headers["Authorization"], json["model"], timeout.read))
        if url.startswith(BASE_URL) and self.aurora_result == "timeout":
            await asyncio.sleep(1)
            pytest.fail("Aurora request should be cancelled at its attempt timeout")
        if url.startswith(BASE_URL) and self.aurora_result == 429:
            return httpx.Response(429, json={"error": "aurora_busy"})
        return httpx.Response(200, json={"choices": [{"message": {"content": CONTENT}}]})


async def _ask():
    return await llm_router.call_chain(
        llm_router.SLOT.CHART, PAYLOAD, timeout=0.08, total_timeout=0.5,
        content_validator=lambda content: None if content == CONTENT else "unapproved_chart",
    )


@pytest.mark.asyncio
async def test_configured_chart_uses_aurora_and_its_separate_token(configured_router, monkeypatch):
    client = _ChartClient()
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    monkeypatch.setattr(llm_router, "_post_aurora_chart", client.post)
    assert (await _ask())["choices"][0]["message"]["content"] == CONTENT
    assert client.calls == [(BASE_URL + "/chat/completions", "Bearer aurora-test", AURORA[1], 0.08)]


@pytest.mark.asyncio
async def test_chart_caps_long_aurora_attempts_at_the_measured_budget(configured_router, monkeypatch):
    client = _ChartClient()
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    monkeypatch.setattr(llm_router, "_post_aurora_chart", client.post)
    await llm_router.call_chain(llm_router.SLOT.CHART, PAYLOAD, timeout=120, total_timeout=120)
    assert client.calls == [(BASE_URL + "/chat/completions", "Bearer aurora-test", AURORA[1], 52.0)]


@pytest.mark.asyncio
async def test_aurora_transport_ignores_environment_proxies(configured_router, monkeypatch):
    monkeypatch.setenv("HTTP_PROXY", "http://192.0.2.1:8080")
    monkeypatch.delenv("NO_PROXY", raising=False)
    actual_client = httpx.AsyncClient
    requests = []

    def handle(request):
        requests.append(request)
        return httpx.Response(200, json={"choices": [{"message": {"content": CONTENT}}]})

    def direct_client(**options):
        assert options["trust_env"] is False
        assert options["follow_redirects"] is False
        return actual_client(transport=httpx.MockTransport(handle), **options)

    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: _ChartClient())
    monkeypatch.setattr(llm_router.httpx, "AsyncClient", direct_client)
    assert (await _ask())["choices"][0]["message"]["content"] == CONTENT
    assert len(requests) == 1
    assert str(requests[0].url) == BASE_URL + "/chat/completions"
    assert requests[0].headers["Authorization"] == "Bearer aurora-test"


@pytest.mark.parametrize(("name", "value"), [
    ("LLM_AURORA_CHART_BASE_URL", None),
    ("LLM_AURORA_CHART_TOKEN", None),
    ("LLM_AURORA_CHART_BASE_URL", "http://127.0.0.1:11435/v1"),
    ("LLM_AURORA_CHART_BASE_URL", "https://example.invalid/v1"),
])
@pytest.mark.asyncio
async def test_missing_or_invalid_aurora_config_skips_and_warns_once(
    configured_router, monkeypatch, caplog, name, value,
):
    if value is None:
        monkeypatch.delenv(name)
    else:
        monkeypatch.setenv(name, value)
    client = _ChartClient()
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    await _ask()
    await _ask()
    assert [call[1:3] for call in client.calls] == [("Bearer a-test", ALIYUN_A[1])] * 2
    warnings = [record for record in caplog.records
                if record.levelno == logging.WARNING and "LLM_AURORA_CHART_BASE_URL" in record.message]
    assert len(warnings) == 1


@pytest.mark.parametrize("failure", ["timeout", 429])
@pytest.mark.asyncio
async def test_chart_falls_back_to_aliyun_a_after_aurora_timeout_or_busy(configured_router, monkeypatch, failure):
    client = _ChartClient(failure)
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    monkeypatch.setattr(llm_router, "_post_aurora_chart", client.post)
    assert (await _ask())["choices"][0]["message"]["content"] == CONTENT
    assert [call[1:3] for call in client.calls] == [
        ("Bearer aurora-test", AURORA[1]), ("Bearer a-test", ALIYUN_A[1]),
    ]
    expected_failures = 1 if failure == "timeout" else 0
    assert llm_router._CB_FAILURES.get("/".join(AURORA), 0) == expected_failures
