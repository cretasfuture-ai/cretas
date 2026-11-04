"""Read saved exhaustive results, after caller cost authorization."""
import json
import os
from pathlib import Path

def read(config):
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
        'source':'saved_evidence','currentRequestUsage':{'totalTokens':0,'source':'saved_evidence','estimated':False},
        'message':'只解释本条已校验证据，不新增推断或改变成本。'}
