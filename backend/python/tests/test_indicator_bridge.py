from datetime import date
from decimal import Decimal

import httpx
import pytest
from fastapi import FastAPI

from auth_middleware import JWTAuthMiddleware
from smartbi.api import indicator_bridge


def _app(monkeypatch, result):
    monkeypatch.setenv("INTERNAL_API_SECRET", "test-secret-for-indicator-bridge-2026")
    async def fake_pool():
        return object()

    async def fake_compute(pool, factory_id, *, date_range=None, **kwargs):
        assert factory_id == "F-001"
        assert date_range == (date(2026, 10, 1), date(2026, 10, 15))
        return result

    monkeypatch.setattr(indicator_bridge, "get_pg_pool", fake_pool)
    monkeypatch.setattr(indicator_bridge, "compute_dish_margins", fake_compute)
    app = FastAPI()
    app.add_middleware(JWTAuthMiddleware, jwt_secret="", enabled=True)
    app.include_router(indicator_bridge.router)
    return app


@pytest.mark.anyio
async def test_bridge_uses_authenticated_factory_and_explicit_window(monkeypatch):
    app = _app(monkeypatch, {"totalRevenueWithCost": Decimal("100"), "avgRate": Decimal("0.125"), "avgRateWithEstimated": Decimal("0.9"), "coverage": {"revenueRatio": 1}})
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get(
            "/api/smartbi/indicator/restaurant-dish-margin",
            params={"factory_id": "F-001", "period_start": "2026-10-01", "period_end": "2026-10-15"},
            headers={"X-Factory-Id": "F-001", "X-Internal-Secret": "test-secret-for-indicator-bridge-2026"},
        )
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["factory_id"] == "F-001"
    assert data["period_start"] == "2026-10-01"
    assert data["period_end"] == "2026-10-15"
    assert data["value"] == 12.5
    assert data["status"] == "AVAILABLE"
    assert data["unit"] == "%"


@pytest.mark.anyio
async def test_bridge_rejects_factory_mismatch(monkeypatch):
    app = _app(monkeypatch, {"totalRevenueWithCost": 100, "avgRate": 0.1})
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get(
            "/api/smartbi/indicator/restaurant-dish-margin",
            params={"factory_id": "F-002", "period_start": "2026-10-01", "period_end": "2026-10-15"},
            headers={"X-Factory-Id": "F-001", "X-Internal-Secret": "test-secret-for-indicator-bridge-2026"},
        )
    assert response.status_code == 403


@pytest.mark.anyio
async def test_bridge_requires_internal_or_jwt_identity(monkeypatch):
    app = _app(monkeypatch, {"totalRevenueWithCost": 100, "avgRate": 0.1, "coverage": {"revenueRatio": 1}})
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get(
            "/api/smartbi/indicator/restaurant-dish-margin",
            params={"factory_id": "F-001", "period_start": "2026-10-01", "period_end": "2026-10-15"},
        )
    assert response.status_code == 401


@pytest.mark.anyio
async def test_bridge_distinguishes_real_zero_and_no_sample(monkeypatch):
    for result, expected_status, expected_value in (
        ({"totalRevenueWithCost": 100, "avgRate": 0, "coverage": {"revenueRatio": 1}}, "AVAILABLE", 0),
        ({"totalRevenueWithCost": 0, "avgRate": 0.4, "coverage": {"revenueRatio": 0}}, "NO_DATA", None),
    ):
        app = _app(monkeypatch, result)
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.get(
                "/api/smartbi/indicator/restaurant-dish-margin",
                params={"factory_id": "F-001", "period_start": "2026-10-01", "period_end": "2026-10-15"},
                headers={"X-Factory-Id": "F-001", "X-Internal-Secret": "test-secret-for-indicator-bridge-2026"},
            )
        assert response.status_code == 200
        assert response.json()["data"]["status"] == expected_status
        assert response.json()["data"]["value"] == expected_value


@pytest.mark.anyio
async def test_bridge_excludes_partial_cost_coverage(monkeypatch):
    for ratio in (0.75, 1.2, 1.0000000005):
        app = _app(monkeypatch, {"totalRevenueWithCost": 100, "avgRate": 0.4, "coverage": {"revenueRatio": ratio}})
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.get(
                "/api/smartbi/indicator/restaurant-dish-margin",
                params={"factory_id": "F-001", "period_start": "2026-10-01", "period_end": "2026-10-15"},
                headers={"X-Factory-Id": "F-001", "X-Internal-Secret": "test-secret-for-indicator-bridge-2026"},
            )
        assert response.status_code == 200
        assert response.json()["data"]["value"] is None
        assert response.json()["data"]["reason"] == "COST_COVERAGE_INCOMPLETE"


@pytest.mark.anyio
async def test_bridge_surfaces_compute_failure(monkeypatch):
    app = _app(monkeypatch, None)

    async def broken(*args, **kwargs):
        raise RuntimeError("gold unavailable")

    monkeypatch.setattr(indicator_bridge, "compute_dish_margins", broken)
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get(
            "/api/smartbi/indicator/restaurant-dish-margin",
            params={"factory_id": "F-001", "period_start": "2026-10-01", "period_end": "2026-10-15"},
            headers={"X-Factory-Id": "F-001", "X-Internal-Secret": "test-secret-for-indicator-bridge-2026"},
        )
    assert response.status_code == 502
