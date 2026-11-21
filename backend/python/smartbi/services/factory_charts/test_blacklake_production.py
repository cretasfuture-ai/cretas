"""Exercise Blacklake report quantities through the factory catalogue and ask routes."""
from datetime import date, datetime, timezone
from decimal import Decimal, ROUND_HALF_UP
import json
import os
from pathlib import Path
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
    async def read_period(factory_id, start, end):
        assert factory_id == FACTORY
        return ExternalProduction(
            datetime(2026, 11, 20, tzinfo=timezone.utc),
            [row for row in rows if (start is None or start <= row["report_date"])
             and (end is None or row["report_date"] <= end)],
        )

    read = AsyncMock(side_effect=read_period)
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
@pytest.mark.parametrize(("question", "opening"), [
    ("11 月每天成品入库的良品量是多少？", "11 月成品入库每日良品量"),
    ("11 月出成率是多少？", "黑湖数据不含投入量，无法计算出成率。"),
    ("11 月成品入库单位成本是多少？", "11 月没有可按成品入库报工量核算的单位成本。"),
])
async def test_blacklake_rule_plan_reaches_factory_analysis_response(blacklake_app, monkeypatch,
                                                                   question, opening):
    app, _, _, _ = blacklake_app
    conversation.clear_contexts()
    provider = AsyncMock(side_effect=AssertionError("explicit report metrics must not call a model"))
    monkeypatch.setattr("common.llm_router.call_chain", provider)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post(f"{PREFIX}/factory-analysis", json={**PERIOD, "question": question})

    assert response.status_code == 200, response.text
    data = response.json()["data"]
    provider.assert_not_awaited()
    assert data["status"] == "answered" and data["plannerMode"]["mode"] == "rule"
    assert "授权图表目录" not in data["answer"]
    assert data["answer"].startswith(opening)
    if "良品量" in opening:
        first, = data["recommendations"]
        assert first["id"] == data["selectedIds"][0]
        assert first["analysis"]["familyId"] == "blacklake-production.finished.good"
        assert data["facts"][0]["value"] == "16" and data["facts"][0]["unit"] == "KG"
        assert "2026-11-18 12.50 KG；2026-11-19 3.50 KG" in data["answer"]
    else:
        assert data["recommendations"] == data["facts"] == []
        if "单位成本" in opening:
            assert "黑湖成本不含制造费用" in data["answer"] and "工单单位成本" in data["answer"]


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


_QUESTIONS = [
    row.split("|")[1].strip() for row in
    (Path(__file__).resolve().parents[5] / "docs/smartbi/liushanmen-blacklake/questions.md").read_text(encoding="utf-8").splitlines()
    if row.startswith("| 11 月")
]
_METRICS = (
    "每日良品量", "良品量最多的产品", "没有成品入库/出料记录", "各工序良品量",
    "各工序不良数量", "各工序不良率", "每日不良率", "没有成品入库/出料记录",
    "成品入库不良数量最多的日期", "没有成品入库/出料记录", "出成率", "单位成本",
)
_FAMILIES = ("finished.good", "finished.ranking", "", "process.good", "process.defects",
             "process.defect_rate", "finished.defect_rate", "", "finished.defects", "", "", "")


async def _ask(client, question, **scope):
    response = await client.post(f"{PREFIX}/factory-analysis", json={
        "source": "business", "question": question, "year": 2026, "period_type": "all", **scope,
    })
    assert response.status_code == 200, response.text
    return response.json()["data"]


def _check_answer(data, n):
    answer = data["answer"]
    assert data["status"] == "answered", answer
    assert _METRICS[n - 1] in answer.splitlines()[0].split("。")[0], answer
    assert not any(term in answer for term in ("累计口径", "分子", "分母", "授权图表目录", "**", "核对批次、报工记录"))
    assert [chart["id"] for chart in data["recommendations"]] == data["selectedIds"]
    assert {chart["analysis"]["familyId"] for chart in data["recommendations"]} == (
        {f"blacklake-production.{_FAMILIES[n - 1]}"} if _FAMILIES[n - 1] else set()
    )
    if n in {3, 8, 10}:
        assert answer.startswith("11 月产品 C P2026100002 在黑湖快照里没有成品入库/出料记录。")
        assert not data["facts"] and "10 月" not in answer
        if data["analysisSubject"]:
            assert any(item["id"] == data["analysisSubject"] and item["label"] == "C P2026100002"
                       for item in data["subjects"])
    if n == 11:
        assert answer == "黑湖数据不含投入量，无法计算出成率。"
    if n == 12:
        assert "黑湖成本不含制造费用" in answer and "工单单位成本" in answer
        assert not data["facts"]


def _report(day, code, name, stage, unit, good, defect):
    return {"report_date": date.fromisoformat(day), "product_code": code, "product_name": name,
            "process_name": stage, "unit": unit, "good_qty": Decimal(str(good)),
            "defect_qty": Decimal(str(defect))}


@pytest.mark.asyncio
@pytest.mark.parametrize("n", range(1, 13))
async def test_blacklake_question_answers_at_http_entry(blacklake_app, monkeypatch, n):
    app, rows, _, _ = blacklake_app
    rows.extend([
        _report("2026-10-24", "C P2026100002", "历史产品", "出料", "KG", 204.4, 0),
        _report("2026-10-24", "C P2026100002", "历史产品", "成品入库", "包", 389, 0),
        _report("2026-11-12", "other", "其他产品", "成品入库", "盒", 10, 0),
        _report("2026-11-13", "other", "其他产品", "成品入库", "盒", 13, 0),
        _report("2026-11-13", "other", "其他产品", "成品入库", "包", 5, 0),
        _report("2026-11-15", "other", "其他产品", "成品入库", "袋", 4, 0),
        _report("2026-11-15", "other", "其他产品", "出料", "箱", 3, 0),
        _report("2026-11-17", "A", "甲", "成品入库", "KG", 7150.387, 0),
        _report("2026-11-16", "other", "其他产品", "砍排修油", "KG", 81649.247, 1864.5),
        _report("2026-11-16", "other", "其他产品", "砍排修油", "盒", 14867.902, 604.5),
        _report("2026-11-16", "other", "其他产品", "贴盒签", "盒", 0, 0),
    ])
    model = AsyncMock(side_effect=AssertionError("clear report intent must bypass general planner"))
    monkeypatch.setattr("common.llm_router.call_chain", model)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        data = await _ask(client, _QUESTIONS[n - 1])
    model.assert_not_awaited()
    _check_answer(data, n)
    answer = data["answer"]
    checks = {
        1: ("按 4 种单位", "2026-11-17 7,150.39 KG；2026-11-18 12.50 KG", "2026-11-15 4 袋"),
        2: ("其他产品 · other 23 盒", "甲 · A 7,162.89 KG"),
        4: ("按 5 种单位", "砍排修油 81,649.25 KG", "出料 3 箱"),
        5: ("按 5 种单位", "砍排修油 1,864.50 KG", "砍排修油 605 盒"),
        6: ("按 5 种单位", "砍排修油 2.233%", "砍排修油 3.907%", "贴盒签 无可计算的报工量"),
        7: ("2026-11-17 0%", "2026-11-18 7.407%", "2026-11-19 12.5%", "2026-11-12 0%"),
        9: ("2026-11-12、2026-11-13", "0 盒"),
    }
    for text in checks.get(n, ()):
        assert text in answer
    if n == 1:
        kg = next(chart for chart in data["recommendations"] if chart["unit"] == "KG")
        assert {"label": "2026-11-17", "value": 7150.387} in kg["tableData"]
    if n in {4, 5, 6}:
        assert len(data["recommendations"]) == 5  # More than the generic planner's four-chart cap.


@pytest.mark.asyncio
async def test_missing_records_requires_a_successful_source_read(blacklake_app, monkeypatch):
    app, rows, read, _ = blacklake_app
    rows.clear()
    planner = AsyncMock(return_value=([], {"mode": "unavailable", "reason": "读取失败"}))
    monkeypatch.setattr(conversation, "plan", planner)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        empty = await _ask(client, _QUESTIONS[2], **PERIOD)
        read.side_effect = RuntimeError("fixture source failure")
        failed = await _ask(client, _QUESTIONS[2], **PERIOD)
    _check_answer(empty, 3)
    assert failed["sourceErrors"]
    assert "黑湖快照里没有" not in failed["answer"]


@pytest.mark.asyncio
async def test_product_questions_use_current_rows_and_only_compare_matching_units(blacklake_app):
    app, rows, _, _ = blacklake_app
    rows.extend([
        _report("2026-11-12", "C P2026100002", "本月产品", "成品入库", "KG", 9, 1),
        _report("2026-11-13", "C P2026100002", "本月产品", "成品入库", "KG", 30, 0),
        _report("2026-11-14", "C P2026100002", "本月产品", "出料", "KG", 49, 0),
        _report("2026-11-14", "C P2026100002", "本月产品", "出料", "盒", 300, 0),
    ])
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        daily, comparison, rate = [await _ask(client, _QUESTIONS[n - 1]) for n in (3, 8, 10)]
    assert "2026-11-12 9.00 KG；2026-11-13 30.00 KG" in daily["answer"]
    assert comparison["answer"] == "按 KG 计，出料良品量比成品入库多报 10.00 KG。"
    assert rate["answer"] == "11 月成品入库总体不良率：按 KG 计为 2.5%。"


def _replay_snapshot(expected):
    """Reconstruct rows that preserve the two independently exported warehouse totals."""
    for item in expected["by_process_unit_nov"]:
        if item["process_name"] != "成品入库":
            yield _report("2026-11-17", "", "", item["process_name"], item["unit"], item["good"], item["defect"])
    remaining = [
        [item, Decimal(str(item["good"]))] for item in expected["warehouse_top_products_nov"]
    ]
    for day in expected["warehouse_daily_nov"]:
        qty = Decimal(str(day["good"]))
        assert day["defect"] == 0
        for entry in remaining:
            item, balance = entry
            if item["unit"] != day["unit"] or qty <= 0 or balance <= 0:
                continue
            portion = min(qty, balance)
            yield _report(day["report_date"], item["product_code"], item["product_name"],
                          "成品入库", day["unit"], portion, 0)
            entry[1] -= portion
            qty -= portion
        assert qty == 0
    assert all(balance == 0 for _, balance in remaining)
    assert expected["code_C_P2026100002_nov"] is None
    for row in expected["code_C_P2026100002_all"]:
        yield _report(row["report_date"], "C P2026100002", "历史产品", row["process_name"],
                      row["unit"], row["good_qty"], row["defect_qty"])


def _expected_quantity(value, unit):
    quantum = Decimal("0.01") if unit == "KG" else Decimal("1")
    rounded = Decimal(str(value)).quantize(quantum, rounding=ROUND_HALF_UP)
    return f"{rounded:,f} {unit}"


@pytest.mark.asyncio
@pytest.mark.skipif(not os.getenv("BLACKLAKE_ASK_EXPECTED"), reason="requires the local organizer snapshot export")
async def test_offline_dispatch_snapshot_replay(blacklake_app):
    expected = json.loads(Path(os.environ["BLACKLAKE_ASK_EXPECTED"]).read_text(encoding="utf-8-sig"))
    app, rows, _, _ = blacklake_app
    rows[:] = _replay_snapshot(expected)
    results = []
    preview = {}
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        for n, question in enumerate(_QUESTIONS, 1):
            data = await _ask(client, question)
            _check_answer(data, n)
            answer = data["answer"]
            unit_lines = dict(line.split("：", 1) for line in answer.splitlines()[1:] if "：" in line)
            if n in {4, 5, 6}:
                partitions = expected["by_process_unit_nov"]
                assert f"共 {len(partitions)} 个分项" in answer
                for row in partitions:
                    value = row[{4: "good", 5: "defect", 6: "defect_rate"}[n]]
                    if n != 6:
                        reading = _expected_quantity(value, row["unit"])
                    elif value is None:
                        reading = "无可计算的报工量"
                    else:
                        reading = f"{value:.3f}".rstrip("0").rstrip(".") + "%"
                    assert f"{row['process_name']} {reading}" in unit_lines[row["unit"]]
            if n in {1, 7}:
                for row in expected["warehouse_daily_nov"]:
                    reading = _expected_quantity(row["good"], row["unit"]) if n == 1 else "0%"
                    assert f"{row['report_date']} {reading}" in unit_lines[row["unit"]]
            if n == 2:
                for row in expected["warehouse_top_products_nov"]:
                    reading = _expected_quantity(row["good"], row["unit"])
                    assert f"{row['product_name']} · {row['product_code']} {reading}" in unit_lines[row["unit"]]
            if n == 9:
                assert "2026-11-12" in answer and "0 盒" in answer
            results.append({"n": n, "after": answer, "pass": True})
            if n in {1, 2, 3, 4, 5, 6}:
                preview[n] = data
    if path := os.getenv("BLACKLAKE_ASK_REPLAY"):
        Path(path).write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
        Path(path).with_name("browser-fixtures.json").write_text(
            json.dumps(preview, ensure_ascii=False), encoding="utf-8")
