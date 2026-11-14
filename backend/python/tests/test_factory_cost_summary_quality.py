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


def _fact(day, pid, unit, actual, cost, yield_rate):
    return {
        "product_type_id": pid, "unit": unit, "planned_qty": actual, "actual_qty": actual,
        "good_qty": actual, "yield_rate": yield_rate, "material_cost": cost, "labor_cost": None,
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
        [_fact(day1, "P1", "kg", 10.0, 10.0, 100.0)] * 1 + [_fact(day2, "P1", "kg", 10.0, 10.0, 80.0)] * 9,
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
    g = patched([_fact(day, "P1", "kg", 5.0, None, None)], rollup=[row])
    summary = (await g.cost_structure(_req(), None, None))["data"]["summary"]
    assert summary["avgYieldRate"] is None and summary["yieldBatchCount"] == 0
