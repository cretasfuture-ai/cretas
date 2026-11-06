"""Run a frozen candidate against the already-open development corpus, both channels."""
import argparse,datetime,hashlib,json,os,pathlib,socket,subprocess,sys,time,urllib.request,xml.etree.ElementTree as E
p=argparse.ArgumentParser();p.add_argument('--previous-evidence',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--corpus',type=pathlib.Path);p.add_argument('--compile-only',action='store_true');p.add_argument('--preflight',action='store_true');a=p.parse_args()
ROOT=pathlib.Path(__file__).resolve().parents[2];BIN=pathlib.Path(r'C:/Program Files/Zulu/zulu-21/bin')
result=a.output/'complete-read-trace.private.json'
if result.exists():raise RuntimeError('Never overwrite a completed evaluation')
source=a.output/'CompleteReadHttpProbe.java';gold=a.corpus or a.previous_evidence/'exploratory-cases-locked.json'
plans=json.loads(gold.read_text('utf-8'))['dialogues'];turn_count=sum(len(x['turns']) for x in plans)
report=ROOT/'backend/java/cretas-api/target/surefire-reports/TEST-com.cretas.aims.service.execution.ReadQueryContinuationContractTest.xml'
props={x.get('name'):x.get('value') for x in E.parse(report).getroot().find('properties')}
classes=pathlib.Path(r'C:/Users/Steve/.codex/tmp/complete-real-read-20261106-classes');classes.mkdir(parents=True,exist_ok=True)
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
 'backend/python/ai/*.py','backend/python/ai/matcher/*.py'],text=True).splitlines()]
paths += list(pathlib.Path(__file__).parent.glob('*.py'))+[source,gold,a.previous_evidence/'real_business.py',a.previous_evidence.parent/'semantic-live-evaluation/entry-probe/verified_loopback.py']
hashes={str(x):hashlib.sha256(x.read_bytes()).hexdigest() for x in paths}
manifest=dict(base_head=subprocess.check_output(['git','-C',str(ROOT),'rev-parse','HEAD'],text=True).strip(),source_hashes=hashes,
    corpus='OPENED_OR_PUBLIC_DEVELOPMENT_REGRESSION_NOT_INDEPENDENT',dialogues=len(plans),turns_per_channel=turn_count,channels=['normal','sse'],production_business_mutations=0,
    wiring='8 actual Tool implementations;actual catalog;shared candidate semantic resolver;authenticated production GET;one read-only SQL count for QC repository adapter',
    limitations='Controller dependencies include controlled state/auth/RBAC;not full Spring/JPA runtime or production deploy;LLM shortlist fallback without live local classifier',started=datetime.datetime.now(datetime.timezone.utc).isoformat())
(a.output/'run-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
argfile=a.output/'probe-java.args';argfile.write_text('\n'.join(json.dumps(x) for x in ['-cp',cp.replace('\\','/'),'com.cretas.aims.service.execution.CompleteReadHttpProbe']),encoding='ascii')
with (a.output/'transport.log').open('w',encoding='utf-8') as sl,(a.output/'probe.log').open('w',encoding='utf-8') as jl:
 server=subprocess.Popen([sys.executable,str(pathlib.Path(__file__).with_name('complete_live_transport.py')),'--previous-evidence',str(a.previous_evidence),'--output',str(a.output)],stdout=sl,stderr=subprocess.STDOUT,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
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
if a.preflight:print('8 tools registered;expired and QC Tool actual readonly preflight passed;no model calls');sys.exit(0)
data=json.loads(result.read_text('utf-8'));assert len(data['trace'])==2*turn_count
manifest.update(finished=datetime.datetime.now(datetime.timezone.utc).isoformat(),source_unchanged=True,actual_turns=len(data['trace']))
(a.output/'run-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
print(str(2*turn_count)+' real-model HTTP turns finished with authenticated actual business reads;score all outcomes separately from carrier failures')
