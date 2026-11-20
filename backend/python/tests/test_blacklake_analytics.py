"""Cost-chain accounting, missingness and test-scope security contracts."""
import copy
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from blacklake.analytics import build, number
from blacklake.smartbi_api import authorize


@pytest.fixture
def source():
    return {'project':[{'id':1,'projectCode':'P1','productCode':'F1','productName':'finished','projectUnit':'kg',
                         'status':'20','planAmount':10,'actualAmount':10,'numberofGoodProducts':10}],
            'task':[{'id':2,'projectId':1,'processName':'cook','processId':3,'processStatus':'20',
                     'sumOutputSalary':20,'outputDurationForSecond':3600,'fineAmount':999}],
            'output':[{'id':4,'projectId':1,'taskId':2,'productName':'finished','processId':3,'stockUnit':'kg',
                       'approvalStatus':'10','numberofgoodProducts':10,'numberofDefectives':0,'estimatedSalary':999}],
            'stock_order':[{'orderStatus':1,'bizType':32,'orderNo':'M1','orderDetails':[
                {'id':1,'productName':'raw','productCode':'R1','unitName':'kg','changeQty':-5,'changedCostAmountSigned':-50,
                 'costUnitPrice':10,'associatedBizOrderCode':'P1','associatedBizOrderId':1,'associatedBizOrderType':10}]}],
            'stock':[{'productName':'raw','unitName':'kg','qtyInWarehouse':5,'warehouseName':'W1'}]}


def analyze(source):
    return build(source,{'finished_at':'2026-11-03T09:21:49+00:00','run_id':'synthetic','stock_pairs_not_returned':3})


def test_cost_chain_uses_project_output_and_task_salary_once(source):
    row=analyze(source)['cost']['projects'][0]
    assert row['materialCost']==50 and row['laborCost']==20
    assert row['finishedGood']==10 and row['unitIdentifiedDirectCost']==7
    assert row['identifiedDirectCost']==70  # ignores task quantities and estimated report wage
    assert analyze(source)['cost']['fullManufacturingCost'] is None


def test_return_reduces_net_consumption_and_cost(source):
    returned=copy.deepcopy(source['stock_order'][0])
    returned['bizType']=30
    returned['orderDetails'][0].update(changeQty=1,changedCostAmountSigned=10)
    source['stock_order'].append(returned)
    result=analyze(source)
    assert result['cost']['projects'][0]['materialCost']==40
    assert result['cost']['materials'][0]['netConsumption']==4


def test_zero_valued_return_blocks_underverified_unit_cost(source):
    returned=copy.deepcopy(source['stock_order'][0])
    returned['bizType']=30
    returned['orderDetails'][0].update(changeQty=1,changedCostAmountSigned=0,costUnitPrice=0)
    source['stock_order'].append(returned)
    assert analyze(source)['cost']['projects'][0]['unitIdentifiedDirectCost'] is None


def test_same_display_name_different_products_and_warehouses_do_not_merge(source):
    source['project'].append(dict(source['project'][0],id=9,projectCode='P2',productCode='F2'))
    source['stock'][0]['warehouseId']=1
    source['stock'].append(dict(source['stock'][0],warehouseId=2))
    result=analyze(source)
    assert len(result['production']['planActual'])==2
    assert len(result['inventory']['byWarehouseUnit'])==2


def test_partial_quality_and_partial_plan_totals_stay_missing(source):
    second=copy.deepcopy(source['output'][0]); second['id']=9; second['numberofDefectives']=None
    source['output'].append(second)
    source['project'].append(dict(source['project'][0],id=9,projectCode='P2',actualAmount=None))
    result=analyze(source)
    assert result['production']['quality'][0]['goodRate'] is None
    assert result['production']['planActual'][0]['actual'] is None


def test_partial_material_consumption_is_unknown_not_a_partial_total(source):
    extra=copy.deepcopy(source['stock_order'][0]['orderDetails'][0]);extra.update(id=2,changeQty=None)
    source['stock_order'][0]['orderDetails'].append(extra)
    row=analyze(source)['cost']['materials'][0]
    assert row['netConsumption'] is None and row['missingQuantityLines']==1


def test_one_zero_or_negative_wage_cannot_be_hidden_by_other_paid_steps(source):
    source['task'].append(dict(source['task'][0],id=8,sumOutputSalary=0))
    assert analyze(source)['cost']['projects'][0]['unitIdentifiedDirectCost'] is None
    source['task'][1]['sumOutputSalary']=-1
    assert analyze(source)['cost']['projects'][0]['unitIdentifiedDirectCost'] is None


@pytest.mark.parametrize('quantity,cost,price',[(0,-10,10),(-5,50,10),(-5,-50,-10)])
def test_movement_sign_and_price_anomalies_block_unit_cost(source,quantity,cost,price):
    source['stock_order'][0]['orderDetails'][0].update(changeQty=quantity,changedCostAmountSigned=cost,costUnitPrice=price)
    assert analyze(source)['cost']['projects'][0]['unitIdentifiedDirectCost'] is None


def test_full_material_return_cannot_produce_labor_only_finished_unit_cost(source):
    returned=copy.deepcopy(source['stock_order'][0]);returned['bizType']=30
    returned['orderDetails'][0].update(changeQty=5,changedCostAmountSigned=50)
    source['stock_order'].append(returned)
    assert analyze(source)['cost']['projects'][0]['unitIdentifiedDirectCost'] is None


def test_partial_valid_materials_do_not_hide_unlinked_candidate_cost(source):
    second=copy.deepcopy(source['stock_order'][0]['orderDetails'][0]);second.update(id=8,associatedBizOrderId=None)
    source['stock_order'][0]['orderDetails'].append(second)
    result=analyze(source)
    assert result['cost']['unlinkedMaterialLines']==1
    assert result['cost']['projects'][0]['unitIdentifiedDirectCost'] is None


def test_conflicting_id_and_code_blocks_both_candidate_projects(source):
    source['project'].append(dict(source['project'][0],id=9,projectCode='P2'))
    source['task'].append(dict(source['task'][0],id=10,projectId=9))
    second=copy.deepcopy(source['stock_order'][0]['orderDetails'][0]);second.update(id=8,associatedBizOrderId=9)
    source['stock_order'][0]['orderDetails'].append(second)
    result=analyze(source)
    assert all('存在可能属于本工单的领退料关联缺失或冲突' in r['issues'] for r in result['cost']['projects'])


@pytest.mark.parametrize('case',['missing_material','zero_material','missing_wage','zero_wage','not_completed','zero_finished','missing_unit','bad_parent','missing_parent','wrong_parent_type'])
def test_incomplete_or_suspicious_chain_has_no_unit_cost(source,case):
    line=source['stock_order'][0]['orderDetails'][0]
    if case=='missing_material': line['changedCostAmountSigned']=None
    elif case=='zero_material': line.update(changedCostAmountSigned=0,costUnitPrice=0)
    elif case=='missing_wage': source['task'][0]['sumOutputSalary']=None
    elif case=='zero_wage': source['task'][0]['sumOutputSalary']=0
    elif case=='not_completed': source['project'][0]['status']='10'
    elif case=='zero_finished': source['project'][0]['numberofGoodProducts']=0
    elif case=='missing_unit': source['project'][0]['projectUnit']=None
    elif case=='bad_parent': line['associatedBizOrderId']=99
    elif case=='missing_parent': line['associatedBizOrderId']=None
    else: line['associatedBizOrderType']=99
    row=analyze(source)['cost']['projects'][0]
    assert row['unitIdentifiedDirectCost'] is None and row['issues']


def test_one_zero_valued_material_line_blocks_partial_understated_unit_cost(source):
    second=copy.deepcopy(source['stock_order'][0]['orderDetails'][0])
    second.update(id=2,productCode='R2',changedCostAmountSigned=0,costUnitPrice=0)
    source['stock_order'][0]['orderDetails'].append(second)
    row=analyze(source)['cost']['projects'][0]
    assert row['materialCost']==50 and row['unitIdentifiedDirectCost'] is None


def test_unapproved_or_nonproduction_movements_are_excluded(source):
    source['stock_order'][0]['orderStatus']=0
    assert analyze(source)['cost']['linkedMaterialLines']==0
    source['stock_order'][0].update(orderStatus=1,bizType=11)
    assert analyze(source)['cost']['linkedMaterialLines']==0


def test_inventory_preserves_negative_missing_and_separate_units(source):
    source['stock'] += [{'productName':'raw2','unitName':'箱','qtyInWarehouse':2,'warehouseName':'W1'},
                        {'productName':'raw3','unitName':'kg','qtyInWarehouse':None,'warehouseName':'W2'},
                        {'productName':'raw4','unitName':'kg','qtyInWarehouse':-1,'warehouseName':'W3'}]
    inventory=analyze(source)['inventory']
    assert len(inventory['byWarehouseUnit'])==4 and inventory['pairsNotReturned']==3
    assert inventory['records'][2]['quantity'] is None
    assert inventory['records'][3]['quantity']==-1


def test_quality_is_per_process_and_approval_missing_is_not_zero(source):
    second=copy.deepcopy(source['output'][0]); second['processId']=9
    source['output'].append(second)
    result=analyze(source)
    assert len(result['production']['quality'])==2
    assert result['cost']['projects'][0]['finishedGood']==10
    source['output'][0]['numberofDefectives']=None
    assert analyze(source)['production']['quality'][0]['goodRate'] is None


def test_duplicate_project_codes_fail_closed(source):
    source['project'].append(dict(source['project'][0],id=9))
    with pytest.raises(ValueError,match='AMBIGUOUS'): analyze(source)


def test_duplicate_nested_movement_detail_fails_closed(source):
    source['stock_order'][0]['orderDetails']*=2
    with pytest.raises(ValueError,match='DUPLICATE_OR_MISSING_MOVEMENT_DETAIL_ID'): analyze(source)


@pytest.mark.parametrize('value',[None,'',True,'NaN','Infinity','bad'])
def test_non_numeric_values_stay_missing(value):
    assert number(value) is None


@pytest.mark.parametrize('factory,role,method',[('F006','factory_super_admin','jwt'),('BLACKLAKE_POC_TEST','viewer','jwt'),('BLACKLAKE_POC_TEST','factory_super_admin','internal')])
def test_smartbi_source_rejects_cross_factory_non_admin_and_internal(factory,role,method):
    request=SimpleNamespace(state=SimpleNamespace(factory_id=factory,role=role,auth_method=method))
    with pytest.raises(HTTPException) as exc: authorize(request,{'tenant_id':'BLACKLAKE_POC_TEST'})
    assert exc.value.status_code==403
