"""Injectable draft stores for tests and local planning.

No database is required by the SOP layer.  The file store is deliberately
limited to drafts and receipts created by this coordinator and uses atomic
replacement for readback consistency.
"""

from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path
from threading import RLock
from typing import Protocol

from .models import TrainingProjectDraft


class DraftStore(Protocol):
    def get(self, project_id: str) -> TrainingProjectDraft | None: ...

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

    def _path(self, project_id: str) -> Path:
        if not project_id or any(char not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_" for char in project_id):
            raise ValueError("invalid project id")
        return self.root / f"{project_id}.json"

    def get(self, project_id: str) -> TrainingProjectDraft | None:
        path = self._path(project_id)
        with self._lock:
            if not path.is_file():
                return None
            return TrainingProjectDraft.model_validate(json.loads(path.read_text(encoding="utf-8")))

    def put(self, draft: TrainingProjectDraft) -> TrainingProjectDraft:
        path = self._path(draft.project_id)
        payload = json.dumps(draft.model_dump(mode="json"), ensure_ascii=False, indent=2)
        with self._lock:
            self._ensure_root()
            existing = self.get(draft.project_id)
            if existing is not None and (
                existing.spec != draft.spec or existing.template != draft.template
            ):
                raise ValueError(f"draft identity is immutable: {draft.project_id}")
            fd, temporary = tempfile.mkstemp(prefix=f"{draft.project_id}.", suffix=".tmp", dir=self.root)
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as handle:
                    handle.write(payload)
                    handle.flush()
                    os.fsync(handle.fileno())
                os.replace(temporary, path)
            finally:
                if os.path.exists(temporary):
                    os.unlink(temporary)
        return draft

    def create_if_absent(self, draft: TrainingProjectDraft) -> TrainingProjectDraft:
        path = self._path(draft.project_id)
        payload = json.dumps(draft.model_dump(mode="json"), ensure_ascii=False, indent=2)
        with self._lock:
            self._ensure_root()
            temporary_path: str | None = None
            fd, temporary_path = tempfile.mkstemp(
                prefix=f"{draft.project_id}.", suffix=".create", dir=self.root
            )
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as handle:
                    handle.write(payload)
                    handle.flush()
                    os.fsync(handle.fileno())
                try:
                    # A hard-link publish is create-if-absent on the same
                    # filesystem: exactly one process can win the link.
                    os.link(temporary_path, path)
                except FileExistsError:
                    existing = self.get(draft.project_id)
                    if existing is None:
                        raise RuntimeError("draft appeared but could not be read")
                    if _same_identity(existing, draft):
                        return existing
                    raise ValueError(f"draft identity is immutable: {draft.project_id}")
                return draft
            finally:
                if temporary_path and os.path.exists(temporary_path):
                    os.unlink(temporary_path)
