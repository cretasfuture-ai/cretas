"""Auditable bounded API calls. Explicit synthetic content, no action tools."""
import asyncio
import datetime as dt
import hashlib
import json
from pathlib import Path
import time
import httpx
from factory_quota_accounting import quota_group,legacy_rows,add_usage

MINI='gpt-5.4-mini-2026-03-17'
BIG='gpt-5.4-2026-03-05'
CAPS={MINI:10_000_000,BIG:1_000_000}


class SyntheticClient:
    def __init__(self,root,concurrency=4):
        self.root=Path(root);self.sem=asyncio.Semaphore(concurrency);self.stop=False
        config={}
        for line in (Path.home()/'.codex/secrets/cretas-openai-eval.env').read_text(encoding='utf-8-sig').splitlines():
            if line.strip() and not line.lstrip().startswith('#') and '=' in line:
                key,value=line.split('=',1);config[key.strip()]=value.strip().strip('"').strip("'")
        self.key=config['OPENAI_API_KEY']
        headers={'Authorization':'Bearer '+self.key}
        for key,header in [('OPENAI_PROJECT_ID','OpenAI-Project'),('OPENAI_ORGANIZATION_ID','OpenAI-Organization')]:
            if config.get(key):headers[header]=config[key]
        self.http=httpx.AsyncClient(headers=headers,timeout=150,follow_redirects=False)
        self.path=self.root/'api-results.jsonl';self.ledger_path=self.root/'api-ledger.json'
        self.results=[json.loads(x) for x in self.path.read_text(encoding='utf-8').splitlines()] if self.path.exists() else []
        self.done={r['id']:r for r in self.results}
        if len(self.done)!=len(self.results):raise ValueError('Duplicate API receipt')
        self.ledger=json.loads(self.ledger_path.read_text(encoding='utf-8')) if self.ledger_path.exists() else []
        if any(r['state']=='pending' for r in self.ledger):raise ValueError('Unresolved request reservation; inspect before resume')
        # Keys represent shared groups, not independent per-model allowances.
        self.used={MINI:0,BIG:0};self.reserved={MINI:0,BIG:0};self.active=set()
        today=dt.datetime.now(dt.timezone.utc).date().isoformat();self.budget_day=today
        # Known prior traffic from this task family today, not organization-wide visibility.
        prior=Path.home()/'Documents/Cretas意图微调-266-20261105/cloud-results.jsonl'
        if prior.exists():
            for r in legacy_rows(prior):add_usage(self.used,r,today)
        budget_root=self.root.parent if (self.root/'shared-budget-root').exists() else self.root
        roots=[budget_root]
        previous_roots=budget_root/'budget-previous-roots.json'
        if previous_roots.exists():
            extra=json.loads(previous_roots.read_text(encoding='utf-8'))
            if not isinstance(extra,list) or any(not isinstance(p,str) for p in extra):
                raise ValueError('Invalid prior budget roots')
            for p in extra:
                prior_root=Path(p).resolve()
                if not prior_root.is_dir():raise ValueError('Prior budget root missing')
                roots.append(prior_root)
        receipts=[];paths=set()
        for directory in roots:
            for path in directory.rglob('api-results.jsonl'):
                resolved=path.resolve()
                if resolved in paths:continue
                paths.add(resolved)
                receipts.extend((str(directory.resolve()),json.loads(line)) for line in path.read_text(encoding='utf-8').splitlines() if line.strip())
        known=set()
        for directory,r in receipts:
            identity=(directory,r['id'])
            if identity in known:raise ValueError('Duplicate cross-phase receipt')
            known.add(identity)
            add_usage(self.used,r,today)
        ledgers=set()
        for directory in roots:
            for path in directory.rglob('api-ledger.json'):
                if path.resolve() in ledgers:continue
                ledgers.add(path.resolve())
                for reservation in json.loads(path.read_text(encoding='utf-8')):
                    if reservation['state']=='abandoned_unknown':
                        if reservation.get('day')!=today:continue
                        self.used[quota_group(reservation['model'])]+=reservation['reserved_tokens']

    def persist(self):
        self.ledger_path.write_text(json.dumps(self.ledger,ensure_ascii=False,indent=2),encoding='utf-8')

    async def close(self):
        # A quota/format failure must not cancel unrelated in-flight responses.
        running=[task for task in self.active if task is not asyncio.current_task() and not task.done()]
        if running:await asyncio.gather(*running,return_exceptions=True)
        await self.http.aclose()

    async def ask(self,id,model,instructions,content,schema,max_output=4096,effort='low'):
        payload=dict(model=model,store=False,reasoning=dict(effort=effort),instructions=instructions,input=content,max_output_tokens=max_output,text=dict(format=dict(type='json_schema',name='synthetic_intent_data',strict=True,schema=schema)))
        encoded=json.dumps(payload,ensure_ascii=False)
        fingerprint=hashlib.sha256(encoded.encode()).hexdigest()
        if id in self.done:
            old=self.done[id]
            if old['payload_sha256']!=fingerprint:raise ValueError('Request changed after freeze: '+id)
            if old.get('error') or 'parsed' not in old:raise ValueError('Failed or incomplete request requires explicit inspection; never replay unknown requests')
            return old['parsed']
        async with self.sem:
            if self.stop:raise RuntimeError('Previous request stopped the run')
            if dt.datetime.now(dt.timezone.utc).date().isoformat()!=self.budget_day:
                self.stop=True;raise ValueError('UTC day changed; reopen client after pending requests settle')
            reserve=len(encoded.encode('utf-8'))+max_output
            if self.used[model]+self.reserved[model]+reserve>CAPS[model]:
                self.stop=True;raise ValueError('Known daily token ceiling reached')
            reservation=dict(id=id,model=model,day=dt.datetime.now(dt.timezone.utc).date().isoformat(),state='pending',payload_sha256=fingerprint,reserved_tokens=reserve)
            self.ledger.append(reservation);self.reserved[model]+=reserve;self.persist()
            task=asyncio.current_task();self.active.add(task)
            start=time.perf_counter();result=dict(id=id,model=model,day=dt.datetime.now(dt.timezone.utc).date().isoformat(),payload_sha256=fingerprint)
            try:
                response=await self.http.post('https://api.openai.com/v1/responses',json=payload)
                result.update(status=response.status_code,request_id=response.headers.get('x-request-id'),latency_ms=(time.perf_counter()-start)*1000)
                body=response.json()
                if isinstance(body.get('created_at'),(int,float)):
                    result['response_created_at']=body['created_at']
                    result['day']=dt.datetime.fromtimestamp(body['created_at'],dt.timezone.utc).date().isoformat()
                    reservation['day']=result['day']
                if response.status_code!=200:
                    result['error']=body.get('error',{}).get('code') or 'http_error';self.stop=True
                else:
                    result['usage']=body.get('usage')
                    texts=[part.get('text','') for item in body.get('output',[]) for part in item.get('content',[]) if part.get('type')=='output_text']
                    result['parsed']=json.loads(''.join(texts))
                    if not result['usage']:raise ValueError('Missing usage')
                    self.used[model]+=result['usage']['input_tokens']+result['usage'].get('output_tokens',0)
                reservation['state']='completed'
            except Exception as exc:
                result['error']=type(exc).__name__;self.stop=True
                if 'status' in result:reservation['state']='completed'
            finally:
                self.reserved[model]-=reserve;self.persist()
                serialized=json.dumps(result,ensure_ascii=False)
                if self.key in serialized:raise ValueError('Credential in output')
                with self.path.open('a',encoding='utf-8') as f:f.write(serialized+'\n')
                self.results.append(result);self.done[id]=result
                self.active.discard(task)
            print(id,model,result.get('status'),(result.get('usage') or {}).get('total_tokens'),flush=True)
            if result.get('error'):raise RuntimeError('API run stopped: '+str(result['error']))
            return result['parsed']
