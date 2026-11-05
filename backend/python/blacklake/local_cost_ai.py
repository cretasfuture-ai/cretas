"""One bounded local-model review over minimised deterministic alert facts.

SSH goes only to the verified Canada host, inference only to its loopback Ollama.
No raw source records, names, credentials or remote provider fallback are sent.
AI selects a verification priority, never edits source facts or costs.
"""
import hashlib
import json
import os
import subprocess
from pathlib import Path
from .secrets import load_local_secret,save_local_secret

CHECKS={
 'CHECK_HISTORICAL_VALUATION':'核对当时材料估值与入库成本依据',
 'CHECK_MATERIAL_COVERAGE':'核对主原料、半成品是否完整领料',
 'CHECK_FINAL_GOOD':'核对最终审批良品和成品入库',
 'CHECK_ORDER_LINK':'核对领退料与工单关联三元组',
 'CHECK_PAYROLL':'核对工资计价、工时及是否另记人工',
 'CHECK_COMPARABILITY':'核对产品规格、工艺阶段和材料用量差异',
 'WAIT_COMPLETION':'等待实际完工后再核算',
 'CONFIRM_CURRENCY':'确认币种与金额口径',
}
ALLOWED_CHECKS={
 'NOT_FINISHED':['WAIT_COMPLETION'],
 'FINISHED_GOOD_INVALID':['CHECK_FINAL_GOOD'],
 'FINISHED_UNIT_MISSING':['CHECK_COMPARABILITY'],
 'MATERIAL_LINK_MISSING':['CHECK_ORDER_LINK','CHECK_MATERIAL_COVERAGE'],
 'MATERIAL_VALUE_MISSING':['CHECK_HISTORICAL_VALUATION'],
 'MATERIAL_VALUE_ZERO':['CHECK_HISTORICAL_VALUATION','CHECK_MATERIAL_COVERAGE'],
 'MATERIAL_VALUE_PARTIAL_ZERO':['CHECK_HISTORICAL_VALUATION','CHECK_MATERIAL_COVERAGE'],
 'MOVEMENT_INVALID':['CHECK_ORDER_LINK','CHECK_HISTORICAL_VALUATION'],
 'LABOR_COVERAGE_MISSING':['CHECK_PAYROLL'], 'LABOR_WITH_HOURS_ZERO':['CHECK_PAYROLL'],
 'LABOR_ZERO_UNVERIFIED':['CHECK_PAYROLL'],
 'MATERIAL_SHARE_LOW':['CHECK_MATERIAL_COVERAGE'], 'LABOR_SHARE_HIGH':['CHECK_PAYROLL','CHECK_MATERIAL_COVERAGE'],
 'INPUT_ONLY_CONSUMABLES':['CHECK_MATERIAL_COVERAGE'],
 'UNIT_COST_SPREAD':['CHECK_COMPARABILITY'], 'PRODUCT_NAME_CONFLICT':['CHECK_COMPARABILITY'],
 'PEER_SAMPLE_SMALL':['CHECK_COMPARABILITY'], 'CURRENCY_UNVERIFIED':['CONFIRM_CURRENCY'],
 'PRODUCT_IDENTITY_MISSING':['CHECK_COMPARABILITY'], 'UPSTREAM_COST_BLOCKED':['CHECK_COMPARABILITY'],
}
def fingerprint(alerts):
    return hashlib.sha256(json.dumps({k:v for k,v in alerts.items() if k!='ai'},sort_keys=True,ensure_ascii=False).encode()).hexdigest()

def request_facts(alerts):
    # One representative per stable type; only ratios/counts, not amounts or factory identities.
    selected={}
    for a in alerts['items']:
        selected.setdefault(a['type'],a)
    facts=[]
    for a in selected.values():
        evidence=a['evidence']
        facts.append({'alertId':a['id'],'type':a['type'],'title':a['title'],'family':a['family'],
                      'allowedChecks':ALLOWED_CHECKS[a['type']],
                      'affectedProjects':len(a['entityRefs']),
                      'ratios':{k:evidence[k] for k in ('spread','threshold','materialShare','sampleSize') if k in evidence}})
    return facts

def validate_response(value,facts):
    allowed={f['alertId']:f['allowedChecks'] for f in facts}
    if not isinstance(value,dict) or set(value)!={'reviews'} or not isinstance(value['reviews'],list):raise ValueError('LOCAL_AI_SCHEMA_INVALID')
    seen=set();result=[]
    if not value['reviews'] or len(value['reviews'])>len(facts):raise ValueError('LOCAL_AI_REVIEW_COUNT_INVALID')
    for item in value['reviews']:
        if (not isinstance(item,dict) or set(item)!={'alertId','check','priority'} or item['alertId'] not in allowed
                or item['alertId'] in seen or item['check'] not in CHECKS or item['priority'] not in ('high','normal')):
            raise ValueError('LOCAL_AI_UNSUPPORTED_CLAIM')
        seen.add(item['alertId']);result.append({**item,'suggestion':CHECKS[item['check']]})
        if item['check'] not in allowed[item['alertId']]:raise ValueError('LOCAL_AI_ACTION_TYPE_MISMATCH')
    if seen!=set(allowed):raise ValueError('LOCAL_AI_REVIEW_INCOMPLETE')
    return result

def attach(alerts,path):
    try:
        if not path or not Path(path).exists():return
        saved=load_local_secret(path)
        if not isinstance(saved,dict):raise ValueError('LOCAL_AI_RECORD_INVALID')
        if saved.get('fingerprint')!=fingerprint(alerts):
            alerts['ai']={'status':'stale','message':'本地AI解读与当前规则事实不一致，请重新分析'};return
        if (saved.get('model')!='qwen3:8b' or saved.get('host')!='canada-mini'
                or saved.get('sourceRecordsSent')!=0 or saved.get('externalProviderCalls')!=0
                or saved.get('inputFacts')!=request_facts(alerts)):raise ValueError('LOCAL_AI_PROVENANCE_INVALID')
        reviews=validate_response({'reviews':[{k:r[k] for k in ('alertId','check','priority')} for r in saved['reviews']]},request_facts(alerts))
        alerts['ai']={'status':'complete','model':saved['model'],'host':saved['host'],'reviews':reviews,
                      'message':'本地模型选择的核对优先级；建议待人工确认，未改变成本和规则结论'}
    except Exception:
        alerts['ai']={'status':'invalid','message':'本地AI解读读取或校验失败，规则与成本分析仍可使用'}

def main():
    from .smartbi_store import read,settings
    directory=Path(os.environ['LOCALAPPDATA'])/'Cretas/Blacklake'
    analysis=read(settings(directory/'smartbi-test.dpapi'));alerts=analysis['costAlerts'];facts=request_facts(alerts)
    prompt={'task':'按证据为每类成本/数据问题选择下一步核对动作。不得推断缺失金额、损耗或采购涨价。只返回JSON reviews，项严格含alertId/check/priority。',
            'facts':facts,'allowedChecks':CHECKS,'priorities':['high','normal']}
    body={'model':'qwen3:8b','stream':False,'think':False,'format':'json','options':{'temperature':0,'num_predict':1800},
          'messages':[{'role':'system','content':'You select evidence-bound review actions. Return only the required JSON, no additional fields.'},
                      {'role':'user','content':json.dumps(prompt,ensure_ascii=False)}]}
    # No shell interpolation; JSON is carried via stdin, not command-line args or files on the Mac.
    command=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=6','canada-mini',
             'curl --noproxy "*" -fsS --max-time 180 -H "Content-Type: application/json" --data-binary @- http://127.0.0.1:11434/api/chat']
    result=subprocess.run(command,input=json.dumps(body),capture_output=True,text=True,encoding='utf-8',timeout=195)
    if result.returncode:raise RuntimeError('LOCAL_AI_UNAVAILABLE_NO_REMOTE_FALLBACK')
    response=json.loads(result.stdout)
    reviews=validate_response(json.loads(response['message']['content']),facts)
    record={'fingerprint':fingerprint(alerts),'model':'qwen3:8b','host':'canada-mini','reviews':reviews,
            'inputFacts':facts,'sourceRecordsSent':0,'externalProviderCalls':0,'outputTokens':response.get('eval_count')}
    save_local_secret(directory/'cost-local-ai.dpapi',record)
    print(json.dumps({'success':True,'host':record['host'],'model':record['model'],'reviews':len(reviews),'externalProviderCalls':0,'sourceRecordsSent':0}))

if __name__=='__main__':main()
