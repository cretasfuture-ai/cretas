import asyncio
import copy
import json
import time
from types import SimpleNamespace
import jwt
import pytest
from fastapi import FastAPI,HTTPException
from fastapi.testclient import TestClient
from auth_middleware import JWTAuthMiddleware
from smartbi.api import data_alerts
from smartbi.services import alert_local_ai,alert_datasets
from smartbi.services.dataset_alerts import analyze_dataset

KEY='synthetic-shared-alert-test-signing-key-only'
ROWS=[{'工单编码':'T1','产品代码':'P','产品名称':'合成产品','单位':'kg','成品良品量':10,'材料成本':None,'人工成本':0}]

@pytest.fixture(autouse=True)
def private_ai_state(monkeypatch,tmp_path):
    monkeypatch.setenv('SMARTBI_ALERT_AI_STATE_FILE',str(tmp_path/'state.sqlite3'))
    monkeypatch.setattr(alert_local_ai,'_lock',asyncio.Lock());alert_local_ai._cache.clear()

def token(factory='A',role='factory_super_admin'):
    return {'Authorization':'Bearer '+jwt.encode({'factoryId':factory,'userId':55,'role':role,'exp':int(time.time())+300},KEY,algorithm='HS256')}

@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv('SMARTBI_ALERT_AI_TRANSPORT','disabled')
    app=FastAPI();app.add_middleware(JWTAuthMiddleware,jwt_secret=KEY,enabled=True);app.include_router(data_alerts.router)
    with TestClient(app) as c:yield c

def body(**extra):return {'rows':copy.deepcopy(ROWS),'source_type':'api','source_id':'synthetic','dataset_kind':'production_cost',**extra}

def test_authenticated_inline_api_and_excel_same_findings(client):
    a=client.post('/api/smartbi/data-alerts/analyze',json=body(),headers=token()).json()['data']
    b=client.post('/api/smartbi/data-alerts/analyze',json=body(source_type='excel'),headers=token()).json()['data']
    assert a['summary']==b['summary'] and a['runId']!=b['runId']
    assert [i['type'] for i in a['items']]==[i['type'] for i in b['items']]
    assert a['scope']['factoryId']=='A'
    assert 'NUMERIC_FIELD_MISSING' in a['summary']['byType']
    assert 'NUMERIC_FIELD_ZERO' in a['summary']['byType']

def test_authentication_cannot_be_replaced_with_factory_header(client):
    assert client.post('/api/smartbi/data-alerts/analyze',json=body(),headers={'X-Factory-Id':'A'}).status_code==401
    assert client.post('/api/smartbi/data-alerts/analyze',json=body(factory_id='B'),headers=token()).status_code==403

def test_nonfinite_wire_value_has_safe_422_instead_of_json_serialization_crash(client):
    response=client.post('/api/smartbi/data-alerts/analyze',content='{"rows":[{"x":NaN}],"source_id":"synthetic"}',headers={**token(),'Content-Type':'application/json'})
    assert response.status_code==422 and 'NaN' not in response.text

def test_unauthorized_cost_role_sees_no_values_and_cannot_invoke_ai(client):
    response=client.post('/api/smartbi/data-alerts/analyze',json=body(),headers=token(role='viewer'))
    report=response.json()['data'];assert report['capabilities']['cost']['status']=='restricted'
    assert not report['items'] and not report['summary']['byType'] and not report['summary']['byFamily']
    assert report['runId']=='restricted'
    assert all(not i['fields'] and not i['evidence'] and not i['entityRefs'] for i in report['items'])
    assert client.post('/api/smartbi/data-alerts/analyze',json=body(with_ai=True),headers=token(role='viewer')).status_code==403

def test_ai_not_configured_is_explicit_and_rules_survive(client):
    report=client.post('/api/smartbi/data-alerts/analyze',json=body(with_ai=True),headers=token()).json()['data']
    assert report['ai']['status']=='not_configured' and report['items']

def test_upload_identity_checked_before_loading_and_foreign_not_found(client,monkeypatch):
    calls=[]
    def load(factory,upload):
        calls.append((factory,upload))
        if factory!='A':raise HTTPException(404,'upload not found')
        return analyze_dataset([{'收入':None}],factory_id=factory,source_type='finance',source_id='upload:'+str(upload),columns=[{'originalName':'收入','isMeasure':True}])
    monkeypatch.setattr(data_alerts,'load_upload',load)
    async def finance_full(*a):return True
    monkeypatch.setattr('smartbi.services.factory_charts.finance_auth.full_factory_finance_access',finance_full)
    assert client.get('/api/smartbi/data-alerts/uploads/1').status_code==401 and not calls
    assert client.get('/api/smartbi/data-alerts/uploads/1',headers=token('B')).status_code==404
    assert client.get('/api/smartbi/data-alerts/uploads/1?factory_id=B',headers=token()).status_code==403
    report=client.get('/api/smartbi/data-alerts/uploads/1',headers=token()).json()['data']
    assert report['capabilities']['cost']['status']=='not_applicable' and report['items']

def test_financial_source_without_row_scope_authority_returns_no_derived_findings(client,monkeypatch):
    monkeypatch.setattr(data_alerts,'load_upload',lambda f,u:analyze_dataset([{'收入':None}],factory_id=f,source_type='finance',source_id='finance',columns=[{'originalName':'收入','isMeasure':True}]))
    async def deny(*a):return False
    monkeypatch.setattr('smartbi.services.factory_charts.finance_auth.full_factory_finance_access',deny)
    report=client.get('/api/smartbi/data-alerts/uploads/1',headers=token()).json()['data']
    assert report['analysisStatus']=='restricted' and not report['items'] and report['runId']=='restricted'

def test_stored_manufacturing_requires_actual_process_secret_authority(client,monkeypatch):
    def load(factory,upload):
        return analyze_dataset([dict(ROWS[0],材料成本=100)],factory_id=factory,source_type='excel',source_id='u1')
    async def unavailable(*a):return SimpleNamespace(is_full=False)
    monkeypatch.setattr(data_alerts,'load_upload',load)
    monkeypatch.setattr('smartbi.services.factory_charts.cost_scope.resolve_cost_scope',unavailable)
    report=client.get('/api/smartbi/data-alerts/uploads/1',headers=token()).json()['data']
    assert report['analysisStatus']=='restricted' and not report['items']
    assert report['ai']['status']=='restricted'
    async def full(*a):return SimpleNamespace(is_full=True)
    monkeypatch.setattr('smartbi.services.factory_charts.cost_scope.resolve_cost_scope',full)
    report=client.get('/api/smartbi/data-alerts/uploads/1',headers=token()).json()['data']
    assert report['capabilities']['cost']['status']=='summary_only'

def test_ambiguous_cost_fields_cannot_skip_authority_or_leak_to_viewer(client,monkeypatch):
    def load(factory,upload):
        return analyze_dataset([{'工单编码':'W','材料成本':100,'原料成本':200}],factory_id=factory,source_type='excel',source_id='ambiguous')
    async def unavailable(*a):return SimpleNamespace(is_full=False)
    monkeypatch.setattr(data_alerts,'load_upload',load)
    monkeypatch.setattr('smartbi.services.factory_charts.cost_scope.resolve_cost_scope',unavailable)
    for role in ('viewer','factory_super_admin'):
        report=client.get('/api/smartbi/data-alerts/uploads/1',headers=token(role=role)).json()['data']
        assert report['analysisStatus']=='restricted' and report['runId']=='restricted'
        assert not report['items'] and report['summary']['byType']=={}

def test_local_ai_facts_omit_records_names_amounts_and_user_labels():
    report=analyze_dataset(ROWS,factory_id='A',source_type='api',source_id='x')
    text=json.dumps(alert_local_ai.facts_for(report),ensure_ascii=False)
    assert '合成产品' not in text and 'T1' not in text and '材料成本' not in text and 'factoryId' not in text

@pytest.mark.parametrize('url',['https://example.com:11434','http://127.0.0.1:11434@evil.com','http://127.0.0.1:11434/api','http://localhost:11434?redirect=evil','http://192.168.1.1:11434'])
def test_ai_cannot_egress_to_unconfigured_external_destination(monkeypatch,url):
    monkeypatch.setenv('SMARTBI_ALERT_AI_TRANSPORT','http');monkeypatch.setenv('SMARTBI_ALERT_AI_BASE_URL',url)
    with pytest.raises(ValueError):alert_local_ai.provider_config()

def test_bad_model_output_leaves_rules_and_discloses_unavailability(monkeypatch):
    monkeypatch.setenv('SMARTBI_ALERT_AI_TRANSPORT','ssh')
    monkeypatch.setattr(alert_local_ai,'_lock',asyncio.Lock());alert_local_ai._cache.clear()
    monkeypatch.setattr(alert_local_ai,'infer',lambda *a:{'reviews':[{'alertId':'invented','check':'CHECK_PAYROLL','priority':'high'}]})
    report=analyze_dataset(ROWS,factory_id='A',source_type='api',source_id='x');before=copy.deepcopy(report['items'])
    asyncio.run(alert_local_ai.enrich(report))
    assert report['ai']['status']=='unavailable' and report['items']==before

def test_partial_model_output_never_claims_complete():
    facts=[{'alertId':'A','allowedChecks':['CHECK_SOURCE_VALUES']},{'alertId':'B','allowedChecks':['CHECK_DUPLICATES']}]
    with pytest.raises(ValueError,match='INCOMPLETE'):
        alert_local_ai.validate_response({'reviews':[{'alertId':'A','check':'CHECK_SOURCE_VALUES','priority':'normal'}]},facts)

def test_same_dataset_cache_is_bound_to_tenant_and_model(monkeypatch):
    monkeypatch.setenv('SMARTBI_ALERT_AI_TRANSPORT','ssh');monkeypatch.setenv('SMARTBI_ALERT_AI_MODEL','qwen3:8b')
    monkeypatch.setattr(alert_local_ai,'_lock',asyncio.Lock());alert_local_ai._cache.clear();calls=[]
    def infer(payload,config):
        calls.append(config['model']);facts=json.loads(payload['messages'][1]['content'])['facts']
        return {'reviews':[{'alertId':f['alertId'],'check':f['allowedChecks'][0],'priority':'normal'} for f in facts]}
    monkeypatch.setattr(alert_local_ai,'infer',infer)
    def run(factory):return asyncio.run(alert_local_ai.enrich(analyze_dataset(ROWS,factory_id=factory,source_type='api',source_id='same')))
    assert run('A')['ai']['cacheHit'] is False and run('A')['ai']['cacheHit'] is True
    assert run('B')['ai']['cacheHit'] is False
    monkeypatch.setenv('SMARTBI_ALERT_AI_MODEL','qwen3.8:27b');assert run('A')['ai']['cacheHit'] is False
    assert calls==['qwen3:8b','qwen3:8b','qwen3.8:27b']

def test_model_identity_is_checked_not_merely_echoed_from_config(monkeypatch):
    monkeypatch.setattr(alert_local_ai.subprocess,'run',lambda *a,**k:SimpleNamespace(returncode=0,stdout=json.dumps({'model':'qwen3:8b','message':{'content':'{}'}})))
    with pytest.raises(RuntimeError,match='MODEL_MISMATCH'):
        alert_local_ai.infer({'model':'qwen3.8:27b'}, {'transport':'ssh','host':'canada-mini','model':'qwen3.8:27b'})

def test_model_serializes_cross_tenant_queue_without_hourly_quotas():
    from concurrent.futures import ThreadPoolExecutor
    from threading import Event
    from smartbi.services.alert_ai_coordination import run
    from smartbi.services.alert_ai_coordination import _connection
    _connection().close()  # Schema fsync is separate from inference scheduling.
    started=Event();release=Event();config={'transport':'ssh','host':'synthetic'}
    def slow(*args):started.set();release.wait(20);return {'ok':True}
    with ThreadPoolExecutor(max_workers=2) as pool:
        first=pool.submit(run,slow,{},config,'A',1)
        assert started.wait(10)
        second=pool.submit(run,lambda *a:{'second':True},{},config,'B',2)
        time.sleep(0.05)
        assert not second.done()
        release.set();assert first.result()['ok']
        assert second.result()['second']
    for _ in range(14):run(lambda *a:None,{},config,'A',1)
