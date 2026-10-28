from copy import deepcopy

import pandas as pd
import pytest
from fastapi import FastAPI, Request
from httpx import ASGITransport, AsyncClient

from smartbi.api import financial_dashboard as api
from smartbi.services.factory_charts import detail_store, engine, ranking


def _app():
    app = FastAPI()

    @app.middleware("http")
    async def trusted_identity(request: Request, call_next):
        request.state.factory_id = "F006"
        request.state.user_id = 1309
        request.state.role = "factory_super_admin"
        return await call_next(request)

    app.include_router(api.router)
    return app


@pytest.fixture
def route_stubs(monkeypatch):
    candidate_list = [
        {"id": "chart-1", "title": "产量", "source": "upload-42 · 本厂上传报表",
         "unit": "kg", "chart": {"chartType": "bar", "echartsOption": {}}},
        {"id": "chart-2", "title": "合格率", "source": "upload-42 · 本厂上传报表",
         "unit": "%", "chart": {"chartType": "line", "echartsOption": {}}},
    ]
    calls = []
    async def load_data(*_args):
        return _frame()

    monkeypatch.setattr(api, "_load_data", load_data)
    monkeypatch.setattr(engine, "analysis_subjects", lambda *_: [])
    monkeypatch.setattr(engine, "scope_datasets", lambda datasets, *_: (datasets, None, "全厂范围"))
    monkeypatch.setattr(engine, "build", lambda *_: (deepcopy(candidate_list), []))

    async def rank_candidates(candidates, *_args, **_kwargs):
        return candidates, []

    async def register(candidates, *_args):
        calls.append([item["id"] for item in candidates])
        result = deepcopy(list(candidates))
        for item in result:
            item["detailContext"] = {"id": f"ctx-{item['id']}", "expiresAt": "2030-01-01T00:00:00+00:00",
                                      "drilldown": True}
        return result

    monkeypatch.setattr(ranking, "rank", rank_candidates)
    monkeypatch.setattr(detail_store, "register_shared", register)
    return calls


def _frame():
    return pd.DataFrame([{"日期": "2026-01-01", "产品": "A", "单位": "kg", "产量": 1}])


@pytest.mark.asyncio
async def test_empty_selection_is_catalog_only(route_stubs):
    async with AsyncClient(transport=ASGITransport(app=_app()), base_url="http://test") as client:
        response = await client.post("/factory-recommendations", json={
            "source": "upload", "upload_id": 42, "use_ai": False, "detail_candidate_ids": [],
        })
    assert response.status_code == 200
    assert route_stubs == []
    cards = response.json()["data"]["recommendations"]
    assert all("detailContext" not in item for item in cards)
    assert all(item["detailStatus"]["message"] == "点击查看时加载详情" for item in cards)
    assert all(item.get("detailSnapshotKey") for item in cards)


@pytest.mark.asyncio
async def test_selected_ids_allocate_only_authorized_candidates(route_stubs):
    async with AsyncClient(transport=ASGITransport(app=_app()), base_url="http://test") as client:
        response = await client.post("/factory-recommendations", json={
            "source": "upload", "upload_id": 42, "use_ai": False,
            "detail_candidate_ids": ["chart-1"],
        })
    assert response.status_code == 200
    assert route_stubs == [["chart-1"]]
    cards = {item["id"]: item for item in response.json()["data"]["recommendations"]}
    assert cards["chart-1"]["detailContext"]["id"] == "ctx-chart-1"
    assert "detailContext" not in cards["chart-2"]


@pytest.mark.asyncio
async def test_unknown_detail_candidate_fails_closed(route_stubs):
    async with AsyncClient(transport=ASGITransport(app=_app()), base_url="http://test") as client:
        response = await client.post("/factory-recommendations", json={
            "source": "upload", "upload_id": 42, "use_ai": False,
            "detail_candidate_ids": ["not-authorized"],
        })
    assert response.status_code == 422
    assert route_stubs == []
