"""Injectable draft stores for tests and local planning.

No database is required by the SOP layer.  The file store is deliberately
limited to drafts and receipts created by this coordinator and uses atomic
replacement for readback consistency.
"""

from __future__ import annotations

import json
import os
import tempfile
import sqlite3
from contextlib import contextmanager
from pathlib import Path
from threading import RLock
from typing import Protocol

from .models import TrainingProjectDraft

MAX_FACTORY_PROJECTS = 50
MAX_GLOBAL_PROJECTS = 1000


class CapacityLimitError(ValueError):
    """A user-visible capacity rejection that must survive service wrapping."""


class DraftStore(Protocol):
    def get(self, project_id: str) -> TrainingProjectDraft | None: ...

    def list(self, factory_id: str) -> tuple[TrainingProjectDraft, ...]: ...

    def create_if_absent(self, draft: TrainingProjectDraft) -> TrainingProjectDraft: ...

    def put(self, draft: TrainingProjectDraft) -> TrainingProjectDraft: ...


def _same_identity(left: TrainingProjectDraft, right: TrainingProjectDraft) -> bool:
    return (
        left.project_id == right.project_id
        and left.preview_hash == right.preview_hash
        and left.sop_version == right.sop_version
        and left.spec == right.spec
        and left.template == right.template
    )


class InMemoryDraftStore:
    def __init__(self) -> None:
        self._items: dict[str, TrainingProjectDraft] = {}
        self._lock = RLock()

    def get(self, project_id: str) -> TrainingProjectDraft | None:
        with self._lock:
            return self._items.get(project_id)

    def list(self, factory_id: str) -> tuple[TrainingProjectDraft, ...]:
        with self._lock:
            return tuple(item for item in self._items.values() if item.spec.factory_id == factory_id)

    def put(self, draft: TrainingProjectDraft) -> TrainingProjectDraft:
        with self._lock:
            existing = self._items.get(draft.project_id)
            if existing is not None and (
                existing.spec != draft.spec or existing.template != draft.template
            ):
                raise ValueError(f"draft identity is immutable: {draft.project_id}")
            self._items[draft.project_id] = draft
            return draft

    def create_if_absent(self, draft: TrainingProjectDraft) -> TrainingProjectDraft:
        with self._lock:
            existing = self._items.get(draft.project_id)
            if existing is not None:
                if _same_identity(existing, draft):
                    return existing
                raise ValueError(f"draft identity is immutable: {draft.project_id}")
            self._items[draft.project_id] = draft
            return draft


class FileDraftStore:
    """A small file-backed store suitable for a local worker or unit test."""

    def __init__(self, root: str | Path) -> None:
        self.root = Path(root)
        self._lock = RLock()

    def _ensure_root(self) -> None:
        self.root.mkdir(parents=True, exist_ok=True)

    @contextmanager
    def _capacity(self):
        self._ensure_root()
        db = sqlite3.connect(self.root / ".capacity.sqlite", timeout=15)
        try:
            db.execute("BEGIN IMMEDIATE")
            db.execute("CREATE TABLE IF NOT EXISTS projects (project_id TEXT PRIMARY KEY, factory_id TEXT NOT NULL)")
            yield db
            db.commit()
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    def _sync_capacity(self, db) -> None:
        for path in self.root.glob("*.json"):
            try:
                draft = TrainingProjectDraft.model_validate(json.loads(path.read_text(encoding="utf-8")))
            except (OSError, ValueError):
                continue
            db.execute("INSERT OR REPLACE INTO projects(project_id,factory_id) VALUES(?,?)",
                       (draft.project_id, draft.spec.factory_id))
        known = {path.stem for path in self.root.glob("*.json")}
        for (project_id,) in db.execute("SELECT project_id FROM projects").fetchall():
            if project_id not in known:
                db.execute("DELETE FROM projects WHERE project_id=?", (project_id,))

    def _check_project_capacity(self, db, draft) -> None:
        self._sync_capacity(db)
        if db.execute("SELECT 1 FROM projects WHERE project_id=?", (draft.project_id,)).fetchone():
            return
        factory_count = db.execute("SELECT COUNT(*) FROM projects WHERE factory_id=?", (draft.spec.factory_id,)).fetchone()[0]
        total_count = db.execute("SELECT COUNT(*) FROM projects").fetchone()[0]
        if factory_count >= MAX_FACTORY_PROJECTS:
            raise CapacityLimitError("本厂检查项目已达五十个上限，请联系管理员归档或核验容量后再新增。")
        if total_count >= MAX_GLOBAL_PROJECTS:
            raise CapacityLimitError("系统检查项目已达一千个上限，请联系管理员归档或核验容量后再新增。")

    @staticmethod
    def _read(path):
        if not path.is_file():
            return None
        return TrainingProjectDraft.model_validate(json.loads(path.read_text(encoding="utf-8")))

    def _path(self, project_id: str) -> Path:
        if not project_id or any(char not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_" for char in project_id):
            raise ValueError("invalid project id")
        return self.root / f"{project_id}.json"

    def get(self, project_id: str) -> TrainingProjectDraft | None:
        path = self._path(project_id)
        with self._lock:
            return self._read(path)

    def list(self, factory_id: str) -> tuple[TrainingProjectDraft, ...]:
        with self._lock:
            self._ensure_root()
            items: list[TrainingProjectDraft] = []
            for path in sorted(self.root.glob("*.json")):
                try:
                    draft = TrainingProjectDraft.model_validate(json.loads(path.read_text(encoding="utf-8")))
                except (OSError, ValueError):
                    continue
                if draft.spec.factory_id == factory_id:
                    items.append(draft)
            return tuple(items)

    def put(self, draft: TrainingProjectDraft) -> TrainingProjectDraft:
        path = self._path(draft.project_id)
        payload = json.dumps(draft.model_dump(mode="json"), ensure_ascii=False, indent=2)
        with self._lock, self._capacity() as db:
            existing = self._read(path)
            if existing is not None and (
                existing.spec != draft.spec or existing.template != draft.template
            ):
                raise ValueError(f"draft identity is immutable: {draft.project_id}")
            if existing is None:
                self._check_project_capacity(db, draft)
            fd, temporary = tempfile.mkstemp(prefix=f"{draft.project_id}.", suffix=".tmp", dir=self.root)
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as handle:
                    handle.write(payload)
                    handle.flush()
                    os.fsync(handle.fileno())
                os.replace(temporary, path)
                if existing is None:
                    db.execute("INSERT INTO projects(project_id,factory_id) VALUES(?,?)",
                               (draft.project_id, draft.spec.factory_id))
            finally:
                if os.path.exists(temporary):
                    os.unlink(temporary)
        return draft

    def create_if_absent(self, draft: TrainingProjectDraft) -> TrainingProjectDraft:
        path = self._path(draft.project_id)
        payload = json.dumps(draft.model_dump(mode="json"), ensure_ascii=False, indent=2)
        with self._lock, self._capacity() as db:
            temporary_path: str | None = None
            fd, temporary_path = tempfile.mkstemp(
                prefix=f"{draft.project_id}.", suffix=".create", dir=self.root
            )
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as handle:
                    handle.write(payload)
                    handle.flush()
                    os.fsync(handle.fileno())
                self._check_project_capacity(db, draft)
                try:
                    # A hard-link publish is create-if-absent on the same
                    # filesystem: exactly one process can win the link.
                    os.link(temporary_path, path)
                except FileExistsError:
                    existing = self._read(path)
                    if existing is None:
                        raise RuntimeError("draft appeared but could not be read")
                    if _same_identity(existing, draft):
                        return existing
                    raise ValueError(f"draft identity is immutable: {draft.project_id}")
                db.execute("INSERT INTO projects(project_id,factory_id) VALUES(?,?)",
                           (draft.project_id, draft.spec.factory_id))
                return draft
            finally:
                if temporary_path and os.path.exists(temporary_path):
                    os.unlink(temporary_path)
