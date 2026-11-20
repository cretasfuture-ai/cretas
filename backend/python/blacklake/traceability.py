"""Map observed Blacklake fields to the shared cost traceability contract."""
from collections import Counter,defaultdict
import json
import os
from pathlib import Path

from .analytics import build
from .full_archive import digest


def mapped(data,analysis,scope,snapshot_id,checked_at,parent_controls=(),negative_control=False,query_status=None):
    from smartbi.services.cost_traceability import diagnose
    projects={str(p['id']):p for p in data.get('project',[])}
    by_code={p['projectCode']:p for p in projects.values()}
    if len(by_code)!=len(projects):raise ValueError('TRACE_PROJECT_CODE_AMBIGUOUS')
    known_cost={str(p['projectId']):p for p in analysis['cost']['projects']}
    products_by_code=defaultdict(list)
    for product in data.get('product',[]):products_by_code[product.get('productCode')].append(product)
    normalized_orders=[]
    for pid,p in projects.items():
        cost=known_cost[pid]
        clues=[]
        owners=[('project',p)]
        if len(products_by_code[p.get('productCode')])==1:owners.append(('product',products_by_code[p.get('productCode')][0]))
        for resource,row in owners:
            for field in row.get('customFieldValues') or []:
                if field.get('name') in ('原材价格','领料重量','单片重量','计算初成数量','最小单位重量','产品类型') and field.get('value') not in (None,'',[]):
                    clues.append({'label':field['name'],'value':field['value'],'sourceResource':resource,
                        'sourceId':row['id'],'unitAndBusinessMeaningVerified':False,'usage':'已有业务录入线索；未确认单位、实际/计划及适用范围，不替代领料实际成本或批次分配。'})
        normalized_orders.append({'id':p['id'],'code':p['projectCode'],'product':p.get('productName'),
            'productCode':p.get('productCode'),'unit':p.get('projectUnit'),'goodQuantity':cost['finishedGood'],
            'completed':str(p.get('status'))=='20','directCostAvailable':cost['unitCostAvailable'],
            'directCostBlockingReasons':cost['issues'],
            'identifiedUnitCost':cost['unitIdentifiedDirectCost'],'providedClues':clues})
    parents={};conflicted=set()
    for row in parent_controls:
        if not negative_control or row.get('dualQueryEqual') is not True:continue
        for identity in row['materialIds']:
            identity=str(identity)
            if identity in parents and str(parents[identity])!=str(row['projectId']):conflicted.add(identity)
            parents[identity]=row['projectId']
    if conflicted:raise ValueError('TRACE_MATERIAL_PARENT_AMBIGUOUS')
    process_names={str(p['id']):p.get('name') or p.get('code') for p in data.get('process',[])}
    materials=[{'id':r['id'],'projectId':parents.get(str(r['id'])),'productCode':r.get('productCode'),
        'feedProcessId':r.get('feedProcessId'),'feedProcessCode':r.get('feedProcessCode'),
        'feedProcessName':process_names.get(str(r.get('feedProcessId'))),
        'parentVerified':str(r['id']) in parents} for r in data.get('materials',[])]
    names=defaultdict(set)
    for p in projects.values():
        if p.get('productCode') and p.get('productName'):names[p['productCode']].add(p['productName'])
    for o in data.get('stock_order',[]):
        for d in o.get('orderDetails',[]):
            if d.get('productCode') and d.get('productName'):names[d['productCode']].add(d['productName'])
    movements=[];outside=0
    for order in data.get('stock_order',[]):
        kind={'30':'return','32':'issue','13':'finished_receipt'}.get(str(order.get('bizType')))
        if not kind:continue
        for d in order.get('orderDetails',[]):
            pid=str(d.get('associatedBizOrderId'));code=d.get('associatedBizOrderCode')
            # Do not label newer, outside-cohort orders as missing associations.
            if pid not in projects and code not in by_code and d.get('associatedBizOrderId') and code:
                outside+=1;continue
            p=projects.get(pid)
            parent_ok=bool(p and p['projectCode']==code and str(d.get('associatedBizOrderType'))=='10')
            quantity=d.get('changeQty');amount=d.get('changedCostAmountSigned')
            from smartbi.services.cost_traceability import number
            q=number(quantity);v=number(amount)
            movements.append({'id':f"{order['id']}:{d['id']}",'sourceDetailId':d['id'],
                'documentCode':order.get('orderNo'),'projectId':d.get('associatedBizOrderId'),'projectCode':code,
                'projectRelationVerified':parent_ok,'kind':kind,'posted':str(order.get('orderStatus'))=='1',
                'productCode':d.get('productCode'),'productName':d.get('productName'),'unit':d.get('unitName'),
                'quantity':str(-q if kind in ('issue','return') else q) if q is not None else None,
                'recognizedCost':str(-v) if v is not None else None,'unitPrice':d.get('costUnitPrice'),
                # This field points to a work-order material line, not a stock batch.
                'originMaterialId':d.get('originDetailId') if kind in ('issue','return') else None,
                'originKind':{0:'manufactured',1:'purchased'}.get(d.get('originType'),'unknown'),
                'identityConflict':len(names[d.get('productCode')])>1,
                'batchId':None})
    tasks=[{'id':t['id'],'projectId':t.get('projectId'),'processId':t.get('processId'),
        'salary':t.get('sumOutputSalary'),'durationSeconds':t.get('outputDurationForSecond')} for t in data.get('task',[])]
    outputs=[{'id':r['id'],'projectId':r.get('projectId'),'taskId':r.get('taskId'),'processId':r.get('processId')} for r in data.get('output',[])]
    snapshot={'scope':{'tenantId':scope[0],'sourceFactoryId':str(scope[1]),'snapshotId':snapshot_id},
        'checkedAt':checked_at,'orders':normalized_orders,'movements':movements,'materials':materials,'tasks':tasks,'outputs':outputs,
        'queryStatus':query_status or {r:('complete' if r in data else 'not_queried') for r in ('project','task','output','stock_order','materials','product')}}
    result=diagnose(snapshot)
    result['analysisScope']=digest(scope)
    documents={r['id']:r['documentCode'] for r in movements}
    for action in result['actions']:
        action['sourceDocuments']=list(dict.fromkeys(documents[k] for k in action['affectedRecordIds'] if k in documents))
    result['excludedOutsideCohortMovements']=outside
    shared=defaultdict(list)
    for row in result['workOrders']:
        for clue in row['providedClues']:
            if clue['label']!='产品类型':shared[clue['label']].append(row['workOrderCode'])
    result['sharedBasisChecks']=[{'field':name,'affectedWorkOrderCodes':list(dict.fromkeys(codes)),
        'responsibleRole':'财务/生产负责人' if name=='原材价格' else '主数据/生产负责人',
        'request':'已有字段值，先统一确认单位、实际/计划属性、录入规则及历史生效范围；价格还需确认币种和计价时点。',
        'afterProvided':'可复用已确认定义核对多张工单的规格换算或参考成本；仍须实际批次分配，不能直接替代库存领料成本。',
        'sourceStatus':'provided_but_business_meaning_unverified'} for name,codes in shared.items()]
    result['fieldClueSummary']=dict(Counter(c['label'] for r in result['workOrders'] for c in r['providedClues']))
    result['baselineAnchor']=snapshot_id
    return result


def from_live(observation,bom=None):
    if bom and (bom.get('parentProbeId')!=observation['probeId'] or bom.get('baselineRunId')!=observation['run_id']
                or (bom.get('tenantId'),str(bom.get('sourceFactoryId')))!=(observation['tenant_id'],str(observation['source_factory_id']))):
        raise ValueError('TRACE_BOM_SUPPLEMENT_SCOPE_MISMATCH')
    data={**observation['data']}
    if bom:data['bom']=bom['rows']
    status={r:'complete' for r in data}
    for failure in observation['failures']:status[failure['resource']]='query_failed'
    if bom:status['bom']='complete' if bom.get('complete') else 'query_failed'
    if not observation.get('negativeControlVerified') or any(not p['dualQueryEqual'] for p in observation['parentControls']):
        status['materials']='parent_filter_not_verified'
    analysis=build(data,{'run_id':observation['probeId'],'finished_at':observation['finished_at'],'stock_pairs_not_returned':None})
    result=mapped(data,analysis,(observation['tenant_id'],observation['source_factory_id']),observation['probeId'],
        observation['finished_at'],observation['parentControls'],observation['negativeControlVerified'],status)
    result['baselineAnchor']=observation['run_id']
    result['observationFingerprint']=observation['observationFingerprint']
    result['observationWindow']={'start':observation['started_at'],'end':observation['finished_at'],
        'bomCheckedAt':bom.get('finishedAt') if bom else None,'atomicSnapshot':False}
    result['sourceProbe']={'queries':observation['requests']+(bom.get('queries',0) if bom else 0),
        'allRequiredQueriesComplete':all(s=='complete' for s in status.values()),
        'newOrdersOutsideOriginalCohort':observation['newProjectsOutsideOriginalCohort'],
        'originalOrdersNotReturned':observation['originalProjectsNotReturned'],
        'correctedBomRequest':bool(bom and bom.get('complete'))}
    return result


def subset(report,project_ids,snapshot_id):
    """Keep full-life order evidence; never total upstream/downstream costs."""
    import copy
    value=copy.deepcopy(report);allowed={str(p) for p in project_ids}
    value['workOrders']=[r for r in value['workOrders'] if str(r['workOrderId']) in allowed]
    value['actions']=[r for r in value['actions'] if str(r['workOrderId']) in allowed]
    value['scope']['snapshotId']=snapshot_id
    value['summary'].update(workOrders=len(value['workOrders']),
        ordersWithDirectCost=sum(r['directCostAvailable'] for r in value['workOrders']),
        ordersNeedingData=sum(bool(r['needs']) for r in value['workOrders']),
        materialOriginLinks=sum(len(r['businessLinks']) for r in value['workOrders']),
        materialProcessLinks=sum(b['processId'] is not None for r in value['workOrders'] for b in r['businessLinks']),
        actionCounts=dict(Counter(n['code'] for n in value['actions'])))
    value['unassignedMovementIds']=[]
    codes={r['workOrderCode'] for r in value['workOrders']}
    for check in value.get('sharedBasisChecks',[]):check['affectedWorkOrderCodes']=[c for c in check['affectedWorkOrderCodes'] if c in codes]
    value['sharedBasisChecks']=[c for c in value.get('sharedBasisChecks',[]) if c['affectedWorkOrderCodes']]
    value['fieldClueSummary']=dict(Counter(c['label'] for r in value['workOrders'] for c in r.get('providedClues',[])))
    return value


def configured_report(config):
    configured=os.getenv('BLACKLAKE_COST_TRACEABILITY_FILE')
    if not configured:return None
    path=Path(configured)
    if not path.is_absolute():raise ValueError('TRACE_ABSOLUTE_REPORT_PATH_REQUIRED')
    report=json.loads(path.read_text(encoding='utf8'))
    scope=report.get('scope',{})
    if (scope.get('tenantId'),str(scope.get('sourceFactoryId')),report.get('baselineAnchor'))!=(config['tenant_id'],str(config['source_factory_id']),config['run_id']):
        raise ValueError('TRACE_REPORT_SCOPE_MISMATCH')
    if report.get('schema')!='smartbi-cost-traceability/1':raise ValueError('TRACE_SCHEMA_UNSUPPORTED')
    orders=report.get('workOrders');actions=report.get('actions')
    if not isinstance(orders,list) or not isinstance(actions,list) or not isinstance(report.get('summary'),dict):
        raise ValueError('TRACE_REPORT_STRUCTURE_INVALID')
    order_ids={str(r.get('workOrderId')) for r in orders if isinstance(r,dict)}
    if len(order_ids)!=len(orders) or 'None' in order_ids or report['summary'].get('workOrders')!=len(orders):
        raise ValueError('TRACE_REPORT_ORDER_INTEGRITY_INVALID')
    if any(not isinstance(r,dict) or str(r.get('workOrderId')) not in order_ids for r in actions):
        raise ValueError('TRACE_REPORT_ACTION_SCOPE_INVALID')
    if any(not isinstance(r.get('needs'),list) or not isinstance(r.get('businessLinks'),list) for r in orders):
        raise ValueError('TRACE_REPORT_STRUCTURE_INVALID')
    return report
