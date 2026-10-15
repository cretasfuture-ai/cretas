"""Factory-scoped vision training SOP planning package."""

from .adapters import AdapterRegistry, LiushanmenVisionLabAdapter
from .models import (
    AssetReceipt,
    EnqueueRequest,
    FactoryContext,
    JobIdentity,
    JobReceipt,
    JobStatus,
    ProjectPreview,
    RecipeAvailability,
    StageName,
    StagePlan,
    TaskSpec,
    TrainingProjectDraft,
    ValidationReport,
    VisionJob,
    WorkerClaimRequest,
    WorkerCompletion,
    WorkerHeartbeatRequest,
)
from .queue import NO_NAMED_RUNNER_REASON, VisionJobStore, build_identity, receipt_hash
from .router import (
    WorkerBinding,
    WorkerContext,
    create_platform_router,
    create_worker_router,
    make_worker_context_dependency,
    require_platform_context,
)
from .service import VisionTrainingService
from .runtime import VisionTrainingRouters, VisionTrainingRuntime, create_file_backed_runtime, safe_example_registration
from .store import FileDraftStore, InMemoryDraftStore
from .templates import SOP_VERSION, TemplateCatalog

__all__ = [
    "AdapterRegistry",
    "AssetReceipt",
    "EnqueueRequest",
    "FactoryContext",
    "FileDraftStore",
    "InMemoryDraftStore",
    "LiushanmenVisionLabAdapter",
    "NO_NAMED_RUNNER_REASON",
    "JobIdentity",
    "JobReceipt",
    "JobStatus",
    "ProjectPreview",
    "RecipeAvailability",
    "SOP_VERSION",
    "StageName",
    "StagePlan",
    "TaskSpec",
    "TemplateCatalog",
    "TrainingProjectDraft",
    "ValidationReport",
    "VisionJob",
    "VisionJobStore",
    "VisionTrainingService",
    "VisionTrainingRouters",
    "VisionTrainingRuntime",
    "WorkerBinding",
    "WorkerClaimRequest",
    "WorkerCompletion",
    "WorkerContext",
    "WorkerHeartbeatRequest",
    "build_identity",
    "create_platform_router",
    "create_worker_router",
    "make_worker_context_dependency",
    "receipt_hash",
    "require_platform_context",
    "create_file_backed_runtime",
    "safe_example_registration",
]
