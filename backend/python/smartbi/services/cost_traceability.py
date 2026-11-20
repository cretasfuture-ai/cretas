"""Source-independent work-order cost evidence and actionable missing-data plans.

Explicit source relationships are required. Product/date proximity is never a
batch allocation, and a downstream carrying value is never added to upstream
manufacturing costs. No source writes, price imputation or model calls.
"""
from collections import Counter,defaultdict
from decimal import Decimal,InvalidOperation
import hashlib
import json

ACTION_SECTIONS={'MATERIAL_DOCUMENTS':'material','MATERIAL_VALUATION':'material','MATERIAL_DOCUMENT_IDENTITY':'material',
    'MATERIAL_AMOUNT_CHECK':'material',
    'LABOR_BASIS':'labor','FINAL_GOOD_BASIS':'finished','FINISHED_RECEIPT_LINK':'finished','FINISHED_RECONCILIATION':'finished',
    'INPUT_PROCESS_LINK':'production','OUTPUT_TASK_LINK':'production','INPUT_BATCH_ALLOCATION':'inventory',
    'AWAITING_COMPLETION':'coverage','DIRECT_COST_BLOCKER':'coverage','MATERIAL_IDENTITY':'product_cost'}


def number(value):
    if value is None or isinstance(value,bool):return None
    try:result=Decimal(str(value))
    except (ValueError,TypeError,InvalidOperation):return None
    return result if result.is_finite() else None


def key(value):return None if value is None or isinstance(value,bool) or value=='' else str(value)
def unit(value):return str(value or '').casefold()


def unique(rows):
    result={}
    for row in rows:
        identity=key(row.get('id'))
        if not identity or identity in result:raise ValueError('TRACE_ID_MISSING_OR_DUPLICATE')
        result[identity]=row
    return result


def allocation_evidence(issues,receipts,allocations):
    """Validate explicit split allocations, including shared receipt capacity."""
    issues=unique(issues);receipts=unique(receipts);edges=[];errors=[]
    used_issue=defaultdict(Decimal);used_receipt=defaultdict(Decimal);seen=set()
    for row in allocations:
        a,b=key(row.get('issueId')),key(row.get('receiptId'));q=number(row.get('quantity'))
        issue,receipt=issues.get(a),receipts.get(b)
        identity=(a,b)
        valid=(issue and receipt and identity not in seen and q is not None and q>0
               and number(issue.get('quantity')) is not None and number(receipt.get('quantity')) is not None
               and issue.get('productCode') and issue.get('productCode')==receipt.get('productCode')
               and unit(issue.get('unit')) and unit(issue.get('unit'))==unit(receipt.get('unit'))
               and issue.get('batchId') and issue.get('batchId')==receipt.get('batchId')
               and issue.get('posted') is True and receipt.get('posted') is True)
        if not valid:errors.append({'issueId':a,'receiptId':b,'code':'ALLOCATION_REFERENCE_OR_BASIS_INVALID'});continue
        seen.add(identity);used_issue[a]+=q;used_receipt[b]+=q
        edges.append({'issueId':a,'receiptId':b,'quantity':str(q)})
    bad_issues={a for a,q in used_issue.items() if q!=number(issues[a].get('quantity'))}
    bad_receipts={b for b,q in used_receipt.items() if q>number(receipts[b].get('quantity'))}
    for a in sorted(bad_issues):errors.append({'issueId':a,'code':'ISSUE_SPLIT_NOT_RECONCILED'})
    for b in sorted(bad_receipts):errors.append({'receiptId':b,'code':'RECEIPT_OVERALLOCATED'})
    # An overdrawn shared receipt invalidates every allocation using it.
    valid_edges=[e for e in edges if e['issueId'] not in bad_issues and e['receiptId'] not in bad_receipts]
    return {'edges':valid_edges,'errors':errors,'costsRolledUp':False}


def diagnose(snapshot):
    scope=snapshot.get('scope') or {}
    if not all(scope.get(k) for k in ('tenantId','sourceFactoryId','snapshotId')):
        raise ValueError('TRACE_SCOPE_REQUIRED')
    for resource in ('orders','materials','tasks','movements','outputs'):
        for row in snapshot.get(resource,[]):
            if any(row.get(k) is not None and str(row[k])!=str(scope[k]) for k in ('tenantId','sourceFactoryId')):
                raise ValueError('TRACE_ROW_SCOPE_MISMATCH')
    orders=unique(snapshot.get('orders',[]));materials=unique(snapshot.get('materials',[]))
    tasks=unique(snapshot.get('tasks',[]));movements=unique(snapshot.get('movements',[]))
    outputs=unique(snapshot.get('outputs',[]));query_status=snapshot.get('queryStatus',{})
    by_order=defaultdict(list);task_by_order=defaultdict(list);unassigned=[]
    for row in movements.values():
        parent=orders.get(key(row.get('projectId')))
        if (parent and row.get('projectCode')==parent['code'] and row.get('projectRelationVerified') is True):
            if row.get('posted') is True:by_order[key(parent['id'])].append(row)
        elif row.get('kind') in ('issue','return','finished_receipt'):unassigned.append(row['id'])
    for row in tasks.values():
        if key(row.get('projectId')) in orders:task_by_order[key(row['projectId'])].append(row)
    results=[];all_needs=[];links=[]
    for pid,p in orders.items():
        rows=by_order[pid];inputs=[r for r in rows if r['kind'] in ('issue','return')]
        receipts=[r for r in rows if r['kind']=='finished_receipt' and r.get('productCode')==p.get('productCode')]
        steps=task_by_order[pid];needs={};order_links=[]
        def need(code,missing,owner,benefit,resource,records=(),state='missing_in_queried_response',priority='high'):
            if query_status.get(resource)!='complete':
                state='query_incomplete';owner='集成负责人';missing='该接口本轮未查完整，先恢复查询，不能据此认定源系统缺数据。'+missing
            elif p.get('completed') is not True:
                state='pending_completion';priority='normal'
            if code not in needs:
                identity=hashlib.sha256(json.dumps([scope,pid,code],sort_keys=True).encode()).hexdigest()[:20]
                needs[code]={'id':identity,'code':code,'workOrderId':p['id'],'workOrderCode':p['code'],
                    'missingData':missing,'responsibleRole':owner,'afterProvided':benefit,'sourceStatus':state,
                    'resource':resource,'affectedRecordIds':[],'priority':priority}
                needs[code]['impactType']=('batch_trace' if code=='INPUT_BATCH_ALLOCATION' else
                    'process_cost' if code=='INPUT_PROCESS_LINK' else 'production_pending' if code=='AWAITING_COMPLETION' else
                    'identity_check' if code=='MATERIAL_IDENTITY' else 'quantity_check' if code in ('FINISHED_RECEIPT_LINK','FINISHED_RECONCILIATION','OUTPUT_TASK_LINK') else 'unit_cost')
            needs[code]['affectedRecordIds']=list(dict.fromkeys(needs[code]['affectedRecordIds']+list(records)))
        if p.get('completed') is not True:
            need('AWAITING_COMPLETION','工单完工确认和最终审批良品量。','生产负责人',
                 '完工后才能按最终良品计算成品单位成本；未完工不等于漏数据。','project',state='pending_completion',priority='normal')
        q=number(p.get('goodQuantity'))
        if q is None or q<=0 or not p.get('unit'):
            need('FINAL_GOOD_BASIS','工单最终良品量、成品单位、末道工序审批及对应入库记录。','生产/仓库负责人',
                 '确定单位成本分母，区分未审批、空工单和数量记录不一致；不以计划量代替。','project')
        if not inputs:
            need('MATERIAL_DOCUMENTS','该工单真实领料、退料单及工单ID和编码关联。','仓库/生产负责人',
                 '把实际材料净消耗和账面金额归到本单，避免按物料名、日期猜分摊。','stock_order')
        conflicting=[r for r in movements.values() if r.get('kind') in ('issue','return')
                     and r.get('posted') is True and r.get('projectRelationVerified') is not True
                     and (key(r.get('projectId'))==pid or r.get('projectCode')==p['code'])]
        if conflicting:
            need('MATERIAL_DOCUMENT_IDENTITY','关联到本单的领退料工单ID与编码存在缺失或冲突，需核对原单真实归属。','仓库/生产负责人',
                 '确认这笔耗料到底属于哪张工单；冲突解除前不猜配材料成本。','stock_order',[r['id'] for r in conflicting],state='source_identity_conflict')
        zero_or_unknown=[];process_unmapped=[];batch_unknown=[];identity_conflicts=[];upstream_candidates=[];sign_conflicts=[]
        for r in inputs:
            amount=number(r.get('recognizedCost'));quantity=number(r.get('quantity'));price=number(r.get('unitPrice'))
            if quantity is None or not r.get('unit') or (quantity!=0 and (amount in (None,Decimal(0)) or price in (None,Decimal(0)))):
                zero_or_unknown.append(r['id'])
            if ((price is not None and price<0) or (quantity is not None and amount is not None and quantity*amount<0)
                    or (quantity==0 and amount not in (None,Decimal(0)))):sign_conflicts.append(r['id'])
            source=materials.get(key(r.get('originMaterialId')))
            valid=(source and source.get('parentVerified') is True and key(source.get('projectId'))==pid
                   and source.get('productCode')==r.get('productCode'))
            if valid:
                relation={'workOrderId':p['id'],'movementId':r['id'],'materialRowId':source['id'],
                    'kind':'explicit_work_order_material_line','processId':None,
                    'sourceDocumentCode':r.get('documentCode'),'materialName':r.get('productName'),'feedProcessName':None}
                candidates=[t for t in steps if key(t.get('processId')) and key(t.get('processId'))==key(source.get('feedProcessId'))]
                if source.get('feedProcessId') is not None and candidates:
                    relation.update(processId=source['feedProcessId'],taskIds=[t['id'] for t in candidates],feedProcessName=source.get('feedProcessName'))
                else:process_unmapped.append(r['id'])
                order_links.append(relation)
            else:process_unmapped.append(r['id'])
            if r.get('identityConflict') is True:identity_conflicts.append(r['id'])
            if r['kind']=='issue':
                batch_unknown.append(r['id'])
                if r.get('originKind')=='manufactured':
                    candidates=[o for oid,o in orders.items() if oid!=pid and o.get('productCode')==r.get('productCode')
                                and unit(o.get('unit'))==unit(r.get('unit'))]
                    upstream_candidates.append({'movementId':r['id'],'candidateCount':len(candidates),
                        'candidateOrders':[{'workOrderId':o['id'],'workOrderCode':o['code']} for o in candidates[:6]],
                        'status':'product_and_unit_candidates_only','actualBatchVerified':False})
        if zero_or_unknown:
            need('MATERIAL_VALUATION','清单所列材料的实际领退料数量、单位、历史计价依据及零金额原因。','财务/仓库负责人',
                 '判断是合理零值、未计价还是漏记，并核算本单材料净额；当前均价只作线索，不回填实际成本。',
                 'stock_order',zero_or_unknown,state='source_zero_or_missing_unverified')
        if sign_conflicts:
            need('MATERIAL_AMOUNT_CHECK','原领退料的数量、成本单价、金额符号与冲销/退料归属；当前数值不一致，不能按正常低成本使用。',
                 '财务/仓库负责人','核实负单价、冲销或归属原因后，才能恢复可信的本单材料成本和单位直接成本。',
                 'stock_order',sign_conflicts,state='source_values_inconsistent')
        if process_unmapped:
            need('INPUT_PROCESS_LINK','领退料来源用料行、该行投料工序，以及同工序多任务间的实际消耗归属。','生产/主数据负责人',
                 '说明哪道工序用了这笔材料；没有分配依据时不能硬算逐任务材料成本。','materials',process_unmapped)
        if batch_unknown:
            need('INPUT_BATCH_ALLOCATION','每笔领料对应的采购或半成品入库明细、实际批号、领用数量及拆批混批分配依据。','仓库/生产负责人',
                 '从本单成品追到上游半成品或采购原料；仅同编码同日期不算关联，下游领料已含上游价值时不再重复加成本。',
                 'stock_order',batch_unknown,state='batch_relationship_not_supplied')
        if identity_conflicts:
            need('MATERIAL_IDENTITY','同编码不同名称对应的规格、历史名称及加工阶段确认。','主数据/生产负责人',
                 '判断是否属于同一物料或不同加工阶段，避免错误比较与合并。','product',identity_conflicts)
        wages=[number(t.get('salary')) for t in steps]
        zero_with_time=[t for t in steps if number(t.get('salary'))==0 and (number(t.get('durationSeconds')) or 0)>0]
        if not steps or any(w is None or w<0 for w in wages) or zero_with_time or (wages and all(w==0 for w in wages)):
            need('LABOR_BASIS','工序累计工资、计薪依据，以及零工资是否另记或集中到其他工序的确认。','生产/财务负责人',
                 '补齐本单人工口径，区分漏记与真实零值；不叠加报工工资估算。','task',
                 [t['id'] for t in steps if number(t.get('salary')) in (None,Decimal(0))],state='zero_or_missing_unverified')
        if not receipts:
            need('FINISHED_RECEIPT_LINK','成品入库单及明细、对应工单ID/编码、入库良品量与单位。','仓库/生产负责人',
                 '核对生产完成是否进入库存；不自动把无入库单等同于无生产。','stock_order')
        else:
            comparable=all(unit(r.get('unit'))==unit(p.get('unit')) and number(r.get('quantity')) is not None for r in receipts)
            receipt_q=sum((number(r['quantity']) for r in receipts),Decimal(0)) if comparable else None
            if not comparable or q is None or receipt_q!=q:
                need('FINISHED_RECONCILIATION','成品入库明细、最终良品及审批状态、分批入库和退回记录；单位不同还需规格换算依据。','生产/仓库负责人',
                     '解释工单良品与入库数量差额，不能凭差额直接认定漏报、损耗或错误。','stock_order',[r['id'] for r in receipts])
        relevant_outputs=[o for o in outputs.values() if key(o.get('projectId'))==pid]
        matched_outputs=[o for o in relevant_outputs if key(o.get('taskId')) in tasks
                         and key(tasks[key(o['taskId'])].get('projectId'))==pid
                         and key(tasks[key(o['taskId'])].get('processId'))==key(o.get('processId'))]
        if len(matched_outputs)!=len(relevant_outputs):
            need('OUTPUT_TASK_LINK','报工记录的工单、任务及工序ID对应和审批状态。','生产/集成负责人',
                 '逐条核对报工归属；任务数与报工数不能直接相减认定漏报。','output')
        if p.get('completed') is True and p.get('directCostAvailable') is not True and not any(n['impactType']=='unit_cost' for n in needs.values()):
            need('DIRECT_COST_BLOCKER','现有单位成本规则仍被阻断：'+'；'.join(p.get('directCostBlockingReasons') or ['需确认金额、数量和单据一致性']),
                 '财务/集成负责人','定位并核实本单具体阻断项后，再计算单位直接成本；不把不可计算显示为零。','stock_order')
        record={'workOrderId':p['id'],'workOrderCode':p['code'],'product':p.get('product'),
            'productCode':p.get('productCode'),'unit':p.get('unit'),'goodQuantity':p.get('goodQuantity'),
            'directCostAvailable':p.get('directCostAvailable') is True,'identifiedUnitCost':p.get('identifiedUnitCost'),
            'directCostBlockingReasons':p.get('directCostBlockingReasons',[]),
            'providedClues':p.get('providedClues',[]),
            'businessLinks':order_links,'finishedReceiptIds':[r['id'] for r in receipts],
            'linkedOutputIds':[o['id'] for o in matched_outputs],'needs':list(needs.values()),
            'upstreamCandidates':upstream_candidates,
            'physicalBatchChainVerified':False,'fullManufacturingCostAvailable':False,
            'costBoundary':'仅已识别材料加人工；不叠加上游制造成本，不含完整制造费用。'}
        results.append(record);all_needs.extend(needs.values());links.extend(order_links)
    return {'schema':'smartbi-cost-traceability/1','definitionVersion':'cost-traceability/2026-11-04.1','scope':scope,'checkedAt':snapshot.get('checkedAt'),
        'queryStatus':query_status,'workOrders':results,'actions':all_needs,'unassignedMovementIds':unassigned,
        'summary':{'workOrders':len(results),'ordersWithDirectCost':sum(r['directCostAvailable'] for r in results),
            'ordersNeedingData':sum(bool(r['needs']) for r in results),'materialOriginLinks':len(links),
            'materialProcessLinks':sum(r['processId'] is not None for r in links),
            'actionCounts':dict(Counter(n['code'] for n in all_needs)),
            'physicalBatchChainsVerified':0,'sourceBusinessWrites':0},
        'limitations':['业务单据关联不是批次流转证明；未提供采购/半成品批次分配，不宣称全链路贯通。',
            '缺口表示本次授权查询证据不足，不证明黑湖所有模块或其他渠道都没有该数据。',
            '每张工单可有多项缺口，缺口条数不能当工单数。']}
