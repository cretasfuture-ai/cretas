import copy
import pytest
from blacklake.full_archive import digest
from blacklake.operational_source import project_observation
from blacklake.period_projection import project_period
from smartbi.services.period_windows import parse_window
from tests.test_blacklake_analytics import source


def observation(raw):
    raw=copy.deepcopy(raw)
    raw['stock_order'][0]['id']=50
    raw.update(product=[{'id':10}],process=[{'id':3}],materials=[])
    raw['project'][0]['actualEndTime']='2026-11-03T12:00:00+08:00'
    current=copy.deepcopy(raw)
    current['task'][0]['sumOutputSalary']=30
    current.pop('stock')
    config={'tenant_id':'TEST','source_factory_id':'FACTORY-A','run_id':'baseline'}
    meta={'run_id':'baseline','finished_at':'2026-11-03T09:00:00Z','stock_pairs_not_returned':0}
    item={'tenant_id':'TEST','source_factory_id':'FACTORY-A','run_id':'baseline','probeId':'current',
          'data':current,'snapshot_fingerprint':digest(raw['project']),'observationFingerprint':digest(current),
          'finished_at':'2026-11-04T10:00:00Z','negativeControlVerified':True,'parentControls':[],
          'failures':[],'originalProjectsNotReturned':[],'sourceBusinessWrites':0,'baselineMutated':False}
    return raw,meta,config,item


def test_latest_production_is_bound_to_original_cohort_and_inventory_keeps_its_own_clock(source):
    raw,meta,config,item=observation(source)
    before=copy.deepcopy(raw)
    current,metadata=project_observation(raw,meta,config,item)
    assert current['task'][0]['sumOutputSalary']==30
    assert current['stock']==before['stock']
    assert metadata['finished_at']=='2026-11-04T10:00:00Z'
    assert metadata['stock_finished_at']=='2026-11-03T09:00:00Z'
    selected,_,period=project_period(current,metadata,parse_window('2026-11-04','2026-11-04',kind='day'))
    assert selected['stock']==[] and period['inventoryStatus']=='historical_balance_unavailable'
    selected,_,period=project_period(current,metadata,parse_window('2026-11-03','2026-11-03',kind='day'))
    assert selected['stock']==before['stock']
    assert period['inventoryAsOf']=='2026-11-03T09:00:00Z'
    assert raw==before


@pytest.mark.parametrize('change',[
    {'tenant_id':'OTHER'}, {'run_id':'OTHER'}, {'snapshot_fingerprint':'bad'},
    {'observationFingerprint':'bad'}, {'negativeControlVerified':False},
    {'sourceBusinessWrites':1}, {'baselineMutated':True},
    {'originalProjectsNotReturned':['one']}, {'finished_at':'invalid'},
    {'failures':[{'resource':'output'}]},
])
def test_rejects_foreign_stale_mutated_or_incomplete_observations(source,change):
    raw,meta,config,item=observation(source)
    with pytest.raises(ValueError):project_observation(raw,meta,config,{**item,**change})


def test_failed_bom_requires_matching_verified_supplement(source):
    raw,meta,config,item=observation(source)
    item['failures']=[{'resource':'bom'}]
    with pytest.raises(ValueError):project_observation(raw,meta,config,item)
    bom={'complete':True,'rows':[{'id':1}],'failures':[],'tenantId':'TEST','sourceFactoryId':'FACTORY-A',
         'baselineRunId':'baseline','parentProbeId':'current','sourceBusinessWrites':0}
    assert project_observation(raw,meta,config,item,bom)[0]['bom']==[{'id':1}]
    with pytest.raises(ValueError):project_observation(raw,meta,config,item,{**bom,'parentProbeId':'OTHER'})


def test_unknown_source_time_is_not_misreported_as_an_early_snapshot(source):
    _,_,period=project_period(source,{'run_id':'test','finished_at':None},parse_window('2026-11-03','2026-11-03',kind='day'))
    assert period['coverageStatus']=='source_time_unknown'


def test_queue_revision_changes_with_observation_and_rejects_foreign_scope(source,tmp_path):
    import json
    from blacklake.operational_source import source_revision
    _,_,config,item=observation(source)
    path=tmp_path/'source.json';path.write_text(json.dumps(item),encoding='utf8')
    initial=source_revision(config,path)
    assert initial==source_revision(config,path)
    item['finished_at']='2026-11-05T10:00:00Z';path.write_text(json.dumps(item),encoding='utf8')
    assert source_revision(config,path)!=initial
    with pytest.raises(ValueError,match='SCOPE_MISMATCH'):
        source_revision({**config,'tenant_id':'OTHER'},path)


def test_text_child_ids_are_canonicalized_without_losing_labor_or_mutating_source(source):
    from blacklake.analytics import build
    raw,meta,config,item=observation(source)
    item['data']['task'][0]['projectId']='1'
    item['data']['output'][0]['projectId']='1'
    item['observationFingerprint']=digest(item['data'])
    current,metadata=project_observation(raw,meta,config,item)
    result=build(current,metadata)
    assert result['cost']['projects'][0]['laborCost']==30
    assert result['production']['processes'][0]['projectIds']==[1]
    assert item['data']['task'][0]['projectId']=='1'
