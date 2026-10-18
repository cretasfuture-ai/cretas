from pathlib import Path
import os,json,sys,socket,subprocess,time,psutil

OUT=Path(os.environ['P10_ACCEPTANCE_DIR']).resolve()
assert OUT==Path.home()/'Documents/cretas-connect-20261015/factory-data-p10','Private P10 owner directory required'
ROOT=Path(__file__).resolve().parents[4]
SOURCE=OUT.parent/'vision/platform-entry'
OWNER='P10-REFUND-HTTP-20261016'
PORTS=[10062,18094,31762,18392]
assert sys.argv[1:] in [['start'],['status']]
def occupied(port):
    try:
        with socket.create_connection(('127.0.0.1',port),timeout=.3):return True
    except OSError:return False
if sys.argv[1]=='status':
    print(json.dumps({'ports':{str(p):occupied(p) for p in PORTS},'record':json.loads((OUT/'runtime.json').read_text()) if (OUT/'runtime.json').exists() else None}));sys.exit(0)
record=json.loads((OUT/'database.json').read_text());assert record['owner']==OWNER and record['state']=='restored' and record['database']=='p10_refund_http_20261016'
assert not any(occupied(p) for p in PORTS),'Acceptance ports occupied; inspect owner instead of replacing'
assert (OUT/'p10-clock-agent.jar').exists() and (OUT/'clock-classes').exists()
assert psutil.virtual_memory().available>5*1024**3,'Insufficient local headroom; do not launch another JVM'
clock=OUT/'clock.txt'
if not clock.exists():clock.write_text('2026-10-31\n',encoding='utf-8')
secure=json.loads((SOURCE/'runtime.private.json').read_text())
db_credentials=json.loads((OUT/'database.private.json').read_text())
environment={k:v for k,v in os.environ.items() if not any(t in k.upper() for t in ['API_KEY','API_SECRET','JWT_SECRET','DB_PASSWORD','INTERNAL_API_SECRET'])}
environment.update({'PYTHON_ENV':'test','JWT_AUTH_ENABLED':'true','JWT_SECRET':secure['jwt'],'INTERNAL_API_SECRET':secure['internal'],
    'VISION_TRAINING_DRAFT_DIR':str(OUT/'vision-state'),'VISION_TRAINING_REGISTRY_FILE':'','VISION_ENTRY_WORKER_TOKEN':secure['worker'],
    'RESTAURANT_OPS_ETL_ENABLED':'false','PYTHONUNBUFFERED':'1','KMP_DUPLICATE_LIB_OK':'TRUE',
    'POSTGRES_ENABLED':'true','POSTGRES_HOST':'127.0.0.1','POSTGRES_PORT':'55447','POSTGRES_DB':record['database'],'POSTGRES_USER':db_credentials['username'],'POSTGRES_PASSWORD':db_credentials['password'],
    'FOOD_KB_HOST':'127.0.0.1','FOOD_KB_PORT':'55447','FOOD_KB_DATABASE':record['database'],'FOOD_KB_USER':db_credentials['username'],'FOOD_KB_PASSWORD':db_credentials['password']})
with (OUT/'python.log').open('w',encoding='utf-8') as log:
    py=subprocess.Popen(['B:/anaconda3/python.exe','-m','uvicorn','main:app','--host','127.0.0.1','--port','18094','--lifespan','off'],cwd=ROOT/'backend/python',env=environment,stdout=log,stderr=log,creationflags=subprocess.CREATE_NO_WINDOW)
args=[json.loads(line) for line in (SOURCE/'entry-java.args').read_text(encoding='utf-8').splitlines() if line.strip()]
cp_index=args.index('-cp')+1
args[cp_index]=str(OUT/'clock-classes')+';'+args[cp_index]
args[cp_index+1]='p10.acceptance.CrossMonthHttpApplication'
database_url='jdbc:postgresql://127.0.0.1:55447/'+record['database']+'?stringtype=unspecified'
overrides={'server.port':'10062','server.address':'127.0.0.1','spring.datasource.url':database_url,'smartbi.postgres.url':database_url,
    'spring.jpa.hibernate.ddl-auto':'none','spring.flyway.enabled':'false','spring.sql.init.mode':'never',
    'python-smartbi.url':'http://127.0.0.1:18094','cretas.python.base-url':'http://127.0.0.1:18094','cretas.ai.service.url':'http://127.0.0.1:18094',
    'python-error-analysis.url':'http://127.0.0.1:18094','python-classifier.url':'http://127.0.0.1:18094',
    'logging.file.name':str(OUT/'java-application.log'),'spring.servlet.multipart.location':str(OUT/'uploads'),
    'p0.local-objects.root':str(OUT/'object-data'),'p0.local-objects.port':'18392','aliyun.oss.endpoint':'127.0.0.1:18392'}
args=[a for a in args if not(a.startswith('--') and a[2:].split('=',1)[0] in overrides)]
args+=['--'+k+'='+v for k,v in overrides.items()]
args=['-javaagent:'+str(OUT/'p10-clock-agent.jar'),'-Dp10.acceptance=true','-Dp10.clock.file='+str(clock)]+args
assert '--spring.jpa.hibernate.ddl-auto=none' in args and '--spring.flyway.enabled=false' in args
assert not any('jdbc:postgresql:' in a and 'vision_entry_probe_20261015' in a for a in args)
for folder in ['uploads','object-data','vision-state']:(OUT/folder).mkdir(exist_ok=True)
argfile=OUT/'entry-java.args';argfile.write_text('\n'.join('"'+a.replace('\\','\\\\').replace('"','\\"')+'"' for a in args),encoding='utf-8')
with (OUT/'java.log').open('w',encoding='utf-8') as log:
    java=subprocess.Popen(['C:/Program Files/Zulu/zulu-21/bin/java.exe','@'+str(argfile)],cwd=ROOT/'backend/java/cretas-api',env=environment,stdout=log,stderr=log,creationflags=subprocess.CREATE_NO_WINDOW)
node_environment=environment.copy();node_environment.update({'VITE_BACKEND_URL':'http://127.0.0.1:10062','VITE_PYTHON_URL':'http://127.0.0.1:18094'})
with (OUT/'web.log').open('w',encoding='utf-8') as log:
    web=subprocess.Popen(['node',str(ROOT/'web-admin/node_modules/vite/bin/vite.js'),'--host','127.0.0.1','--port','31762','--strictPort'],cwd=ROOT/'web-admin',env=node_environment,stdout=log,stderr=log,creationflags=subprocess.CREATE_NO_WINDOW)
runtime={'owner':OWNER,'database':record['database'],'ports':PORTS,'javaPid':java.pid,'pythonPid':py.pid,'webPid':web.pid,'clockFile':str(clock),'productionTouched':False,'originalRuntimeUntouched':True}
(OUT/'runtime.json').write_text(json.dumps(runtime,indent=2),encoding='utf-8')
deadline=time.monotonic()+300
while time.monotonic()<deadline:
    assert all(p.poll() is None for p in [java,py,web]),'Owned child exited; inspect retained logs'
    if all(occupied(p) for p in PORTS):print(json.dumps({'status':'PASS','runtime':runtime}));sys.exit(0)
    time.sleep(1)
raise RuntimeError('Private clone runtime startup timeout; original runtime remains running')
