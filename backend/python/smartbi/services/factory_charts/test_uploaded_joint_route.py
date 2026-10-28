"""Exercise the public route, real joint qualifier, engine and snapshot scope."""
import pandas as pd
import pytest
from fastapi import FastAPI, Request
from httpx import ASGITransport, AsyncClient

from smartbi.api import financial_dashboard as api
from . import conversation, conversation_store, detail_store, engine
from .test_uploaded_joint import _datasets


def app():
    server = FastAPI()
    @server.middleware('http')
    async def identity(request: Request, call_next):
        request.state.factory_id = 'F006'
        request.state.user_id = 1309
        request.state.role = 'factory_super_admin'
        return await call_next(request)
    server.include_router(api.router)
    return server


@pytest.fixture
def uploaded(monkeypatch):
    p, q, c = _datasets()
    p.frame['产品'] = '产品A'
    frames = {1: p.frame.copy(), 2: q.frame.copy(), 3: c.frame.copy()}
    for batch, day, factor in [('b2', '2026-02-01', 2), ('b3', '2025-01-01', 3)]:
        row = {**p.frame.iloc[0].to_dict(), '来源主键': batch, '日期': day, '产量': 10 * factor, '良品数': 9 * factor}
        frames[1] = pd.concat([frames[1], pd.DataFrame([row])], ignore_index=True)
        qi = q.frame.copy(); qi['生产批次ID'] = batch; qi['检验ID'] = qi['检验ID'].map(lambda value: batch + value)
        frames[2] = pd.concat([frames[2], qi], ignore_index=True)
        cost = c.frame.copy(); cost['生产批次ID'] = batch; cost['日期'] = '2026-12-31'
        for column in ('材料成本', '人工成本', '设备成本', '其他成本', '总成本'):
            cost[column] *= factor
        frames[3] = pd.concat([frames[3], cost], ignore_index=True)
    # A locatable but invalid production fact must remain in the coverage denominator.
    frames[1] = pd.concat([frames[1], pd.DataFrame([{**p.frame.iloc[0].to_dict(), '来源主键': 'bad', '产量': -1}])], ignore_index=True)
    calls = []
    async def load(request, factory_id):
        assert factory_id == 'F006'
        calls.append(request.upload_id)
        return frames[request.upload_id].copy(deep=True)
    monkeypatch.setattr(api, '_load_data', load)
    return frames, calls


def request_body():
    subject = engine.analysis_subjects([engine.Dataset(pd.DataFrame([{'产品': '产品A', '产品ID': 'p1'}]), 'upload')], True)[0]['id']
    return {'source': 'upload', 'upload_id': 1, 'inspection_upload_id': 2, 'cost_upload_id': 3,
            'year': 2026, 'period_type': 'month_range', 'start_month': 1, 'end_month': 1,
            'guided': True, 'analysis_subject': subject, 'use_ai': False, 'detail_candidate_ids': []}


@pytest.mark.asyncio
async def test_three_table_route_uses_joint_family_exact_period_coverage_and_original_rows(uploaded):
    async with AsyncClient(transport=ASGITransport(app=app()), base_url='http://test') as client:
        response = await client.post('/factory-recommendations', json=request_body())
    assert response.status_code == 200, response.text
    data = response.json()['data']
    assert data['uploadId'] == 1 and data['jointUploads'] == {'inspectionUploadId': 2, 'costUploadId': 3}
    assert len(data['recommendations']) == 1
    candidate = data['recommendations'][0]
    assert candidate['analysis']['familyId'] == 'production.inspection_fused'
    assert [float(k['value']) for k in candidate['kpis']] == [10, 90, 90, 10]
    assert candidate['tableData']['rows'][0][0] == '2026-01-01'
    assert candidate['sourceMetadata']['coverage'] == {'totalBatches': 2, 'coveredBatches': 1}
    assert candidate['detailSnapshotKey'] and 'detailContext' not in candidate
    assert any('无效' in str(gap) for gap in data['unavailable'])
    # Actual original-row preparation must freeze the same production month.
    from .uploaded_joint import prepare_uploaded_join, dataset_for_join, parse_selection
    frames, _ = uploaded
    joined, _ = prepare_uploaded_join(*(frames[i] for i in (1, 2, 3)))
    dataset = dataset_for_join(parse_selection({'uploadId': 1, 'inspectionUploadId': 2, 'costUploadId': 3}), joined)
    snapshot = list(detail_store._prepared([candidate], [dataset], api.FactoryRecommendationRequest(**request_body()), ('F006', 1309, 'factory_super_admin')))[0][1]
    assert [row['来源主键'] for row in snapshot['rows']] == ['b1']
    assert snapshot['rows'][0]['总成本'] == 10


@pytest.mark.asyncio
async def test_related_upload_limit_and_cost_denial_happen_before_join(uploaded, monkeypatch):
    frames, calls = uploaded
    frames[2] = pd.concat([frames[2].iloc[[0]]] * 20001, ignore_index=True)
    async with AsyncClient(transport=ASGITransport(app=app()), base_url='http://test') as client:
        response = await client.post('/factory-recommendations', json=request_body())
        assert response.status_code == 422
        calls.clear()
        monkeypatch.setattr('smartbi.api.factory_production_gold._can_view_cost', lambda role: False)
        response = await client.post('/factory-recommendations', json=request_body())
        assert response.status_code == 403 and calls == []


@pytest.mark.asyncio
async def test_followup_restores_all_three_upload_roles_and_exact_original_period(uploaded, monkeypatch):
    async def plan(_question, catalog, *_args, **_kwargs):
        eligible = [item for item in catalog if item['familyId'] == 'production.inspection_fused']
        assert len(eligible) == 1
        return [eligible[0]['id']], {'mode': 'ai'}
    async def selected_details(candidates, datasets, request, owner, can_price):
        result = detail_store.prepare_shared_metadata(candidates, datasets, request, owner, can_price)
        for candidate in result:
            candidate['detailContext'] = {'id': 'test-selected', 'expiresAt': '2099-01-01T00:00:00Z'}
        return result
    monkeypatch.setattr(conversation, 'plan', plan)
    monkeypatch.setattr(detail_store, 'register_shared', selected_details)
    previous = conversation_store.set_store_for_tests(conversation_store.MemoryConversationStore())
    try:
        initial = request_body()
        body = {'question': '联合分析生产、检验和成本', 'source': 'upload', 'uploadId': 1,
                'inspectionUploadId': 2, 'costUploadId': 3, 'analysisSubject': initial['analysis_subject'],
                'year': 2026, 'periodType': 'month_range', 'startMonth': 1, 'endMonth': 1}
        async with AsyncClient(transport=ASGITransport(app=app()), base_url='http://test') as client:
            response = await client.post('/factory-analysis', json=body)
            assert response.status_code == 200, response.text
            first = response.json()['data']
            assert first['status'] == 'answered', first
            _, calls = uploaded; calls.clear()
            response = await client.post('/factory-analysis', json={'question': '继续联合比较生产与检验成本', 'contextId': first['contextId']})
            assert response.status_code == 200, response.text
            followup = response.json()['data']
            assert followup['status'] == 'answered', followup
            assert followup['uploadId'] == 1 and followup['jointUploads'] == first['jointUploads']
            assert followup['period'] == first['period']
            assert calls and set(calls) == {1, 2, 3}
            assert [float(k['value']) for k in followup['recommendations'][0]['kpis']] == [10, 90, 90, 10]
    finally:
        conversation_store.set_store_for_tests(previous)
