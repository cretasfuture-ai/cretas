"""Lazy, injectable runtime assembly for the vision-training routes.

Importing this module creates no files, opens no database, and registers no
factory or worker.  The host entry point calls ``create_file_backed_runtime``
after it has selected code-external persistence roots and trusted bindings.
"""

from __future__ import annotations

from collections.abc import Collection, Mapping
from dataclasses import dataclass
import os
from pathlib import Path
import tempfile
from uuid import uuid4

from fastapi import APIRouter

from .models import FactoryContext, RecipeAvailability
from .queue import VisionJobStore
from .router import (
    WorkerBinding,
    create_platform_router,
    create_router,
    create_worker_router,
    make_worker_context_dependency,
    require_factory_context,
    require_platform_context,
)
from .service import VisionTrainingService
from .store import FileDraftStore
from .preparation import PreparationStore
from .preparation_router import create_preparation_router
from .preparation_worker_router import create_preparation_worker_router


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


@dataclass(frozen=True)
class VisionTrainingRouters:
    factory: APIRouter
    platform: APIRouter
    worker: APIRouter


@dataclass
class VisionTrainingRuntime:
    service: VisionTrainingService
    job_store: VisionJobStore
    routers: VisionTrainingRouters
    preparation_store: PreparationStore

    def startup_probe(self) -> None:
        self.job_store.probe()
        self.preparation_store.probe()

    def close(self) -> None:
        self.job_store.close()


def create_file_backed_runtime(
    *,
    draft_root: str | Path,
    job_db: str | Path,
    recipe_catalog: Collection[RecipeAvailability] = (),
    worker_bindings: Collection[WorkerBinding] = (),
    specialized_factory_allowlist: Mapping[str, Collection[str]] | None = None,
    factory_context_dependency=require_factory_context,
    platform_context_dependency=require_platform_context,
) -> VisionTrainingRuntime:
    """Create all three routers with explicit, code-external local state.

    Empty recipe/binding/allowlist collections are intentional safe defaults:
    generic work remains blocked and no specialized factory is enabled until the
    host supplies a trusted registration.
    """

    durable_draft_root = resolve_draft_root(str(draft_root))
    durable_job_db = Path(job_db)
    if not durable_job_db.is_absolute():
        raise ValueError("job_db must be an absolute persistent path")
    durable_job_db = durable_job_db.resolve()
    source_root = Path(__file__).resolve().parents[3]
    if source_root == durable_job_db or source_root in durable_job_db.parents:
        raise ValueError("vision training jobs must be outside the release-managed source tree")
    store = FileDraftStore(durable_draft_root)
    jobs = VisionJobStore(durable_job_db)
    service = VisionTrainingService(
        store=store,
        specialized_factory_allowlist=specialized_factory_allowlist,
        job_store=jobs,
    )
    worker_dependency = make_worker_context_dependency(worker_bindings)
    routers = VisionTrainingRouters(
        factory=create_router(
            service,
            factory_context_dependency=factory_context_dependency,
            job_store=jobs,
            recipe_catalog=recipe_catalog,
        ),
        platform=create_platform_router(
            service,
            jobs,
            platform_context_dependency=platform_context_dependency,
            recipe_catalog=recipe_catalog,
        ),
        worker=create_worker_router(service, jobs, worker_context_dependency=worker_dependency),
    )
    preparation = PreparationStore(durable_draft_root / "preparation.sqlite3")
    routers.factory.include_router(create_preparation_router(
        service, jobs, preparation,
        tuple(recipe_catalog), factory_context_dependency, tuple(worker_bindings)))
    routers.worker.include_router(create_preparation_worker_router(service, preparation, worker_dependency))
    return VisionTrainingRuntime(service=service, job_store=jobs, routers=routers, preparation_store=preparation)


def safe_example_registration() -> dict[str, object]:
    """Return a non-secret example suitable for documentation or form schemas."""

    return {
        "recipes": [],
        "worker_bindings": [],
        "specialized_factory_allowlist": {},
        "note": "Register a recipe and named worker through trusted server configuration; no secret or customer path is included.",
    }
