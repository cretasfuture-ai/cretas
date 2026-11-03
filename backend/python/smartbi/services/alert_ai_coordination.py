"""Cross-worker local-model admission: short SQLite lease + rolling quotas.

Only hashed identities, timestamps and an opaque lease are retained. A slow
model returns busy to other callers immediately rather than queueing tenants.
"""
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import time
import uuid

class ReviewBusy(RuntimeError):pass
class ReviewQuota(RuntimeError):pass

def _key(value):return hashlib.sha256(str(value).encode()).hexdigest()

def _connection():
    path=Path(os.getenv('SMARTBI_ALERT_AI_STATE_FILE') or Path.home()/'.cache/cretas/smartbi-alert-ai/state.sqlite3')
    path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
    db=sqlite3.connect(path,timeout=0.2,isolation_level=None)
    db.execute('CREATE TABLE IF NOT EXISTS leases (resource TEXT PRIMARY KEY, owner TEXT NOT NULL, expires REAL NOT NULL)')
    db.execute('CREATE TABLE IF NOT EXISTS attempts (bucket TEXT NOT NULL, at REAL NOT NULL)')
    db.execute('CREATE INDEX IF NOT EXISTS attempts_bucket_time ON attempts(bucket,at)')
    return db

def run(infer,payload,config,tenant,user):
    resource=_key(json.dumps({k:config.get(k) for k in ('transport','host','base')},sort_keys=True))
    tenant_bucket='tenant:'+_key(tenant);user_bucket='user:'+_key((tenant,user))
    owner=uuid.uuid4().hex;now=time.time()
    db=_connection()
    try:
        try:
            db.execute('BEGIN IMMEDIATE')
            db.execute('DELETE FROM leases WHERE expires<=?',(now,))
            db.execute('DELETE FROM attempts WHERE at<?',(now-3600,))
            if db.execute('SELECT 1 FROM leases WHERE resource=?',(resource,)).fetchone():raise ReviewBusy()
            for bucket,limit in ((tenant_bucket,12),(user_bucket,6)):
                count=db.execute('SELECT count(*) FROM attempts WHERE bucket=? AND at>=?',(bucket,now-3600)).fetchone()[0]
                if count>=limit:raise ReviewQuota()
            db.execute('INSERT INTO leases VALUES (?,?,?)',(resource,owner,now+180))
            db.executemany('INSERT INTO attempts VALUES (?,?)',[(tenant_bucket,now),(user_bucket,now)])
            db.execute('COMMIT')
        except (ReviewBusy,ReviewQuota):
            db.execute('ROLLBACK');raise
        except sqlite3.OperationalError:
            if db.in_transaction:db.execute('ROLLBACK')
            raise ReviewBusy() from None
        try:return infer(payload,config)
        finally:db.execute('DELETE FROM leases WHERE resource=? AND owner=?',(resource,owner))
    finally:db.close()
