import copy,json
from types import SimpleNamespace
import asyncio
import pytest
from fastapi import HTTPException

from blacklake.analytics import build
from blacklake.traceability import mapped,subset,configured_report
from tests.test_blacklake_analytics import source


def report(source):
    raw=copy.deepcopy(source);raw['stock_order'][0]['id']=90
    raw['stock_order'][0]['orderDetails'][0]['originDetailId']=8
    raw['materials']=[{'id':8,'productCode':'R1','feedProcessId':3}]
    raw['project'][0]['customFieldValues']=[{'name':'单片重量','value':'0.25'}]
    return mapped(raw,build(raw,{'run_id':'S','finished_at':None,'stock_pairs_not_returned':None}),('T','F'),'S','synthetic',
        [{'projectId':1,'dualQueryEqual':True,'materialIds':[8]}],True)


def test_blacklake_origin_detail_is_a_material_row_not_a_stock_receipt(source):
    value=report(source)
    row=value['workOrders'][0]
    assert row['businessLinks'][0]['materialRowId']==8 and row['businessLinks'][0]['processId']==3
    assert row['physicalBatchChainVerified'] is False
    assert row['providedClues'][0]['value']=='0.25' and row['providedClues'][0]['unitAndBusinessMeaningVerified'] is False
    assert value['sharedBasisChecks'][0]['affectedWorkOrderCodes']==['P1']


def test_period_subset_does_not_keep_other_orders_or_their_shared_basis_requests(source):
    value=subset(report(source),[], 'period-empty')
    assert value['workOrders']==[] and value['actions']==[] and value['sharedBasisChecks']==[]
    assert value['summary']['materialOriginLinks']==0 and value['fieldClueSummary']=={}


def test_latest_file_is_bound_to_tenant_factory_and_original_anchor(source,tmp_path,monkeypatch):
    value=report(source);path=tmp_path/'report.json';path.write_text(json.dumps(value),encoding='utf8')
    monkeypatch.setenv('BLACKLAKE_COST_TRACEABILITY_FILE',str(path))
    assert configured_report({'tenant_id':'T','source_factory_id':'F','run_id':'S'})['scope']['snapshotId']=='S'
    for field in ('tenant_id','source_factory_id','run_id'):
        config={'tenant_id':'T','source_factory_id':'F','run_id':'S'};config[field]='OTHER'
        with pytest.raises(ValueError,match='SCOPE_MISMATCH'):configured_report(config)


def test_endpoint_preserves_cost_authority_before_reading_file(monkeypatch):
    from blacklake import smartbi_api as api,traceability
    monkeypatch.setenv('BLACKLAKE_SMARTBI_CONFIG','synthetic')
    monkeypatch.setattr(api,'settings',lambda path:{'tenant_id':'T','source_factory_id':'F','run_id':'S'})
    reads=[]
    monkeypatch.setattr(traceability,'configured_report',lambda config:reads.append(config) or {'schema':'synthetic'})
    async def denied(*args):raise HTTPException(403,'成本授权未确认')
    monkeypatch.setattr(api,'cost_authority',denied)
    request=SimpleNamespace(state=SimpleNamespace(auth_method='jwt',factory_id='T',role='factory_super_admin'))
    with pytest.raises(HTTPException) as error:asyncio.run(api.cost_traceability(request))
    assert error.value.status_code==403 and not reads
    async def allowed(*args):pass
    monkeypatch.setattr(api,'cost_authority',allowed)
    assert asyncio.run(api.cost_traceability(request))['success'] and len(reads)==1
    request.state.factory_id='OTHER'
    with pytest.raises(HTTPException):asyncio.run(api.cost_traceability(request))
    assert len(reads)==1


def test_latest_file_rejects_actions_from_another_order(source,tmp_path,monkeypatch):
    value=report(source);value['actions'][0]['workOrderId']='OTHER'
    path=tmp_path/'report.json';path.write_text(json.dumps(value),encoding='utf8')
    monkeypatch.setenv('BLACKLAKE_COST_TRACEABILITY_FILE',str(path))
    with pytest.raises(ValueError,match='ACTION_SCOPE_INVALID'):
        configured_report({'tenant_id':'T','source_factory_id':'F','run_id':'S'})
