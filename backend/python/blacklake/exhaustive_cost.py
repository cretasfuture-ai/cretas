"""Local-only full-evidence cost run, retaining the original 109-order snapshot."""
import argparse
import asyncio
from copy import deepcopy
import json
import os
from pathlib import Path

from .analytics import build as analyze
from .cost_review import review
from .full_archive import digest
from .intelligence import enrich as intelligence
from .lineage_probe import save
from .traceability import subset

def project_dates(projects):
    """Preserve source dates separately from validated cost arithmetic."""
    from .period_projection import instant
    def normalized(value):
        parsed = instant(value)
        return parsed.isoformat() if parsed else None
    return {str(row['id']): {'completedAt': normalized(row.get('actualEndTime')),
                            'createdAt': normalized(row.get('createTime'))} for row in projects}

def prepare(directory,config):
    from smartbi.services.analysis_evidence import build,TYPE_HAZARD
    from smartbi.services.cost_data_alerts import evaluate
    from smartbi.services.analysis_batches import digest as hash_value
    directory=Path(directory)
    full=json.loads((directory/'local-profile10/input-context-private.json').read_text(encoding='utf8'))
    trace=json.loads((directory/'delivery/traceability-latest-private.json').read_text(encoding='utf8'))
    scope={'tenantId':config['tenant_id'],'sourceFactoryId':str(config['source_factory_id']),'baselineAnchor':config['run_id']}
    if (trace['scope']['tenantId'],str(trace['scope']['sourceFactoryId']),trace['baselineAnchor'])!=(scope['tenantId'],scope['sourceFactoryId'],scope['baselineAnchor']):raise ValueError('EXHAUSTIVE_SOURCE_SCOPE_MISMATCH')
    if full['runId']!=trace['scope']['snapshotId']:raise ValueError('EXHAUSTIVE_SNAPSHOT_MISMATCH')
    previous=build(full['costAlerts'],full);all_facts=build(full['costAlerts'],full,include_all=True)
    retained={r['id'] for r in previous['facts']}
    remaining=[r for r in all_facts['facts'] if r['id'] not in retained]
    if len(remaining)!=44 or previous['omittedFacts']!=44:raise ValueError('ORIGINAL_44_FACTS_CHANGED')
    source=json.loads((directory/'live-recheck/observations-private.json').read_text(encoding='utf8'))
    if source['probeId']!=full['runId']:raise ValueError('EXHAUSTIVE_SOURCE_REVISION_MISMATCH')
    raw=deepcopy(source['data'])
    bom=json.loads((directory/'live-bom-recheck/bom-supplement-private.json').read_text(encoding='utf8'))
    if (bom.get('parentProbeId')!=source['probeId'] or bom.get('baselineRunId')!=config['run_id']
            or (bom.get('tenantId'),str(bom.get('sourceFactoryId')))!=(config['tenant_id'],str(config['source_factory_id'])) or not bom.get('complete')):
        raise ValueError('EXHAUSTIVE_BOM_SUPPLEMENT_SCOPE_MISMATCH')
    raw['bom']=bom['rows']
    eligible=[p for p in full['cost']['projects'] if p['unitCostAvailable']]
    ids={str(p['projectId']) for p in eligible};codes={p['projectCode'] for p in eligible}
    if len(ids)!=26 or {str(p['workOrderId']) for p in trace['workOrders'] if p['directCostAvailable']}!=ids:raise ValueError('EXACT_26_COHORT_REQUIRED')
    raw['project']=[p for p in raw['project'] if str(p['id']) in ids]
    for resource in ('task','output'):raw[resource]=[r for r in raw[resource] if str(r.get('projectId')) in ids]
    orders=[]
    for order in raw['stock_order']:
        details=[d for d in order.get('orderDetails',[]) if str(d.get('associatedBizOrderId')) in ids or d.get('associatedBizOrderCode') in codes]
        if details:orders.append({**order,'orderDetails':details})
    raw['stock_order']=orders
    run_id=full['runId']+':eligible26:'+hash_value(sorted(ids))[:12]
    metadata={'run_id':run_id,'finished_at':source['finished_at'],'stock_pairs_not_returned':None}
    analysis=analyze(raw,metadata)
    if len(analysis['cost']['projects'])!=26 or any(not p['unitCostAvailable'] for p in analysis['cost']['projects']):raise ValueError('ELIGIBLE_RECOMPUTATION_CHANGED')
    # Exact unchanged arithmetic, not a new estimate or cross-stage aggregation.
    old_by_id={str(p['projectId']):p for p in eligible}
    for row in analysis['cost']['projects']:
        if row!=old_by_id[str(row['projectId'])]:raise ValueError('COST_RECOMPUTATION_MISMATCH')
    analysis['cost']['projectDates'] = project_dates(raw['project'])
    analysis['eligibleCostReview']=review(analysis)
    analysis['costIntelligence']=intelligence(analysis,raw,metadata)
    analysis['costAlerts']=evaluate(analysis['cost']['projects'],scope=digest((config['tenant_id'],config['source_factory_id'])),run_id=run_id)
    analysis['costTraceability']=subset(trace,list(ids),run_id)
    from .section_insights import build as sections
    analysis['sectionInsights']=sections(analysis)
    pack=build(analysis['costAlerts'],analysis,include_all=True)
    items=[]
    def add(fact,display,cohort):
        identity=('remaining44' if cohort=='original44' else 'eligible26')+'_'+fact['id']
        items.append({**deepcopy(fact),'id':identity,'cohort':cohort,'display':{**deepcopy(display),'id':identity}})
    for fact in remaining:add(fact,all_facts['displayFacts'][fact['id']],'original44')
    for fact in pack['facts']:add(fact,pack['displayFacts'][fact['id']],'eligible26')
    for i,row in enumerate(analysis['eligibleCostReview']['projects']):
        related=[a for a in analysis['costAlerts']['items'] if any(str(r['projectId'])==str(row['projectId']) for r in a['entityRefs'])]
        hazards={TYPE_HAZARD[a['type']] for a in related if a['type'] in TYPE_HAZARD}
        if any(str(c.get('unit','')).casefold()!=str(row['unit']).casefold() for c in row['components']):hazards.add('UNIT_CONVERSION')
        fact={'id':f'ORDER{i+1:03d}','sectionId':'product_cost','kind':'work_order_detail','considerations':sorted(hazards),
            'groupId':'G'+hash_value([row['productCode'],row['unit']])[:12],
            'statistics':{'materialShare':row['materialShare'],'laborShare':row['laborShare'],'materialLines':len(row['components']),
                          'materialPerFinished':row['materialPerFinished'],'laborPerFinished':row['laborPerFinished'],'unitDirectCost':row['unitIdentifiedDirectCost']},
            'breakdown':{'materials':[{'component':f'M{j+1}','inputUnit':c['unit'],'costPerFinished':c['costPerFinished'],'usagePerFinished':c['usagePerFinished']} for j,c in enumerate(row['components'])],
                'processes':[{'process':f'P{j+1}','hours':p['hours'],'laborCost':p['laborCost']} for j,p in enumerate(row['processes'])],
                'actualBatchChainVerified':False,'costBoundary':'仅本单已识别材料加人工；不再加上游制造成本。'}}
        text=f"{row['projectCode']}（{row['product']}）：单位直接成本 {row['unitIdentifiedDirectCost']:.2f}/{row['unit']}，其中材料 {row['materialPerFinished']:.2f}、人工 {row['laborPerFinished']:.2f}。"
        text+=' 材料：'+'；'.join(f"{c['material']}，来源{c['orderCode']}，每{row['unit']}成品耗用{c['usagePerFinished']:.2f}{c['unit']}，贡献{c['costPerFinished']:.2f}" for c in row['components'])
        text+='。金额只代表已识别材料加人工；实际上游批次仍待核。'
        add(fact,{'text':text,'entityRefs':[{'projectId':row['projectId'],'projectCode':row['projectCode']}]},'eligible26')
    items.sort(key=lambda r:(r['cohort'],r['sectionId'],r['id']))
    return scope,analysis,items,{'originalRemainingFacts':len(remaining),'eligibleOrders':26,
        'eligibleSectionFacts':len(pack['facts']),'eligiblePerOrderFacts':26,'baselineSnapshotUnchanged':True,
        'inventoryBoundary':'本轮未重新查询库存余额，不将零行响应解释为零库存；只核对选中工单生命周期领退料。'}

async def main(args):
    from .smartbi_app import create_app
    create_app()
    from . import period_runtime as runtime
    from smartbi.services.analysis_batches import Queue
    config=runtime.settings(os.environ['BLACKLAKE_SMARTBI_CONFIG'])
    if await runtime.authorized_actor(config) is None:raise RuntimeError('FULL_COST_AUTHORITY_REQUIRED')
    directory=Path(args.input);out=Path(args.output)
    if not directory.is_absolute() or not out.is_absolute():raise ValueError('ABSOLUTE_LOCAL_PATHS_REQUIRED')
    out.mkdir(exist_ok=True,parents=True)
    scope,analysis,items,contract=prepare(directory,config)
    queue=Queue(out/'evidence-queue.sqlite3')
    fingerprint=digest({'source':analysis['runId'],'facts':items})
    run_id=queue.submit(scope,fingerprint,items)
    save(out/'input-context-private.json',analysis);save(out/'all-evidence-private.json',items)
    if args.retry_failed:
        with queue.connect() as db:db.execute("UPDATE jobs SET status='queued',error=NULL WHERE run_id=? AND status='failed'",(run_id,))
    while True:
        progress=queue.read(run_id,scope)
        save(out/'smartbi-complete-cost.json',{'schema':'blacklake-complete-cost/1','scope':scope,'sourceRunId':analysis['runId'],
            'sourceCheckedAt':analysis['snapshotAt'],'contract':contract,'analysis':analysis,'batchAnalysis':progress})
        print(json.dumps({k:progress[k] for k in ('status','jobs','expectedFacts','analyzedFacts','remainingFacts','cohorts','usage')},ensure_ascii=False),flush=True)
        if progress['status']=='complete':return 0
        if progress['status']=='failed':return 1
        actor=await runtime.authorized_actor(config)
        if actor is None:raise RuntimeError('FULL_COST_AUTHORITY_RECHECK_FAILED')
        from smartbi.services.analysis_batches import run_one
        worked=await asyncio.to_thread(run_one,queue,run_id,scope,actor)
        if not worked:await asyncio.sleep(10)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--input',required=True);parser.add_argument('--output',required=True);parser.add_argument('--retry-failed',action='store_true')
    raise SystemExit(asyncio.run(main(parser.parse_args())))
