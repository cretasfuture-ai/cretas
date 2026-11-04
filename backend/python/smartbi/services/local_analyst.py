"""Local-model business interpretation of precomputed, anonymous evidence.

The model selects statistics and explains them. The server binds real figures
and identities after validation; financial arithmetic remains deterministic.
"""
import asyncio
from collections import OrderedDict
from copy import deepcopy
import hashlib
import json
import re
import time

from .analysis_evidence import build
from .analysis_playbook import VERSION,SECTIONS,HAZARDS,SYSTEM_PROMPT,curriculum

_cache=OrderedDict()
OWNERS={'生产','仓库','财务','生产/仓库','生产/财务','主数据/生产','数据管理员'}
FIELDS={'sectionId','evidenceRefs','whatItMeans','whyItMatters','hypotheses','nextStep','owner','cannotConclude','followupQuestion','considerations'}
HAZARD_WORDS={
    'MATERIAL_MIX':(r'材料|半成品',r'组合|用量|耗量|折算|估值'),
    'UNIT_CONVERSION':(r'单位|规格|换算',r'核|确认'),
    'MEASUREMENT_BASIS':(r'实称|称重|计量|默认|按产量',r'损耗|出成|消耗|投入'),
    'MISSING_COST':(r'缺|漏|不全|不完整|未记|零|记录',r'材料|成本|计价|估值|数据'),
    'PACKAGING_ONLY':(r'包装|耗材',r'食材|半成品|原料|主料'),
    'ZERO_LABOR_BASELINE':(r'人工|工资',r'零|漏|另记|未记'),
    'LABOR_SHARE':(r'材料',r'人工|工资'),
    'IDENTITY_CONFLICT':(r'名称|规格|阶段',r'核|确认|不能'),
    'SMALL_SAMPLE':(r'样本|少',r'不能|不足|核|不代表'),
    'PARTIAL_PERIOD':(r'周期|采集|范围|数据',r'未结束|不完整|未齐|不足|截止|截至|不代表'),
    'HISTORICAL_BALANCE':(r'历史|当时',r'库存',r'缺|无|不能|未'),
    'NO_DATA':(r'数据|证据|记录',r'不能|无法|未提供|缺少'),
}


class AnalystPayload(dict):
    timeout_seconds=450


def _curriculum(pack):
    value=curriculum(pack['sectionIds'])
    if not pack.get('isCostDataset',True):
        value['coverage']={'label':'数据范围与质量','indicator':'扫描覆盖与数据质量','formula':'已扫描行数、缺失和重复等质量统计',
                           'howToRead':'非生产工单数据不启用制造成本推断，不要求补材料或工资字段。'}
    return value


def _asserted(pattern,text):
    """Do not treat 'cannot conclude X' as an assertion of X."""
    for clause in re.split(r'[。；;\n]|但是|不过|然而|但',text):
        for match in re.finditer(pattern,clause):
            prefix=clause[max(0,match.start()-12):match.start()]
            if not re.search(r'不能|不应|不代表|无法断言|未能证实|尚未证实',prefix):return True
    return False


def payload_for(pack,review_facts,model,repair=None):
    from .alert_local_ai import CHECKS
    from .analysis_distillation import EXAMPLES
    from .analysis_methods import definitions_for,directions_for
    data={'profileVersion':VERSION,'curriculum':_curriculum(pack),
          'statisticDefinitions':definitions_for(pack),
          'secondPassReviewDirections':directions_for(pack),
          'scopeGuard':'只解释一般数据范围与质量，不要求补工单、材料或工资' if not pack.get('isCostDataset',True) else '只分析当前生产工单证据，不扩展到未经授权的来源',
          'distilledSecondPassExamples':EXAMPLES,
          'facts':pack['facts'],'requiredConsiderations':pack['requiredConsiderations'],
          'riskDefinitions':{h:HAZARDS[h] for codes in pack['requiredConsiderations'].values() for h in codes},
          'sectionPlan':[{'sectionId':sid,'allowedEvidenceRefs':[f['id'] for f in pack['facts'] if f['sectionId']==sid],
                          'mustUseEvidenceRefs':list(dict.fromkeys(next(f['id'] for f in pack['facts'] if f['sectionId']==sid and code in f['considerations']) for code in pack['requiredConsiderations'][sid]))}
                         for sid in pack['sectionIds']],
          'reviewFacts':review_facts,'allowedChecks':CHECKS,
          'allowedOwners':sorted(OWNERS),
          'instruction':'sectionPlan逐板块列出了允许引用和必须引用的事实编号。sections中的evidenceRefs只从该板块allowedEvidenceRefs选，并包含mustUseEvidenceRefs，不能放reviews里的alertId。事实编号请原样复制。解释文字不写编号或任何数字，系统单独展示引用统计。'}
    if repair:data['validationFeedback']=repair
    return AnalystPayload(model=model,stream=False,think=False,format='json',keep_alive='60s',
        options={'temperature':0,'num_ctx':16384,'num_predict':6000},
        messages=[{'role':'system','content':SYSTEM_PROMPT},{'role':'user','content':json.dumps(data,ensure_ascii=False)}])


def validate(value,pack,review_facts):
    from .alert_local_ai import validate_response
    if not isinstance(value,dict) or set(value)!={'reviews','sections'}:raise ValueError('ANALYST_SCHEMA')
    if not review_facts and value['reviews']!=[]:raise ValueError('ANALYST_UNSUPPORTED_REVIEW')
    reviews=validate_response({'reviews':value['reviews']},review_facts) if review_facts else []
    if not isinstance(value['sections'],list) or len(value['sections'])!=len(pack['sectionIds']):raise ValueError('ANALYST_SECTION_COVERAGE')
    evidence={fact['id']:fact for fact in pack['facts']};seen=set();sections=[]
    for section in value['sections']:
        if not isinstance(section,dict) or set(section)!=FIELDS:raise ValueError('ANALYST_SECTION_SCHEMA')
        sid=section['sectionId']
        if sid not in pack['sectionIds'] or sid in seen:raise ValueError('ANALYST_SECTION_ID')
        seen.add(sid);refs=section['evidenceRefs']
        if isinstance(refs,list):
            refs=[f'F{int(match[1]):03d}' if isinstance(ref,str) and (match:=re.fullmatch(r'[Ff](\d{1,3})',ref.strip())) else ref for ref in refs]
        allowed=[f['id'] for f in pack['facts'] if f['sectionId']==sid]
        if (not isinstance(refs,list) or not 1<=len(refs)<=18 or any(not isinstance(r,str) or r not in evidence or evidence[r]['sectionId']!=sid for r in refs)
                or len(set(refs))!=len(refs)):raise ValueError('ANALYST_EVIDENCE_SCOPE:'+sid+';allowed='+','.join(allowed))
        codes=section['considerations']
        if not isinstance(codes,list) or any(not isinstance(c,str) for c in codes) or set(codes)!=set(pack['requiredConsiderations'][sid]):raise ValueError('ANALYST_REQUIRED_RISKS')
        covered={c for ref in refs for c in evidence[ref]['considerations']}
        if not set(codes)<=covered:raise ValueError('ANALYST_RISK_EVIDENCE')
        if section['owner'] not in OWNERS:raise ValueError('ANALYST_OWNER')
        hypotheses=section['hypotheses']
        if not isinstance(hypotheses,list) or len(hypotheses)>2:raise ValueError('ANALYST_HYPOTHESES')
        texts=[section[k] for k in ('whatItMeans','whyItMatters','nextStep','cannotConclude','followupQuestion')]+hypotheses
        if any(not isinstance(t,str) or not 4<=len(t)<=240 for t in texts):raise ValueError('ANALYST_TEXT_LENGTH')
        combined=' '.join(texts)
        if re.search(r'\d|百分之|[零一二三四五六七八九十百千万点]+(?:倍|元|公斤|包|盒)',combined):raise ValueError('ANALYST_UNBOUND_NUMBER_OR_ALIAS')
        if _asserted(r'已证实.{0,8}(浪费|漏记|低效)|确定是|原因就是|利润为|毛利率为|必然',combined):raise ValueError('ANALYST_UNSUPPORTED_CERTAINTY')
        for text in texts:
            for clause in re.split(r'[。；;\n]',text):
                if re.search(r'严重低估|严重高估',clause) and not re.search(r'如果|可能|若|不能|无法|不代表|未证实',clause):
                    raise ValueError('ANALYST_UNSUPPORTED_COVERAGE_IMPACT')
        movement_known=any(f['sectionId']=='inventory' and f.get('statistics',{}).get('movementOrders',0)>0 for f in pack['facts'])
        if sid=='inventory' and movement_known and _asserted(r'无(?:历史)?出入库(?:记录|流水|数据)|(?:没有|缺少|缺乏|未提供|未同步).{0,8}出入库(?:记录|流水|数据)',combined):
            raise ValueError('ANALYST_INVENTORY_AVAILABILITY_CONTRADICTION')
        for code in codes:
            if any(not re.search(pattern,combined) for pattern in HAZARD_WORDS.get(code,())):raise ValueError('ANALYST_RISK_NOT_EXPLAINED:'+code)
        sections.append({**section,'evidenceRefs':refs,'title':_curriculum(pack)[sid]['label'],
                         'statistics':[deepcopy(pack['displayFacts'][ref]) for ref in refs],
                         'indicator':_curriculum(pack)[sid],
                         'hypotheses':['待核实：'+text for text in hypotheses]})
    if seen!=set(pack['sectionIds']):raise ValueError('ANALYST_SECTION_COVERAGE')
    return reviews,sections


def _usage(calls):
    if not calls:return None
    def total(field):
        return sum(c[field] for c in calls) if all(c.get(field) is not None for c in calls) else None
    return {'inputTokens':total('inputTokens'),'outputTokens':total('outputTokens'),'totalTokens':total('totalTokens'),
            'durationSeconds':total('durationSeconds'),'source':'ollama_response','estimated':False,
            'requestIds':[c['requestId'] for c in calls if c.get('requestId')]}


async def enrich(report,requester=None,analysis_context=None):
    from . import alert_local_ai as provider
    from .alert_ai_coordination import run,ReviewBusy
    try:config=provider.provider_config()
    except (ValueError,TypeError):
        report['ai']={'status':'unavailable','analysisProfileVersion':VERSION,'message':'本地模型配置不可用，未展示未经验证的解读'};return report
    if config['transport']=='disabled':
        report['ai']={'status':'not_configured','analysisProfileVersion':VERSION,'message':'本地模型未配置，当前保留规则统计'};return report
    pack=build(report,analysis_context);facts=provider.facts_for(report)
    key=hashlib.sha256(json.dumps({'version':VERSION,'provider':config,'report':provider.fingerprint(report),
                                 'evidence':pack},sort_keys=True,ensure_ascii=False,default=str).encode()).hexdigest()
    cached=_cache.get(key)
    if cached and time.monotonic()-cached[0]<600:
        report['ai']=deepcopy(cached[1]);report['ai'].update(cacheHit=True,currentRequestUsage={'inputTokens':0,'outputTokens':0,'totalTokens':0,'source':'cache','estimated':False})
        return report
    calls=[];feedback=None
    try:
        for attempt in range(2):
            try:
                raw=await asyncio.to_thread(run,provider.infer,payload_for(pack,facts,config['model'],feedback),config,report.get('scope'),requester)
                if getattr(raw,'usage',None):calls.append(raw.usage)
                reviews,sections=validate(raw,pack,facts)
                break
            except (ValueError,TypeError,KeyError,provider.LocalAIOutputError) as error:
                if getattr(error,'usage',None):calls.append(error.usage)
                # Only fixed validation codes, not business data or model text.
                feedback=str(error)[:1000]
                if attempt:raise ValueError('ANALYST_VALIDATION_FAILED') from None
        ai={'status':'complete','model':config['model'],'host':config['host'],'reviews':reviews,
            'message':'本地模型已完成统计解读与核对建议；证据已校验，原因仍待业务确认',
            'analysisProfileVersion':VERSION,'analysisSections':sections,'validation':{'schema':True,'allSections':True,'evidenceScope':True,'numbersBoundByProgram':True,'requiredRisks':True},
            'sourceRecordsSent':0,'externalProviderCalls':0,'cacheHit':False,'representativeOnly':True,
            'reviewedRepresentativeFacts':len(facts),'totalRepresentativeGroups':len(provider.representative_groups(report)),
            'maxRepresentativeFacts':24,'omittedAnalysisFacts':pack['omittedFacts'],'repairAttempts':attempt,
            'usageCalls':calls,'usage':_usage(calls),'currentRequestUsage':_usage(calls),'inputFingerprint':key}
        if pack['omittedFacts'] or len(provider.representative_groups(report))>len(facts):
            ai.update(status='partial',message='本地模型完成所提供代表事实的解读，仍有未覆盖证据，不能称为全部核对')
        report['ai']=ai;_cache[key]=(time.monotonic(),deepcopy(ai))
        while len(_cache)>64:_cache.popitem(last=False)
    except Exception as error:
        report['ai']={'status':'busy' if isinstance(error,ReviewBusy) else 'unavailable',
                      'message':'本地经营分析仍在排队' if isinstance(error,ReviewBusy) else '本地经营解读未通过证据校验或服务暂不可用；规则统计保留，未展示未经校验的结论',
                      'analysisProfileVersion':VERSION,'usage':_usage(calls),'usageCalls':calls,'validationFeedback':feedback}
    return report


def retain_usage(ai,previous):
    """A durable retry retains prior consumed calls without counting them twice."""
    calls=[];seen=set()
    for source in (previous,ai):
        for call in source.get('usageCalls',[]):
            request=call.get('requestId')
            if request and request not in seen:
                seen.add(request);calls.append(call)
    if calls:
        ai['usageCalls']=calls;ai['usage']=_usage(calls)


def apply_sections(sections,ai):
    """Keep deterministic formulas/evidence, clearly label accepted model interpretation."""
    by_id={s['sectionId']:s for s in ai.get('analysisSections',[])} if ai.get('status') in ('complete','partial') else {}
    for section in sections:
        local=by_id.get(section['sectionId'])
        if not local:continue
        section['localAnalysis']=local;section['ruleSummary']=section['summary']
        section['summary']=local['whatItMeans']
        section['observations']=list(dict.fromkeys([f['text'] for f in local['statistics']]+section['observations']))
        section['hypotheses']=list(dict.fromkeys(local['hypotheses']+section['hypotheses']))
        section['nextSteps']=[local['nextStep']+'（'+local['owner']+'）']+section['nextSteps']
        section['limitations']=[local['cannotConclude']]+section['limitations']
        section['suggestedQuestions']=list(dict.fromkeys([local['followupQuestion']]+section['suggestedQuestions']))
