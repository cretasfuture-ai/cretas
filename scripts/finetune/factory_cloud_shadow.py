"""Resumable synthetic shadow evaluation; never sends a gold label or model weight.

Offline TF-IDF is an experiment proxy, not the production BM25/vector pipeline.
One cloud result per model/case is reused across both local routing arms.
"""
import argparse
import asyncio
import json
from pathlib import Path
import time
import httpx
from factory_experiment import read,save,write,sha,verify

MODELS=['gpt-6-luna','gpt-5.4-mini-2026-03-17','gpt-5.4-2026-03-05']
INSTRUCTIONS=('你是食品工厂意图分类器，只判断意图，不执行操作。选最具体且语义匹配的候选。'
 '区分库存原料/成品、产品/人员排名、今日产量/进度、质检项目/结果/全厂报表、打卡记录/人数统计、设备告警/实时运行健康。'
 '缺少执行实体ID可保留明确意图；缺业务对象、指标或候选无合适项选__CLARIFY__。'
 '明确要求系统当前没有的能力选UNSUPPORTED_CAPABILITY，闲聊选OUT_OF_DOMAIN。不能猜测邻近标签。')


def plan(root):
    from sklearn.feature_extraction.text import TfidfVectorizer
    verify(root)
    rows=json.loads((root/'catalog.json').read_text(encoding='utf-8'))['canonical_intents']
    docs=[r['intent_name']+' '+str(r.get('description') or '')+' '+r['intent_code'] for r in rows]
    vec=TfidfVectorizer(analyzer='char',ngram_range=(1,3),sublinear_tf=True)
    mat=vec.fit_transform(docs)
    bycode={r['intent_code']:r for r in rows};result=[]
    for split in ['test','reviewed-35']:
        for i,row in enumerate(read(root/(split+'.jsonl'))):
            scores=(mat@vec.transform([row['text']]).T).toarray().ravel()
            ids=[rows[n]['intent_code'] for n in scores.argsort()[::-1][:24]]
            for label in ['OUT_OF_DOMAIN','UNSUPPORTED_CAPABILITY']:
                if label not in ids: ids.append(label)
            choices=[dict(value=label,description=bycode[label]['intent_name']+'：'+str(bycode[label].get('description') or '')[:100]) for label in ids]
            choices.append(dict(value='__CLARIFY__',description='对象/指标/语义不明确或没有合适候选，需要澄清。'))
            # Explicit negative boundary for missing factory capabilities; no gold used.
            for c in choices:
                if c['value']=='UNSUPPORTED_CAPABILITY': c['description']='明确请求当前目录不具备的能力，如通用设备实时运行健康或运行时长报告。不能把设备报警当作机器健康结论。'
            result.append(dict(id=f'{split}:{i}',split=split,text=row['text'],choices=choices))
    path=root/'cloud-plan.jsonl'
    if len(result)*len(MODELS)>500:
        raise ValueError('Experiment exceeds bounded 500-request plan; do not silently enlarge')
    if path.exists():
        if read(path)!=result: raise ValueError('Cloud plan changed')
    else: write(path,result)
    save(root/'cloud-protocol.json',dict(models=MODELS,plan_sha256=sha(path),retrieval='offline char TF-IDF top24, NOT production BM25/vector/reranker',reasoning_effort='low',store=False,max_output_tokens=1024,confidence=.65,concurrency=3,retries=0,teacher_labels_used_in_training=False))
    return result


async def run(root):
    rows=plan(root)
    config={}
    for line in (Path.home()/'.codex/secrets/cretas-openai-eval.env').read_text(encoding='utf-8-sig').splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            k,v=line.split('=',1);config[k.strip()]=v.strip().strip('"').strip("'")
    headers={'Authorization':'Bearer '+config['OPENAI_API_KEY']}
    for k,h in [('OPENAI_PROJECT_ID','OpenAI-Project'),('OPENAI_ORGANIZATION_ID','OpenAI-Organization')]:
        if config.get(k):headers[h]=config[k]
    output=root/'cloud-results.jsonl';existing=read(output) if output.exists() else []
    done={(r['model'],r['id']) for r in existing}
    if len(done)!=len(existing):raise ValueError('Duplicate cloud evidence; inspect before resuming')
    ledgerpath=root/'cloud-ledger.json'; ledger=json.loads(ledgerpath.read_text(encoding='utf-8')) if ledgerpath.exists() else []
    if any(r['state']=='pending' for r in ledger):raise ValueError('Unresolved request; inspect before resuming, no automatic retry')
    stop=asyncio.Event();sem=asyncio.Semaphore(3)
    async with httpx.AsyncClient(headers=headers,timeout=90,follow_redirects=False) as client:
        async def one(model,row):
            async with sem:
                if stop.is_set() or (model,row['id']) in done:return
                choices=row['choices']; values=[c['value'] for c in choices]
                if model=='gpt-6-luna':
                    endpoint='decisions';payload=dict(model=model,input=row['text'],questions=[dict(type='choice',name='intent',instructions=INSTRUCTIONS,choices=choices)])
                else:
                    endpoint='responses';payload=dict(model=model,store=False,reasoning=dict(effort='low'),instructions=INSTRUCTIONS+'\n候选：'+json.dumps(choices,ensure_ascii=False),input=row['text'],max_output_tokens=1024,
                        text=dict(format=dict(type='json_schema',name='intent_choice',strict=True,schema=dict(type='object',properties=dict(label=dict(type='string',enum=values),confidence=dict(type='number')),required=['label','confidence'],additionalProperties=False))))
                reserve=dict(model=model,id=row['id'],state='pending',payload_sha256=__import__('hashlib').sha256(json.dumps(payload,ensure_ascii=False).encode()).hexdigest())
                ledger.append(reserve);save(ledgerpath,ledger)
                start=time.perf_counter();result=dict(model=model,id=row['id'],split=row['split'],candidates=values)
                try:
                    response=await client.post('https://api.openai.com/v1/'+endpoint,json=payload)
                    result.update(status=response.status_code,latency_ms=(time.perf_counter()-start)*1000,request_id=response.headers.get('x-request-id'))
                    body=response.json()
                    if response.status_code!=200:
                        result['error_code']=body.get('error',{}).get('code');stop.set()
                    else:
                        result['response']=body;result['usage']=body.get('usage')
                        if endpoint=='decisions':
                            answer=body['answers'][0];label=answer.get('choice','__CLARIFY__');confidence=answer.get('confidence',0)
                        else:
                            texts=[c.get('text','') for o in body.get('output',[]) for c in o.get('content',[]) if c.get('type')=='output_text']
                            answer=json.loads(''.join(texts));label=answer['label'];confidence=answer['confidence']
                        if label not in values or not result['usage']:raise ValueError('Invalid model response')
                        result.update(raw=label,confidence=confidence,guarded=label if confidence>=.65 else '__CLARIFY__')
                except Exception as exc:
                    result['error_type']=type(exc).__name__;stop.set()
                reserve['state']='completed' if 'status' in result else 'pending'
                save(ledgerpath,ledger)
                encoded=json.dumps(result,ensure_ascii=False)
                if config['OPENAI_API_KEY'] in encoded:raise ValueError('Credential in result')
                with output.open('a',encoding='utf-8') as f:f.write(encoded+'\n')
                done.add((model,row['id']))
                print(model,row['id'],result.get('status'),result.get('raw'),round(result.get('latency_ms',0)),flush=True)
        # A single canary for each model, then bounded parallel requests, no retries.
        for model in MODELS:
            pending=[r for r in rows if (model,r['id']) not in done]
            if pending:await one(model,pending[0])
            if stop.is_set():raise RuntimeError('Canary failed; inspect shadow evidence before continuing')
        await asyncio.gather(*(one(model,row) for row in rows for model in MODELS if (model,row['id']) not in done and row!=rows[0]))
        if stop.is_set():raise RuntimeError('Shadow run incomplete; no automatic retry')


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);args=p.parse_args();asyncio.run(run(args.evidence))
