"""Load accepted P30 classes into only the dedicated copy; no source service restart."""
from pathlib import Path
import json,subprocess,psutil,os,sys,hashlib
assert sys.argv[1:]==['NON_PRODUCTION_ONLY']
ROOT=Path(__file__).resolve().parents[4];OUT=Path.home()/'Documents/cretas-connect-20261015/factory-data-p30'
assert 'BUILD SUCCESS' in (OUT/'java-final.log').read_text(encoding='utf-8',errors='replace')
for f,digest in json.loads((OUT/'product-final-freeze.json').read_text()).items():assert hashlib.sha256((ROOT/f).read_bytes()).hexdigest()==digest,f
r=json.loads((OUT/'runtime.json').read_text());assert r['database']=='p30_refund_close_20261018' and r['javaPid']!=46300
old=psutil.Process(r['javaPid']);assert old.name().lower()=='java.exe' and '@'+r['javaArgFile'] in old.cmdline()
args=[json.loads(line) for line in Path(r['javaArgFile']).read_text(encoding='utf-8').splitlines() if line.strip()]
cp=args.index('-cp')+1;assert args[cp].replace('\\','/').count('factory-data-p29/build/classes')==1
args[cp]=args[cp].replace('factory-data-p29','factory-data-p30')
assert '--server.port=10066' in args and any(a.startswith('--spring.datasource.url=jdbc:postgresql://127.0.0.1:55447/p30_refund_close_20261018?') for a in args)
argfile=OUT/'entry-java-fixed.args';assert not argfile.exists();argfile.write_text('\n'.join(json.dumps(a) for a in args),encoding='utf-8')
secure=json.loads((OUT.parent/'factory-data-p14/runtime.private.json').read_text());env={k:v for k,v in os.environ.items() if not any(t in k.upper() for t in ('API_KEY','API_SECRET','JWT_SECRET','DB_PASSWORD','INTERNAL_API_SECRET'))};env.update(JWT_SECRET=secure['jwt'],INTERNAL_API_SECRET=secure['internal'],VISION_ENTRY_WORKER_TOKEN=secure['worker'])
old.terminate();old.wait(timeout=30)
with (OUT/'java-fixed.log').open('w',encoding='utf-8') as log:child=subprocess.Popen(['C:/Program Files/Zulu/zulu-21/bin/java.exe','@'+str(argfile)],cwd=ROOT/'backend/java/cretas-api',env=env,stdout=log,stderr=log,creationflags=subprocess.CREATE_NO_WINDOW)
r.update(previousJavaPid=r['javaPid'],javaPid=child.pid,javaArgFile=str(argfile),productClasses=str(OUT/'build/classes'),sourceRuntimeUntouched=True)
(OUT/'runtime.json').write_text(json.dumps(r,indent=2),encoding='utf-8');print(json.dumps({'status':'STARTED_NOT_YET_ACCEPTED','javaPid':child.pid,'sourceServiceRestarted':False}))
