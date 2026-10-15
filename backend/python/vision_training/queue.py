"""Persistent, factory-scoped execution queue for the vision SOP.

The API owns this queue.  A Windows worker may claim one leased job and publish
one immutable receipt, but it cannot create a new identity or retry an expired
execution.  SQLite is intentionally used here as a small durable coordination
store; production hosts may replace this class with an equivalent transactional
store without changing the contracts.
"""

from __future__ import annotations

import hashlib
import json
import sqlite3
from datetime import datetime, timedelta
from pathlib import Path
from threading import RLock
from typing import Iterable

from .errors import InvalidTaskSpecError, ScopeViolationError
from .models import (
    AssetReceipt,
    JobIdentity,
    JobReceipt,
    JobStatus,
    ReceiptStatus,
    StageName,
    StageReceipt,
    TrainingProjectDraft,
    VisionJob,
    utc_now,
)


class QueueConflictError(ValueError):
    """A project/stage already has a different immutable execution identity."""


class QueueLeaseError(ValueError):
    """A worker attempted to use a missing, expired, or foreign lease."""


NO_NAMED_RUNNER_REASON = "NO_NAMED_RUNNER_REGISTERED_FOR_TEMPLATE"


def _canonical(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def receipt_hash(receipt: JobReceipt) -> str:
    payload = receipt.model_dump(mode="json")
    payload["receipt_hash"] = ""
    return hashlib.sha256(_canonical(payload).encode("utf-8")).hexdigest()


def build_identity(
    draft: TrainingProjectDraft,
    *,
    config_hash: str,
    input_hash: str,
    source_asset_sha256: str | None = None,
) -> JobIdentity:
    return JobIdentity(
        factory_id=draft.spec.factory_id,
        project_id=draft.project_id,
        task_family=draft.spec.task_family,
        template_id=draft.template.template_id,
        template_version=draft.template.version,
        sop_version=draft.sop_version,
        data_version=draft.spec.data_version,
        recipe_version=draft.spec.recipe_version,
        human_standard_version=draft.spec.human_standard_version,
        independent_acceptance_version=draft.spec.independent_acceptance_version,
        recipe_id=draft.spec.recipe_id,
        ocr_rules_version=draft.spec.ocr_rules_version,
        preview_hash=draft.preview_hash,
        config_hash=config_hash,
        input_hash=input_hash,
        source_asset_sha256=source_asset_sha256,
    )


class VisionJobStore:
    """SQLite-backed atomic enqueue/claim/complete store."""

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        self._lock = RLock()
        self.db: sqlite3.Connection | None = None

    def _ensure_initialized(self) -> sqlite3.Connection:
        """Open and initialize storage only when an operation needs it."""
        with self._lock:
            if self.db is not None:
                return self.db
            self.path.parent.mkdir(parents=True, exist_ok=True)
            connection: sqlite3.Connection | None = None
            try:
                connection = sqlite3.connect(
                    self.path, timeout=15, isolation_level=None, check_same_thread=False
                )
                connection.row_factory = sqlite3.Row
                connection.execute("PRAGMA journal_mode=WAL")
                connection.execute("PRAGMA busy_timeout=15000")
                connection.executescript(
                    """
                    CREATE TABLE IF NOT EXISTS vision_jobs (
                      job_id TEXT PRIMARY KEY,
                      identity_key TEXT NOT NULL UNIQUE,
                      project_id TEXT NOT NULL,
                      factory_id TEXT NOT NULL,
                      stage TEXT NOT NULL,
                      adapter_id TEXT,
                      identity_json TEXT NOT NULL,
                      status TEXT NOT NULL,
                      attempts INTEGER NOT NULL DEFAULT 0,
                      lease_owner TEXT,
                      lease_expires_at TEXT,
                      completed_by TEXT,
                      blocked_reason TEXT,
                      receipt_json TEXT,
                      created_at TEXT NOT NULL,
                      updated_at TEXT NOT NULL
                    );
                    CREATE UNIQUE INDEX IF NOT EXISTS uq_vision_job_project_stage
                      ON vision_jobs(project_id, stage);
                    CREATE INDEX IF NOT EXISTS idx_vision_job_claim
                      ON vision_jobs(status, factory_id, created_at);
                    """
                )
                try:
                    connection.execute("ALTER TABLE vision_jobs ADD COLUMN completed_by TEXT")
                except sqlite3.OperationalError as exc:
                    if "duplicate column name" not in str(exc).lower():
                        raise
                self.db = connection
                return connection
            except Exception:
                if connection is not None:
                    connection.close()
                raise

    def close(self) -> None:
        with self._lock:
            if self.db is None:
                return
            self.db.close()
            self.db = None

    def probe(self) -> None:
        with self._lock:
            self._ensure_initialized().execute("SELECT 1").fetchone()

    @staticmethod
    def _identity_key(identity: JobIdentity, stage: StageName, adapter_id: str | None) -> str:
        payload = {"identity": identity.model_dump(mode="json"), "stage": stage.value, "adapter_id": adapter_id}
        return hashlib.sha256(_canonical(payload).encode("utf-8")).hexdigest()

    @staticmethod
    def _parse_time(value: str | None) -> datetime | None:
        return datetime.fromisoformat(value) if value else None

    def _row_to_job(self, row: sqlite3.Row) -> VisionJob:
        receipt = JobReceipt.model_validate(json.loads(row["receipt_json"])) if row["receipt_json"] else None
        return VisionJob(
            job_id=row["job_id"],
            identity=JobIdentity.model_validate(json.loads(row["identity_json"])),
            stage=StageName(row["stage"]),
            adapter_id=row["adapter_id"],
            status=JobStatus(row["status"]),
            attempts=int(row["attempts"]),
            lease_owner=row["lease_owner"],
            lease_expires_at=self._parse_time(row["lease_expires_at"]),
            blocked_reason=row["blocked_reason"],
            receipt=receipt,
            created_at=datetime.fromisoformat(row["created_at"]),
            updated_at=datetime.fromisoformat(row["updated_at"]),
        )

    def get(self, job_id: str) -> VisionJob | None:
        with self._lock:
            db = self._ensure_initialized()
            row = db.execute("SELECT * FROM vision_jobs WHERE job_id=?", (job_id,)).fetchone()
            return self._row_to_job(row) if row else None

    def list_for_project(self, project_id: str, *, factory_id: str | None = None) -> tuple[VisionJob, ...]:
        with self._lock:
            db = self._ensure_initialized()
            if factory_id is None:
                rows = db.execute(
                    "SELECT * FROM vision_jobs WHERE project_id=? ORDER BY created_at, stage",
                    (project_id,),
                ).fetchall()
            else:
                rows = db.execute(
                    "SELECT * FROM vision_jobs WHERE project_id=? AND factory_id=? ORDER BY created_at, stage",
                    (project_id, factory_id),
                ).fetchall()
            return tuple(self._row_to_job(row) for row in rows)

    def enqueue(
        self,
        draft: TrainingProjectDraft,
        *,
        stage: StageName,
        config_hash: str,
        input_hash: str,
        source_asset_sha256: str | None = None,
        adapter_id: str | None = None,
    ) -> VisionJob:
        if stage in {StageName.PREVIEW, StageName.INDEPENDENT_ACCEPTANCE, StageName.ENABLE}:
            raise InvalidTaskSpecError(f"stage {stage.value} cannot be dispatched to the local worker")
        identity = build_identity(
            draft,
            config_hash=config_hash,
            input_hash=input_hash,
            source_asset_sha256=source_asset_sha256,
        )
        adapter_id = adapter_id if adapter_id is not None else draft.template.adapter_id
        dependencies = {
            StageName.TRAIN: StageName.DATASET,
            StageName.EVALUATE: StageName.TRAIN,
        }
        identity_key = self._identity_key(identity, stage, adapter_id)
        now = utc_now()
        status = JobStatus.QUEUED if adapter_id else JobStatus.BLOCKED
        reason = None if adapter_id else NO_NAMED_RUNNER_REASON
        job_id = "vjob-" + identity_key[:32]
        payload = (
            job_id,
            identity_key,
            identity.project_id,
            identity.factory_id,
            stage.value,
            adapter_id,
            _canonical(identity.model_dump(mode="json")),
            status.value,
            reason,
            now.isoformat(),
            now.isoformat(),
        )
        with self._lock:
            db = self._ensure_initialized()
            db.execute("BEGIN IMMEDIATE")
            try:
                existing = db.execute(
                    "SELECT * FROM vision_jobs WHERE project_id=? AND stage=?",
                    (identity.project_id, stage.value),
                ).fetchone()
                if existing:
                    if existing["identity_key"] != identity_key:
                        raise QueueConflictError("project/stage already has a different immutable job identity")
                    db.execute("COMMIT")
                    return self._row_to_job(existing)
                dependency = dependencies.get(stage)
                if dependency is not None and adapter_id is not None:
                    dependency_row = db.execute(
                        "SELECT * FROM vision_jobs WHERE project_id=? AND factory_id=? AND stage=?",
                        (identity.project_id, identity.factory_id, dependency.value),
                    ).fetchone()
                    if dependency_row is None or dependency_row["status"] != JobStatus.SUCCEEDED.value:
                        raise QueueConflictError(
                            f"stage {stage.value} requires a succeeded {dependency.value} job for this project"
                        )
                    dependency_job = self._row_to_job(dependency_row)
                    dependency_kind = "dataset" if dependency == StageName.DATASET else "model"
                    identity_fields = (
                        "factory_id", "project_id", "task_family", "template_id", "template_version",
                        "sop_version", "data_version", "recipe_version", "human_standard_version",
                        "independent_acceptance_version", "recipe_id", "ocr_rules_version", "preview_hash",
                    )
                    if any(getattr(dependency_job.identity, field) != getattr(identity, field) for field in identity_fields):
                        raise QueueConflictError("stage dependency identity does not match this project/version")
                    if (
                        dependency_job.receipt is None
                        or len(dependency_job.receipt.asset_receipts) != 1
                        or dependency_job.receipt.asset_receipts[0].kind != dependency_kind
                        or dependency_job.receipt.asset_receipts[0].status != ReceiptStatus.SUCCEEDED
                    ):
                        raise QueueConflictError(
                            f"stage {stage.value} requires a version-bound {dependency_kind} asset receipt"
                        )
                    dependency_asset = dependency_job.receipt.asset_receipts[0]
                    if not identity.source_asset_sha256:
                        raise QueueConflictError(
                            f"stage {stage.value} requires an explicit source asset hash"
                        )
                    if identity.source_asset_sha256.lower() != dependency_asset.sha256.lower():
                        raise QueueConflictError(
                            f"stage {stage.value} source asset hash does not match the succeeded {dependency_kind} asset"
                        )
                db.execute(
                    """INSERT INTO vision_jobs
                       (job_id,identity_key,project_id,factory_id,stage,adapter_id,identity_json,status,
                        blocked_reason,created_at,updated_at)
                       VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                    payload,
                )
                db.execute("COMMIT")
            except Exception:
                db.execute("ROLLBACK")
                raise
        result = self.get(job_id)
        if result is None:  # pragma: no cover
            raise RuntimeError("queued job disappeared after commit")
        return result

    def _freeze_expired(self, now: datetime) -> None:
        self._ensure_initialized().execute(
            """UPDATE vision_jobs SET status=?, blocked_reason=?, lease_owner=NULL,
               lease_expires_at=NULL, updated_at=?
               WHERE status=? AND lease_expires_at IS NOT NULL AND lease_expires_at <= ?""",
            (
                JobStatus.FAILED.value,
                "worker lease expired; execution frozen and will not be retried automatically",
                now.isoformat(),
                JobStatus.RUNNING.value,
                now.isoformat(),
            ),
        )

    def claim_next(
        self,
        *,
        worker_id: str,
        allowed_factory_ids: Iterable[str],
        allowed_project_ids: Iterable[str] = (),
        lease_seconds: int = 900,
    ) -> VisionJob | None:
        if not worker_id.strip() or not 1 <= lease_seconds <= 86400:
            raise ValueError("invalid worker lease request")
        factories = tuple(sorted({value for value in allowed_factory_ids if value}))
        if not factories:
            return None
        now = utc_now()
        expires = now + timedelta(seconds=lease_seconds)
        with self._lock:
            db = self._ensure_initialized()
            db.execute("BEGIN IMMEDIATE")
            try:
                self._freeze_expired(now)
                placeholders = ",".join("?" for _ in factories)
                projects = tuple(sorted({value for value in allowed_project_ids if value}))
                project_clause = ""
                project_values: tuple[str, ...] = ()
                if projects:
                    project_placeholders = ",".join("?" for _ in projects)
                    project_clause = f" AND project_id IN ({project_placeholders})"
                    project_values = projects
                row = db.execute(
                    f"SELECT * FROM vision_jobs WHERE status=? AND factory_id IN ({placeholders}){project_clause} ORDER BY created_at LIMIT 1",
                    (JobStatus.QUEUED.value, *factories, *project_values),
                ).fetchone()
                if row is None:
                    db.execute("COMMIT")
                    return None
                db.execute(
                    """UPDATE vision_jobs SET status=?, attempts=attempts+1, lease_owner=?,
                       lease_expires_at=?, updated_at=? WHERE job_id=? AND status=?""",
                    (
                        JobStatus.RUNNING.value,
                        worker_id,
                        expires.isoformat(),
                        now.isoformat(),
                        row["job_id"],
                        JobStatus.QUEUED.value,
                    ),
                )
                db.execute("COMMIT")
            except Exception:
                db.execute("ROLLBACK")
                raise
        result = self.get(row["job_id"])
        if result is None:  # pragma: no cover
            raise RuntimeError("claimed job disappeared after commit")
        return result

    @staticmethod
    def _validate_receipt(job: VisionJob, receipt: JobReceipt) -> None:
        if receipt.job_id != job.job_id or receipt.identity != job.identity or receipt.stage != job.stage:
            raise ScopeViolationError("worker receipt identity does not match the queued job")
        if receipt.receipt_hash != receipt_hash(receipt):
            raise ScopeViolationError("worker receipt hash does not match its immutable contents")
        stage = receipt.stage_receipt
        if (
            stage.project_id != job.identity.project_id
            or stage.factory_id != job.identity.factory_id
            or stage.task_family != job.identity.task_family
            or stage.stage != job.stage
        ):
            raise ScopeViolationError("stage receipt crosses the queued job scope")
        if stage.production_writes != 0:
            raise ScopeViolationError("vision worker receipts cannot report production writes")
        if receipt.status not in {ReceiptStatus.SUCCEEDED, ReceiptStatus.FAILED}:
            raise ScopeViolationError("worker completion must be succeeded or failed")
        if receipt.status != stage.status:
            raise ScopeViolationError("job and stage receipt statuses must match")
        if job.stage == StageName.TRAIN and receipt.status == ReceiptStatus.SUCCEEDED and not stage.training_executed:
            raise InvalidTaskSpecError("successful training requires training_executed=true")
        expected_kind = {
            StageName.DATASET: "dataset",
            StageName.TRAIN: "model",
            StageName.EVALUATE: "evaluation",
        }.get(job.stage)
        if receipt.status == ReceiptStatus.SUCCEEDED:
            if expected_kind is None:
                if receipt.asset_receipts:
                    raise ScopeViolationError("non-artifact stages cannot publish assets")
            elif len(receipt.asset_receipts) != 1 or receipt.asset_receipts[0].kind != expected_kind:
                raise ScopeViolationError(f"successful {job.stage.value} requires exactly one {expected_kind} asset")
        elif any(asset.status == ReceiptStatus.SUCCEEDED for asset in receipt.asset_receipts):
            raise ScopeViolationError("failed completion cannot include a successful asset")
        for asset in receipt.asset_receipts:
            if (
                asset.factory_id != job.identity.factory_id
                or asset.task_id != job.identity.project_id
                or asset.task_family != job.identity.task_family
                or asset.data_version != job.identity.data_version
                or asset.recipe_version != job.identity.recipe_version
                or asset.human_standard_version != job.identity.human_standard_version
                or asset.production_writes != 0
            ):
                raise ScopeViolationError("asset receipt crosses the queued job version or factory scope")
            if asset.status != receipt.status:
                raise ScopeViolationError("asset receipt status does not match the completed job")
            if asset.acceptance_status != "pending" or asset.enabled:
                raise ScopeViolationError("worker cannot independently accept or enable an asset")
            if expected_kind is not None and asset.kind != expected_kind:
                raise ScopeViolationError("asset kind does not match the queued stage")
            if asset.kind == "dataset" and (
                not asset.reviewed_only or asset.unreviewed_count != 0 or not asset.protected_holdout_excluded
            ):
                raise ScopeViolationError("dataset success requires reviewed-only and protected-holdout exclusion evidence")
            if asset.kind == "model" and not asset.training_success:
                raise ScopeViolationError("model success requires training_success evidence")
            if asset.kind == "evaluation" and not asset.independent:
                raise ScopeViolationError("evaluation success requires independent evaluation evidence")

    def complete(self, *, job_id: str, worker_id: str, receipt: JobReceipt) -> VisionJob:
        now = utc_now()
        with self._lock:
            db = self._ensure_initialized()
            db.execute("BEGIN IMMEDIATE")
            try:
                row = db.execute("SELECT * FROM vision_jobs WHERE job_id=?", (job_id,)).fetchone()
                if row is None:
                    raise QueueLeaseError("job does not exist")
                job = self._row_to_job(row)
                if job.status in {JobStatus.SUCCEEDED, JobStatus.FAILED}:
                    if row["completed_by"] == worker_id and job.receipt is not None and job.receipt == receipt:
                        db.execute("COMMIT")
                        return job
                    raise QueueLeaseError("job is already completed with a different receipt")
                if job.status != JobStatus.RUNNING or job.lease_owner != worker_id:
                    raise QueueLeaseError("worker does not own the active job lease")
                if job.lease_expires_at is None or job.lease_expires_at <= now:
                    self._freeze_expired(now)
                    db.execute("COMMIT")
                    raise QueueLeaseError("worker lease expired; job was frozen")
                self._validate_receipt(job, receipt)
                new_status = JobStatus.SUCCEEDED if receipt.status == ReceiptStatus.SUCCEEDED else JobStatus.FAILED
                db.execute(
                    """UPDATE vision_jobs SET status=?, lease_owner=NULL, lease_expires_at=NULL, completed_by=?,
                       receipt_json=?, blocked_reason=NULL, updated_at=? WHERE job_id=? AND status=? AND lease_owner=?""",
                    (
                        new_status.value,
                        worker_id,
                        _canonical(receipt.model_dump(mode="json")),
                        now.isoformat(),
                        job_id,
                        JobStatus.RUNNING.value,
                        worker_id,
                    ),
                )
                db.execute("COMMIT")
            except Exception:
                if db.in_transaction:
                    db.execute("ROLLBACK")
                raise
        result = self.get(job_id)
        if result is None:  # pragma: no cover
            raise RuntimeError("completed job disappeared after commit")
        return result

    def heartbeat(self, *, job_id: str, worker_id: str, lease_seconds: int = 900) -> VisionJob:
        if not 1 <= lease_seconds <= 86400:
            raise ValueError("invalid worker lease request")
        now = utc_now()
        expires = now + timedelta(seconds=lease_seconds)
        with self._lock:
            db = self._ensure_initialized()
            db.execute("BEGIN IMMEDIATE")
            try:
                row = db.execute("SELECT * FROM vision_jobs WHERE job_id=?", (job_id,)).fetchone()
                if row is None:
                    raise QueueLeaseError("job does not exist")
                job = self._row_to_job(row)
                if job.status != JobStatus.RUNNING or job.lease_owner != worker_id:
                    raise QueueLeaseError("worker does not own the active job lease")
                if job.lease_expires_at is None or job.lease_expires_at <= now:
                    self._freeze_expired(now)
                    db.execute("COMMIT")
                    raise QueueLeaseError("worker lease expired; job was frozen")
                db.execute(
                    "UPDATE vision_jobs SET lease_expires_at=?, updated_at=? WHERE job_id=? AND status=? AND lease_owner=?",
                    (expires.isoformat(), now.isoformat(), job_id, JobStatus.RUNNING.value, worker_id),
                )
                db.execute("COMMIT")
            except Exception:
                if db.in_transaction:
                    db.execute("ROLLBACK")
                raise
        result = self.get(job_id)
        if result is None:  # pragma: no cover
            raise RuntimeError("heartbeat job disappeared")
        return result
