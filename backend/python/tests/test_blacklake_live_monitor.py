import copy
import json
import pytest
from blacklake import live_monitor as live
from blacklake.stock_reconciliation import reconcile


def balance(q=-5,unit='个'):
    return {'productId':1,'warehouseId':2,'productName':'材料','productCode':'P1','warehouseName':'线边仓','qtyInWarehouse':str(q),'unitName':unit}


def order(q=-5,unit='个',warehouse=2,header=9,status=1,direction=20,identity=1):
    return {'id':identity,'orderNo':'OUT1','orderType':direction,'orderStatus':status,'bizAt':'2026-11-05 10:00:00','warehouse':{'id':header},
            'orderDetails':[{'id':identity,'productId':1,'detailWarehouseId':warehouse,'changeQty':q,'unitName':unit}]}


def test_detail_warehouse_wins_and_equal_net_does_not_prove_physical_shortage():
    r=reconcile([balance()],[order()],'2026-11-05T04:00:00Z')[0]
    assert r['inbound']==0 and r['outbound']==5
    assert r['difference'] is None and r['expectedClosing'] is None and r['openingQuantity'] is None
    assert r['status']=='needs_review' and '实物' in r['conclusion']
    assert r['movements'][0]['warehouseConflict'] is True
    assert '单头仓库与明细' in r['possibleReasons'][0]


def test_other_warehouse_is_not_counted_and_suggests_transfer_without_asserting_missing_document():
    r=reconcile([balance()],[order(),order(20,warehouse=9,header=9,direction=10,identity=2)],'2026-11-05T04:00:00Z')[0]
    assert len(r['movements'])==1 and r['inbound']==0
    assert any('可能漏调拨' in s and '尚未证实' in s for s in r['possibleReasons'])


@pytest.mark.parametrize('change,reason',[
    ({'unit':'箱'},'单位'),({'q':None},'未返回'),({'q':5},'符号'),({'warehouse':None,'header':None},'仓库')])
def test_incomplete_or_wrong_unit_rows_never_sum(change,reason):
    r=reconcile([balance()],[order(**change)],'2026-11-05T04:00:00Z')[0]
    assert r['inbound'] is None and r['outbound'] is None
    assert r['movements'][0]['counted'] is False and reason in r['movements'][0]['reason']


def test_unapproved_rows_do_not_reduce_current_balance():
    r=reconcile([balance()],[order(),order(-100,status=0,identity=2)],'2026-11-05T04:00:00Z')[0]
    assert r['outbound']==5 and not r['movements'][1]['counted']


def test_duplicate_detail_rejected():
    with pytest.raises(ValueError,match='DUPLICATE'):reconcile([balance()],[order(),order()],'2026-11-05T04:00:00Z')


def test_recovered_balance_remains_traceable_without_claiming_action_was_taken():
    r=reconcile([balance(2)],[],'2026-11-05T04:00:00Z',tracked=[balance()])[0]
    assert r['status']=='resolved' and r['quantity']==2 and r['openingQuantity'] is None


def test_original_negative_pair_not_returned_stays_unknown_and_visible():
    r=reconcile([],[],'2026-11-05T04:00:00Z',tracked=[balance()])[0]
    assert r['quantity'] is None and r['status']=='needs_review' and '未知' in r['conclusion']


def test_known_unit_precision_is_used_instead_of_requesting_already_available_settings():
    r=reconcile([balance(-0.6)],[order(-0.6)],'2026-11-05T04:00:00Z',units=[{'name':'个','decimalLength':6}])[0]
    assert '允许保留 6 位小数' in r['conclusion']
    assert not any('需核对允许小数的设置' in reason for reason in r['possibleReasons'])
    assert r['status']=='needs_review' and r['difference'] is None


@pytest.fixture
def cfg(tmp_path):
    v={'schema':live.SCHEMA,'tenant_id':'BLACKLAKE_POC_TEST','source_factory_id':'factory','baselineRunId':'baseline',
       'db':str(tmp_path/'monitor.sqlite3'),'intervalSeconds':60,'days':7}
    live.initialize(v)
    return v


def payload():
    return {'data':{'stock':[balance()],'project':[],'stock_order':[order()]},'counts':{'stock':1,'project':0},'controls':[],
            'parentHashes':{},'materialAt':'2026-11-05T00:00:00Z','referenceAt':'2026-11-05T00:00:00Z','pairsNotReturned':0}


def test_unchanged_minute_retains_both_receipts_and_deduplicates_content(cfg):
    def collect(v,previous):return payload(),3
    assert live.cycle(cfg,collect) and live.cycle(cfg,collect)
    state=live.status(cfg)
    assert state['cycles']==2 and state['successfulCycles']==2 and state['changedCycles']==1
    assert state['history'][0]['counts']=={'stock':1,'project':0}
    c=live.connect(cfg['db']);assert c.execute('SELECT count(*) FROM content').fetchone()[0]==1;c.close()


def test_fresh_schema_initialization_is_serialized_across_workers(tmp_path):
    from concurrent.futures import ThreadPoolExecutor
    value={'tenant_id':'BLACKLAKE_POC_TEST','source_factory_id':'factory','baselineRunId':'baseline','db':str(tmp_path/'parallel.sqlite3')}
    with ThreadPoolExecutor(max_workers=4) as pool:list(pool.map(lambda _:live.initialize(value),range(8)))
    assert live.status(value)['cycles']==0


def test_failed_fetch_preserves_last_success_and_visible_error(cfg):
    assert live.cycle(cfg,lambda v,p:(payload(),3))
    before=live.latest(cfg)
    def fail(v,p):raise ValueError('LIVE_SOURCE_SCOPE_MISMATCH')
    assert not live.cycle(cfg,fail)
    assert live.latest(cfg)==before
    status=live.status(cfg);assert status['successfulCycles']==1 and status['history'][0]['status']=='failed'
    assert 'SCOPE_MISMATCH' in status['error']


def test_source_scope_and_storage_tampering_rejected(cfg):
    assert live.cycle(cfg,lambda v,p:(payload(),3))
    with pytest.raises(ValueError,match='SCOPE'):live.latest({**cfg,'source_factory_id':'other'})
    c=live.connect(cfg['db'])
    with c:c.execute('UPDATE content SET payload=?',(json.dumps({**payload(),'counts':{'stock':999}}),))
    c.close()
    with pytest.raises(ValueError,match='INTEGRITY'):live.latest(cfg)


def test_second_process_cannot_poll_while_lease_is_held(cfg):
    c=live.connect(cfg['db'])
    with c:c.execute("UPDATE monitor SET owner='first',lease_until='2999-01-01T00:00:00Z'")
    c.close()
    assert live.cycle(cfg,lambda *_:pytest.fail('collector must not start')) is False


def test_completed_campaign_will_not_start_new_source_queries(cfg):
    c=live.connect(cfg['db'])
    with c:c.execute("UPDATE monitor SET ends='2000-01-01T00:00:00Z'")
    c.close()
    assert live.cycle(cfg,lambda *_:pytest.fail('campaign ended')) is False
    assert live.status(cfg)['status']=='completed' and live.status(cfg)['enabled'] is False


def test_active_initial_collection_does_not_claim_stale(cfg):
    c=live.connect(cfg['db'])
    with c:c.execute("UPDATE monitor SET owner='first',lease_until='2999-01-01T00:00:00Z'")
    c.close()
    assert live.status(cfg)['status']=='running' and live.status(cfg)['error'] is None


def test_failed_requests_are_counted_and_error_does_not_expose_auth_material(cfg):
    def fail(v,p):raise live.CollectionFailure('UPSTREAM_HTTP_ERROR',19)
    assert live.cycle(cfg,fail) is False
    assert live.status(cfg)['history'][0]['requests']==19


def test_history_pagination_keeps_global_totals(cfg):
    c=live.connect(cfg['db'])
    with c:
        for i in range(102):c.execute("INSERT INTO cycle(id,started,status) VALUES(?,?,'failed')",(str(i),f'2026-11-05T00:{i//60:02}:{i%60:02}+00:00'))
    c.close()
    a=live.status(cfg);b=live.status(cfg,100)
    assert len(a['history'])==100 and len(b['history'])==2
    assert a['historyHasMore'] and not b['historyHasMore']
    assert a['cycles']==b['cycles']==102 and a['lastAttemptAt']==b['lastAttemptAt']


@pytest.mark.asyncio
async def test_background_scope_mismatch_never_reads_foreign_live_or_report_store(cfg,monkeypatch):
    from blacklake.live_api import analysis_tick
    from blacklake import period_runtime
    monkeypatch.setenv('BLACKLAKE_LIVE_CONFIG','synthetic')
    monkeypatch.setattr(live,'config',lambda _:cfg)
    monkeypatch.setattr(live,'latest',lambda *_:pytest.fail('must reject before reading foreign state'))
    monkeypatch.setattr(period_runtime,'store',lambda:pytest.fail('must not access report storage'))
    with pytest.raises(ValueError,match='SCOPE'):
        await analysis_tick({'tenant_id':'OTHER','source_factory_id':'factory','run_id':'baseline'})


def test_historical_snapshot_corruption_is_rejected_even_if_latest_is_valid(cfg,tmp_path,monkeypatch):
    cfg['supplement']=str(tmp_path/'supplement.json')
    (tmp_path/'supplement.json').write_text(json.dumps({'stock':[balance()]}),encoding='utf8')
    assert live.cycle(cfg,lambda v,p:(payload(),3))
    c=live.connect(cfg['db'])
    with c:c.execute("UPDATE cycle SET finished='2026-11-04T15:50:00+00:00'")
    c.close()
    updated=payload();updated['data']['stock']=[balance(-8)]
    assert live.cycle(cfg,lambda v,p:(updated,3))
    c=live.connect(cfg['db'])
    with c:
        oldest=c.execute('SELECT hash FROM cycle ORDER BY finished LIMIT 1').fetchone()[0]
        c.execute('UPDATE content SET payload=? WHERE hash=?',(json.dumps(updated),oldest))
    c.close()
    monkeypatch.setattr(live,'config',lambda _:cfg)
    with pytest.raises(ValueError,match='HISTORY_CONTENT_INTEGRITY'):
        live.load({}, {'stock_snapshots':[]},{'tenant_id':cfg['tenant_id'],'source_factory_id':'factory','run_id':'baseline'},'synthetic')


def test_snapshots_preserve_beijing_day_boundaries_and_pinned_cycle(cfg,tmp_path,monkeypatch):
    cfg['supplement']=str(tmp_path/'supplement.json');(tmp_path/'supplement.json').write_text(json.dumps({'stock':[]}),encoding='utf8')
    assert live.cycle(cfg,lambda v,p:(payload(),3))
    c=live.connect(cfg['db'])
    with c:c.execute("UPDATE cycle SET finished='2026-11-04T15:50:00+00:00'")
    first=c.execute('SELECT id FROM cycle').fetchone()[0];c.close()
    second=payload();second['data']['stock']=[balance(-6)]
    assert live.cycle(cfg,lambda v,p:(second,3))
    c=live.connect(cfg['db'])
    with c:c.execute("UPDATE cycle SET finished='2026-11-04T23:50:00+00:00' WHERE id<>?",(first,))
    c.close();monkeypatch.setattr(live,'config',lambda _:cfg)
    configured={'tenant_id':cfg['tenant_id'],'source_factory_id':'factory','run_id':'baseline'}
    _,meta=live.load({}, {'stock_snapshots':[]},configured,'synthetic')
    assert len(meta['stock_snapshots'])==2
    source,meta=live.load({}, {'stock_snapshots':[]},configured,'synthetic',first)
    assert len(meta['stock_snapshots'])==1 and source['stock'][0]['qtyInWarehouse']=='-5'


@pytest.mark.asyncio
async def test_live_lane_calls_real_period_scheduler_only_at_night_when_campaign_active(cfg,monkeypatch):
    from blacklake.live_api import nightly_tick
    from blacklake import period_runtime
    from unittest.mock import AsyncMock
    monkeypatch.setenv('BLACKLAKE_LIVE_CONFIG','synthetic');monkeypatch.setenv('BLACKLAKE_PERIOD_SCHEDULER_ENABLED','0')
    monkeypatch.setattr(live,'config',lambda _:cfg)
    tick=AsyncMock();monkeypatch.setattr(period_runtime,'tick',tick)
    assert period_runtime.schedule_status()['enabled'] is True
    assert period_runtime.schedule_status()['executionLane']=='live_nightly'
    configured={'tenant_id':cfg['tenant_id'],'source_factory_id':'factory','run_id':'baseline'}
    await nightly_tick(configured,live.instant('2026-11-05T03:55:00Z'));tick.assert_not_called()
    await nightly_tick(configured,live.instant('2026-11-05T15:55:00Z'));tick.assert_awaited_once()
    monkeypatch.setattr(live,'status',lambda _:{'enabled':False})
    await nightly_tick(configured,live.instant('2026-11-12T15:55:00Z'));assert tick.await_count==1


@pytest.mark.asyncio
async def test_terminal_unchanged_analysis_retries_are_bounded_and_do_not_read_cost_again(cfg,monkeypatch):
    from blacklake.live_api import analysis_tick
    from blacklake import period_runtime,smartbi_store
    from unittest.mock import AsyncMock
    assert live.cycle(cfg,lambda v,p:(payload(),3))
    p=live.latest(cfg)
    c=live.connect(cfg['db'])
    info={'reportId':'failed-job','status':'failed','retries':2,'retryAt':'2000-01-01T00:00:00Z','analyzedDataHash':live.digest(p['data'])}
    with c:c.execute('UPDATE monitor SET analysis=?',(json.dumps(info),))
    c.close()
    class Store:
        def get(self,*_):return {'status':'failed'}
        def submit(self,*_):pytest.fail('unchanged failed data must not create unlimited revisions')
    monkeypatch.setenv('BLACKLAKE_LIVE_CONFIG','synthetic');monkeypatch.setattr(live,'config',lambda _:cfg)
    monkeypatch.setattr(period_runtime,'store',Store)
    monkeypatch.setattr(smartbi_store,'read',lambda *_:pytest.fail('must not read cost'))
    for _ in range(3):await analysis_tick({'tenant_id':cfg['tenant_id'],'source_factory_id':'factory','run_id':'baseline'})
    assert '连续重试仍失败' in live.status(cfg)['analysis']['message']


@pytest.mark.asyncio
async def test_change_analysis_error_does_not_skip_nightly_lane(cfg,monkeypatch):
    from blacklake import live_api
    from unittest.mock import AsyncMock
    monkeypatch.setenv('BLACKLAKE_LIVE_CONFIG','synthetic');monkeypatch.setattr(live,'config',lambda _:cfg)
    monkeypatch.setattr(live_api,'analysis_tick',AsyncMock(side_effect=ValueError('invalid cached metadata')))
    night=AsyncMock();monkeypatch.setattr(live_api,'nightly_tick',night)
    await live_api.analysis_step({'tenant_id':cfg['tenant_id'],'source_factory_id':'factory','run_id':'baseline'})
    night.assert_awaited_once()


@pytest.mark.asyncio
async def test_nightly_failure_persists_across_source_success_until_night_retry_succeeds(cfg,monkeypatch):
    from blacklake import live_api,period_runtime
    from unittest.mock import AsyncMock
    monkeypatch.setenv('BLACKLAKE_LIVE_CONFIG','synthetic');monkeypatch.setenv('BLACKLAKE_PERIOD_SCHEDULER_ENABLED','0')
    monkeypatch.setattr(live,'config',lambda _:cfg)
    configured={'tenant_id':cfg['tenant_id'],'source_factory_id':'factory','run_id':'baseline'}
    tick=AsyncMock(side_effect=RuntimeError('no auth'));monkeypatch.setattr(period_runtime,'tick',tick)
    with pytest.raises(RuntimeError):await live_api.nightly_tick(configured,live.instant('2026-11-05T15:55:00Z'))
    assert live.cycle(cfg,lambda v,p:(payload(),3))
    assert '夜间' in live.status(cfg)['error'] and '夜间' in period_runtime.schedule_status()['message']
    tick.side_effect=None
    await live_api.nightly_tick(configured,live.instant('2026-11-05T15:56:00Z'))
    assert live.status(cfg)['nightly']['error'] is None
    c=live.connect(cfg['db'])
    with c:c.execute("UPDATE monitor SET ends='2000-01-01T00:00:00Z'")
    c.close();assert not period_runtime.schedule_status()['enabled']


def test_seed_reuses_validated_scope_chain_and_preserves_bom_clock(tmp_path):
    from tests.test_blacklake_operations_supplement import fixture
    from tests.test_blacklake_operational_source import observation
    from tests.test_blacklake_analytics import source as source_fixture
    raw_source=source_fixture.__wrapped__()
    _,_,_,s=fixture(raw_source)
    raw,_,_,o=observation(raw_source)
    o['data']['output'][0].pop('stockUnit',None)
    o['started_at']='2026-11-04T09:00:00Z';o['data']['project'][0]['creatorName']='person-to-minimize'
    o['observationFingerprint']=live.digest(o['data'])
    b={'complete':True,'tenantId':'TEST','sourceFactoryId':'FACTORY-A','baselineRunId':'baseline','parentProbeId':'current',
       'sourceBusinessWrites':0,'failures':[],'rows':[],'finishedAt':'2026-11-04T11:00:00Z'}
    v={'tenant_id':'TEST','source_factory_id':'FACTORY-A','baselineRunId':'baseline'}
    for key,value in [('observation',o),('bom',b),('supplement',s)]:
        v[key]=str(tmp_path/(key+'.json'));(tmp_path/(key+'.json')).write_text(json.dumps(value),encoding='utf8')
    seed=live.seeded(v,raw['project'])
    assert seed['referenceAt']==b['finishedAt'] and 'creatorName' not in seed['data']['project'][0]
    for target,field,value in [('supplement','source_factory_id','OTHER'),('bom','baselineRunId','OTHER'),('observation','baselineMutated',True)]:
        changed=copy.deepcopy({'observation':o,'bom':b,'supplement':s}[target]);changed[field]=value
        if target=='supplement':changed['fingerprint']=live.digest({k:x for k,x in changed.items() if k!='fingerprint'})
        Path=__import__('pathlib').Path
        Path(v[target]).write_text(json.dumps(changed),encoding='utf8')
        with pytest.raises(ValueError):live.seeded(v,raw['project'])
        Path(v[target]).write_text(json.dumps({'observation':o,'bom':b,'supplement':s}[target]),encoding='utf8')


@pytest.mark.asyncio
async def test_latest_submission_failure_cannot_label_previous_summary_current(cfg,monkeypatch):
    from blacklake import live_api,period_runtime,smartbi_store
    from unittest.mock import AsyncMock
    assert live.cycle(cfg,lambda v,p:(payload(),3))
    c=live.connect(cfg['db'])
    with c:c.execute('UPDATE monitor SET analysis=?',(json.dumps({'reportId':'old','analyzedDataHash':'old'}),))
    c.close()
    class Store:
        def get(self,*_):return {'status':'ready','result':{'summary':'old summary','observations':[]}}
    monkeypatch.setenv('BLACKLAKE_LIVE_CONFIG','synthetic');monkeypatch.setattr(live,'config',lambda _:cfg)
    monkeypatch.setattr(period_runtime,'store',Store);monkeypatch.setattr(period_runtime,'authorized_actor',AsyncMock(return_value=1))
    def fail(*_,**kwargs):
        assert kwargs['live_cycle_id']==live.latest(cfg)['cycleId']
        raise ValueError('source read unavailable')
    monkeypatch.setattr(smartbi_store,'read',fail);monkeypatch.setattr(live_api,'nightly_tick',AsyncMock())
    await live_api.analysis_step({'tenant_id':cfg['tenant_id'],'source_factory_id':'factory','run_id':'baseline'})
    assert live.cycle(cfg,lambda v,p:(payload(),3))
    info=live.status(cfg)['analysis']
    assert info['status']=='pending_latest' and info['summary'] is None and '最新数据' in info['message']


@pytest.mark.asyncio
async def test_final_unseen_snapshot_is_submitted_once_after_deadline_without_endless_retry(cfg,monkeypatch):
    from blacklake import live_api,period_runtime,smartbi_store
    from smartbi.services import analysis_supplement
    from unittest.mock import AsyncMock
    assert live.cycle(cfg,lambda v,p:(payload(),3))
    c=live.connect(cfg['db'])
    with c:c.execute("UPDATE monitor SET ends='2000-01-01T00:00:00Z',analysis=?",(json.dumps({'reportId':'old','analyzedDataHash':'old'}),))
    c.close();submissions=[]
    class Store:
        path=__import__('pathlib').Path(cfg['db']).parent/'period-reports.sqlite3'
        def get(self,scope,id):return {'status':'ready','result':{'summary':'old','observations':[]}} if id=='old' else {'status':'failed'}
        def submit(self,*args):submissions.append(args);return {'id':'final','status':'queued'}
    monkeypatch.setenv('BLACKLAKE_LIVE_CONFIG','synthetic');monkeypatch.setattr(live,'config',lambda _:cfg)
    monkeypatch.setattr(period_runtime,'store',Store);monkeypatch.setattr(period_runtime,'authorized_actor',AsyncMock(return_value=1))
    monkeypatch.setattr(period_runtime,'ensure_worker',lambda _:None)
    monkeypatch.setattr(analysis_supplement,'attach_saved',lambda job,*args:job)
    monkeypatch.setattr(smartbi_store,'read',lambda *_,**kwargs:{'periodMetadata':{'fingerprint':'f'*64}})
    configured={'tenant_id':cfg['tenant_id'],'source_factory_id':'factory','run_id':'baseline'}
    await live_api.analysis_tick(configured)
    await live_api.analysis_tick(configured)
    assert len(submissions)==1
    info=live.status(cfg)['analysis'];assert info['status']=='failed' and '手动' in info['message'] and '两分钟后' not in info['message']
