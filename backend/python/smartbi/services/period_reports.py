"""Tenant-scoped, local SQLite persistence for periodic SmartBI reports."""
from __future__ import annotations

import json
import sqlite3
import uuid
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Mapping

DEFINITION_VERSION = "smartbi-period-report/2026-11-05.3"
REQUIRED_SCOPE_KEYS = ("tenant_id", "source_factory_id", "connector")


class LeaseError(RuntimeError):
    """Raised when a worker tries to use a missing, stale, or transferred lease."""


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _stamp(value: datetime | None = None) -> str:
    return (value or _utc_now()).isoformat().replace("+00:00", "Z")


def _json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str)


def _load(value: str | None) -> Any:
    return json.loads(value) if value else None


def _scope(scope: Mapping[str, Any]) -> tuple[dict[str, str], str]:
    if not isinstance(scope, Mapping):
        raise ValueError("scope must be a mapping")
    normalized: dict[str, str] = {}
    for key in REQUIRED_SCOPE_KEYS:
        value = scope.get(key)
        if not isinstance(value, str) or not value.strip():
            raise ValueError(f"scope.{key} is required")
        normalized[key] = value.strip()
    return normalized, _json(normalized)


def _period(period: Mapping[str, Any]) -> dict[str, Any]:
    if not isinstance(period, Mapping):
        raise ValueError("period must be a mapping")
    required = ("startDate", "endDate", "basis", "timezone")
    missing = [key for key in required if not period.get(key)]
    if missing:
        raise ValueError(f"period missing: {', '.join(missing)}")
    return dict(period)


def _safe_error(error: Any) -> str:
    if isinstance(error, Mapping):
        code = str(error.get("code") or "REPORT_FAILED").strip()
        message = str(error.get("message") or "报告生成失败").strip()
    elif isinstance(error, str):
        code, message = "REPORT_FAILED", error.strip()
    else:
        code, message = "REPORT_FAILED", "报告生成失败"
    code = "".join(char for char in code if char.isalnum() or char in "_.-")[:80] or "REPORT_FAILED"
    message = " ".join(message.split())[:500] or "报告生成失败"
    return f"{code}: {message}"


class ReportStore:
    """Durable local report queue with scope isolation and lease ownership."""

    def __init__(self, path: Path) -> None:
        self.path = Path(path)
        if not self.path.is_absolute():
            raise ValueError("ReportStore path must be absolute")

    def init(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as db:
            db.executescript(
                """
                CREATE TABLE IF NOT EXISTS period_reports (
                    id TEXT PRIMARY KEY,
                    scope_key TEXT NOT NULL,
                    tenant_id TEXT NOT NULL,
                    source_factory_id TEXT NOT NULL,
                    connector TEXT NOT NULL,
                    start_date TEXT NOT NULL,
                    end_date TEXT NOT NULL,
                    basis TEXT NOT NULL,
                    timezone TEXT NOT NULL,
                    period_json TEXT NOT NULL,
                    definition_version TEXT NOT NULL,
                    fingerprint TEXT NOT NULL,
                    revision INTEGER NOT NULL,
                    status TEXT NOT NULL CHECK (status IN ('queued','running','ready','failed')),
                    created_at TEXT NOT NULL,
                    finished_at TEXT,
                    result_json TEXT,
                    error_text TEXT,
                    trigger_name TEXT NOT NULL,
                    actor TEXT,
                    facts_json TEXT NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    lease_token TEXT,
                    lease_expires REAL,
                    next_attempt_at REAL
                );
                CREATE INDEX IF NOT EXISTS period_reports_scope_created
                    ON period_reports(scope_key, created_at DESC, revision DESC);
                CREATE INDEX IF NOT EXISTS period_reports_idempotency
                    ON period_reports(scope_key, start_date, end_date, basis, timezone, definition_version, fingerprint, revision DESC);
                CREATE TABLE IF NOT EXISTS period_schedule_seen (
                    scope_key TEXT NOT NULL,
                    schedule_key TEXT NOT NULL,
                    job_id TEXT NOT NULL,
                    marked_at TEXT NOT NULL,
                    PRIMARY KEY (scope_key, schedule_key)
                );
                CREATE TABLE IF NOT EXISTS period_schedule_checkpoints (
                    scope_key TEXT PRIMARY KEY,
                    last_tick TEXT NOT NULL
                );
                """
            )
            columns = {row["name"] for row in db.execute("PRAGMA table_info(period_reports)").fetchall()}
            if "next_attempt_at" not in columns:
                db.execute("ALTER TABLE period_reports ADD COLUMN next_attempt_at REAL")

    def _connect(self) -> sqlite3.Connection:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        db = sqlite3.connect(self.path, timeout=10, isolation_level=None)
        db.row_factory = sqlite3.Row
        return db

    def _ensure(self) -> None:
        self.init()

    @staticmethod
    def _public(row: sqlite3.Row, *, from_cache: bool = False) -> dict[str, Any]:
        return {
            "id": row["id"],
            "status": row["status"],
            "period": _load(row["period_json"]),
            "fingerprint": row["fingerprint"],
            "definitionVersion": row["definition_version"],
            "revision": row["revision"],
            "createdAt": row["created_at"],
            "finishedAt": row["finished_at"],
            "nextAttemptAt": _stamp(datetime.fromtimestamp(row["next_attempt_at"], timezone.utc)) if row["next_attempt_at"] is not None else None,
            "fromCache": from_cache,
            "result": _load(row["result_json"]),
            "error": row["error_text"],
            "trigger": row["trigger_name"],
        }

    def submit(
        self,
        scope: Mapping[str, Any],
        period: Mapping[str, Any],
        fingerprint: str,
        facts: Any,
        trigger: str = "manual",
        actor: str | None = None,
        force: bool = False,
    ) -> dict[str, Any]:
        normalized_scope, scope_key = _scope(scope)
        normalized_period = _period(period)
        if not isinstance(fingerprint, str) or not fingerprint.strip():
            raise ValueError("fingerprint is required")
        self._ensure()
        with self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            params = (
                scope_key,
                normalized_period["startDate"],
                normalized_period["endDate"],
                normalized_period["basis"],
                normalized_period["timezone"],
                DEFINITION_VERSION,
                fingerprint.strip(),
            )
            existing = db.execute(
                """SELECT * FROM period_reports
                   WHERE scope_key=? AND start_date=? AND end_date=? AND basis=? AND timezone=?
                     AND definition_version=? AND fingerprint=?
                   ORDER BY revision DESC LIMIT 1""",
                params,
            ).fetchone()
            if existing is not None and not force:
                db.execute("COMMIT")
                return self._public(existing, from_cache=True)
            revision = int(
                db.execute(
                    """SELECT COALESCE(MAX(revision), 0) FROM period_reports
                       WHERE scope_key=? AND start_date=? AND end_date=? AND basis=? AND timezone=?
                         AND definition_version=?""",
                    params[:5] + (DEFINITION_VERSION,),
                ).fetchone()[0]
            ) + 1
            now = _stamp()
            job_id = uuid.uuid4().hex
            db.execute(
                """INSERT INTO period_reports (
                    id, scope_key, tenant_id, source_factory_id, connector,
                    start_date, end_date, basis, timezone, period_json,
                    definition_version, fingerprint, revision, status,
                    created_at, trigger_name, actor, facts_json
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (
                    job_id, scope_key, normalized_scope["tenant_id"], normalized_scope["source_factory_id"], normalized_scope["connector"],
                    normalized_period["startDate"], normalized_period["endDate"], normalized_period["basis"], normalized_period["timezone"], _json(normalized_period),
                    DEFINITION_VERSION, fingerprint.strip(), revision, "queued", now,
                    str(trigger or "manual")[:80], str(actor)[:160] if actor is not None else None, _json(facts),
                ),
            )
            row = db.execute("SELECT * FROM period_reports WHERE id=?", (job_id,)).fetchone()
            db.execute("COMMIT")
            return self._public(row)

    def get(self, scope: Mapping[str, Any], job_id: str) -> dict[str, Any] | None:
        _, scope_key = _scope(scope)
        self._ensure()
        with self._connect() as db:
            row = db.execute("SELECT * FROM period_reports WHERE id=? AND scope_key=?", (job_id, scope_key)).fetchone()
            return self._public(row) if row else None

    def list(self, scope: Mapping[str, Any], limit: int = 50) -> list[dict[str, Any]]:
        _, scope_key = _scope(scope)
        limit = max(1, min(int(limit), 500))
        self._ensure()
        with self._connect() as db:
            rows = db.execute("SELECT * FROM period_reports WHERE scope_key=? ORDER BY created_at DESC, revision DESC LIMIT ?", (scope_key, limit)).fetchall()
            return [self._public(row) for row in rows]

    def windows(self, scope: Mapping[str, Any], offset: int = 0, limit: int = 50) -> list[dict[str, Any]]:
        """List the latest revision for each saved scope/window, stably paged."""
        _, scope_key = _scope(scope)
        offset = max(0, int(offset))
        limit = max(1, min(int(limit), 100))
        self._ensure()
        with self._connect() as db:
            rows = db.execute(
                """SELECT * FROM (
                       SELECT p.*, ROW_NUMBER() OVER (
                           PARTITION BY start_date, end_date, basis, timezone, definition_version
                           ORDER BY revision DESC, created_at DESC, id DESC
                       ) AS row_number
                         FROM period_reports p
                        WHERE scope_key=?
                   ) latest
                   WHERE row_number=1
                   ORDER BY start_date, end_date, basis, timezone, definition_version
                   LIMIT ? OFFSET ?""",
                (scope_key, limit, offset),
            ).fetchall()
            return [self._public(row) for row in rows]

    def history(self, scope: Mapping[str, Any], current: Mapping[str, Any], limit: int = 100) -> list[dict[str, Any]]:
        """Latest ready predecessor per relevant window, as known at submission time."""
        _, scope_key = _scope(scope)
        self._ensure()
        period = current["period"]
        previous_end = (date.fromisoformat(period["startDate"]) - timedelta(days=1)).isoformat()
        same = (period["startDate"], period["endDate"])
        with self._connect() as db:
            rows = db.execute(
                """SELECT * FROM (
                     SELECT p.*, ROW_NUMBER() OVER (
                       PARTITION BY start_date,end_date,basis,timezone,definition_version
                       ORDER BY revision DESC,created_at DESC,id DESC) AS row_number
                       FROM period_reports p
                      WHERE scope_key=? AND status='ready' AND id<>? AND definition_version=?
                        AND basis=? AND timezone=?
                        AND (created_at<? OR (created_at=? AND start_date=? AND end_date=? AND revision<?))
                        AND ((start_date>=? AND end_date<=?) OR end_date=?)
                        AND NOT (start_date=? AND end_date=? AND revision>=?)
                   ) relevant WHERE row_number=1
                   ORDER BY (start_date=? AND end_date=?) DESC,end_date DESC,start_date ASC
                   LIMIT ?""",
                (scope_key,current["id"],current.get("definitionVersion",DEFINITION_VERSION),
                 period["basis"],period["timezone"],current["createdAt"],current["createdAt"],
                 *same,current["revision"],*same,previous_end,*same,current["revision"],
                 *same,max(1,min(int(limit),500))),
            ).fetchall()
            return [self._public(row) for row in rows]

    def claim(self, scope: Mapping[str, Any], job_id: str | None = None, lease_seconds: int = 600) -> dict[str, Any] | None:
        _, scope_key = _scope(scope)
        lease_seconds = max(1, int(lease_seconds))
        self._ensure()
        now = _utc_now().timestamp()
        with self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute(
                """UPDATE period_reports SET status='failed',finished_at=?,error_text=?,
                       lease_token=NULL,lease_expires=NULL,next_attempt_at=NULL
                     WHERE scope_key=? AND definition_version<>? AND status IN ('queued','running')""",
                (_stamp(),'REPORT_DEFINITION_SUPERSEDED: 旧口径未完成任务已停止；原记录保留，当前口径将另生成报告。',scope_key,DEFINITION_VERSION),
            )
            expired_error = "LEASE_EXPIRED_MAX_ATTEMPTS: lease expired after maximum attempts"
            db.execute(
                """UPDATE period_reports
                      SET status='failed', finished_at=?, error_text=?, lease_token=NULL, lease_expires=NULL
                    WHERE scope_key=? AND status='running' AND attempts>=3
                      AND lease_expires IS NOT NULL AND lease_expires<=?""",
                (_stamp(), expired_error, scope_key, now),
            )
            query = """SELECT * FROM period_reports
                       WHERE scope_key=? AND definition_version=? AND attempts < 3
                         AND ((status='queued' AND (next_attempt_at IS NULL OR next_attempt_at<=?))
                              OR (status='running' AND lease_expires IS NOT NULL AND lease_expires<=?))"""
            args: list[Any] = [scope_key, DEFINITION_VERSION, now, now]
            if job_id is not None:
                query += " AND id=?"
                args.append(job_id)
            query += " ORDER BY created_at ASC, revision ASC LIMIT 1"
            row = db.execute(query, args).fetchone()
            if row is None:
                db.execute("COMMIT")
                return None
            token = uuid.uuid4().hex
            db.execute(
                "UPDATE period_reports SET status='running', attempts=attempts+1, lease_token=?, lease_expires=?, next_attempt_at=NULL WHERE id=?",
                (token, now + lease_seconds, row["id"]),
            )
            claimed = db.execute("SELECT * FROM period_reports WHERE id=?", (row["id"],)).fetchone()
            db.execute("COMMIT")
            result = self._public(claimed)
            result.update({"facts": _load(claimed["facts_json"]), "leaseToken": token, "attempts": int(claimed['attempts'])})
            return result

    def _lease_row(self, db: sqlite3.Connection, scope_key: str, job_id: str, lease_token: str) -> sqlite3.Row:
        row = db.execute("SELECT * FROM period_reports WHERE id=? AND scope_key=?", (job_id, scope_key)).fetchone()
        if row is None or row["status"] != "running" or row["lease_token"] != lease_token or row["lease_expires"] is None or row["lease_expires"] <= _utc_now().timestamp():
            raise LeaseError("lease is missing, expired, or no longer owned")
        return row

    def complete(self, scope: Mapping[str, Any], job_id: str, lease_token: str, result: Any) -> dict[str, Any]:
        _, scope_key = _scope(scope)
        self._ensure()
        with self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = self._lease_row(db, scope_key, job_id, lease_token)
            db.execute("UPDATE period_reports SET status='ready', finished_at=?, result_json=?, error_text=NULL, lease_token=NULL, lease_expires=NULL WHERE id=?", (_stamp(), _json(result), job_id))
            updated = db.execute("SELECT * FROM period_reports WHERE id=?", (job_id,)).fetchone()
            db.execute("COMMIT")
            return self._public(updated)

    def assert_lease(self, scope: Mapping[str, Any], job_id: str, lease_token: str) -> None:
        """Protect per-batch file checkpoints with the same durable queue ownership."""
        _,scope_key=_scope(scope)
        with self._connect() as db:self._lease_row(db,scope_key,job_id,lease_token)

    def save_progress(self, scope, job_id, lease_token, result):
        _,scope_key=_scope(scope)
        with self._connect() as db:
            db.execute('BEGIN IMMEDIATE')
            self._lease_row(db,scope_key,job_id,lease_token)
            db.execute('UPDATE period_reports SET result_json=? WHERE id=?',(_json(result),job_id))
            db.execute('COMMIT')

    def has_due(self, scope: Mapping[str, Any]) -> bool:
        _,scope_key=_scope(scope);self._ensure();now=_utc_now().timestamp()
        with self._connect() as db:
            return db.execute("""SELECT 1 FROM period_reports WHERE scope_key=?
                AND ((status='queued' AND (definition_version<>? OR next_attempt_at IS NULL OR next_attempt_at<=?))
                     OR (status='running' AND lease_expires<=?)) LIMIT 1""",
                (scope_key,DEFINITION_VERSION,now,now)).fetchone() is not None

    def defer(self, scope: Mapping[str, Any], job_id: str, lease_token: str, result: Any,
              retry_seconds: int = 60, refund_attempt: bool = True) -> dict[str, Any]:
        """Persist useful rule results while backpressure delays optional AI work."""
        _, scope_key = _scope(scope)
        delay=max(1,min(int(retry_seconds),86400))
        self._ensure()
        with self._connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row=self._lease_row(db,scope_key,job_id,lease_token)
            attempts=max(0,int(row['attempts'])-1) if refund_attempt else int(row['attempts'])
            db.execute("""UPDATE period_reports SET status='queued',result_json=?,finished_at=NULL,error_text=NULL,
                          attempts=?,next_attempt_at=?,lease_token=NULL,lease_expires=NULL WHERE id=?""",
                       (_json(result),attempts,_utc_now().timestamp()+delay,job_id))
            updated=db.execute('SELECT * FROM period_reports WHERE id=?',(job_id,)).fetchone()
            db.execute('COMMIT')
            return self._public(updated)

    def resume_ai_queue(self, scope: Mapping[str, Any], analysis_profile: str | None = None) -> list[str]:
        """Explicitly resume old quota/busy drafts; never alter ready or leased jobs."""
        _,scope_key=_scope(scope)
        self._ensure()
        with self._connect() as db:
            db.execute('BEGIN IMMEDIATE')
            rows=db.execute("SELECT id,result_json FROM period_reports WHERE scope_key=? AND definition_version=? AND status='queued' AND lease_token IS NULL",(scope_key,DEFINITION_VERSION)).fetchall()
            ids=[]
            for row in rows:
                ai=(_load(row['result_json']) or {}).get('ai',{})
                if (ai.get('status') in ('rate_limited','busy') or
                        (analysis_profile and ai.get('status')=='unavailable' and ai.get('analysisProfileVersion')
                         and ai['analysisProfileVersion']!=analysis_profile)):
                    ids.append(row['id'])
            db.executemany('UPDATE period_reports SET next_attempt_at=NULL WHERE id=? AND scope_key=? AND status=\'queued\' AND lease_token IS NULL',[(job_id,scope_key) for job_id in ids])
            db.execute('COMMIT')
            return ids

    def fail(self, scope: Mapping[str, Any], job_id: str, lease_token: str, error: Any) -> dict[str, Any]:
        _, scope_key = _scope(scope)
        self._ensure()
        safe_error = _safe_error(error)
        with self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = self._lease_row(db, scope_key, job_id, lease_token)
            attempts = int(row["attempts"])
            final = attempts >= 3
            now = _utc_now()
            retry_at = now.timestamp() + (60 if attempts == 1 else 120)
            db.execute(
                "UPDATE period_reports SET status=?, finished_at=?, error_text=?, lease_token=NULL, lease_expires=NULL, next_attempt_at=? WHERE id=?",
                ("failed" if final else "queued", _stamp(now) if final else None, safe_error, None if final else retry_at, job_id),
            )
            updated = db.execute("SELECT * FROM period_reports WHERE id=?", (job_id,)).fetchone()
            db.execute("COMMIT")
            return self._public(updated)

    def schedule_seen(self, scope: Mapping[str, Any], key: str) -> bool:
        _, scope_key = _scope(scope)
        self._ensure()
        with self._connect() as db:
            return db.execute("SELECT 1 FROM period_schedule_seen WHERE scope_key=? AND schedule_key=?", (scope_key, key)).fetchone() is not None

    def mark_schedule(self, scope: Mapping[str, Any], key: str, job_id: str) -> bool:
        _, scope_key = _scope(scope)
        self._ensure()
        with self._connect() as db:
            row = db.execute("SELECT 1 FROM period_reports WHERE id=? AND scope_key=?", (job_id, scope_key)).fetchone()
            if row is None:
                raise ValueError("job does not belong to scope")
            db.execute(
                "INSERT INTO period_schedule_seen(scope_key,schedule_key,job_id,marked_at) VALUES(?,?,?,?) ON CONFLICT(scope_key,schedule_key) DO UPDATE SET job_id=excluded.job_id, marked_at=excluded.marked_at",
                (scope_key, key, job_id, _stamp()),
            )
            return True

    def schedule_checkpoint(self, scope: Mapping[str, Any], at: datetime | None = None) -> str | None:
        """Read or monotonically advance the successful scheduler cursor for a scope."""
        _, scope_key = _scope(scope)
        if at is not None:
            if at.tzinfo is None or at.utcoffset() is None:
                raise ValueError("checkpoint time must be timezone-aware")
            target = at.astimezone(timezone.utc)
        else:
            target = None
        self._ensure()
        with self._connect() as db:
            if target is None:
                row = db.execute("SELECT last_tick FROM period_schedule_checkpoints WHERE scope_key=?", (scope_key,)).fetchone()
                return row["last_tick"] if row else None
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT last_tick FROM period_schedule_checkpoints WHERE scope_key=?", (scope_key,)).fetchone()
            target_stamp = _stamp(target)
            if row is None:
                db.execute("INSERT INTO period_schedule_checkpoints(scope_key,last_tick) VALUES(?,?)", (scope_key, target_stamp))
                result = target_stamp
            elif target_stamp > row["last_tick"]:
                db.execute("UPDATE period_schedule_checkpoints SET last_tick=? WHERE scope_key=?", (target_stamp, scope_key))
                result = target_stamp
            else:
                result = row["last_tick"]
            db.execute("COMMIT")
            return result
