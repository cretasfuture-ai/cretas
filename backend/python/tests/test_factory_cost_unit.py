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


@pytest.mark.asyncio
async def test_fact_query_executes_against_row_grain_schema_without_aggregate_columns(monkeypatch):
    """Exercise the SQL: Silver has one row per batch, no batch_count column."""
    import sqlite3
    from contextlib import asynccontextmanager
    from smartbi import config
    from smartbi.api import factory_production_gold as gold

    db = sqlite3.connect(":memory:")
    db.row_factory = sqlite3.Row
    db.execute("""CREATE TABLE fact_production_batch (
        factory_id TEXT, product_type_id TEXT, unit TEXT, planned_qty REAL,
        actual_qty REAL, good_qty REAL, yield_rate REAL, material_cost REAL,
        labor_cost REAL, equipment_cost REAL, other_cost REAL, total_cost REAL,
        stat_date TEXT)""")
    db.execute("INSERT INTO fact_production_batch VALUES ('F006','BEEF','盒',4,4,4,100,60,5,0,0,65,'2026-11-05')")
    db.execute("INSERT INTO fact_production_batch VALUES ('F005','PRIVATE','kg',9,9,9,100,900,0,0,0,900,'2026-11-05')")

    @asynccontextmanager
    async def context(value=None):
        yield value

    class Connection:
        transaction = lambda self: context()

        async def execute(self, sql, factory_id):
            assert "set_config('app.factory_id'" in sql
            assert factory_id == 'F006'

        async def fetch(self, sql, *params):
            return [dict(row) for row in db.execute(sql, params)]

    connection = Connection()
    pool = SimpleNamespace(acquire=lambda: context(connection))

    async def get_pool():
        return pool

    monkeypatch.setattr(config, 'get_pg_pool', get_pool)
    rows, error = await gold._query_fact_product_rows('F006', None, None)
    db.close()
    assert error is None
    assert len(rows) == 1
    assert rows[0]['product_type_id'] == 'BEEF'
    assert rows[0]['unit'] == '盒'
    assert rows[0]['batch_count'] == 1


@pytest.mark.asyncio
async def test_unit_source_query_failure_is_an_error_not_a_mixed_quantity_fallback(monkeypatch):
    from smartbi.api import factory_production_gold as gold
    monkeypatch.setattr(gold, '_query_gold', lambda *args, **kwargs: _rows([{}]))

    async def unavailable(*args):
        return None, 'source quantity unavailable'

    monkeypatch.setattr(gold, '_query_fact_product_rows', unavailable)
    # Use a valid Gold row so the source-unit failure, not row conversion, is tested.
    monkeypatch.setattr(gold, '_row_to_dict', lambda _row: {})
    response = await gold.cost_structure(_request(), None, None)
    assert response['success'] is False
    assert response['data'] is None
    assert 'source quantity unavailable' in response['message']
