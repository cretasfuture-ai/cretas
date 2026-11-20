"""Cross-worker FIFO inference admission and content-free usage accounting.

Only hashes, timing and provider token counts are retained. No hourly quota.
Long waits return to the caller's durable queue instead of monopolizing HTTP.
"""
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import time
import uuid

class ReviewBusy(RuntimeError):pass

WAIT_SECONDS=120
POLL_SECONDS=0.2

def _key(value):return hashlib.sha256(str(value).encode()).hexdigest()

def _connection():
    path=Path(os.getenv('SMARTBI_ALERT_AI_STATE_FILE') or Path.home()/'.cache/cretas/smartbi-alert-ai/state.sqlite3')
    path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
    # Admission/usage writes are short, but schema checks can contend with a
    # sibling connection on Windows. Wait for that transaction, not a quota.
    db=sqlite3.connect(path,timeout=5,isolation_level=None)
    db.execute('CREATE TABLE IF NOT EXISTS leases (resource TEXT PRIMARY KEY, owner TEXT NOT NULL, expires REAL NOT NULL)')
    db.execute('CREATE TABLE IF NOT EXISTS attempts (bucket TEXT NOT NULL, at REAL NOT NULL)')
    db.execute('CREATE INDEX IF NOT EXISTS attempts_bucket_time ON attempts(bucket,at)')
    db.execute('CREATE TABLE IF NOT EXISTS inference_queue (sequence INTEGER PRIMARY KEY AUTOINCREMENT, resource TEXT NOT NULL, owner TEXT UNIQUE NOT NULL, expires REAL NOT NULL)')
    db.execute('CREATE INDEX IF NOT EXISTS inference_queue_resource ON inference_queue(resource,sequence)')
    db.execute('''CREATE TABLE IF NOT EXISTS inference_usage (
        request_id TEXT PRIMARY KEY,resource TEXT NOT NULL,tenant_hash TEXT NOT NULL,user_hash TEXT NOT NULL,
        model TEXT,started_at REAL NOT NULL,finished_at REAL NOT NULL,queue_seconds REAL NOT NULL,
        outcome TEXT NOT NULL,input_tokens INTEGER,output_tokens INTEGER,total_tokens INTEGER,
        provider_duration_seconds REAL)''')
    return db

def run(infer,payload,config,tenant,user):
    resource=_key(json.dumps({k:config.get(k) for k in ('transport','host','base')},sort_keys=True))
    owner=uuid.uuid4().hex;queued=time.monotonic();deadline=queued+WAIT_SECONDS
    db=_connection()
    acquired=False
    try:
        db.execute('INSERT INTO inference_queue(resource,owner,expires) VALUES (?,?,?)',(resource,owner,time.time()+WAIT_SECONDS+1))
        while time.monotonic()<deadline:
            now=time.time()
            try:
                db.execute('BEGIN IMMEDIATE')
                db.execute('DELETE FROM leases WHERE expires<=?',(now,))
                db.execute('DELETE FROM inference_queue WHERE expires<=?',(now,))
                head=db.execute('SELECT owner FROM inference_queue WHERE resource=? ORDER BY sequence LIMIT 1',(resource,)).fetchone()
                if head and head[0]==owner and not db.execute('SELECT 1 FROM leases WHERE resource=?',(resource,)).fetchone():
                    lease_seconds=min(480,max(180,int(getattr(payload,'timeout_seconds',150))+30))
                    db.execute('INSERT INTO leases VALUES (?,?,?)',(resource,owner,now+lease_seconds))
                    db.execute('DELETE FROM inference_queue WHERE owner=?',(owner,))
                    acquired=True
                db.execute('COMMIT')
            except sqlite3.OperationalError:
                if db.in_transaction:db.execute('ROLLBACK')
            if acquired:break
            time.sleep(POLL_SECONDS)
        if not acquired:raise ReviewBusy()
        started=time.time();result=None;failure=None
        try:
            result=infer(payload,config)
            if getattr(result,'usage',None) is not None:
                result.usage={**result.usage,'requestId':owner}
            return result
        except Exception as exc:
            failure=exc
            if getattr(exc,'usage',None) is not None:
                exc.usage={**exc.usage,'requestId':owner}
            raise
        finally:
            usage=getattr(result if failure is None else failure,'usage',None) or {}
            db.execute('''INSERT INTO inference_usage VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)''',
                       (owner,resource,_key(tenant),_key((tenant,user)),config.get('model'),started,time.time(),
                        max(0,time.monotonic()-queued-(time.time()-started)),
                        'returned' if failure is None else 'failed',usage.get('inputTokens'),usage.get('outputTokens'),
                        usage.get('totalTokens'),usage.get('durationSeconds')))
    except sqlite3.OperationalError:
        if db.in_transaction:db.execute('ROLLBACK')
        raise ReviewBusy() from None
    finally:
        try:
            db.execute('DELETE FROM inference_queue WHERE owner=?',(owner,))
            if acquired:db.execute('DELETE FROM leases WHERE resource=? AND owner=?',(resource,owner))
        finally:db.close()
