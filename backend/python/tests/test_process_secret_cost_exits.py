"""工艺保密 (process secret): SmartBI Python cost exits.

Owner decisions under test:

* A protected product's cost amounts are hidden from callers without full-view
  authorization; quantities and revenue stay visible.
* A factory-wide cost total shown to such a caller is the SUM OF THE ALLOWED
  PRODUCTS' ROWS (computed here by iterating the allowed rows, never as
  "total - secret"), annotated with excludedProtectedProductCount / 「不含 N 个保密产品」.
* Authorized callers (unscopedCostAllowed) see the full total (negative control);
  a factory whose products are all unprotected is unchanged.
* Authorization failure fails closed: cost amounts null (not 0), totals null with a reason.

Java owns the policy; here ``cost_authorization.authorize_batches`` is replaced
by a fake with the same contract as SmartBICostProductAccessController
(unprotected always allowed; protected only when granted; unscoped flag =
every protected product granted).
"""
from __future__ import annotations

import asyncio
import os
from datetime import date, datetime
from decimal import Decimal, ROUND_HALF_UP
from time import time
from types import SimpleNamespace

os.environ.setdefault("JWT_SECRET", "process-secret-cost-exits-test-secret-0123456789")

import jwt as jwt_lib  # noqa: E402
import pytest  # noqa: E402

from smartbi.services.factory_charts import cost_authorization, cost_scope  # noqa: E402

FACTORY = "F006"
SECRET = "P-SECRET"
PUBLIC = "P-PUB"
PUBLIC2 = "P-PUB2"


# ─── Java authority fake ─────────────────────────────────────────────────


class _Java:
    def __init__(self, protected=frozenset({SECRET}), granted=frozenset(), fail=False):
        self.protected = set(protected)
        self.granted = set(granted)
        self.fail = fail
        self.calls: list[dict] = []

    async def authorize_batches(self, request, factory_id, product_ids, *, unscoped=False):
        headers = getattr(request, "headers", {}) or {}
        self.calls.append({"factory": factory_id, "ids": list(product_ids),
                           "bearer": str(headers.get("authorization", ""))})
        if self.fail:
            raise cost_authorization.CostAuthorizationError("java authority unavailable")
        allowed = frozenset(p for p in product_ids if p not in self.protected or p in self.granted)
        return cost_authorization.CostAuthorization(
            factory_id, 42, allowed, self.protected <= self.granted)


@pytest.fixture
def java(monkeypatch):
    def install(**kwargs):
        fake = _Java(**kwargs)
        monkeypatch.setattr(cost_authorization, "authorize_batches", fake.authorize_batches)
        return fake
    return install


def _req(role="factory_super_admin"):
    return SimpleNamespace(
        state=SimpleNamespace(factory_id=FACTORY, user_id=42, role=role),
        headers={"authorization": "Bearer caller-token"},
    )


def _token(role="factory_super_admin"):
    return jwt_lib.encode(
        {"userId": 42, "username": "u", "factoryId": FACTORY, "role": role,
         "exp": int(time()) + 3600},
        os.environ["JWT_SECRET"], algorithm="HS256")


# ─── cost_scope unit ────────────────────────────────────────────────────


async def test_resolver_without_request_fails_closed_without_calling_java(java):
    fake = java()
    scope = await cost_scope.CostScopeResolver(None, FACTORY).scope_for([PUBLIC])
    assert scope.is_unavailable and not scope.allows(PUBLIC) and not scope.allows(None)
    assert fake.calls == []


async def test_resolver_caches_decisions_and_failure_is_sticky(java):
    fake = java()
    resolver = cost_scope.CostScopeResolver(_req(), FACTORY)
    first = await resolver.scope_for([PUBLIC, SECRET])
    again = await resolver.scope_for([SECRET])
    assert first.allows(PUBLIC) and not first.allows(SECRET)
    assert not again.allows(SECRET)
    assert len(fake.calls) == 1  # second question answered from cache
    fake.fail = True
    third = await resolver.scope_for([PUBLIC2])
    assert third.is_unavailable
    assert (await resolver.scope_for([PUBLIC])).is_unavailable  # sticky


# ─── Gold fixture (agg_factory_batch_daily / fact_production_report) ────


def _gold(day, pid, qty, mat, labor, equip, other):
    costs = [mat, labor, equip, other]
    total = None if all(c is None for c in costs) else sum(c or 0 for c in costs)
    return {
        "factory_id": FACTORY, "stat_date": day, "product_type_id": pid,
        "batch_count": 1, "total_planned_qty": Decimal(qty), "total_actual_qty": Decimal(qty),
        "total_good_qty": Decimal(qty) - 1, "avg_yield_rate": Decimal("90"),
        "total_material_cost": None if mat is None else Decimal(mat),
        "total_labor_cost": None if labor is None else Decimal(labor),
        "total_equipment_cost": None if equip is None else Decimal(equip),
        "total_other_cost": None if other is None else Decimal(other),
        "total_cost": None if total is None else Decimal(total),
        "version": 1, "updated_at": None,
    }


D1, D2 = date(2026, 5, 1), date(2026, 5, 2)
PRODUCT_ROWS = [
    _gold(D1, PUBLIC, "10", 60, 20, 10, 10),      # 100
    _gold(D1, PUBLIC2, "5", 30, 10, 5, 5),        # 50
    _gold(D1, SECRET, "20", 600, 200, 50, 50),    # 900 (most expensive)
    _gold(D2, PUBLIC, "12", 70, 30, 10, 10),      # 120
    _gold(D2, PUBLIC2, "4", None, None, None, None),  # uncosted
    _gold(D2, SECRET, "15", 500, 100, 50, 50),    # 700
]
_COST_COLUMNS = ("total_material_cost", "total_labor_cost", "total_equipment_cost",
                 "total_other_cost", "total_cost")


def _rollup(product_rows):
    """The ETL all-product rollup: per-day SUM over every product (honest null)."""
    out = []
    for day in sorted({r["stat_date"] for r in product_rows}):
        rows = [r for r in product_rows if r["stat_date"] == day]
        row = dict(rows[0], product_type_id=None,
                   batch_count=sum(r["batch_count"] for r in rows),
                   total_planned_qty=sum(r["total_planned_qty"] for r in rows),
                   total_actual_qty=sum(r["total_actual_qty"] for r in rows),
                   total_good_qty=sum(r["total_good_qty"] for r in rows))
        for col in _COST_COLUMNS:
            vals = [r[col] for r in rows if r[col] is not None]
            row[col] = sum(vals) if vals else None
        out.append(row)
    return out


def _report(pk, batch, pid, labor, material):
    return {
        "source_pk": pk, "batch_id": batch, "process_order": 1, "process_category": "mix",
        "report_date": D1, "report_kind": "YIELD", "input_qty": Decimal("10"),
        "output_qty": Decimal("9"), "good_qty": Decimal("9"), "yield_rate": Decimal("90"),
        "total_work_minutes": 60, "total_workers": 2,
        "labor_cost": Decimal(labor), "material_cost": Decimal(material),
        "cost_source": "reported", "product_type_id": pid,
    }


REPORT_ROWS = [
    _report("r1", "b-pub", PUBLIC, "11", "22"),
    _report("r2", "b-secret", SECRET, "33", "44"),
    _report("r3", "b-unfinished", None, "55", "66"),  # batch not in fact_production_batch
]


class _GoldConn:
    def __init__(self, product_rows, report_rows):
        self.product_rows, self.report_rows, self.sqls = product_rows, report_rows, []

    def transaction(self):
        class _Tx:
            async def __aenter__(self_inner):
                return self_inner

            async def __aexit__(self_inner, *exc):
                return False
        return _Tx()

    async def execute(self, sql, *args):
        return None

    async def fetch(self, sql, *args):
        self.sqls.append(sql)
        if "fact_production_report" in sql:
            return self.report_rows
        if "product_type_id IS NULL" in sql:
            return _rollup(self.product_rows)
        if "product_type_id = $" in sql:
            return [r for r in self.product_rows if r["product_type_id"] == args[-1]]
        return self.product_rows


@pytest.fixture
def gold(monkeypatch):
    def install(product_rows=PRODUCT_ROWS, report_rows=REPORT_ROWS):
        conn = _GoldConn(product_rows, report_rows)

        class _Pool:
            def acquire(self):
                class _Ctx:
                    async def __aenter__(self_inner):
                        return conn

                    async def __aexit__(self_inner, *exc):
                        return False
                return _Ctx()

        async def get_pg_pool():
            return _Pool()

        import smartbi.config
        monkeypatch.setattr(smartbi.config, "get_pg_pool", get_pg_pool)
        return conn
    return install


def _allowed_day_sum(product_rows, day, column, allowed):
    """Expected factory total for a day: iterate the ALLOWED rows and add them up."""
    values = [float(r[column]) for r in product_rows
              if r["stat_date"] == day and r["product_type_id"] in allowed and r[column] is not None]
    return round(sum(values), 2) if values else None


# ─── /factory/cost-structure ────────────────────────────────────────────


async def test_cost_structure_unauthorized_total_is_sum_of_allowed_products(java, gold):
    from smartbi.api import factory_production_gold as g
    java(); gold()
    data = (await g.cost_structure(_req(), None, None))["data"]
    allowed = {PUBLIC, PUBLIC2}
    rollup = {r["stat_date"].isoformat(): r for r in _rollup(PRODUCT_ROWS)}
    for row in data["rows"]:
        day = date.fromisoformat(row["statDate"])
        for key, col in (("totalCost", "total_cost"), ("totalMaterialCost", "total_material_cost"),
                         ("totalLaborCost", "total_labor_cost")):
            assert row[key] == pytest.approx(_allowed_day_sum(PRODUCT_ROWS, day, col, allowed))
        # quantities stay the full factory figures
        assert row["batchCount"] == rollup[row["statDate"]]["batch_count"]
        assert row["totalActualQty"] == pytest.approx(float(rollup[row["statDate"]]["total_actual_qty"]))
    expected_total = sum(float(r["total_cost"]) for r in PRODUCT_ROWS
                         if r["product_type_id"] in allowed and r["total_cost"] is not None)
    assert data["summary"]["totalCost"] == pytest.approx(expected_total)          # 270
    assert data["summary"]["totalCost"] != pytest.approx(
        sum(float(r["total_cost"]) for r in _rollup(PRODUCT_ROWS)))                # not 1870
    assert data["costScope"] == {"status": "ALLOWED_PRODUCTS_ONLY",
                                 "excludedProtectedProductCount": 1, "note": "不含 1 个保密产品"}


async def test_cost_structure_authorized_sees_full_rollup(java, gold):
    from smartbi.api import factory_production_gold as g
    java(granted={SECRET}); gold()
    data = (await g.cost_structure(_req(), None, None))["data"]
    assert [r["totalCost"] for r in data["rows"]] == [
        float(r["total_cost"]) for r in _rollup(PRODUCT_ROWS)]
    assert data["summary"]["totalCost"] == pytest.approx(1870.0)
    assert "costScope" not in data


async def test_cost_structure_unprotected_only_factory_unchanged(java, gold):
    from smartbi.api import factory_production_gold as g
    java(protected=frozenset())
    rows = [r for r in PRODUCT_ROWS if r["product_type_id"] != SECRET]
    gold(product_rows=rows)
    data = (await g.cost_structure(_req(), None, None))["data"]
    assert [r["totalCost"] for r in data["rows"]] == [
        float(r["total_cost"]) if r["total_cost"] is not None else None for r in _rollup(rows)]
    assert "costScope" not in data


async def test_cost_structure_authority_failure_fails_closed(java, gold):
    from smartbi.api import factory_production_gold as g
    java(fail=True); gold()
    data = (await g.cost_structure(_req(), None, None))["data"]
    assert all(r["totalCost"] is None and r["totalMaterialCost"] is None for r in data["rows"])
    assert data["summary"]["totalCost"] is None  # null, not 0
    assert data["rows"][0]["totalActualQty"] is not None
    assert data["costScope"]["status"] == "UNAVAILABLE" and data["costScope"]["reason"]


# ─── /factory/yield-trend ───────────────────────────────────────────────


async def test_yield_trend_rollup_rebuilt_from_allowed_products(java, gold):
    from smartbi.api import factory_production_gold as g
    java(); gold()
    data = (await g.yield_trend(_req(), None, None, None))["data"]
    for point in data["trend"]:
        day = date.fromisoformat(point["statDate"])
        assert point["totalCost"] == pytest.approx(
            _allowed_day_sum(PRODUCT_ROWS, day, "total_cost", {PUBLIC, PUBLIC2}))
    assert data["costScope"]["excludedProtectedProductCount"] == 1


async def test_yield_trend_rollup_authorized_unchanged(java, gold):
    from smartbi.api import factory_production_gold as g
    java(granted={SECRET}); gold()
    data = (await g.yield_trend(_req(), None, None, None))["data"]
    assert [p["totalCost"] for p in data["trend"]] == [900.0 + 150.0, 820.0]
    assert "costScope" not in data


async def test_yield_trend_single_secret_product_hides_cost_keeps_quantity(java, gold):
    from smartbi.api import factory_production_gold as g
    java(); gold()
    data = (await g.yield_trend(_req(), None, None, SECRET))["data"]
    assert data["trend"] and all(p["totalCost"] is None for p in data["trend"])
    assert [p["totalActualQty"] for p in data["trend"]] == [20.0, 15.0]
    assert data["processSecret"]["visibility"] == "TOTALS_ONLY"
    assert data["processSecret"]["hiddenFields"] == ["totalCost"]
    public = (await g.yield_trend(_req(), None, None, PUBLIC))["data"]
    assert [p["totalCost"] for p in public["trend"]] == [100.0, 120.0]
    assert "processSecret" not in public


async def test_yield_trend_single_product_authority_failure_fails_closed(java, gold):
    from smartbi.api import factory_production_gold as g
    java(fail=True); gold()
    data = (await g.yield_trend(_req(), None, None, PUBLIC))["data"]
    assert all(p["totalCost"] is None for p in data["trend"])
    assert data["processSecret"]["visibility"] == "COST_UNVERIFIED"


# ─── /factory/product-cost-compare ──────────────────────────────────────


async def test_product_compare_hides_secret_costs_keeps_quantities_and_rank_order(java, gold):
    from smartbi.api import factory_production_gold as g
    java(); gold()
    products = (await g.product_cost_compare(_req(), None, None))["data"]["products"]
    by_id = {p["productTypeId"]: p for p in products}
    secret = by_id[SECRET]
    for field in ("totalMaterialCost", "totalLaborCost", "totalEquipmentCost",
                  "totalOtherCost", "totalCost", "costPerUnit"):
        assert secret[field] is None
    assert secret["batchCount"] == 2 and secret["totalActualQty"] == pytest.approx(35.0)
    assert secret["processSecret"]["visibility"] == "TOTALS_ONLY"
    assert "totalCost" in secret["processSecret"]["hiddenFields"]
    # The secret product is the most expensive one; its position must not say so.
    assert [p["productTypeId"] for p in products] == [PUBLIC, PUBLIC2, SECRET]
    assert by_id[PUBLIC]["totalCost"] == pytest.approx(220.0)
    assert "processSecret" not in by_id[PUBLIC]


async def test_product_compare_authorized_sees_secret_cost_first(java, gold):
    from smartbi.api import factory_production_gold as g
    java(granted={SECRET}); gold()
    products = (await g.product_cost_compare(_req(), None, None))["data"]["products"]
    assert [p["productTypeId"] for p in products] == [SECRET, PUBLIC, PUBLIC2]
    assert products[0]["totalCost"] == pytest.approx(1600.0)


async def test_product_compare_authority_failure_hides_every_cost(java, gold):
    from smartbi.api import factory_production_gold as g
    java(fail=True); gold()
    products = (await g.product_cost_compare(_req(), None, None))["data"]["products"]
    assert all(p["totalCost"] is None and p["costPerUnit"] is None for p in products)
    assert all(p["processSecret"]["visibility"] == "COST_UNVERIFIED" for p in products)
    assert all(p["totalActualQty"] is not None for p in products)


# ─── /factory/process-cost ──────────────────────────────────────────────


async def test_process_cost_hides_secret_and_unresolved_reports(java, gold):
    from smartbi.api import factory_production_gold as g
    java(); conn = gold()
    data = (await g.process_cost(_req(), None, None, None, False))["data"]
    sql = [s for s in conn.sqls if "fact_production_report" in s][0]
    assert "LEFT JOIN fact_production_batch b" in sql and "b.source_pk = r.batch_id" in sql
    by_pk = {r["sourcePk"]: r for r in data["reports"]}
    assert by_pk["r1"]["laborCost"] == pytest.approx(11.0) and by_pk["r1"]["materialCost"] == pytest.approx(22.0)
    for pk in ("r2", "r3"):
        assert by_pk[pk]["laborCost"] is None and by_pk[pk]["materialCost"] is None
        assert by_pk[pk]["outputQty"] == pytest.approx(9.0)
        assert "laborCost" in by_pk[pk]["processSecret"]["hiddenFields"]
    meta = data["meta"]
    assert meta["totalReports"] == 3 and meta["reportsWithCost"] == 1
    assert meta["hiddenCostReports"] == 2 and meta["costCoverageRate"] == 100.0
    assert data["costScope"]["excludedProtectedProductCount"] == 1


async def test_process_cost_authorized_sees_all(java, gold):
    from smartbi.api import factory_production_gold as g
    java(granted={SECRET}); gold()
    data = (await g.process_cost(_req(), None, None, None, False))["data"]
    assert [r["laborCost"] for r in data["reports"]] == [11.0, 33.0, 55.0]
    assert data["meta"]["reportsWithCost"] == 3 and "costScope" not in data


async def test_process_cost_authority_failure_fails_closed(java, gold):
    from smartbi.api import factory_production_gold as g
    java(fail=True); gold()
    data = (await g.process_cost(_req(), None, None, None, False))["data"]
    assert all(r["laborCost"] is None and r["materialCost"] is None for r in data["reports"])
    assert data["meta"]["costCoverageRate"] is None
    assert data["costScope"]["status"] == "UNAVAILABLE"


# ─── /sales-preset/gross-margin ─────────────────────────────────────────


def _gm(pid, revenue, cost):
    return {
        "product_type_id": pid, "product_name": pid, "unit": "kg",
        "total_qty": Decimal("10"), "order_count": 1, "line_count": 1,
        "costed_line_count": 1, "zero_cost_line_count": 0, "missing_price_line_count": 0,
        "revenue": Decimal(revenue), "costed_revenue": Decimal(revenue), "cost": Decimal(cost),
    }


GM_ROWS = [
    _gm(PUBLIC, "1000", "700"),   # 30%
    _gm(SECRET, "2000", "200"),   # 90% — would rank first
    _gm(PUBLIC2, "500", "450"),   # 10%
]


@pytest.fixture
def sales_pool(monkeypatch):
    from smartbi.api import sales_preset

    class _Conn:
        async def execute(self, *args):
            return None

        async def fetch(self, sql, *args):
            return GM_ROWS

    class _Pool:
        def acquire(self):
            class _Ctx:
                async def __aenter__(self_inner):
                    return _Conn()

                async def __aexit__(self_inner, *exc):
                    return False
            return _Ctx()

    async def _get_pool():
        return _Pool()

    monkeypatch.setattr(sales_preset, "_get_pool", _get_pool)
    return sales_preset


async def test_gross_margin_hides_secret_and_sums_allowed_only(java, sales_pool):
    java()
    data = (await sales_pool.gross_margin(FACTORY, _req(), "2026-09-01", "2026-09-30"))["data"]
    ranked = [r["productTypeId"] for r in data["rank"]]
    assert ranked == [PUBLIC, PUBLIC2, SECRET]  # 90% secret margin does not lead
    secret = data["rank"][2]
    for field in ("cost", "grossProfit", "marginRate", "costCoverage", "costedRevenue"):
        assert secret[field] is None
    assert secret["revenue"] == pytest.approx(2000.0) and secret["totalQty"] == pytest.approx(10.0)
    assert secret["processSecret"]["visibility"] == "TOTALS_ONLY"
    allowed = [r for r in GM_ROWS if r["product_type_id"] != SECRET]
    exp_cost = sum(float(r["cost"]) for r in allowed)
    exp_costed = sum(float(r["costed_revenue"]) for r in allowed)
    s = data["summary"]
    assert s["cost"] == pytest.approx(exp_cost)
    assert s["grossProfit"] == pytest.approx(exp_costed - exp_cost)
    assert s["marginRate"] == pytest.approx((exp_costed - exp_cost) / exp_costed)
    assert s["revenue"] == pytest.approx(3500.0)  # revenue stays factory-wide
    assert s["costScope"]["excludedProtectedProductCount"] == 1
    assert s["costScope"]["note"] == "不含 1 个保密产品"


async def test_gross_margin_authorized_includes_secret(java, sales_pool):
    java(granted={SECRET})
    data = (await sales_pool.gross_margin(FACTORY, _req(), "2026-09-01", "2026-09-30"))["data"]
    assert [r["productTypeId"] for r in data["rank"]] == [SECRET, PUBLIC, PUBLIC2]
    assert data["summary"]["cost"] == pytest.approx(1350.0)
    assert "costScope" not in data["summary"]


async def test_gross_margin_authority_failure_fails_closed(java, sales_pool):
    java(fail=True)
    data = (await sales_pool.gross_margin(FACTORY, _req(), "2026-09-01", "2026-09-30"))["data"]
    assert all(r["cost"] is None and r["marginRate"] is None for r in data["rank"])
    s = data["summary"]
    assert s["cost"] is None and s["grossProfit"] is None and s["marginRate"] is None
    assert s["revenue"] == pytest.approx(3500.0)
    assert s["costScope"]["status"] == "UNAVAILABLE"


# ─── /analysis/quality TOTAL_QUALITY_COST ───────────────────────────────


def _quality_cost(result):
    metric = next(m for m in result["metrics"] if m["metricCode"] == "TOTAL_QUALITY_COST")
    chart = next(d for d in result["costChart"]["data"] if d["category"] == "生产总成本")
    return metric, chart


async def test_quality_cost_unauthorized_sums_allowed_products(java, gold):
    from smartbi_compat.api.analysis_quality import _factory_quality_dispatch
    java(); gold()
    resolver = cost_scope.CostScopeResolver(_req(), FACTORY)
    result = await _factory_quality_dispatch(FACTORY, D1, D2, "rework", cost_resolver=resolver)
    metric, chart = _quality_cost(result)
    expected = sum(float(r["total_cost"]) for r in PRODUCT_ROWS
                   if r["product_type_id"] != SECRET and r["total_cost"] is not None)
    assert metric["value"] == pytest.approx(expected) and chart["cost"] == pytest.approx(expected)
    assert "不含 1 个保密产品" in metric["description"]
    assert result["costScope"]["excludedProtectedProductCount"] == 1


async def test_quality_cost_authorized_full_total(java, gold):
    from smartbi_compat.api.analysis_quality import _factory_quality_dispatch
    java(granted={SECRET}); gold()
    resolver = cost_scope.CostScopeResolver(_req(), FACTORY)
    result = await _factory_quality_dispatch(FACTORY, D1, D2, "rework", cost_resolver=resolver)
    metric, chart = _quality_cost(result)
    assert metric["value"] == pytest.approx(1870.0) and chart["cost"] == pytest.approx(1870.0)
    assert "costScope" not in result


async def test_quality_cost_fails_closed_without_resolver_or_on_failure(java, gold):
    from smartbi_compat.api.analysis_quality import _factory_quality_dispatch
    java(fail=True); gold()
    for kwargs in ({}, {"cost_resolver": cost_scope.CostScopeResolver(_req(), FACTORY)}):
        result = await _factory_quality_dispatch(FACTORY, D1, D2, "rework", **kwargs)
        metric, chart = _quality_cost(result)
        assert metric["value"] is None and chart["cost"] is None
        assert result["costScope"]["status"] == "UNAVAILABLE"


def test_quality_route_wires_the_callers_bearer_into_the_authority(java, gold, monkeypatch):
    """Product entry: GET /analysis/quality must ask Java with the caller's token."""
    import smartbi.config
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from smartbi_compat.api import analysis_quality

    fake = java(); gold()

    async def no_cretas_pool():
        raise RuntimeError("tenant lookup unavailable in test → FACTORY")

    monkeypatch.setattr(smartbi.config, "get_cretas_pool", no_cretas_pool)
    app = FastAPI()
    app.include_router(analysis_quality.router)
    token = _token()
    r = TestClient(app).get(f"/api/mobile/{FACTORY}/smart-bi/analysis/quality",
                            params={"startDate": "2026-05-01", "endDate": "2026-05-02",
                                    "analysisType": "rework"},
                            headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 200, r.text
    assert fake.calls and fake.calls[0]["bearer"] == f"Bearer {token}"
    assert r.json()["data"]["costScope"]["excludedProtectedProductCount"] == 1


# ─── /analysis/inventory consumption ────────────────────────────────────


def _cons(pid, cost, *, linked=True, when=datetime(2026, 5, 3, 10)):
    return {"total_cost": Decimal(cost), "cost_product_type_id": pid,
            "cost_link_present": linked, "consumption_time": when}


CONSUMPTIONS = [
    _cons(PUBLIC, "300"),
    _cons(SECRET, "5000"),
    _cons(None, "200", linked=False),        # raw material, no product → stays
    _cons(None, "999", linked=True),         # batch exists but product unresolved → left out
]


@pytest.fixture
def inventory(monkeypatch):
    from smartbi_compat.api import analysis_inventory as mod

    async def inv_total(factory_id):
        return Decimal("10000")

    async def consumptions(factory_id, start_date, end_date):
        return CONSUMPTIONS

    async def batches(factory_id, status="AVAILABLE"):
        return []

    monkeypatch.setattr(mod, "_query_inventory_value_total", inv_total)
    monkeypatch.setattr(mod, "_query_material_consumptions_in_range", consumptions)
    monkeypatch.setattr(mod, "_query_material_batches_by_status", batches)
    return mod


def _turnover_for(consumption_total, days, inventory_value=Decimal("10000")):
    annualized = (consumption_total * Decimal("365") / Decimal(days)).quantize(
        Decimal("0.0001"), rounding=ROUND_HALF_UP)
    rate = (annualized / inventory_value).quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP)
    return float(rate.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))


async def test_inventory_consumption_unauthorized_sums_allowed_and_unlinked_rows(java, inventory):
    java()
    resolver = cost_scope.CostScopeResolver(_req(), FACTORY)
    result = await inventory._get_turnover_mode(FACTORY, date(2026, 5, 1), date(2026, 5, 31),
                                                cost_resolver=resolver)
    by = {m["metricCode"]: m for m in result["metrics"]}
    expected = Decimal("0")
    for c in CONSUMPTIONS:
        if c["cost_product_type_id"] == PUBLIC or not c["cost_link_present"]:
            expected += c["total_cost"]
    assert by["CONSUMPTION_AMOUNT"]["value"] == int(expected)          # 500
    assert by["TURNOVER_RATE"]["value"] == pytest.approx(_turnover_for(expected, 31))
    assert by["INVENTORY_VALUE"]["value"] == 10000
    assert "不含 1 个保密产品" in by["CONSUMPTION_AMOUNT"]["description"]
    assert result["trendChart"]["data"][0]["consumption"] == int(expected)
    assert result["costScope"]["excludedProtectedProductCount"] == 1
    assert result["costScope"]["unresolvedProductRowCount"] == 1


async def test_inventory_consumption_authorized_full(java, inventory):
    java(granted={SECRET})
    resolver = cost_scope.CostScopeResolver(_req(), FACTORY)
    result = await inventory._get_turnover_mode(FACTORY, date(2026, 5, 1), date(2026, 5, 31),
                                                cost_resolver=resolver)
    by = {m["metricCode"]: m for m in result["metrics"]}
    assert by["CONSUMPTION_AMOUNT"]["value"] == 6499
    assert by["CONSUMPTION_AMOUNT"]["description"] is None
    assert "costScope" not in result


async def test_inventory_consumption_fails_closed(java, inventory):
    java(fail=True)
    for resolver in (None, cost_scope.CostScopeResolver(_req(), FACTORY)):
        result = await inventory._get_turnover_mode(FACTORY, date(2026, 5, 1), date(2026, 5, 31),
                                                    cost_resolver=resolver)
        by = {m["metricCode"]: m for m in result["metrics"]}
        for code in ("CONSUMPTION_AMOUNT", "TURNOVER_RATE", "INVENTORY_DAYS"):
            assert by[code]["value"] is None, code
        assert by["INVENTORY_VALUE"]["value"] == 10000
        assert result["trendChart"]["data"][0]["consumption"] is None
        assert result["costScope"]["status"] == "UNAVAILABLE"


async def test_inventory_consumption_query_resolves_the_product(monkeypatch):
    from smartbi_compat.api import analysis_inventory as mod
    seen = {}

    async def fetch_all(sql, *args):
        seen["sql"] = sql
        return []

    monkeypatch.setattr(mod, "_fetch_all", fetch_all)
    await mod._query_material_consumptions_in_range(FACTORY, date(2026, 5, 1), date(2026, 5, 31))
    sql = seen["sql"]
    assert "LEFT JOIN production_batches pb" in sql and "pb.id = mc.production_batch_id" in sql
    assert "COALESCE(pb.product_type_id, pp.product_type_id) AS cost_product_type_id" in sql
    assert "cost_link_present" in sql


def test_inventory_route_wires_the_callers_bearer_into_the_authority(java, inventory):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    fake = java()
    app = FastAPI()
    app.include_router(inventory.router)
    token = _token()
    r = TestClient(app).get(f"/api/mobile/{FACTORY}/smart-bi/analysis/inventory",
                            params={"startDate": "2026-05-01", "endDate": "2026-05-31",
                                    "analysisType": "turnover"},
                            headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 200, r.text
    assert fake.calls and fake.calls[0]["bearer"] == f"Bearer {token}"
    by = {m["metricCode"]: m for m in r.json()["data"]["metrics"]}
    assert by["CONSUMPTION_AMOUNT"]["value"] == 500


def test_unified_dashboard_route_passes_a_resolver_to_inventory_health(java, monkeypatch):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from smartbi_compat.api import dashboard_composite as dc

    fake = java()
    seen = {}

    async def fake_inventory(factory_id, start_date, end_date, cost_resolver=None):
        seen["scope"] = await cost_resolver.scope_for([SECRET, PUBLIC])
        return {"period": "month", "kpiCards": []}

    async def empty(*args, **kwargs):
        return {"period": "month", "kpiCards": []}

    monkeypatch.setattr(dc, "_get_inventory_health", fake_inventory)
    for name in ("_get_sales_overview", "_get_finance_overview", "_get_procurement_overview"):
        monkeypatch.setattr(dc, name, empty)
    app = FastAPI()
    app.include_router(dc.router)
    token = _token()
    r = TestClient(app).get(f"/api/mobile/{FACTORY}/smart-bi/dashboard",
                            headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 200, r.text
    assert fake.calls and fake.calls[0]["bearer"] == f"Bearer {token}"
    assert seen["scope"].allows(PUBLIC) and not seen["scope"].allows(SECRET)


# ─── Review follow-ups: raw ids (M1), empty sums (L1), quality summary (L3) ──


async def test_protected_id_with_whitespace_is_never_trimmed(java, gold):
    """A protected id stored as "P1 " must not be asked/matched as "P1".

    The Java fake protects the raw "P1 " only, so a trimmed "P1" would be
    judged unprotected and allowed — the raw row must still stay hidden.
    """
    from smartbi.api import factory_production_gold as g
    fake = java(protected=frozenset({"P1 "}))
    resolver = cost_scope.CostScopeResolver(_req(), FACTORY)
    scope = await resolver.scope_for(["P1 ", PUBLIC])
    assert not scope.allows("P1 ") and scope.allows(PUBLIC)
    assert all("P1" not in call["ids"] for call in fake.calls)  # never sent trimmed
    assert scope.excluded_product_ids(["P1 ", PUBLIC]) == ["P1 "]

    rows = [dict(r, product_type_id="P1 ") if r["product_type_id"] == SECRET else r
            for r in PRODUCT_ROWS]
    gold(product_rows=rows)
    products = (await g.product_cost_compare(_req(), None, None))["data"]["products"]
    padded = next(p for p in products if p["productTypeId"] == "P1 ")
    assert padded["totalCost"] is None and padded["costPerUnit"] is None
    assert padded["totalActualQty"] == pytest.approx(35.0)
    assert padded["processSecret"]["visibility"] == "TOTALS_ONLY"


def _install_consumptions(monkeypatch, rows):
    from smartbi_compat.api import analysis_inventory as mod

    async def inv_total(factory_id):
        return Decimal("10000")

    async def consumptions(factory_id, start_date, end_date):
        return rows

    async def batches(factory_id, status="AVAILABLE"):
        return []

    monkeypatch.setattr(mod, "_query_inventory_value_total", inv_total)
    monkeypatch.setattr(mod, "_query_material_consumptions_in_range", consumptions)
    monkeypatch.setattr(mod, "_query_material_batches_by_status", batches)
    return mod


async def test_inventory_entirely_protected_consumption_is_null_not_zero(java, monkeypatch):
    mod = _install_consumptions(monkeypatch, [_cons(SECRET, "5000")])
    java()
    resolver = cost_scope.CostScopeResolver(_req(), FACTORY)
    result = await mod._get_turnover_mode(FACTORY, date(2026, 5, 1), date(2026, 5, 31),
                                          cost_resolver=resolver)
    by = {m["metricCode"]: m for m in result["metrics"]}
    for code in ("CONSUMPTION_AMOUNT", "TURNOVER_RATE", "INVENTORY_DAYS"):
        assert by[code]["value"] is None, code          # not 0 / 999
        assert by[code]["alertLevel"] is None, code     # no RED from an unknown
        assert by[code]["description"] == "不含 1 个保密产品"
    assert result["trendChart"]["data"][0]["consumption"] is None
    assert result["costScope"]["excludedProtectedProductCount"] == 1

    insights = mod._generate_ai_insights([], result["metrics"], FACTORY)
    turnover = [i for i in insights if i["category"] == "周转效率"]
    assert [i["level"] for i in turnover] == ["INFO"]
    assert "不含 1 个保密产品" in turnover[0]["message"]
    assert all("库存积压严重" not in i["message"] for i in insights)


async def test_inventory_scoped_red_insight_carries_the_note_full_does_not(java, monkeypatch):
    mod = _install_consumptions(monkeypatch, [_cons(PUBLIC, "100"), _cons(SECRET, "5000")])
    java()
    scoped = await mod._get_turnover_analysis(FACTORY, date(2026, 5, 1), date(2026, 5, 31),
                                              cost_resolver=cost_scope.CostScopeResolver(_req(), FACTORY))
    red = [i for i in mod._generate_ai_insights([], scoped, FACTORY) if i["level"] == "RED"]
    assert red and "库存积压严重" in red[0]["message"] and "不含 1 个保密产品" in red[0]["message"]
    java(granted={SECRET})
    full = await mod._get_turnover_analysis(FACTORY, date(2026, 5, 1), date(2026, 5, 31),
                                            cost_resolver=cost_scope.CostScopeResolver(_req(), FACTORY))
    assert all("保密" not in i["message"] for i in mod._generate_ai_insights([], full, FACTORY))


async def test_inventory_scoped_caller_with_no_consumption_keeps_zero(java, monkeypatch):
    """Control: nothing excluded and nothing consumed is a real 0 (unchanged)."""
    mod = _install_consumptions(monkeypatch, [])
    java()
    metrics = await mod._get_turnover_analysis(FACTORY, date(2026, 5, 1), date(2026, 5, 31),
                                               cost_resolver=cost_scope.CostScopeResolver(_req(), FACTORY))
    by = {m["metricCode"]: m for m in metrics}
    assert by["CONSUMPTION_AMOUNT"]["value"] == 0 and by["INVENTORY_DAYS"]["value"] == 999


async def test_quality_cost_null_when_no_allowed_row_has_cost(java, gold):
    from smartbi_compat.api.analysis_quality import _factory_quality_dispatch
    java()
    gold(product_rows=[_gold(D1, PUBLIC, "10", None, None, None, None),
                       _gold(D1, SECRET, "20", 600, 200, 50, 50)])
    resolver = cost_scope.CostScopeResolver(_req(), FACTORY)
    result = await _factory_quality_dispatch(FACTORY, D1, D2, "rework", cost_resolver=resolver)
    metric, chart = _quality_cost(result)
    assert metric["value"] is None and chart["cost"] is None  # not Decimal 0
    assert result["costScope"]["excludedProtectedProductCount"] == 1


async def test_quality_summary_total_cost_is_overwritten_with_scoped_value():
    from smartbi_compat.api import analysis_quality as q
    scoped = cost_scope.CostScope(cost_scope.SCOPED, frozenset({PUBLIC, PUBLIC2}))
    summary = q._calculate_factory_quality_summary(PRODUCT_ROWS)
    assert summary["totalCost"] == Decimal("1870")  # the spread full total, before scoping
    q._scope_quality_cost(summary, PRODUCT_ROWS, scoped)
    expected = sum(r["total_cost"] for r in PRODUCT_ROWS
                   if r["product_type_id"] in {PUBLIC, PUBLIC2} and r["total_cost"] is not None)
    assert summary["qualityCost"] == expected == Decimal("270")
    assert summary["totalCost"] == expected
    failed = q._calculate_factory_quality_summary(PRODUCT_ROWS)
    q._scope_quality_cost(failed, PRODUCT_ROWS, cost_scope.unavailable())
    assert failed["totalCost"] is None and failed["qualityCost"] is None
