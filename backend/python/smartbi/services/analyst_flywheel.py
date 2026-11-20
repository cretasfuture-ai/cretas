"""Local candidate ledger; model agreement is never a training approval.

No external calls, scheduled training, prompt promotion or cross-tenant export.
Candidates remain local until evidence review; split groups cannot be reused.
"""
import hashlib
import json
from pathlib import Path
import sqlite3
from datetime import datetime,timezone


def digest(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,ensure_ascii=False,default=str).encode()).hexdigest()


class FlywheelStore:
    def __init__(self,path):
        self.path=Path(path)
        if not self.path.is_absolute():raise ValueError('FLYWHEEL_ABSOLUTE_LOCAL_PATH_REQUIRED')
        self.path.parent.mkdir(parents=True,exist_ok=True)
        with self.connect() as db:
            db.execute('''CREATE TABLE IF NOT EXISTS candidate (
                id TEXT PRIMARY KEY, scope TEXT NOT NULL, group_key TEXT NOT NULL,
                task TEXT NOT NULL, profile TEXT NOT NULL, state TEXT NOT NULL,
                evidence_json TEXT NOT NULL, issue_json TEXT NOT NULL,
                reviewer TEXT, split TEXT, created TEXT NOT NULL)''')

    def connect(self):return sqlite3.connect(str(self.path),timeout=5)

    def add(self,scope,group_key,task,profile,evidence,issues):
        scope=digest(scope);group_key=digest(group_key)
        identity=digest([scope,group_key,task,profile,evidence,issues])
        with self.connect() as db:
            db.execute('INSERT OR IGNORE INTO candidate VALUES (?,?,?,?,?,?,?,?,NULL,NULL,?)',
                (identity,scope,group_key,task,profile,'candidate',json.dumps(evidence,ensure_ascii=False),
                 json.dumps(issues,ensure_ascii=False),datetime.now(timezone.utc).isoformat()))
        return identity

    def review(self,scope,identity,state,reviewer,*,split=None):
        if state not in ('reviewed_candidate','human_confirmed','rejected','withdrawn') or not reviewer:
            raise ValueError('EXPLICIT_REVIEW_REQUIRED')
        if state=='human_confirmed' and (not reviewer.startswith('human:') or split not in ('train','holdout')):
            raise ValueError('HUMAN_CONFIRMATION_AND_SPLIT_REQUIRED')
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row=db.execute('SELECT group_key,state FROM candidate WHERE id=? AND scope=?',(identity,digest(scope))).fetchone()
            if not row:raise ValueError('CANDIDATE_SCOPE_NOT_FOUND')
            if row[1]=='withdrawn' and state!='withdrawn':raise ValueError('WITHDRAWN_CANDIDATE_NOT_REENROLLED')
            if state=='human_confirmed' and db.execute("SELECT 1 FROM candidate WHERE scope=? AND group_key=? AND state='human_confirmed' AND split<>? AND id<>?",(digest(scope),row[0],split,identity)).fetchone():
                raise ValueError('SPLIT_GROUP_LEAKAGE')
            db.execute('UPDATE candidate SET state=?,reviewer=?,split=? WHERE id=? AND scope=?',
                       (state,reviewer,split,identity,digest(scope)))

    def export_confirmed(self,scope,split):
        if split not in ('train','holdout'):raise ValueError('FLYWHEEL_SPLIT_REQUIRED')
        with self.connect() as db:
            rows=db.execute("SELECT id,task,profile,evidence_json,issue_json,reviewer FROM candidate WHERE scope=? AND state='human_confirmed' AND split=? ORDER BY id",(digest(scope),split)).fetchall()
        return [{'id':r[0],'task':r[1],'profile':r[2],'evidence':json.loads(r[3]),'issues':json.loads(r[4]),'reviewer':r[5]} for r in rows]
