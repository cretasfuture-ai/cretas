"""Real-model budget transport and strictly readonly, authenticated F006 data bridge.

Requires existing private credentials/helper directory, never copies them into Git.
The service/repository interfaces in the servlet probe remain adapters, not JPA.
"""
import argparse,asyncio,concurrent.futures,datetime,json,os,pathlib,re,sys,threading,time,urllib.parse
p=argparse.ArgumentParser();p.add_argument('--previous-evidence',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--local-runtime',action='store_true');p.add_argument('--local-pg-dsn');a=p.parse_args()
sys.path.insert(0,str(a.previous_evidence));sys.path.insert(0,str(a.previous_evidence.parent/'semantic-live-evaluation/entry-probe'))
import verified_loopback as v
from real_business import Business
from readonly_remote import run
from ai.orchestrator import Orchestrator
business=Business()
assert business.actor['factoryId']=='F006' and business.actor['id']>0
assert (business.actor.get('role') or business.actor.get('roleCode'))=='factory_super_admin'
try:
 catalog=business.get('/api/mobile/F006/ai-intents')
 catalog_source=dict(source='LIVE_AUTHENTICATED_BULK_API')
except Exception as failure:
 # Explicit metadata-only alternative, not a stale snapshot or a permission grant.
 # Enumerate exactly the existing repository's tenant/tombstone predicate, then
 # retrieve each declaration through the same authenticated single-intent API.
 codes=run('print(json.dumps(query("SELECT json_build_object(\'code\',c.intent_code) FROM ai_intent_configs c WHERE c.is_active=true AND c.deleted_at IS NULL AND (c.factory_id=\'F006\' OR c.factory_id IS NULL) AND c.intent_code NOT IN (SELECT b.intent_code FROM ai_intent_configs b WHERE b.factory_id=\'F006\' AND (b.deleted_at IS NOT NULL OR b.is_active IS DISTINCT FROM TRUE)) ORDER BY c.priority DESC;")))')
 assert 0<len(codes)<=500
 def declaration(row):
  code=row['code']
  if not re.fullmatch(r'[A-Z0-9_]+',code):raise ValueError('Invalid intent code')
  path='/api/mobile/F006/ai-intents/'+code;t=time.perf_counter();r=business.http.get(path)
  business.requests.append(dict(method='GET',path=path,http=r.status_code,latency_ms=(time.perf_counter()-t)*1000))
  r.raise_for_status();raw=r.json()
  if not raw.get('success') or raw['data'].get('factoryId') not in (None,'F006'):raise RuntimeError('Invalid scoped declaration')
  return raw['data']
 with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
  catalog=list(pool.map(declaration,codes))
 catalog_source=dict(source='LIVE_PER_INTENT_AUTHENTICATED_API_WITH_READONLY_SQL_CODE_ENUMERATION',bulk_failure=type(failure).__name__,rows=len(catalog),business_data_still_authenticated_get=True)
(a.output/'actual-catalog-source.json').write_text(json.dumps(catalog_source,ensure_ascii=False,indent=2),encoding='utf-8')
(a.output/'actual-catalog.private.json').write_text(json.dumps(catalog,ensure_ascii=False,indent=2),encoding='utf-8')
def snake(k):
 import re
 return re.sub(r'(?<!^)(?=[A-Z])','_',k).lower()
v.ROWS=[{snake(k):x for k,x in r.items()} for r in catalog if r.get('isActive') and not r.get('deletedAt')]
from openai_decision_transport import decision_type
v.ENGINE=Orchestrator(v.Empty(),v.Empty(),v.Empty(),decision_matcher=decision_type(v)(timeout_s=60),candidate_retriever=v.Empty(),config=v.CONFIG)
v.BASE=a.output;v.RUN='complete-real-read-'+a.output.name;v.EFFORT='low'
if os.environ.get('CRETAS_EVAL_STRICT_READ_TOOL_ARGS')=='1':
 from openai_strict_tools import enable_read_tool_strict_wire
 enable_read_tool_strict_wire(v)


class _AsyncLoopRunner:
 def __init__(self):
  self.loop=asyncio.new_event_loop();self.thread=threading.Thread(target=self._serve,daemon=True)
 def _serve(self):
  asyncio.set_event_loop(self.loop);self.loop.run_forever()
 def start(self):self.thread.start()
 def run(self,coro):return asyncio.run_coroutine_threadsafe(coro,self.loop).result()
 def stop(self):
  self.loop.call_soon_threadsafe(self.loop.stop);self.thread.join(timeout=20);self.loop.close()


class _VerifiedAsyncioProxy:
 def __init__(self, module, run):self._module=module;self.run=run
 def __getattr__(self,name):return getattr(self._module,name)


_LOCAL_RUNNER=None
_LOCAL_RUNTIME=None
_ORIGINAL_VERIFIED_ASYNCIO=None


class _MainPathEvaluationOrchestrator(Orchestrator):
 async def _match_legacy(self,*args,**kwargs):
  from evaluation_spans import mark_error
  mark_error('legacy_fallback_outside_confirmed_main_path')
  raise RuntimeError('Main-path evaluation cannot silently recover through an unmeasured legacy branch')


async def _local_transport_match(raw):
 from ai.embedding import _request_embedding_cache
 from local_runtime import request_visible_rows
 from evaluation_spans import bind_span,mark_error,reset_span,model_evidence_failures
 body=v.IntentMatchRequest(**raw)
 if body.factoryId!='F006' or body.mode!='READ':raise ValueError('controlled read scope only')
 span_token=bind_span()
 cache_token=_request_embedding_cache.set({})
 try:
  visible_rows=request_visible_rows(v.ROWS,body)
  v.log('evaluation_request_catalog',dict(factoryId=body.factoryId,businessType=body.businessType,
        mode=body.mode,visible_codes=[row['intent_code'] for row in visible_rows],
        source='ai.db.filter_intents_for_request+filter_rows_by_rw_mode'))
  await _LOCAL_RUNTIME.prefetch(body.query,body.factoryId,body.businessType)
  result=await v.ENGINE.match(query=body.query,factoryId=body.factoryId,businessType=body.businessType,userId=body.userId,
                               role=body.role,visible_intents=visible_rows,history=body.history,min_confidence=.7,enable_llm=True,
                               task_context=body.taskContext)
  timing=result.timingMs or {}
  for failure in model_evidence_failures(timing):mark_error(failure)
 except Exception as failure:
  mark_error('local_runtime_or_recognition_'+type(failure).__name__)
  raise
 finally:
  _LOCAL_RUNTIME.discard_prefetch(body.query,body.factoryId,body.businessType)
  _request_embedding_cache.reset(cache_token)
  reset_span(span_token)
 return dict(success=True,data=result.model_dump(mode='json'),message='local classifier and BlendRetriever candidate transport')


def _enable_local_runtime():
 global _LOCAL_RUNNER,_LOCAL_RUNTIME,_ORIGINAL_VERIFIED_ASYNCIO
 if not a.local_runtime:return
 sys.path.insert(0,str(pathlib.Path(__file__).parent))
 from evaluation_spans import configure,wrap_ask
 from local_runtime import CLASSIFIER_LABEL_COUNT,MODEL_DIR,close_local_runtime,open_local_runtime
 from ai.matcher.semantic import SemanticMatcher
 configure(a.output/'evaluation-spans.private.json')
 _LOCAL_RUNNER=_AsyncLoopRunner();_LOCAL_RUNNER.start()
 _LOCAL_RUNTIME=_LOCAL_RUNNER.run(open_local_runtime(trace_path=a.output/'local-candidate-trace.jsonl',dsn=a.local_pg_dsn))
 v.ENGINE=_MainPathEvaluationOrchestrator(SemanticMatcher(_LOCAL_RUNTIME.pool),_LOCAL_RUNTIME.classifier_matcher,v.Empty(),
                       decision_matcher=decision_type(v)(timeout_s=60),candidate_retriever=_LOCAL_RUNTIME,config=v.CONFIG)
 v.match=_local_transport_match
 v.ask=wrap_ask(v.ask)
 _ORIGINAL_VERIFIED_ASYNCIO=v.asyncio
 v.asyncio=_VerifiedAsyncioProxy(asyncio,_LOCAL_RUNNER.run)
 (a.output/'local-runtime-preflight.json').write_text(json.dumps({
  'model_dir':str(MODEL_DIR),
  'model_sha256':'0fa159f28bf30b445672086a670f30177eeebfa9101146952acc49d319e741b2',
  'mapping_sha256':'ccd2497bc53ac5958d7531f6539cf0b62301c2f530904ea64feeccc9a669cb5b',
  'classifier_label_count':CLASSIFIER_LABEL_COUNT,
  'classifier_temperature':1.5,'classifier_min_confidence':0.05,
  'vector_corpus_sha256':_LOCAL_RUNTIME.vector_corpus_hash,
  'vector_corpus_rows':_LOCAL_RUNTIME.vector_corpus_rows,
  'transaction_read_only':_LOCAL_RUNTIME.transaction_read_only,
  'embedding_endpoint':os.environ.get('AI_EMBEDDING_GRPC_ENDPOINT','localhost:9090'),
  'backend':'backend/python',
  'components':['SemanticMatcher','ClassifierMatcher','BlendRetriever','Orchestrator.decision_matcher'],
  'legacy_llm_fallback_enabled':False,
  'legacy_fallback_policy':'BLOCK_AND_RECORD_IN_EVALUATION_ONLY;production Orchestrator unchanged',
  'candidate_sources':['ClassifierMatcher','BlendRetriever'],'chooser':'existing Orchestrator decision matcher',
 },ensure_ascii=False,indent=2),encoding='utf-8')
class Handler(v.Handler):
 def do_GET(self):
  uri=urllib.parse.urlsplit(self.path)
  if uri.path in ('/evaluation/begin','/evaluation/end'):
   try:
    from evaluation_spans import begin,end
    params=dict(urllib.parse.parse_qsl(uri.query));span=params.get('span')
    if not span:raise ValueError('span is required')
    result=begin(span) if uri.path.endswith('/begin') else end(span)
    status=200
   except Exception as exc:
    result={'ok':False,'error':type(exc).__name__};status=409
   body=json.dumps(result,ensure_ascii=False).encode();self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body);return
  if uri.path=='/actor':
   body=json.dumps(dict(id=business.actor['id'],factoryId=business.actor['factoryId'],role=business.actor.get('role') or business.actor.get('roleCode'))).encode()
   self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body);return
  if not uri.path.startswith('/business/'):return super().do_GET()
  try:
   params=dict(urllib.parse.parse_qsl(uri.query))
   if uri.path=='/business/stock-receipt-count':
    if set(params)!={'from'}:raise ValueError('Only receipt boundary permitted')
    start=datetime.datetime.fromisoformat(params['from']).isoformat()
    # Exact existing @Query uses createdAt >=, despite the historical method name.
    payload=run('print(json.dumps(query("SELECT json_build_object(\'count\',count(*)) FROM material_batches WHERE factory_id=\'F006\' AND deleted_at IS NULL AND created_at >= TIMESTAMP \''+start+'\';")[0]))')
    data=payload
   else:
    if set(params)-{'page','size','status','_candidateSort'}:raise ValueError('Unimplemented filters cannot be emulated')
    if uri.path=='/business/orders':
     sort=json.loads(params.pop('_candidateSort'))
     if sort!=[dict(property='createdAt',direction='DESC'),dict(property='id',direction='ASC')]:raise ValueError('Unreviewed candidate sort')
     page=int(params.get('page',1));size=int(params.get('size',20))
     if page<1 or not 1<=size<=100:raise ValueError('Invalid candidate pagination')
     path='/api/mobile/F006/sales/orders/by-status' if params.get('status') else '/api/mobile/F006/sales/orders'
     # Full authorized GET only: never assemble an unstable sequence of old pages.
     full=business.get(path,dict(page=1,size=1000,**({'status':params['status']} if params.get('status') else {})))
     rows=full['content'];total=full['totalElements']
     if len(rows)!=total or total>1000 or len({r['id'] for r in rows})!=total:raise RuntimeError('Authorized collection incomplete')
     # Execute the sort exported from the actual candidate service, not oracle/gold.
     for order in reversed(sort):
      if any(r.get(order['property']) is None for r in rows):raise ValueError('Missing stable sort property')
      rows=sorted(rows,key=lambda r:r[order['property']],reverse=order['direction']=='DESC')
     data=dict(content=rows[(page-1)*size:page*size],totalElements=total,page=page,size=size)
     v.log('candidate_service_adapter',dict(source='FULL_AUTHORIZED_PRODUCTION_GET',sort=sort,total=total,page=page,size=size))
    elif uri.path=='/business/stock':data=business.get('/api/mobile/F006/material-batches',params)
    elif uri.path=='/business/stock-expired':data=business.get('/api/mobile/F006/material-batches/expired')
    elif uri.path=='/business/stock-all-status':
     if set(params)!={'status'}:raise ValueError('Only status permitted')
     data=[];page=1
     while True:
      result=business.get('/api/mobile/F006/material-batches',dict(status=params['status'],page=page,size=100));data.extend(result['content'])
      if len(data)>=result['totalElements']:break
      if not result['content']:raise RuntimeError('Incomplete readonly pagination')
      page+=1
    else:raise ValueError('Outside readonly paths')
   self.send_response(200)
  except Exception as e:
   data=dict(adapter_error=type(e).__name__);self.send_response(502)
  body=json.dumps(data,ensure_ascii=False).encode();self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
  (a.output/'actual-business-requests.json').write_text(json.dumps(dict(actor=business.actor,requests=business.requests,business_writes=0),ensure_ascii=False,indent=2),encoding='utf-8')
if __name__=='__main__':
 v.CLIENT=v.budget.Client();_enable_local_runtime()
 server=v.ThreadingHTTPServer(('127.0.0.1',18766),Handler)
 try:server.serve_forever()
 finally:
  server.server_close()
  if _LOCAL_RUNNER is not None:
   v.asyncio=_ORIGINAL_VERIFIED_ASYNCIO
   from local_runtime import close_local_runtime
   _LOCAL_RUNNER.run(close_local_runtime(_LOCAL_RUNTIME));_LOCAL_RUNNER.stop()
  business.close()
