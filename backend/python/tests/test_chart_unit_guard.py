"""图表混单位闸：单元规则 + 经真实路由（/build、/batch）的接线断言。离线，不需要活服务。"""
import os
import sys

import pytest

_SMARTBI = os.path.join(os.path.dirname(__file__), "..", "smartbi")
if _SMARTBI not in sys.path:
    sys.path.insert(0, _SMARTBI)

from services.chart_unit_guard import mixed_unit_error  # noqa: E402

MIXED = [
    {"物料": "面粉", "数量": 10, "单位": "kg"},
    {"物料": "包装袋", "数量": 500, "单位": "个"},
]
SAME = [
    {"物料": "面粉", "数量": 10, "单位": "kg"},
    {"物料": "糖", "数量": 5, "单位": "kg"},
]


def test_mixed_units_rejected_with_reason():
    msg = mixed_unit_error(MIXED, ["数量"])
    assert msg and "kg" in msg and "个" in msg and "单一单位" in msg


def test_blank_unit_rejected_not_treated_as_zero_or_ok():
    rows = SAME + [{"物料": "盐", "数量": 3, "单位": ""}]
    assert "没有填写" in mixed_unit_error(rows, ["数量"])


def test_single_unit_passes_and_no_unit_column_passes():
    assert mixed_unit_error(SAME, ["数量"]) is None
    assert mixed_unit_error([{"月": "1", "数量": 3}], ["数量"]) is None


def test_non_quantity_field_not_blocked():
    assert mixed_unit_error(MIXED, ["金额"]) is None


def test_unspecified_y_fields_still_checked():
    assert mixed_unit_error(MIXED, None) is not None


@pytest.fixture
def client():
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from smartbi.api import chart

    app = FastAPI()
    app.include_router(chart.router, prefix="/api/chart")
    return TestClient(app)


def _payload(rows, y):
    return {"chartType": "bar", "data": rows, "xField": "物料", "yFields": y}


def test_build_route_refuses_mixed_units_and_never_calls_builder(client, monkeypatch):
    from smartbi.api import chart

    def boom(*a, **k):
        raise AssertionError("builder must not run for mixed units")

    monkeypatch.setattr(chart.chart_builder, "build", boom)
    body = client.post("/api/chart/build", json=_payload(MIXED, ["数量"])).json()
    assert body["success"] is False and "单一单位" in body["error"]
    assert body.get("config") is None


def test_build_route_still_builds_single_unit(client):
    body = client.post("/api/chart/build", json=_payload(SAME, ["数量"])).json()
    assert body["success"] is True and body["config"]["series"]


def test_batch_route_isolates_the_bad_chart(client):
    body = client.post("/api/chart/batch", json=[_payload(MIXED, ["数量"]), _payload(SAME, ["数量"])]).json()
    bad, good = body["data"]["charts"]
    assert bad["success"] is False and "单一单位" in bad["error"]
    assert good["success"] is True
