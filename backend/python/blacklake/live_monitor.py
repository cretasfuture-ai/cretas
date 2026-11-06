"""Local durable one-minute collection. Credentials never enter state or browser."""
import argparse
from copy import deepcopy
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import sqlite3
import time
import threading
from contextlib import contextmanager
import uuid

from .contracts import BlacklakeError
from .full_archive import Archive, digest
from .lineage_probe import TASK_FIELDS, OUTPUT_FIELDS, minimized
from .period_projection import instant
from .stock_reconciliation import reconcile

SCHEMA='blacklake-live/1'
_init_lock=threading.Lock()


@contextmanager
def initialization_lock(path):
    # WAL mode cannot be changed inside a SQLite transaction. Serialize the
    # complete first-open sequence across both threads and Windows processes.
    with _init_lock:
        handle=open(str(path)+'.init.lock','a+b')
        try:
            handle.seek(0,2)
            if handle.tell()==0:handle.write(b'0');handle.flush()
            deadline=time.monotonic()+15
            while True:
                handle.seek(0)
                try:
                    if os.name=='nt':
                        import msvcrt
                        msvcrt.locking(handle.fileno(),msvcrt.LK_NBLCK,1)
                    else:
                        import fcntl
                        fcntl.flock(handle.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB)
                    break
                except OSError:
                    if time.monotonic()>=deadline:raise RuntimeError('LIVE_INITIALIZATION_BUSY') from None
                    time.sleep(0.05)
            try:yield
            finally:
                handle.seek(0)
                if os.name=='nt':msvcrt.locking(handle.fileno(),msvcrt.LK_UNLCK,1)
                else:fcntl.flock(handle.fileno(),fcntl.LOCK_UN)
        finally:handle.close()


class CollectionFailure(BlacklakeError):
    def __init__(self,code,requests):
        super().__init__(code);self.requests=requests


def now():return datetime.now(timezone.utc).isoformat()


def connect(path):
    if not Path(path).is_absolute():raise ValueError('LIVE_STATE_ABSOLUTE_PATH_REQUIRED')
    with initialization_lock(path):
        c=sqlite3.connect(path,timeout=15);c.row_factory=sqlite3.Row
        try:
            c.execute('PRAGMA busy_timeout=15000');c.execute('PRAGMA journal_mode=WAL')
            c.executescript('''CREATE TABLE IF NOT EXISTS monitor (id INTEGER PRIMARY KEY CHECK(id=1), scope TEXT NOT NULL, started TEXT NOT NULL, ends TEXT NOT NULL, next_at TEXT, error TEXT, analysis TEXT, owner TEXT, lease_until TEXT);
    CREATE TABLE IF NOT EXISTS content (hash TEXT PRIMARY KEY, payload TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS cycle (id TEXT PRIMARY KEY, started TEXT NOT NULL, finished TEXT, status TEXT NOT NULL, requests INTEGER NOT NULL DEFAULT 0, changed INTEGER NOT NULL DEFAULT 0, hash TEXT, error TEXT, report_id TEXT);
    CREATE INDEX IF NOT EXISTS cycle_finished ON cycle(status,finished);
    ''')
            c.execute('BEGIN IMMEDIATE')
            columns={r[1] for r in c.execute('PRAGMA table_info(cycle)')}
            for column in ('counts','work_at','stock_at'):
                if column not in columns:c.execute(f'ALTER TABLE cycle ADD COLUMN {column} TEXT')
            if 'nightly' not in {r[1] for r in c.execute('PRAGMA table_info(monitor)')}:
                c.execute('ALTER TABLE monitor ADD COLUMN nightly TEXT')
            c.commit()
        except Exception:
            c.rollback();c.close();raise
    return c


def config(path):
    path=Path(path)
    if not path.is_absolute():raise ValueError('LIVE_CONFIG_ABSOLUTE_PATH_REQUIRED')
    v=json.loads(path.read_text(encoding='utf8'))
    if v.get('schema')!=SCHEMA or v.get('tenant_id')!='BLACKLAKE_POC_TEST' or v.get('intervalSeconds')!=60 or v.get('days')!=7:
        raise ValueError('LIVE_ISOLATED_CONFIGURATION_REQUIRED')
    if not v.get('source_factory_id') or not v.get('baselineRunId'):raise ValueError('LIVE_SOURCE_BINDING_REQUIRED')
    for key in ('db','observation','bom','supplement'):
        if not Path(v[key]).is_absolute():raise ValueError('LIVE_ABSOLUTE_PATH_REQUIRED')
    return v


def binding(v):return digest([v['tenant_id'],str(v['source_factory_id']),v['baselineRunId']])


def initialize(v):
    c=connect(v['db'])
    with c:
        at=now();ends=(instant(at)+timedelta(days=7)).isoformat()
        c.execute('INSERT OR IGNORE INTO monitor(id,scope,started,ends,next_at) VALUES(1,?,?,?,?)',(binding(v),at,ends,at))
        if c.execute('SELECT scope FROM monitor').fetchone()[0]!=binding(v):raise ValueError('LIVE_SCOPE_MISMATCH')
    c.close()


def latest(v,cycle_id=None):
    c=connect(v['db'])
    try:
        m=c.execute('SELECT * FROM monitor WHERE id=1').fetchone()
        if not m or m['scope']!=binding(v):raise ValueError('LIVE_SCOPE_MISMATCH')
        row=(c.execute("SELECT * FROM cycle WHERE status='success' AND id=?",(cycle_id,)).fetchone() if cycle_id else
             c.execute("SELECT * FROM cycle WHERE status='success' ORDER BY finished DESC LIMIT 1").fetchone())
        if cycle_id and not row:raise ValueError('LIVE_CYCLE_NOT_FOUND')
        if not row:return None
        p=json.loads(c.execute('SELECT payload FROM content WHERE hash=?',(row['hash'],)).fetchone()[0])
        if digest(p)!=row['hash']:raise ValueError('LIVE_CONTENT_INTEGRITY_FAILED')
        return {**p,'checkedAt':row['finished'],'workOrdersAt':row['work_at'] or row['finished'],
                'stockAt':row['stock_at'] or row['finished'],'contentHash':row['hash'],'cycleId':row['id']}
    finally:c.close()


def status(v,history_offset=0):
    if type(history_offset) is not int or not 0<=history_offset<=1000000:raise ValueError('LIVE_HISTORY_OFFSET_INVALID')
    c=connect(v['db'])
    try:
        m=c.execute('SELECT * FROM monitor WHERE id=1').fetchone()
        if not m or m['scope']!=binding(v):raise ValueError('LIVE_SCOPE_MISMATCH')
        rows=c.execute('SELECT * FROM cycle ORDER BY started DESC LIMIT 100 OFFSET ?',(history_offset,)).fetchall()
        last_attempt=c.execute('SELECT started FROM cycle ORDER BY started DESC LIMIT 1').fetchone()
        counts=c.execute("SELECT count(*),sum(status='success'),sum(changed) FROM cycle").fetchone()
        success=c.execute("SELECT finished FROM cycle WHERE status='success' ORDER BY finished DESC LIMIT 1").fetchone()
        end=instant(m['ends'])<=instant(now())
        active=m['owner'] and m['lease_until'] and instant(m['lease_until'])>instant(now())
        reference=success[0] if success else m['started']
        stale=not end and not active and (instant(now())-instant(reference)).total_seconds()>180
        history=[]
        for r in rows:
            stats=json.loads(r['counts']) if r['counts'] else {}
            history.append({'id':r['id'],'startedAt':r['started'],'finishedAt':r['finished'],'status':r['status'],'requests':r['requests'],
                            'changed':bool(r['changed']),'counts':stats,'error':r['error'],'analysisReportId':r['report_id']})
        nightly=json.loads(m['nightly']) if m['nightly'] else {}
        return {'enabled':not end,'status':'completed' if end else 'stale' if stale else 'running',
                'startedAt':m['started'],'endsAt':m['ends'],'intervalSeconds':60,'lastAttemptAt':last_attempt[0] if last_attempt else None,
                'lastSuccessAt':success[0] if success else None,'nextRunAt':m['next_at'],'cycles':counts[0],
                'successfulCycles':counts[1] or 0,'changedCycles':counts[2] or 0,'error':m['error'] or nightly.get('error') or ('采集已超过三分钟没有成功，请检查本机任务和网络。' if stale else None),
                'nightly':nightly,
                'gaps':[{'from':r['finished'],'to':r['next_at']} for r in c.execute("SELECT * FROM (SELECT finished,lead(started) OVER (ORDER BY started) AS next_at FROM cycle WHERE status='success') WHERE (julianday(next_at)-julianday(finished))*86400>150")],
                'analysis':json.loads(m['analysis']) if m['analysis'] else {'status':'waiting','message':'等待首次采集与分析。'},'history':history,
                'historyOffset':history_offset,'historyHasMore':history_offset+len(rows)<counts[0],
                'dailyStats':[dict(r) for r in c.execute("SELECT date(started,'+8 hours') AS date,count(*) AS cycles,sum(status='success') AS successful,sum(changed) AS changed,sum(requests) AS requests FROM cycle GROUP BY date(started,'+8 hours') ORDER BY date DESC") ]}
    finally:c.close()


def record_nightly(v,successful=None):
    c=connect(v['db'])
    try:
        row=c.execute('SELECT nightly FROM monitor WHERE scope=?',(binding(v),)).fetchone()
        if not row:raise ValueError('LIVE_NIGHTLY_SCOPE_MISMATCH')
        state=json.loads(row[0]) if row[0] else {}
        state['lastAttemptAt']=now()
        if successful is True:state.update(lastSuccessAt=now(),error=None)
        elif successful is False:state['error']='夜间周期分析暂未提交完成，已保留数据与计划，自动重试。'
        with c:c.execute('UPDATE monitor SET nightly=? WHERE scope=?',(json.dumps(state,ensure_ascii=False),binding(v)))
    finally:c.close()


def seeded(v,baseline_projects=None):
    o=json.loads(Path(v['observation']).read_text(encoding='utf8'));s=json.loads(Path(v['supplement']).read_text(encoding='utf8'))
    b=json.loads(Path(v['bom']).read_text(encoding='utf8'))
    if ((o['tenant_id'],str(o['source_factory_id']),o['run_id'])!=(v['tenant_id'],str(v['source_factory_id']),v['baselineRunId'])
            or o.get('observationFingerprint')!=digest(o['data']) or o.get('sourceBusinessWrites')!=0
            or o.get('negativeControlVerified') is not True or s.get('fingerprint')!=digest({k:x for k,x in s.items() if k!='fingerprint'})
            or s.get('parentObservationId')!=o['probeId'] or s.get('baselineRunId')!=o['run_id']
            or b.get('parentProbeId')!=o['probeId'] or b.get('complete') is not True):raise ValueError('LIVE_SEED_INVALID')
    if (o.get('baselineMutated') is not False or o.get('originalProjectsNotReturned')
            or {r.get('resource') for r in o.get('failures',[])}-{'bom'}
            or not all(c.get('dualQueryEqual') is True for c in o.get('parentControls',[]))
            or s.get('schema')!='blacklake-operations-supplement/1' or s.get('complete') is not True
            or s.get('factoryVerified') is not True or s.get('sourceBusinessWrites')!=0 or s.get('failures')
            or (s.get('tenant_id'),str(s.get('source_factory_id')))!=(v['tenant_id'],str(v['source_factory_id']))
            or s.get('parentOutputFingerprint')!=digest(o['data']['output'])
            or (b.get('tenantId'),str(b.get('sourceFactoryId')),b.get('baselineRunId'))!=(v['tenant_id'],str(v['source_factory_id']),v['baselineRunId'])
            or b.get('sourceBusinessWrites')!=0 or b.get('failures')):raise ValueError('LIVE_SEED_UNVERIFIED')
    if baseline_projects is None or o.get('snapshot_fingerprint')!=digest(baseline_projects):raise ValueError('LIVE_SEED_BASELINE_REQUIRED')
    if {(str(p['id']),p['projectCode']) for p in baseline_projects}!={(str(p['id']),p['projectCode']) for p in o['data']['project']}:raise ValueError('LIVE_SEED_COHORT_MISMATCH')
    from .operational_source import project_observation
    from .operations_supplement import apply
    configured={'tenant_id':v['tenant_id'],'source_factory_id':str(v['source_factory_id']),'run_id':v['baselineRunId']}
    metadata={'run_id':v['baselineRunId'],'finished_at':o['started_at'],'stock_pairs_not_returned':0}
    data,metadata=project_observation({'project':baseline_projects,'stock':[]},metadata,configured,o,b)
    data,_=apply(data,metadata,configured,s)
    if instant(b.get('finishedAt')) is None or instant(b['finishedAt'])<instant(o['finished_at']):raise ValueError('LIVE_BOM_TIME_INVALID')
    data=minimized(data);data['warehouse']=s['warehouses']
    return {'data':data,'controls':o['parentControls'],'parentHashes':{str(r['id']):digest(r) for r in data['project']},
            'referenceAt':b['finishedAt'],'materialAt':o['finished_at'],'stockAt':s['finishedAt']}


def collect(v, previous=None, max_requests=1000):
    from .full_probe import context
    directory,client,store,cache=context();requests=0
    if (store.scope[0],str(store.scope[1]))!=(v['tenant_id'],str(v['source_factory_id'])):
        client.close();store.connection.close();cache.close();raise ValueError('LIVE_SOURCE_SCOPE_MISMATCH')
    archive=Archive(client,store,json.loads((Path(__file__).parent/'full_contract.json').read_text()),v['baselineRunId'])
    try:prior=previous or seeded(v,archive.raw_rows('project'))
    except Exception:
        client.close();store.connection.close();cache.close();raise
    data=deepcopy(prior['data']);controls=deepcopy(prior['controls']);hashes=prior['parentHashes'];at=now()
    original=client.query
    def query(resource,body):
        nonlocal requests
        if requests>=max_requests:raise BlacklakeError('LIVE_REQUEST_BOUND_REACHED')
        requests+=1
        return original(resource,body)
    client.query=query
    def fetch(resource,body):
        rows,_=archive.fetch(resource,body)
        # Validate stable keys and factory claims even when not writing source archive.
        seen=set()
        for r in rows:
            key=archive.identity(resource,r,())
            if key in seen:raise BlacklakeError('LIVE_RESOURCE_DUPLICATE')
            seen.add(key);archive.normalized(resource,r,())
        return rows
    try:
        with client.coordination.lease():
            projects=[]
            for state in ('0','10','20','30'):
                rows=fetch('project',{'status':state})
                if any(str(r.get('status'))!=state for r in rows):raise BlacklakeError('LIVE_STATUS_FILTER_REJECTED')
                projects.extend(rows)
            ids={str(p['id']) for p in projects}
            if len(ids)!=len(projects):raise BlacklakeError('LIVE_PROJECT_DUPLICATE')
            # Disappeared parents remain a visible completeness issue; never silently delete history.
            missing={str(p['id']) for p in data['project']}-ids
            if missing:raise BlacklakeError('LIVE_PREVIOUS_PARENT_NOT_RETURNED')
            projects=minimized(projects)
            data['project']=projects
            labor_key=None
            for resource,fields in [('task',TASK_FIELDS),('output',OUTPUT_FIELDS)]:
                rows=[]
                for offset in range(0,len(projects),50):
                    parents=[p['id'] for p in projects[offset:offset+50]]
                    part=fetch(resource,{'projectIds':parents})
                    if any(str(r.get('projectId')) not in {str(p) for p in parents} for r in part):raise BlacklakeError('LIVE_CHILD_SCOPE_MISMATCH')
                    for row in part:
                        clean={k:x for k,x in row.items() if k in fields and k!='productionWorkerKey'}
                        if resource=='output' and row.get('productionStaffId') is not None:
                            from .labor_evidence import local_person_key,person_key
                            if labor_key is None:labor_key=local_person_key(directory)
                            clean['productionWorkerKey']=person_key(row['productionStaffId'],labor_key,store.scope)
                        rows.append(clean)
                if len({str(r['id']) for r in rows})!=len(rows):raise BlacklakeError('LIVE_CHILD_DUPLICATE')
                data[resource]=rows
            orders=[]
            for direction in (10,20):
                part=fetch('stock_order',{'orderType':direction})
                if any(r.get('orderType')!=direction for r in part):raise BlacklakeError('LIVE_DIRECTION_FILTER_REJECTED')
                orders.extend(part)
            data['stock_order']=minimized(orders)
            work_at=now()
            for resource in ('product','process','routing','unit','warehouse'):data[resource]=minimized(fetch(resource,{}))
            warehouses=[r.get('simpleVO',r) for r in data['warehouse']]
            pairs=[{'productId':p['id'],'warehouseId':w['id']} for p in data['product'] for w in warehouses]
            balances=[];seen=set()
            for offset in range(0,len(pairs),100):
                part=pairs[offset:offset+100];allowed={(str(r['productId']),str(r['warehouseId'])) for r in part}
                rows=fetch('stock',{'stockInfoQryOpenApiCOList':part})
                for r in rows:
                    key=(str(r.get('productId')),str(r.get('warehouseId')))
                    if key not in allowed or key in seen:raise BlacklakeError('LIVE_STOCK_SCOPE_MISMATCH')
                    seen.add(key);balances.append(r)
            data['stock']=minimized(balances)
            stock_at=now()
            # Existing parent evidence is retained with its own clock. Refresh changed/new parents,
            # and revalidate all parent material filters hourly without exceeding API quotas.
            refresh_all=(instant(at)-instant(prior['materialAt'])).total_seconds()>=3600
            parent_map={str(c['projectId']):c for c in controls}
            material_map={str(c['projectId']):[r for r in data.get('materials',[]) if str(r.get('id')) in {str(i) for i in c['materialIds']}] for c in controls}
            for p in projects:
                key=str(p['id'])
                if refresh_all or hashes.get(key)!=digest(p) or key not in parent_map:
                    first=fetch('materials',{'projectId':p['id']});second=fetch('materials',{'projectCode':p['projectCode']})
                    if len(first)!=len(second) or len({digest(r) for r in first})!=len(first) or {digest(r) for r in first}!={digest(r) for r in second}:raise BlacklakeError('LIVE_MATERIAL_PARENT_UNVERIFIED')
                    if any(r.get('projectId') is not None and str(r['projectId'])!=key for r in first):raise BlacklakeError('LIVE_MATERIAL_SCOPE_MISMATCH')
                    parent_map[key]={'projectId':p['id'],'dualQueryEqual':True,'materialIds':[r['id'] for r in first]}
                    material_map[key]=minimized(first)
            data['materials']=[r for p in projects for r in material_map.get(str(p['id']),[])]
            controls=[parent_map[str(p['id'])] for p in projects]
            if len({str(r['id']) for r in data['materials']})!=len(data['materials']):raise BlacklakeError('LIVE_MATERIAL_DUPLICATE_PARENT')
            material_at=now() if refresh_all else prior['materialAt']
            # Daily BOM refresh, plus any newly visible product; preserve earlier reference clock.
            ref_at=prior['referenceAt']
            if (instant(at)-instant(ref_at)).total_seconds()>=86400 or {str(r['id']) for r in data['product']}!={str(r['id']) for r in prior['data']['product']}:
                bom=[]
                for p in data['product']:
                    rows=fetch('bom',{'lastProductCode':p['productCode']})
                    if any(r.get('lastProductCode')!=p['productCode'] for r in rows):raise BlacklakeError('LIVE_BOM_PARENT_UNVERIFIED')
                    bom.extend(rows)
                data['bom']=minimized(bom);ref_at=now()
            if client.factory_verified is not True:raise BlacklakeError('LIVE_FACTORY_NOT_VERIFIED')
            client.coordination.check_lease()
        return {'data':data,'controls':controls,'parentHashes':{str(p['id']):digest(p) for p in projects},
                'referenceAt':ref_at,'materialAt':material_at,
                'observedAt':{'workOrders':work_at,'stock':stock_at},
                'counts':{k:len(rows) for k,rows in data.items()},'pairsNotReturned':len(pairs)-len(seen)},requests
    except Exception as e:
        code=str(e) if isinstance(e,BlacklakeError) else type(e).__name__
        raise CollectionFailure(code,requests) from None
    finally:client.close();store.connection.close();cache.close()


def cycle(v, collector=collect):
    initialize(v);c=connect(v['db']);owner=uuid.uuid4().hex;at=now();identity=uuid.uuid4().hex
    stopped=threading.Event();renewal=None
    try:
        with c:
            acquired=c.execute('UPDATE monitor SET owner=?,lease_until=? WHERE id=1 AND scope=? AND (lease_until IS NULL OR lease_until<?) AND ends>?',
                (owner,(instant(at)+timedelta(minutes=2)).isoformat(),binding(v),at,at)).rowcount
            if not acquired:return False
            c.execute("UPDATE cycle SET status='failed',finished=?,error='进程中断，保留上次成功数据并重试。' WHERE status='running'",(at,))
            c.execute("INSERT INTO cycle(id,started,status) VALUES(?,?,'running')",(identity,at))
        def renew():
            while not stopped.wait(30):
                lease=connect(v['db'])
                try:
                    with lease:lease.execute('UPDATE monitor SET lease_until=? WHERE owner=?',((instant(now())+timedelta(minutes=2)).isoformat(),owner))
                finally:lease.close()
        renewal=threading.Thread(target=renew,daemon=True);renewal.start()
        previous=latest(v);payload,requests=collector(v,previous)
        clocks=payload.pop('observedAt',{})
        fingerprint=digest(payload);finished=now()
        changed=previous is None or digest(payload['data'])!=digest(previous['data'])
        with c:
            if c.execute('SELECT owner FROM monitor').fetchone()[0]!=owner:raise ValueError('LIVE_LEASE_LOST')
            c.execute('INSERT OR IGNORE INTO content(hash,payload) VALUES(?,?)',(fingerprint,json.dumps(payload,ensure_ascii=False)))
            c.execute("UPDATE cycle SET finished=?,status='success',requests=?,changed=?,hash=?,counts=?,work_at=?,stock_at=? WHERE id=?",(finished,requests,int(changed),fingerprint,json.dumps(payload['counts']),clocks.get('workOrders'),clocks.get('stock'),identity))
            c.execute('UPDATE monitor SET error=NULL,next_at=?,owner=NULL,lease_until=NULL WHERE owner=?',((instant(at)+timedelta(seconds=60)).isoformat(),owner))
        return True
    except Exception as e:
        code=str(e) if isinstance(e,(BlacklakeError,ValueError)) and str(e).replace('_','').isalnum() else type(e).__name__
        message='本次采集未完成，仍显示上次成功数据；稍后自动重试。'+code
        with c:
            c.execute("UPDATE cycle SET finished=?,status='failed',error=?,requests=? WHERE id=?",(now(),message,getattr(e,'requests',0),identity))
            c.execute('UPDATE monitor SET error=?,next_at=?,owner=NULL,lease_until=NULL WHERE owner=?',(message,(instant(now())+timedelta(seconds=60)).isoformat(),owner))
        return False
    finally:
        stopped.set()
        if renewal is not None:renewal.join(timeout=3)
        c.close()


def load(data,metadata,configured,path,cycle_id=None):
    v=config(path)
    if (v['tenant_id'],str(v['source_factory_id']),v['baselineRunId'])!=(configured['tenant_id'],str(configured['source_factory_id']),configured['run_id']):raise ValueError('LIVE_READER_SCOPE_MISMATCH')
    p=latest(v,cycle_id)
    if p is None:return data,metadata
    source=deepcopy(p['data']);meta=deepcopy(metadata)
    tracked=json.loads(Path(v['supplement']).read_text(encoding='utf8'))['stock']
    tracked=[r for r in tracked if float(r['qtyInWarehouse'])<0]
    meta.update(run_id='live:'+p['contentHash'][:32],finished_at=p['workOrdersAt'],stock_finished_at=p['stockAt'],stock_latest_finished_at=p['stockAt'],
                stock_pairs_not_returned=p['pairsNotReturned'],operational_parent_controls=p['controls'])
    snapshots=meta.get('stock_snapshots',[])
    c=connect(v['db'])
    try:
        # Latest snapshot of each collected date; retain minute evidence in SQLite,
        # without loading every minute's whole inventory into each browser request.
        rows=c.execute("SELECT coalesce(stock_at,finished) AS stock_time,hash FROM cycle WHERE status='success' AND finished<=? AND coalesce(stock_at,finished) IN (SELECT max(coalesce(stock_at,finished)) FROM cycle WHERE status='success' AND finished<=? GROUP BY date(coalesce(stock_at,finished),'+8 hours')) ORDER BY stock_time",(p['checkedAt'],p['checkedAt'])).fetchall()
        for r in rows:
            body=json.loads(c.execute('SELECT payload FROM content WHERE hash=?',(r['hash'],)).fetchone()[0])
            if digest(body)!=r['hash']:raise ValueError('LIVE_HISTORY_CONTENT_INTEGRITY_FAILED')
            snapshots.append({'at':r['stock_time'],'records':body['data']['stock'],'pairsNotReturned':body['pairsNotReturned']})
    finally:c.close()
    meta['stock_snapshots']=snapshots
    meta['stock_reconciliations']=reconcile(source['stock'],source['stock_order'],p['stockAt'],tracked=tracked,units=source.get('unit'))
    meta['operational_source']={'workOrdersCheckedAt':p['workOrdersAt'],'inventoryCheckedAt':p['stockAt'],
        'materialReferencesCheckedAt':p['materialAt'],'bomReferencesCheckedAt':p['referenceAt'],
        'referenceExplanation':'实际材料金额取本次出入库、人工取本次任务；工单用料与BOM按各自复核时间，仅作配方参考，不替代实际耗料成本。',
        'baselineAnchor':configured['run_id'],'observationId':p['cycleId']}
    meta['operations_supplement']=None
    return source,meta


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--config',required=True);parser.add_argument('--once',action='store_true');args=parser.parse_args()
    v=config(args.config);initialize(v)
    while instant(now())<instant(status(v)['endsAt']):
        start=time.monotonic();ok=cycle(v)
        print(json.dumps({'at':now(),'success':ok,'sourceBusinessWrites':0}),flush=True)
        if args.once:return 0 if ok else 1
        time.sleep(max(1,60-(time.monotonic()-start)))
    return 0


if __name__=='__main__':raise SystemExit(main())
