"""六膳门成本分析口径: 单位成本同口径、平均出成率按批次加权。

两条都是「数字看着合理但口径不对」的缺陷, 单靠总额对账发现不了:
* costPerUnit 原来 = 有成本批次的总成本 / 全部批次的产量, 漏填成本的批次会把单位成本拉低;
* 汇总出成率前端原来按天简单平均, 1 批的一天和 10 批的一天等权。
"""
from __future__ import annotations

import os
from datetime import date

os.environ.setdefault("JWT_SECRET", "factory-cost-summary-quality-test-secret-0123456789")

import jwt as jwt_lib  # noqa: E402
import pytest  # noqa: E402

FACTORY = "F006"


def _req():
    token = jwt_lib.encode({"factoryId": FACTORY, "role": "factory_super_admin"},
                           os.environ["JWT_SECRET"], algorithm="HS256")
    from types import SimpleNamespace
    return SimpleNamespace(headers={"authorization": f"Bearer {token}"},
                           state=SimpleNamespace(factory_id=FACTORY, role="factory_super_admin"))


def _fact(day, pid, unit, actual, cost, yield_rate, good="same"):
    """yield_rate 是 production_batches 的存量列 (可能冻结); 读端点必须按 良品÷实际 现算。"""
    return {
        "product_type_id": pid, "unit": unit, "planned_qty": actual, "actual_qty": actual,
        "good_qty": actual if good == "same" else good, "yield_rate": yield_rate, "material_cost": cost, "labor_cost": None,
        "equipment_cost": None, "other_cost": None, "total_cost": cost, "stat_date": day,
        "batch_count": 1,
    }


class _Row(dict):
    pass


@pytest.fixture
def patched(monkeypatch):
    from smartbi.api import factory_production_gold as g
    from smartbi.services.factory_charts import cost_scope

    def install(facts, rollup=None):
        async def fake_facts(*_a, **_k):
            return [_Row(r) for r in facts], None

        async def fake_gold(*_a, **_k):
            return [_Row(r) for r in (rollup or [])], None

        class _Full:
            is_full = True
            is_unavailable = False

            def allows(self, _pid):
                return True

        async def fake_scope(*_a, **_k):
            return _Full()

        monkeypatch.setattr(g, "_query_fact_product_rows", fake_facts)
        monkeypatch.setattr(g, "_query_gold", fake_gold)
        monkeypatch.setattr(g.process_secret, "resolve_cost_scope", fake_scope)
        return g
    return install


async def test_cost_per_unit_ignores_quantity_of_uncosted_batches(patched):
    g = patched([
        _fact(date(2026, 6, 1), "P1", "kg", 100.0, 1000.0, 90.0),   # 有成本: 10 元/kg
        _fact(date(2026, 6, 2), "P1", "kg", 100.0, None, 90.0),     # 漏填成本
    ])
    products = (await g.product_cost_compare(_req(), None, None))["data"]["products"]
    p = products[0]
    assert p["totalActualQty"] == pytest.approx(200.0)
    assert p["totalCost"] == pytest.approx(1000.0)
    # 旧口径 1000/200 = 5.0 (被漏填批次拉低一半); 同口径应为 1000/100 = 10.0
    assert p["costPerUnit"] == pytest.approx(10.0)
    assert p["costedBatchCount"] == 1 and p["batchCount"] == 2


async def test_cost_per_unit_is_null_when_no_batch_has_cost(patched):
    g = patched([_fact(date(2026, 6, 1), "P1", "kg", 100.0, None, 90.0)])
    p = (await g.product_cost_compare(_req(), None, None))["data"]["products"][0]
    assert p["totalCost"] is None and p["costPerUnit"] is None and p["costedBatchCount"] == 0


async def test_summary_yield_is_weighted_by_batch_count(patched):
    day1, day2 = date(2026, 6, 1), date(2026, 6, 2)

    def roll(day, batches, rate, qty):
        return {"factory_id": FACTORY, "stat_date": day, "product_type_id": None,
                "batch_count": batches, "total_planned_qty": qty, "total_actual_qty": qty,
                "total_good_qty": qty, "avg_yield_rate": rate, "total_material_cost": 10.0,
                "total_labor_cost": None, "total_equipment_cost": None, "total_other_cost": None,
                "total_cost": 10.0, "version": 1, "updated_at": None}

    g = patched(
        [_fact(day1, "P1", "kg", 10.0, 10.0, 100.0)] * 1
        + [_fact(day2, "P1", "kg", 10.0, 10.0, 80.0, good=8.0)] * 9,
        rollup=[roll(day1, 1, 100.0, 10.0), roll(day2, 9, 80.0, 90.0)],
    )
    summary = (await g.cost_structure(_req(), None, None))["data"]["summary"]
    # 简单平均 (100+80)/2 = 90; 按批次加权 (100*1 + 80*9)/10 = 82
    assert summary["avgYieldRate"] == pytest.approx(82.0)
    assert summary["yieldBatchCount"] == 10


async def test_summary_yield_is_null_when_no_day_has_yield(patched):
    day = date(2026, 6, 1)
    row = {"factory_id": FACTORY, "stat_date": day, "product_type_id": None, "batch_count": 2,
           "total_planned_qty": None, "total_actual_qty": 5.0, "total_good_qty": None,
           "avg_yield_rate": None, "total_material_cost": None, "total_labor_cost": None,
           "total_equipment_cost": None, "total_other_cost": None, "total_cost": None,
           "version": 1, "updated_at": None}
    g = patched([_fact(day, "P1", "kg", 5.0, None, None, good=None)], rollup=[row])
    summary = (await g.cost_structure(_req(), None, None))["data"]["summary"]
    assert summary["avgYieldRate"] is None and summary["yieldBatchCount"] == 0


# ── LSM-COSTBI-B: 出成率口径统一为 良品÷实际 (与 AI 问答同一定义) ─────────────────


def _roll(day, batches, qty, rate=80.0):
    return {"factory_id": FACTORY, "stat_date": day, "product_type_id": None,
            "batch_count": batches, "total_planned_qty": qty, "total_actual_qty": qty,
            "total_good_qty": qty, "avg_yield_rate": rate, "total_material_cost": 10.0,
            "total_labor_cost": None, "total_equipment_cost": None, "total_other_cost": None,
            "total_cost": 10.0, "version": 1, "updated_at": None}


async def test_frozen_yield_column_is_not_reported(patched):
    """LSMDEMO 实测: 存量列 80.00, 良品=实际 ⇒ 按定义 100%。页面不能再给 80 而 AI 给 100。"""
    day = date(2026, 11, 1)
    g = patched([_fact(day, "P1", "盒", 100.0, 10.0, 80.0)] * 3, rollup=[_roll(day, 3, 300.0)])
    summary = (await g.cost_structure(_req(), None, None))["data"]["summary"]
    assert summary["avgYieldRate"] == pytest.approx(100.0)
    trend = (await g.yield_trend(_req(), None, None, None))["data"]["trend"]
    assert trend[0]["avgYieldRate"] == pytest.approx(100.0)
    product = (await g.product_cost_compare(_req(), None, None))["data"]["products"][0]
    assert product["avgYieldRate"] == pytest.approx(100.0)


async def test_yield_batch_count_counts_only_batches_with_yield(patched):
    """卤味三拼 1 批没报良品: 汇总要说「按 9 批」, 不是 10 批。"""
    day = date(2026, 11, 1)
    facts = [_fact(day, "P1", "盒", 100.0, 10.0, 80.0, good=90.0)] * 9         + [_fact(day, "P2", "盒", 100.0, 10.0, 80.0, good=None)]
    g = patched(facts, rollup=[_roll(day, 10, 1000.0)])
    data = (await g.cost_structure(_req(), None, None))["data"]
    assert data["summary"]["batchCount"] == 10
    assert data["summary"]["yieldBatchCount"] == 9
    assert data["summary"]["avgYieldRate"] == pytest.approx(90.0)
    assert data["rows"][0]["yieldBatchCount"] == 9
    products = {p["productTypeId"]: p for p in
                (await g.product_cost_compare(_req(), None, None))["data"]["products"]}
    assert products["P2"]["avgYieldRate"] is None and products["P2"]["yieldBatchCount"] == 0
    assert products["P1"]["yieldBatchCount"] == 9


async def test_yield_trend_single_product_filters_fact_rows(patched):
    day = date(2026, 11, 1)
    facts = [_fact(day, "P1", "盒", 100.0, 10.0, 80.0, good=50.0),
             _fact(day, "P2", "盒", 100.0, 10.0, 80.0, good=100.0)]
    g = patched(facts, rollup=[dict(_roll(day, 1, 100.0), product_type_id="P1")])
    trend = (await g.yield_trend(_req(), None, None, "P1"))["data"]["trend"]
    assert trend[0]["avgYieldRate"] == pytest.approx(50.0) and trend[0]["yieldBatchCount"] == 1


def test_batch_yield_rate_definition():
    from smartbi.gold.factory_production_etl import batch_yield_rate
    assert batch_yield_rate(90, 100) == pytest.approx(90.0)
    assert batch_yield_rate(None, 100) is None
    assert batch_yield_rate(5, 0) is None
    assert batch_yield_rate(5, None) is None
