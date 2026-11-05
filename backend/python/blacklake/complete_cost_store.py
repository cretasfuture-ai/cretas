"""Read saved exhaustive results, after caller cost authorization."""
import json
import os
from pathlib import Path

def read(config):
    if os.environ.get('BLACKLAKE_LIVE_CONFIG'):return read_live(config)
    return read_archived(config)


def read_archived(config):
    configured=os.getenv('BLACKLAKE_COMPLETE_COST_FILE')
    if not configured:return None
    path=Path(configured)
    if not path.is_absolute():raise ValueError('COMPLETE_COST_ABSOLUTE_PATH_REQUIRED')
    value=json.loads(path.read_text(encoding='utf8'));scope=value.get('scope',{})
    if (scope.get('tenantId'),str(scope.get('sourceFactoryId')),scope.get('baselineAnchor'))!=(config['tenant_id'],str(config['source_factory_id']),config['run_id']):raise ValueError('COMPLETE_COST_SCOPE_MISMATCH')
    if value.get('schema')!='blacklake-complete-cost/1':raise ValueError('COMPLETE_COST_SCHEMA_INVALID')
    analysis=value['analysis'];batch=value['batchAnalysis'];rows=analysis['cost']['projects']
    if (len(rows)!=26 or len({r['projectId'] for r in rows})!=26 or any(not r['unitCostAvailable'] for r in rows)
            or analysis['runId']!=value['sourceRunId'] or analysis['costAlerts']['runId']!=value['sourceRunId']):raise ValueError('COMPLETE_COST_COHORT_INVALID')
    if batch['scope']!=scope or batch['analyzedFacts']!=len(batch['results']) or len({r['factId'] for r in batch['results']})!=len(batch['results']):raise ValueError('COMPLETE_COST_RESULT_INVALID')
    if batch['status']=='complete' and (batch['remainingFacts']!=0 or batch['analyzedFacts']!=batch['expectedFacts']):raise ValueError('COMPLETE_COST_COVERAGE_INVALID')
    return value


def read_live(config):
    """Current eligible rows, deterministic explanations; original44 stays historical."""
    from copy import deepcopy
    from . import live_monitor
    from .smartbi_store import read as read_analysis
    from .exhaustive_cost import project_dates
    from .full_archive import digest
    from smartbi.services.cost_data_alerts import evaluate
    v=live_monitor.config(os.environ['BLACKLAKE_LIVE_CONFIG'])
    if (v['tenant_id'],str(v['source_factory_id']),v['baselineRunId'])!=(config['tenant_id'],str(config['source_factory_id']),config['run_id']):raise ValueError('LIVE_COMPLETE_COST_SCOPE_MISMATCH')
    source=live_monitor.latest(v)
    if source is None:raise ValueError('LIVE_COMPLETE_COST_NOT_READY')
    current=read_analysis(config,live_cycle_id=source['cycleId'])
    analysis=deepcopy(current);eligible=[r for r in current['cost']['projects'] if r['unitCostAvailable']]
    ids={str(r['projectId']) for r in eligible}
    analysis['cost']['projects']=eligible
    analysis['cost']['projectDates']=project_dates([p for p in source['data']['project'] if str(p['id']) in ids])
    analysis['costAlerts']=evaluate(eligible,scope=digest((config['tenant_id'],config['source_factory_id'])),run_id=current['runId'])
    from .traceability import subset
    analysis['costTraceability']=subset(current['costTraceability'],list(ids),current['runId'])
    archived=read_archived(config)
    historical=[{**deepcopy(r),'historical':True,'engine':'historical_local_ai',
                 'sourceRunId':archived['sourceRunId'],'sourceCheckedAt':archived['sourceCheckedAt']}
                for r in (archived or {}).get('batchAnalysis',{}).get('results',[]) if r['cohort']=='original44']
    facts=[]
    for row in eligible:
        warnings=[a for a in analysis['costAlerts']['items'] if any(str(e['projectId'])==str(row['projectId']) for e in a['entityRefs'])]
        unit=row['unit'];title=f'{row["projectCode"]}（{row["product"]}）'
        text=f'{title}：每{unit}材料费 {row["materialCost"]/row["finishedGood"]:,.2f}、人工费 {row["laborCost"]/row["finishedGood"]:,.2f}，合计 {row["unitIdentifiedDirectCost"]:,.2f}。'
        facts.append({'factId':'live_order_'+str(row['projectId']),'cohort':'eligible_live','sectionId':'product_cost','kind':'work_order_detail',
            'whatItMeans':text,'whyItMatters':'先看材料和人工各占多少，再对照同产品、同规格、同单位的工单；不跨产品直接比高低。',
            'hypotheses':[],'nextStep':'；'.join(dict.fromkeys(a['action'] for a in warnings)) or '核对领退料原单、工序工资和成品良品量；当前没有已识别的重点数值矛盾。',
            'owner':' / '.join(dict.fromkeys(a['owner'] for a in warnings)) or '生产 / 仓库 / 财务负责人',
            'cannotConclude':'这是已识别材料加人工，不含全部制造费用；上游原料批次、实际损耗原因仍需原单或盘点证据。',
            'display':{'text':text,'entityRefs':[{'projectId':row['projectId'],'projectCode':row['projectCode']}]},
            'engine':'deterministic_live','historical':False,'sourceRunId':current['runId'],'sourceCheckedAt':current['snapshotAt']})
    scope={'tenantId':config['tenant_id'],'sourceFactoryId':str(config['source_factory_id']),'baselineAnchor':config['run_id']}
    results=[*historical,*facts]
    return {'schema':'blacklake-complete-cost/1','scope':scope,'sourceRunId':current['runId'],'sourceCheckedAt':current['snapshotAt'],
        'contract':{'eligibleOrders':len(eligible),'originalRemainingFacts':len(historical),'live':True,'engine':'mixed_provenance','currentExplanationEngine':'deterministic_live',
                    'inventoryBoundary':'当前库存按独立采集时间查看；工单成本取当前已识别材料与人工。',
                    'sourceOrders':current['production']['projectCount'],'historicalAnalysisRetained':bool(historical)},
        'analysis':analysis,'batchAnalysis':{'status':'complete','expectedFacts':len(results),'analyzedFacts':len(results),'remainingFacts':0,
          'results':results,'cohorts':{'original44':{'expected':len(historical),'analyzed':len(historical)},'eligible_live':{'expected':len(facts),'analyzed':len(facts)}},
          'provenance':{'original44':{'engine':'historical_local_ai','sourceRunId':(archived or {}).get('sourceRunId'),'sourceCheckedAt':(archived or {}).get('sourceCheckedAt')},
                        'eligible_live':{'engine':'deterministic_live','sourceRunId':current['runId'],'sourceCheckedAt':current['snapshotAt']}},
          'usage':None,'historicalUsage':(archived or {}).get('batchAnalysis',{}).get('usage'),'jobs':{'complete':len(results)}}}

def followup(value,fact_id,question):
    rows=[r for r in value['batchAnalysis']['results'] if r['factId']==fact_id]
    if len(rows)!=1:raise ValueError('COMPLETE_COST_FACT_NOT_READY')
    row=rows[0];q=question.strip()
    if any(k in q for k in ('补','找谁','负责','下一步','核对')):
        lines=[row['nextStep']+'（'+row['owner']+'）',row['cannotConclude']]
    elif any(k in q for k in ('为什么','原因','异常')):
        lines=[row['whatItMeans'],row['whyItMatters'],*row['hypotheses'],row['cannotConclude']]
    else:lines=[row['whatItMeans'],row['whyItMatters'],row['nextStep']+'（'+row['owner']+'）',row['cannotConclude']]
    return {'answer':'\n\n'.join([row['display']['text'],*lines]),'factId':fact_id,'runId':value['sourceRunId'],
        'source':'saved_evidence','evidenceRunId':row.get('sourceRunId',value['sourceRunId']),
        'evidenceCheckedAt':row.get('sourceCheckedAt',value['sourceCheckedAt']),'historical':row.get('historical',False),
        'currentRequestUsage':{'totalTokens':0,'source':'saved_evidence','estimated':False},
        'message':'只解释本条已校验证据，不新增推断或改变成本。'}
