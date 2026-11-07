"""Run a frozen candidate against the already-open development corpus, both channels."""
import argparse,datetime,hashlib,json,os,pathlib,socket,subprocess,sys,time,urllib.request,xml.etree.ElementTree as E
p=argparse.ArgumentParser();p.add_argument('--previous-evidence',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--corpus',type=pathlib.Path);p.add_argument('--compile-only',action='store_true');p.add_argument('--preflight',action='store_true');p.add_argument('--local-runtime',action='store_true');p.add_argument('--build-receipt',type=pathlib.Path);a=p.parse_args()
ROOT=pathlib.Path(__file__).resolve().parents[2];BIN=pathlib.Path(r'C:/Program Files/Zulu/zulu-21/bin')
result=a.output/'complete-read-trace.private.json'
if result.exists():raise RuntimeError('Never overwrite a completed evaluation')
source=a.output/'CompleteReadHttpProbe.java';gold=a.corpus or a.previous_evidence/'exploratory-cases-locked.json'
plans=json.loads(gold.read_text('utf-8'))['dialogues'];turn_count=sum(len(x['turns']) for x in plans)
report=ROOT/'backend/java/cretas-api/target/surefire-reports/TEST-com.cretas.aims.service.execution.ReadQueryContinuationContractTest.xml'
props={x.get('name'):x.get('value') for x in E.parse(report).getroot().find('properties')}
classes=pathlib.Path.home()/'.codex/tmp'/('semantic-probe-'+hashlib.sha256(str(a.output.resolve()).encode()).hexdigest()[:16]);classes.mkdir(parents=True,exist_ok=True)
cp=str(classes)+';'+props['java.class.path']
cmd=[str(BIN/'javac.exe'),'-encoding','UTF-8','-cp',cp,'-d',str(classes),str(source)]
r=subprocess.run(cmd,capture_output=True);(a.output/'probe-compile.log').write_bytes(r.stdout+r.stderr)
if r.returncode:raise RuntimeError('Probe compile failure;see private log')
if a.compile_only:print('Complete candidate probe compiled;no external calls');sys.exit(0)
for port in (18766,18770):
 with socket.socket() as sock:sock.bind(('127.0.0.1',port))
paths=[ROOT/x for x in subprocess.check_output(['git','-C',str(ROOT),'ls-files',
 'backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/*.java',
 'backend/java/cretas-api/src/main/java/com/cretas/aims/service/inventory/impl/SalesServiceImpl.java',
 'backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/crm/*.java',
 'backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/material/*.java',
 'backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/*.java',
 'backend/python/ai/*.py','backend/python/ai/matcher/*.py'],text=True).splitlines()]
paths += list(pathlib.Path(__file__).parent.glob('*.py'))+[source,gold,a.previous_evidence/'real_business.py',a.previous_evidence.parent/'semantic-live-evaluation/entry-probe/verified_loopback.py']
hashes={str(x):hashlib.sha256(x.read_bytes()).hexdigest() for x in paths}
fixture_dir=ROOT/'backend/java/cretas-api/target/test-classes/com/cretas/aims/service/execution'
fixture_hashes={str(x):hashlib.sha256(x.read_bytes()).hexdigest() for x in fixture_dir.glob('*.class')}
if not fixture_hashes:raise RuntimeError('Controlled HTTP harness classes missing;do not call a model')
main_dir=ROOT/'backend/java/cretas-api/target/classes'
main_hashes={str(x):hashlib.sha256(x.read_bytes()).hexdigest() for x in main_dir.rglob('*.class')}
if not main_hashes:raise RuntimeError('Production classpath missing')
if a.local_runtime:
 if a.build_receipt is None:raise RuntimeError('Local-chain evaluation requires a verified current Java build receipt')
 build_proof=json.loads(a.build_receipt.read_text('utf-8-sig'))
 if build_proof['production_class_hashes']!=main_hashes:raise RuntimeError('Actual Java classpath differs from verified package')
 if not all(hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()==value for path,value in build_proof['java_source_hashes'].items()):raise RuntimeError('Java source changed after verified package')
manifest=dict(base_head=subprocess.check_output(['git','-C',str(ROOT),'rev-parse','HEAD'],text=True).strip(),source_hashes=hashes,
    controlled_fixture_class_hashes=fixture_hashes,
    production_class_hashes=main_hashes,build_receipt_sha256=hashlib.sha256(a.build_receipt.read_bytes()).hexdigest() if a.build_receipt else None,
    openai_read_tool_strict_wire=os.environ.get('CRETAS_EVAL_STRICT_READ_TOOL_ARGS')=='1',
    decision_contract='Core system/user choice prompt and deterministic option order;native Luna full-choice output replaces assistant partial/token logprobs;raw probabilities preserved,not production score parity',
    purpose='LIVE_MINI_REQUEST_PURPOSE_CAN_OVERRIDE_NATIVE_CHOICE;shortlist also live mini;local266/vector enabled only with --local-runtime',
    corpus='OPENED_OR_PUBLIC_DEVELOPMENT_REGRESSION_NOT_INDEPENDENT',dialogues=len(plans),turns_per_channel=turn_count,channels=['normal','sse'],production_business_mutations=0,
    wiring='8 actual Tool implementations;actual catalog;shared candidate semantic resolver;authenticated production GET;one read-only SQL count for QC repository adapter',
    limitations='Controller dependencies include controlled state/auth/RBAC;not full Spring/JPA runtime or production deploy;legacy LLM fallback is intentionally disabled in local runtime',
    local_runtime=dict(enabled=a.local_runtime,model_sha256='0fa159f28bf30b445672086a670f30177eeebfa9101146952acc49d319e741b2' if a.local_runtime else None,
                       mapping_sha256='ccd2497bc53ac5958d7531f6539cf0b62301c2f530904ea64feeccc9a669cb5b' if a.local_runtime else None,
                       classifier_label_count=266 if a.local_runtime else None,
                       backend='backend/python' if a.local_runtime else None,
                       components=['SemanticMatcher','ClassifierMatcher','BlendRetriever','Orchestrator.decision_matcher'] if a.local_runtime else [],
                       legacy_llm_fallback_enabled=False,
                       legacy_fallback_policy='BLOCK_AND_RECORD_IN_EVALUATION_ONLY' if a.local_runtime else 'legacy slots empty',
                       classifier_temperature=1.5 if a.local_runtime else None,classifier_min_confidence=.05 if a.local_runtime else None,
                       candidate_union='ClassifierMatcher + BlendRetriever; existing chooser remains final selector',
                       no_local_auto_selection=True,classifier_calls=0,classifier_ok_calls=0,classifier_failure_calls=0,blend_retriever_calls=0,blend_retriever_ok_calls=0,blend_retriever_failure_calls=0,rounds_with_classifier=0,rounds_with_blend_retriever=0,
                       vector_corpus_sha256=None,vector_corpus_rows=None),
    started=datetime.datetime.now(datetime.timezone.utc).isoformat())
(a.output/'run-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
argfile=a.output/'probe-java.args';argfile.write_text('\n'.join(json.dumps(x) for x in ['-cp',cp.replace('\\','/'),'com.cretas.aims.service.execution.CompleteReadHttpProbe']),encoding='ascii')
with (a.output/'transport.log').open('w',encoding='utf-8') as sl,(a.output/'probe.log').open('w',encoding='utf-8') as jl:
 transport_args=[sys.executable,str(pathlib.Path(__file__).with_name('complete_live_transport.py')),'--previous-evidence',str(a.previous_evidence),'--output',str(a.output)]
 if a.local_runtime:transport_args.append('--local-runtime')
 server=subprocess.Popen(transport_args,stdout=sl,stderr=subprocess.STDOUT,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
 try:
  for _ in range(600):
   if server.poll() is not None:raise RuntimeError('Readonly model/data server exited')
   try:
    with urllib.request.urlopen('http://127.0.0.1:18766/health',timeout=1) as r:ready=json.loads(r.read())['ok']
    if ready:break
   except OSError:time.sleep(.2)
  else:raise RuntimeError('Readonly data server health timeout')
  args=[str(BIN/'java.exe'),'@'+str(argfile),str(result),str(gold),str(a.output/'actual-catalog.private.json')]
  if a.preflight:args.append('--wiring-only')
  r=subprocess.run(args,stdout=jl,stderr=subprocess.STDOUT,timeout=2400,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
  if r.returncode:raise RuntimeError('HTTP runner failed;no retry;preserve log and partial evidence')
 finally:server.terminate();server.wait(timeout=20)
assert all(hashlib.sha256(pathlib.Path(k).read_bytes()).hexdigest()==v for k,v in hashes.items()),'Source changed during real inference'
assert all(hashlib.sha256(pathlib.Path(k).read_bytes()).hexdigest()==v for k,v in fixture_hashes.items()),'Controlled fixture classes changed during real inference'
assert all(hashlib.sha256(pathlib.Path(k).read_bytes()).hexdigest()==v for k,v in main_hashes.items()),'Production classpath changed during real inference'
if a.preflight:
 if a.local_runtime:
  preflight=json.loads((a.output/'local-runtime-preflight.json').read_text('utf-8'))
  manifest['local_runtime'].update(vector_corpus_sha256=preflight['vector_corpus_sha256'],vector_corpus_rows=preflight['vector_corpus_rows'])
  manifest['local_runtime']['preflight']=preflight
  (a.output/'run-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
 print('8 tools registered;expired and QC Tool actual readonly preflight passed;no model calls');sys.exit(0)
data=json.loads(result.read_text('utf-8'));assert len(data['trace'])==2*turn_count
if a.local_runtime:
 span_path=a.output/'evaluation-spans.private.json'
 spans=json.loads(span_path.read_text('utf-8'))['spans']
 for row in data['trace']:
  span=row.get('evaluation_span')
  if not span or span not in spans:raise RuntimeError('HTTP turn missing final request evidence')
  row['evaluation_model_evidence_at_http_return']=row.get('evaluation_model_evidence')
  row['evaluation_model_evidence']=spans[span]
 raw_trace=a.output/'http-return-trace.private.json'
 if raw_trace.exists():raise RuntimeError('Never overwrite original HTTP trace')
 raw_trace.write_bytes(result.read_bytes())
 result.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
 manifest['evaluation_span_sha256']=hashlib.sha256(span_path.read_bytes()).hexdigest()
 trace_path=a.output/'local-candidate-trace.jsonl'
 records=[json.loads(line) for line in trace_path.read_text('utf-8').splitlines() if line.strip()]
 preflight=json.loads((a.output/'local-runtime-preflight.json').read_text('utf-8'))
 if len(records)!=len(data['trace']):raise RuntimeError('local candidate trace count does not equal HTTP turns')
 classifier_components=[x.get('components',{}).get('classifier',{}) for x in records]
 blend_components=[x.get('components',{}).get('blend_retriever',{}) for x in records]
 manifest['local_runtime'].update(
     classifier_calls=sum(bool(x.get('called')) for x in classifier_components),classifier_ok_calls=sum(bool(x.get('called') and x.get('ok')) for x in classifier_components),classifier_failure_calls=sum(bool(x.get('called') and not x.get('ok')) for x in classifier_components),
     blend_retriever_calls=sum(bool(x.get('called')) for x in blend_components),blend_retriever_ok_calls=sum(bool(x.get('called') and x.get('ok')) for x in blend_components),blend_retriever_failure_calls=sum(bool(x.get('called') and not x.get('ok')) for x in blend_components),
     rounds_with_classifier=sum(bool(x.get('called')) for x in classifier_components),rounds_with_blend_retriever=sum(bool(x.get('called')) for x in blend_components),
     all_required_components_ok=all(x.get('called') and x.get('ok') for x in classifier_components+blend_components),
     vector_corpus_sha256=preflight['vector_corpus_sha256'],vector_corpus_rows=preflight['vector_corpus_rows'],
     candidate_trace_sha256=hashlib.sha256(trace_path.read_bytes()).hexdigest())
manifest.update(finished=datetime.datetime.now(datetime.timezone.utc).isoformat(),source_unchanged=True,actual_turns=len(data['trace']))
(a.output/'run-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
print(str(2*turn_count)+' real-model HTTP turns finished with authenticated actual business reads;score all outcomes separately from carrier failures')
