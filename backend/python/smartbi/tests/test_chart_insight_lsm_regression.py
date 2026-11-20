import time
from types import SimpleNamespace
from unittest.mock import AsyncMock

import jwt
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from auth_middleware import JWTAuthMiddleware
from smartbi.api import chart_insight
from smartbi.services.insights import chart_insight_service


_SECRET = "local-chart-insight-regression-key"
_PATH = "/api/smartbi/chart-insight"


def _headers(factory="LIUSHANMEN", role="factory_super_admin"):
    token = jwt.encode(
        {"factoryId": factory, "userId": 1, "role": role, "exp": int(time.time()) + 60},
        _SECRET,
        algorithm="HS256",
    )
    return {"Authorization": f"Bearer {token}"}


def _request(chart_type, x_dim, metric, values, labels, pattern):
    return {
        "factory_id": "LIUSHANMEN",
        "chart_type": chart_type,
        "x_dim": x_dim,
        "y_metric": metric,
        "domain": "factory",
        "aggregation": "sum",
        "data_pattern": pattern,
        "series_values": values,
        "series_labels": labels,
    }


# Replay the four chart shapes from supplement 3. Costs and labels are synthetic.
# The production screenshot shows two dates and one product; the cost screenshot
# shows 23 eligible dates (after exclusions) and a scrollable 9+ product ranking.
_COST_DAYS = [
    f"2026-{month_day}" for month_day in (
        "10-24", "10-25", "10-26", "10-27", "10-28", "10-30",
        "11-01", "11-02", "11-03", "11-05", "11-06", "11-07",
        "11-08", "11-09", "11-10", "11-11", "11-13", "11-14",
        "11-15", "11-16", "11-17", "11-18", "11-19",
    )
]
_COST_TREND = _request(
    "LINE", "time", "cost", [100, 20, 10] + [100] * 11 + [300] + [100] * 7 + [160],
    _COST_DAYS, "trend:mixed:high",
)
_COST_RANK = _request(
    "BAR", "product", "cost", list(range(100, 0, -10)),
    [f"样品{i} · BCP202610{i:04d}" for i in range(1, 11)], "ranking:top-share:0-50:n9+",
)
_PRODUCTION_TREND = _request(
    "LINE", "time", "quantity", [6300.0, 3087.0],
    ["2026-11-16", "2026-11-17"], "trend:falling:high",
)
_PRODUCTION_RANK = _request(
    "BAR", "product", "quantity", [9400.0], ["样品 BCP2026100003"], "ranking:top-share:80+:n2-3",
)


@pytest.fixture
def entry(monkeypatch):
    monkeypatch.delenv("CHART_INSIGHT_DETERMINISTIC_FIRST", raising=False)
    budget = SimpleNamespace(
        check_budget=AsyncMock(return_value=SimpleNamespace(blocked=False)),
        consume=AsyncMock(),
    )
    service = chart_insight_service.ChartInsightService(pool=None, budget_tracker=budget)
    model = AsyncMock()
    persist = AsyncMock()
    monkeypatch.setattr(service, "_call_llm", model)
    monkeypatch.setattr(chart_insight_service, "persist_distillation_sample", persist)
    monkeypatch.setattr(chart_insight, "_get_service", AsyncMock(return_value=service))
    app = FastAPI()
    app.add_middleware(JWTAuthMiddleware, jwt_secret=_SECRET)
    app.include_router(chart_insight.router, prefix="/api/smartbi")
    return TestClient(app), budget, model, persist


@pytest.mark.parametrize(
    ("payload", "rejected_finding", "expected_finding", "expected_suggestion"),
    [
        (
            _COST_TREND, "2026-11-19最高。",
            "有记录的日期里，2026-11-10的成本最高，2026-10-26最低。"
            "从首条记录（2026-10-24）到末条记录（2026-11-19），成本增加60.0%。",
            "2026-11-10的成本是2026-10-26的30.0倍。可对照这两处的工单明细，"
            "核对工单数，以及材料费和人工费哪一项相差更多。",
        ),
        (
            _COST_RANK, "样品1 · BCP2026100001成本最高。",
            "参与比较的产品中，样品1的成本最高，样品10最低。样品1占这些产品合计18.2%。",
            "样品1的成本是样品10的10.0倍。可对照这两处的工单明细，"
            "核对工单数，以及材料费和人工费哪一项相差更多。",
        ),
        (
            _PRODUCTION_TREND, "11日良品下降。",
            "有记录的日期里，2026-11-16的数量最高，2026-11-17最低。"
            "从首条记录（2026-11-16）到末条记录（2026-11-17），数量减少51.0%。",
            "2026-11-17的数量比2026-11-16少51.0%。可打开2026-11-17的报工明细，"
            "核对是否有工序漏报，以及两处的计量单位是否一致。",
        ),
    ],
    ids=["cost-daily-23", "cost-products-9plus", "production-daily-2"],
)
def test_rejected_model_output_uses_chart_numbers(entry, payload, rejected_finding, expected_finding, expected_suggestion):
    client, budget, model, persist = entry
    model.return_value = {"finding": rejected_finding, "claims": []}
    response = client.post(_PATH, json=payload, headers=_headers())

    assert response.status_code == 200
    result = response.json()["data"]
    assert result is not None
    assert result["source"] == "rules"
    assert result["finding"] == expected_finding
    assert result["suggestion"] == expected_suggestion
    assert result["input_hash"] is None
    assert model.await_args.args[0].permission_tier == "finance_visible"
    budget.consume.assert_awaited_once_with("LIUSHANMEN", 800)
    persist.assert_not_awaited()


def test_model_unavailable_uses_chart_numbers(entry):
    client, budget, model, _ = entry
    model.return_value = None
    response = client.post(_PATH, json=_PRODUCTION_TREND, headers=_headers())
    assert response.status_code == 200
    assert response.json()["data"]["source"] == "rules"
    assert "数量减少51.0%" in response.json()["data"]["finding"]
    budget.consume.assert_awaited_once_with("LIUSHANMEN", 800)


def test_same_name_products_keep_codes_in_the_finding_and_suggestion(entry):
    client, _, model, _ = entry
    model.return_value = None
    payload = _request(
        "BAR", "product", "cost", [100, 20, 30],
        ["样品 · BCP2026100001", "样品 · BCP2026100002", "套装 · 加量"], "ranking:top-share:50-80:n2-3",
    )
    response = client.post(_PATH, json=payload, headers=_headers())

    assert response.status_code == 200
    result = response.json()["data"]
    assert "样品 · BCP2026100001的成本最高，样品 · BCP2026100002最低" in result["finding"]
    assert "样品 · BCP2026100001的成本是样品 · BCP2026100002的5.0倍" in result["suggestion"]


def test_zero_cost_baseline_has_no_ratio(entry):
    client, _, model, _ = entry
    model.return_value = None
    payload = {**_COST_TREND, "series_values": [0, 30], "series_labels": ["2026-10-24", "2026-10-27"]}
    response = client.post(_PATH, json=payload, headers=_headers())

    assert response.status_code == 200
    result = response.json()["data"]
    assert "%" not in result["finding"]
    assert "2026-10-24的成本记录为0，2026-10-27的成本最高" in result["suggestion"]
    assert "倍" not in result["suggestion"]


def test_single_product_has_clear_reason_and_never_calls_model(entry):
    client, budget, model, _ = entry
    model.return_value = {"finding": None, "claims": []}
    response = client.post(_PATH, json=_PRODUCTION_RANK, headers=_headers())
    assert response.status_code == 200
    assert response.json()["data"] is None
    assert response.json().get("code") == "INSUFFICIENT_DATA"
    budget.check_budget.assert_not_awaited()
    model.assert_not_awaited()


def test_budget_block_has_clear_reason_and_never_calls_model(entry):
    client, budget, model, _ = entry
    budget.check_budget.return_value = SimpleNamespace(blocked=True)
    response = client.post(_PATH, json=_COST_TREND, headers=_headers())
    assert response.status_code == 200
    assert response.json()["data"] is None
    assert response.json().get("code") == "BUDGET_EXHAUSTED"
    budget.check_budget.assert_awaited_once_with("LIUSHANMEN")
    model.assert_not_awaited()


def test_lower_role_cannot_select_finance_rules_in_body(entry):
    client, _, model, _ = entry
    model.return_value = None
    response = client.post(
        _PATH, json={**_COST_RANK, "permission_tier": "finance_visible"},
        headers=_headers(role="warehouse_worker"),
    )
    assert response.status_code == 200
    assert response.json()["data"] is None
    assert model.await_args.args[0].permission_tier == "finance_hidden"
    assert response.json().get("code") == "RESULT_UNAVAILABLE"


def test_other_factory_cannot_reach_lsm_budget_cache_or_model(entry):
    client, budget, model, persist = entry
    model.return_value = None
    same_factory = client.post(_PATH, json=_COST_TREND, headers=_headers())
    assert same_factory.status_code == 200
    assert same_factory.json()["data"]["source"] == "rules"

    cross_factory = client.post(_PATH, json=_COST_TREND, headers=_headers(factory="F006"))
    assert cross_factory.status_code == 403
    assert cross_factory.json()["code"] == "FACTORY_MISMATCH"
    assert "data" not in cross_factory.json()
    chart_insight._get_service.assert_awaited_once()
    budget.check_budget.assert_awaited_once_with("LIUSHANMEN")
    model.assert_awaited_once()
    persist.assert_not_awaited()
