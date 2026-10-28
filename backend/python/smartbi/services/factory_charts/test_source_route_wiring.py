"""Actual rule output must replace only the corresponding missing-capture gap."""
import pandas as pd
import pytest
from httpx import AsyncClient, ASGITransport
from .test_detail_api import app_for, clean_details
from . import sources, finance_sources, operational_sources, captured_sources, trusted_business_sources
from .engine import Dataset
from .test_operational_sources import FakePool
from decimal import Decimal


@pytest.mark.asyncio
@pytest.mark.parametrize('qualified', [True, False])
async def test_business_route_reconciles_trusted_positive_capture_only(monkeypatch, qualified):
    async def empty(*args): return [], [], []
    async def finance(*args):
        return [], [], [{'id': i, 'title': i, 'missing': ['capture required']} for i in
                        ('sales.channel', 'finance.unit-source', 'production.oee-source')]
    async def captured(*args):
        if not qualified: return [], [], []
        return [
                Dataset(pd.DataFrame([{'日期':'2026-10-01','渠道':'QA直营','订单ID':'qa1','实际（元）':100},
                                      {'日期':'2026-10-02','渠道':'QA经销','订单ID':'qa2','实际（元）':50}]),
                    'sales-sales_channel · actual order', financial=True, currency='CNY'),
            Dataset(pd.DataFrame([{'日期':'2026-10-01','部门':'QA部门','实际（元）':100}]),
                    'sales-department_id · actual order', financial=True, currency='CNY'),
            Dataset(pd.DataFrame([{'日期':'2026-10-01','计划运行分钟':100,'实际运行分钟':90,
                                  '理想周期秒':48,'总数':90,'良品数':85.5,'单位':'kg'}]),
                    'oee · actual report'),
        ], [], []
    async def allowed(*args): return True
    monkeypatch.setattr(sources, 'load', empty)
    monkeypatch.setattr(operational_sources, 'load', empty)
    monkeypatch.setattr(finance_sources, 'load', finance)
    monkeypatch.setattr(captured_sources, 'load', captured)
    monkeypatch.setattr(trusted_business_sources, 'load', empty)
    monkeypatch.setattr('smartbi.services.factory_charts.finance_auth.full_factory_finance_access', allowed)
    async with AsyncClient(transport=ASGITransport(app=app_for()), base_url='http://test') as c:
        response=await c.post('/factory-recommendations',json={'use_ai':False})
        if qualified:
            channel=next(x for x in response.json()['data']['recommendations'] if 'finance.channel_analysis' in x['id'])
            assert channel['detailContext']['drilldown']
            drilled=await c.post('/factory-details',json={'context_id':channel['detailContext']['id'],
                                                       'point':{'name':'QA直营','seriesName':'实际'}})
            assert drilled.status_code==200
            assert drilled.json()['data']['total']==1
            assert drilled.json()['data']['rows'][0]['订单ID']=='qa1'
    assert response.status_code==200
    data=response.json()['data']
    ids={g['id'] for g in data['unavailable']}
    expected={'sales.channel','finance.unit-source','production.oee-source'}
    if qualified:
        metrics={x['id'] for x in data['recommendations']}
        for metric in ('finance.channel_analysis','finance.business_unit_analysis','production.oee'):
            assert any(value.split(':',1)[0] == metric or value.split(':',1)[0].endswith('.'+metric)
                       for value in metrics), metrics
        assert not ids.intersection(expected)
    else:
        assert expected <= ids and data['recommendations']==[]


@pytest.mark.asyncio
async def test_route_loads_canonical_sales_sku_and_detail_preserves_line_provenance(monkeypatch):
    rows = {
        "sales_order_items i": [{"订单行ID": "line-851", "订单ID": "order-851", "日期": "2026-10-01",
                                  "产品ID": "sku-851", "产品": "QA SKU", "单位": "kg", "数量": Decimal("3"),
                                  "单价": Decimal("10"), "折扣率": Decimal("0"), "报价含税单价": Decimal("10"),
                                  "订单总额": Decimal("30")}],
        "smart_bi_department_data": [], "production_reports pr": [],
        "semi_finished_inventory sfi": [], "semi_finished_inventory_transactions tx": [],
    }
    async def get_pool():
        return FakePool(rows, [])
    async def empty(*args): return [], [], []
    monkeypatch.setattr(operational_sources.config, 'get_cretas_pool', get_pool)
    monkeypatch.setattr(sources, 'load', empty)
    monkeypatch.setattr(finance_sources, 'load', empty)
    monkeypatch.setattr(captured_sources, 'load', empty)
    monkeypatch.setattr(trusted_business_sources, 'load', empty)
    async def allowed(*args): return True
    monkeypatch.setattr('smartbi.services.factory_charts.finance_auth.full_factory_finance_access', allowed)
    async with AsyncClient(transport=ASGITransport(app=app_for()), base_url='http://test') as c:
        response = await c.post('/factory-recommendations', json={'use_ai': False})
        assert response.status_code == 200
        sku = next(card for card in response.json()['data']['recommendations'] if 'sales.sku' in card['id'])
        assert sku['detailContext']['drilldown']
        drilled = await c.post('/factory-details', json={'context_id': sku['detailContext']['id'],
                                                          'point': {'name': 'QA SKU'}})
        assert drilled.status_code == 200
        row = drilled.json()['data']['rows'][0]
        assert row['订单行ID'] == 'line-851' and row['订单ID'] == 'order-851'
        assert str(row['订单总额']) in {'30', '30.00'}


@pytest.mark.asyncio
async def test_route_loads_named_trusted_customer_source_candidate_without_channel_alias(monkeypatch):
    async def empty(*args): return [], [], []
    async def trusted(*args):
        return [Dataset(pd.DataFrame([
            {'日期': '2026-10-01', '订单ID': 'so-source-1', '订单行ID': 'line-source-1',
             '客户来源': 'REFERRAL', '订单行小计': Decimal('20'), '单位': 'kg',
             '事实限定': '已逐单对账'},
            {'日期': '2026-10-02', '订单ID': 'so-source-2', '订单行ID': 'line-source-2',
             '客户来源': 'WEBSITE', '订单行小计': Decimal('5'), '单位': 'kg',
             '事实限定': '已逐单对账'},
        ]), 'customer-source-sales · 客户来源获客归因（非订单销售渠道）', financial=True, currency='CNY')], [], []
    async def allowed(*args): return True
    monkeypatch.setattr(sources, 'load', empty)
    monkeypatch.setattr(operational_sources, 'load', empty)
    monkeypatch.setattr(finance_sources, 'load', empty)
    monkeypatch.setattr(captured_sources, 'load', empty)
    monkeypatch.setattr(trusted_business_sources, 'load', trusted)
    monkeypatch.setattr('smartbi.services.factory_charts.finance_auth.full_factory_finance_access', allowed)
    async with AsyncClient(transport=ASGITransport(app=app_for()), base_url='http://test') as c:
        response = await c.post('/factory-recommendations', json={'use_ai': False})
        assert response.status_code == 200
        data = response.json()['data']
        candidate = next(card for card in data['recommendations'] if 'customer-source-sales.sales.customer_source' in card['id'])
        assert '非订单销售渠道' in '；'.join(candidate['metadata']['limitations'])
        detail = await c.post('/factory-details', json={'context_id': candidate['detailContext']['id']})
        assert detail.status_code == 200
        assert detail.json()['data']['total'] == 2


@pytest.mark.asyncio
async def test_route_loads_sheet_oee_and_details_keep_electronic_row_provenance(monkeypatch):
    async def empty(*args): return [], [], []
    async def captured(*args):
        return [Dataset(pd.DataFrame([{
            '计划运行分钟': 20, '实际运行分钟': 15, '理想周期秒': 60,
            '总数': 10, '良品数': 0, '单位': 'kg', '日期': '2026-10-01',
            '产品': 'QA产品', '报工ID': 'sheet-row:qa-1', '设备ID': 'eq-sheet',
        }]), 'oee-sheet · 电子工序表提交事实 · eq-sheet|QA产品|kg|60')], [], []
    async def allowed(*args): return True
    monkeypatch.setattr(sources, 'load', empty)
    monkeypatch.setattr(operational_sources, 'load', empty)
    monkeypatch.setattr(finance_sources, 'load', empty)
    monkeypatch.setattr(captured_sources, 'load', captured)
    monkeypatch.setattr(trusted_business_sources, 'load', empty)
    monkeypatch.setattr('smartbi.services.factory_charts.finance_auth.full_factory_finance_access', allowed)
    async with AsyncClient(transport=ASGITransport(app=app_for()), base_url='http://test') as c:
        response = await c.post('/factory-recommendations', json={'use_ai': False})
        assert response.status_code == 200
        card = next(card for card in response.json()['data']['recommendations'] if 'production.oee' in card['id'])
        assert card['detailContext']['id'] and card['detailContext']['drilldown'] is False
        drilled = await c.post('/factory-details', json={
            'context_id': card['detailContext']['id'],
            'point': {'name': 'QA产品'},
        })
        assert drilled.status_code == 200
        rows = drilled.json()['data']['rows']
        assert any(row['报工ID'] == 'sheet-row:qa-1' for row in rows)
