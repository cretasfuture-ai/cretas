"""Versioned contracts for the factory-scoped vision training SOP.

The contracts deliberately keep planning, execution, evaluation, and enablement
separate.  A successful training receipt therefore cannot imply acceptance or
model activation.
"""

from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class ProjectStatus(str, Enum):
    DRAFT = "draft"
    READY = "ready"
    TRAINING = "training"
    EVALUATED = "evaluated"
    ACCEPTED = "accepted"
    ENABLED = "enabled"
    BLOCKED = "blocked"


class ReceiptStatus(str, Enum):
    PENDING = "pending"
    SUCCEEDED = "succeeded"
    FAILED = "failed"


class StageName(str, Enum):
    PREVIEW = "preview"
    COLLECT = "collect"
    DATASET = "dataset"
    TRAIN = "train"
    EVALUATE = "evaluate"
    INDEPENDENT_ACCEPTANCE = "independent_acceptance"
    ENABLE = "enable"


class FactoryContext(StrictModel):
    """Authenticated factory context supplied by the host application.

    The API must obtain this object from its auth dependency.  A client supplied
    ``factory_id`` is only a requested scope and is never an identity source.
    """

    factory_id: str = Field(min_length=1)
    actor_id: str = Field(min_length=1)
    roles: tuple[str, ...] = ()

    @field_validator("factory_id", "actor_id")
    @classmethod
    def _non_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("value must not be blank")
        return value


class TaskSpec(StrictModel):
    """Everything a conversational factory setup must make explicit."""

    factory_id: str = Field(min_length=1)
    workstation_id: str = Field(min_length=1)
    task_family: str = Field(min_length=1)
    product_scope: tuple[str, ...] = Field(min_length=1)
    packaging_scope: tuple[str, ...] = Field(min_length=1)
    data_version: str = Field(min_length=1)
    recipe_version: str = Field(min_length=1)
    human_standard_version: str = Field(min_length=1)
    independent_acceptance_version: str = Field(min_length=1)
    ocr_rules_version: str | None = None

    @field_validator(
        "factory_id",
        "workstation_id",
        "task_family",
        "data_version",
        "recipe_version",
        "human_standard_version",
        "independent_acceptance_version",
    )
    @classmethod
    def _required_text(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("value must not be blank")
        return value

    @field_validator("product_scope", "packaging_scope")
    @classmethod
    def _scope_items(cls, value: tuple[str, ...]) -> tuple[str, ...]:
        if not value or any(not item.strip() for item in value):
            raise ValueError("scope must contain at least one non-blank item")
        if len(set(value)) != len(value):
            raise ValueError("scope items must be unique")
        return value


class TaskTemplate(StrictModel):
    template_id: str = Field(min_length=1)
    version: str = Field(min_length=1)
    task_family: str = Field(min_length=1)
    title: str = Field(min_length=1)
    scope: Literal["generic", "specialized"]
    required_fields: tuple[str, ...] = ()
    required_receipts: tuple[str, ...] = ()
    stages: tuple[StageName, ...] = (
        StageName.PREVIEW,
        StageName.COLLECT,
        StageName.DATASET,
        StageName.TRAIN,
        StageName.EVALUATE,
        StageName.INDEPENDENT_ACCEPTANCE,
        StageName.ENABLE,
    )
    ocr_rules_required: bool = False
    adapter_id: str | None = None
    human_standard: str = Field(min_length=1)
    independent_acceptance: str = Field(min_length=1)

    @model_validator(mode="after")
    def _specialized_adapter(self) -> "TaskTemplate":
        if self.scope == "specialized" and not self.adapter_id:
            raise ValueError("specialized templates require an explicit adapter_id")
        if self.ocr_rules_required and "ocr_rules_version" not in self.required_fields:
            raise ValueError("OCR templates must require ocr_rules_version")
        return self


class AssetReceipt(StrictModel):
    """Receipt for a dataset, model, or evaluation artifact.

    ``factory_id`` and ``task_family`` are intentionally carried on every
    receipt so cross-factory and cross-task reuse can fail closed.
    """

    receipt_id: str = Field(min_length=1)
    kind: Literal["dataset", "model", "evaluation"]
    status: ReceiptStatus
    factory_id: str = Field(min_length=1)
    task_id: str = Field(min_length=1)
    task_family: str = Field(min_length=1)
    data_version: str = Field(min_length=1)
    recipe_version: str = Field(min_length=1)
    human_standard_version: str = Field(min_length=1)
    sha256: str = Field(min_length=1)
    reviewed_only: bool = False
    unreviewed_count: int = Field(default=0, ge=0)
    protected_holdout_excluded: bool = False
    independent: bool = False
    training_success: bool = False
    acceptance_status: Literal["pending", "passed", "failed"] = "pending"
    enabled: bool = False
    production_writes: int = Field(default=0, ge=0)
    details: dict[str, Any] = Field(default_factory=dict)


class StageReceipt(StrictModel):
    receipt_id: str = Field(min_length=1)
    stage: StageName
    status: ReceiptStatus
    project_id: str = Field(min_length=1)
    factory_id: str = Field(min_length=1)
    task_family: str = Field(min_length=1)
    training_executed: bool = False
    production_writes: int = Field(default=0, ge=0)
    message: str = ""
    details: dict[str, Any] = Field(default_factory=dict)
    created_at: datetime = Field(default_factory=utc_now)


class ProjectPreview(StrictModel):
    preview_id: str = Field(min_length=1)
    preview_hash: str = Field(min_length=1)
    sop_version: str = Field(min_length=1)
    template: TaskTemplate
    spec: TaskSpec
    stage_plan: tuple[StageName, ...]
    missing_requirements: tuple[str, ...] = ()
    warnings: tuple[str, ...] = ()
    generated_at: datetime = Field(default_factory=utc_now)


class TrainingProjectDraft(StrictModel):
    project_id: str = Field(min_length=1)
    sop_version: str = Field(min_length=1)
    template: TaskTemplate
    spec: TaskSpec
    status: ProjectStatus = ProjectStatus.DRAFT
    preview_hash: str = Field(min_length=1)
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)
    receipts: tuple[StageReceipt, ...] = ()


class StagePlanItem(StrictModel):
    stage: StageName
    status: ReceiptStatus
    reason: str = ""
    receipt_id: str | None = None


class StagePlan(StrictModel):
    project_id: str = Field(min_length=1)
    status: ProjectStatus
    items: tuple[StagePlanItem, ...]
    training_success: bool = False
    accepted: bool = False
    enabled: bool = False
    blockers: tuple[str, ...] = ()


class ValidationReport(StrictModel):
    valid: bool
    errors: tuple[str, ...] = ()
    warnings: tuple[str, ...] = ()
