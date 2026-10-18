"""Stop/start only the registered P31 JVM to load a locally accepted repair."""
from pathlib import Path
import os,sys,json,subprocess,psutil,hashlib
assert sys.argv[1:] in (['NON_PRODUCTION_ONLY','stop'],['NON_PRODUCTION_ONLY','start'])
ROOT=Path(__file__).resolve().parents[4];OUT=Path.home()/'Documents/cretas-connect-20261015/factory-data-p31'
r=json.loads((OUT/'runtime.json').read_text());assert r['owner']=='P31-FINANCE-CLOSEOUT-20261018' and r['database']=='p31_finance_closeout_20261018'
argfile=Path(r['javaArgFile']);assert argfile.parent==OUT
args=[json.loads(x) for x in argfile.read_text().splitlines() if x.strip()]
assert '--server.port=10068' in args and '--server.address=127.0.0.1' in args
assert '--spring.datasource.url=jdbc:postgresql://127.0.0.1:55447/p31_finance_closeout_20261018?stringtype=unspecified' in args
original=json.loads((OUT.parent/'factory-data-p30/runtime.json').read_text());assert original['javaPid']!=r['javaPid']
source=psutil.Process(original['javaPid']);assert '@'+original['javaArgFile'] in source.cmdline()
if sys.argv[-1]=='stop':
 p=psutil.Process(r['javaPid']);assert p.name().lower()=='java.exe' and '@'+str(argfile) in p.cmdline()
 assert (OUT/'clock.txt').read_text().strip()=='2026-10-18'
 p.terminate();p.wait(timeout=30);r['stoppedPid']=r['javaPid'];r['localState']='STOPPED_FOR_FIX'
else:
 assert r.get('localState')=='STOPPED_FOR_FIX' and not psutil.pid_exists(r['javaPid'])
 assert 'BUILD SUCCESS' in (OUT/'java-final.log').read_text(encoding='utf-8',errors='replace')
 for f,digest in json.loads((OUT/'product-final-freeze.json').read_text()).items():assert hashlib.sha256((ROOT/f).read_bytes()).hexdigest()==digest,f
 secure=json.loads((OUT.parent/'factory-data-p14/runtime.private.json').read_text());env={k:v for k,v in os.environ.items() if not any(t in k.upper() for t in ('API_KEY','API_SECRET','JWT_SECRET','DB_PASSWORD','INTERNAL_API_SECRET'))};env.update(JWT_SECRET=secure['jwt'],INTERNAL_API_SECRET=secure['internal'],VISION_ENTRY_WORKER_TOKEN=secure['worker'])
 with (OUT/'java-reload.log').open('w',encoding='utf-8') as log:p=subprocess.Popen(['C:/Program Files/Zulu/zulu-21/bin/java.exe','@'+str(argfile)],cwd=ROOT/'backend/java/cretas-api',env=env,stdout=log,stderr=log,creationflags=subprocess.CREATE_NO_WINDOW)
 r['javaPid']=p.pid;r['localState']='STARTED_NOT_YET_ACCEPTED'
(OUT/'runtime.json').write_text(json.dumps(r,indent=2),encoding='utf-8');print(json.dumps({'state':r['localState'],'javaPid':r['javaPid'],'originalRuntimeUntouched':True}))
