"""Unit contract for factory cost quantities: source units are not guessed."""
from datetime import date
from types import SimpleNamespace

import pytest


def _request():
    return SimpleNamespace(state=SimpleNamespace(factory_id="F006", role="factory_super_admin"))


def _fact(pid, unit, qty, cost):
    return {
        "product_type_id": pid,
        "unit": unit,
        "planned_qty": qty,
        "actual_qty": qty,
        "good_qty": qty,
        "yield_rate": 100,
        "material_cost": cost,
        "labor_cost": None,
        "equipment_cost": None,
        "other_cost": None,
        "total_cost": cost,
        "stat_date": date(2026, 11, 5),
        "batch_count": 1,
    }


@pytest.mark.asyncio
async def test_product_compare_keeps_mixed_units_and_cost_per_source_unit(monkeypatch):
    from smartbi.api import factory_production_gold as gold

    rows = [_fact("BEEF", "盒", 100, 1622), _fact("BEEF", "kg", 20, 400)]
    monkeypatch.setattr(gold, "_query_fact_product_rows", lambda *args: _rows(rows))
    monkeypatch.setattr(gold.process_secret, "resolve_cost_scope", _full_scope)

    products = (await gold.product_cost_compare(_request(), None, None))["data"]["products"]

    by_unit = {item["unit"]: item for item in products}
    assert by_unit["盒"]["totalActualQty"] == pytest.approx(100)
    assert by_unit["盒"]["costPerUnit"] == pytest.approx(16.22)
    assert by_unit["kg"]["totalActualQty"] == pytest.approx(20)
    assert by_unit["kg"]["costPerUnit"] == pytest.approx(20)


@pytest.mark.asyncio
async def test_cost_structure_marks_mixed_units_instead_of_calling_them_kg(monkeypatch):
    from smartbi.api import factory_production_gold as gold

    day = date(2026, 11, 5)
    rollup = {"stat_date": day, "factory_id": "F006", "product_type_id": None,
              "batch_count": 2, "total_planned_qty": 120, "total_actual_qty": 120,
              "total_good_qty": 120, "avg_yield_rate": 100,
              "total_material_cost": 2022, "total_labor_cost": None,
              "total_equipment_cost": None, "total_other_cost": None,
              "total_cost": 2022, "version": 1, "updated_at": None}
    facts = [_fact("BEEF", "盒", 100, 1622), _fact("BEEF", "kg", 20, 400)]
    monkeypatch.setattr(gold, "_query_gold", lambda *args, **kwargs: _rows([rollup]))
    monkeypatch.setattr(gold, "_query_fact_product_rows", lambda *args: _rows(facts))
    monkeypatch.setattr(gold, "_scope_rollup_costs", _no_scope_rollup)

    data = (await gold.cost_structure(_request(), None, None))["data"]
    assert data["summary"]["totalActualQty"] is None
    assert data["summary"]["quantityByUnit"] == {"盒": 100, "kg": 20}
    assert data["rows"][0]["actualQtyByUnit"] == {"盒": 100, "kg": 20}


async def _rows(rows):
    return rows, None


async def _full_scope(*args, **kwargs):
    return SimpleNamespace(is_full=True, is_unavailable=False, allows=lambda _pid: True)


async def _no_scope_rollup(*args, **kwargs):
    return None
