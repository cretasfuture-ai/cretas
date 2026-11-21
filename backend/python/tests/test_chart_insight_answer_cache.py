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
async def entry(monkeypatch):
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


async def test_expiry_allows_regeneration(entry, monkeypatch):
    clock = [100.0]
    monkeypatch.setattr(chart_insight_service, "_ANSWER_CACHE_TTL_SECONDS", 2)
    client, model, _, service = entry
    service._answers = chart_insight_service.TTLCache(
        maxsize=1024, ttl=chart_insight_service._ANSWER_CACHE_TTL_SECONDS, timer=lambda: clock[0],
    )
    assert answer(await client.post(PATH, json=payload(), headers=headers()))["cacheHit"] is False
    clock[0] += 1
    assert answer(await client.post(PATH, json=payload(), headers=headers()))["cacheHit"] is True
    clock[0] += 2
    assert answer(await client.post(PATH, json=payload(), headers=headers()))["cacheHit"] is False
    assert model.await_count == 2


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
