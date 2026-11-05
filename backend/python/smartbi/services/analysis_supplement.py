"""Resume omitted model-safe facts without changing an immutable base report."""
import asyncio
from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path
import re
from datetime import datetime,timezone,timedelta

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
    'PACKAGING_ONLY':'目前仅见包装耗材，需核对食材或半成品价值是否另记。',
    'LABOR_SHARE':'先确认材料是否记全，再核对人工工资。',
    'IDENTITY_CONFLICT':'名称、规格或加工阶段可能不同，需核对确认。',
    'MEASUREMENT_BASIS':'需确认实称还是按产量默认生成，不能据此判断真实损耗。',
    'PARTIAL_PERIOD':'本周期未结束或采集尚未覆盖完整周期，不能当作全周期结果。',
    'HISTORICAL_BALANCE':'缺少对应历史库存，不能倒推当时余额。',
    'NO_DATA':'缺少可核验证据或数据，无法判断。',
}


def digest(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,ensure_ascii=False,default=str).encode()).hexdigest()


def plan(job,facts,*,exhaustive=False):
    context=deepcopy(facts)
    context['analysisHistory']=job['result'].get('history',{})
    if job['fingerprint']!=context['periodMetadata']['fingerprint'] or job['result']['runId']!=context['runId']:
        raise ValueError('SUPPLEMENT_FACT_BINDING')
    base=build(context['costAlerts'],context)
    full=build(context['costAlerts'],context,include_all=True)
    selected={f['id'] for f in base['facts']}
    missing=full['facts'] if exhaustive else [f for f in full['facts'] if f['id'] not in selected]
    return full,missing,{'schema':SCHEMA,'reportId':job['id'],'fingerprint':job['fingerprint'],
        'baseInputFingerprint':job['result']['ai'].get('inputFingerprint'),
        'evidenceDigest':digest(full),'totalFacts':len(full['facts']),'baseProvided':len(base['facts']),
        'unrepresentedGroups':full['omittedFacts']}


def validate_review(raw,fact,full):
    if not isinstance(raw,dict) or set(raw)!=FIELDS or raw['factId']!=fact['id']:
        raise ValueError('SUPPLEMENT_FACT_ID_OR_SCHEMA')
    texts=[raw.get(k) for k in ('whatItMeans','nextStep','cannotConclude')]
    if any(isinstance(t,str) and re.search(r'[零一二三四五六七八九十百千万两]+(?:个|条|张|次|组|项|笔|名|份|样本)',t) for t in texts):
        raise ValueError('SUPPLEMENT_UNBOUND_CHINESE_QUANTITY')
    meaning=raw.get('whatItMeans')
    if isinstance(meaning,str):
        if analyst._asserted(r'(?:高于|低于|多于|少于|优于).{0,5}(?:其他|其它)(?:组|产品)',meaning):
            raise ValueError('SUPPLEMENT_UNSUPPORTED_CROSS_GROUP_COMPARISON')
        size=fact.get('statistics',{}).get('sampleSize')
        if size is not None and size<2 and analyst._asserted(r'波动(?:很|较)?大|成本(?:很|较)?稳定|波动(?:很|较)?小',meaning):
            raise ValueError('SUPPLEMENT_VARIATION_REQUIRES_MULTIPLE_SAMPLES')
    sid=fact['sectionId']
    section={'sectionId':sid,'evidenceRefs':[fact['id']],
        'whatItMeans':raw['whatItMeans'],'whyItMatters':raw['whatItMeans'],
        'hypotheses':[],'nextStep':raw['nextStep'],'owner':raw['owner'],
        'cannotConclude':raw['cannotConclude'],'followupQuestion':'还需要核对哪些业务记录？',
        'considerations':fact['considerations']}
    one={**full,'sectionIds':[sid],'requiredConsiderations':{sid:fact['considerations']},
        'requiredFactRefs':{sid:[fact['id']]},'narrativeRequirements':{sid:[]}}
    _,accepted=analyst.validate({'reviews':[],'sections':[section]},one,[])
    label={'weighted_unit_cost':'单位成本','product_coverage':'资料覆盖',
        'upstream_unit_conversion':'原料与成品单位换算','material_quantity_valuation_split':'领料数量与估值',
        'common_input_price_check':'相同材料单价','same_product_bridge':'同产品工单差异',
        'material_link_coverage':'领退料关联'}.get(fact['kind'],'补充核对')
    title=full['displayFacts'][fact['id']]['text'].split('：',1)[0]
    return {**raw,'sectionId':sid,'label':title+' · '+label,'statistics':accepted[0]['statistics']}


def payload(full,batch,model,feedback=None):
    from .analysis_playbook import HAZARDS
    ids={f['id'] for f in batch};groups={f.get('groupId') for f in batch if f.get('groupId')}
    # Context is explicitly separate from facts that require individual answers.
    related=[f for f in full['facts'] if f['id'] not in ids and f.get('groupId') in groups]
    data={'facts':batch,'relatedContext':related[:24],'kindDefinitions':KINDS,
        'riskDefinitions':HAZARDS,'allowedOwners':sorted(analyst.OWNERS),
        'requiredRiskWording':{f['id']:[RISK_WORDING.get(c,HAZARDS.get(c,c)) for c in f['considerations']] for f in batch},
        'validationFeedback':feedback,
        'instruction':'逐条解释facts，不遗漏、不合并。groupId相同才可讨论关联；relatedContext只作背景，不新增待核对项。'
        '每条返回factId、whatItMeans、nextStep、cannotConclude、owner五个字段。'
        'owner必须从allowedOwners原样选择一个值，不能把多个选项拼接成新的部门组合。'
        'factId原样复制。文字用工厂人员看得懂的白话，说明哪里可能有问题、找谁核对什么。'
        '正文不写数字或中文数量（例如“两个”“三个”）、事实编号、产品代号，数字和真实产品名由系统绑定。'
        '样本数量只说“样本少”；不能因样本少就认定波动大，也不比较其他产品组。'
        '每个事实的considerations风险都必须在正文解释，不能断言原因已确认。每个文字字段不少于四字且不超过两百四十字。'}
    return analyst.AnalystPayload(model=model,stream=False,think=False,format='json',keep_alive='60s',
        options={'temperature':0,'num_ctx':16384,'num_predict':6000},messages=[
            {'role':'system','content':'你是工厂生产与成本复核助手。只根据提供的匿名程序统计给核对建议。'
             '不编造金额、数量、采购价或业务因果。只返回JSON对象 {"reviews":[逐条核对结果]}。'},
            {'role':'user','content':json.dumps(data,ensure_ascii=False)}])


def save(path,state,guard=None):
    if guard:guard()
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    temporary=path.with_suffix('.tmp')
    temporary.write_text(json.dumps(state,ensure_ascii=False,indent=2),encoding='utf8')
    os.replace(temporary,path)


class SupplementRetry(RuntimeError):pass


def stamp():return datetime.now(timezone.utc).isoformat()


def blocker_message(code):
    if 'OWNER' in code:return '处理部门没有按规定填写，正在纠正后重试。'
    if 'NUMBER' in code or 'QUANTITY' in code:return '说明中出现未绑定的数字或数量，正在重写。'
    if 'RISK' in code:return '风险或样本限制没有讲清楚，正在补充说明。'
    if 'SCOPE' in code or 'COVERAGE' in code:return '引用事实不齐或不对应，正在重新核对。'
    if 'VARIATION' in code or 'COMPARISON' in code:return '比较结论缺少足够依据，正在改写。'
    if 'BUSY' in code:return '本地模型正在处理其他任务，稍后自动继续。'
    if 'TRANSPORT' in code:return '本地模型连接暂不可用，恢复后自动继续。'
    return '这批说明尚未通过校验，正在纠正后重试。'


async def complete(job,facts,scope,path,requester,*,batch_size=8,exhaustive=False,max_batches=None,guard=None):
    from .alert_ai_coordination import run
    from . import alert_local_ai as provider
    full,missing,binding=plan(job,facts,exhaustive=exhaustive)
    config=provider.provider_config()
    if config['transport']=='disabled':raise ValueError('SUPPLEMENT_LOCAL_MODEL_REQUIRED')
    state=json.loads(Path(path).read_text(encoding='utf8')) if Path(path).exists() else {
        **binding,'scope':scope,'accepted':{},'calls':[],'rejections':[],'status':'running'}
    if any(state.get(k)!=v for k,v in binding.items()) or state.get('scope')!=scope:
        raise ValueError('SUPPLEMENT_CHECKPOINT_BINDING')
    by_id={f['id']:f for f in missing}
    if exhaustive:state['mode']='all_facts'
    for id,raw in list(state['accepted'].items()):
        if id not in by_id:raise ValueError('SUPPLEMENT_UNREQUESTED_FACT')
        try:validate_review(raw,by_id[id],full)
        except ValueError as error:
            state['rejections'].append({'code':str(error),'candidate':raw,'previouslyAccepted':True})
            del state['accepted'][id]
    save(path,state,guard)
    pending=[f for f in missing if f['id'] not in state['accepted']]
    for offset in range(0,len(pending),batch_size):
        if max_batches is not None and offset//batch_size>=max_batches:break
        batch=pending[offset:offset+batch_size]
        feedback=state.get('lastError')
        for attempt in range(2):
            batch=[f for f in batch if f['id'] not in state['accepted']]
            if not batch:break
            raw=None
            try:
                state.update(status='generating' if not feedback else 'correcting',updatedAt=stamp(),
                    currentFacts=[{'id':f['id'],'label':full['displayFacts'][f['id']]['text'].split('：',1)[0]} for f in batch])
                save(path,state,guard)
                raw=await asyncio.to_thread(run,provider.infer,payload(full,batch,config['model'],feedback),
                                           config,facts['costAlerts']['scope'],requester)
                if getattr(raw,'usage',None):state['calls'].append(raw.usage)
                if not isinstance(raw,dict) or set(raw)!={'reviews'} or not isinstance(raw['reviews'],list):
                    raise ValueError('SUPPLEMENT_BATCH_SCHEMA')
                ids=[r.get('factId') for r in raw['reviews'] if isinstance(r,dict)]
                if len(ids)!=len(batch) or set(ids)!={f['id'] for f in batch}:
                    raise ValueError('SUPPLEMENT_BATCH_COVERAGE')
                candidates={r['factId']:r for r in raw['reviews']}
                errors=[]
                for fact in batch:
                    try:
                        validate_review(candidates[fact['id']],fact,full)
                        state['accepted'][fact['id']]=candidates[fact['id']]
                    except ValueError as error:errors.append(fact['id']+':'+str(error))
                save(path,state,guard)
                if errors:raise ValueError(';'.join(errors))
                state['accepted'].update(candidates)
                state.update(status='queued',lastError=None,blocker=None,updatedAt=stamp(),currentFacts=[])
                save(path,state,guard)
                break
            except (ValueError,TypeError,KeyError,provider.LocalAIOutputError) as error:
                if getattr(error,'usage',None):state['calls'].append(error.usage)
                feedback=str(error)[:1000]
                state.update(lastError=feedback,status='correcting',updatedAt=stamp())
                state['rejections'].append({'code':feedback,'candidate':dict(raw) if isinstance(raw,dict) else None})
                save(path,state,guard)
                if attempt:
                    state.update(status='retrying',retryAt=(datetime.now(timezone.utc)+timedelta(seconds=60)).isoformat(),
                        blocker={'code':feedback,'message':blocker_message(feedback),'facts':state['currentFacts']})
                    save(path,state,guard)
                    if exhaustive:raise SupplementRetry(feedback) from None
                    raise ValueError('SUPPLEMENT_VALIDATION_FAILED:'+feedback) from None
            except Exception as error:
                from .alert_ai_coordination import ReviewBusy
                # Lease loss and cancellation are never converted into successful checkpoints.
                from .period_reports import LeaseError
                if isinstance(error,(LeaseError,asyncio.CancelledError)):raise
                code='MODEL_BUSY' if isinstance(error,ReviewBusy) else 'LOCAL_AI_TRANSPORT_FAILED'
                state.update(status='waiting_model',lastError=code,updatedAt=stamp(),
                    retryAt=(datetime.now(timezone.utc)+timedelta(seconds=60)).isoformat(),
                    blocker={'code':code,'message':blocker_message(code),'facts':state.get('currentFacts',[])})
                save(path,state,guard)
                raise SupplementRetry(code) from None
    state['status']='ready' if len(state['accepted'])==len(missing) and not full['omittedFacts'] else 'queued'
    if full['omittedFacts']:
        state.update(status='waiting_source',blocker={'code':'UPSTREAM_FACTS_INCOMPLETE',
            'message':'上游仍有分组未生成完整分析事实，保留已完成结果，等待数据流程补齐。','facts':[]})
    save(path,state,guard)
    return attach(job,facts,scope,path)


def attach(job,facts,scope,path):
    state=json.loads(Path(path).read_text(encoding='utf8'))
    exhaustive=state.get('mode')=='all_facts'
    full,missing,binding=plan(job,facts,exhaustive=exhaustive)
    if any(state.get(k)!=v for k,v in binding.items()) or state.get('scope')!=scope:
        raise ValueError('SUPPLEMENT_PUBLICATION_BINDING')
    by_id={f['id']:f for f in missing}
    accepted=[]
    for id,raw in state['accepted'].items():
        if id not in by_id:raise ValueError('SUPPLEMENT_UNREQUESTED_FACT')
        accepted.append(validate_review(raw,by_id[id],full))
    updated=deepcopy(job);ai=updated['result']['ai']
    remaining=len(missing)-len(accepted)+full['omittedFacts']
    base_ids={f['id'] for f in build(facts['costAlerts'],{**facts,'analysisHistory':job['result'].get('history',{})})['facts']}
    ai['analysisFactCoverage']={'total':len(full['facts']),'baseProvided':binding['baseProvided'],
        'additionalReviewed':sum(r['factId'] not in base_ids for r in accepted) if exhaustive else len(accepted),
        'reviewed':len(accepted),'remaining':remaining,'mode':'all_facts' if exhaustive else 'omitted_only',
        'phase':state.get('status','queued'),'currentFacts':state.get('currentFacts',[]),
        'blocker':state.get('blocker'),'retryAt':state.get('retryAt') if state.get('blocker') else None,
        'attempts':len(state.get('calls',[])),'updatedAt':state.get('updatedAt'),
        'scope':'program_generated_facts_not_all_source_records'}
    ai['supplementalReviews']=accepted
    ai['omittedAnalysisFacts']=remaining
    analyst.retain_usage(ai,{'usageCalls':state['calls']})
    if not remaining and ai.get('validation',{}).get('allSections') and (exhaustive or ai.get('totalRepresentativeGroups',0)<=ai.get('reviewedRepresentativeFacts',0)):
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
