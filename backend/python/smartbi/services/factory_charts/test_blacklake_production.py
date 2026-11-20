"""Exercise Blacklake report quantities through the factory catalogue and ask routes."""
from datetime import date, datetime, timezone
from decimal import Decimal
from unittest.mock import AsyncMock

import pandas as pd
import pytest
from fastapi import FastAPI, Request
from httpx import ASGITransport, AsyncClient
from pandas.api.types import is_numeric_dtype

from smartbi.api import financial_dashboard as api
from smartbi.services.external_production import ExternalProduction
from . import (
    blacklake_production, captured_sources, conversation, conversation_store,
    detail_store, finance_sources, operational_sources, sources, trusted_business_sources,
)
from .engine import Dataset
from .test_sources import FakePool


FACTORY = "LIUSHANMEN"
PREFIX = "/api/smartbi/financial-dashboard"
PERIOD = {"source": "business", "period_type": "month_range", "year": 2026,
          "start_month": 11, "end_month": 11}
REPORTS = [
    (18, "A", "甲", "成品入库", "KG", "10.25", "0.75"),
    (18, "A", "甲", "成品入库", "KG", "2.25", "0.25"),
    (19, "B", "乙", "成品入库", "KG", "3.50", "0.50"),
    (18, "A", "甲", "切配", "KG", "15.75", "0.25"),
    (18, "A", "甲", "包装", "盒", "4", "1"),
]


@pytest.fixture
def blacklake_app(monkeypatch):
    rows = [
        {"report_date": date(2026, 11, day), "product_code": code, "product_name": name,
         "process_name": process, "unit": unit, "good_qty": Decimal(good), "defect_qty": Decimal(defect)}
        for day, code, name, process, unit, good, defect in REPORTS
    ]
    source_calls = []
    cretas_pool = FakePool([
        ("external_snapshot_metadata", "external_source_snapshots", [{"factory_id": FACTORY, "status": "ACTIVE"}]),
        ("sales", "sales_orders so", []),
        ("purchase", "purchase_receive_records pr", []),
        ("quality", "quality_inspections qi", []),
    ], source_calls)
    pg_pool = FakePool([
        ("production", "fact_production_batch", [
            {"日期": date(2026, 11, day), "产品": "自有产品", "产品ID": "local-sku", "产量": qty, "单位": "KG"}
            for day, qty in [(18, 8), (19, 9)]
        ]),
        ("process", "fact_production_report", []),
    ], [])
    monkeypatch.setattr(sources.config, "get_cretas_pool", AsyncMock(return_value=cretas_pool))
    monkeypatch.setattr(sources.config, "get_pg_pool", AsyncMock(return_value=pg_pool))
    read = AsyncMock(return_value=ExternalProduction(datetime(2026, 11, 20, tzinfo=timezone.utc), rows))
    monkeypatch.setattr(blacklake_production, "load_external_production", read)
    for adapter in (finance_sources, captured_sources, operational_sources, trusted_business_sources, sources.settled_sources):
        monkeypatch.setattr(adapter, "load", AsyncMock(return_value=([], [], [])))

    async def register(candidates, *_args):
        return candidates

    monkeypatch.setattr(detail_store, "register_shared", register)
    monkeypatch.setattr(conversation_store, "_STORE", conversation_store.MemoryConversationStore())
    app = FastAPI()

    @app.middleware("http")
    async def identity(request: Request, call_next):
        request.state.factory_id = FACTORY
        request.state.user_id = 1309
        request.state.role = "viewer"
        return await call_next(request)

    app.include_router(api.router, prefix=PREFIX)
    return app, rows, read, source_calls


@pytest.mark.asyncio
@pytest.mark.parametrize("with_finished", [True, False], ids=["mixed-stages", "no-finished"])
async def test_blacklake_decimal_quantities_through_both_routes(blacklake_app, monkeypatch, with_finished):
    app, rows, read, source_calls = blacklake_app
    if not with_finished:
        rows[:] = [row for row in rows if row["process_name"] != "成品入库"]
    metric = "finished.good" if with_finished else "process.good"
    observed_dtypes = []
    original_build = blacklake_production.build

    def observe_build(dataset):
        # Other safeguards can still render Decimal rows. Pin the loader's
        # conversion at the real builder boundary as well as the HTTP output.
        observed_dtypes.append(tuple(dataset.frame[["良品数", "不良数"]].dtypes))
        return original_build(dataset)

    async def plan(_question, catalog, *_args, **_kwargs):
        return [item["id"] for item in catalog
                if item["metric"] == f"blacklake-production.{metric}" and item["unit"] == "KG"], {"mode": "ai"}

    monkeypatch.setattr(blacklake_production, "build", observe_build)
    monkeypatch.setattr(conversation, "plan", plan)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        reco = await client.post(f"{PREFIX}/factory-recommendations",
                                 json={**PERIOD, "use_ai": False, "detail_candidate_ids": []})
        ask = await client.post(f"{PREFIX}/factory-analysis", json={**PERIOD, "question": "良品数量是多少？"})

    assert reco.status_code == 200, reco.text
    charts = reco.json()["data"]["recommendations"]
    external = [item for item in charts if item["id"].startswith("blacklake-production.")]
    assert {item["unit"] for item in external if ".process.good" in item["id"]} == {"KG", "盒"}
    assert {item["unit"] for item in external if ".finished.good" in item["id"]} == (
        {"KG"} if with_finished else set()
    )
    assert ask.status_code == 200, ask.text
    answer = ask.json()["data"]
    assert answer["status"] == "answered", answer
    assert any(item["sourceKey"] == "blacklake-production" for item in answer["catalog"])
    selected = answer["recommendations"]
    assert len(selected) == 1 and selected[0]["id"].startswith(f"blacklake-production.{metric}@")
    assert _points(selected[0]) == ([12.5, 3.5] if with_finished else [15.75])
    assert observed_dtypes and all(is_numeric_dtype(dtype) for pair in observed_dtypes for dtype in pair), observed_dtypes
    assert read.await_count >= 2
    assert all(call.args == (FACTORY, date(2026, 11, 1), date(2026, 11, 30)) for call in read.await_args_list)
    assert [call[3] for call in source_calls if call[0] == "fetchval"] == [(FACTORY,)] * read.await_count


@pytest.mark.asyncio
async def test_factory_recommendations_isolates_a_source_build_failure(blacklake_app, monkeypatch, caplog):
    app, _, _, _ = blacklake_app

    def fail_blacklake(_dataset):
        raise RuntimeError("fixture source build failure")

    monkeypatch.setattr(blacklake_production, "build", fail_blacklake)
    async with AsyncClient(transport=ASGITransport(app=app, raise_app_exceptions=False), base_url="http://test") as client:
        response = await client.post(f"{PREFIX}/factory-recommendations",
                                     json={**PERIOD, "use_ai": False, "detail_candidate_ids": []})

    assert response.status_code == 200, response.text
    data = response.json()["data"]
    assert any(item["id"].startswith("production.") for item in data["recommendations"])
    assert all(not item["id"].startswith("blacklake-production.") for item in data["recommendations"])
    gap, = [item for item in data["unavailable"] if item["id"].startswith("blacklake-production.dataset-build:")]
    assert gap["error"] is True and "图表生成失败" in gap["missing"][0]
    assert {"source": gap["title"], "message": gap["missing"][0]} in data["sourceErrors"]
    assert "fixture source build failure" not in response.text
    assert any(record.levelname == "ERROR" and "blacklake-production" in record.getMessage()
               and record.exc_info and str(record.exc_info[1]) == "fixture source build failure"
               for record in caplog.records)


def _points(candidate):
    return candidate["chart"]["echartsOption"]["series"][0]["data"]


def test_blacklake_float_daily_product_and_stage_totals_match_baseline():
    frame = pd.DataFrame(
        [(f"2026-11-{day}", code, name, process, unit, float(good), float(defect))
         for day, code, name, process, unit, good, defect in REPORTS],
        columns=["日期", "产品编码", "产品", "工序", "单位", "良品数", "不良数"],
    )
    candidates, _ = blacklake_production.build(Dataset(frame, "blacklake-production · fixture"))
    assert {item["id"]: _points(item) for item in candidates} == {
        "process.good@KG:bar_horizontal": [16.0, 15.75],
        "process.defects@KG:bar_horizontal": [1.5, 0.25],
        "process.defect_rate@KG:bar_horizontal": [8.571428571428571, 1.5625],
        "finished.good@KG:line": [12.5, 3.5],
        "finished.defects@KG:line": [1.0, 0.5],
        "finished.defect_rate@KG:line": [7.407407407407407, 12.5],
        "finished.ranking@KG:bar_horizontal": [12.5, 3.5],
        "process.good@盒:bar_horizontal": [4.0],
        "process.defects@盒:bar_horizontal": [1.0],
        "process.defect_rate@盒:bar_horizontal": [20.0],
    }


def test_blacklake_object_quantities_with_no_named_stage_produce_no_points():
    frame = pd.DataFrame([{"日期": "2026-11-18", "单位": "盒", "工序": "",
                           "良品数": Decimal("4"), "不良数": Decimal("1")}])
    candidates, gaps = blacklake_production.build(Dataset(frame, "blacklake-production · fixture"))
    assert candidates == []
    assert [gap["id"] for gap in gaps] == ["inputs"]
