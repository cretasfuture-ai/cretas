import importlib

import pytest
from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

from smartbi.capability import api as capability_api


def _app() -> FastAPI:
    app = FastAPI()

    @app.middleware("http")
    async def tenant_context(request: Request, call_next):
        request.state.factory_id = request.headers.get("X-Test-Factory")
        return await call_next(request)

    app.include_router(capability_api.router)
    return app


async def _fake_calculator():
    class FakeCalculator:
        async def get_capabilities(self, factory_id: str):
            assert factory_id in {"F006", "LIUSHANMEN"}
            return {"date"}

    return FakeCalculator()


def test_default_cohort_includes_lsm_and_preserves_existing_factories():
    assert capability_api._parse_rollout_factories(None) == {
        "F001",
        "RES_3101_009",
        "F006",
        "LIUSHANMEN",
    }


def test_authenticated_factory_guard_still_rejects_missing_and_cross_tenant():
    client = TestClient(_app())

    missing = client.get("/api/smartbi/capability/F006")
    assert missing.status_code == 401
    assert missing.json()["detail"] == "Tenant context not set"

    cross_tenant = client.get(
        "/api/smartbi/capability/F006",
        headers={"X-Test-Factory": "F001"},
    )
    assert cross_tenant.status_code == 403
    assert cross_tenant.json()["detail"] == "Cross-tenant access denied"


@pytest.mark.parametrize("factory", ["F006", "LIUSHANMEN"])
def test_default_rollout_is_visible_to_authenticated_same_factory(monkeypatch, factory):
    monkeypatch.setattr(capability_api, "_ROLLOUT_FACTORIES", capability_api._parse_rollout_factories(None))
    monkeypatch.setattr(capability_api, "_get_calculator", _fake_calculator)
    client = TestClient(_app())
    response = client.get(
        f"/api/smartbi/capability/{factory}",
        headers={"X-Test-Factory": factory},
    )

    assert response.status_code == 200
    assert response.json()["factory_id"] == factory
    assert response.json()["available_fields"] == ["date"]

    cross_tenant = client.get(
        f"/api/smartbi/capability/{factory}",
        headers={"X-Test-Factory": "F001"},
    )
    assert cross_tenant.status_code == 403
    assert cross_tenant.json()["detail"] == "Cross-tenant access denied"


def test_operator_override_supports_explicit_cohort_and_wildcard(monkeypatch):
    try:
        monkeypatch.setenv("CAPABILITY_ROLLOUT_FACTORIES", "F001,RES_3101_009")
        importlib.reload(capability_api)
        disabled = TestClient(_app()).get(
            "/api/smartbi/capability/F006",
            headers={"X-Test-Factory": "F006"},
        )
        assert disabled.status_code == 503
        assert "gradual rollout" in disabled.json()["detail"]

        monkeypatch.setenv("CAPABILITY_ROLLOUT_FACTORIES", "*")
        importlib.reload(capability_api)
        monkeypatch.setattr(capability_api, "_get_calculator", _fake_calculator)
        enabled = TestClient(_app()).get(
            "/api/smartbi/capability/F006",
            headers={"X-Test-Factory": "F006"},
        )
        assert enabled.status_code == 200
    finally:
        monkeypatch.delenv("CAPABILITY_ROLLOUT_FACTORIES", raising=False)
        importlib.reload(capability_api)
