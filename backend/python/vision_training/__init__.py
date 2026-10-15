"""Factory-scoped vision training SOP planning package."""

from .adapters import AdapterRegistry, LiushanmenVisionLabAdapter
from .models import (
    AssetReceipt,
    FactoryContext,
    ProjectPreview,
    StageName,
    StagePlan,
    TaskSpec,
    TrainingProjectDraft,
    ValidationReport,
)
from .service import VisionTrainingService
from .store import FileDraftStore, InMemoryDraftStore
from .templates import SOP_VERSION, TemplateCatalog

__all__ = [
    "AdapterRegistry",
    "AssetReceipt",
    "FactoryContext",
    "FileDraftStore",
    "InMemoryDraftStore",
    "LiushanmenVisionLabAdapter",
    "ProjectPreview",
    "SOP_VERSION",
    "StageName",
    "StagePlan",
    "TaskSpec",
    "TemplateCatalog",
    "TrainingProjectDraft",
    "ValidationReport",
    "VisionTrainingService",
]
