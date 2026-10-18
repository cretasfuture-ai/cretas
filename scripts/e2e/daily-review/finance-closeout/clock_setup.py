"""Reuse the existing bounded request clock, in the P31 copy only; never change OS time."""
from pathlib import Path
import json,subprocess,sys,hashlib
assert sys.argv[1:]==['NON_PRODUCTION_ONLY']
ROOT=Path(__file__).resolve().parents[4];OUT=Path.home()/'Documents/cretas-connect-20261015/factory-data-p31';OUT.mkdir(exist_ok=True)
old=OUT.parent/'factory-data-p30';r=json.loads((old/'runtime.json').read_text());args=[json.loads(x) for x in Path(r['javaArgFile']).read_text().splitlines() if x.strip()]
assert r['database']=='p30_refund_close_20261018'
cp=args[args.index('-cp')+1].replace('factory-data-p30','factory-data-p31')
original_args=(OUT.parent/'factory-data-p10/clock-javac.args').read_text().splitlines();clock_cp=original_args[original_args.index('-cp')+1]
classes=OUT/'clock-classes';classes.mkdir(exist_ok=True)
sources=list((ROOT/'scripts/e2e/daily-review/cross-month-http/java').rglob('*.java'))
javac_args=['-encoding','UTF-8','-source','21','-target','21','-cp',cp+';'+clock_cp,'-d',str(classes)]+[str(p) for p in sources]
argfile=OUT/'clock-javac.args';argfile.write_text('\n'.join(json.dumps(a) for a in javac_args),encoding='utf-8')
with (OUT/'clock-build.log').open('w',encoding='utf-8') as log:
 p=subprocess.run(['C:/Program Files/Zulu/zulu-21/bin/javac.exe','@'+str(argfile)],stdout=log,stderr=log)
assert p.returncode==0,'Inspect clock-build.log'
manifest=OUT.parent/'factory-data-p10/clock-manifest.mf';agent=OUT/'clock-agent.jar'
p=subprocess.run(['C:/Program Files/Zulu/zulu-21/bin/jar.exe','cfm',str(agent),str(manifest),'-C',str(classes),'p10'],capture_output=True,text=True);assert p.returncode==0,p.stderr
(OUT/'clock.txt').write_text('2026-10-18\n',encoding='utf-8')
freeze={str(p.relative_to(ROOT)).replace('\\','/'):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
(OUT/'clock-source-freeze.json').write_text(json.dumps(freeze,indent=2),encoding='utf-8');print('P31 request-clock build PASS; no runtime or system clock changed')
