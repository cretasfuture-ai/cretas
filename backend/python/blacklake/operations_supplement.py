"""Validate independently timed unit/balance evidence without rewriting history."""
from copy import deepcopy
import json
from pathlib import Path
from .full_archive import digest
from .period_projection import instant


def apply(data, metadata, config, supplement):
    s=supplement
    if (s.get('tenant_id'),str(s.get('source_factory_id')),s.get('baselineRunId'),s.get('parentObservationId')) != (config['tenant_id'],str(config['source_factory_id']),config['run_id'],metadata['run_id']):
        raise ValueError('OPERATIONS_SUPPLEMENT_SCOPE_MISMATCH')
    if (s.get('schema')!='blacklake-operations-supplement/1' or s.get('complete') is not True
            or s.get('factoryVerified') is not True or s.get('sourceBusinessWrites')!=0 or s.get('failures')
            or s.get('fingerprint')!=digest({k:v for k,v in s.items() if k!='fingerprint'})
            or s.get('parentOutputFingerprint')!=digest(data['output'])):
        raise ValueError('OPERATIONS_SUPPLEMENT_INTEGRITY_FAILED')
    begin,end,previous=instant(s.get('startedAt')),instant(s.get('finishedAt')),instant(metadata.get('finished_at'))
    if None in (begin,end,previous) or not previous<=begin<=end:raise ValueError('OPERATIONS_SUPPLEMENT_TIME_INVALID')
    result=deepcopy(data);meta=deepcopy(metadata)
    current={str(r['id']):r for r in s['outputs']}
    if len(current)!=len(s['outputs']):raise ValueError('OPERATIONS_SUPPLEMENT_OUTPUT_DUPLICATE')
    recovered=0;unresolved=[]
    for old in result['output']:
        fresh=current.get(str(old['id']))
        # The original numeric/status/time facts must still match; only absent
        # wire names/units may be filled. Changed facts require a complete recheck.
        if fresh is None or (old.get('stockUnit') and old.get('stockUnit')!=fresh.get('stockUnit')) or any(fresh.get(k)!=v for k,v in old.items() if k not in ('stockUnit','productName')):
            unresolved.append({'outputId':old['id'],'projectId':old.get('projectId'),'reason':'source_missing_or_changed'})
            continue
        if not old.get('stockUnit') and isinstance(fresh.get('stockUnit'),str) and fresh['stockUnit'].strip():
            old['stockUnit']=fresh['stockUnit'];recovered+=1
        if fresh.get('productName'):old['productName']=fresh['productName']
        if not old.get('stockUnit'):unresolved.append({'outputId':old['id'],'projectId':old.get('projectId'),'reason':'source_unit_not_returned'})
    product_ids={str(r['id']) for r in s['products']};warehouse_ids={str(r['id']) for r in s['warehouses']}
    if not product_ids or not warehouse_ids:raise ValueError('OPERATIONS_SUPPLEMENT_DEFINITIONS_REQUIRED')
    if len(product_ids)!=len(s['products']) or len(warehouse_ids)!=len(s['warehouses']):raise ValueError('OPERATIONS_SUPPLEMENT_DEFINITION_DUPLICATE')
    pairs=set()
    for row in s['stock']:
        pair=(str(row.get('productId')),str(row.get('warehouseId')))
        if pair[0] not in product_ids or pair[1] not in warehouse_ids or pair in pairs:raise ValueError('OPERATIONS_SUPPLEMENT_STOCK_SCOPE_MISMATCH')
        pairs.add(pair)
    total=len(product_ids)*len(warehouse_ids)
    if total!=s.get('stockPairsQueried') or total-len(pairs)!=s.get('stockPairsNotReturned'):raise ValueError('OPERATIONS_SUPPLEMENT_STOCK_COUNT_MISMATCH')
    prior={'at':meta.get('stock_finished_at',meta['finished_at']),'records':deepcopy(result.get('stock',[])),
           'pairsNotReturned':meta.get('stock_pairs_not_returned')}
    latest={'at':s['finishedAt'],'records':deepcopy(s['stock']),'pairsNotReturned':s['stockPairsNotReturned']}
    meta.update(stock_snapshots=[prior,latest],stock_finished_at=latest['at'],stock_latest_finished_at=latest['at'],stock_pairs_not_returned=latest['pairsNotReturned'])
    result['stock']=deepcopy(latest['records'])
    meta['run_id']=meta['run_id']+':supplement:'+s['id']+':'+s['fingerprint'][:20]
    meta['operational_source']={**meta.get('operational_source',{}),'inventoryCheckedAt':s['finishedAt'],'outputUnitsCheckedAt':s['finishedAt']}
    meta['operations_supplement']={'checkedAt':s['finishedAt'],'reportingUnitsRecovered':recovered,'outputRecords':len(result['output']),
        'unresolvedOutputs':unresolved,'inventoryRecords':len(s['stock']),'stockPairsQueried':total,'stockPairsNotReturned':s['stockPairsNotReturned'],
        'sourceBusinessWrites':0,'requests':[
        {'code':'stock_unreturned','status':'queried_no_records','missing':f'{s["stockPairsNotReturned"]} 组产品与仓库未返回余额；不等于零库存或缺货。','owner':'仓库负责人 / 数据管理员','action':'先确认停用、从未有库存和查询可见范围，再提供需要盘点的物料与仓库余额。','afterProvided':'区分正常无记录、资料缺失和需要补货的物料。'},
        {'code':'period_stock_boundary','status':'requires_historical_records','missing':'历史期间同一边界的期初、期末库存表及盘点、调整依据；已有流水的期间与审核完整性仍需核实。','owner':'仓库 / 财务负责人','action':'提供期初期末库存及盘点依据，结合系统已有出入库、调拨记录逐笔核实。','afterProvided':'核对期初＋入库－出库＋调整＝期末，判断差异在哪一笔；当前余额不能倒推历史期末，已有流水不等于没有流水。'}]}
    if unresolved:meta['operations_supplement']['requests'].insert(0,{'code':'reporting_units','status':'source_missing_or_changed','missing':f'{len(unresolved)} 条报工未能安全补齐单位。','owner':'车间报工负责人 / 数据管理员','action':'按报工编号核对原单单位与修订后的数量、审批状态。','afterProvided':'恢复同产品、同工序、同单位的数量及良品率核对。'})
    return result,meta


def load(data,metadata,config,path):
    path=Path(path)
    if not path.is_absolute():raise ValueError('OPERATIONS_SUPPLEMENT_ABSOLUTE_PATH_REQUIRED')
    return apply(data,metadata,config,json.loads(path.read_text(encoding='utf8')))
