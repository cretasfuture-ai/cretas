"""Local test follow-up metadata. Never changes source records or rule outcomes."""
from datetime import datetime, timezone
import os
from pathlib import Path
import sqlite3


class FollowupConflict(Exception):
    pass


def destination():
    value=os.environ.get('BLACKLAKE_ALERT_FOLLOWUP_DB')
    if not value or not Path(value).is_absolute():
        raise RuntimeError('FOLLOWUP_STORAGE_NOT_CONFIGURED')
    return Path(value).resolve()


def connection(path):
    path.parent.mkdir(parents=True,exist_ok=True)
    db=sqlite3.connect(path,timeout=2,isolation_level=None)
    db.row_factory=sqlite3.Row
    return db


def initialize(db):
    db.execute('''CREATE TABLE IF NOT EXISTS alert_followups (
      tenant_id TEXT NOT NULL, source_factory_id TEXT NOT NULL, run_id TEXT NOT NULL,
      alert_id TEXT NOT NULL, status TEXT NOT NULL CHECK(status IN ('open','awaiting_data','verified')),
      note TEXT NOT NULL, revision INTEGER NOT NULL, updated_by INTEGER NOT NULL, updated_at TEXT NOT NULL,
      PRIMARY KEY(tenant_id,source_factory_id,run_id,alert_id))''')
    db.execute('''CREATE TABLE IF NOT EXISTS alert_followup_events (
      id INTEGER PRIMARY KEY,tenant_id TEXT NOT NULL,source_factory_id TEXT NOT NULL,run_id TEXT NOT NULL,
      alert_id TEXT NOT NULL,revision INTEGER NOT NULL,status TEXT NOT NULL,
      updated_by INTEGER NOT NULL,updated_at TEXT NOT NULL)''')


def scope(config,run_id):
    if config['run_id']!=run_id:
        raise ValueError('FOLLOWUP_RUN_MISMATCH')
    return config['tenant_id'],str(config['source_factory_id']),run_id


def wire(row):
    return {'alertId':row['alert_id'],'status':row['status'],'note':row['note'],
            'revision':row['revision'],'updatedBy':row['updated_by'],'updatedAt':row['updated_at']}


def load(config,run_id):
    key=scope(config,run_id);path=destination()
    if not path.exists():return {}
    db=connection(path)
    try:
        db.execute('PRAGMA query_only=ON')
        rows=db.execute('SELECT * FROM alert_followups WHERE tenant_id=? AND source_factory_id=? AND run_id=?',key).fetchall()
        return {r['alert_id']:wire(r) for r in rows}
    finally:db.close()


def save(config,run_id,alert_id,status,note,expected_revision,actor):
    key=(*scope(config,run_id),alert_id)
    if status not in ('open','awaiting_data','verified') or not note.strip() or len(note)>500:
        raise ValueError('FOLLOWUP_STATUS_AND_NOTE_REQUIRED')
    if not isinstance(actor,int) or isinstance(actor,bool) or actor<=0:
        raise ValueError('FOLLOWUP_AUTHENTICATED_ACTOR_REQUIRED')
    db=connection(destination())
    try:
        initialize(db);db.execute('BEGIN IMMEDIATE')
        row=db.execute('SELECT * FROM alert_followups WHERE tenant_id=? AND source_factory_id=? AND run_id=? AND alert_id=?',key).fetchone()
        revision=row['revision'] if row else 0
        if revision!=expected_revision:raise FollowupConflict()
        now=datetime.now(timezone.utc).isoformat()
        db.execute('''INSERT INTO alert_followups(tenant_id,source_factory_id,run_id,alert_id,status,note,revision,updated_by,updated_at)
                   VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(tenant_id,source_factory_id,run_id,alert_id)
                   DO UPDATE SET status=excluded.status,note=excluded.note,revision=excluded.revision,
                   updated_by=excluded.updated_by,updated_at=excluded.updated_at''',
                   (*key,status,note.strip(),revision+1,actor,now))
        db.execute('INSERT INTO alert_followup_events(tenant_id,source_factory_id,run_id,alert_id,revision,status,updated_by,updated_at) VALUES(?,?,?,?,?,?,?,?)',
                   (*key,revision+1,status,actor,now))
        db.execute('COMMIT')
        return {'alertId':alert_id,'status':status,'note':note.strip(),'revision':revision+1,'updatedBy':actor,'updatedAt':now}
    except Exception:
        if db.in_transaction:db.execute('ROLLBACK')
        raise
    finally:db.close()
