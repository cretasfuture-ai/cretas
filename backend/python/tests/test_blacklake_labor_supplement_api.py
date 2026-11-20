import copy
from types import SimpleNamespace
from unittest.mock import AsyncMock
import httpx
import pytest
from fastapi import FastAPI,HTTPException
from fastapi.testclient import TestClient
from blacklake import smartbi_api as api
from blacklake.labor_supplements import status,store,TaskSupplement,Segment,revision
from blacklake.labor_supplement_analysis import apply_alerts,explain
from blacklake.public_preview import create_app


@pytest.fixture(autouse=True)
def platform_test_codec(monkeypatch):
    import os,json,base64
    if os.name!='nt':
        import blacklake.labor_supplements as module
        monkeypatch.setattr(module,'_seal',lambda value:base64.b64encode(json.dumps(value).encode()).decode())
        monkeypatch.setattr(module,'_unseal',lambda value:json.loads(base64.b64decode(value)))


def facts():
    p={'projectId':1,'projectCode':'GD1','product':'样品','unit':'包','laborCost':100,'processes':[{'taskId':11,'process':'切配','laborCost':60},{'taskId':12,'process':'包装','laborCost':40}]}
    return {'runId':'live:'+'a'*32,'cost':{'projects':[p]},'costAlerts':{'scope':'synthetic','runId':'live:'+'a'*32,'ruleVersion':'test','items':[],'summary':{}},'sectionInsights':[{'sectionId':'labor','observations':[],'calculation':[],'nextSteps':[]}]}


@pytest.fixture
def client(tmp_path,monkeypatch):
    configured={'tenant_id':'T','source_factory_id':'F','run_id':'baseline','labor_supplements_path':str(tmp_path/'supp.sqlite3')}
    monkeypatch.setenv('BLACKLAKE_SMARTBI_CONFIG','synthetic')
    monkeypatch.setattr(api,'settings',lambda _:configured)
    monkeypatch.setattr(api,'cost_authority',AsyncMock())
    def read(*args,**kw):
        a=facts();a['laborSupplements']=status(configured,a);apply_alerts(a);explain(a);return a
    monkeypatch.setattr(api,'read',read)
    app=FastAPI()
    @app.middleware('http')
    async def identity(request,next):
        request.state.auth_method='jwt';request.state.factory_id=request.headers.get('factory','T');request.state.role='factory_super_admin';request.state.user_id='synthetic_admin';return await next(request)
    app.include_router(api.router)
    with TestClient(app) as c:yield c,configured


def entry(tid,people,start='08:00',end='10:00',revision=0):
    return {'projectId':1,'taskId':tid,'segments':[{'start':f'2026-11-06T{start}:00+08:00','end':f'2026-11-06T{end}:00+08:00','workers':people,'breakMinutes':0,'shutdownMinutes':0}],
            'confirmed':True,'expectedRevision':revision,'source':'manual','reference':'synthetic_shift','zeroConfirmed':False}


def test_fill_then_read_recalculates_and_resolves_labor_missing_alert_without_changing_payroll(client):
    c,config=client
    before=c.get('/labor-supplements?project_ids=1').json()['data'];assert before['summary']['perPerson'] is None
    response=c.post('/labor-supplements',json={'sourceRunId':facts()['runId'],'entries':[entry(11,['E1','E2']),entry(12,['E1'],'10:00','11:00')]})
    assert response.status_code==200,response.text
    result=c.get('/labor-supplements?project_ids=1').json()['data'];assert result['summary']['perPerson']==50
    assert result['summary']['personHours']==5 and result['summary']['perPersonHour']==20
    assert [p['allocated'] for p in result['summary']['processes']]==[80,20]
    analyzed=api.read(config);assert not analyzed['costAlerts']['items'];assert analyzed['cost']['projects'][0]['laborCost']==100
    assert any('50.00' in s for s in analyzed['sectionInsights'][0]['observations'])
    assert 'E1' not in str(result) and 'E2' not in str(result)


def test_factory_denial_and_stale_source_do_not_save_any_supplement(client):
    c,config=client
    assert c.get('/labor-supplements?project_ids=1',headers={'factory':'OTHER'}).status_code==403
    assert c.post('/labor-supplements',json={'sourceRunId':'live:'+'b'*32,'entries':[entry(11,['E1'])]}).status_code==409
    assert c.get('/labor-supplements?project_ids=999').status_code==404
    assert status(config,facts())['revision'] in ('0',0)


def test_partial_completion_and_source_changes_keep_specific_gaps(client):
    c,config=client
    assert c.post('/labor-supplements',json={'sourceRunId':facts()['runId'],'entries':[entry(11,['E1'])]}).status_code==200
    a=api.read(config);assert a['laborSupplements']['summary']['complete'] is False
    assert a['costAlerts']['items'][0]['evidence']['tasks']==[{'taskId':12,'process':'包装','status':'missing'}]
    changed=facts();changed['cost']['projects'][0]['processes'][0]['laborCost']=61;changed['laborSupplements']=status(config,changed);apply_alerts(changed)
    assert changed['costAlerts']['items'][0]['type']=='LABOR_SUPPLEMENT_STALE'


class Broker:
    async def get(self,invalidate=False):return 'synthetic_not_real'


def test_public_view_cannot_write_or_spoof_local_administrator(tmp_path):
    calls=[]
    def upstream(request):
        calls.append(request)
        return httpx.Response(200,json={'success':True,'data':{'editable':True,'tasks':[{'reference':'private staff note'}]}})
    app=create_app(tmp_path,'https://test.cretaceousfuture.com',broker=Broker(),transport=httpx.MockTransport(upstream))
    path='/smartbi-api/api/smartbi/blacklake/labor-supplements'
    @app.middleware('http')
    async def loopback_client(request,next):
        request.scope['client']=('127.0.0.1',55000)
        return await next(request)
    with TestClient(app,base_url='http://127.0.0.1:5188') as c:
        public={'Origin':'https://test.cretaceousfuture.com','Host':'test.cretaceousfuture.com','X-Preview-Client-IP':'1.2.3.4'}
        r=c.get(path+'?project_ids=1',headers=public);assert r.json()['data']['editable'] is False;assert 'private staff note' not in r.text
        assert c.post(path,headers=public,json={}).status_code==403
        assert c.post(path,headers={**public,'Host':'127.0.0.1:5188'},json={}).status_code==403
        before=len(calls)
        response=c.post(path,headers={'Origin':'http://127.0.0.1:5188'},json={'sourceRunId':facts()['runId'],'entries':[entry(11,['E1'])]})
        assert response.status_code==200 and len(calls)==before+1
        assert c.post(path,headers={'Origin':'https://evil.invalid'},json={}).status_code==403


def config(tmp_path):return {'tenant_id':'T','source_factory_id':'F','run_id':'baseline','labor_supplements_path':str(tmp_path/'supp.sqlite3')}


def test_all_or_none_and_revision_conflicts_retain_immutable_history(tmp_path):
    import sqlite3,json
    v=config(tmp_path);a=facts();bad=entry(999,['E2'])
    with pytest.raises(ValueError):store(v,a['runId'],[TaskSupplement.model_validate(e) for e in [entry(11,['private_E1']),bad]],a,'admin')
    assert revision(v)=='0'
    store(v,a['runId'],[TaskSupplement.model_validate(entry(11,['private_E1']))],a,'admin')
    with pytest.raises(ValueError,match='REVISION_CONFLICT'):store(v,a['runId'],[TaskSupplement.model_validate(entry(11,['E3']))],a,'admin')
    store(v,a['runId'],[TaskSupplement.model_validate(entry(11,['private_E2'],revision=1))],a,'admin')
    with sqlite3.connect(v['labor_supplements_path']) as db:
        audit=db.execute('SELECT payload FROM labor_audit ORDER BY id').fetchall()
        assert len(audit)==2 and audit[0]!=audit[1]
        assert 'private_E1' not in str(audit) and 'private_E2' not in str(audit)
    assert status(v,a)['tasks'][0]['revision']==2


def test_per_order_allocation_dedup_and_cent_remainder_are_not_global_pool(tmp_path):
    from decimal import Decimal
    a=facts();a['cost']['projects'].append({'projectId':2,'projectCode':'GD2','product':'样品','laborCost':200,'processes':[{'taskId':21,'process':'切配','laborCost':200},{'taskId':22,'process':'包装','laborCost':0}]})
    data=[entry(11,['A'],'08:00','09:00'),entry(12,['A'],'09:00','11:00'),{**entry(21,['B'],'08:00','10:00'),'projectId':2},{**entry(22,['B'],'10:00','11:00'),'projectId':2}]
    v=config(tmp_path);store(v,a['runId'],[TaskSupplement.model_validate(e) for e in data],a,'admin')
    result=status(v,a)['summary'];assert result['complete'] and result['participants']==2 and result['perPerson']==150
    assert result['personHours']==6 and result['perPersonHour']==50
    assert [r['allocated'] for r in result['processes']]==[166.66,133.34]
    assert sum(Decimal(str(r['allocated'])) for r in result['processes'])==300


def test_zero_hours_without_roster_never_invents_actual_headcount_or_negative_rounding_tail(tmp_path):
    a=facts();a['cost']['projects'][0]['laborCost']=0.02;a['cost']['projects'][0]['processes']=[{'taskId':11+i,'process':f'工序{i}','laborCost':.02 if i==0 else 0} for i in range(5)]
    entries=[entry(11+i,[f'E{i}']) for i in range(4)]
    entries.append({'projectId':1,'taskId':15,'segments':[],'confirmed':True,'source':'manual','reference':'zero','zeroConfirmed':True})
    v=config(tmp_path);store(v,a['runId'],[TaskSupplement.model_validate(e) for e in entries],a,'admin')
    result=status(v,a)['summary'];assert result['complete'] and result['perPerson'] is None and result['participants'] is None
    assert result['processes'][-1]['allocated']==0
    assert all(p['allocated']>=0 for p in result['processes']) and round(sum(p['allocated'] for p in result['processes']),2)==.02


def test_same_person_overlapping_inside_one_task_and_cross_order_is_rejected_atomically(tmp_path):
    a=facts();e=entry(11,['A']);e['segments']*=2
    with pytest.raises(ValueError,match='WORKER_SEGMENT_OVERLAP'):store(config(tmp_path),a['runId'],[TaskSupplement.model_validate(e)],a,'admin')
    assert revision(config(tmp_path))=='0'


@pytest.mark.parametrize('change',[{'workers':[]},{'workers':['A',' A ']},{'breakMinutes':float('nan')},{'shutdownMinutes':float('inf')},{'breakMinutes':130},{'start':'2026-11-06T08:00:00'}])
def test_invalid_segment_facts_are_rejected_before_any_storage(change):
    seg=entry(11,['A'])['segments'][0];seg.update(change)
    with pytest.raises(ValueError):Segment.model_validate(seg)


def test_post_commit_readback_failure_returns_saved_receipt_not_false_rollback(client,monkeypatch):
    c,config=client
    import blacklake.labor_supplements as module
    original=module.status
    def status_after_commit(*args,**kwargs):
        if revision(config)!='0':raise RuntimeError('synthetic readback failure')
        return original(*args,**kwargs)
    monkeypatch.setattr(module,'status',status_after_commit)
    r=c.post('/labor-supplements',json={'sourceRunId':facts()['runId'],'entries':[entry(11,['E1'])]})
    assert r.status_code==200 and r.json()['data']['saved'] is True and r.json()['data']['readbackPending'] is True
    assert revision(config)=='1'


def test_windows_storage_protects_hmac_secret_roster_and_references(tmp_path):
    import os,sqlite3
    if os.name!='nt':pytest.skip('Real CurrentUser-DPAPI acceptance requires Windows')
    from blacklake.labor_private_codec import unseal
    v=config(tmp_path);e=entry(11,['private_staff']);e['reference']='private_personnel_reference'
    store(v,facts()['runId'],[TaskSupplement.model_validate(e)],facts(),'test_admin')
    raw=(tmp_path/'supp.sqlite3').read_bytes()
    assert b'private_staff' not in raw and b'private_personnel_reference' not in raw
    with sqlite3.connect(v['labor_supplements_path']) as db:
        secret=db.execute('SELECT secret FROM labor_meta').fetchone()[0]
        assert len(bytes.fromhex(unseal(secret)['key']))==32
        assert b'"workers"' not in raw and b'"key"' not in raw
