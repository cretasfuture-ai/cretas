"""Public SOP entry points kept separate from the stage runner implementation."""

from .models import AssetReceipt, FactoryContext, TaskSpec, TaskTemplate
from .service import VisionTrainingService
from .templates import BUILTIN_TEMPLATES, SOP_VERSION, TemplateCatalog

__all__ = [
    "AssetReceipt",
    "BUILTIN_TEMPLATES",
    "FactoryContext",
    "SOP_VERSION",
    "TaskSpec",
    "TaskTemplate",
    "TemplateCatalog",
    "VisionTrainingService",
]
