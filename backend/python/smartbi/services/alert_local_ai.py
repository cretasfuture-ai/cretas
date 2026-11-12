"""Portable local-only review of minimised, deterministic SmartBI alert facts.

The provider is configured by the operator, never by request payload. It cannot
change rules, numbers, source data or assert an unsupported root cause.
"""
import asyncio
from collections import OrderedDict
import hashlib
import json
import math
import os
import subprocess
import time
from urllib.parse import urlparse

from .alert_actions import COST_CHECKS, COST_ALLOWED

CHECKS={**COST_CHECKS,
 'CHECK_FIELD_MAPPING':'核对原字段与标准字段的映射及含义',
 'CHECK_SOURCE_VALUES':'回查源记录的缺失、零值或格式，不默认补零',
 'CHECK_DUPLICATES':'核对重复记录是否为合法多笔业务，不自动删除',
}
MODELS=frozenset({'qwen3:8b','qwen3:14b','qwen3.8:27b'})
_lock=asyncio.Lock()
_cache=OrderedDict()


class InferenceResult(dict):
    def __init__(self,value,usage):
        super().__init__(value)
        self.usage=usage


class LocalAIOutputError(RuntimeError):
    def __init__(self,usage,raw_content=None):
        super().__init__('LOCAL_AI_OUTPUT_INVALID')
        self.usage=usage
        self.raw_content=raw_content


def provider_usage(response):
    def count(key):
        value=response.get(key)
        return value if type(value) is int and value>=0 else None
    incoming=count('prompt_eval_count');outgoing=count('eval_count');duration=count('total_duration')
    return {'inputTokens':incoming,'outputTokens':outgoing,
            'totalTokens':incoming+outgoing if incoming is not None and outgoing is not None else None,
            'durationSeconds':duration/1_000_000_000 if duration is not None else None,
            'source':'ollama_response','estimated':False}

def fingerprint(report):
    return hashlib.sha256(json.dumps({k:v for k,v in report.items() if k!='ai'},sort_keys=True,ensure_ascii=False,default=str).encode()).hexdigest()

def representative_groups(report):
    selected={}
    for item in report.get('items',[]):
        key=(item['type'],tuple(item.get('checkOptions') or COST_ALLOWED.get(item['type'],['CHECK_SOURCE_VALUES'])))
        selected.setdefault(key,item)
    return selected

def facts_for(report):
    selected=representative_groups(report)
    result=[]
    for item in list(selected.values())[:24]:
        options=item.get('checkOptions') or COST_ALLOWED.get(item['type'],['CHECK_SOURCE_VALUES'])
        options=[o for o in options if o in CHECKS]
        if not options:continue
        evidence=item.get('evidence') or {}
        ratios={}
        for k in ('spread','threshold','materialShare','laborShare','sampleSize'):
            value=evidence.get(k)
            if value is None or isinstance(value,bool):continue
            try: number=float(value)
            except (ValueError,TypeError,OverflowError):continue
            if math.isfinite(number) and abs(number)<1e6:ratios[k]=number
        result.append({'alertId':item['id'],'type':item['type'],'family':item['family'],
                       'sampledRows':len(item.get('sourceRefs') or item.get('entityRefs') or []),
                       'allowedChecks':options,'ratios':ratios})
    return result

def validate_response(value,facts):
    allowed={f['alertId']:f['allowedChecks'] for f in facts}
    if not isinstance(value,dict) or set(value)!={'reviews'} or not isinstance(value['reviews'],list):raise ValueError('AI_SCHEMA_INVALID')
    if not value['reviews'] or len(value['reviews'])>len(facts):raise ValueError('AI_REVIEW_COUNT_INVALID')
    seen=set();result=[]
    for item in value['reviews']:
        if (not isinstance(item,dict) or set(item)!={'alertId','check','priority'} or item['alertId'] not in allowed
                or item['alertId'] in seen or item['check'] not in allowed[item['alertId']]
                or item['priority'] not in ('high','normal')):raise ValueError('AI_UNSUPPORTED_ACTION')
        seen.add(item['alertId']);result.append({**item,'suggestion':CHECKS[item['check']]})
    if seen!=set(allowed):raise ValueError('AI_REVIEW_INCOMPLETE')
    return result

def provider_config():
    transport=os.getenv('SMARTBI_ALERT_AI_TRANSPORT','disabled')
    model=os.getenv('SMARTBI_ALERT_AI_MODEL','qwen3.8:27b')
    if transport not in ('disabled','ssh','http') or model not in MODELS:raise ValueError('LOCAL_AI_CONFIG_INVALID')
    config={'transport':transport,'model':model}
    if transport=='ssh':
        host=os.getenv('SMARTBI_ALERT_AI_SSH_HOST','canada-mini')
        if host not in ('canada-mini','aurora-mini'):raise ValueError('LOCAL_AI_SSH_ALIAS_NOT_ALLOWED')
        config['host']=host
    elif transport=='http':
        base=os.getenv('SMARTBI_ALERT_AI_BASE_URL','http://127.0.0.1:11434')
        parsed=urlparse(base)
        if (parsed.scheme!='http' or parsed.hostname not in ('127.0.0.1','localhost','::1')
                or parsed.port!=11434 or parsed.username or parsed.password or parsed.query or parsed.fragment
                or parsed.path not in ('','/')):raise ValueError('LOCAL_AI_LOOPBACK_REQUIRED')
        config.update(host='loopback',base=base.rstrip('/'))
    return config

def payload_for(facts,model):
    return {'model':model,'stream':False,'think':False,'format':'json','keep_alive':'60s',
            'options':{'temperature':0,'num_ctx':4096,'num_predict':1800},
            'messages':[{'role':'system','content':'Select evidence-bound review actions. Never invent data or causes. Return only JSON {"reviews":[{"alertId":"...","check":"...","priority":"high or normal"}]}. Each check must belong to the allowedChecks for that alert.'},
                        {'role':'user','content':json.dumps({'facts':facts,'checks':CHECKS},ensure_ascii=False)}]}

def infer(payload,config):
    inference_timeout=min(450,max(150,int(getattr(payload,'timeout_seconds',150))))
    if config['transport']=='ssh':
        command=['ssh','-T','-o','BatchMode=yes','-o','ConnectTimeout=6',config['host'],
                 'curl --noproxy "*" -fsS --max-time '+str(inference_timeout)+' -H "Content-Type: application/json" --data-binary @- http://127.0.0.1:11434/api/chat']
        process=subprocess.run(command,input=json.dumps(payload),capture_output=True,text=True,encoding='utf-8',timeout=inference_timeout+10)
        if process.returncode or len(process.stdout)>131072:raise RuntimeError('LOCAL_AI_TRANSPORT_FAILED')
        response=json.loads(process.stdout)
    elif config['transport']=='http':
        import httpx
        with httpx.Client(timeout=inference_timeout,trust_env=False,follow_redirects=False) as client:
            response=client.post(config['base']+'/api/chat',json=payload)
            response.raise_for_status()
            if len(response.content)>131072:raise RuntimeError('LOCAL_AI_RESPONSE_TOO_LARGE')
            response=response.json()
    else:raise RuntimeError('LOCAL_AI_DISABLED')
    if response.get('model')!=payload['model']:raise RuntimeError('LOCAL_AI_MODEL_MISMATCH')
    usage=provider_usage(response)
    try:return InferenceResult(json.loads(response['message']['content']),usage)
    except (ValueError,TypeError,KeyError):
        message=response.get('message')
        raw=message.get('content') if isinstance(message,dict) else None
        raise LocalAIOutputError(usage,raw if isinstance(raw,str) else None) from None

async def enrich(report,requester=None,*,analysis_context=None):
    """Optional AI failure is reported explicitly without invalidating the rules."""
    if os.getenv('SMARTBI_LOCAL_ANALYST_ENABLED')=='1':
        from .local_analyst import enrich as explain
        return await explain(report,requester,analysis_context)
    try:
        config=provider_config()
        if config['transport']=='disabled':
            report['ai']={'status':'not_configured','message':'本地AI尚未配置，规则分析已完成'};return report
        facts=facts_for(report)
        if not facts:
            report['ai']={'status':'not_applicable','message':'当前没有需要AI核对的提醒'};return report
        key=(fingerprint(report),json.dumps(config,sort_keys=True))
        async with _lock:
            if key in _cache and time.monotonic()-_cache[key][0]<600:
                report['ai']=dict(_cache[key][1],cacheHit=True,currentRequestUsage={'inputTokens':0,'outputTokens':0,'totalTokens':0,'source':'cache','estimated':False});return report
        from .alert_ai_coordination import run
        scope=report.get('scope') or {}
        tenant=scope.get('factoryId') if isinstance(scope,dict) else scope
        raw=await asyncio.to_thread(run,infer,payload_for(facts,config['model']),config,tenant,requester)
        reviews=validate_response(raw,facts)
        ai={'status':'complete','model':config['model'],'host':config['host'],'reviews':reviews,
            'message':'本地AI选择的核对动作和优先级，待人工确认；金额与规则结论未改变',
            'inputFingerprint':key[0],'sourceRecordsSent':0,'externalProviderCalls':0,'cacheHit':False,
            'reviewedRepresentativeFacts':len(facts),'maxRepresentativeFacts':24,'representativeOnly':True}
        usage=getattr(raw,'usage',None)
        if usage is not None:ai.update(usage=usage,currentRequestUsage=usage)
        total=len(representative_groups(report))
        ai['totalRepresentativeGroups']=total
        if total>len(facts):
            ai.update(status='partial',message=f'本次本地AI核对 {len(facts)}/{total} 类代表问题，其余尚未核对；规则提醒不受影响')
        async with _lock:
            _cache[key]=(time.monotonic(),ai)
            while len(_cache)>128:_cache.popitem(last=False)
            report['ai']=ai
    except Exception as exc:
        from .alert_ai_coordination import ReviewBusy
        if isinstance(exc,ReviewBusy):
            report['ai']={'status':'busy','message':'本地AI正在按顺序处理任务，本次仍在等待；周期报告会自动继续排队，规则提醒已完成'}
            return report
        report['ai']={'status':'unavailable','message':'本地AI暂不可用或输出未通过校验，规则提醒仍可使用'}
    return report
