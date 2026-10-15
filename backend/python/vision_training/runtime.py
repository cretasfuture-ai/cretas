"""Durable state belongs outside release-managed source directories."""
from __future__ import annotations

import os
from pathlib import Path
import tempfile
from uuid import uuid4


def resolve_draft_root(configured: str | None = None) -> Path:
    default = Path.home() / ".cretas-state" / "vision-training" if os.name == "nt" else Path("/var/lib/cretas/vision-training")
    root = Path(configured) if configured is not None else default
    if not root.is_absolute():
        raise ValueError("VISION_TRAINING_DRAFT_DIR must be an absolute persistent directory")
    root = root.resolve()
    source_root = Path(__file__).resolve().parents[3]
    if root == source_root or source_root in root.parents:
        raise ValueError("vision training drafts must be outside the release-managed source tree")
    return root


def verify_draft_storage(root: Path) -> None:
    """Fail startup if the persistent volume cannot safely replace a file."""
    root.mkdir(parents=True, exist_ok=True)
    paths: list[Path] = []
    try:
        for _ in range(2):
            descriptor, name = tempfile.mkstemp(prefix=".startup-probe-", dir=root)
            paths.append(Path(name))
            with os.fdopen(descriptor, "wb") as handle:
                handle.write(b"vision-training-state-probe")
                handle.flush()
                os.fsync(handle.fileno())
        os.replace(paths[0], paths[1])
        if paths[1].read_bytes() != b"vision-training-state-probe":
            raise RuntimeError("vision training state readback failed")
        # Atomic draft creation uses hard-link publication across workers.
        link = root / f".startup-probe-{uuid4().hex}.link"
        paths.append(link)
        os.link(paths[1], link)
        if link.read_bytes() != b"vision-training-state-probe":
            raise RuntimeError("vision training atomic creation readback failed")
    finally:
        for path in paths:
            path.unlink(missing_ok=True)
