from contextlib import asynccontextmanager
from datetime import date

import pandas as pd
import pytest

from smartbi.api import financial_dashboard as api
from smartbi.services.factory_charts import sources
from smartbi.services.factory_charts.engine import Dataset


def request(**kwargs):
    return api.FactoryRecommendationRequest(use_ai=False, **kwargs)


class FakeConnection:
    def __init__(self, rows_by_source, calls):
        self.rows_by_source = rows_by_source
        self.calls = calls

    async def execute(self, sql, *params):
        self.calls.append(('execute', sql, params))

    @asynccontextmanager
    async def transaction(self):
        yield self

    async def fetch(self, sql, *params):
        source = next((name for name, marker, _rows in self.rows_by_source if marker in sql), 'unknown')
        self.calls.append(('fetch', source, sql, params))
        return next(rows for name, marker, rows in self.rows_by_source if name == source)


class FakePool:
    def __init__(self, rows_by_source, calls):
        self.connection = FakeConnection(rows_by_source, calls)

    @asynccontextmanager
    async def acquire(self):
        yield self.connection


async def fake_loader(rows_by_source, calls):
    pool = FakePool(rows_by_source, calls)
    async def get_pool():
        return pool
    return get_pool


@pytest.mark.asyncio
async def test_load_binds_factory_and_period_and_removes_price_without_mixing_sources(monkeypatch):
    calls = []
    rows_by_source = [
        ('production', 'fact_production_batch', [{'日期': '2026-02-01', '产量': 5, '材料成本': 12, '单位': 'kg'}]),
        ('sales', 'sales_orders so', [{'日期': '2026-02-02', '销售数量': 3, '销售金额': 30}]),
        ('purchase', 'purchase_receive_records pr', [{'日期': '2026-02-03', '入库数量': 4, '入库金额': 40}]),
        ('quality', 'quality_inspections qi', [{'日期': '2026-02-04', '检验数': 4, '合格数': 4}]),
        ('process', 'fact_production_report', [{'日期': '2026-02-05', '材料成本': 8, '人工成本': 2}]),
        ('settled', 'production_batches pb', []),
    ]
    get_pool = await fake_loader(rows_by_source, calls)
    monkeypatch.setattr(sources.config, 'get_pg_pool', get_pool)
    monkeypatch.setattr(sources.config, 'get_cretas_pool', get_pool)

    datasets, errors, gaps = await sources.load(
        'F006', request(period_type='month_range', year=2026, start_month=2, end_month=3), False
    )

    assert errors == []
    source_frames = {dataset.source.split(' · ', 1)[0]: dataset.frame for dataset in datasets}
    assert set(source_frames) == {'production', 'sales', 'purchase', 'quality', 'process'}
    by_source = source_frames
    assert '材料成本' not in by_source['production']
    assert '销售金额' not in by_source['sales']
    assert '入库金额' not in by_source['purchase']
    assert '人工成本' not in by_source['process']
    assert '销售数量' in by_source['sales']
    assert any(g['id'] == 'stock.snapshot' for g in gaps)

    fetches = [call for call in calls if call[0] == 'fetch']
    assert len(fetches) == 6
    for _, _, sql, params in fetches:
        assert '$1' in sql and 'F006' not in sql
        assert params[0] == 'F006'
        assert params[1:] == (date(2026, 2, 1), date(2026, 3, 31))
    assert all(call[0] != 'fetch' or call[1] != 'inventory' for call in calls)
    assert all(call[0] != 'execute' or call[2] == ('F006',) for call in calls)


@pytest.mark.asyncio
async def test_load_distinguishes_source_failure_from_successful_empty_source_and_inventory_is_all_period_only(monkeypatch):
    calls = []
    rows_by_source = [
        ('production', 'fact_production_batch', RuntimeError('db unavailable')),
        ('sales', 'sales_orders so', []),
        ('purchase', 'purchase_receive_records pr', []),
        ('quality', 'quality_inspections qi', []),
        ('process', 'fact_production_report', []),
        ('settled', 'production_batches pb', []),
    ]

    class MixedConnection(FakeConnection):
        async def fetch(self, sql, *params):
            source = next((name for name, marker, _rows in rows_by_source if marker in sql), 'unknown')
            calls.append(('fetch', source, sql, params))
            value = next(rows for name, marker, rows in rows_by_source if name == source)
            if isinstance(value, Exception):
                raise value
            return value

    class MixedPool(FakePool):
        def __init__(self):
            self.connection = MixedConnection(rows_by_source, calls)

    async def get_pool():
        return MixedPool()

    monkeypatch.setattr(sources.config, 'get_pg_pool', get_pool)
    monkeypatch.setattr(sources.config, 'get_cretas_pool', get_pool)

    datasets, errors, gaps = await sources.load(
        'F006', request(period_type='month_range', year=2026, start_month=2, end_month=2), True
    )

    assert [error['source'] for error in errors] == ['production']
    assert 'db unavailable' not in errors[0]['message']
    assert {dataset.source.split(' · ', 1)[0] for dataset in datasets} == {'sales', 'purchase', 'quality', 'process'}
    assert all(dataset.frame.empty for dataset in datasets)
    assert any(g['id'] == 'stock.snapshot' for g in gaps)
    assert all(call[1] != 'inventory' for call in calls if call[0] == 'fetch')


@pytest.mark.asyncio
async def test_inventory_provenance_names_material_batches_and_excludes_independent_finished_stock(monkeypatch):
    calls = []
    rows_by_source = [
        ('production', 'fact_production_batch', []),
        ('sales', 'sales_orders so', []),
        ('purchase', 'purchase_receive_records pr', []),
        ('quality', 'quality_inspections qi', []),
        ('process', 'fact_production_report', []),
        ('inventory', 'material_batches mb', [{'日期': '2026-10-26', '原料': 'QA原料', '库存数量': 7, '单位': 'kg'}]),
        ('settled', 'production_batches pb', []),
    ]
    get_pool = await fake_loader(rows_by_source, calls)
    monkeypatch.setattr(sources.config, 'get_pg_pool', get_pool)
    monkeypatch.setattr(sources.config, 'get_cretas_pool', get_pool)
    datasets, errors, _ = await sources.load('F006', request(period_type='all'), False)
    inventory = next(dataset for dataset in datasets if dataset.source.startswith('inventory ·'))
    assert errors == []
    assert '物料批次可用库存快照（不含独立成品库存）' in inventory.source
    assert inventory.frame.iloc[0]['库存数量'] == 7
    assert 'finished_goods_batches' not in sources.INVENTORY_SQL


@pytest.mark.asyncio
async def test_load_merges_read_only_settled_source_before_quality_join(monkeypatch):
    calls = []
    rows_by_source = [
        ('production', 'fact_production_batch', [{'来源主键': '1600'}]),
        ('sales', 'sales_orders so', []),
        ('purchase', 'purchase_receive_records pr', []),
        ('quality', 'quality_inspections qi', []),
        ('process', 'fact_production_report', []),
        ('settled', 'production_batches pb', []),
    ]
    get_pool = await fake_loader(rows_by_source, calls)
    monkeypatch.setattr(sources.config, 'get_pg_pool', get_pool)
    monkeypatch.setattr(sources.config, 'get_cretas_pool', get_pool)

    async def settled_loader(factory_id, request_value, can_price):
        assert factory_id == 'F006' and can_price is True
        return [Dataset(pd.DataFrame([{
            '日期': '2026-10-28', '来源主键': '1600', '计划ID': 'plan-1',
            '批次': '11003', '产品ID': 'sku-1', '产品': 'QA成品',
            '成品批次ID': 'fg-1', '成品Manifest': 'manifestFG-1', '单位': '盒',
            '产量': 10, '良品数': 10, '不良数': 0,
            '材料成本': 25, '人工成本': 26, '设备成本': 2, '其他成本': 1,
            '总成本': 54,
        }]), 'settled-production · test', currency='CNY')], [], []

    monkeypatch.setattr(sources.settled_sources, 'load', settled_loader)
    datasets, errors, gaps = await sources.load(
        'F006', request(period_type='month_range', year=2026, start_month=10, end_month=10), True
    )
    assert errors == []
    settled = next(dataset for dataset in datasets if dataset.source.startswith('settled-production'))
    assert settled.frame.empty
    assert any(item['id'] == 'settled.production.overlap' for item in gaps)
    assert any(call[0] == 'fetch' and call[1] == 'production' for call in calls)
