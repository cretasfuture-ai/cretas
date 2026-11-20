import copy
import pytest
from blacklake import complete_cost_store as module,live_monitor,smartbi_store
from blacklake.analytics import build
from tests.test_blacklake_analytics import source


def test_live_cost_is_not_frozen_at_26_and_keeps_original44_separate(source,monkeypatch):
    config={'tenant_id':'BLACKLAKE_POC_TEST','source_factory_id':'factory','run_id':'baseline'}
    monkeypatch.setenv('BLACKLAKE_LIVE_CONFIG','synthetic')
    monkeypatch.setattr(live_monitor,'config',lambda _:{'tenant_id':config['tenant_id'],'source_factory_id':'factory','baselineRunId':'baseline'})
    full=build(source,{'run_id':'live-current','finished_at':'2026-11-05T10:00:00Z','stock_pairs_not_returned':0})
    base=full['cost']['projects'][0]
    full['cost']['projects']=[{**copy.deepcopy(base),'projectId':i,'projectCode':f'P{i}'} for i in range(1,38)]
    full['costTraceability']={'workOrders':[],'actions':[],'scope':{},'summary':{}}
    before=copy.deepcopy(full);calls=[]
    source_rows=[{'id':i,'projectCode':f'P{i}','actualEndTime':'2026-11-05 10:00:00'} for i in range(1,38)]
    monkeypatch.setattr(live_monitor,'latest',lambda _:{'cycleId':'pinned','data':{'project':source_rows}})
    def read(v,**kwargs):calls.append(kwargs);return full
    monkeypatch.setattr(smartbi_store,'read',read)
    historical={'factId':'historical','cohort':'original44'}
    monkeypatch.setattr(module,'read_archived',lambda _:{'sourceRunId':'archived-run','sourceCheckedAt':'2026-11-04T10:00:00Z','batchAnalysis':{'results':[historical]}})
    result=module.read(config)
    assert result['contract']['eligibleOrders']==37 and result['contract']['live']
    assert len(result['analysis']['cost']['projects'])==37 and calls==[{'live_cycle_id':'pinned'}]
    assert result['batchAnalysis']['results'][0]['sourceRunId']=='archived-run'
    assert result['batchAnalysis']['results'][0]['historical'] is True
    assert result['batchAnalysis']['results'][0]['sourceCheckedAt']=='2026-11-04T10:00:00Z'
    assert result['batchAnalysis']['results'][1]['engine']=='deterministic_live'
    assert result['batchAnalysis']['results'][1]['cohort']=='eligible_live'
    assert 'eligible26' not in result['batchAnalysis']['cohorts']
    assert len(result['analysis']['cost']['projectDates'])==37 and full==before
    assert '生产' in module.followup(result,'live_order_1','找谁补资料')['answer']


def test_live_complete_cost_rejects_cross_factory_before_reading_snapshot(monkeypatch):
    monkeypatch.setenv('BLACKLAKE_LIVE_CONFIG','synthetic')
    monkeypatch.setattr(live_monitor,'config',lambda _:{'tenant_id':'OTHER','source_factory_id':'factory','baselineRunId':'baseline'})
    monkeypatch.setattr(live_monitor,'latest',lambda _:pytest.fail('foreign snapshot must not be read'))
    with pytest.raises(ValueError,match='SCOPE'):module.read({'tenant_id':'BLACKLAKE_POC_TEST','source_factory_id':'factory','run_id':'baseline'})


def test_live_gap_endpoint_uses_current_scope_instead_of_static_old_file(monkeypatch):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from unittest.mock import AsyncMock
    from blacklake import smartbi_api,traceability
    config={'tenant_id':'BLACKLAKE_POC_TEST','source_factory_id':'factory','run_id':'baseline'}
    monkeypatch.setenv('BLACKLAKE_SMARTBI_CONFIG','synthetic');monkeypatch.setenv('BLACKLAKE_LIVE_CONFIG','synthetic')
    monkeypatch.setattr(smartbi_api,'settings',lambda _:config)
    monkeypatch.setattr(smartbi_api,'cost_authority',AsyncMock())
    calls=[]
    def read(_):calls.append(True);return {'costTraceability':{'summary':{'workOrders':136}}}
    monkeypatch.setattr(smartbi_api,'read',read)
    monkeypatch.setattr(traceability,'configured_report',lambda _:pytest.fail('static history must not replace live gaps'))
    app=FastAPI()
    @app.middleware('http')
    async def identity(request,call_next):
        request.state.auth_method='jwt';request.state.factory_id=request.headers.get('x-test-factory','BLACKLAKE_POC_TEST');request.state.role='factory_super_admin'
        return await call_next(request)
    app.include_router(smartbi_api.router,prefix='/api/smartbi/blacklake')
    with TestClient(app) as client:
        response=client.get('/api/smartbi/blacklake/cost-traceability')
        assert response.status_code==200 and response.json()['data']['summary']['workOrders']==136
        assert client.get('/api/smartbi/blacklake/cost-traceability',headers={'x-test-factory':'OTHER'}).status_code==403
    assert len(calls)==1
