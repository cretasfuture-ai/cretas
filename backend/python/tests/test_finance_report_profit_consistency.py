"""财务报表页 (web-admin finance/reports) 收入/成本/毛利/利润率同一口径回归。

2026-11-17 事故: F006 只有销售数据 (smart_bi_sales_data), 没有 finance_data, 销售行
没录成本。GET /smart-bi/analysis/finance?analysisType=profit 实测返回:
  metrics: GROSS_PROFIT=29706.35, GROSS_MARGIN=100, ROI=0
  trendChart.data: [{period, grossProfit, netProfit(=毛利×0.70), grossMargin=100}]  ← 没有 revenue/cost
页面把 trend 点的 revenue/cost 加总 → 总收入 0、总成本 0; 毛利 29,706、利润率 100% 却来自
metrics —— 四张卡片自相矛盾, 而且 100% 是把「没录成本」当成「成本 0」算出来的假值。

口径 (修复后):
  - 趋势点 6 键 [period, revenue, cost, grossProfit, netProfit, grossMargin], 两条数据路径相同。
  - 有金额的销售行成本为 null 或 0 = 没录成本 (Excel 缺值写 0)。该期 cost/grossProfit/grossMargin
    = null; 汇总只要有一行缺成本, GROSS_PROFIT/GROSS_MARGIN/ROI = null, 毛利率不标 RED。
  - 销售回退的 netProfit = null (不再输出「毛利×0.70」)。

这些用例在 python-gate 收集的 backend/python/tests 下 (仓库根 tests/python/smartbi_compat
不进任何 CI workflow)。
"""
from __future__ import annotations

import asyncio
import os
from datetime import date
from decimal import Decimal

import jwt
import pytest

os.environ.setdefault("JWT_SECRET", "finance-report-consistency-test-secret")

from smartbi_compat.api import analysis_finance as mod  # noqa: E402
from smartbi_compat.auth import JWT_ALGORITHM  # noqa: E402
from smartbi_compat.date_range import DateRange  # noqa: E402

pytestmark = [pytest.mark.api_endpoint("analysis_finance")]

JWT_SECRET = "finance-report-consistency-test-secret"
_PATH = (
    "/api/mobile/F006/smart-bi/analysis/finance"
    "?startDate=2026-10-18&endDate=2026-11-17&analysisType=profit"
)


@pytest.fixture(autouse=True)
def _jwt_env(monkeypatch):
    monkeypatch.setenv("JWT_SECRET", JWT_SECRET)
    yield


@pytest.fixture(autouse=True)
def _factory_tenant_lookup(monkeypatch):
    class _Acquire:
        async def __aenter__(self):
            return object()

        async def __aexit__(self, *_args):
            return False

    class _Pool:
        def acquire(self):
            return _Acquire()

    async def factory_pool():
        return _Pool()

    async def factory_type(*_args, **_kwargs):
        from smartbi_compat.tenant import TenantType
        return TenantType.FACTORY

    monkeypatch.setattr("smartbi.config.get_cretas_pool", factory_pool)
    monkeypatch.setattr(mod, "get_tenant_type", factory_type)


def _token() -> str:
    from time import time as _time
    payload = {"userId": 1, "username": "f006_admin", "role": "factory_super_admin",
               "factoryId": "F006", "exp": int(_time()) + 3600}
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALGORITHM)


@pytest.fixture
def client():
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    app = FastAPI()
    app.include_router(mod.router)
    return TestClient(app)


def _f006_like_sales() -> list[dict]:
    """F006 实况形状: 两个月销售额合计 29,706.35, 成本没录 (一行 0, 一行 null)。"""
    return [
        {"amount": Decimal("21872.88"), "cost": Decimal("0"), "order_date": date(2026, 10, 20)},
        {"amount": Decimal("7833.47"), "cost": None, "order_date": date(2026, 11, 3)},
    ]


def _with_cost_sales() -> list[dict]:
    return [
        {"amount": Decimal("10000"), "cost": Decimal("7000"), "order_date": date(2026, 10, 20)},
        {"amount": Decimal("5000"), "cost": Decimal("3000"), "order_date": date(2026, 11, 3)},
    ]


def _patch_seams(monkeypatch, sales_rows, finance_rows=None):
    finance_rows = finance_rows or {}

    async def fake_finance(_fid, record_type, _s, _e):
        return finance_rows.get(record_type, [])

    async def fake_sales(_fid, _s, _e):
        return sales_rows

    monkeypatch.setattr("smartbi_compat.api.analysis_finance._query_finance_data", fake_finance)
    monkeypatch.setattr(
        "smartbi_compat.api.analysis_finance._query_finance_sales_fallback", fake_sales)


def _get_profit(client):
    resp = client.get(_PATH, headers={"Authorization": f"Bearer {_token()}"})
    assert resp.status_code == 200, resp.text[:300]
    data = resp.json()["data"]
    return {m["metricCode"]: m for m in data["metrics"]}, data["trendChart"]["data"]


# ---------------------------------------------------------------- 产品真实入口

def test_f006_sales_without_cost_revenue_is_real_and_margin_not_faked(client, monkeypatch):
    _patch_seams(monkeypatch, _f006_like_sales())
    metrics, points = _get_profit(client)

    # 趋势点带收入 → 页面加总得到真实收入, 不是 0
    assert [p["revenue"] for p in points] == [21872.88, 7833.47]
    assert round(sum(p["revenue"] for p in points), 2) == 29706.35
    # 成本没录 → 成本/毛利/毛利率都是缺数, 不是 0 / 29706.35 / 100
    assert [p["cost"] for p in points] == [None, None]
    assert [p["grossProfit"] for p in points] == [None, None]
    assert [p["grossMargin"] for p in points] == [None, None]
    assert metrics["GROSS_PROFIT"]["value"] is None
    assert metrics["GROSS_MARGIN"]["value"] is None
    assert metrics["ROI"]["value"] is None
    # 缺数不是「毛利率偏低」: 不能触发 RED 告警/洞察
    assert metrics["GROSS_MARGIN"]["alertLevel"] != "RED"


def test_sales_with_cost_cards_are_self_consistent(client, monkeypatch):
    """阳性对照: 成本齐全 → 收入-成本 == 毛利, 毛利/收入 == 毛利率, 与趋势点同口径。"""
    _patch_seams(monkeypatch, _with_cost_sales())
    metrics, points = _get_profit(client)

    revenue = sum(p["revenue"] for p in points)
    cost = sum(p["cost"] for p in points)
    assert (revenue, cost) == (15000, 10000)
    assert metrics["GROSS_PROFIT"]["value"] == revenue - cost == sum(p["grossProfit"] for p in points)
    assert metrics["GROSS_MARGIN"]["value"] == pytest.approx((revenue - cost) / revenue * 100, abs=0.01)
    assert [p["grossMargin"] for p in points] == [30, 40]
    # 销售数据没有费用 → 净利润不编 (旧: 毛利×0.70)
    assert [p["netProfit"] for p in points] == [None, None]


def test_trend_points_have_same_six_keys_on_both_paths(client, monkeypatch):
    _patch_seams(monkeypatch, _with_cost_sales())
    _, sales_points = _get_profit(client)
    _patch_seams(monkeypatch, [], {
        "REVENUE": [{"actual_amount": Decimal("100"), "category": "营业收入",
                     "record_date": date(2026, 10, 20), "upload_id": 1}],
        "COST": [{"total_cost": Decimal("60"), "actual_amount": None,
                  "record_date": date(2026, 10, 20), "upload_id": 1}],
    })
    _, finance_points = _get_profit(client)
    keys = ["period", "revenue", "cost", "grossProfit", "netProfit", "grossMargin"]
    assert [list(p.keys()) for p in sales_points] == [keys, keys]
    assert [list(p.keys()) for p in finance_points] == [keys]


# ---------------------------------------------------------------- 口径细节

def _metrics(sales_rows, finance_rows=None):
    finance_rows = finance_rows or {}
    orig_f, orig_s = mod._query_finance_data, mod._query_finance_sales_fallback

    async def fake_finance(_fid, rt, _s, _e):
        return finance_rows.get(rt, [])

    async def fake_sales(_fid, _s, _e):
        return sales_rows
    try:
        mod._query_finance_data = fake_finance
        mod._query_finance_sales_fallback = fake_sales
        rng = DateRange.custom(date(2026, 1, 1), date(2026, 12, 31))
        return {m["metricCode"]: m for m in asyncio.run(mod._get_profit_metrics("F006", rng))}
    finally:
        mod._query_finance_data, mod._query_finance_sales_fallback = orig_f, orig_s


def test_partial_cost_makes_that_period_and_total_missing():
    rows = [
        {"amount": Decimal("1000"), "cost": Decimal("600"), "order_date": date(2026, 5, 1)},
        {"amount": Decimal("2000"), "cost": Decimal("1500"), "order_date": date(2026, 6, 1)},
        {"amount": Decimal("500"), "cost": None, "order_date": date(2026, 6, 9)},
    ]
    may, jun = mod._aggregate_profit_by_period_sales(rows, "MONTH")
    assert (may["revenue"], may["cost"], may["grossProfit"], may["grossMargin"]) == (1000, 600, 400, 40)
    assert (jun["revenue"], jun["cost"], jun["grossProfit"], jun["grossMargin"]) == (2500, None, None, None)
    m = _metrics(rows)
    assert m["GROSS_PROFIT"]["value"] is None and m["GROSS_MARGIN"]["value"] is None


def test_finance_data_revenue_without_cost_records_is_missing():
    m = _metrics([], {"REVENUE": [{"actual_amount": Decimal("100000"), "category": "营业收入",
                                   "record_date": date(2026, 6, 1), "upload_id": 1}]})
    assert m["GROSS_PROFIT"]["value"] is None
    assert m["GROSS_MARGIN"]["value"] is None
    assert m["GROSS_MARGIN"]["alertLevel"] != "RED"
    assert m["ROI"]["value"] is None
    point, = mod._build_profit_chart_from_finance_data(
        [{"actual_amount": Decimal("100000"), "category": "营业收入",
          "record_date": date(2026, 6, 1), "upload_id": 1}], [], "MONTH")
    assert (point["revenue"], point["cost"], point["grossProfit"], point["grossMargin"]) == (
        100000, None, None, None)


def test_no_data_at_all_keeps_java_golden_zero_metrics():
    """完全无数据 (F999 空态) 保持 Java golden 的 5 个 0 指标, 不改 parity 契约; 页面在
    趋势点为空时自己把四张卡片都显示为「—」。"""
    m = _metrics([])
    assert m["GROSS_PROFIT"]["value"] == 0
    assert m["GROSS_MARGIN"]["value"] == 0
    assert m["ROI"]["value"] == 0


# ---------------------------------------------------------------- 复审 M1: 按上传批次判断「没录成本」

def test_complete_costs_plus_one_gift_row_still_gives_gross_profit():
    """阴性对照: 成本齐全的批次里有一行赠品 (金额 > 0, 成本 0) = 真 0, 毛利照常给出。"""
    rows = [
        {"amount": Decimal("10000"), "cost": Decimal("6000"), "order_date": date(2026, 10, 5), "upload_id": 7},
        {"amount": Decimal("200"), "cost": Decimal("0"), "order_date": date(2026, 10, 6), "upload_id": 7},
    ]
    m = _metrics(rows)
    assert m["GROSS_PROFIT"]["value"] == 4200
    assert m["GROSS_MARGIN"]["value"] == pytest.approx(41.18, abs=0.01)
    assert m["ROI"]["value"] is not None
    point, = mod._aggregate_profit_by_period_sales(rows, "MONTH")
    assert (point["revenue"], point["cost"], point["grossProfit"]) == (10200, 6000, 4200)


def test_batch_with_all_zero_cost_is_missing_even_if_other_batch_has_cost():
    """整批成本全是 0 = 那次上传没录成本列 → 缺数, 即使另一批成本齐全。"""
    rows = [
        {"amount": Decimal("10000"), "cost": Decimal("6000"), "order_date": date(2026, 10, 5), "upload_id": 7},
        {"amount": Decimal("3000"), "cost": Decimal("0"), "order_date": date(2026, 11, 2), "upload_id": 8},
        {"amount": Decimal("1000"), "cost": Decimal("0"), "order_date": date(2026, 11, 9), "upload_id": 8},
    ]
    oct_, nov = mod._aggregate_profit_by_period_sales(rows, "MONTH")
    assert (oct_["cost"], oct_["grossProfit"]) == (6000, 4000)
    assert (nov["revenue"], nov["cost"], nov["grossProfit"]) == (4000, None, None)
    m = _metrics(rows)
    assert m["GROSS_PROFIT"]["value"] is None and m["GROSS_MARGIN"]["value"] is None


# ---------------------------------------------------------------- 复审 M2: finance_data 按月判断

def _rev(month, amount):
    return {"actual_amount": Decimal(amount), "category": "营业收入",
            "record_date": date(2026, month, 15), "upload_id": 1}


def _cost(month, amount):
    return {"total_cost": Decimal(amount), "actual_amount": None,
            "record_date": date(2026, month, 15), "upload_id": 1}


def test_finance_month_with_revenue_but_no_cost_nulls_metrics():
    """10 月有收入有成本, 11 月只有收入 → 不能用「两个月收入 − 10 月成本」算毛利率。"""
    m = _metrics([], {"REVENUE": [_rev(10, "10000"), _rev(11, "8000")], "COST": [_cost(10, "6000")]})
    assert m["GROSS_PROFIT"]["value"] is None
    assert m["GROSS_MARGIN"]["value"] is None
    assert m["GROSS_MARGIN"]["alertLevel"] != "RED"
    assert m["ROI"]["value"] is None


def test_finance_month_with_cost_but_no_revenue_is_left_out_of_gross():
    """10 月收入、成本都有, 11 月只有成本 → 毛利只用 10 月算, 与趋势点、页面合计同一口径。"""
    finance = {"REVENUE": [_rev(10, "10000")], "COST": [_cost(10, "6000"), _cost(11, "500")]}
    m = _metrics([], finance)
    assert m["GROSS_PROFIT"]["value"] == 4000
    assert m["GROSS_MARGIN"]["value"] == 40
    assert m["ROI"]["value"] == pytest.approx(66.67, abs=0.01)
    oct_, nov = mod._build_profit_chart_from_finance_data(finance["REVENUE"], finance["COST"], "MONTH")
    assert (oct_["revenue"], oct_["cost"], oct_["grossProfit"]) == (10000, 6000, 4000)
    assert (nov["revenue"], nov["cost"], nov["grossProfit"]) == (None, 500, None)


def test_same_upload_cost_in_october_does_not_cover_empty_november():
    """复审 2 轮中1: 同一批上传 10 月有成本、11 月成本格为空 (写成 0)。
    查 10–11 月与只查 11 月, 11 月都必须是缺数 —— 结果不能取决于查询区间。"""
    oct_rows = [{"amount": Decimal("10000"), "cost": Decimal("6000"), "order_date": date(2026, 10, 5), "upload_id": 9}]
    nov_rows = [{"amount": Decimal("5000"), "cost": Decimal("0"), "order_date": date(2026, 11, 5), "upload_id": 9}]

    both = mod._aggregate_profit_by_period_sales(oct_rows + nov_rows, "MONTH")
    nov_only = mod._aggregate_profit_by_period_sales(nov_rows, "MONTH")
    assert (both[1]["cost"], both[1]["grossMargin"]) == (None, None)
    assert (nov_only[0]["cost"], nov_only[0]["grossMargin"]) == (None, None)
    assert _metrics(oct_rows + nov_rows)["GROSS_MARGIN"]["value"] is None
    assert _metrics(nov_rows)["GROSS_MARGIN"]["value"] is None
