from types import SimpleNamespace

import pandas as pd
import pytest
from fastapi import FastAPI, Request
from httpx import ASGITransport, AsyncClient

from smartbi.api import financial_dashboard as api
from smartbi.services.factory_charts import details, detail_store


def app_for(factory='F006', user=1309, role='factory_super_admin'):
    app = FastAPI()

    @app.middleware('http')
    async def trusted_identity(request: Request, call_next):
        request.state.factory_id = factory
        request.state.user_id = user
        request.state.role = role
        return await call_next(request)

    app.include_router(api.router)
    return app


@pytest.fixture(autouse=True)
def clean_details(monkeypatch):
    # Router/auth/engine contracts use a deterministic unit store here. The
    # actual PostgreSQL store and independent workers have a separate PG test.
    async def register(*args): return details.register(*args)
    async def requires(*args): return details.requires_finance(*args)
    async def page(*args): return details.page(*args)
    monkeypatch.setattr(detail_store, 'register_shared', register)
    monkeypatch.setattr(detail_store, 'requires_finance_shared', requires)
    monkeypatch.setattr(detail_store, 'page_shared', page)
    details.clear_cache()
    yield
    details.clear_cache()


@pytest.mark.asyncio
async def test_actual_recommendation_route_exposes_owner_bound_original_pages(monkeypatch):
    original = pd.DataFrame([
        {'日期':'2026-10-01','产品':'A','单位':'kg','产量':20},
        {'日期':'2026-10-02','产品':'B','单位':'kg','产量':30},
    ])
    async def load(*args): return original.copy()
    monkeypatch.setattr(api, '_load_data', load)
    async with AsyncClient(transport=ASGITransport(app=app_for()), base_url='http://test') as client:
        response = await client.post('/factory-recommendations', json={'source':'upload','upload_id':42,'use_ai':False})
        assert response.status_code == 200
        cards = response.json()['data']['recommendations']
        candidate = next(c for c in cards if '.production.output' in c['id'] and c['unit']=='kg')
        assert candidate['kpis'][0]['value'] == '50'
        token = candidate['detailContext']['id']
        result = await client.post('/factory-details', json={'context_id':token,'page_size':1})
        assert result.status_code == 200
        data = result.json()['data']
        assert data['total']==2 and data['hasMore'] and data['rows']==[original.iloc[0].to_dict()]
        assert data['factoryId']=='F006' and data['candidateId']==candidate['id']
        last = await client.post('/factory-details', json={'context_id':token,'page':2,'page_size':1})
        assert last.json()['data']['rows']==[original.iloc[1].to_dict()]
        point = await client.post('/factory-details', json={'context_id':token,'point':{'name':'A','seriesName':'产量','dataIndex':0}})
        assert point.status_code==200 and point.json()['data']['total']==1
        assert point.json()['data']['rows'][0]['产量']==20
    for factory,user,role in [('OTHER',1309,'factory_super_admin'),('F006',999,'factory_super_admin'),('F006',1309,'factory_admin')]:
        async with AsyncClient(transport=ASGITransport(app=app_for(factory,user,role)),base_url='http://test') as other:
            assert (await other.post('/factory-details',json={'context_id':token})).status_code==403


@pytest.mark.asyncio
@pytest.mark.parametrize('period_type,start_month,end_month,expected_label,expected_total', [
    ('all', 1, 12, '全部历史', 3),
    ('month_range', 10, 10, '2026年10–10月', 2),
    ('month_range', 9, 10, '2026年9–10月', 3),
])
async def test_recommendation_and_detail_share_period_label_and_filtered_rows(
        monkeypatch, period_type, start_month, end_month, expected_label, expected_total):
    original = pd.DataFrame([
        {'日期': '2026-09-30', '产品': 'outside', '单位': 'kg', '产量': 9},
        {'日期': '2026-10-01', '产品': 'inside', '单位': 'kg', '产量': 20},
        {'日期': '2026-10-02', '产品': 'inside-2', '单位': 'kg', '产量': 21},
    ])
    async def load(*args): return original.copy()
    monkeypatch.setattr(api, '_load_data', load)
    payload = {'source': 'upload', 'upload_id': 42, 'use_ai': False,
               'period_type': period_type, 'start_month': start_month, 'end_month': end_month}
    async with AsyncClient(transport=ASGITransport(app=app_for()), base_url='http://test') as client:
        response = await client.post('/factory-recommendations', json=payload)
        assert response.status_code == 200
        data = response.json()['data']
        assert data['period']['label'] == expected_label
        candidate = next(c for c in data['recommendations'] if '.production.output' in c['id'] and c['unit'] == 'kg')
        token = candidate['detailContext']['id']
        detail = await client.post('/factory-details', json={'context_id': token})
        assert detail.status_code == 200
        detail_data = detail.json()['data']
        assert detail_data['period'] == expected_label
        assert detail_data['total'] == expected_total
        if expected_total == 2:
            assert {row['产品'] for row in detail_data['rows']} == {'inside', 'inside-2'}


@pytest.mark.asyncio
@pytest.mark.parametrize('factory,user,role,expected',[(None,1309,'factory_super_admin',401),('F006',None,'factory_super_admin',401),('F006',1309,'operator',403)])
async def test_details_requires_trusted_analytic_user(factory,user,role,expected):
    async with AsyncClient(transport=ASGITransport(app=app_for(factory,user,role)),base_url='http://test') as client:
        response=await client.post('/factory-details',json={'context_id':'not-a-valid-context'})
        assert response.status_code==expected


@pytest.mark.asyncio
async def test_invalid_expired_and_cross_factory_detail_inputs():
    async with AsyncClient(transport=ASGITransport(app=app_for()),base_url='http://test') as client:
        assert (await client.post('/factory-details',json={'context_id':'not-a-valid-context'})).status_code==410
        assert (await client.post('/factory-details',json={'context_id':'not-a-valid-context','factory_id':'OTHER'})).status_code==403
        for extra in [{'page_size':101},{'point':{'name':'A','sql':'SELECT *'}},{'filters':[{'field':'A','value':{'foreign':'id'}}]},{'sql':'SELECT *'}]:
            assert (await client.post('/factory-details',json={'context_id':'not-a-valid-context',**extra})).status_code==422


@pytest.mark.asyncio
async def test_business_financial_snapshot_rechecks_current_finance_scope(monkeypatch):
    raw={'frame':pd.DataFrame([{'日期':'2026-10-01','金额':30}]),'source':'cashflow · actual ledger'}
    candidate={'id':'cashflow.example','source':raw['source'],'title':'现金流','domain':'财务','unit':'元','chart':{'chartType':'bar','echartsOption':{}}}
    token=details.register([candidate],[raw],SimpleNamespace(period_type='all'),('F006',1309,'factory_super_admin'),True)[0]['detailContext']['id']
    calls=[]
    async def denied(request,factory): calls.append(factory); return False
    monkeypatch.setattr('smartbi.services.factory_charts.finance_auth.full_factory_finance_access',denied)
    async with AsyncClient(transport=ASGITransport(app=app_for()),base_url='http://test') as client:
        response=await client.post('/factory-details',json={'context_id':token})
        assert response.status_code==403 and calls==['F006']
