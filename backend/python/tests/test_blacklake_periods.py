import asyncio
import copy
import time
from datetime import datetime, timezone

from fastapi import FastAPI
from fastapi.testclient import TestClient
import jwt
import pytest

from auth_middleware import JWTAuthMiddleware
from blacklake import smartbi_api, period_runtime
from blacklake.analytics import build
from blacklake.cost_review import review
from blacklake.period_projection import project_period, describe_period
from blacklake.section_insights import build as sections
from smartbi.services.cost_data_alerts import evaluate
from smartbi.services.period_windows import parse_window
from tests.test_blacklake_analytics import source


META={'run_id':'run-period','finished_at':'2026-11-05T04:00:00Z','stock_pairs_not_returned':3}


def dated(source):
    result=copy.deepcopy(source)
    result['project'][0].update(actualEndTime='2026-11-03T00:00:00+08:00',createTime='2026-10-01T10:00:00+08:00')
    result['stock_order'][0]['bizAt']='2026-10-20T10:00:00+08:00'
    return result


def period_analysis(raw, window, metadata=None):
    data,meta,period=project_period(raw,metadata or META,window)
    value=build(data,meta)
    value['costIntelligence']={'comparisons':[]}
    value['eligibleCostReview']=review(value)
    from blacklake.full_archive import digest
    value['costAlerts']=evaluate(value['cost']['projects'],scope=digest(('BLACKLAKE_POC_TEST','SOURCE-A')),run_id=value['runId'])
    value['dataAlerts']=value['costAlerts']
    value['sectionInsights']=sections(value)
    return describe_period(value,period)


def test_completed_date_cohort_preserves_cross_day_material_and_full_task_wage(source):
    raw=dated(source)
    raw['project'].append({**raw['project'][0],'id':9,'projectCode':'P2','actualEndTime':'2026-11-04T00:00:00+08:00'})
    raw['task'].append({**raw['task'][0],'id':99,'projectId':9,'sumOutputSalary':999})
    raw['output'].append({**raw['output'][0],'id':98,'projectId':9,'numberofgoodProducts':999})
    before=copy.deepcopy(raw)
    window=parse_window('2026-11-03','2026-11-03',kind='day')
    value=period_analysis(raw,window)
    assert value['production']['projectCount']==1 and value['production']['taskCount']==1
    assert value['production']['outputCount']==1
    assert value['cost']['projects'][0]['materialCost']==50
    assert value['cost']['projects'][0]['laborCost']==20
    assert value['cost']['projects'][0]['unitIdentifiedDirectCost']==7
    assert value['inventory']['records']==[]
    assert value['periodMetadata']['inventoryStatus']=='historical_balance_unavailable'
    assert '生命周期' in value['sectionInsights'][0]['scopeDescription']
    assert raw==before


def test_missing_completed_date_never_falls_back_to_creation_and_created_is_explicit(source):
    raw=dated(source)
    raw['project'][0].update(actualEndTime=None,createTime='2026-11-03T10:00:00+08:00')
    finished=period_analysis(raw,parse_window('2026-11-03','2026-11-03'))
    assert finished['production']['projectCount']==0
    assert finished['periodMetadata']['unassignedCompletedProjects']==1
    created=period_analysis(raw,parse_window('2026-11-03','2026-11-03',basis='created'))
    assert created['production']['projectCount']==1
    assert '工单创建日期' in created['periodMetadata']['scopeDescription']


def test_business_timezone_half_open_and_equivalent_custom_range_share_fingerprint(source):
    raw=dated(source);raw['project'][0]['actualEndTime']='2026-11-02T16:00:00Z'
    day=period_analysis(raw,parse_window('2026-11-03','2026-11-03',kind='day'))
    custom=period_analysis(raw,parse_window('2026-11-03','2026-11-03',kind='custom'))
    assert day['production']['projectCount']==1
    assert day['periodMetadata']['fingerprint']==custom['periodMetadata']['fingerprint']
    raw['project'][0]['actualEndTime']='2026-11-03T16:00:00Z'
    assert period_analysis(raw,parse_window('2026-11-03','2026-11-03'))['production']['projectCount']==0


def test_partial_snapshot_and_unknown_movement_links_are_visible_without_zero_backfill(source):
    raw=dated(source)
    unknown=copy.deepcopy(raw['stock_order'][0]);unknown.update(orderNo='UNKNOWN',bizAt='2026-11-03T10:00:00+08:00')
    unknown['orderDetails'][0].update(associatedBizOrderId=999,associatedBizOrderCode='NOT-FOUND')
    raw['stock_order'].append(unknown)
    value=period_analysis(raw,parse_window('2026-11-03','2026-12-31',kind='custom'))
    assert value['cost']['unlinkedMaterialLines']==1
    assert value['periodMetadata']['periodMovementOrders']==1
    assert value['periodMetadata']['coverageStatus']=='source_snapshot_before_period_end'
    assert value['inventory']['records']  # Snapshot is inside the selected period, not a historic closing balance.


@pytest.fixture
def period_client(monkeypatch,source,tmp_path):
    raw=dated(source);key='period-synthetic-jwt-signing-key-only'
    config={'tenant_id':'BLACKLAKE_POC_TEST','source_factory_id':'SOURCE-A','run_id':META['run_id']}
    monkeypatch.setenv('BLACKLAKE_SMARTBI_CONFIG','synthetic.dpapi')
    monkeypatch.setenv('BLACKLAKE_PERIOD_REPORT_DB',str(tmp_path/'reports.sqlite3'))
    monkeypatch.setenv('BLACKLAKE_ALERT_FOLLOWUP_DB',str(tmp_path/'followups.sqlite3'))
    monkeypatch.setenv('SMARTBI_ALERT_AI_TRANSPORT','disabled')
    monkeypatch.setattr(smartbi_api,'settings',lambda p:dict(config))
    monkeypatch.setattr(period_runtime,'settings',lambda p:dict(config))
    monkeypatch.setattr(smartbi_api,'read',lambda c,w=None:period_analysis(raw,w) if w else pytest.fail('period test must bind dates'))
    async def full(*args): return None
    async def authority(*args):return 33
    monkeypatch.setattr(smartbi_api,'cost_authority',full)
    monkeypatch.setattr(period_runtime,'worker_authority',authority)
    monkeypatch.setattr(period_runtime,'ensure_worker',lambda c:None)
    app=FastAPI();app.add_middleware(JWTAuthMiddleware,jwt_secret=key,enabled=True)
    app.include_router(smartbi_api.router,prefix='/api/smartbi/blacklake')
    claims={'factoryId':config['tenant_id'],'userId':33,'role':'factory_super_admin','exp':int(time.time())+120}
    headers={'Authorization':'Bearer '+jwt.encode(claims,key,algorithm='HS256')}
    with TestClient(app) as client:yield client,headers,raw,config,key


def test_facts_are_immediate_and_diagnosis_persists_and_reuses_exact_version(period_client):
    client,headers,raw,config,key=period_client
    path='/api/smartbi/blacklake';body={'start_date':'2026-11-03','end_date':'2026-11-03','kind':'day','basis':'completed'}
    facts=client.get(path+'/analysis',params=body,headers=headers)
    assert facts.status_code==200 and facts.json()['data']['sectionInsights']==[]
    assert facts.json()['data']['cost']['projects'][0]['unitIdentifiedDirectCost']==7
    request={**body,'expected_fingerprint':facts.json()['data']['periodMetadata']['fingerprint']}
    assert client.post(path+'/period-reports',json={**request,'expected_fingerprint':'f'*64},headers=headers).status_code==409
    submitted=client.post(path+'/period-reports',json=request,headers=headers).json()['data']
    assert submitted['status']=='queued' and 'facts' not in submitted and 'leaseToken' not in submitted
    assert asyncio.run(period_runtime.run_one(config)) is True
    saved=client.get(path+'/period-reports/'+submitted['id'],headers=headers).json()['data']
    assert saved['status']=='queued' and saved['result']['sectionInsights']
    assert saved['result']['ai']['analysisStage']['phase']=='retrying'  # Disabled model cannot finish full verification.
    again=client.post(path+'/period-reports',json=request,headers=headers).json()['data']
    assert again['id']==saved['id'] and again['fromCache'] is True
    raw['stock_order'][0]['orderDetails'][0]['changedCostAmountSigned']=-60
    newfacts=client.get(path+'/analysis',params=body,headers=headers).json()['data']
    new=client.post(path+'/period-reports',json={**body,'expected_fingerprint':newfacts['periodMetadata']['fingerprint']},headers=headers).json()['data']
    assert new['id']!=saved['id'] and new['revision']==2
    assert client.get(path+'/period-reports/'+saved['id'],headers=headers).json()['data']['result']==saved['result']


def test_period_endpoints_authorize_before_read_and_source_scope_is_not_reusable(period_client,monkeypatch):
    client,headers,raw,config,key=period_client;path='/api/smartbi/blacklake'
    body={'start_date':'2026-11-03','end_date':'2026-11-03'}
    assert client.post(path+'/period-reports',json=body).status_code==401
    foreign=jwt.encode({'factoryId':'OTHER','userId':33,'role':'factory_super_admin','exp':int(time.time())+120},key,algorithm='HS256')
    assert client.post(path+'/period-reports',json=body,headers={'Authorization':'Bearer '+foreign}).status_code==403
    job=client.post(path+'/period-reports',json=body,headers=headers).json()['data']
    config['source_factory_id']='SOURCE-B'
    assert client.get(path+'/period-reports/'+job['id'],headers=headers).status_code==404
    assert client.get(path+'/period-reports',headers=headers).json()['data']==[]
    from fastapi import HTTPException
    async def deny(*args):raise HTTPException(403,'denied')
    monkeypatch.setattr(smartbi_api,'cost_authority',deny)
    monkeypatch.setattr(smartbi_api,'read',lambda *args:pytest.fail('must authorize first'))
    assert client.post(path+'/period-reports',json=body,headers=headers).status_code==403
    assert client.get(path+'/period-reports',headers=headers).status_code==403


def test_invalid_range_rejected_and_old_scope_cannot_be_used_for_followup(period_client):
    client,headers,*_=period_client;path='/api/smartbi/blacklake'
    assert client.post(path+'/period-reports',json={'start_date':'2026-11-04','end_date':'2026-11-03'},headers=headers).status_code==422
    assert client.get(path+'/analysis?start_date=2026-11-03',headers=headers).status_code==422
    assert client.post(path+'/period-reports',json={'start_date':'9999-12-31','end_date':'9999-12-31'},headers=headers).status_code==422
    body={'section_id':'material','question':'怎么算？','run_id':'run-period','period':{'start_date':'2026-11-03','end_date':'2026-11-03'}}
    assert client.post(path+'/sections/followup',json=body,headers=headers).status_code==409


def test_explicit_profile_upgrade_preserves_old_ready_and_reuses_new_revision(period_client,monkeypatch):
    client,headers,raw,config,*_=period_client
    path='/api/smartbi/blacklake/period-reports'
    body={'start_date':'2026-11-03','end_date':'2026-11-03'}
    old=client.post(path,json=body,headers=headers).json()['data']
    db=period_runtime.store();scope=period_runtime.scope(config)
    lease=db.claim(scope,old['id'])
    old_result=asyncio.run(period_runtime.compile_report(lease,[],33))
    db.complete(scope,old['id'],lease['leaseToken'],old_result)  # Existing pre-policy rules-only report.
    old_ready=db.get(scope,old['id'])
    monkeypatch.setenv('SMARTBI_LOCAL_ANALYST_ENABLED','1')
    upgraded=client.post(path,json=body,headers=headers).json()['data']
    assert upgraded['id']!=old['id'] and upgraded['revision']==old['revision']+1
    same=client.post(path,json=body,headers=headers).json()['data']
    assert same['id']==upgraded['id']
    assert asyncio.run(period_runtime.run_one(config))
    ready=client.post(path,json=body,headers=headers).json()['data']
    assert ready['id']==upgraded['id'] and ready['status']=='queued'
    assert period_runtime.store().get(period_runtime.scope(config),old['id'])==old_ready


def test_history_summaries_do_not_download_every_analysis_and_full_report_stays_available(period_client):
    client,headers,raw,config,*_=period_client
    created=client.post('/api/smartbi/blacklake/period-reports',headers=headers,json={'start_date':'2026-11-03','end_date':'2026-11-03'}).json()['data']
    assert asyncio.run(period_runtime.run_one(config))
    full=client.get('/api/smartbi/blacklake/period-reports',headers=headers).json()['data'][0]
    compact=client.get('/api/smartbi/blacklake/period-reports?summaries_only=true',headers=headers).json()['data'][0]
    assert compact['id']==full['id']==created['id'] and compact['summaryOnly'] is True
    assert compact['result']['summary']==full['result']['summary']
    assert compact['result']['observations']==full['result']['observations']
    assert compact['result']['sectionInsights']==[] and full['result']['sectionInsights']
    reread=client.get('/api/smartbi/blacklake/period-reports/'+created['id'],headers=headers).json()['data']
    assert reread['result']==full['result']
    assert client.get('/api/smartbi/blacklake/period-reports?summaries_only=true').status_code==401


def test_report_distinguishes_new_inventory_from_older_production_clock(source):
    raw=dated(source)
    metadata={**META,'finished_at':'2026-11-04T10:00:00Z','stock_finished_at':'2026-11-05T07:38:00Z'}
    data=period_analysis(raw,parse_window('2026-11-05','2026-11-05',kind='day'),metadata)
    result=asyncio.run(period_runtime.compile_report({'id':'clock','period':data['periodMetadata'],'fingerprint':data['periodMetadata']['fingerprint'],'facts':data},[],33))
    observations=' '.join(result['observations'])
    assert '生产与工单成本资料采集：11-04 18:00' in observations
    assert '库存余额采集：11-05 15:38' in observations
    assert '分析使用的数据截至' not in observations


def test_empty_period_profile_never_requeues_forever(period_client,monkeypatch):
    client,headers,raw,config,*_=period_client
    monkeypatch.setenv('SMARTBI_LOCAL_ANALYST_ENABLED','1')
    body={'start_date':'2025-01-01','end_date':'2025-01-01'}
    job=client.post('/api/smartbi/blacklake/period-reports',json=body,headers=headers).json()['data']
    assert asyncio.run(period_runtime.run_one(config))
    again=client.post('/api/smartbi/blacklake/period-reports',json=body,headers=headers).json()['data']
    assert again['id']==job['id'] and again['result']['ai']['status']=='not_applicable'


def test_worker_rechecks_authority_and_no_data_never_calls_model(period_client,monkeypatch):
    client,headers,raw,config,*_=period_client;path='/api/smartbi/blacklake'
    job=client.post(path+'/period-reports',json={'start_date':'2026-11-03','end_date':'2026-11-03'},headers=headers).json()['data']
    async def deny(*args):raise RuntimeError('sensitive exception must not persist')
    monkeypatch.setattr(period_runtime,'worker_authority',deny)
    asyncio.run(period_runtime.run_one(config))
    state=period_runtime.store().get(period_runtime.scope(config),job['id'])
    assert 'sensitive' not in str(state) and state['result'] is None
    empty=period_analysis(raw,parse_window('2025-01-01','2025-01-01'))
    result=asyncio.run(period_runtime.compile_report({'id':'empty','period':empty['periodMetadata'],'fingerprint':empty['periodMetadata']['fingerprint'],'facts':empty},[],33))
    assert result['ai']['status']=='not_applicable' and '不表示' in result['summary']


def test_history_is_context_with_no_double_counting_or_false_resolution(source):
    analysis=period_analysis(dated(source),parse_window('2026-11-01','2026-11-30',kind='month'))
    period=analysis['periodMetadata'];version=analysis['costAlerts']['ruleVersion']
    def previous(id,start,end):
        return {'id':id,'status':'ready','createdAt':'2026-11-05T00:00:00Z',
                'period':{**period,'startDate':start,'endDate':end},
                'result':{'rulesVersion':version,'sourceRunId':META['run_id'],'issueTypes':{'CURRENCY_UNVERIFIED':'币种未核实'}}}
    old=[previous('day','2026-11-03','2026-11-03'),previous('overlap','2026-11-01','2026-11-05')]
    context=period_runtime.previous_context(old,{'id':'current','period':period},analysis)
    assert len(context['usedReports'])==1  # Overlapping child windows are never both counted.
    assert context['recurringIssues']==['来源金额币种尚未核实']
    assert '不相加日报单价' in context['message']


def test_actual_reader_binds_alert_and_ai_cache_to_period_not_only_source_run(source,monkeypatch):
    from collections import Counter
    from blacklake import smartbi_store
    from blacklake.full_archive import digest
    from smartbi.services.alert_local_ai import fingerprint
    raw=dated(source)
    # Real stored stock orders have external IDs; traceability now validates them.
    for i,order in enumerate(raw['stock_order']):order['id']=900+i
    records=[(resource,str(i),digest(payload),payload) for resource,rows in raw.items() for i,payload in enumerate(rows)]
    metadata={**META,'source_totals':dict(Counter(r[0] for r in records))}
    class Cursor:
        query=''
        def __enter__(self):return self
        def __exit__(self,*args):pass
        def execute(self,query,*args):self.query=query
        def fetchone(self):return (False,False) if 'pg_roles' in self.query else (metadata,)
        def fetchall(self):return records
    class Connection:
        def __enter__(self):return self
        def __exit__(self,*args):pass
        def set_session(self,**kwargs):assert kwargs=={'readonly':True}
        def cursor(self):return Cursor()
        def close(self):pass
    monkeypatch.setattr(smartbi_store.psycopg2,'connect',lambda *a,**kw:Connection())
    monkeypatch.setattr(smartbi_store,'validate',lambda *a:None)
    monkeypatch.setattr(smartbi_store,'enrich',lambda *a:{'comparisons':[]})
    config={'tenant_id':'T','source_factory_id':'S','run_id':META['run_id'],
            'dsn':'host=127.0.0.1 port=15432 dbname=smartbi_testenv user=blacklake_bi_reader'}
    first=smartbi_store.read(config,parse_window('2026-11-03','2026-11-03'))
    second=smartbi_store.read(config,parse_window('2026-11-02','2026-11-03'))
    assert first['production']['projectCount']==second['production']['projectCount']==1
    assert first['costAlerts']['runId']==first['runId']
    assert second['costAlerts']['runId']==second['runId']
    assert first['runId']!=second['runId']
    assert {a['id'] for a in first['costAlerts']['items']}.isdisjoint(a['id'] for a in second['costAlerts']['items'])
    assert fingerprint(first['costAlerts'])!=fingerprint(second['costAlerts'])


def test_report_rejects_durable_fact_label_or_source_tampering_before_ai(source,monkeypatch):
    value=period_analysis(dated(source),parse_window('2026-11-03','2026-11-03'))
    job={'id':'job','period':value['periodMetadata'],'fingerprint':'f'*64,'facts':value}
    from smartbi.services import alert_local_ai
    async def forbidden(*args):pytest.fail('must validate durable facts before model access')
    monkeypatch.setattr(alert_local_ai,'enrich',forbidden)
    with pytest.raises(ValueError,match='BINDING'):
        asyncio.run(period_runtime.compile_report(job,[],33))
    job['fingerprint']=value['periodMetadata']['fingerprint']
    job['period']={**job['period'],'basis':'created'}
    with pytest.raises(ValueError,match='BINDING'):
        asyncio.run(period_runtime.compile_report(job,[],33))


def test_late_older_revision_never_uses_newer_revision_as_its_history(source):
    analysis=period_analysis(dated(source),parse_window('2026-11-03','2026-11-03'))
    period=analysis['periodMetadata'];version=analysis['costAlerts']['ruleVersion']
    newer={'id':'newer','revision':2,'status':'ready','createdAt':'2026-11-05T02:00:00Z','period':period,
           'result':{'rulesVersion':version,'issueTypes':{'OTHER':'later report should not affect old facts'}}}
    older={'id':'older','revision':1,'createdAt':'2026-11-05T01:00:00Z','period':period}
    assert period_runtime.previous_context([newer],older,analysis)['usedReports']==[]


def test_missing_movement_dates_and_mislabeled_periods_are_not_silently_counted(source,period_client):
    raw=dated(source);raw['stock_order'][0]['bizAt']='invalid-date'
    value=period_analysis(raw,parse_window('2026-11-03','2026-11-03'))
    assert value['periodMetadata']['unassignedMovementOrders']==1
    assert value['cost']['projects'][0]['materialCost']==50  # Cost lifecycle retains the linked line.
    assert any('业务日期' in line for line in value['limitations'])
    client,headers,*_=period_client
    for kind in ('year','month','week','quarter'):
        response=client.post('/api/smartbi/blacklake/period-reports',headers=headers,
                             json={'start_date':'2026-11-03','end_date':'2026-11-03','kind':kind})
        assert response.status_code==422


def test_nightly_checkpoint_catches_long_outage_and_only_advances_after_success(period_client,monkeypatch):
    client,headers,raw,config,*_=period_client
    from smartbi.services import period_schedule
    db=period_runtime.store();key=period_runtime.scope(config)
    checkpoint=datetime(2026,9,1,tzinfo=timezone.utc)
    db.schedule_checkpoint(key,checkpoint)
    monkeypatch.setattr(period_runtime,'settings',lambda p:dict(config))
    seen=[]
    def due(now,lookback_days=2,preview=True):
        seen.append((now,lookback_days));return []
    monkeypatch.setattr(period_schedule,'due_periods',due)
    now=datetime(2026,11,10,tzinfo=timezone.utc)
    asyncio.run(period_runtime.tick(dict(config),now))
    assert seen[0][0]==datetime(2026,10,2,tzinfo=timezone.utc)
    assert db.schedule_checkpoint(key).startswith('2026-10-02')
    async def fail(*args):raise RuntimeError('safe failure in synthetic dependency')
    monkeypatch.setattr(period_runtime,'reconcile_windows',fail)
    with pytest.raises(RuntimeError):asyncio.run(period_runtime.tick(dict(config),now))
    assert db.schedule_checkpoint(key).startswith('2026-10-02')


def test_scheduler_reads_new_source_revision_and_revises_saved_windows_without_restart(period_client,monkeypatch):
    client,headers,raw,config,*_=period_client
    from smartbi.services import period_schedule
    db=period_runtime.store();key=period_runtime.scope(config)
    window=parse_window('2026-11-03','2026-11-03',kind='day')
    initial=period_analysis(raw,window)
    first=db.submit(key,window.wire(),initial['periodMetadata']['fingerprint'],initial)
    anchor=dict(config)
    monkeypatch.setattr(period_runtime,'settings',lambda p:dict(config))
    reads=[]
    def read_live(c,w):
        reads.append(c['run_id'])
        return period_analysis(raw,w,{**META,'run_id':c['run_id']})
    monkeypatch.setattr(period_runtime,'read',read_live)
    monkeypatch.setattr(period_schedule,'due_periods',lambda *a,**kw:[])
    now=datetime(2026,11,6,tzinfo=timezone.utc)
    asyncio.run(period_runtime.tick(anchor,now))
    config['run_id']='new-source-run'
    asyncio.run(period_runtime.tick(anchor,now))
    latest=db.windows(key)[0]
    assert latest['id']!=first['id'] and latest['revision']==2
    assert reads[-1]=='new-source-run'
    assert db.get(key,first['id'])['fingerprint']==first['fingerprint']
    config['source_factory_id']='OTHER-SOURCE'
    with pytest.raises(RuntimeError,match='IDENTITY_CHANGED'):
        asyncio.run(period_runtime.tick(anchor,now))


def test_operational_file_revision_requeues_once_without_changing_baseline_config(period_client,monkeypatch,tmp_path):
    import json
    from smartbi.services import period_schedule
    client,headers,raw,config,*_=period_client
    db=period_runtime.store();key=period_runtime.scope(config)
    window=parse_window('2026-11-03','2026-11-03',kind='day')
    initial=period_analysis(raw,window)
    first=db.submit(key,window.wire(),initial['periodMetadata']['fingerprint'],initial)
    path=tmp_path/'operational.json'
    observation={'tenant_id':config['tenant_id'],'source_factory_id':config['source_factory_id'],'run_id':config['run_id'],'probeId':'one'}
    path.write_text(json.dumps(observation),encoding='utf8')
    monkeypatch.setenv('BLACKLAKE_OPERATIONAL_SOURCE_FILE',str(path))
    monkeypatch.delenv('BLACKLAKE_OPERATIONAL_BOM_FILE',raising=False)
    monkeypatch.setattr(period_runtime,'settings',lambda p:dict(config))
    monkeypatch.setattr(period_schedule,'due_periods',lambda *a,**kw:[])
    def read_current(c,w):
        current=json.loads(path.read_text(encoding='utf8'))
        return period_analysis(raw,w,{**META,'run_id':current['probeId']})
    monkeypatch.setattr(period_runtime,'read',read_current)
    now=datetime(2026,11,6,tzinfo=timezone.utc)
    asyncio.run(period_runtime.tick(dict(config),now))
    observation['probeId']='two';path.write_text(json.dumps(observation),encoding='utf8')
    asyncio.run(period_runtime.tick(dict(config),now))
    latest=db.windows(key)[0]
    assert latest['revision']==3 and latest['fingerprint']!=first['fingerprint']
    asyncio.run(period_runtime.tick(dict(config),now))
    assert db.windows(key)[0]['id']==latest['id']


def test_transient_worker_failure_respects_backoff_instead_of_burning_attempts(period_client,monkeypatch):
    client,headers,raw,config,*_=period_client
    from smartbi.services import period_reports
    current=datetime(2026,11,5,tzinfo=timezone.utc)
    monkeypatch.setattr(period_reports,'_utc_now',lambda:current)
    job=client.post('/api/smartbi/blacklake/period-reports',headers=headers,
                    json={'start_date':'2026-11-03','end_date':'2026-11-03'}).json()['data']
    original=period_runtime.compile_report
    async def unavailable(*args):raise RuntimeError('analysis dependency unavailable')
    monkeypatch.setattr(period_runtime,'compile_report',unavailable)
    assert asyncio.run(period_runtime.run_one(config)) is True
    assert asyncio.run(period_runtime.run_one(config)) is False
    waiting=period_runtime.store().get(period_runtime.scope(config),job['id'])
    assert waiting['status']=='queued' and waiting['nextAttemptAt']
    from datetime import timedelta
    current += timedelta(seconds=61)
    monkeypatch.setattr(period_runtime,'compile_report',original)
    assert asyncio.run(period_runtime.run_one(config)) is True
    assert period_runtime.store().get(period_runtime.scope(config),job['id'])['status']=='queued'


def test_worker_and_reconciliation_authorize_before_loading_saved_cost_facts(period_client,monkeypatch):
    client,headers,raw,config,*_=period_client
    from unittest.mock import Mock
    async def deny(*args):raise RuntimeError('permission unavailable')
    monkeypatch.setattr(period_runtime,'worker_authority',deny)
    spy=Mock(side_effect=AssertionError('must not claim/load cost data without authority'))
    monkeypatch.setattr(period_runtime,'store',spy)
    assert asyncio.run(period_runtime.run_one(config)) is False
    spy.assert_not_called()
    class SafeMarkerStore:
        def schedule_seen(self,*a):return False
        def windows(self,*a):pytest.fail('must not load report history before authority')
    with pytest.raises(RuntimeError,match='AUTHORITY'):
        asyncio.run(period_runtime.reconcile_windows(config,SafeMarkerStore(),period_runtime.scope(config)))


def test_full_fact_queue_resumes_without_regenerating_overview_and_only_then_finishes(period_client,monkeypatch):
    from smartbi.services import period_reports,analysis_supplement
    client,headers,_,config,*_=period_client
    now=datetime(2026,11,5,tzinfo=timezone.utc)
    monkeypatch.setattr(period_reports,'_utc_now',lambda:now)
    created=client.post('/api/smartbi/blacklake/period-reports',headers=headers,
        json={'start_date':'2026-11-03','end_date':'2026-11-03'}).json()['data']
    original=period_runtime.compile_report;overviews=[];batches=[]
    async def overview(*args):
        overviews.append(1);result=await original(*args)
        result['ai'].update(status='partial',validation={'allSections':True})
        return result
    def attached(job,*args):
        from copy import deepcopy
        value=deepcopy(job)
        value['result']['ai']['analysisFactCoverage']={'total':12,'reviewed':8,'remaining':4,'phase':'retrying'}
        return value
    async def batch(job,facts,scope,path,actor,**options):
        options['guard']();assert options['exhaustive'] and options['max_batches']==1
        assert period_runtime.store().get(scope,job['id'])['result']  # Overview visible before inference.
        batches.append(1)
        if len(batches)==1:raise analysis_supplement.SupplementRetry('ANALYST_OWNER')
        value=attached(job);value['result']['ai']['analysisFactCoverage'].update(reviewed=12,remaining=0,phase='ready')
        return value
    monkeypatch.setattr(period_runtime,'compile_report',overview)
    monkeypatch.setattr(analysis_supplement,'complete',batch)
    monkeypatch.setattr(analysis_supplement,'attach',attached)
    assert asyncio.run(period_runtime.run_one(config))
    db=period_runtime.store();scope=period_runtime.scope(config)
    waiting=db.get(scope,created['id'])
    assert waiting['status']=='queued' and waiting['result']['ai']['analysisFactCoverage']['remaining']==4
    assert waiting['nextAttemptAt']
    with db._connect() as connection:
        assert connection.execute('SELECT attempts FROM period_reports WHERE id=?',(created['id'],)).fetchone()[0]==0
    assert not asyncio.run(period_runtime.run_one(config))
    now+=__import__('datetime').timedelta(seconds=61)
    assert asyncio.run(period_runtime.run_one(config))
    assert len(overviews)==1 and len(batches)==2
    finished=db.get(scope,created['id'])
    assert finished['status']=='ready' and finished['result']['ai']['analysisFactCoverage']['remaining']==0


@pytest.mark.asyncio
async def test_background_pump_recovers_configuration_error_without_a_browser(monkeypatch):
    from types import SimpleNamespace
    turns=[];woken=[]
    def current(config):
        if not turns:raise ValueError('temporary config problem')
        return config
    async def sleep(seconds):
        turns.append(seconds)
        if len(turns)==2:raise asyncio.CancelledError
    monkeypatch.setattr(period_runtime,'live_config',current)
    monkeypatch.setattr(period_runtime,'store',lambda:SimpleNamespace(has_due=lambda scope:True))
    monkeypatch.setattr(period_runtime,'ensure_worker',lambda config:woken.append(config))
    monkeypatch.setattr(period_runtime.asyncio,'sleep',sleep)
    config={'tenant_id':'T','source_factory_id':'F'}
    with pytest.raises(asyncio.CancelledError):await period_runtime.worker_loop(config)
    assert woken==[config] and turns==[5,5]


def test_raw_completed_cohort_completion_rate_is_not_a_factory_completion_rate(period_client):
    client,headers,*_=period_client
    response=client.get('/api/smartbi/blacklake/analysis',headers=headers,
                        params={'start_date':'2026-11-03','end_date':'2026-11-03'})
    production=response.json()['data']['production']
    assert production['completionRate'] is None
    assert production['completionRateApplicable'] is False


@pytest.mark.timeout(180)
def test_many_force_revisions_do_not_hide_the_previous_period_ready_report(tmp_path):
    from smartbi.services.period_reports import ReportStore
    db=ReportStore(tmp_path/'many-revisions.sqlite3');scope={'tenant_id':'T','source_factory_id':'S','connector':'blacklake'}
    prior=db.submit(scope,parse_window('2026-11-02','2026-11-02').wire(),'old',{})
    leased=db.claim(scope,prior['id']);db.complete(scope,prior['id'],leased['leaseToken'],{'summary':'prior'})
    period=parse_window('2026-11-03','2026-11-03').wire()
    for _ in range(105):current=db.submit(scope,period,'same',{},force=True)
    history=db.history(scope,current)
    assert [h['id'] for h in history]==[prior['id']]


def test_rate_limited_local_ai_keeps_saved_rules_pending_and_resumes_automatically(period_client,monkeypatch):
    client,headers,raw,config,*_=period_client
    from smartbi.services import period_reports
    now=datetime(2026,11,5,tzinfo=timezone.utc)
    monkeypatch.setattr(period_reports,'_utc_now',lambda:now)
    job=client.post('/api/smartbi/blacklake/period-reports',headers=headers,
                    json={'start_date':'2026-11-03','end_date':'2026-11-03'}).json()['data']
    original=period_runtime.compile_report
    async def limited(*args):
        result=await original(*args)
        result['ai']={'status':'rate_limited','message':'本地AI配额暂不可用，规则已保存'}
        return result
    monkeypatch.setattr(period_runtime,'compile_report',limited)
    assert asyncio.run(period_runtime.run_one(config)) is True
    saved=period_runtime.store().get(period_runtime.scope(config),job['id'])
    assert saved['status']=='queued' and len(saved['result']['sectionInsights'])==7
    assert saved['finishedAt'] is None and saved['nextAttemptAt']
    assert asyncio.run(period_runtime.run_one(config)) is False
    from datetime import timedelta
    now += timedelta(seconds=3601)
    monkeypatch.setattr(period_runtime,'compile_report',original)
    assert asyncio.run(period_runtime.run_one(config)) is True
    assert period_runtime.store().get(period_runtime.scope(config),job['id'])['status']=='queued'
