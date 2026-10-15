"""Preview, draft, receipt validation, and stage planning services."""

from __future__ import annotations

import hashlib
import json
from collections.abc import Collection, Mapping
from datetime import datetime, timezone
from typing import Iterable
from typing import TYPE_CHECKING
from pydantic import ValidationError

from .errors import (
    DraftNotFoundError,
    InvalidTaskSpecError,
    PreviewMismatchError,
    ScopeViolationError,
)
from .models import (
    AssetReceipt,
    FactoryContext,
    JobReceipt,
    ProjectPreview,
    ProjectStatus,
    ReceiptStatus,
    StageName,
    StagePlan,
    StagePlanItem,
    StageReceipt,
    TaskSpec,
    TaskTemplate,
    TrainingProjectDraft,
    ValidationReport,
)
from .store import DraftStore, InMemoryDraftStore
from .templates import SOP_VERSION, TemplateCatalog

if TYPE_CHECKING:
    from .queue import VisionJobStore


def _canonical(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _hash_preview(spec: TaskSpec, template: TaskTemplate) -> str:
    payload = {
        "sop_version": SOP_VERSION,
        "template_id": template.template_id,
        "template_version": template.version,
        "spec": spec.model_dump(mode="json"),
    }
    return hashlib.sha256(_canonical(payload).encode("utf-8")).hexdigest()


def _coerce_spec(value: TaskSpec | dict[str, object]) -> TaskSpec:
    if isinstance(value, TaskSpec):
        return value
    try:
        return TaskSpec.model_validate(value)
    except ValidationError as exc:
        raise InvalidTaskSpecError(str(exc)) from exc


class VisionTrainingService:
    """The safe orchestration boundary used by a future API or conversation tool.

    It does not run training.  A host can later inject named adapters and feed
    their existing receipts into ``validate_assets`` and ``stage_plan``.
    """

    def __init__(
        self,
        *,
        store: DraftStore | None = None,
        catalog: TemplateCatalog | None = None,
        specialized_factory_allowlist: Mapping[str, Collection[str]] | None = None,
        job_store: "VisionJobStore | None" = None,
    ) -> None:
        self.store = store or InMemoryDraftStore()
        self.catalog = catalog or TemplateCatalog()
        self.specialized_factory_allowlist = {
            template_id: frozenset(factory_ids)
            for template_id, factory_ids in (specialized_factory_allowlist or {}).items()
        }
        self.job_store = job_store

    def attach_job_store(self, job_store: "VisionJobStore") -> None:
        self.job_store = job_store

    @staticmethod
    def _check_context(spec: TaskSpec, context: FactoryContext | None) -> None:
        if context is not None and context.factory_id != spec.factory_id:
            raise ScopeViolationError(
                f"requested factory {spec.factory_id!r} does not match authenticated factory "
                f"{context.factory_id!r}"
            )

    def _template(self, spec: TaskSpec, template_id: str | None) -> TaskTemplate:
        template = (
            self.catalog.get(template_id)
            if template_id
            else self.catalog.for_task_family(spec.task_family)
        )
        if template.task_family != spec.task_family:
            raise InvalidTaskSpecError(
                f"template {template.template_id!r} is for {template.task_family!r}, "
                f"not {spec.task_family!r}"
            )
        missing = [
            field
            for field in template.required_fields
            if field == "ocr_rules_version"
            and not getattr(spec, field, None)
        ]
        if missing:
            raise InvalidTaskSpecError(
                f"template {template.template_id!r} is missing required fields: {', '.join(missing)}"
            )
        return template

    def _check_specialized_factory(self, template: TaskTemplate, factory_id: str) -> None:
        if template.scope != "specialized":
            return
        allowed = self.specialized_factory_allowlist.get(template.template_id, frozenset())
        if factory_id not in allowed:
            raise ScopeViolationError(
                f"specialized template {template.template_id!r} has no explicit binding for factory {factory_id!r}"
            )

    def preview(
        self,
        spec: TaskSpec | dict[str, object],
        *,
        template_id: str | None = None,
        factory_context: FactoryContext | None = None,
    ) -> ProjectPreview:
        parsed = _coerce_spec(spec)
        self._check_context(parsed, factory_context)
        template = self._template(parsed, template_id)
        self._check_specialized_factory(template, parsed.factory_id)
        preview_hash = _hash_preview(parsed, template)
        warnings: list[str] = []
        if template.scope == "specialized":
            warnings.append(
                "specialized adapter is opt-in; no generic factory may inherit its model or thresholds"
            )
        return ProjectPreview(
            preview_id=f"preview-{preview_hash[:20]}",
            preview_hash=preview_hash,
            sop_version=SOP_VERSION,
            template=template,
            spec=parsed,
            stage_plan=template.stages,
            warnings=tuple(warnings),
        )

    def save_draft(
        self,
        preview: ProjectPreview,
        *,
        factory_context: FactoryContext | None = None,
    ) -> TrainingProjectDraft:
        self._check_context(preview.spec, factory_context)
        if preview.sop_version != SOP_VERSION:
            raise PreviewMismatchError("preview SOP version is not supported")
        # Treat the client preview as an acknowledgement, never as a source
        # of template policy.  Rebuild the template from the server catalog
        # and compare the complete model before accepting the hash.
        canonical_template = self._template(preview.spec, preview.template.template_id)
        self._check_specialized_factory(canonical_template, preview.spec.factory_id)
        if preview.template != canonical_template:
            raise PreviewMismatchError("preview template does not match the registered catalog")
        if preview.stage_plan != canonical_template.stages:
            raise PreviewMismatchError("preview stage plan does not match the registered template")
        expected_hash = _hash_preview(preview.spec, canonical_template)
        if preview.preview_hash != expected_hash:
            raise PreviewMismatchError("preview no longer matches its task specification or template")
        if preview.preview_id != f"preview-{expected_hash[:20]}":
            raise PreviewMismatchError("preview id does not match its canonical content hash")
        project_id = f"vtp-{preview.preview_hash[:20]}"
        # The preview hash is the idempotency key.  An existing draft is
        # returned verbatim so a repeated save cannot erase receipts or move
        # its original creation time.
        now = datetime.now(timezone.utc)
        draft = TrainingProjectDraft(
            project_id=project_id,
            sop_version=SOP_VERSION,
            template=canonical_template,
            spec=preview.spec,
            preview_hash=preview.preview_hash,
            created_at=now,
            updated_at=now,
        )
        try:
            return self.store.create_if_absent(draft)
        except ValueError as exc:
            raise PreviewMismatchError("draft identity does not match the preview") from exc

    def available_templates(self, factory_id: str) -> tuple[TaskTemplate, ...]:
        """Return the catalog visible to one trusted factory context."""

        available: list[TaskTemplate] = []
        for template in self.catalog.all():
            if template.scope == "generic":
                available.append(template)
                continue
            if factory_id in self.specialized_factory_allowlist.get(
                template.template_id, frozenset()
            ):
                available.append(template)
        return tuple(available)

    def readback(self, project_id: str) -> TrainingProjectDraft:
        draft = self.store.get(project_id)
        if draft is None:
            raise DraftNotFoundError(f"training project draft not found: {project_id}")
        if self.job_store is not None:
            stage_receipts = {item.receipt_id: item for item in draft.receipts}
            assets = {item.receipt_id: item for item in draft.asset_receipts}
            for job in self.job_store.list_for_project(project_id, factory_id=draft.spec.factory_id):
                if job.receipt is None:
                    continue
                stage_receipts[job.receipt.stage_receipt.receipt_id] = job.receipt.stage_receipt
                for asset in job.receipt.asset_receipts:
                    assets[asset.receipt_id] = asset
            draft = draft.model_copy(update={"receipts": tuple(stage_receipts.values()), "asset_receipts": tuple(assets.values())})
        return draft

    def list_drafts(
        self,
        factory_id: str,
        *,
        factory_context: FactoryContext | None = None,
    ) -> tuple[TrainingProjectDraft, ...]:
        if factory_context is not None and factory_context.factory_id != factory_id:
            raise ScopeViolationError("requested factory does not match authenticated factory")
        return tuple(self.readback(item.project_id) for item in self.store.list(factory_id))

    def record_stage_receipt(
        self,
        project_id: str,
        receipt: StageReceipt,
        *,
        factory_context: FactoryContext | None = None,
    ) -> TrainingProjectDraft:
        draft = self.readback(project_id)
        self._check_context(draft.spec, factory_context)
        if receipt.project_id != project_id:
            raise ScopeViolationError("stage receipt project_id does not match the draft")
        if receipt.factory_id != draft.spec.factory_id:
            raise ScopeViolationError("stage receipt factory_id does not match the draft")
        if receipt.task_family != draft.spec.task_family:
            raise ScopeViolationError("stage receipt task_family does not match the draft")
        if receipt.production_writes != 0:
            raise ScopeViolationError("vision training coordinator requires production_writes=0")
        existing = {item.receipt_id: item for item in draft.receipts}
        previous = existing.get(receipt.receipt_id)
        if previous is not None and previous != receipt:
            raise ValueError(f"stage receipt is not idempotent: {receipt.receipt_id}")
        if previous is None:
            existing[receipt.receipt_id] = receipt
        updated = draft.model_copy(
            update={
                "receipts": tuple(existing.values()),
                "updated_at": datetime.now(timezone.utc),
            }
        )
        return self.store.put(updated)

    def record_job_receipt(
        self,
        project_id: str,
        receipt: JobReceipt,
        *,
        factory_context: FactoryContext | None = None,
    ) -> TrainingProjectDraft:
        """Persist one verified worker receipt without changing project identity."""

        draft = self.readback(project_id)
        self._check_context(draft.spec, factory_context)
        if receipt.job_id == "" or receipt.identity.project_id != project_id:
            raise ScopeViolationError("worker receipt project_id does not match the draft")
        if receipt.identity.factory_id != draft.spec.factory_id:
            raise ScopeViolationError("worker receipt factory_id does not match the draft")
        if receipt.identity.task_family != draft.spec.task_family:
            raise ScopeViolationError("worker receipt task_family does not match the draft")
        if receipt.identity.preview_hash != draft.preview_hash:
            raise ScopeViolationError("worker receipt preview hash does not match the draft")
        stage_receipts = {item.receipt_id: item for item in draft.receipts}
        previous = stage_receipts.get(receipt.stage_receipt.receipt_id)
        if previous is not None and previous != receipt.stage_receipt:
            raise ValueError(f"stage receipt is not idempotent: {receipt.stage_receipt.receipt_id}")
        if previous is None:
            stage_receipts[receipt.stage_receipt.receipt_id] = receipt.stage_receipt
        assets = {item.receipt_id: item for item in draft.asset_receipts}
        for asset in receipt.asset_receipts:
            prior = assets.get(asset.receipt_id)
            if prior is not None and prior != asset:
                raise ValueError(f"asset receipt is not idempotent: {asset.receipt_id}")
            assets[asset.receipt_id] = asset
        updated = draft.model_copy(
            update={
                "receipts": tuple(stage_receipts.values()),
                "asset_receipts": tuple(assets.values()),
                "updated_at": datetime.now(timezone.utc),
            }
        )
        return self.store.put(updated)

    def validate_assets(
        self,
        project_id: str,
        receipts: Iterable[AssetReceipt],
        *,
        factory_context: FactoryContext | None = None,
    ) -> ValidationReport:
        draft = self.readback(project_id)
        self._check_context(draft.spec, factory_context)
        items = tuple(receipts)
        errors: list[str] = []
        warnings: list[str] = []
        by_kind: dict[str, AssetReceipt] = {}
        for receipt in items:
            if receipt.kind in by_kind:
                errors.append(f"duplicate {receipt.kind} receipt")
                continue
            by_kind[receipt.kind] = receipt
            if receipt.factory_id != draft.spec.factory_id:
                errors.append(f"{receipt.kind} receipt crosses factory scope")
            if receipt.task_id != project_id:
                errors.append(f"{receipt.kind} receipt crosses project scope")
            if receipt.task_family != draft.spec.task_family:
                errors.append(f"{receipt.kind} receipt crosses task family scope")
            if receipt.data_version != draft.spec.data_version:
                errors.append(f"{receipt.kind} data_version does not match the draft")
            if receipt.recipe_version != draft.spec.recipe_version:
                errors.append(f"{receipt.kind} recipe_version does not match the draft")
            if receipt.human_standard_version != draft.spec.human_standard_version:
                errors.append(f"{receipt.kind} human_standard_version does not match the draft")
            if receipt.production_writes != 0:
                errors.append(f"{receipt.kind} receipt reports production writes")
            if receipt.status != ReceiptStatus.SUCCEEDED:
                errors.append(f"{receipt.kind} receipt is not succeeded")

        for required in draft.template.required_receipts:
            if required not in by_kind:
                errors.append(f"missing required {required} receipt")

        dataset = by_kind.get("dataset")
        if dataset is not None:
            if not dataset.reviewed_only:
                errors.append("dataset is not restricted to human-reviewed samples")
            if dataset.unreviewed_count != 0:
                errors.append("dataset contains unreviewed samples")
            if not dataset.protected_holdout_excluded:
                errors.append("protected holdout must be excluded from training dataset")

        model = by_kind.get("model")
        if model is not None:
            if not model.training_success:
                errors.append("model receipt does not prove training success")
            if model.enabled:
                errors.append("model receipt cannot enable a model")
            if model.acceptance_status != "pending":
                warnings.append("model acceptance state is informational; evaluation remains independent")

        evaluation = by_kind.get("evaluation")
        if evaluation is not None:
            if not evaluation.independent:
                errors.append("evaluation is not marked independent")
            if evaluation.acceptance_status == "failed":
                errors.append("independent evaluation failed")
            if evaluation.acceptance_status == "pending":
                warnings.append("independent acceptance is still pending")

        if model is not None and model.training_success and evaluation is None:
            warnings.append("training success does not imply evaluation or acceptance")
        return ValidationReport(valid=not errors, errors=tuple(errors), warnings=tuple(warnings))

    def stage_plan(
        self,
        project_id: str,
        receipts: Iterable[AssetReceipt] = (),
        *,
        factory_context: FactoryContext | None = None,
    ) -> StagePlan:
        draft = self.readback(project_id)
        self._check_context(draft.spec, factory_context)
        assets = tuple(receipts) or draft.asset_receipts
        report = (
            self.validate_assets(project_id, assets, factory_context=factory_context)
            if assets
            else ValidationReport(valid=False, errors=("no asset receipts supplied",))
        )
        by_kind = {item.kind: item for item in assets}
        stage_receipts = {item.stage: item for item in draft.receipts}
        items: list[StagePlanItem] = []
        blockers: list[str] = list(report.errors)
        items.append(StagePlanItem(stage=StageName.PREVIEW, status=ReceiptStatus.SUCCEEDED, reason="preview generated"))
        for stage in (StageName.COLLECT, StageName.DATASET):
            receipt = stage_receipts.get(stage)
            stage_status = receipt.status if receipt else ReceiptStatus.PENDING
            items.append(StagePlanItem(
                stage=stage,
                status=stage_status,
                receipt_id=receipt.receipt_id if receipt else None,
                reason=(
                    "execution recorded; asset validation still blocks downstream use"
                    if receipt and not report.valid and receipt.status == ReceiptStatus.SUCCEEDED
                    else receipt.message if receipt else "not executed"
                ),
            ))
        model = by_kind.get("model")
        train_success = bool(
            report.valid
            and model
            and model.status == ReceiptStatus.SUCCEEDED
            and model.training_success
        )
        train_receipt = stage_receipts.get(StageName.TRAIN)
        train_status = (
            ReceiptStatus.SUCCEEDED
            if train_success
            else ReceiptStatus.FAILED
            if model and model.status == ReceiptStatus.FAILED
            else train_receipt.status
            if train_receipt
            else ReceiptStatus.PENDING
        )
        items.append(StagePlanItem(
            stage=StageName.TRAIN,
            status=train_status,
            receipt_id=train_receipt.receipt_id if train_receipt else None,
            reason=(
                "training receipt succeeded; acceptance remains separate"
                if train_success
                else "execution recorded; asset validation still blocks downstream use"
                if not report.valid and train_status == ReceiptStatus.SUCCEEDED
                else "blocked by asset validation"
                if not report.valid and train_status == ReceiptStatus.PENDING
                else "training not executed"
            ),
        ))
        evaluation = by_kind.get("evaluation")
        eval_success = bool(
            report.valid
            and evaluation
            and evaluation.status == ReceiptStatus.SUCCEEDED
        )
        eval_receipt = stage_receipts.get(StageName.EVALUATE)
        eval_status = (
            ReceiptStatus.SUCCEEDED
            if eval_success
            else ReceiptStatus.FAILED
            if evaluation and (
                evaluation.status == ReceiptStatus.FAILED
                or evaluation.acceptance_status == "failed"
            )
            else eval_receipt.status
            if eval_receipt
            else ReceiptStatus.PENDING
        )
        items.append(StagePlanItem(
            stage=StageName.EVALUATE,
            status=eval_status,
            receipt_id=eval_receipt.receipt_id if eval_receipt else None,
            reason=(
                "evaluation receipt succeeded"
                if eval_success
                else "evaluation failed"
                if eval_status == ReceiptStatus.FAILED
                else "execution recorded; asset validation still blocks downstream use"
                if not report.valid and eval_status == ReceiptStatus.SUCCEEDED
                else "blocked by asset validation"
                if not report.valid
                else "evaluation not executed"
            ),
        ))
        accepted = bool(
            report.valid
            and evaluation
            and evaluation.status == ReceiptStatus.SUCCEEDED
            and evaluation.independent
            and evaluation.acceptance_status == "passed"
        )
        if not accepted and report.valid:
            blockers.append("independent acceptance is explicit and is not inferred from training success")
        items.append(StagePlanItem(
            stage=StageName.INDEPENDENT_ACCEPTANCE,
            status=ReceiptStatus.SUCCEEDED if accepted else ReceiptStatus.PENDING,
            reason=(
                "independent acceptance receipt passed"
                if accepted
                else "blocked by asset validation"
                if not report.valid
                else "pending independent acceptance"
            ),
        ))
        if not accepted:
            blockers.append("model enablement is blocked until independent acceptance passes")
        items.append(StagePlanItem(
            stage=StageName.ENABLE,
            status=ReceiptStatus.PENDING,
            reason="enablement is a separate authorized action and is never automatic",
        ))
        status = ProjectStatus.ACCEPTED if accepted else (ProjectStatus.EVALUATED if eval_success else (ProjectStatus.TRAINING if train_success else ProjectStatus.DRAFT))
        if report.errors:
            status = ProjectStatus.BLOCKED
        return StagePlan(
            project_id=project_id,
            status=status,
            items=tuple(items),
            training_success=train_success,
            accepted=accepted,
            enabled=False,
            blockers=tuple(dict.fromkeys(blockers)),
        )
