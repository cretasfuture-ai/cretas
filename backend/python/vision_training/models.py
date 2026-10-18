"""Versioned contracts for the factory-scoped vision training SOP.

The contracts deliberately keep planning, execution, evaluation, and enablement
separate.  A successful training receipt therefore cannot imply acceptance or
model activation.
"""

from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
import re
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

    factory_id: str = Field(min_length=1, max_length=100)
    actor_id: str = Field(min_length=1, max_length=100)
    roles: tuple[str, ...] = ()
    actor_type: Literal["FACTORY_USER", "PLATFORM_ADMIN", "WORKER"] = "FACTORY_USER"

    @field_validator("factory_id", "actor_id")
    @classmethod
    def _non_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("value must not be blank")
        return value


class JobStatus(str, Enum):
    QUEUED = "queued"
    RUNNING = "running"
    SUCCEEDED = "succeeded"
    FAILED = "failed"
    BLOCKED = "blocked"


class JobIdentity(StrictModel):
    """Immutable scope and input identity for one local execution request."""

    factory_id: str = Field(min_length=1, max_length=100)
    project_id: str = Field(min_length=1, max_length=100)
    task_family: str = Field(min_length=1, max_length=100)
    template_id: str = Field(min_length=1, max_length=100)
    template_version: str = Field(min_length=1, max_length=100)
    sop_version: str = Field(min_length=1, max_length=100)
    data_version: str = Field(min_length=1, max_length=100)
    recipe_version: str = Field(min_length=1, max_length=100)
    human_standard_version: str = Field(min_length=1, max_length=100)
    independent_acceptance_version: str = Field(min_length=1, max_length=100)
    recipe_id: str | None = Field(default=None, max_length=100)
    ocr_rules_version: str | None = Field(default=None, max_length=100)
    preview_hash: str = Field(pattern=r"^[0-9a-fA-F]{64}$")
    config_hash: str = Field(pattern=r"^[0-9a-fA-F]{64}$")
    input_hash: str = Field(pattern=r"^[0-9a-fA-F]{64}$")
    source_asset_sha256: str | None = Field(default=None, pattern=r"^[0-9a-fA-F]{64}$")


class VisionJob(StrictModel):
    job_id: str = Field(min_length=1, max_length=160)
    identity: JobIdentity
    stage: StageName
    adapter_id: str | None = Field(default=None, max_length=100)
    status: JobStatus = JobStatus.QUEUED
    attempts: int = Field(default=0, ge=0)
    lease_owner: str | None = None
    lease_expires_at: datetime | None = None
    blocked_reason: str | None = None
    receipt: "JobReceipt | None" = None
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)


class JobReceipt(StrictModel):
    """Worker receipt with the complete immutable job identity attached."""

    job_id: str = Field(min_length=1, max_length=160)
    identity: JobIdentity
    stage: StageName
    status: ReceiptStatus
    receipt_hash: str = Field(pattern=r"^[0-9a-fA-F]{64}$")
    stage_receipt: "StageReceipt"
    asset_receipts: tuple["AssetReceipt", ...] = ()
    created_at: datetime = Field(default_factory=utc_now)


class EnqueueRequest(StrictModel):
    stage: StageName
    config_hash: str | None = Field(default=None, pattern=r"^[0-9a-fA-F]{64}$")
    input_hash: str | None = Field(default=None, pattern=r"^[0-9a-fA-F]{64}$")
    recipe_id: str | None = Field(default=None, max_length=100)


class RecipeAvailability(StrictModel):
    recipe_id: str = Field(min_length=1, max_length=100)
    factory_id: str = Field(min_length=1, max_length=100)
    task_family: str = Field(min_length=1, max_length=100)
    recipe_version: str = Field(min_length=1, max_length=100)
    available: bool = False
    reason: str = ""
    template_id: str | None = Field(default=None, max_length=100)
    template_version: str | None = Field(default=None, max_length=100)
    sop_version: str | None = Field(default=None, max_length=100)
    data_version: str | None = Field(default=None, max_length=100)
    human_standard_version: str | None = Field(default=None, max_length=100)
    independent_acceptance_version: str | None = Field(default=None, max_length=100)
    ocr_rules_version: str | None = Field(default=None, max_length=100)
    config_sha256: str | None = Field(default=None, pattern=r"^[0-9a-fA-F]{64}$")
    stage_input_hashes: dict[str, str] = Field(default_factory=dict)
    stage_source_asset_hashes: dict[str, str] = Field(default_factory=dict)
    preparation_project_id: str | None = Field(default=None, pattern=r"^vtp-[0-9a-f]{20}$")
    preparation_sha256: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")

    @field_validator("stage_input_hashes")
    @classmethod
    def _stage_hashes(cls, value: dict[str, str]) -> dict[str, str]:
        allowed = {item.value for item in StageName}
        if any(stage not in allowed or not isinstance(digest, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", digest) for stage, digest in value.items()):
            raise ValueError("stage_input_hashes must contain stage names and 64-character digests")
        return value

    @field_validator("stage_source_asset_hashes")
    @classmethod
    def _stage_source_hashes(cls, value: dict[str, str]) -> dict[str, str]:
        allowed = {item.value for item in StageName}
        if any(stage not in allowed or not isinstance(digest, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", digest) for stage, digest in value.items()):
            raise ValueError("stage_source_asset_hashes must contain stage names and 64-character digests")
        return value

    @model_validator(mode="after")
    def _available_recipe_is_complete(self) -> "RecipeAvailability":
        required = (
            self.template_id,
            self.template_version,
            self.sop_version,
            self.data_version,
            self.human_standard_version,
            self.independent_acceptance_version,
            self.config_sha256,
        )
        if self.available and (any(value is None or not str(value).strip() for value in required) or not self.stage_input_hashes):
            raise ValueError("available recipe must bind template, SOP, all task versions, config, and stage input hashes")
        return self


class WorkerClaimRequest(StrictModel):
    project_id: str | None = Field(default=None, max_length=100)
    lease_seconds: int = Field(default=900, ge=1, le=86400)


class WorkerHeartbeatRequest(StrictModel):
    lease_seconds: int = Field(default=900, ge=1, le=86400)


class WorkerCompletion(StrictModel):
    job_id: str = Field(min_length=1, max_length=160)
    receipt: JobReceipt


class TaskSpec(StrictModel):
    """Everything a conversational factory setup must make explicit."""

    factory_id: str = Field(min_length=1, max_length=100)
    workstation_id: str = Field(min_length=1, max_length=100)
    task_family: str = Field(min_length=1, max_length=100)
    product_scope: tuple[str, ...] = Field(min_length=1, max_length=100)
    packaging_scope: tuple[str, ...] = Field(min_length=1, max_length=100)
    data_version: str = Field(min_length=1, max_length=100)
    recipe_version: str = Field(min_length=1, max_length=100)
    human_standard_version: str = Field(min_length=1, max_length=100)
    independent_acceptance_version: str = Field(min_length=1, max_length=100)
    recipe_id: str | None = Field(default=None, max_length=100)
    ocr_rules_version: str | None = Field(default=None, max_length=100)

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
        if not value or len(value) > 100 or any(not item.strip() or len(item) > 100 for item in value):
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
    sha256: str = Field(pattern=r"^[0-9a-fA-F]{64}$")
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
    asset_receipts: tuple[AssetReceipt, ...] = ()


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


# These models refer to receipts declared later in this module.  Rebuilding at
# import time keeps validation strict while retaining the readable declaration
# order above.
VisionJob.model_rebuild()
JobReceipt.model_rebuild()
