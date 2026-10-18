"""Dedicated loopback copy of the accepted new factory; never edits the source DB/runtime."""
from pathlib import Path
import json,os,socket,subprocess,sys,hashlib
import psycopg,psutil
import argparse

ROOT=Path(__file__).resolve().parents[4]
parser=argparse.ArgumentParser();parser.add_argument('ack',choices=['NON_PRODUCTION_ONLY']);parser.add_argument('--profile',choices=['P30','P31','P34'],default='P30');options=parser.parse_args()
P31=options.profile=='P31';OUT=Path.home()/('Documents/cretas-connect-20261015/factory-data-'+options.profile.lower())
SOURCE='p30_refund_close_20261018' if P31 else 'p14_new_factory_20261016';TARGET='p31_finance_closeout_20261018' if P31 else 'p30_refund_close_20261018';OWNER='P31-FINANCE-CLOSEOUT-20261018' if P31 else 'P30-REFUND-CLOSE-RACE-20261018'
JAVA_PORT,WEB_PORT,OBJECT_PORT=(10068,31768,18398) if P31 else (10066,31766,18396)
P34=options.profile=='P34'
if P34:
 SOURCE,TARGET,OWNER='p31_finance_closeout_20261018','p34_disposal_guard_20261018','P34-DISPOSAL-LIFECYCLE-GUARD-20261018'
 JAVA_PORT,WEB_PORT,OBJECT_PORT=10070,31770,18400
OUT.mkdir(exist_ok=True)
def busy(port):
 try:
  with socket.create_connection(('127.0.0.1',port),timeout=.3):return True
 except OSError:return False
assert not (OUT/'runtime.json').exists(),'Existing runtime: inspect instead of replacing/replaying'
assert not any(busy(p) for p in (JAVA_PORT,WEB_PORT,OBJECT_PORT)),'Dedicated ports occupied'
assert psutil.virtual_memory().available>5*1024**3
accepted=OUT.parent/('factory-data-p31' if P34 else 'factory-data-p30' if P31 else 'factory-data-p29');old=json.loads((accepted/'runtime.json').read_text())
assert old['owner']==('P31-FINANCE-CLOSEOUT-20261018' if P34 else 'P30-REFUND-CLOSE-RACE-20261018' if P31 else 'P29-SKU-FINANCE-PERIOD-20261018')
process=psutil.Process(old['javaPid']);assert process.name().lower()=='java.exe' and '@'+old['javaArgFile'] in process.cmdline()
fixed=json.loads((OUT/'product-final-freeze.json').read_text()) if (OUT/'product-final-freeze.json').exists() else {}
assert not (P31 or P34) or fixed,'Dedicated profile requires its own tested product freeze'
previous=json.loads((accepted/'product-final-freeze.json').read_text()) if P34 else {} if P31 else json.loads((accepted/'source-final-freeze.json').read_text(encoding='utf-8-sig'))
for f,digest in {**previous,**fixed}.items():
 assert hashlib.sha256((ROOT/f).read_bytes()).hexdigest()==digest,'Source drift: '+f
record=OUT/'database.json'
assert not record.exists(),'Do not overwrite a partial clone; inspect its state'
with psycopg.connect(host='127.0.0.1',port=55447,dbname=SOURCE,user='assembly_executor',autocommit=True) as db:
 assert db.execute('select current_database()').fetchone()[0]==SOURCE
 assert db.execute('select count(*) from pg_database where datname=%s',(TARGET,)).fetchone()[0]==0
 db.execute('CREATE DATABASE '+TARGET)
meta={'owner':OWNER,'source':SOURCE,'database':TARGET,'host':'127.0.0.1','port':55447,'state':'created','productionTouched':False}
record.write_text(json.dumps(meta,indent=2),encoding='utf-8')
env=dict(os.environ);env.update(PGHOST='127.0.0.1',PGPORT='55447',PGUSER='assembly_executor',PGPASSWORD='')
pg=Path('C:/Program Files/PostgreSQL/17/bin')
for phase,cmd in [('dump',[str(pg/'pg_dump.exe'),'--format=custom','--no-owner','--no-acl','--file='+str(OUT/'source.dump'),'--dbname='+SOURCE]),('restore',[str(pg/'pg_restore.exe'),'--no-owner','--no-acl','--exit-on-error','--dbname='+TARGET,str(OUT/'source.dump')])]:
 with (OUT/(phase+'.log')).open('w',encoding='utf-8') as log:r=subprocess.run(cmd,env=env,stdout=log,stderr=subprocess.STDOUT)
 assert r.returncode==0,(phase,r.returncode)
meta['state']='restored';record.write_text(json.dumps(meta,indent=2),encoding='utf-8')
template=json.loads((OUT.parent/'factory-data-p30/runtime.json').read_text()) if P34 else old
template_process=psutil.Process(template['javaPid']);assert '@'+template['javaArgFile'] in template_process.cmdline()
args=[json.loads(line) for line in Path(template['javaArgFile']).read_text(encoding='utf-8').splitlines() if line.strip()]
if fixed:
 assert 'BUILD SUCCESS' in (OUT/'java-final.log').read_text(encoding='utf-8',errors='replace')
 cp=args.index('-cp')+1;args[cp]=args[cp].replace('factory-data-p30','factory-data-p34') if P34 else args[cp].replace('factory-data-p30','factory-data-p31') if P31 else args[cp].replace('factory-data-p29','factory-data-p30')
url='jdbc:postgresql://127.0.0.1:55447/'+TARGET+'?stringtype=unspecified'
if P34:url+='&options=-c%20lock_timeout%3D3s'
overrides={'server.port':str(JAVA_PORT),'server.address':'127.0.0.1','spring.datasource.url':url,'smartbi.postgres.url':url,'spring.jpa.hibernate.ddl-auto':'none','spring.flyway.enabled':'false','spring.sql.init.mode':'never','logging.file.name':str(OUT/'java-application.log'),'spring.servlet.multipart.location':str(OUT/'uploads'),'p0.local-objects.root':str(OUT/'object-data'),'p0.local-objects.port':str(OBJECT_PORT),'aliyun.oss.endpoint':'127.0.0.1:'+str(OBJECT_PORT)}
for key in ('python-smartbi.url','cretas.python.base-url','cretas.ai.service.url','python-error-analysis.url','python-classifier.url'):overrides[key]='http://127.0.0.1:18098'
args=[a for a in args if not(a.startswith('--') and a[2:].split('=',1)[0] in overrides)]
args+=['--'+k+'='+v for k,v in overrides.items()]
assert not any(SOURCE in a for a in args),'Source database still referenced'
assert not any('javaagent' in a or 'p10.clock' in a for a in args),'No clock substitution in this acceptance'
if P31:
 assert (OUT/'clock-agent.jar').is_file() and (OUT/'clock.txt').read_text().strip()=='2026-10-18'
 cp=args.index('-cp')+1;args[cp]=str(OUT/'clock-classes')+';'+args[cp]
 args[cp+1]='p10.acceptance.CrossMonthHttpApplication'
 args=['-javaagent:'+str(OUT/'clock-agent.jar'),'-Dp10.acceptance=true','-Dp10.profile=P31','-Dp10.clock.file='+str(OUT/'clock.txt')]+args
for folder in ('uploads','object-data'):(OUT/folder).mkdir(exist_ok=True)
argfile=OUT/'entry-java.args';argfile.write_text('\n'.join(json.dumps(a) for a in args),encoding='utf-8')
secure=json.loads((OUT.parent/'factory-data-p14/runtime.private.json').read_text())
env={k:v for k,v in os.environ.items() if not any(t in k.upper() for t in ('API_KEY','API_SECRET','JWT_SECRET','DB_PASSWORD','INTERNAL_API_SECRET'))}
env.update(JWT_SECRET=secure['jwt'],INTERNAL_API_SECRET=secure['internal'],VISION_ENTRY_WORKER_TOKEN=secure['worker'])
with (OUT/'java.log').open('w',encoding='utf-8') as log:java=subprocess.Popen(['C:/Program Files/Zulu/zulu-21/bin/java.exe','@'+str(argfile)],cwd=ROOT/'backend/java/cretas-api',env=env,stdout=log,stderr=log,creationflags=subprocess.CREATE_NO_WINDOW)
env.update(VITE_BACKEND_URL='http://127.0.0.1:'+str(JAVA_PORT),VITE_PYTHON_URL='http://127.0.0.1:18098')
with (OUT/'web.log').open('w',encoding='utf-8') as log:web=subprocess.Popen(['node',str(ROOT/'web-admin/node_modules/vite/bin/vite.js'),'--host','127.0.0.1','--port',str(WEB_PORT),'--strictPort'],cwd=ROOT/'web-admin',env=env,stdout=log,stderr=log,creationflags=subprocess.CREATE_NO_WINDOW)
runtime={**meta,'javaPid':java.pid,'webPid':web.pid,'javaArgFile':str(argfile),'ports':[JAVA_PORT,WEB_PORT,OBJECT_PORT],'sourceRuntimeUntouched':True,'acceptedProductBase':'2f519de714a68ed1f71cc1e85972a153e8d524b7' if P34 else '716c3474e3df9361cb339cef167266cb3bfbf2fb' if P31 else '7d80003c86bd985a02f0e87847585d89fd2a2219'}
(OUT/'runtime.json').write_text(json.dumps(runtime,indent=2),encoding='utf-8')
print(json.dumps({'status':'STARTED_NOT_YET_ACCEPTED','database':TARGET,'javaPid':java.pid,'webPid':web.pid}))
