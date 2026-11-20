"""Verify the two chart routes, their budgets, and Aurora's F006 priority."""

import asyncio
import datetime
import json as jsonlib
import logging
from types import SimpleNamespace

import httpx
import pytest

from common import llm_router
from llm import f006_mini_provider

pytestmark = pytest.mark.usefixtures("egress_as_factory_request")

AURORA = ("aurora_chart", "qwen3:14b")
REX = ("local_canada", "qwen3:8b")
ALIYUN_A = ("aliyun_a_vl", "qwen3.8-flash")
BASE_URL = "http://127.0.0.1:11440/v1"
REX_URL = "http://127.0.0.1:11437/v1"
PAYLOAD = {"messages": [{"role": "user", "content": "return a JSON object"}]}
CONTENT = '{"selected_ids":["chart_approved"]}'
CATALOG = [{"id": "production", "familyId": "production.output", "domain": "生产",
            "metric": "产量", "chartType": "line"}]


@pytest.fixture
def configured_router(monkeypatch):
    monkeypatch.setattr(llm_router, "_CB_FAILURES", {})
    monkeypatch.setattr(llm_router, "_CB_LAST_FAIL", {})
    monkeypatch.setattr(llm_router, "_QUOTA_EXHAUSTED_UNTIL", {})
    monkeypatch.setattr(llm_router, "_EMPTY_KEY_WARNED", set())
    monkeypatch.setattr(llm_router, "_today", lambda: datetime.date(2026, 11, 20))
    monkeypatch.setenv("LLM_ALIYUN_A_API_KEY", "a-test")
    monkeypatch.setenv("LLM_ALIYUN_B_API_KEY", "b-test")
    monkeypatch.setenv("LLM_CHART_ALIYUN_A_FREE_ONLY_CONFIRMED", "1")
    monkeypatch.delenv("SMARTBI_CHART_PLANNER_BACKGROUND", raising=False)
    monkeypatch.setenv("LLM_LOCAL_CANADA_BASE_URL", REX_URL)
    monkeypatch.setenv("LLM_LOCAL_CANADA_API_KEY", "canada-test")
    monkeypatch.setenv("LLM_AURORA_CHART_BASE_URL", BASE_URL)
    monkeypatch.setenv("LLM_AURORA_CHART_TOKEN", "aurora-test")
    monkeypatch.setenv("NO_PROXY", "127.0.0.1")
    monkeypatch.setattr(llm_router, "_f006_lease_is_idle", lambda: True)


def test_shared_chart_chain_and_f006_stay_on_their_original_routes():
    assert llm_router.SLOT_MODELS[llm_router.SLOT.CHART] == [
        REX, ALIYUN_A, ("aliyun_b_vl", "qwen3.8-flash"),
    ]
    assert all(AURORA not in chain for chain in llm_router.SLOT_MODELS.values())
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
        chart_background=True,
    )


@pytest.mark.asyncio
async def test_configured_chart_uses_aurora_and_its_separate_token(configured_router, monkeypatch):
    client = _ChartClient()
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    assert (await _ask())["choices"][0]["message"]["content"] == CONTENT
    assert client.calls == [(BASE_URL + "/chat/completions", "Bearer aurora-test", AURORA[1], 0.08)]


@pytest.mark.asyncio
async def test_chart_caps_long_aurora_attempts_at_the_measured_budget(configured_router, monkeypatch):
    client = _ChartClient()
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    await llm_router.call_chain(llm_router.SLOT.CHART, PAYLOAD, timeout=120, total_timeout=120,
                                chart_background=True)
    assert client.calls == [(BASE_URL + "/chat/completions", "Bearer aurora-test", AURORA[1], 52.0)]


@pytest.mark.asyncio
async def test_chart_skips_aurora_when_proxy_would_intercept_loopback(configured_router, monkeypatch, caplog):
    monkeypatch.setenv("HTTP_PROXY", "http://192.0.2.1:8080")
    monkeypatch.setenv("NO_PROXY", "localhost")
    client = _ChartClient()
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    with pytest.raises(llm_router.LLMChainExhausted, match="empty_api_key"):
        await _ask()
    assert client.calls == []
    assert "回环代理豁免" in caplog.text


@pytest.mark.asyncio
async def test_proxied_environment_uses_shared_redaction_with_loopback_exemption(configured_router, monkeypatch):
    from common.llm_client import _RedactingLLMClient

    monkeypatch.setenv("ALL_PROXY", "http://192.0.2.1:8080")
    requests = []

    def handle(request):
        requests.append(request)
        return httpx.Response(200, json={"choices": [{"message": {"content": CONTENT}}]})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as inner:
        wrapper = _RedactingLLMClient(inner)
        monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: wrapper)
        await llm_router.call_chain(llm_router.SLOT.CHART, {
            "messages": [{"role": "user", "content": "return JSON，联系人电话 13912345678"}],
        }, chart_background=True)
    assert len(requests) == 1
    assert str(requests[0].url) == BASE_URL + "/chat/completions"
    assert requests[0].headers["Authorization"] == "Bearer aurora-test"
    assert b"13912345678" not in requests[0].content


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
    for _ in range(2):
        with pytest.raises(llm_router.LLMChainExhausted, match="empty_api_key"):
            await _ask()
    assert client.calls == []
    warnings = [record for record in caplog.records
                if record.levelno == logging.WARNING and "LLM_AURORA_CHART_BASE_URL" in record.message]
    assert len(warnings) == 1


@pytest.mark.parametrize("failure", ["timeout", 429])
@pytest.mark.asyncio
async def test_chart_reports_aurora_timeout_or_busy_without_switching_provider(configured_router, monkeypatch, failure):
    client = _ChartClient(failure)
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    with pytest.raises(llm_router.LLMChainExhausted, match="timeout" if failure == "timeout" else "local_busy"):
        await _ask()
    assert [call[1:3] for call in client.calls] == [("Bearer aurora-test", AURORA[1])]
    expected_failures = 1 if failure == "timeout" else 0
    assert llm_router._CB_FAILURES.get("/".join(AURORA), 0) == expected_failures


@pytest.mark.asyncio
async def test_foreground_plan_ignores_shared_chart_cloud_candidates(configured_router, monkeypatch):
    client = _ChartClient()
    monkeypatch.setitem(llm_router.SLOT_MODELS, llm_router.SLOT.CHART, [ALIYUN_A, REX])
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    assert (await llm_router.call_chain(llm_router.SLOT.CHART, PAYLOAD, chart_background=False))["choices"]
    assert client.calls == [(REX_URL + "/chat/completions", "Bearer canada-test", REX[1], 15.0)]


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", ["f006_lease", "invalid_ids", "missing_config"])
async def test_background_planner_uses_rules_when_aurora_cannot_plan(configured_router, monkeypatch, failure):
    from smartbi.services.factory_charts import conversation

    calls = []
    aurora_url = BASE_URL + "/chat/completions"

    class Client:
        async def post(self, url, *, headers, json, timeout):
            calls.append(url)
            return httpx.Response(200, json={"choices": [{"message": {"content": CONTENT}}]})

    if failure == "missing_config":
        monkeypatch.delenv("LLM_AURORA_CHART_TOKEN")
    monkeypatch.setattr(llm_router, "_f006_lease_is_idle", lambda: failure != "f006_lease")
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: Client())
    chosen, result = await conversation.plan("今天的生产产量", CATALOG, background=True)

    assert chosen == ["production"]
    assert result["mode"] == "rule"
    assert calls == ([aurora_url] if failure == "invalid_ids" else [])
    assert not llm_router._CB_FAILURES.get("/".join(AURORA), 0)


class _ScaledWaits:
    """Keep real event-loop cancellation while shortening the model and deadline waits."""

    scale = 0.02

    def __init__(self):
        self.timeouts = []

    async def wait_for(self, awaitable, timeout):
        self.timeouts.append(timeout)
        return await asyncio.wait_for(awaitable, timeout * self.scale)

    def __getattr__(self, name):
        return getattr(asyncio, name)


class _PlanningClient:
    def __init__(self, model_seconds):
        self.model_seconds = model_seconds
        self.calls = []
        self.cancelled = False

    async def post(self, url, *, headers, json, timeout):
        self.calls.append((url, json["model"], timeout.read))
        try:
            if self.model_seconds is None:
                await asyncio.Future()
            else:
                await asyncio.sleep(self.model_seconds * _ScaledWaits.scale)
        except asyncio.CancelledError:
            self.cancelled = True
            raise
        candidate = jsonlib.loads(json["messages"][1]["content"])["catalog"][0]["id"]
        content = jsonlib.dumps({"selected_ids": [candidate]})
        return httpx.Response(200, json={"choices": [{"message": {"content": content}}]})


@pytest.fixture
def timed_planner(configured_router, monkeypatch):
    from smartbi.services.factory_charts import conversation

    planner_waits, router_waits = _ScaledWaits(), _ScaledWaits()
    monkeypatch.setattr(conversation, "asyncio", planner_waits)
    monkeypatch.setattr(llm_router, "asyncio", router_waits)
    monkeypatch.setattr(conversation, "_planner_monotonic", lambda: 0.0)
    monkeypatch.setattr(llm_router, "_LOCAL_ATTEMPT_TIMEOUT_S", 8.0)
    conversation._PLAN_CACHE.clear()
    yield conversation, planner_waits, router_waits
    conversation._PLAN_CACHE.clear()


@pytest.mark.asyncio
async def test_main_planner_accepts_a_rex_result_within_20_seconds(timed_planner, monkeypatch):
    conversation, planner_waits, router_waits = timed_planner
    client = _PlanningClient(model_seconds=11)
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)

    chosen, result = await conversation.plan("今天的生产产量", CATALOG)

    assert (chosen, result["mode"]) == (["production"], "ai")
    assert client.calls == [(REX_URL + "/chat/completions", REX[1], 15.0)]
    assert router_waits.timeouts == [15.0]
    assert planner_waits.timeouts == [20.0]


@pytest.mark.asyncio
@pytest.mark.parametrize(("background", "url", "model", "attempt", "deadline"), [
    pytest.param(False, REX_URL, REX[1], 15.0, 20.0, id="rex"),
    pytest.param(True, BASE_URL, AURORA[1], 52.0, 60.0, id="aurora"),
])
async def test_planner_timeout_returns_rule(timed_planner, monkeypatch, background, url, model, attempt, deadline):
    conversation, planner_waits, router_waits = timed_planner
    client = _PlanningClient(model_seconds=None)
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)

    chosen, result = await conversation.plan("今天的生产产量", CATALOG, background=background)

    assert (chosen, result["mode"]) == (["production"], "rule")
    assert client.calls == [(url + "/chat/completions", model, attempt)]
    assert client.cancelled
    assert router_waits.timeouts == [attempt]
    assert planner_waits.timeouts == [deadline]


@pytest.mark.asyncio
async def test_background_planner_accepts_aurora_after_12_seconds(timed_planner, monkeypatch):
    conversation, planner_waits, router_waits = timed_planner
    client = _PlanningClient(model_seconds=18)
    monkeypatch.setenv("SMARTBI_CHART_PLANNER_BACKGROUND", "1")
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)

    chosen, result = await conversation.plan("今天的生产产量", CATALOG)

    assert (chosen, result["mode"]) == (["production"], "ai")
    assert client.calls == [(BASE_URL + "/chat/completions", AURORA[1], 52.0)]
    assert router_waits.timeouts == [52.0]
    assert planner_waits.timeouts == [60.0]


@pytest.mark.asyncio
@pytest.mark.parametrize("background", [False, True])
async def test_http_timeout_with_no_matching_chart_reports_unavailable(configured_router, monkeypatch, background):
    from smartbi.services.factory_charts import conversation

    class TimedOutClient:
        async def post(self, *_args, **_kwargs):
            if background:
                return httpx.Response(504, json={"error": "inference_timeout"})
            raise httpx.ReadTimeout("test timeout")

    monkeypatch.setattr(llm_router, "get_llm_http_client", TimedOutClient)

    chosen, result = await conversation.plan("企鹅生活在哪里？", CATALOG, background=background)

    assert chosen == []
    assert result == {"mode": "unavailable", "reason": "AI 规划超时，关键词规则未命中"}


@pytest.mark.asyncio
@pytest.mark.parametrize("consumer", ["recommender", "insight", "ranking"])
async def test_shared_chart_callers_keep_cloud_fallback_and_timeouts(configured_router, monkeypatch, consumer):
    from smartbi.services.chart_recommender import ChartRecommender
    from smartbi.services.factory_charts import ranking
    from smartbi.services.insights.chart_insight_service import ChartInsightContext, ChartInsightService

    monkeypatch.setenv("SMARTBI_CHART_PLANNER_BACKGROUND", "1")
    monkeypatch.delenv("CHART_INSIGHT_LLM_SLOT", raising=False)
    monkeypatch.setattr(llm_router, "_LOCAL_ATTEMPT_TIMEOUT_S", 8.0)
    outer_timeouts = []

    async def observe_outer_timeout(awaitable, timeout):
        outer_timeouts.append(timeout)
        return await asyncio.wait_for(awaitable, timeout=timeout)

    monkeypatch.setattr(ranking, "asyncio", SimpleNamespace(wait_for=observe_outer_timeout))
    replies = {
        "recommender": {"recommendations": [{"chart_type": "line", "title": "产量趋势"}]},
        "insight": {"claims": [], "finding": None},
        "ranking": {"rankings": [{"id": "production", "reason_code": "observe"}]},
    }
    calls = []

    class Client:
        async def post(self, url, *, headers, json, timeout):
            auth = headers["Authorization"]
            calls.append((auth, json["model"], timeout.read))
            if auth != "Bearer b-test":
                return httpx.Response(503, json={"error": "test provider unavailable"})
            return httpx.Response(200, json={"choices": [{"message": {
                "content": jsonlib.dumps(replies[consumer]),
            }}]})

    monkeypatch.setattr(llm_router, "get_llm_http_client", Client)
    if consumer == "recommender":
        recommender = ChartRecommender()
        monkeypatch.setattr(recommender, "settings", SimpleNamespace(llm_api_key="enabled-for-test"))
        recs = await recommender.recommend({"row_count": 100}, use_cache=False)
        result = [rec.title for rec in recs]
    elif consumer == "insight":
        context = ChartInsightContext(
            chart_type="LINE", x_dim="time", y_metric="quantity", aggregation="sum",
            domain="factory", data_pattern="trend:up", permission_tier="finance_visible",
            factory_id="TEST", series_values=[10, 20], series_labels=["一月", "二月"],
        )
        result = await ChartInsightService(pool=None, budget_tracker=None)._call_llm(context)
    else:
        ranked, meta = await ranking.rank([{**CATALOG[0], "priority": "required"}], use_ai=True)
        result = (meta["mode"], ranked[0].get("aiReason"))

    assert calls == [
        ("Bearer canada-test", "qwen3:8b", 8.0),
        ("Bearer a-test", "qwen3.8-flash", 30.0),
        ("Bearer b-test", "qwen3.8-flash", 30.0),
    ]
    assert outer_timeouts == ([12] if consumer == "ranking" else [])
    assert result == {
        "recommender": ["产量趋势"],
        "insight": replies["insight"],
        "ranking": ("ai", ranking.REASONS["observe"]),
    }[consumer]


@pytest.mark.asyncio
async def test_planner_cache_keeps_main_rules_separate_from_background_ai(configured_router, monkeypatch):
    from smartbi.services.factory_charts import conversation

    conversation._PLAN_CACHE.clear()
    client = _PlanningClient(model_seconds=0)
    monkeypatch.delenv("LLM_LOCAL_CANADA_API_KEY")
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    try:
        _, main = await conversation.plan("今天的生产产量", CATALOG, factory_id="F001")
        _, background = await conversation.plan("今天的生产产量", CATALOG, factory_id="F001", background=True)
        _, cached_main = await conversation.plan("今天的生产产量", CATALOG, factory_id="F001")
    finally:
        conversation._PLAN_CACHE.clear()

    assert (main["mode"], main["cache"]) == ("rule", "miss")
    assert (background["mode"], background["cache"]) == ("ai", "miss")
    assert (cached_main["mode"], cached_main["cache"]) == ("rule", "hit")
    assert [call[:2] for call in client.calls] == [(BASE_URL + "/chat/completions", AURORA[1])]


@pytest.mark.asyncio
async def test_aurora_yields_in_flight_when_f006_acquires_lease(configured_router, monkeypatch):
    cancelled = asyncio.Event()
    lease_held = False

    class LeaseDuringRequest(_ChartClient):
        async def post(self, url, **kwargs):
            nonlocal lease_held
            if url.startswith(BASE_URL):
                self.calls.append(url)
                lease_held = True
                try:
                    await asyncio.Future()
                finally:
                    cancelled.set()
            return await super().post(url, **kwargs)

    client = LeaseDuringRequest()
    monkeypatch.setattr(llm_router, "_f006_lease_is_idle", lambda: not lease_held)
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    with pytest.raises(llm_router.LLMChainExhausted, match="f006_priority"):
        await llm_router.call_chain(llm_router.SLOT.CHART, PAYLOAD, timeout=1, total_timeout=2,
                                    chart_background=True)
    assert cancelled.is_set()
    assert client.calls == [BASE_URL + "/chat/completions"]
    assert "/".join(AURORA) not in llm_router._CB_FAILURES


def test_aurora_probe_observes_an_actual_f006_lease(tmp_path, monkeypatch, capsys):
    from llm.f006_mini_lease import acquire_host_lease

    if not llm_router.Path("/proc/locks").is_file():
        pytest.skip("requires Linux /proc/locks")
    monkeypatch.setattr(llm_router.tempfile, "gettempdir", lambda: str(tmp_path))
    assert llm_router._f006_lease_is_idle()
    lease = acquire_host_lease()
    try:
        assert not llm_router._f006_lease_is_idle()
    finally:
        lease.close()
    assert llm_router._f006_lease_is_idle()
    with capsys.disabled():
        print("test_aurora_probe_observes_an_actual_f006_lease: /proc/locks verified idle -> held -> idle")
