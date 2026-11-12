"""Local hop guard (2026-11-12): one Mac mini serves qwen3:8b; ollama runs it serially.

Measured on 47 the same day: 4 concurrent CHAT calls took 0.52 / 1.01 / 1.52 / 2.05 s (queueing).
So each worker allows at most LLM_LOCAL_MAX_INFLIGHT local requests at a time; when full the router
skips local (``local_busy``, no circuit-breaker strike) and moves on to aliyun instead of queueing.
"""
import asyncio

import httpx
import pytest

from common import llm_router
from common.llm_router import SLOT

pytestmark = pytest.mark.usefixtures("egress_as_factory_request")

LOCAL = ("local_canada", "qwen3:8b")
CLOUD = ("aliyun_a_vl", "qwen3.8-flash")


@pytest.fixture(autouse=True)
def _env(monkeypatch):
    for d in (llm_router._CB_FAILURES, llm_router._CB_LAST_FAIL, llm_router._QUOTA_EXHAUSTED_UNTIL):
        d.clear()
    monkeypatch.setenv("LLM_LOCAL_CANADA_API_KEY", "lk")
    monkeypatch.setenv("LLM_ALIYUN_A_API_KEY", "ak")
    monkeypatch.setattr(llm_router, "_log_cache_and_record_budget", lambda *a, **k: None)
    monkeypatch.setattr(llm_router, "_local_inflight", 0)
    monkeypatch.setitem(llm_router.SLOT_MODELS, SLOT.CHAT, [LOCAL, CLOUD])
    yield
    for d in (llm_router._CB_FAILURES, llm_router._CB_LAST_FAIL, llm_router._QUOTA_EXHAUSTED_UNTIL):
        d.clear()


class _Client:
    def __init__(self, local_delay=0.0, local_raises=None):
        self.calls, self.timeouts = [], []
        self.local_delay, self.local_raises = local_delay, local_raises

    async def post(self, url, json=None, headers=None, timeout=None):  # noqa: A002
        model = json["model"]
        self.calls.append(model)
        self.timeouts.append((model, timeout.read if timeout is not None else None))
        if model == LOCAL[1]:
            if self.local_raises:
                raise self.local_raises
            await asyncio.sleep(self.local_delay)
        return httpx.Response(200, json={"model": model, "choices": [{"message": {"content": f"答复来自 {model}"}}]},
                              request=httpx.Request("POST", str(url)))


def _install(monkeypatch, client):
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    return client


_PAYLOAD = {"messages": [{"role": "user", "content": "说一句话"}], "max_tokens": 20}


def test_busy_local_overflows_to_aliyun_instead_of_queueing(monkeypatch):
    client = _install(monkeypatch, _Client(local_delay=0.2))

    async def three():
        return await asyncio.gather(*(llm_router.call_chain(SLOT.CHAT, dict(_PAYLOAD)) for _ in range(3)))

    models = sorted(b["model"] for b in asyncio.run(three()))
    assert models == sorted([LOCAL[1], CLOUD[1], CLOUD[1]]), models
    assert client.calls.count(LOCAL[1]) == 1, "本地被并发打了不止一次 —— 上限没生效"
    assert llm_router._CB_FAILURES.get("local_canada/qwen3:8b", 0) == 0, "忙不是故障, 不许记熔断"
    assert llm_router._local_inflight == 0, "并发名额没有归还"


def test_inflight_slot_is_released_after_an_exception(monkeypatch):
    _install(monkeypatch, _Client(local_raises=httpx.ConnectError("mini down")))
    out = asyncio.run(llm_router.call_chain(SLOT.CHAT, dict(_PAYLOAD)))
    assert out["model"] == CLOUD[1]
    assert llm_router._local_inflight == 0
    # control: the next call tries local again (the slot really was freed)
    client = _install(monkeypatch, _Client())
    assert asyncio.run(llm_router.call_chain(SLOT.CHAT, dict(_PAYLOAD)))["model"] == LOCAL[1]
    assert client.calls == [LOCAL[1]]


def test_local_attempt_timeout_is_capped(monkeypatch):
    client = _install(monkeypatch, _Client())
    monkeypatch.setattr(llm_router, "_LOCAL_ATTEMPT_TIMEOUT_S", 2.5)
    asyncio.run(llm_router.call_chain(SLOT.CHAT, dict(_PAYLOAD), timeout=30.0))
    assert client.timeouts == [(LOCAL[1], 2.5)], client.timeouts


def test_busy_shows_in_the_exhausted_message(monkeypatch):
    monkeypatch.setitem(llm_router.SLOT_MODELS, SLOT.CHAT, [LOCAL])
    monkeypatch.setattr(llm_router, "_local_inflight", llm_router._LOCAL_MAX_INFLIGHT)
    _install(monkeypatch, _Client())
    with pytest.raises(llm_router.LLMChainExhausted, match="local_busy"):
        asyncio.run(llm_router.call_chain(SLOT.CHAT, dict(_PAYLOAD)))
