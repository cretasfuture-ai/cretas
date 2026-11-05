import pytest
from blacklake.blocked_audit import CATEGORIES, category, validate_current
from blacklake.blocked_report import linked, value, not_later, fields
from blacklake.contracts import BlacklakeError

def row(**kw):
    return dict(unitCostAvailable=False,status='20',finishedGood=10,materialLines=2,issues=[],**kw)

@pytest.mark.parametrize('status',['0','10','30'])
def test_unfinished_first_even_when_missing_good(status):
    r=row();r.update(status=status,finishedGood=None,materialLines=0)
    assert category(r)==CATEGORIES[0]

@pytest.mark.parametrize('good',[None,0,-1])
def test_no_good_precedes_missing_material(good):
    r=row();r.update(finishedGood=good,materialLines=0)
    assert category(r)==CATEGORIES[1]

def test_mutually_exclusive_material_classes():
    r=row();r['materialLines']=0;assert category(r)==CATEGORIES[2]
    r['materialLines']=1;r['issues']=['存在耗料但材料金额全部为源端零值','部分领料金额或单价为零/缺失，估值未核实']
    assert category(r)==CATEGORIES[3]
    r['issues']=r['issues'][1:];assert category(r)==CATEGORIES[4]
    r['unitCostAvailable']=True;assert category(r)=='已具备可识别单位直接成本条件'

def test_link_requires_type_id_and_code():
    p={'id':5,'projectCode':'P5'};d={'associatedBizOrderType':10,'associatedBizOrderId':'5','associatedBizOrderCode':'P5'}
    assert linked(d,p)
    for key in d:
        other={**d,key:'wrong'};assert not linked(other,p)
    assert not linked({**d,'associatedBizOrderCode':None},{**p,'projectCode':None})

def test_zero_and_absent_are_distinct():
    assert value({'x':0},'x')=='0'
    assert value({},'x')=='未提供'
    assert fields({'x':0},['x'])=='无'
    assert fields({'x':0},['x'],True)=='x'
    assert fields({'costUnitPrice':'NaN'},['costUnitPrice'])=='格式非法:costUnitPrice'

def test_historical_price_compares_actual_instants_and_rejects_unknown():
    assert not_later('2026-11-03T10:00:00+08:00','2026-11-03T03:00:00Z')
    assert not not_later('2026-11-03T04:00:00Z','2026-11-03T10:00:00+08:00')
    assert not not_later(None,'2026-11-03T03:00:00Z')
    assert not not_later('garbage','2026-11-03T03:00:00Z')

@pytest.mark.parametrize('data',[
    {'project':[{'id':1,'projectCode':'P'},{'id':1,'projectCode':'Q'}]},
    {'project':[{'id':1,'projectCode':'P'},{'id':2,'projectCode':'P'}]},
    {'project':[{'projectCode':'P'}]},
    {'project':[{'id':1,'projectCode':'P'}],'task':[{'id':2},{'id':2}]},
])
def test_ambiguous_current_facts_fail_closed(data):
    with pytest.raises(BlacklakeError):validate_current(data)
