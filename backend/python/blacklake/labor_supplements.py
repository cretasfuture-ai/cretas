"""Private, tenant-scoped labor roster supplements for Blacklake analysis.

This module deliberately has no Blacklake API or payroll integration.  It stores
only HMAC worker keys and timing facts in a local SQLite file.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import sqlite3
import secrets
from collections import defaultdict
from datetime import datetime, timezone
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator
from .labor_private_codec import seal as _seal,unseal as _unseal


class Segment(BaseModel):
    model_config = ConfigDict(extra="forbid")
    start: datetime
    end: datetime
    workers: list[str] = Field(min_length=1, max_length=500)
    breakMinutes: float = Field(default=0, ge=0, le=1440)
    shutdownMinutes: float = Field(default=0, ge=0, le=1440)

    @model_validator(mode="after")
    def valid(self):
        if self.start.tzinfo is None or self.start.utcoffset() is None or self.end.tzinfo is None or self.end.utcoffset() is None:
            raise ValueError("SEGMENT_TIMEZONE_REQUIRED")
        seconds = (self.end - self.start).total_seconds()
        if seconds <= 0 or seconds > 86400: raise ValueError("SEGMENT_DURATION_INVALID")
        if self.breakMinutes < 0 or self.shutdownMinutes < 0 or (self.breakMinutes + self.shutdownMinutes) * 60 > seconds:
            raise ValueError("SEGMENT_NET_DURATION_INVALID")
        self.workers = [w.strip() for w in self.workers]
        if len(self.workers) != len(set(self.workers)) or any(not w or len(w)>64 for w in self.workers):
            raise ValueError("SEGMENT_WORKERS_INVALID")
        return self

    @property
    def net_hours(self) -> float:
        return ((self.end - self.start).total_seconds() / 3600) - (self.breakMinutes + self.shutdownMinutes) / 60


class TaskSupplement(BaseModel):
    model_config = ConfigDict(extra="forbid")
    projectId: int
    taskId: int
    segments: list[Segment] = Field(default_factory=list,max_length=100)
    confirmed: bool
    expectedRevision: int = 0
    source: Literal["manual", "excel", "cretas_mes"]
    reference: str = Field(min_length=1, max_length=120)
    zeroConfirmed: bool = False

    @model_validator(mode="after")
    def valid(self):
        if self.projectId <= 0 or self.taskId <= 0 or self.expectedRevision < 0: raise ValueError("IDENTIFIER_INVALID")
        self.reference = self.reference.strip()
        if not self.reference: raise ValueError("REFERENCE_REQUIRED")
        if not self.confirmed: raise ValueError("CONFIRMATION_REQUIRED")
        if not self.segments and not self.zeroConfirmed: raise ValueError("EMPTY_SEGMENTS_REQUIRE_ZERO_CONFIRMATION")
        if self.segments and self.zeroConfirmed: raise ValueError("ZERO_CONFIRMATION_WITH_SEGMENTS")
        return self


class Batch(BaseModel):
    model_config = ConfigDict(extra="forbid")
    sourceRunId: str = Field(min_length=1,max_length=200)
    entries: list[TaskSupplement] = Field(min_length=1,max_length=200)


def _scope(config: dict) -> tuple[str, str, str]:
    try: return (str(config["tenant_id"]), str(config["source_factory_id"]), str(config["run_id"]))
    except KeyError as exc: raise ValueError("LABOR_SCOPE_REQUIRED") from exc


def _db_path(config: dict) -> Path | None:
    value = config.get("labor_supplements_path")
    return Path(value) if value else None


def _catalog(analysis: dict) -> list[dict]:
    rows = []
    for project in analysis.get("cost", {}).get("projects", []):
        for task in project.get("processes", []):
            if task.get("taskId") is None: continue  # Old snapshots cannot be assigned a guessed task ID.
            facts = {"projectId": project.get("projectId"), "projectCode": project.get("projectCode"), "product": project.get("product"),
                     "taskId": task.get("taskId"), "process": task.get("process"), "laborCost": task.get("laborCost"),
                     "laborEvidence": task.get("laborEvidence"),"hours":task.get('hours'),"projectLaborCost":project.get('laborCost')}
            signature = hashlib.sha256(json.dumps(facts, sort_keys=True, default=str, separators=(",", ":")).encode()).hexdigest()
            rows.append({"projectId": project.get("projectId"), "projectCode": project.get("projectCode"), "product": project.get("product"),
                         "taskId": task.get("taskId"), "process": task.get("process"), "laborCost": task.get("laborCost"), "signature": signature})
    if len({str(r['taskId']) for r in rows})!=len(rows):raise ValueError('CATALOG_DUPLICATE_TASK')
    return rows


def catalog(analysis: dict) -> list[dict]:
    return _catalog(analysis)


def _ensure(conn: sqlite3.Connection):
    conn.executescript("""
    CREATE TABLE IF NOT EXISTS labor_meta(scope TEXT PRIMARY KEY, revision INTEGER NOT NULL, secret BLOB NOT NULL);
    CREATE TABLE IF NOT EXISTS labor_tasks(scope TEXT NOT NULL, task_id TEXT NOT NULL, project_id TEXT NOT NULL,
      payload TEXT NOT NULL, signature TEXT NOT NULL, updated_at TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(scope,task_id));
    CREATE TABLE IF NOT EXISTS labor_audit(id INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL, revision INTEGER NOT NULL,
      actor TEXT NOT NULL, at TEXT NOT NULL, source TEXT NOT NULL, reference TEXT NOT NULL, source_run TEXT NOT NULL, signature TEXT NOT NULL, payload_hash TEXT NOT NULL, payload TEXT NOT NULL, task_id TEXT NOT NULL, project_id TEXT NOT NULL);
    """)


def _secret(conn, scope):
    row = conn.execute("SELECT secret FROM labor_meta WHERE scope=?", (scope,)).fetchone()
    if row: return bytes.fromhex(_unseal(row[0])['key'])
    secret = secrets.token_bytes(32)
    conn.execute("INSERT INTO labor_meta(scope,revision,secret) VALUES(?,?,?)", (scope, 0, _seal({'key':secret.hex()}))); return secret


def _worker_key(value: str, secret: bytes) -> str:
    return hmac.new(secret, value.encode(), hashlib.sha256).hexdigest()


def _wire(entry: TaskSupplement, secret: bytes) -> dict:
    return {"segments":[{"start":s.start.isoformat(), "end":s.end.isoformat(), "workers":[_worker_key(w, secret) for w in s.workers],
                          "breakMinutes":s.breakMinutes, "shutdownMinutes":s.shutdownMinutes} for s in entry.segments],
            "confirmed":entry.confirmed, "zeroConfirmed":entry.zeroConfirmed, "source":entry.source, "reference":entry.reference}


def store(config: dict, sourceRunId: str, entries: list[TaskSupplement] | Batch, analysis: dict, actor: str) -> dict:
    path = _db_path(config)
    if path is None: raise ValueError("LABOR_SUPPLEMENTS_PATH_REQUIRED")
    batch = entries if isinstance(entries, Batch) else Batch(sourceRunId=sourceRunId, entries=entries)
    if batch.sourceRunId != sourceRunId or str(sourceRunId) != str(analysis.get("runId")): raise ValueError("SOURCE_RUN_MISMATCH")
    cat = _catalog(analysis); by_id = {str(r["taskId"]): r for r in cat}; scope_tuple = _scope(config); scope = json.dumps(scope_tuple,separators=(',',':'))
    if len({str(e.taskId) for e in batch.entries}) != len(batch.entries): raise ValueError("DUPLICATE_TASK")
    path.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(path) as conn:
        _ensure(conn)
    with sqlite3.connect(path) as conn:
        conn.execute("BEGIN IMMEDIATE"); secret = _secret(conn, scope)
        current = conn.execute("SELECT revision FROM labor_meta WHERE scope=?", (scope,)).fetchone()[0]
        existing = {str(r[0]): _unseal(r[1]) for r in conn.execute("SELECT task_id,payload FROM labor_tasks WHERE scope=?", (scope,))}
        proposed = dict(existing)
        for entry in batch.entries:
            row = by_id.get(str(entry.taskId))
            if row is None or str(row["projectId"]) != str(entry.projectId): raise ValueError("TASK_PROJECT_MISMATCH")
            old_revision = conn.execute("SELECT revision FROM labor_tasks WHERE scope=? AND task_id=?", (scope, str(entry.taskId))).fetchone()
            if entry.expectedRevision != (old_revision[0] if old_revision else 0): raise ValueError("REVISION_CONFLICT")
            proposed[str(entry.taskId)] = _wire(entry, secret)
        intervals=[]
        for tid,payload in proposed.items():
            for seg in payload["segments"]:
                start=datetime.fromisoformat(seg["start"]); end=datetime.fromisoformat(seg["end"])
                for worker in seg["workers"]: intervals.append((worker,start,end,tid))
        intervals.sort(key=lambda x:(x[0],x[1]))
        for i,(worker,start,end,tid) in enumerate(intervals):
            for other,os,oe,ot in intervals[i+1:]:
                if other != worker: break
                if os >= end: break
                if ot != tid or os < end and start < oe: raise ValueError("WORKER_SEGMENT_OVERLAP")
        for entry in batch.entries:
            row = by_id[str(entry.taskId)]; payload = proposed[str(entry.taskId)]; now = datetime.now(timezone.utc).isoformat()
            old_revision = conn.execute("SELECT revision FROM labor_tasks WHERE scope=? AND task_id=?", (scope, str(entry.taskId))).fetchone()
            next_revision = (old_revision[0] if old_revision else 0) + 1
            conn.execute("INSERT OR REPLACE INTO labor_tasks(scope,task_id,project_id,payload,signature,updated_at,revision) VALUES(?,?,?,?,?,?,?)", (scope, str(entry.taskId), str(entry.projectId), _seal(payload), row["signature"], now, next_revision))
        revision = current + 1
        conn.execute("UPDATE labor_meta SET revision=? WHERE scope=?", (revision, scope))
        digest = hashlib.sha256(json.dumps([by_id[str(e.taskId)]["signature"] for e in batch.entries], sort_keys=True).encode()).hexdigest()
        for e in batch.entries:
            payload_hash=hashlib.sha256(json.dumps(proposed[str(e.taskId)],sort_keys=True,separators=(",", ":")).encode()).hexdigest()
            conn.execute("INSERT INTO labor_audit(scope,revision,actor,at,source,reference,source_run,signature,payload_hash,payload,task_id,project_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", (scope, revision, actor, datetime.now(timezone.utc).isoformat(), e.source, '', sourceRunId, digest, payload_hash, _seal(proposed[str(e.taskId)]), str(e.taskId), str(e.projectId)))
    return {"revision": str(revision), "count": len(batch.entries)}


def status(config: dict, analysis: dict, project_ids: list[int] | None = None, private: bool = False) -> dict:
    cat = _catalog(analysis); wanted = {str(x) for x in project_ids} if project_ids is not None else None
    tasks = [r for r in cat if wanted is None or str(r["projectId"]) in wanted]; path = _db_path(config); scope = json.dumps(_scope(config),separators=(',',':')); records = {}
    revision = "0"
    if path is not None and path.exists():
        with sqlite3.connect(path) as conn:
            row = conn.execute("SELECT revision FROM labor_meta WHERE scope=?", (scope,)).fetchone(); revision = str(row[0]) if row else "0"
            for task_id, payload_text, sig, updated, task_revision in conn.execute("SELECT task_id,payload,signature,updated_at,revision FROM labor_tasks WHERE scope=?", (scope,)):
                payload = _unseal(payload_text)
                records[str(task_id)] = (payload, sig, updated, payload.get("source"), payload.get("reference"), task_revision)
    out=[]; by_project=defaultdict(list)
    for row in tasks:
        rec=records.get(str(row["taskId"])); stale=rec is not None and rec[1] != row["signature"]
        payload=rec[0] if rec else None; hours=0.0
        if payload: hours=sum(max(0,(datetime.fromisoformat(s["end"])-datetime.fromisoformat(s["start"])).total_seconds()/3600-(s["breakMinutes"]+s["shutdownMinutes"])/60)*len(s["workers"] or [None]) for s in payload["segments"])
        ready=bool(payload and payload.get("confirmed") and not stale)
        participant_count=len({w for s in (payload or {}).get("segments",[]) for w in s["workers"]}) if ready else None
        item={**row, "revision":rec[5] if rec else 0, "status":"stale" if stale else ("ready" if ready else "missing"), "rosterComplete":bool(ready and participant_count), "participants":participant_count or None, "personHours":hours if ready else None, "updatedAt":rec[2] if rec else None, "source":rec[3] if rec else None, "reference":rec[4] if rec else None}
        out.append(item); by_project[str(row["projectId"])].append((row,item,hours,ready))
    orders=[]; total=Decimal(0); all_complete=True; all_workers=set(); all_hours=0.0; process=defaultdict(lambda:[0.0,set(),Decimal(0)])
    projects={str(p.get('projectId')):p for p in analysis.get('cost',{}).get('projects',[])}
    project_wages={k:p.get('laborCost') for k,p in projects.items()}
    for pid, group in by_project.items():
        complete=all(x[3] for x in group) and len(group)==len(projects[pid]['processes']); task_wage=sum((Decimal(str(x[0]["laborCost"])) for x in group if x[0]["laborCost"] is not None), Decimal(0)); raw_project_wage=project_wages.get(pid)
        complete = complete and all(x[0].get("laborCost") is not None and Decimal(str(x[0]["laborCost"])) >= 0 for x in group)
        wage=Decimal(str(raw_project_wage)) if raw_project_wage is not None else task_wage
        complete=complete and raw_project_wage is not None and wage >= 0 and abs(task_wage-wage) < Decimal("0.005")
        roster_complete=complete and all(x[1].get("participants") for x in group)
        ph=sum(x[2] for x in group) if complete else None
        participants=set(w for _,i,_,_ in group for s in ((records.get(str(i["taskId"]), ({},))[0]).get("segments",[]) if i["status"]=="ready" else []) for w in s["workers"])
        orders.append({"projectId":int(pid),"complete":complete,"rosterComplete":roster_complete,"allocationReady":bool(complete and ph and ph>0),"participants":len(participants) if roster_complete else None,"personHours":ph,"perPerson":float(wage/len(participants)) if roster_complete and participants else None,"perPersonHour":float(wage/Decimal(str(ph))) if complete and ph else None,"total":float(wage) if complete else None})
        all_complete &= complete; total += wage if complete else Decimal(0); all_workers.update(participants); all_hours += ph or 0
        allocations=[]
        if complete and ph and ph > 0:
            cents=wage.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP); allocated=Decimal(0)
            last_positive=max(i for i,g in enumerate(group) if g[2]>0)
            for index,(row,item,hours,ready) in enumerate(group):
                amount = cents-allocated if index==last_positive else min(cents-allocated,(cents*Decimal(str(hours))/Decimal(str(ph))).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)) if hours>0 else Decimal(0)
                allocated += amount; allocations.append(amount)
        else: allocations=[None]*len(group)
        for index,(row,item,hours,ready) in enumerate(group):
            if ready:
                task_workers={w for s in records[str(row["taskId"])][0].get("segments",[]) for w in s["workers"]}
                process[row["process"]][0]+=hours; process[row["process"]][1].update(task_workers)
                if allocations[index] is not None: process[row["process"]][2]+=allocations[index]
    requested={pid for pid in projects if wanted is None or pid in wanted}
    all_complete=all_complete and bool(tasks) and requested==set(by_project)
    roster_complete=all_complete and all(o['rosterComplete'] for o in orders)
    allocation_ready=all_complete and all(o['allocationReady'] for o in orders)
    summary={"complete":all_complete,"allocationReady":allocation_ready,"participants":len(all_workers) if roster_complete else None,"personHours":all_hours if all_complete else None,"perPerson":float(total/len(all_workers)) if roster_complete and all_workers else None,"perPersonHour":float(total/Decimal(str(all_hours))) if all_complete and all_hours else None,"total":float(total) if all_complete else None,"processes":[{"process":p,"personHours":v[0] if all_complete else None,"participants":len(v[1]) if roster_complete else None,"allocated":float(v[2]) if allocation_ready else None} for p,v in process.items()],"orders":orders}
    return {"sourceRunId": analysis.get("runId"), "revision":revision,"tasks":out,"summary":summary}


def revision(config: dict) -> str:
    """Read the local supplement version without creating or mutating a database."""
    path = _db_path(config)
    if path is None or not path.exists(): return "0"
    scope = json.dumps(_scope(config),separators=(',',':'))
    with sqlite3.connect(path) as conn:
        row = conn.execute("SELECT revision FROM labor_meta WHERE scope=?", (scope,)).fetchone()
    return str(row[0]) if row else "0"


def attach(analysis: dict, config: dict) -> dict:
    analysis["laborSupplements"] = status(config, analysis)
    return analysis
