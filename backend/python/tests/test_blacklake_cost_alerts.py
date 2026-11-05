import copy
import json
import pytest
from blacklake.analytics import build
from blacklake.cost_review import review
from blacklake.local_cost_ai import validate_response,request_facts,fingerprint,attach
from smartbi.services.cost_data_alerts import evaluate
from tests.test_blacklake_analytics import source

def analyze(data):
    return build(data,{'run_id':'test','finished_at':'2026-11-03T09:21:49+00:00','stock_pairs_not_returned':0})

def alerts(data):return evaluate(analyze(data)['cost']['projects'],scope='factory-a/source-a',run_id='test')

def types(result):return {r['type'] for r in result['items']}

def test_evidence_chain_reconciles_without_double_labor(source):
    result=analyze(source);view=review(result);p=view['projects'][0]
    assert p['materialPerFinished']==5 and p['laborPerFinished']==2
    assert sum(r['costPerFinished'] for r in p['components'])+p['laborPerFinished']==p['unitIdentifiedDirectCost']
    assert 'PEER_SAMPLE_SMALL' in types(alerts(source))

@pytest.mark.parametrize('value,kind',[(None,'MATERIAL_VALUE_MISSING'),(0,'MATERIAL_VALUE_ZERO')])
def test_absent_and_explicit_zero_have_different_rules(source,value,kind):
    source['stock_order'][0]['orderDetails'][0]['changedCostAmountSigned']=value
    a=alerts(source);assert kind in types(a)
    assert any(i['blocksUnitCost'] for i in a['items'] if i['type']==kind)
    assert a['summary']['costBlocked']==1

def test_unfinished_is_readiness_and_does_not_become_no_activity(source):
    source['project'][0]['status']='10';a=alerts(source)
    rule=next(i for i in a['items'] if i['type']=='NOT_FINISHED')
    assert rule['family']=='readiness' and rule['blocksUnitCost']

def test_unpaid_hours_and_zero_labor_without_hours_both_visible(source):
    source['task'][0]['sumOutputSalary']=0
    assert 'LABOR_WITH_HOURS_ZERO' in types(alerts(source))
    source['task'][0]['outputDurationForSecond']=0
    a=alerts(source)
    assert a['summary']['costEligible']==1 and 'LABOR_ZERO_UNVERIFIED' in types(a)

def test_spread_never_compares_different_sku_or_unit(source):
    p=analyze(source)['cost']['projects'][0]
    peers=[dict(p,projectId=i,projectCode='P'+str(i),unitIdentifiedDirectCost=v) for i,v in [(1,7),(2,14),(3,21)]]
    a=evaluate(peers,scope='s',run_id='r')
    assert 'UNIT_COST_SPREAD' in types(a)
    peers[2]['unit']='box';assert 'UNIT_COST_SPREAD' not in types(evaluate(peers,scope='s',run_id='r'))
    peers[2]['unit']='kg';peers[2]['productCode']='another';assert 'UNIT_COST_SPREAD' not in types(evaluate(peers,scope='s',run_id='r'))

def test_material_and_labor_ratio_is_a_candidate_not_efficiency_claim(source):
    source['task'][0]['sumOutputSalary']=1000
    a=alerts(source)
    assert {'MATERIAL_SHARE_LOW','LABOR_SHARE_HIGH'}<=types(a)
    assert all(not r['blocksUnitCost'] for r in a['items'] if r['family']=='business_signal')

def test_packaging_keyword_is_only_evidence_hint(source):
    source['stock_order'][0]['orderDetails'][0]['productName']='吸塑盒'
    a=alerts(source);r=next(r for r in a['items'] if r['type']=='INPUT_ONLY_CONSUMABLES')
    assert not r['blocksUnitCost'] and '非物料类别真值' in r['evidence']['method']

def test_scope_and_snapshot_separate_alert_identity(source):
    rows=analyze(source)['cost']['projects']
    a=evaluate(rows,scope='a',run_id='r');b=evaluate(rows,scope='b',run_id='r');c=evaluate(rows,scope='a',run_id='r2')
    assert a['items'][0]['id']!=b['items'][0]['id']!=c['items'][0]['id']
    assert fingerprint(a)!=fingerprint(b)

def test_missing_identity_or_different_process_names_suppress_spread(source):
    p=analyze(source)['cost']['projects'][0]
    rows=[dict(p,projectId=i,projectCode='P'+str(i),unitIdentifiedDirectCost=v) for i,v in [(1,7),(2,14),(3,21)]]
    for r in rows:r['productCode']=None
    a=evaluate(rows,scope='s',run_id='r')
    assert 'PRODUCT_IDENTITY_MISSING' in types(a) and 'UNIT_COST_SPREAD' not in types(a)
    for r in rows:r['productCode']='F1'
    rows[2]['product']='different stage'
    a=evaluate(rows,scope='s',run_id='r')
    assert 'PRODUCT_NAME_CONFLICT' in types(a) and 'UNIT_COST_SPREAD' not in types(a)

def test_ai_cannot_add_facts_or_select_unknown_alert(source):
    facts=request_facts(alerts(source));id=facts[0]['alertId']
    result=validate_response({'reviews':[{'alertId':id,'check':'CHECK_COMPARABILITY','priority':'high'}]},facts[:1])
    assert result[0]['suggestion']
    for item in [{'alertId':'invented','check':'CHECK_PAYROLL','priority':'high'},
                 {'alertId':id,'check':'INVENT_CAUSE','priority':'high'},
                 {'alertId':id,'check':'CHECK_PAYROLL','priority':'high','fact':'cost is zero'}]:
        with pytest.raises(ValueError):validate_response({'reviews':[item]},facts)
    assert not any('projectCode' in f or 'materialCost' in f for f in facts)
    with pytest.raises(ValueError):validate_response({'reviews':[{'alertId':id,'check':'CONFIRM_CURRENCY','priority':'high'}]},facts)

@pytest.mark.parametrize('saved',[None,[],{}, {'reviews':[]}])
def test_ai_corrupt_or_incomplete_file_cannot_break_rules(source,tmp_path,monkeypatch,saved):
    monkeypatch.setattr('blacklake.local_cost_ai.load_local_secret',lambda p:saved)
    p=tmp_path/'ai';p.touch();a=alerts(source);items=copy.deepcopy(a['items']);attach(a,p)
    assert a['items']==items and a['ai']['status'] in ('invalid','stale')

def test_ai_decrypt_failure_cannot_break_rules(source,tmp_path,monkeypatch):
    def fail(p):raise RuntimeError('decrypt failed')
    monkeypatch.setattr('blacklake.local_cost_ai.load_local_secret',fail)
    p=tmp_path/'ai';p.touch();a=alerts(source);attach(a,p)
    assert a['ai']['status']=='invalid' and a['summary']['costEligible']==1

def test_old_ai_fact_binding_is_rejected(source,tmp_path,monkeypatch):
    monkeypatch.setattr('blacklake.local_cost_ai.load_local_secret',lambda p:{'fingerprint':'old'})
    p=tmp_path/'ai';p.touch();a=alerts(source);attach(a,p)
    assert a['ai']['status']=='stale'

def test_all_blocked_projects_have_machine_readable_blocker(source):
    result=analyze(source);p=result['cost']['projects'][0]
    p['unitCostAvailable']=False;p['issues']=['unrecognized upstream check']
    a=evaluate([p],scope='s',run_id='r')
    assert any(r['blocksUnitCost'] and r['type']=='UPSTREAM_COST_BLOCKED' for r in a['items'])

@pytest.mark.parametrize('threshold',['NaN','-1','0','bad'])
def test_invalid_threshold_fails_closed(source,threshold):
    with pytest.raises(ValueError):evaluate(analyze(source)['cost']['projects'],scope='s',run_id='r',spread_threshold=threshold)
