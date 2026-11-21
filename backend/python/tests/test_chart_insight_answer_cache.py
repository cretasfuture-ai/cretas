"""Cache and progress contracts through the authenticated chart-insight POST."""
import asyncio
from contextlib import asynccontextmanager
import json
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock

import jwt
import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from auth_middleware import JWTAuthMiddleware
from smartbi.api import chart_insight
from smartbi.services.insights import chart_insight_service
from common import smartbi_analysis_cache as storage
from tests.analysis_cache_fixtures import MemoryCache, analysis_cache  # noqa: F401


PATH = "/api/smartbi/chart-insight"
SECRET = "local-chart-insight-cache-signing-secret"
FACTORY = "CACHE-TEST-A"
OTHER_FACTORY = "CACHE-TEST-B"


def headers(factory=FACTORY, role="finance_manager", *, stream=False):
    bearer = jwt.encode(
        {"factoryId": factory, "role": role, "userId": 42, "exp": int(time.time()) + 120},
        SECRET, algorithm="HS256",
    )
    return {"Authorization": f"Bearer {bearer}", "Accept": "text/event-stream" if stream else "application/json"}


def payload(**overrides):
    return {
        "factory_id": FACTORY, "chart_type": "BAR", "x_dim": "product", "y_metric": "quantity",
        "domain": "factory", "aggregation": "sum", "data_pattern": "rank",
        "series_values": [60, 40], "series_labels": ["甲", "乙"],
        "data_version": '["2026-11-21T03:04:00Z","2026-11-01/2026-11-21"]',
        **overrides,
    }


def model_result(ctx):
    label = ctx.series_labels[0]
    share = round(ctx.series_values[0] / sum(ctx.series_values) * 100, 1)
    marker = "金额权限内" if ctx.permission_tier == "finance_visible" else "数量"
    return {
        "claims": [{"entity": label, "stat_type": "share", "value": share}],
        "finding": f"{label}占{share:.1f}%，按{marker}口径统计。",
        "suggestion": "可核对两种产品的报工范围是否一致。",
    }


@pytest.fixture
async def entry(monkeypatch, analysis_cache):
    monkeypatch.delenv("CHART_INSIGHT_DETERMINISTIC_FIRST", raising=False)
    budget = SimpleNamespace(
        check_budget=AsyncMock(return_value=SimpleNamespace(blocked=False)),
        consume=AsyncMock(),
    )
    service = chart_insight_service.ChartInsightService(pool=None, budget_tracker=budget)
    model = AsyncMock(side_effect=model_result)
    monkeypatch.setattr(service, "_call_llm", model)
    monkeypatch.setattr(chart_insight, "_service", service)
    app = FastAPI()
    app.add_middleware(JWTAuthMiddleware, jwt_secret=SECRET)
    app.include_router(chart_insight.router, prefix="/api/smartbi")
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        yield client, model, budget, service


def answer(response):
    assert response.status_code == 200
    result = response.json()
    assert result["success"] and result["data"]
    return result["data"]


async def test_same_chart_hits_and_body_order_is_irrelevant(entry):
    client, model, budget, _ = entry
    body = payload()
    first = answer(await client.post(PATH, json=body, headers=headers()))
    again = answer(await client.post(PATH, json=dict(reversed(list(body.items()))), headers=headers()))

    assert first["source"] == "llm"
    assert first["cacheHit"] is False
    assert again == {**first, "cacheHit": True}
    model.assert_awaited_once()
    budget.consume.assert_awaited_once()


async def test_same_chart_cannot_hit_another_factory(entry):
    client, model, _, _ = entry
    factory_a = answer(await client.post(PATH, json=payload(), headers=headers()))
    factory_b = answer(await client.post(PATH, json=payload(factory_id=OTHER_FACTORY), headers=headers(OTHER_FACTORY)))
    forged = await client.post(PATH, json=payload(), headers=headers(OTHER_FACTORY))

    assert factory_a["cacheHit"] is False and factory_b["cacheHit"] is False
    assert factory_a["input_hash"] != factory_b["input_hash"]
    assert forged.status_code == 403
    assert [call.args[0].factory_id for call in model.await_args_list] == [FACTORY, OTHER_FACTORY]


async def test_same_chart_cannot_hit_a_higher_permission_entry(entry):
    client, model, _, _ = entry
    body = payload(permission_tier="finance_visible")
    visible = answer(await client.post(PATH, json=body, headers=headers()))
    hidden = answer(await client.post(PATH, json=body, headers=headers(role="warehouse_worker")))
    again = answer(await client.post(PATH, json=body, headers=headers(role="warehouse_worker")))

    assert visible["cacheHit"] is False and hidden["cacheHit"] is False
    assert visible["input_hash"] != hidden["input_hash"]
    assert "金额权限内" not in hidden["finding"]
    assert again == {**hidden, "cacheHit": True}
    assert [call.args[0].permission_tier for call in model.await_args_list] == ["finance_visible", "finance_hidden"]


@pytest.mark.parametrize("changes", [
    {"series_values": [61, 39]},
    {"series_labels": ["甲（新规格）", "乙"]},
    {"data_version": '["2026-11-21T04:00:00Z","2026-11-01/2026-11-21"]'},
    {"data_version": '["2026-11-21T03:04:00Z","2026-11-03/2026-11-21"]'},
], ids=["number", "label", "snapshot", "date-range"])
async def test_chart_version_change_misses(entry, changes):
    client, model, _, _ = entry
    first = answer(await client.post(PATH, json=payload(), headers=headers()))
    changed = answer(await client.post(PATH, json=payload(**changes), headers=headers()))
    assert first["cacheHit"] is False and changed["cacheHit"] is False
    assert first["input_hash"] != changed["input_hash"]
    assert model.await_count == 2


async def test_process_replacement_keeps_cached_answer_and_entry_has_no_expiry(entry, analysis_cache, monkeypatch):
    client, model, budget, _ = entry
    initial = answer(await client.post(PATH, json=payload(), headers=headers()))
    replacement = chart_insight_service.ChartInsightService(pool=None, budget_tracker=budget)
    monkeypatch.setattr(replacement, "_call_llm", model)
    monkeypatch.setattr(chart_insight, "_service", replacement)
    if not isinstance(analysis_cache, MemoryCache):
        assert await analysis_cache.ttl(storage._VALUES) == -1
    assert answer(await client.post(PATH, json=payload(), headers=headers()))["cacheHit"] is True
    assert answer(await client.post(PATH, json=payload(), headers=headers())) == {**initial, "cacheHit": True}
    model.assert_awaited_once()


async def test_concurrent_same_key_uses_one_model_call(entry):
    client, model, budget, _ = entry
    entered = asyncio.Event()
    ready = asyncio.Event()

    async def delayed(ctx):
        entered.set()
        await ready.wait()
        return model_result(ctx)

    model.side_effect = delayed
    first = asyncio.create_task(client.post(PATH, json=payload(), headers=headers()))
    await asyncio.wait_for(entered.wait(), 1)
    second = asyncio.create_task(client.post(PATH, json=payload(), headers=headers()))
    await asyncio.sleep(0)
    ready.set()
    results = [answer(response) for response in await asyncio.gather(first, second)]
    assert [result["cacheHit"] for result in results] == [False, True]
    model.assert_awaited_once()
    budget.consume.assert_awaited_once()


async def test_lease_is_renewed_until_the_model_completes(entry, analysis_cache, monkeypatch):
    if isinstance(analysis_cache, MemoryCache):
        pytest.skip("lease renewal requires Redis")
    monkeypatch.setattr(storage, "_LEASE_SECONDS", 1)
    client, model, _, _ = entry
    entered = asyncio.Event()
    release = asyncio.Event()

    async def delayed(ctx):
        entered.set()
        await release.wait()
        return model_result(ctx)

    model.side_effect = delayed
    primary = asyncio.create_task(client.post(PATH, json=payload(), headers=headers()))
    await asyncio.wait_for(entered.wait(), 2)
    secondary = asyncio.create_task(client.post(PATH, json=payload(), headers=headers()))
    try:
        await asyncio.sleep(1.3)
        assert model.await_count == 1
    finally:
        release.set()
        first, second = await asyncio.gather(primary, secondary)
    assert answer(second) == {**answer(first), "cacheHit": True}


async def test_analysis_lease_wait_has_deadline_without_stealing(analysis_cache, monkeypatch):
    if isinstance(analysis_cache, MemoryCache):
        pytest.skip("lease timeout requires Redis")
    monkeypatch.setattr(storage, "_MAX_WAIT_SECONDS", 0.05)
    key = storage.result_key(storage.AnalysisScope(FACTORY, "operator"), "lease", 1,
                             prompt_version="v1", model_version="v1")
    cache = storage.get_cache()
    try:
        owner = await cache.acquire(key)
        async with storage.analysis_result(key) as waiting:
            assert waiting.response_fields == {"cacheHit": False}
            await waiting.store({"answer": "uncached"})
        assert await analysis_cache.get(key.lock) == owner.revision.encode()
        assert await cache.complete(owner, {"answer": "owner"})
    finally:
        await cache.redis.aclose()
    async with storage.analysis_result(key) as reused:
        assert reused.entry.value == {"answer": "owner"}


async def test_cancelled_acquire_releases_lease_even_if_redis_already_applied_it(analysis_cache, monkeypatch):
    if isinstance(analysis_cache, MemoryCache):
        pytest.skip("acquire cancellation requires Redis")
    key = storage.result_key(storage.AnalysisScope(FACTORY, "operator"), "lease", 2,
                             prompt_version="v1", model_version="v1")
    cache = storage.get_cache()
    applied, closed = asyncio.Event(), asyncio.Event()
    original_eval, original_close = cache.redis.eval, cache.redis.aclose

    async def pause_after_eval(script, *args):
        result = await original_eval(script, *args)
        if script == storage._ACQUIRE:
            applied.set()
            await asyncio.Future()
        return result

    async def observe_close():
        await original_close()
        closed.set()

    monkeypatch.setattr(cache.redis, "eval", pause_after_eval)
    monkeypatch.setattr(cache.redis, "aclose", observe_close)
    monkeypatch.setattr(storage, "get_cache", lambda: cache)

    async def enter():
        async with storage.analysis_result(key):
            pytest.fail("Cancelled acquire reached the generator")

    task = asyncio.create_task(enter())
    await asyncio.wait_for(applied.wait(), 2)
    assert await analysis_cache.exists(key.lock)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert closed.is_set() and not await analysis_cache.exists(key.lock)


async def test_concurrent_cold_start_uses_one_service_cache(entry, monkeypatch):
    from smartbi import config
    from smartbi.agent import budget_tracker

    client, model, budget, _ = entry
    both_entered = asyncio.Event()
    waiters = 0

    async def get_pool():
        nonlocal waiters
        waiters += 1
        if waiters == 2:
            both_entered.set()
        await asyncio.wait_for(both_entered.wait(), 1)
        return object()

    service_type = chart_insight_service.ChartInsightService
    monkeypatch.setattr(chart_insight, "_service", None)
    monkeypatch.setattr(config, "get_pg_pool", get_pool)
    monkeypatch.setattr(budget_tracker, "AgentBudgetTracker", lambda pool: budget)
    monkeypatch.setattr(service_type, "_call_llm", model)
    monkeypatch.setattr(service_type, "_read_corpus_cache", AsyncMock(return_value=None))
    monkeypatch.setattr(chart_insight_service, "persist_distillation_sample", AsyncMock())
    outcomes = await asyncio.gather(*[
        client.post(PATH, json=payload(), headers=headers()) for _ in range(2)
    ])

    assert waiters == 2
    assert sorted(answer(response)["cacheHit"] for response in outcomes) == [False, True]
    model.assert_awaited_once()


async def test_stream_disconnect_keeps_generation_and_metering(entry):
    from smartbi.tenant_ctx import get_factory_id

    client, model, budget, service = entry
    entered = asyncio.Event()
    finish = asyncio.Event()

    async def delayed(ctx):
        entered.set()
        await finish.wait()
        assert get_factory_id() == FACTORY
        return model_result(ctx)

    model.side_effect = delayed
    request = asyncio.create_task(client.post(PATH, json=payload(), headers=headers(stream=True)))
    await asyncio.wait_for(entered.wait(), 1)
    request.cancel()
    with pytest.raises(asyncio.CancelledError):
        await request
    budget.consume.assert_not_awaited()

    finish.set()
    refreshed = answer(await client.post(PATH, json=payload(), headers=headers()))
    assert refreshed["cacheHit"] is True
    assert refreshed["source"] == "llm"
    model.assert_awaited_once()
    budget.consume.assert_awaited_once()
    assert not service._pending_answers


def events(response):
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    return [
        (kind.removeprefix("event: "), json.loads(data.removeprefix("data: ")))
        for kind, data in (frame.splitlines() for frame in response.text.strip().split("\n\n"))
    ]


async def test_stream_preview_then_final_and_cached_identical_final(entry):
    client, model, _, _ = entry
    initial = events(await client.post(PATH, json=payload(), headers=headers(stream=True)))
    repeated = events(await client.post(PATH, json=payload(), headers=headers(stream=True)))
    assert [kind for kind, _ in initial] == ["preview", "final"]
    preview, final = [envelope["data"] for _, envelope in initial]
    assert preview["tier"] == 1 and preview["input_hash"] is None and preview["source"] == "rules"
    assert final["tier"] == 2 and final["source"] == "llm"
    assert repeated[-1][1]["data"] == {**final, "cacheHit": True}
    model.assert_awaited_once()


async def test_stream_never_previews_cost_for_hidden_role(entry):
    client, model, _, _ = entry
    body = payload(y_metric="cost", permission_tier="finance_visible")
    privileged = events(await client.post(PATH, json=body, headers=headers(stream=True)))
    hidden = events(await client.post(PATH, json=body, headers=headers(role="warehouse_worker", stream=True)))
    assert [kind for kind, _ in privileged] == ["preview", "final"]
    assert [kind for kind, _ in hidden] == ["final"]
    assert hidden[0][1]["data"]["cacheHit"] is False
    assert model.await_count == 2


async def test_feedback_is_tenant_scoped_and_invalidates_downvoted_answer(entry, monkeypatch):
    client, model, _, service = entry
    initial = answer(await client.post(PATH, json=payload(), headers=headers()))
    input_hash = initial["input_hash"]

    async def lookup(sql, requested_hash, factory):
        assert "WHERE input_hash = $1 AND factory_id = $2" in sql
        if (requested_hash, factory) == (input_hash, FACTORY):
            return {"quality": 5, "metadata": {}}
        return None

    conn = SimpleNamespace(fetchrow=lookup, execute=AsyncMock())

    @asynccontextmanager
    async def acquire():
        yield conn

    monkeypatch.setattr(service, "_pool", SimpleNamespace(acquire=acquire))
    monkeypatch.setattr(service, "_read_corpus_cache", AsyncMock(return_value=None))
    monkeypatch.setattr(chart_insight_service, "persist_distillation_sample", AsyncMock())
    feedback_path = "/api/smartbi/insight/feedback"
    vote = {"input_hash": input_hash, "vote": "down", "factory_id": FACTORY}
    denied = await client.post(feedback_path, json={**vote, "factory_id": OTHER_FACTORY}, headers=headers(OTHER_FACTORY))
    assert denied.json()["code"] == "ROW_NOT_FOUND"
    conn.execute.assert_not_awaited()
    assert answer(await client.post(PATH, json=payload(), headers=headers()))["cacheHit"] is True

    accepted = answer(await client.post(feedback_path, json=vote, headers=headers()))
    assert accepted["feedback_recorded"] is True
    sql, _, _, hash_arg, factory_arg = conn.execute.await_args.args
    assert "WHERE input_hash = $3 AND factory_id = $4" in sql
    assert (hash_arg, factory_arg) == (input_hash, FACTORY)
    assert answer(await client.post(PATH, json=payload(), headers=headers()))["cacheHit"] is False
    assert model.await_count == 2


async def test_retry_after_cache_failure_does_not_count_feedback_twice(entry, monkeypatch):
    from smartbi.api import chart_insight as api

    client, model, _, service = entry
    initial = answer(await client.post(PATH, json=payload(), headers=headers()))
    row = {"quality": 5, "metadata": {"permission_tier": "finance_visible"}}

    async def save(_sql, metadata, quality, _hash, _factory):
        row.update(metadata=json.loads(metadata), quality=quality)

    conn = SimpleNamespace(fetchrow=AsyncMock(return_value=row), execute=AsyncMock(side_effect=save))

    @asynccontextmanager
    async def acquire():
        yield conn

    monkeypatch.setattr(service, "_pool", SimpleNamespace(acquire=acquire))
    monkeypatch.setattr(service, "_read_corpus_cache", AsyncMock(return_value=None))
    monkeypatch.setattr(chart_insight_service, "persist_distillation_sample", AsyncMock())
    invalidate = api.invalidate
    monkeypatch.setattr(api, "invalidate", AsyncMock(side_effect=api.AnalysisCacheUnavailable))
    vote = {"input_hash": initial["input_hash"], "cache_token": initial["cacheToken"],
            "vote": "down", "factory_id": FACTORY}
    path = "/api/smartbi/insight/feedback"
    assert (await client.post(path, json=vote, headers=headers())).status_code == 503
    monkeypatch.setattr(api, "invalidate", invalidate)
    assert answer(await client.post(path, json=vote, headers=headers()))["new_quality"] == 5
    conn.execute.assert_awaited_once()
    assert row["metadata"]["feedback_down"] == 1
    assert answer(await client.post(PATH, json=payload(), headers=headers()))["cacheHit"] is False
    assert model.await_count == 2


async def test_redis_feedback_does_not_depend_on_corpus_capture(entry):
    client, model, _, _ = entry
    initial = answer(await client.post(PATH, json=payload(), headers=headers()))
    vote = {"input_hash": initial["input_hash"], "cache_token": initial["cacheToken"],
            "vote": "down", "factory_id": FACTORY}
    response = await client.post("/api/smartbi/insight/feedback", json=vote, headers=headers())
    assert answer(response)["invalidated"] is True
    assert answer(await client.post(PATH, json=payload(), headers=headers()))["cacheHit"] is False
    assert model.await_count == 2
