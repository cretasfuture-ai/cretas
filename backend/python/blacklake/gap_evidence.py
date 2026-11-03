"""Derive auditable gap findings, without substituting estimated prices into facts."""
import json
import re
from collections import Counter
from datetime import datetime,timezone
from .analytics import number
from .full_archive import digest
from .contracts import ENDPOINTS
from .sync import timestamp
from .full_verify import offline_context
from .secrets import load_local_secret,save_local_secret


EVIDENCE_SCHEMA='blacklake-gap-evidence/1'


def negative_verified(live):
    negative=live['negative_control']
    base={k:negative.get(k) for k in ('tenant_id','source_factory_id','run_id','snapshot_fingerprint','resource','endpoint','body','checked_at')}
    bound=all(negative.get(k)==live[k] for k in ('tenant_id','source_factory_id','run_id','snapshot_fingerprint'))
    times_valid=live['started_at']<=str(negative.get('checked_at',''))<=live['finished_at']
    return (bound and times_valid and negative.get('query_digest')==digest(base)
                       and base['resource']=='materials' and base['endpoint']==ENDPOINTS['materials']
                       and bool(re.fullmatch('CRETAS-NONEXISTENT-[a-f0-9]{10}',str((base['body'] or {}).get('projectCode',''))))
                       and ((negative.get('mode')=='response' and negative.get('empty') is True)
                            or (str(negative.get('code'))=='1014' and bool(re.search('工单不存在|未找到工单|不存在.*工单|工单.*不存在',str(negative.get('msg','')))))))


def campaign_complete(live):
    return (negative_verified(live) and not live['failures'] and len(live['materials'])==live['expected_projects']
            and len({p['project_id'] for p in live['materials']})==live['expected_projects']
            and all(p['dual_query_equal'] and p['snapshot_equal'] for p in live['materials']))


def derive(offline,live):
    if any(offline.get(k)!=live[k] for k in ('run_id','tenant_id','source_factory_id')):raise ValueError('GAP_RUN_MISMATCH')
    control_verified=negative_verified(live)
    verified=[]
    global_ids=set()
    for parent in live['materials']:
        if not (control_verified and parent['dual_query_equal'] and parent['snapshot_equal']):continue
        for row in parent['rows']:
            if row['id'] in global_ids:raise ValueError('MATERIAL_SOURCE_ID_REPEATED')
            global_ids.add(row['id'])
            verified.append({'projectId':parent['project_id'],'materialId':row['id']})
    fresh={(o['id'],r['id']):(o,r) for o in live['fresh_stock_orders'] for r in o.get('orderDetails',[])}
    incoming={}
    for order in live['fresh_stock_orders']:
        if order.get('orderType')==10:
            for detail in order.get('orderDetails',[]):
                incoming.setdefault(str(detail.get('productId')),[]).append({'order':order,'detail':detail})
    unresolved=[];zero_confirmed=0;changed=0;references=[];unit_price_zero=0;unit_price_missing=0;unit_price_changed=0
    for old in offline['unlinked']:
        o,r=old['order'],old['detail'];current=fresh.get((o['id'],r['id']))
        fields=('associatedBizOrderId','associatedBizOrderCode','associatedBizOrderType','originDetailId','originDetailInfo','originDetailDisplayInfo')
        order_fields=('originIdList','originCodeList')
        missing=current is not None and all(current[1].get(key) in (None,'',[]) for key in fields) and all(current[0].get(key) in (None,'',[]) for key in order_fields)
        unresolved.append({'orderCode':o.get('orderNo'),'material':r.get('productName'),'materialCode':r.get('productCode'),
                           'status':'source_still_has_no_link' if missing else 'source_changed_or_missing',
                           'sourceFieldsMissing':list(fields) if missing else [],
                           'checkedOrderFields':list(order_fields),'orderOriginType':current[0].get('originType') if current else None,
                           'detailOriginType':current[1].get('originType') if current else None,
                           'action':'请工厂核对原始领退料单对应工单；不按日期、物料名或数量猜测分摊'})
    for old in offline['zero_material_lines']:
        order,detail=old['order'],old['detail'];current=fresh.get((order['id'],detail['id']))
        if current and number(current[1].get('changedCostAmountSigned'))==0:zero_confirmed+=1
        else:changed+=1
        fresh_price=number(current[1].get('costUnitPrice')) if current else None
        if fresh_price==0:unit_price_zero+=1
        elif fresh_price is None:unit_price_missing+=1
        else:unit_price_changed+=1
        for candidate in incoming.get(str(detail.get('productId')),[]):
            io,ir=candidate['order'],candidate['detail']
            if str(io.get('orderStatus'))!='1' or not io.get('bizAt') or not order.get('bizAt') or ir.get('unitName')!=detail.get('unitName'):continue
            if timestamp(io['bizAt'],'Asia/Shanghai')>timestamp(order['bizAt'],'Asia/Shanghai'):continue
            for field in ('costUnitPrice','realCostUnitPrice','taxInclusiveUnitPrice'):
                price=number(ir.get(field))
                if price is not None and price>0:
                    references.append({'materialCode':detail.get('productCode'),'material':detail.get('productName'),
                                       'unit':detail.get('unitName'),'price':float(price),'field':field,
                                       'sourceOrder':io.get('orderNo'),'date':io.get('bizAt'),
                                       'zeroOrder':order.get('orderNo'),'sourceBizType':io.get('bizType'),
                                       'usage':'历史入库参考价，仅作为估值排查线索，不回填实际成本'})
                    break
    current_references=[]
    zero_keys={(str(r['detail'].get('productId')),r['detail'].get('productCode'),r['detail'].get('unitName')) for r in offline['zero_material_lines']}
    unit_mismatches=0
    for product in live['fresh_products']:
        matching=[key for key in zero_keys if key[:2]==(str(product['id']),product.get('productCode'))]
        if matching and not any(key[2]==product.get('unit') for key in matching):unit_mismatches+=1
        if any(key[2]==product.get('unit') for key in matching) and (number(product.get('movingWeightedAverageCost')) or 0)>0:
            current_references.append({'materialCode':product['productCode'],'material':product.get('productName'),
                                       'unit':product.get('unit'),'price':float(number(product['movingWeightedAverageCost'])),
                                       'field':'movingWeightedAverageCost','sourceOrder':None,'date':live['finished_at'],
                                       'usage':'当前产品移动平均价线索；不是历史领料时点成本，禁止回填'})
    unique={json.dumps(r,sort_keys=True,ensure_ascii=False):r for r in references}
    references=list(unique.values())
    expected=live['expected_projects']
    complete=campaign_complete(live)
    if not complete:verified=[]
    return {'schema':EVIDENCE_SCHEMA,'status':'complete' if complete else 'incomplete','runId':live['run_id'],'tenantId':live['tenant_id'],'sourceFactoryId':live['source_factory_id'],
            'snapshotFingerprint':live['snapshot_fingerprint'],'checkedAt':live['finished_at'],
            'verifiedMaterialParents':verified,'parentQueryMode':'id_and_code_equal_plus_missing_parent_rejected',
            'negativeControlVerified':control_verified,'unresolvedLinks':unresolved,
            'priceReferences':references,'currentPriceClues':current_references,
            'summary':{'materialsQueryVerified':len(verified),'materialProjectsChecked':len(live['materials']),
                       'materialProjectsExpected':expected,'failedProjects':len(live['failures']),
                       'unlinkedStillMissing':sum(r['status']=='source_still_has_no_link' for r in unresolved),
                       'zeroAmountsReconfirmed':zero_confirmed,'zeroAmountRowsChangedOrMissing':changed,
                       'productsWithHistoricalPriceReferences':len({r['materialCode'] for r in references}),
                       'priceReferenceRows':len(references),'productsWithCurrentPriceClues':len(current_references),
                       'currentPriceUnitMismatches':unit_mismatches,
                       'zeroMovementUnitPrices':unit_price_zero,'movementUnitPriceMissing':unit_price_missing,
                       'movementUnitPriceChanged':unit_price_changed,
                       'freshStockOrders':len(live['fresh_stock_orders']),
                       'sourceBusinessMutations':0},
            'limitations':['查询证据验证父级过滤，不在原始响应用料上制造projectId字段',
                           '历史入库参考价不是该工单实际领料成本；不替换零值',
                           '新出入库单与原归档分时点披露，不混入旧时点金额']}


def main():
    directory,store=offline_context()
    try:
        run=load_local_secret(directory/'full-run.dpapi')
        if run['source']!=digest(store.scope):raise ValueError('GAP_SOURCE_BINDING_MISMATCH')
        report=derive(load_local_secret(directory/'gap-offline.dpapi'),load_local_secret(directory/'gap-live.dpapi'))
        if (report['tenantId'],report['sourceFactoryId'],report['runId'])!=(*store.scope,run['run_id']):
            raise ValueError('GAP_EVIDENCE_SCOPE_MISMATCH')
        save_local_secret(directory/'gap-evidence.dpapi',report)
        settings_path=directory/'smartbi-test.dpapi'
        if settings_path.exists():
            settings=load_local_secret(settings_path)
            if (settings['tenant_id'],settings['source_factory_id'],settings['run_id'])!=(report['tenantId'],report['sourceFactoryId'],report['runId']):
                raise ValueError('GAP_EVIDENCE_TEST_SCOPE_MISMATCH')
            settings['gap_evidence_file']=str((directory/'gap-evidence.dpapi').resolve())
            save_local_secret(settings_path,settings)
        print(json.dumps(report['summary'],ensure_ascii=False))
    finally:store.connection.close()


if __name__=='__main__':main()
