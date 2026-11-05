import asyncio
import os
from types import SimpleNamespace

import jwt
import pytest
from fastapi import HTTPException

from smartbi.api import synthesis
from smartbi.shared.retired_tenants import is_retired_tenant
from smartbi_compat.api import analysis_finance, analysis_production, analysis_quality, analysis_sales
from smartbi_compat.auth import JWT_ALGORITHM
from smartbi_compat.tenant import get_tenant_type


HISTORICAL_RESTAURANT_IDS = [
    "MOCK_REST", "DEMO_REST", "R_DONGMENKOU_REAL", "R_HONGDEJI_REAL",
    "R_HUOGUO_GENERIC_REAL", "R_ILTEATRO_REAL", "R_JINCHUAN_HG_REAL",
    "R_JINRINIUSHI_REAL", "R_LINJIAYAN_REAL", "R_QINGHUAJIAO_REAL",
    "R_SHANGMA_HG_REAL", "R_XIMAXIANG_REAL", "R_XINBASHU_REAL",
    "R_YONGHE_REAL", "R_YOUZIYOUWEI_REAL", "R_YUJIUJING_REAL",
]


@pytest.mark.parametrize("factory_id", HISTORICAL_RESTAURANT_IDS)
@pytest.mark.parametrize("route", [synthesis.comprehensive, synthesis.comprehensive_stream])
def test_synthesis_rejects_historical_tenant_before_pool_or_engine(monkeypatch, factory_id, route):
    async def forbidden_pool():
        raise AssertionError("retired tenant reached the pool")

    def forbidden_engine(*args, **kwargs):
        raise AssertionError("retired tenant reached the engine/cache/budget")

    monkeypatch.setattr(synthesis, "get_pg_pool", forbidden_pool)
    monkeypatch.setattr(synthesis, "ComprehensiveSynthesisEngine", forbidden_engine)
    request = SimpleNamespace(state=SimpleNamespace(factory_id=factory_id, role="factory_super_admin", user_id=7))
    with pytest.raises(HTTPException) as error:
        asyncio.run(route(request, synthesis.SynthesisRequest(question="查看经营情况")))
    assert error.value.status_code == 410
    assert error.value.detail == "RESTAURANT_AI_RETIRED"


@pytest.mark.parametrize("factory_id", ["F006", "FACTORY_WITH_LONG_ID", "QATEST_1021_001", "F006BK", "R_FACTORY_CUSTOM"])
def test_factory_test_and_backup_ids_are_not_business_retirement(factory_id):
    assert is_retired_tenant(factory_id) is False


@pytest.mark.parametrize("row", [None, {"type": None}, {"type": "UNKNOWN"}], ids=["row-none", "type-none", "type-unknown"])
def test_four_analytics_routes_reject_invalid_metadata_before_handlers(monkeypatch, row):
    """Every polymorphic analytics entrypoint must fail closed on metadata."""
    secret = "retired-tenant-boundary-test-secret"
    monkeypatch.setenv("JWT_SECRET", secret)

    class _Conn:
        async def fetchrow(self, *_args):
            return row

    class _Acquire:
        async def __aenter__(self):
            return _Conn()

        async def __aexit__(self, *_args):
            return False

    class _Pool:
        def acquire(self):
            return _Acquire()

    async def fake_pool():
        return _Pool()

    async def forbidden(*_args, **_kwargs):
        raise AssertionError("invalid tenant metadata reached analytics handler")

    import smartbi.config
    monkeypatch.setattr(smartbi.config, "get_cretas_pool", fake_pool)
    for module in (analysis_sales, analysis_finance, analysis_production, analysis_quality):
        monkeypatch.setattr(module, "get_tenant_type", get_tenant_type)
    monkeypatch.setattr(analysis_sales, "_get_comprehensive_sales_analysis", forbidden)
    monkeypatch.setattr(analysis_finance, "_get_comprehensive_finance_analysis", forbidden)
    monkeypatch.setattr(analysis_production, "_factory_production_dispatch", forbidden)
    monkeypatch.setattr(analysis_quality, "_factory_quality_dispatch", forbidden)

    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    app = FastAPI()
    for module in (analysis_sales, analysis_finance, analysis_production, analysis_quality):
        app.include_router(module.router)
    token = jwt.encode(
        {"userId": 7, "username": "boundary", "role": "factory_super_admin", "factoryId": "TENANT_42"},
        secret,
        algorithm=JWT_ALGORITHM,
    )
    headers = {"Authorization": f"Bearer {token}"}
    params = {"startDate": "2026-01-01", "endDate": "2026-01-31"}
    client = TestClient(app)

    paths = [
        "/api/mobile/TENANT_42/smart-bi/analysis/sales",
        "/api/mobile/TENANT_42/smart-bi/analysis/finance",
        "/api/mobile/TENANT_42/smart-bi/analysis/production",
        "/api/mobile/TENANT_42/smart-bi/analysis/quality",
    ]
    for path in paths:
        response = client.get(path, params=params, headers=headers)
        assert response.status_code == 503, (path, response.text)
