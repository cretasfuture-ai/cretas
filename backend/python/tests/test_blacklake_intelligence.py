"""Real SmartBI reuse, exact cost bridges and independent source gap evidence."""
import copy
import pytest
from blacklake.analytics import build
from blacklake.intelligence import enrich,bridge,_smartbi
from blacklake.gap_evidence import derive,campaign_complete,EVIDENCE_SCHEMA
from blacklake.full_archive import digest
from blacklake.contracts import ENDPOINTS
from tests.test_blacklake_analytics import source

META={'run_id':'synthetic','finished_at':'2026-11-03T09:21:49+00:00','stock_pairs_not_returned':0}


def two_projects(source):
    source['product']=[{'id':100,'productCode':'F1'},{'id':200,'productCode':'R1'}]
    other=copy.deepcopy(source['project'][0]);other.update(id=9,projectCode='P2')
    source['project'].append(other)
    other_task=copy.deepcopy(source['task'][0]);other_task.update(id=10,projectId=9,sumOutputSalary=30)
    source['task'].append(other_task)
    order=copy.deepcopy(source['stock_order'][0])
    order['orderDetails'][0].update(associatedBizOrderId=9,associatedBizOrderCode='P2',changeQty=-6,changedCostAmountSigned=-72,costUnitPrice=12)
    source['stock_order'].append(order)
    return source


def test_material_price_usage_and_labor_bridge_reconciles(source):
    data=two_projects(source);result=build(data,META)
    comparison=bridge(*result['cost']['projects'])
    assert comparison['materialDelta']==pytest.approx(2.2)
    assert comparison['laborDelta']==1
    assert comparison['delta']==pytest.approx(3.2)
    assert comparison['materials'][0]['usageEffect']==1
    assert comparison['materials'][0]['priceEffect']==pytest.approx(1.2)
    assert abs(comparison['reconciliationDifference'])<1e-8


def test_real_smartbi_candidate_and_fact_functions_are_used(source,monkeypatch):
    _smartbi()
    from smartbi.services.factory_charts import engine,conversation
    original=engine.series_candidate;original_facts=conversation.facts_for
    called=[]
    def candidate(*args,**kwargs):called.append('candidate');return original(*args,**kwargs)
    def facts(*args,**kwargs):called.append('facts');return original_facts(*args,**kwargs)
    monkeypatch.setattr(engine,'series_candidate',candidate)
    monkeypatch.setattr(conversation,'facts_for',facts)
    data=two_projects(source)
    result=enrich(build(data,META),data,META)
    assert called==['candidate','facts']
    assert len(result['recommendations'])==1 and result['facts']
    assert result['recommendations'][0]['chart']['echartsOption']['series']
    assert '累计口径' not in str(result['recommendations'][0]['findings'])


def test_weighted_cost_is_ratio_of_sums_not_average_of_unit_costs(source):
    data=two_projects(source);data['project'][1]['numberofGoodProducts']=20
    result=enrich(build(data,META),data,META)
    assert result['comparisons'][0]['weightedUnitCost']==pytest.approx(172/30)


def test_ineligible_project_excluded_and_coverage_remains_visible(source):
    data=two_projects(source);data['stock_order'][1]['orderDetails'][0]['costUnitPrice']=0
    result=enrich(build(data,META),data,META)
    c=result['comparisons'][0]
    assert c['eligibleProjects']==1 and c['visibleProjects']==2 and c['bridge'] is None


@pytest.mark.parametrize('field,value',[('productCode','different'),('unit','box'),('unitCostAvailable',False)])
def test_bridge_rejects_different_scope_or_ineligible(source,field,value):
    result=build(two_projects(source),META)['cost']['projects']
    result[1][field]=value
    with pytest.raises(ValueError,match='SCOPE_INVALID'):bridge(*result)


def test_price_clues_never_change_actual_cost_or_eligibility(source):
    source['stock_order'][0]['orderDetails'][0].update(costUnitPrice=0,changedCostAmountSigned=0)
    result=build(source,META);before=copy.deepcopy(result['cost'])
    enriched=enrich(result,source,META,{'currentPriceClues':[{'price':99999}],'verifiedMaterialParents':[]})
    assert result['cost']==before and enriched['recommendations']==[]


def test_new_material_contribution_is_not_fabricated_as_price_or_usage_effect(source):
    data=two_projects(source)
    extra=copy.deepcopy(data['stock_order'][1]['orderDetails'][0]);extra.update(id=8,productCode='R2',changedCostAmountSigned=-12)
    data['stock_order'][1]['orderDetails'].append(extra)
    result=bridge(*build(data,META)['cost']['projects'])
    added=next(r for r in result['materials'] if r['materialCode']=='R2')
    assert added['delta']==pytest.approx(1.2) and added['priceEffect'] is None and added['usageEffect'] is None


def proof():
    detail={'id':1,'productId':2,'productCode':'R','productName':'raw','unitName':'kg',
            'changedCostAmountSigned':0,'costUnitPrice':0}
    order={'id':3,'orderNo':'OUT','orderType':20,'bizAt':'2026-11-01 00:00:00','orderDetails':[detail]}
    offline={'run_id':'synthetic','tenant_id':'T','source_factory_id':'F','unlinked':[{'order':order,'detail':detail}],
             'zero_material_lines':[{'order':order,'detail':detail}],'incoming_candidates':{}}
    live={'run_id':'synthetic','tenant_id':'T','source_factory_id':'F','snapshot_fingerprint':'hash',
          'started_at':'2026-11-01T00:00:00+00:00','expected_projects':1,'failures':[],
          'finished_at':'2026-11-03T00:00:00+00:00','materials':[{'project_id':9,'dual_query_equal':True,'snapshot_equal':True,'rows':[{'id':10}]}],
          'fresh_stock_orders':[order],'fresh_products':[]}
    negative={'tenant_id':'T','source_factory_id':'F','run_id':'synthetic','snapshot_fingerprint':'hash',
              'resource':'materials','endpoint':ENDPOINTS['materials'],'body':{'projectCode':'CRETAS-NONEXISTENT-0123456789'},
              'checked_at':'2026-11-02T00:00:00+00:00'}
    negative['query_digest']=digest(negative)
    negative.update(code='1014',msg='工单不存在',mode='source_rejected')
    live['negative_control']=negative
    return offline,live,negative


def test_dual_parent_query_with_negative_control_and_snapshot_equality_proves_query_scope():
    offline,live,_=proof();evidence=derive(offline,live)
    assert evidence['verifiedMaterialParents']==[{'projectId':9,'materialId':10}]
    assert evidence['summary']['unlinkedStillMissing']==1
    assert evidence['summary']['zeroAmountsReconfirmed']==1


@pytest.mark.parametrize('invalid',['negative','snapshot','dual'])
def test_parent_scope_not_promoted_without_each_piece_of_evidence(invalid):
    offline,live,negative=proof()
    if invalid=='negative':negative['msg']='参数校验失败'
    elif invalid=='snapshot':live['materials'][0]['snapshot_equal']=False
    else:live['materials'][0]['dual_query_equal']=False
    assert derive(offline,live)['verifiedMaterialParents']==[]


def test_gap_mixed_runs_and_repeated_child_ids_fail_closed():
    offline,live,negative=proof();offline['run_id']='different'
    with pytest.raises(ValueError,match='RUN_MISMATCH'):derive(offline,live)
    offline,live,negative=proof();live['materials'].append(dict(live['materials'][0],project_id=99))
    with pytest.raises(ValueError,match='SOURCE_ID_REPEATED'):derive(offline,live)


def test_current_master_price_is_explicitly_not_historical_cost():
    offline,live,negative=proof()
    live['fresh_products']=[{'id':2,'productCode':'R','productName':'raw','unit':'kg','movingWeightedAverageCost':12}]
    evidence=derive(offline,live)
    assert evidence['summary']['productsWithCurrentPriceClues']==1
    assert evidence['summary']['productsWithHistoricalPriceReferences']==0
    assert '禁止回填' in evidence['currentPriceClues'][0]['usage']


@pytest.mark.parametrize('field,value',[('tenant_id','other'),('source_factory_id','other'),('run_id','other'),('query_digest','old'),('checked_at','2020-01-01T00:00:00+00:00')])
def test_unbound_or_stale_negative_controls_do_not_promote_parent_scope(field,value):
    offline,live,negative=proof();negative[field]=value
    report=derive(offline,live)
    assert report['status']=='incomplete' and report['verifiedMaterialParents']==[]


def test_partial_live_campaign_is_not_complete():
    offline,live,_=proof();live['failures']=[{'project_id':99}]
    assert derive(offline,live)['status']=='incomplete'


def test_live_unit_price_must_be_rechecked_not_copied_from_old_snapshot():
    offline,live,_=proof();live=copy.deepcopy(live)
    live['fresh_stock_orders'][0]['orderDetails'][0]['costUnitPrice']=12
    report=derive(offline,live)
    assert report['summary']['zeroMovementUnitPrices']==0 and report['summary']['movementUnitPriceChanged']==1


def test_header_origin_code_prevents_claiming_all_link_pointers_empty():
    offline,live,_=proof();live=copy.deepcopy(live)
    live['fresh_stock_orders'][0]['originCodeList']=['EXPLICIT-ORDER']
    assert derive(offline,live)['summary']['unlinkedStillMissing']==0


@pytest.mark.parametrize('returned_amount',[72,80])
def test_nonpositive_net_material_value_is_not_a_price_or_usage_effect(source,returned_amount):
    data=two_projects(source)
    returned=copy.deepcopy(data['stock_order'][1]);returned['bizType']=30
    returned['orderDetails'][0].update(changeQty=1,changedCostAmountSigned=returned_amount,costUnitPrice=returned_amount)
    data['stock_order'].append(returned)
    extra=copy.deepcopy(data['stock_order'][1]['orderDetails'][0]);extra.update(id=8,productCode='R2',changedCostAmountSigned=-100,costUnitPrice=20,changeQty=-5)
    data['stock_order'][1]['orderDetails'].append(extra)
    rows=build(data,META)['cost']['projects'];result=bridge(*rows)
    r=next(row for row in result['materials'] if row['materialCode']=='R1')
    assert r['priceEffect'] is None and r['usageEffect'] is None


def test_campaign_completeness_rejects_snapshot_changes():
    _,live,_=proof();assert campaign_complete(live)
    live['materials'][0]['snapshot_equal']=False
    assert not campaign_complete(live)


@pytest.mark.parametrize('bad',['tenantId','sourceFactoryId','runId','snapshotFingerprint','status','schema','negativeControlVerified'])
def test_store_evidence_binding_rejects_other_scopes_and_unverified_schema(bad):
    from blacklake.smartbi_store import bind_evidence
    settings={'tenant_id':'T','source_factory_id':'F','run_id':'R'}
    records=[('project','1','hash',{})]
    evidence={'schema':EVIDENCE_SCHEMA,'status':'complete','negativeControlVerified':True,
              'tenantId':'T','sourceFactoryId':'F','runId':'R','snapshotFingerprint':digest([('project','1','hash')]),
              'verifiedMaterialParents':[{'projectId':1,'materialId':2}]}
    assert bind_evidence(settings,records,evidence) is evidence
    evidence[bad]=False if bad=='negativeControlVerified' else 'other'
    rejected=bind_evidence(settings,records,evidence)
    assert rejected['status']!='complete' and rejected['verifiedMaterialParents']==[]


@pytest.mark.parametrize('state',['complete','incomplete','legacy'])
def test_only_complete_current_evidence_can_provide_material_references(source,state):
    source['materials']=[{'id':42,'productCode':'R1','unitQty':1,'feedProcessCode':'cook'}]
    evidence={'schema':EVIDENCE_SCHEMA if state!='legacy' else 'old','status':state,
              'negativeControlVerified':True,'verifiedMaterialParents':[{'projectId':1,'materialId':42}]}
    enriched=enrich(build(source,META),source,META,evidence)
    assert bool(enriched['verifiedMaterialReferences'])==(state=='complete')


def test_current_price_clue_requires_id_code_and_unit_agreement():
    offline,live,_=proof()
    live['fresh_products']=[{'id':99,'productCode':'R','unit':'kg','movingWeightedAverageCost':12}]
    assert derive(offline,live)['currentPriceClues']==[]
    live['fresh_products'][0].update(id=2,unit='box')
    report=derive(offline,live)
    assert report['currentPriceClues']==[] and report['summary']['currentPriceUnitMismatches']==1


def test_cross_project_comparison_rejects_ambiguous_codes_and_conflicting_ids(source):
    data=two_projects(source)
    data['product'].append({'id':101,'productCode':'F1'})
    with pytest.raises(ValueError,match='AMBIGUOUS_PRODUCT_CODE_IDENTITY'):enrich(build(data,META),data,META)
    data['product'].pop();data['stock_order'][0]['orderDetails'][0]['productId']=201
    with pytest.raises(ValueError,match='MOVEMENT_PRODUCT_IDENTITY_MISMATCH'):enrich(build(data,META),data,META)


@pytest.mark.parametrize('when,expected',[('2026-10-30 00:00:00',1),('2026-11-02 00:00:00',0)])
def test_price_reference_search_includes_newly_rechecked_receipts_but_not_future_prices(when,expected):
    offline,live,_=proof()
    receipt={'id':7,'orderNo':'IN','orderType':10,'orderStatus':1,'bizAt':when,'bizType':12,
             'orderDetails':[{'id':8,'productId':2,'productCode':'R','unitName':'kg','costUnitPrice':10}]}
    live['fresh_stock_orders'].append(receipt)
    report=derive(offline,live)
    assert report['summary']['priceReferenceRows']==expected
