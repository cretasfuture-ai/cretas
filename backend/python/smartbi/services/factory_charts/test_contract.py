from types import SimpleNamespace
import json

import pandas as pd
import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from smartbi.services.factory_charts.engine import Dataset, build, factory_candidates, is_financial
from smartbi.services.factory_charts.ranking import rank
from smartbi.api import financial_dashboard as api


def request(**kw):
    return api.FactoryRecommendationRequest(use_ai=False, **kw)


def rows():
    return [
        {'日期': '2026-01-01', '币种': 'CNY', '产品': '产品A', '工序': '工序A', '单位': 'kg', '产量': 100, '计划产量': 120,
         '良品数': 95, '不良数': 5, '检验数': 100, '总成本': 200, '材料成本': 100, '人工成本': 50,
         '设备成本': 30, '其他成本': 20, '销售金额': 5000, '入库金额': 1000, '入库数量': 50, '供应商': '甲'},
        {'日期': '2026-02-01', '币种': 'CNY', '产品': '产品B', '工序': '工序B', '单位': 'kg', '产量': 300, '计划产量': 280,
         '良品数': 270, '不良数': 30, '检验数': 300, '总成本': 900, '材料成本': 450, '人工成本': 225,
         '设备成本': 135, '其他成本': 90, '销售金额': 15000, '入库金额': 3000, '入库数量': 150, '供应商': '乙'},
    ]


def chart(items, key):
    return next(c for c in items if key in c['id'])


def test_shared_weighted_quality_cost_and_graph_values():
    items, gaps = build([Dataset(pd.DataFrame(rows()), 'fixture')], request(), True)
    quality = chart(items, 'production.quality')
    assert quality['kpis'][0]['value'] == '91.25'
    assert [v for _, v in [(r['label'], r['value']) for r in quality['tableData']]] == [95, 90]
    assert '91.25%' in quality['findings']['good'][0]
    unit = chart(items, 'cost.unit')
    assert unit['kpis'][0]['value'] == '2.75'
    assert unit['unit'] == '元/kg'
    assert unit['chart']['echartsOption']['series'][0]['data'] == [3.0, 2.0]
    assert '2.75元/kg' in unit['findings']['good'][0]
    structure = chart(items, 'cost.structure:donut')
    assert structure['kpis'][0]['value'] == '1,100'
    assert sum(x['value'] for x in structure['chart']['echartsOption']['series'][0]['data']) == 1100
    assert not any('dish_' in c['id'] or 'member_' in c['id'] for c in items)
    assert all(c['chart']['echartsOption']['tooltip']['renderMode'] == 'richText' for c in items)


@pytest.mark.parametrize('change', ['missing-unit', 'mixed-unit', 'missing-cost', 'zero-denominator', 'nan', 'imbalance'])
def test_ineligible_inputs_never_become_zero_or_mixed_unit_charts(change):
    data = rows()
    if change == 'missing-unit':
        for row in data: row.pop('单位')
    elif change == 'mixed-unit': data[1]['单位'] = '盒'
    elif change == 'missing-cost': data[0]['总成本'] = None
    elif change == 'zero-denominator': data[0]['产量'] = 0
    elif change == 'nan': data[0]['产量'] = float('inf')
    elif change == 'imbalance': data[0]['总成本'] = 999
    items, gaps = factory_candidates(Dataset(pd.DataFrame(data), 'fixture'), request(), True)
    if change in ('missing-unit', 'zero-denominator', 'nan'):
        assert not any('production.quality' in c['id'] for c in items)
    if change in ('missing-cost', 'zero-denominator', 'nan'):
        assert not any('cost.unit' in c['id'] for c in items)
    if change == 'imbalance': assert not any('cost.structure' in c['id'] for c in items)
    if change == 'mixed-unit':
        q = [c for c in items if 'production.quality' in c['id']]
        assert {c['kpis'][0]['value'] for c in q} == {'95', '90'}
        assert all('91.25' not in json.dumps(c) for c in q)
    assert gaps


def test_period_filter_scale_and_optional_conditions():
    data = rows()
    for row in data: row['销售金额（万元）'] = row.pop('销售金额') / 10000
    items, _ = factory_candidates(Dataset(pd.DataFrame(data), 'fixture'), request(period_type='month_range', start_month=2, end_month=2, include_optional=False), True)
    assert all(c['priority'] == 'required' for c in items)
    cost = chart(items, 'cost.unit')
    assert cost['kpis'][0]['value'] == '3'
    assert not any('sales.trend' in c['id'] for c in items)  # a one-point trend is not invented
    all_items, _ = factory_candidates(Dataset(pd.DataFrame(data), 'fixture'), request(), True)
    assert chart(all_items, 'sales.trend')['kpis'][0]['value'] == '20,000'
    assert any(c['priority'] == 'optional' and c['chart']['chartType'] == 'area' for c in all_items)


def test_price_denial_and_no_restaurant_semantics():
    items, _ = factory_candidates(Dataset(pd.DataFrame(rows()), 'fixture'), request(), False)
    assert not any(c['domain'] in ('成本', '销售', '采购') and c['unit'] == '元' for c in items)
    assert not any('cost.' in c['id'] for c in items)
    assert is_financial(pd.DataFrame([{'月份': '1月', '本年毛利率': '20%'}]))
    items, _ = build([Dataset(pd.DataFrame([{'菜品': '菜A', '餐段': '午餐', '会员': 100}]), 'fixture')], request(), True)
    assert items == []


def test_financial_upload_reuses_weighted_margin_and_explicit_ratio_only_basis():
    weighted = pd.DataFrame([
        {'月份': '1月', '本年收入': 100, '本年成本': 50, '上年收入': 500, '上年成本': 300},
        {'月份': '2月', '本年收入': 900, '本年成本': 810, '上年收入': 500, '上年成本': 300},
    ])
    items, _ = build([Dataset(weighted, 'upload-test', True, currency='CNY')], request(), True)
    margin = chart(items, 'gross_margin_trend')
    assert any(k['value'] == '14.0' or k['value'] == '14.00' or k['value'] == '14' for k in margin['kpis'])
    assert '14' in json.dumps(margin['findings'], ensure_ascii=False)
    only = pd.DataFrame([{'月份': '1月', '本年毛利率': '20%', '上年毛利率': '20%'}, {'月份': '2月', '本年毛利率': '40%', '上年毛利率': '30%'}])
    items, _ = build([Dataset(only, 'upload-test', True)], request(), True)
    margin = chart(items, 'gross_margin_trend')
    assert '简单平均' in json.dumps(margin, ensure_ascii=False)


@pytest.mark.asyncio
@pytest.mark.parametrize('case', ['valid', 'extra', 'duplicate', 'numeric', 'claim', 'failure'])
async def test_ai_cannot_add_or_calculate_candidates(monkeypatch, case):
    candidates = [{'id': 'required', 'metric': '产量', 'domain': '生产', 'priority': 'required'},
                  {'id': 'optional', 'metric': '结构', 'domain': '成本', 'priority': 'optional'}]
    async def call(slot, payload):
        assert 'kg' not in payload['messages'][1]['content']
        if case == 'failure': raise RuntimeError('not available')
        data = [{'id': 'optional', 'reason_code': 'structure'}, {'id': 'required', 'reason_code': 'observe'}]
        if case == 'extra': data[0]['id'] = 'dish_sales'
        if case == 'duplicate': data[0]['id'] = 'required'
        if case == 'numeric': data[0]['reason'] = '成本下降50%'
        if case == 'claim': data[0]['reason'] = '产量持续下降，建议减产'
        return {'choices': [{'message': {'content': json.dumps(data)}}]}
    monkeypatch.setattr('common.llm_router.call_chain', call)
    items, mode = await rank(candidates, True)
    assert [c['id'] for c in items] == ['required', 'optional']
    assert mode['mode'] == ('ai' if case == 'valid' else 'rule')
    if case != 'valid': assert '未经AI排序' in mode['reason']


@pytest.mark.parametrize('kind', ['usd', 'unknown', 'conflict', 'negative', 'gap', 'limit'])
def test_currency_physical_and_readability_guards(kind):
    data = rows()
    if kind == 'usd':
        for row in data:
            row['销售金额（美元）'] = row.pop('销售金额')
    elif kind == 'unknown':
        for row in data: row.pop('币种')
    elif kind == 'conflict':
        for row in data: row['销售金额（万元）'] = row['销售金额'] / 10000
    elif kind == 'negative': data[0]['产量'] = -100
    elif kind == 'gap': data[1]['日期'] = '2026-01-03'
    elif kind == 'limit':
        data = [{**data[0], '产品': f'SKU{i}'} for i in range(101)]
    items, gaps = build([Dataset(pd.DataFrame(data), 'fixture')], request(), True)
    if kind in ('usd', 'unknown', 'conflict'):
        assert not any(c['id'].startswith('fixture.cost.') or c['id'].startswith('fixture.sales.') for c in items)
    elif kind == 'negative':
        assert not any('production.output' in c['id'] for c in items)
    elif kind == 'gap':
        option = next(c for c in items if 'production.output' in c['id'] and c['chart']['chartType'] == 'line')
        assert option['chart']['echartsOption']['xAxis']['data'] == ['2026-01-01', '2026-01-02', '2026-01-03']
        assert option['chart']['echartsOption']['series'][0]['data'] == [100, None, 300]
        assert option['kpis'][0]['value'] == '400'
    else:
        assert not any(c['chart']['chartType'] == 'bar_horizontal' and 'products' in c['id'] for c in items)
        assert any('100' in ' '.join(g['missing']) for g in gaps)


@pytest.mark.asyncio
async def test_ai_candidate_input_bound_skips_provider(monkeypatch):
    async def forbidden(*args): raise AssertionError('provider must not receive unbounded candidates')
    monkeypatch.setattr('common.llm_router.call_chain', forbidden)
    candidates = [{'id': str(i), 'metric': '产量', 'domain': '生产', 'priority': 'required'} for i in range(61)]
    items, mode = await rank(candidates, True)
    assert items == candidates and mode['mode'] == 'rule'
    assert '候选较多' in mode['reason']


@pytest.mark.parametrize('kind', ['unknown', 'foreign', 'conflict', 'categories'])
def test_financial_subject_currency_and_untruncated_cardinality(kind):
    frame = pd.DataFrame([{'科目': '营业收入', '月份': '1月', '本年': 100, '上年': 90}])
    if kind == 'foreign': frame = frame.rename(columns={'本年':'本年（美元）'})
    elif kind == 'conflict':
        frame = frame.rename(columns={'本年':'本年（元）'})
        frame['本年（万元）'] = 1
    elif kind == 'categories':
        frame = pd.DataFrame([{'月份':'1月','品类':f'类别{i}','本年（元）':100+i,'上年（元）':90} for i in range(101)])
    items, gaps = build([Dataset(frame, 'upload-test', True)], request(), True)
    if kind == 'categories':
        assert not any(c['chart']['chartType'] in ('category_structure_donut','category_yoy_comparison','product_ranking','small_multiples') for c in items)
        assert any('截断' in ' '.join(g['missing']) or '100' in ' '.join(g['missing']) for g in gaps)
    else:
        assert not items
        assert any('人民币' in ' '.join(g['missing']) for g in gaps)


@pytest.mark.asyncio
@pytest.mark.parametrize('factory,claimed,role,expected', [(None, None, 'factory_super_admin', 401), ('F006', 'F001', 'factory_super_admin', 403), ('F006', None, 'warehouse_worker', 403), ('F006', 'F006', 'factory_super_admin', 200)])
async def test_real_api_tenant_and_role_guard_before_data_load(monkeypatch, factory, claimed, role, expected):
    app = FastAPI()
    @app.middleware('http')
    async def identity(r, call_next):
        r.state.factory_id, r.state.role = factory, role
        return await call_next(r)
    app.include_router(api.router)
    calls = []
    async def load(req, fid):
        calls.append(fid)
        return pd.DataFrame(rows())
    monkeypatch.setattr(api, '_load_data', load)
    async with AsyncClient(transport=ASGITransport(app=app), base_url='http://test') as client:
        r = await client.post('/factory-recommendations', json={'source': 'upload', 'upload_id': 5258, 'factory_id': claimed, 'use_ai': False})
    assert r.status_code == expected
    if expected == 200:
        assert r.json()['data']['factoryId'] == 'F006'
        assert chart(r.json()['data']['recommendations'], 'production.quality')['kpis'][0]['value'] == '91.25'
        assert calls == ['F006']
    else:
        assert calls == []
