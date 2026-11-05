"""Resume omitted model-safe facts without changing an immutable base report."""
import asyncio
from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path
import re

from .analysis_evidence import build
from . import local_analyst as analyst

SCHEMA='smartbi-analysis-supplement/1'
FIELDS={'factId','whatItMeans','nextStep','cannotConclude','owner'}
KINDS={
    'weighted_unit_cost':'同一单位下的账面材料加人工成本；不是完整制造成本，也不能跨产品规格直接比较。',
    'product_coverage':'分别看可核算工单比例及同单位良品产量覆盖；缺失不能当零，也不能外推全厂。',
    'upstream_unit_conversion':'上游原料或半成品与成品单位不同，先补实测重量及换算，不把投入等同成品。',
    'material_quantity_valuation_split':'拆开账面耗用量与领料折算值，材料组合不同不直接断言采购涨价或超耗。',
    'common_input_price_check':'只比较已提供的同种材料账面估值；不能认定采购价变化或查明原因。',
    'same_product_bridge':'同组工单拆材料与人工差额；数量与单位先核对，可能漏记而非效率变化。',
    'material_link_coverage':'已关联的领退料、未关联记录和源端零值分别核对；业务关联不等于批次闭环。',
}
RISK_WORDING={
    'SMALL_SAMPLE':'样本少，不能推广到全部工单。',
    'UNIT_CONVERSION':'单位与规格换算尚待核对确认。',
    'MATERIAL_MIX':'材料组合与耗用量不同，需分别核对领料折算值。',
    'ZERO_LABOR_BASELINE':'对照工单工资为零，需核实人工是否另记或漏记。',
    'MISSING_COST':'材料成本记录不全，先核对缺失的单据。',
}


def digest(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,ensure_ascii=False,default=str).encode()).hexdigest()


def plan(job,facts):
    context=deepcopy(facts)
    context['analysisHistory']=job['result'].get('history',{})
    if job['fingerprint']!=context['periodMetadata']['fingerprint'] or job['result']['runId']!=context['runId']:
        raise ValueError('SUPPLEMENT_FACT_BINDING')
    base=build(context['costAlerts'],context)
    full=build(context['costAlerts'],context,include_all=True)
    selected={f['id'] for f in base['facts']}
    missing=[f for f in full['facts'] if f['id'] not in selected]
    return full,missing,{'schema':SCHEMA,'reportId':job['id'],'fingerprint':job['fingerprint'],
        'baseInputFingerprint':job['result']['ai'].get('inputFingerprint'),
        'evidenceDigest':digest(full),'totalFacts':len(full['facts']),'baseProvided':len(base['facts']),
        'unrepresentedGroups':full['omittedFacts']}


def validate_review(raw,fact,full):
    if not isinstance(raw,dict) or set(raw)!=FIELDS or raw['factId']!=fact['id']:
        raise ValueError('SUPPLEMENT_FACT_ID_OR_SCHEMA')
    sid=fact['sectionId']
    section={'sectionId':sid,'evidenceRefs':[fact['id']],
        'whatItMeans':raw['whatItMeans'],'whyItMatters':raw['whatItMeans'],
        'hypotheses':[],'nextStep':raw['nextStep'],'owner':raw['owner'],
        'cannotConclude':raw['cannotConclude'],'followupQuestion':'还需要核对哪些业务记录？',
        'considerations':fact['considerations']}
    one={**full,'sectionIds':[sid],'requiredConsiderations':{sid:fact['considerations']},
        'requiredFactRefs':{sid:[fact['id']]},'narrativeRequirements':{sid:[]}}
    _,accepted=analyst.validate({'reviews':[],'sections':[section]},one,[])
    return {**raw,'sectionId':sid,'statistics':accepted[0]['statistics']}


def payload(full,batch,model,feedback=None):
    from .analysis_playbook import HAZARDS
    ids={f['id'] for f in batch};groups={f.get('groupId') for f in batch if f.get('groupId')}
    # Context is explicitly separate from facts that require individual answers.
    related=[f for f in full['facts'] if f['id'] not in ids and f.get('groupId') in groups]
    data={'facts':batch,'relatedContext':related[:24],'kindDefinitions':KINDS,
        'riskDefinitions':HAZARDS,'allowedOwners':sorted(analyst.OWNERS),
        'requiredRiskWording':{f['id']:[RISK_WORDING[c] for c in f['considerations'] if c in RISK_WORDING] for f in batch},
        'validationFeedback':feedback,
        'instruction':'逐条解释facts，不遗漏、不合并。groupId相同才可讨论关联；relatedContext只作背景，不新增待核对项。'
        '每条返回factId、whatItMeans、nextStep、cannotConclude、owner五个字段。'
        'factId原样复制。文字用工厂人员看得懂的白话，说明哪里可能有问题、找谁核对什么。'
        '正文不写数字、事实编号、产品代号，数字和真实产品名由系统绑定。'
        '每个事实的considerations风险都必须在正文解释，不能断言原因已确认。每个文字字段不少于四字且不超过两百四十字。'}
    return analyst.AnalystPayload(model=model,stream=False,think=False,format='json',keep_alive='60s',
        options={'temperature':0,'num_ctx':16384,'num_predict':6000},messages=[
            {'role':'system','content':'你是工厂生产与成本复核助手。只根据提供的匿名程序统计给核对建议。'
             '不编造金额、数量、采购价或业务因果。只返回JSON对象 {"reviews":[逐条核对结果]}。'},
            {'role':'user','content':json.dumps(data,ensure_ascii=False)}])


def save(path,state):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    temporary=path.with_suffix('.tmp')
    temporary.write_text(json.dumps(state,ensure_ascii=False,indent=2),encoding='utf8')
    os.replace(temporary,path)


async def complete(job,facts,scope,path,requester,*,batch_size=8):
    from .alert_ai_coordination import run
    from . import alert_local_ai as provider
    full,missing,binding=plan(job,facts)
    config=provider.provider_config()
    if config['transport']=='disabled':raise ValueError('SUPPLEMENT_LOCAL_MODEL_REQUIRED')
    state=json.loads(Path(path).read_text(encoding='utf8')) if Path(path).exists() else {
        **binding,'scope':scope,'accepted':{},'calls':[],'rejections':[],'status':'running'}
    if any(state.get(k)!=v for k,v in binding.items()) or state.get('scope')!=scope:
        raise ValueError('SUPPLEMENT_CHECKPOINT_BINDING')
    by_id={f['id']:f for f in missing}
    for id,raw in state['accepted'].items():
        if id not in by_id:raise ValueError('SUPPLEMENT_UNREQUESTED_FACT')
        validate_review(raw,by_id[id],full)
    pending=[f for f in missing if f['id'] not in state['accepted']]
    for offset in range(0,len(pending),batch_size):
        batch=pending[offset:offset+batch_size];feedback=None
        for attempt in range(2):
            raw=None
            try:
                raw=await asyncio.to_thread(run,provider.infer,payload(full,batch,config['model'],feedback),
                                           config,facts['costAlerts']['scope'],requester)
                if getattr(raw,'usage',None):state['calls'].append(raw.usage)
                if not isinstance(raw,dict) or set(raw)!={'reviews'} or not isinstance(raw['reviews'],list):
                    raise ValueError('SUPPLEMENT_BATCH_SCHEMA')
                ids=[r.get('factId') for r in raw['reviews'] if isinstance(r,dict)]
                if len(ids)!=len(batch) or set(ids)!={f['id'] for f in batch}:
                    raise ValueError('SUPPLEMENT_BATCH_COVERAGE')
                candidates={r['factId']:r for r in raw['reviews']}
                for fact in batch:
                    try:validate_review(candidates[fact['id']],fact,full)
                    except ValueError as error:raise ValueError(fact['id']+':'+str(error)) from None
                state['accepted'].update(candidates);save(path,state)
                break
            except (ValueError,TypeError,KeyError,provider.LocalAIOutputError) as error:
                if getattr(error,'usage',None):state['calls'].append(error.usage)
                feedback=str(error)[:1000]
                state['rejections'].append({'code':feedback,'candidate':dict(raw) if isinstance(raw,dict) else None})
                save(path,state)
                if attempt:raise ValueError('SUPPLEMENT_VALIDATION_FAILED:'+feedback) from None
    state['status']='ready';save(path,state)
    return attach(job,facts,scope,path)


def attach(job,facts,scope,path):
    full,missing,binding=plan(job,facts)
    state=json.loads(Path(path).read_text(encoding='utf8'))
    if any(state.get(k)!=v for k,v in binding.items()) or state.get('scope')!=scope:
        raise ValueError('SUPPLEMENT_PUBLICATION_BINDING')
    by_id={f['id']:f for f in missing}
    accepted=[]
    for id,raw in state['accepted'].items():
        if id not in by_id:raise ValueError('SUPPLEMENT_UNREQUESTED_FACT')
        accepted.append(validate_review(raw,by_id[id],full))
    updated=deepcopy(job);ai=updated['result']['ai']
    remaining=len(missing)-len(accepted)+full['omittedFacts']
    ai['analysisFactCoverage']={'total':len(full['facts']),'baseProvided':binding['baseProvided'],
        'additionalReviewed':len(accepted),'remaining':remaining,
        'scope':'program_generated_facts_not_all_source_records'}
    ai['supplementalReviews']=accepted
    ai['omittedAnalysisFacts']=remaining
    analyst.retain_usage(ai,{'usageCalls':state['calls']})
    if not remaining and ai.get('validation',{}).get('allSections') and ai.get('totalRepresentativeGroups',0)<=ai.get('reviewedRepresentativeFacts',0):
        ai.update(status='complete',message='已补齐本报告程序生成的分析事实解读；原始单据完整性和业务原因仍需核对。')
    else:ai.update(status='partial',message='已有解读均已核验，仍有分析事实尚待补充；不等于模型结论核验失败。')
    for section in updated['result']['sectionInsights']:
        section['supplementalReviews']=[r for r in accepted if r['sectionId']==section['sectionId']]
    return updated


def attach_saved(job,scope,db_path):
    """Called only after report authorization; never overwrite the base revision."""
    if not re.fullmatch(r'[a-f0-9]{32}',job['id']):return job
    path=Path(db_path).parent/'analysis-supplements'/(job['id']+'.json')
    if not path.exists():return job
    import sqlite3
    with sqlite3.connect(str(db_path)) as db:
        row=db.execute('SELECT facts_json FROM period_reports WHERE id=? AND scope_key=?',
            (job['id'],json.dumps(scope,ensure_ascii=False,sort_keys=True,separators=(',',':')))).fetchone()
    if row is None:raise ValueError('SUPPLEMENT_SOURCE_SCOPE')
    return attach(job,json.loads(row[0]),scope,path)
