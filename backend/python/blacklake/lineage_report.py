"""Local per-order delivery; no source mutation, guessed price or cross-stage sum."""
import argparse
from collections import Counter
import json
import os
from pathlib import Path

from .lineage_probe import save
from .traceability import from_live
from .smartbi_store import settings


def export(observations,bom,output,baseline=None):
    output=Path(output)
    if not output.is_absolute():raise ValueError('TRACE_REPORT_ABSOLUTE_PATH_REQUIRED')
    output.mkdir(parents=True,exist_ok=True)
    report=from_live(json.loads(Path(observations).read_text(encoding='utf8')),
                     json.loads(Path(bom).read_text(encoding='utf8')) if bom else None)
    directory=Path(os.environ['LOCALAPPDATA'])/'Cretas/Blacklake'
    config=settings(directory/'smartbi-test.dpapi')
    if (report['scope']['tenantId'],report['scope']['sourceFactoryId'],report['baselineAnchor'])!=(config['tenant_id'],str(config['source_factory_id']),config['run_id']):
        raise ValueError('TRACE_EXPORT_SCOPE_MISMATCH')
    if baseline:
        old=json.loads(Path(baseline).read_text(encoding='utf8'))
        if tuple(old['scope'])!=(config['tenant_id'],str(config['source_factory_id'])) or old['runId']!=config['run_id']:
            raise ValueError('TRACE_BASELINE_COMPARISON_SCOPE_MISMATCH')
        from .analytics import build
        old_analysis=build(old['data'],{'run_id':old['runId'],'finished_at':None,'stock_pairs_not_returned':None})
        old_cost={str(r['projectId']):r for r in old_analysis['cost']['projects']}
        recovered=[];regressed=[]
        for order in report['workOrders']:
            prior=old_cost[str(order['workOrderId'])]
            order['baselineComparison']={'directCostAvailable':prior['unitCostAvailable'],
                'identifiedUnitCost':prior['unitIdentifiedDirectCost'],'previousIssues':prior['issues']}
            if not prior['unitCostAvailable'] and order['directCostAvailable']:recovered.append(order['workOrderCode'])
            if prior['unitCostAvailable'] and not order['directCostAvailable']:regressed.append(order['workOrderCode'])
        report['baselineComparison']={'baselineRunId':old['runId'],
            'previousOrdersWithDirectCost':sum(r['unitCostAvailable'] for r in old_cost.values()),
            'newlyFieldsEligibleOriginallyBlocked':len(recovered),'newlyEligibleOrderCodes':recovered,
            'previouslyEligibleNowBlocked':len(regressed),'newlyBlockedOrderCodes':regressed,
            'stillBlockedOriginalCohort':sum(not r['directCostAvailable'] for r in report['workOrders']),
            'historicalSnapshotModified':False}
    save(output/'traceability-latest-private.json',report)
    labels={'missing_in_queried_response':'已查接口未提供，待核其他记录',
        'source_identity_conflict':'源单工单ID/编码冲突，需核真实归属',
        'source_values_inconsistent':'单价或数量金额符号不一致，待财务仓库核对',
        'source_zero_or_missing_unverified':'源端零值或缺值，原因待核','batch_relationship_not_supplied':'接口未提供批次分配，待工厂/黑湖确认',
        'zero_or_missing_unverified':'零值或缺值，计价待核','pending_completion':'未完工，待生产完成后核对',
        'query_incomplete':'接口未查完整，先由集成恢复'}
    impact={'unit_cost':'影响当前单位直接成本','batch_trace':'影响原料到成品的批次追溯',
            'process_cost':'影响逐工序材料成本','quantity_check':'影响生产与库存对账','identity_check':'影响物料可比性','production_pending':'生产进度条件'}
    txt=['六膳门｜逐工单成本关联与补资料清单',
        '只核对原109张工单；新增工单单列、不混入。最新补查与旧SmartBI时点分开，不回填历史金额。',
        '本次接口未提供不等于黑湖所有模块没有；请先提供已有记录，不要求直接修改源系统。',
        '已识别材料加人工能计算，不等于逐批原料到成品贯通；半成品领料已含上游价值时不再重复加。',
        f"观察窗口：{report['observationWindow']['start']} 至 {report['observationWindow']['end']}（UTC）。",'']
    overview=[];actions=[];business=[];candidates=[]
    for order in report['workOrders']:
        direct='现有字段具备' if order['directCostAvailable'] else '现有字段不足'
        cost='未提供' if order['identifiedUnitCost'] is None else f"{order['identifiedUnitCost']:.2f}"
        previous=order.get('baselineComparison',{})
        change=('本次新增字段具备' if previous.get('directCostAvailable') is False and order['directCostAvailable'] else
                '本次新增阻断' if previous.get('directCostAvailable') is True and not order['directCostAvailable'] else
                '原字段具备' if previous.get('directCostAvailable') else '仍待补核')
        overview.append([order['workOrderCode'],order['product'],direct,
            order['identifiedUnitCost'] if order['identifiedUnitCost'] is not None else '未提供',order['unit'] or '未提供',
            len(order['businessLinks']),sum(r['processId'] is not None for r in order['businessLinks']),len(order['finishedReceiptIds']),
            len(order['needs']),'尚未证明批次贯通',change])
        txt.extend([f"{order['workOrderCode']}｜{order['product']}",
            f"当前直接成本：{direct}；单位直接成本 {cost}/{order['unit'] or '未提供'}（来源币种未核实）。",
            f"已核实 {len(order['businessLinks'])} 条领退料→用料清单关联，其中 {sum(r['processId'] is not None for r in order['businessLinks'])} 条能定位投料工序；成品入库关联 {len(order['finishedReceiptIds'])} 条。"])
        def clue_text(clue):
            from smartbi.services.cost_traceability import number
            numeric=number(clue['value'])
            value=f'{numeric:.2f}' if numeric is not None else str(clue['value'])
            return clue['label']+'='+value
        clue_description='；'.join(clue_text(c) for c in order.get('providedClues',[]))
        overview[-1].append(clue_description or '未提供')
        if clue_description:txt.append('已有线索（定义/单位待核，不作实际成本回填）：'+clue_description)
        for need in order['needs']:
            state=labels.get(need['sourceStatus'],need['sourceStatus'])
            actions.append([order['workOrderCode'],order['product'],impact[need['impactType']],state,need['missingData'],
                need['responsibleRole'],need['afterProvided'],'、'.join(need.get('sourceDocuments',[])),need['priority'],need['id']])
            txt.append(f"- {impact[need['impactType']]}：{need['missingData']} 找谁：{need['responsibleRole']}。补齐后：{need['afterProvided']} 状态：{state}。")
        for link in order['businessLinks']:
            business.append([order['workOrderCode'],link.get('sourceDocumentCode') or '未提供',link.get('materialName') or '未提供',link.get('feedProcessName') or '未定位投料工序',link['movementId'],link['materialRowId'],
                link['processId'] if link['processId'] is not None else '未提供','、'.join(map(str,link.get('taskIds',[]))),
                '来源明细ID+用料父工单ID/编码+产品编码核对；不代表批次'])
        for row in order['upstreamCandidates']:
            codes='、'.join(c['workOrderCode'] for c in row['candidateOrders']) or '未见同编码同单位候选'
            candidates.append([order['workOrderCode'],row['movementId'],row['candidateCount'],codes,
                '仅同编码/单位候选；尚无实际入库批次、领用分配，不回填成本'])
        txt.append('')
    workbook={'summary':report['summary'],'checkedAt':report['checkedAt'],'sourceProbe':report['sourceProbe'],
        'baselineComparison':report.get('baselineComparison'),
        'overview':overview,'actions':actions,'businessLinks':business,'candidates':candidates,
        'statusCounts':dict(Counter(n['sourceStatus'] for n in report['actions']))}
    save(output/'workbook-data-private.json',workbook)
    (output/'六膳门-逐工单成本缺口与负责人.txt').write_text('\n'.join(txt),encoding='utf8')
    print(json.dumps({'summary':report['summary'],'sourceProbe':report['sourceProbe'],'statusCounts':workbook['statusCounts'],
                      'rows':{k:len(workbook[k]) for k in ('overview','actions','businessLinks','candidates')}} ,ensure_ascii=False))
    return report


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--observations',required=True);parser.add_argument('--bom');parser.add_argument('--output',required=True);parser.add_argument('--baseline')
    args=parser.parse_args();export(args.observations,args.bom,args.output,args.baseline)
