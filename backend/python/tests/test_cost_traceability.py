from copy import deepcopy
import pytest
from smartbi.services.cost_traceability import diagnose,allocation_evidence


def snapshot():
    return {'scope':{'tenantId':'T','sourceFactoryId':'F','snapshotId':'S'},'checkedAt':'synthetic',
        'queryStatus':{r:'complete' for r in ('project','task','output','stock_order','materials','product')},
        'orders':[{'id':1,'code':'ORDER-1','product':'合成成品','productCode':'FOOD','unit':'KG','goodQuantity':10,
                   'completed':True,'directCostAvailable':True,'identifiedUnitCost':15}],
        'materials':[{'id':8,'projectId':1,'productCode':'RAW','feedProcessId':4,'parentVerified':True}],
        'tasks':[{'id':9,'projectId':1,'processId':4,'salary':50}],
        'outputs':[{'id':11,'projectId':1,'processId':4,'taskId':9}],
        'movements':[{'id':'I','projectId':1,'projectCode':'ORDER-1','projectRelationVerified':True,'posted':True,
            'kind':'issue','productCode':'RAW','unit':'KG','quantity':10,'recognizedCost':100,'unitPrice':10,
            'originMaterialId':8,'originKind':'purchased'},
            {'id':'R','projectId':1,'projectCode':'ORDER-1','projectRelationVerified':True,'posted':True,
             'kind':'finished_receipt','productCode':'FOOD','unit':'KG','quantity':10}]}


def test_explicit_material_and_process_link_does_not_prove_batch_cost_chain():
    report=diagnose(snapshot());order=report['workOrders'][0]
    assert order['businessLinks'][0]['processId']==4
    assert report['summary']['materialOriginLinks']==1
    assert not order['physicalBatchChainVerified'] and not order['fullManufacturingCostAvailable']
    assert {n['code'] for n in order['needs']}=={'INPUT_BATCH_ALLOCATION'}
    need=order['needs'][0]
    assert need['responsibleRole']=='仓库/生产负责人' and '批号' in need['missingData'] and '不再重复加' in need['afterProvided']


@pytest.mark.parametrize('field,value',[('projectId',2),('productCode','OTHER'),('parentVerified',False)])
def test_cross_parent_product_or_unverified_material_context_is_never_linked(field,value):
    data=snapshot();data['materials'][0][field]=value
    report=diagnose(data)
    assert not report['workOrders'][0]['businessLinks']
    assert 'INPUT_PROCESS_LINK' in {n['code'] for n in report['actions']}


def test_partial_query_does_not_become_a_request_to_factory_for_missing_source_data():
    data=snapshot();data['movements']=[];data['queryStatus']['stock_order']='query_failed'
    report=diagnose(data)
    missing=next(n for n in report['actions'] if n['code']=='MATERIAL_DOCUMENTS')
    assert missing['sourceStatus']=='query_incomplete' and missing['responsibleRole']=='集成负责人'


def test_same_product_and_unit_producer_is_only_a_candidate_even_when_unique():
    data=snapshot();data['movements'][0]['originKind']='manufactured'
    data['orders'].append({'id':2,'code':'POSSIBLE','productCode':'RAW','unit':'KG','goodQuantity':10,'completed':True})
    order=diagnose(data)['workOrders'][0]
    assert order['upstreamCandidates'][0]['candidateCount']==1
    assert order['upstreamCandidates'][0]['actualBatchVerified'] is False
    assert not order['physicalBatchChainVerified']


def test_unknown_final_quantity_and_zero_wage_remain_unverified():
    data=snapshot();data['orders'][0]['goodQuantity']=None;data['tasks'][0]['salary']=0
    order=diagnose(data)['workOrders'][0]
    assert order['goodQuantity'] is None
    assert {'FINAL_GOOD_BASIS','LABOR_BASIS'}<={n['code'] for n in order['needs']}


def test_unposted_receipt_and_wrong_project_code_are_not_counted():
    data=snapshot();data['movements'][1]['posted']=False;data['movements'][0]['projectCode']='WRONG'
    order=diagnose(data)['workOrders'][0]
    assert not order['finishedReceiptIds'] and not order['businessLinks']


def test_cross_factory_row_is_rejected_before_building_worklist():
    data=snapshot();data['tasks'][0]['sourceFactoryId']='OTHER'
    with pytest.raises(ValueError,match='SCOPE_MISMATCH'):diagnose(data)


def test_explicit_split_allocation_is_valid_but_never_sums_upstream_costs():
    issues=[{'id':'I','productCode':'RAW','batchId':'B','unit':'kg','quantity':10,'posted':True}]
    receipts=[{'id':'R1','productCode':'RAW','batchId':'B','unit':'KG','quantity':6,'posted':True},
              {'id':'R2','productCode':'RAW','batchId':'B','unit':'KG','quantity':8,'posted':True}]
    result=allocation_evidence(issues,receipts,[{'issueId':'I','receiptId':'R1','quantity':6},{'issueId':'I','receiptId':'R2','quantity':4}])
    assert len(result['edges'])==2 and not result['errors'] and result['costsRolledUp'] is False
    receipts[0]['batchId']='OTHER'
    assert not allocation_evidence(issues,receipts,[{'issueId':'I','receiptId':'R1','quantity':10}])['edges']


def test_shared_receipt_overallocation_invalidates_all_its_edges():
    issues=[{'id':i,'productCode':'RAW','batchId':'B','unit':'kg','quantity':6,'posted':True} for i in ('A','B')]
    receipts=[{'id':'R','productCode':'RAW','batchId':'B','unit':'kg','quantity':10,'posted':True}]
    result=allocation_evidence(issues,receipts,[{'issueId':i,'receiptId':'R','quantity':6} for i in ('A','B')])
    assert not result['edges'] and any(e['code']=='RECEIPT_OVERALLOCATED' for e in result['errors'])


def test_zero_wage_without_work_time_does_not_require_repaying_every_process():
    data=snapshot();data['tasks'].append({'id':12,'projectId':1,'processId':5,'salary':0,'durationSeconds':0})
    assert 'LABOR_BASIS' not in {n['code'] for n in diagnose(data)['actions']}


def test_completed_order_with_blocked_cost_always_exposes_its_reason():
    data=snapshot();data['orders'][0].update(directCostAvailable=False,directCostBlockingReasons=['合成数量金额符号需核'])
    report=diagnose(data)
    assert any(n['impactType']=='unit_cost' and '合成数量金额符号需核' in n['missingData'] for n in report['actions'])


def test_negative_price_is_an_explicit_finance_request_not_a_low_cost_conclusion():
    data=snapshot();data['movements'][0].update(unitPrice=-3,recognizedCost=-30)
    need=next(n for n in diagnose(data)['actions'] if n['code']=='MATERIAL_AMOUNT_CHECK')
    assert need['sourceStatus']=='source_values_inconsistent'
    assert '财务' in need['responsibleRole'] and '恢复可信' in need['afterProvided']
