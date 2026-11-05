import copy
import pytest
from blacklake.full_archive import digest
from blacklake.operations_supplement import apply
from blacklake.operational_source import project_observation
from blacklake.period_projection import project_period
from blacklake.analytics import build
from smartbi.services.period_windows import parse_window
from tests.test_blacklake_operational_source import observation
from tests.test_blacklake_analytics import source


def signed(s):
    s['fingerprint']=digest({k:v for k,v in s.items() if k!='fingerprint'})
    return s


def fixture(source):
    raw,meta,config,parent=observation(source)
    parent['data']['output'][0].pop('stockUnit',None)
    parent['observationFingerprint']=digest(parent['data'])
    data,meta=project_observation(raw,meta,config,parent)
    fresh=copy.deepcopy(data['output']);fresh[0]['stockUnit']='kg'
    s=signed({'schema':'blacklake-operations-supplement/1','id':'supplement','tenant_id':'TEST','source_factory_id':'FACTORY-A',
       'baselineRunId':'baseline','parentObservationId':'current','parentOutputFingerprint':digest(data['output']),
       'startedAt':'2026-11-05T07:00:00Z','finishedAt':'2026-11-05T07:00:05Z','complete':True,'factoryVerified':True,'sourceBusinessWrites':0,
       'outputs':fresh,'products':[{'id':10}],'warehouses':[{'id':5}],'stock':[{'productId':10,'warehouseId':5,'qtyInWarehouse':8,'unitName':'kg','productName':'raw','warehouseName':'W1'}],
       'stockPairsQueried':1,'stockPairsNotReturned':0,'failures':[]})
    return data,meta,config,s


def test_restores_only_verified_unit_and_preserves_costs_and_historical_balances(source):
    data,meta,config,s=fixture(source);before=copy.deepcopy(data)
    result,m=apply(data,meta,config,s)
    assert result['output'][0]['stockUnit']=='kg' and 'stockUnit' not in data['output'][0]
    assert m['operations_supplement']['reportingUnitsRecovered']==1 and not m['operations_supplement']['unresolvedOutputs']
    assert build(result,m)['cost']['projects']==build(before,meta)['cost']['projects']
    for day,quantity in [('2026-11-03',5),('2026-11-05',8)]:
        selected,scoped,period=project_period(result,m,parse_window(day,day,kind='day'))
        assert selected['stock'][0]['qtyInWarehouse']==quantity
        assert build(selected,scoped)['inventory']['lastAvailableBalanceAt']==s['finishedAt']
    empty,_,period=project_period(result,m,parse_window('2026-11-04','2026-11-04',kind='day'))
    assert empty['stock']==[] and period['inventoryAsOf'] is None
    month,_,_=project_period(result,m,parse_window('2026-11-01','2026-11-30',kind='month'))
    assert len(month['stock'])==1 and month['stock'][0]['qtyInWarehouse']==8
    assert data==before


def test_changed_reporting_facts_are_not_silently_attached_to_old_unit_costs(source):
    data,meta,config,s=fixture(source)
    s['outputs'][0]['numberofgoodProducts']=999;signed(s)
    result,m=apply(data,meta,config,s)
    assert 'stockUnit' not in result['output'][0]
    assert result['output'][0]['numberofgoodProducts']==10
    assert m['operations_supplement']['unresolvedOutputs'][0]['reason']=='source_missing_or_changed'
    assert m['operations_supplement']['requests'][0]['code']=='reporting_units'


@pytest.mark.parametrize('changes',[{'tenant_id':'OTHER'},{'parentObservationId':'OTHER'},{'sourceBusinessWrites':1},{'factoryVerified':False},{'complete':False},{'startedAt':'2026-11-01T00:00:00Z'},{'parentOutputFingerprint':'bad'},{'stockPairsNotReturned':1},{'stock':[{'productId':99,'warehouseId':5}]},{'warehouses':[]}])
def test_rejects_foreign_unverified_or_incomplete_supplements(source,changes):
    data,meta,config,s=fixture(source);s.update(changes);signed(s)
    with pytest.raises(ValueError):apply(data,meta,config,s)


def test_modified_payload_and_duplicate_stock_are_rejected(source):
    data,meta,config,s=fixture(source);s['stock'][0]['qtyInWarehouse']=9
    with pytest.raises(ValueError,match='INTEGRITY'):apply(data,meta,config,s)
    s['stock']*=2;signed(s)
    with pytest.raises(ValueError,match='STOCK_SCOPE'):apply(data,meta,config,s)


def test_future_lineage_archives_keep_reporting_unit_but_not_personnel():
    from blacklake.lineage_probe import OUTPUT_FIELDS
    assert {'stockUnit','productName'}<=OUTPUT_FIELDS
    assert not {'productionStaff','productionStaffId','approver','approverId','token'}&OUTPUT_FIELDS
