"""Explicit, named adapters for existing training-stage implementations."""

from __future__ import annotations

from collections.abc import Iterable
from typing import Any, Protocol

from .errors import AdapterNotRegisteredError, InvalidTaskSpecError, ScopeViolationError
from .models import StageName, StageReceipt, TaskTemplate, TrainingProjectDraft


class ExistingVisionLabRunner(Protocol):
    """The only runner surface accepted by the specialized adapter.

    An implementation may wrap the existing VisionLab functions.  It cannot
    provide an arbitrary shell command or an arbitrary filesystem path.
    """

    def collect(self, draft: TrainingProjectDraft) -> StageReceipt | dict[str, Any]: ...

    def build_dataset(self, draft: TrainingProjectDraft) -> StageReceipt | dict[str, Any]: ...

    def train(self, draft: TrainingProjectDraft) -> StageReceipt | dict[str, Any]: ...

    def evaluate(self, draft: TrainingProjectDraft) -> StageReceipt | dict[str, Any]: ...

    def deploy(self, draft: TrainingProjectDraft) -> StageReceipt | dict[str, Any]: ...


class StageAdapter(Protocol):
    adapter_id: str

    def supports(self, template: TaskTemplate) -> bool: ...

    def run_stage(self, draft: TrainingProjectDraft, stage: StageName) -> StageReceipt: ...


def _coerce_receipt(
    value: StageReceipt | dict[str, Any],
    *,
    draft: TrainingProjectDraft,
    stage: StageName,
) -> StageReceipt:
    receipt = value if isinstance(value, StageReceipt) else StageReceipt.model_validate(value)
    mismatches: list[str] = []
    if receipt.stage != stage:
        mismatches.append("stage")
    if receipt.project_id != draft.project_id:
        mismatches.append("project_id")
    if receipt.factory_id != draft.spec.factory_id:
        mismatches.append("factory_id")
    if receipt.task_family != draft.spec.task_family:
        mismatches.append("task_family")
    if mismatches:
        raise ScopeViolationError(
            "adapter receipt scope mismatch: " + ", ".join(mismatches)
        )
    if stage == StageName.TRAIN and receipt.status.value == "succeeded" and not receipt.training_executed:
        raise InvalidTaskSpecError("succeeded training receipt must set training_executed=true")
    return receipt


class LiushanmenVisionLabAdapter:
    """Opt-in adapter for the existing 六膳门 VisionLab stage contract.

    It has no default model path and never loads the historical LocateAnything
    weights.  With no injected runner every stage remains ``pending``.  A
    caller must explicitly provide a named runner that wraps the existing
    receipt-producing functions.
    """

    adapter_id = "liushanmen-visionlab-v1"
    template_id = "liushanmen-tray"
    task_family = "liushanmen-tray-label-detection"

    def __init__(
        self,
        runner: ExistingVisionLabRunner | None = None,
        *,
        allowed_factory_ids: Iterable[str] = (),
    ) -> None:
        self.runner = runner
        self.allowed_factory_ids = frozenset(allowed_factory_ids)

    def supports(self, template: TaskTemplate) -> bool:
        return (
            template.template_id == self.template_id
            and template.task_family == self.task_family
            and template.adapter_id == self.adapter_id
        )

    def run_stage(self, draft: TrainingProjectDraft, stage: StageName) -> StageReceipt:
        if not self.supports(draft.template):
            raise InvalidTaskSpecError("六膳门 adapter cannot run a generic or other factory template")
        if draft.spec.factory_id not in self.allowed_factory_ids:
            raise ScopeViolationError(
                "六膳门 adapter has no explicit binding for this factory"
            )
        runner_method: dict[StageName, str] = {
            StageName.COLLECT: "collect",
            StageName.DATASET: "build_dataset",
            StageName.TRAIN: "train",
            StageName.EVALUATE: "evaluate",
        }
        method_name = runner_method.get(stage)
        if method_name is None:
            return StageReceipt(
                receipt_id=f"pending-{draft.project_id}-{stage.value}",
                stage=stage,
                status="pending",
                project_id=draft.project_id,
                factory_id=draft.spec.factory_id,
                task_family=draft.spec.task_family,
                training_executed=False,
                production_writes=0,
                message="stage is planned but was not executed",
            )
        if self.runner is None:
            return StageReceipt(
                receipt_id=f"pending-{draft.project_id}-{stage.value}",
                stage=stage,
                status="pending",
                project_id=draft.project_id,
                factory_id=draft.spec.factory_id,
                task_family=draft.spec.task_family,
                training_executed=False,
                production_writes=0,
                message="no VisionLab runner registered; no training or deployment was executed",
            )
        value = getattr(self.runner, method_name)(draft)
        receipt = _coerce_receipt(value, draft=draft, stage=stage)
        if receipt.production_writes != 0:
            raise InvalidTaskSpecError("VisionLab adapter requires production_writes=0")
        return receipt


class AdapterRegistry:
    def __init__(self) -> None:
        self._adapters: dict[str, StageAdapter] = {}

    def register(self, adapter: StageAdapter) -> None:
        if adapter.adapter_id in self._adapters:
            raise ValueError(f"adapter already registered: {adapter.adapter_id}")
        self._adapters[adapter.adapter_id] = adapter

    def get(self, adapter_id: str) -> StageAdapter:
        try:
            return self._adapters[adapter_id]
        except KeyError as exc:
            raise AdapterNotRegisteredError(f"adapter is not explicitly registered: {adapter_id}") from exc

    def for_template(self, template: TaskTemplate) -> StageAdapter:
        if not template.adapter_id:
            raise AdapterNotRegisteredError(
                f"generic template {template.template_id} has no default training adapter"
            )
        adapter = self.get(template.adapter_id)
        if not adapter.supports(template):
            raise AdapterNotRegisteredError(
                f"registered adapter {template.adapter_id} does not support {template.template_id}"
            )
        return adapter
