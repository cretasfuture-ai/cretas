from __future__ import annotations

from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import pytest

from vision_training import (
    AdapterRegistry,
    AssetReceipt,
    FactoryContext,
    FileDraftStore,
    InMemoryDraftStore,
    LiushanmenVisionLabAdapter,
    StageName,
    TaskSpec,
    VisionTrainingService,
)
from vision_training.errors import (
    AdapterNotRegisteredError,
    InvalidTaskSpecError,
    PreviewMismatchError,
    ScopeViolationError,
    UnknownTaskTemplateError,
)
from vision_training.models import ReceiptStatus, StageReceipt


def spec(
    *,
    factory_id: str = "F001",
    task_family: str = "packaging-detection",
    ocr_rules_version: str | None = None,
) -> TaskSpec:
    return TaskSpec(
        factory_id=factory_id,
        workstation_id="ws-01",
        task_family=task_family,
        product_scope=("SKU-01",),
        packaging_scope=("tray",),
        data_version="photos-v3",
        recipe_version="recipe-v2",
        human_standard_version="human-v1",
        independent_acceptance_version="acceptance-v1",
        ocr_rules_version=ocr_rules_version,
    )


def receipt(
    project_id: str,
    kind: str,
    *,
    factory_id: str = "F001",
    task_family: str = "packaging-detection",
    reviewed_only: bool = True,
    unreviewed_count: int = 0,
    protected_holdout_excluded: bool = True,
    independent: bool = False,
    training_success: bool = False,
    acceptance_status: str = "pending",
) -> AssetReceipt:
    return AssetReceipt(
        receipt_id=f"{kind}-r1",
        kind=kind,
        status=ReceiptStatus.SUCCEEDED,
        factory_id=factory_id,
        task_id=project_id,
        task_family=task_family,
        data_version="photos-v3",
        recipe_version="recipe-v2",
        human_standard_version="human-v1",
        sha256="0" * 64,
        reviewed_only=reviewed_only,
        unreviewed_count=unreviewed_count,
        protected_holdout_excluded=protected_holdout_excluded,
        independent=independent,
        training_success=training_success,
        acceptance_status=acceptance_status,
    )


def make_project(service: VisionTrainingService, *, factory_id: str = "F001"):
    context = FactoryContext(factory_id=factory_id, actor_id="user-1", roles=("quality",))
    preview = service.preview(spec(factory_id=factory_id), factory_context=context)
    return service.save_draft(preview, factory_context=context)


def test_preview_is_read_only_and_unknown_task_is_rejected() -> None:
    store = InMemoryDraftStore()
    service = VisionTrainingService(store=store)

    preview = service.preview(spec())
    assert preview.sop_version == "vision-training-sop-v1"
    assert store.get("vtp-anything") is None

    with pytest.raises(UnknownTaskTemplateError):
        service.preview(spec(task_family="unregistered-task"))


def test_ocr_rules_are_required_by_the_ocr_template_and_separate_from_missing_template() -> None:
    service = VisionTrainingService()
    with pytest.raises(InvalidTaskSpecError, match="ocr_rules_version"):
        service.preview(spec(task_family="packaging-ocr"), template_id="generic-packaging-ocr")
    with pytest.raises(UnknownTaskTemplateError):
        service.preview(spec(task_family="unregistered-ocr"), template_id="missing-template")


def test_explicit_save_readback_is_idempotent_and_preview_mismatch_fails(tmp_path: Path) -> None:
    service = VisionTrainingService(store=FileDraftStore(tmp_path))
    preview = service.preview(spec())
    first = service.save_draft(preview)
    second = service.save_draft(preview)
    assert first.project_id == second.project_id
    assert service.readback(first.project_id).preview_hash == preview.preview_hash

    same_identity = first.model_copy(update={"created_at": first.created_at.replace(year=2025)})
    assert FileDraftStore(tmp_path).create_if_absent(same_identity).created_at == first.created_at
    different_identity = first.model_copy(
        update={"spec": first.spec.model_copy(update={"data_version": "photos-other"})}
    )
    with pytest.raises(ValueError, match="identity"):
        FileDraftStore(tmp_path).create_if_absent(different_identity)

    altered = preview.model_copy(update={"spec": preview.spec.model_copy(update={"data_version": "photos-v4"})})
    with pytest.raises(PreviewMismatchError):
        service.save_draft(altered)


def test_save_rebuilds_canonical_template_and_preserves_receipts_on_retry() -> None:
    service = VisionTrainingService()
    preview = service.preview(spec())
    tampered_template = preview.template.model_copy(update={"required_receipts": ()})
    tampered_preview = preview.model_copy(update={"template": tampered_template})
    with pytest.raises(PreviewMismatchError, match="registered catalog"):
        service.save_draft(tampered_preview)
    with pytest.raises(PreviewMismatchError, match="content hash"):
        service.save_draft(preview.model_copy(update={"preview_id": "preview-forged"}))

    first = service.save_draft(preview)
    created_at = first.created_at
    stage_receipt = StageReceipt(
        receipt_id="collect-retry-1",
        stage=StageName.COLLECT,
        status=ReceiptStatus.FAILED,
        project_id=first.project_id,
        factory_id="F001",
        task_family="packaging-detection",
        message="synthetic failure",
    )
    with_receipt = service.record_stage_receipt(first.project_id, stage_receipt)
    retried = service.save_draft(preview)
    assert retried.created_at == created_at
    assert retried.receipts == with_receipt.receipts


def test_authenticated_factory_context_is_required_to_match_requested_scope() -> None:
    service = VisionTrainingService()
    with pytest.raises(ScopeViolationError):
        service.preview(
            spec(factory_id="F001"),
            factory_context=FactoryContext(factory_id="F002", actor_id="user-1"),
        )


def test_specialized_template_requires_explicit_factory_binding() -> None:
    service = VisionTrainingService()
    with pytest.raises(ScopeViolationError, match="no explicit binding"):
        service.preview(spec(factory_id="F006", task_family="liushanmen-tray-label-detection"))

    bound = VisionTrainingService(
        specialized_factory_allowlist={"liushanmen-tray": ("F006",)},
    )
    preview = bound.preview(
        spec(factory_id="F006", task_family="liushanmen-tray-label-detection")
    )
    bound.save_draft(preview)
    with pytest.raises(ScopeViolationError, match="no explicit binding"):
        VisionTrainingService().save_draft(preview)


def test_asset_validation_blocks_cross_factory_and_unreviewed_data() -> None:
    service = VisionTrainingService()
    project = make_project(service)
    report = service.validate_assets(
        project.project_id,
        (
            receipt(project.project_id, "dataset", factory_id="F002", unreviewed_count=2),
            receipt(project.project_id, "model", factory_id="F002", training_success=True),
            receipt(project.project_id, "evaluation", factory_id="F002", independent=True),
        ),
    )
    assert not report.valid
    assert any("crosses factory" in error for error in report.errors)
    assert any("unreviewed" in error for error in report.errors)


def test_training_success_does_not_auto_accept_or_enable() -> None:
    service = VisionTrainingService()
    project = make_project(service)
    assets = (
        receipt(project.project_id, "dataset"),
        receipt(project.project_id, "model", training_success=True),
        receipt(project.project_id, "evaluation", independent=True),
    )
    plan = service.stage_plan(project.project_id, assets)
    assert plan.training_success is True
    assert plan.accepted is False
    assert plan.enabled is False
    assert plan.status.value == "evaluated"
    assert any("not inferred" in blocker for blocker in plan.blockers)


def test_invalid_asset_scope_cannot_report_successful_train_or_acceptance_items() -> None:
    service = VisionTrainingService()
    project = make_project(service)
    assets = (
        receipt(project.project_id, "dataset"),
        receipt(project.project_id, "model", training_success=True),
        receipt(
            project.project_id,
            "evaluation",
            factory_id="F999",
            independent=True,
            acceptance_status="passed",
        ),
    )
    plan = service.stage_plan(project.project_id, assets)
    by_stage = {item.stage: item for item in plan.items}
    assert plan.status.value == "blocked"
    assert plan.training_success is False
    assert plan.accepted is False
    assert by_stage[StageName.TRAIN].status is not ReceiptStatus.SUCCEEDED
    assert by_stage[StageName.EVALUATE].status is not ReceiptStatus.SUCCEEDED
    assert by_stage[StageName.INDEPENDENT_ACCEPTANCE].status is not ReceiptStatus.SUCCEEDED


def test_failed_evaluation_is_explicitly_failed_and_never_accepted() -> None:
    service = VisionTrainingService()
    project = make_project(service)
    failed_evaluation = receipt(
        project.project_id,
        "evaluation",
        independent=True,
        acceptance_status="failed",
    )
    assets = (
        receipt(project.project_id, "dataset"),
        receipt(project.project_id, "model", training_success=True),
        failed_evaluation,
    )
    plan = service.stage_plan(project.project_id, assets)
    by_stage = {item.stage: item for item in plan.items}
    assert plan.status.value == "blocked"
    assert by_stage[StageName.EVALUATE].status is ReceiptStatus.FAILED
    assert plan.accepted is False


def test_independent_pass_is_explicit_but_enablement_remains_separate() -> None:
    service = VisionTrainingService()
    project = make_project(service)
    assets = (
        receipt(project.project_id, "dataset"),
        receipt(project.project_id, "model", training_success=True),
        receipt(project.project_id, "evaluation", independent=True, acceptance_status="passed"),
    )
    plan = service.stage_plan(project.project_id, assets)
    assert plan.accepted is True
    assert plan.enabled is False
    assert plan.items[-1].stage is StageName.ENABLE
    assert plan.items[-1].status is ReceiptStatus.PENDING


def test_stage_receipt_failure_is_read_back_and_idempotent() -> None:
    service = VisionTrainingService()
    project = make_project(service)
    failed = StageReceipt(
        receipt_id="collect-fail-1",
        stage=StageName.COLLECT,
        status=ReceiptStatus.FAILED,
        project_id=project.project_id,
        factory_id="F001",
        task_family="packaging-detection",
        message="synthetic failure",
    )
    updated = service.record_stage_receipt(project.project_id, failed)
    service.record_stage_receipt(project.project_id, failed)
    assert updated.receipts[0].status is ReceiptStatus.FAILED
    assert service.stage_plan(project.project_id).items[1].status is ReceiptStatus.FAILED


class FakeRunner:
    @staticmethod
    def _receipt(draft, stage, **extra):
        return {
            "receipt_id": f"{stage}-1",
            "stage": stage,
            "status": "succeeded",
            "project_id": draft.project_id,
            "factory_id": draft.spec.factory_id,
            "task_family": draft.spec.task_family,
            **extra,
        }

    def collect(self, draft):
        return self._receipt(draft, "collect", message="fake collect")

    def build_dataset(self, draft):
        return self._receipt(draft, "dataset", message="fake dataset")

    def train(self, draft):
        return self._receipt(draft, "train", training_executed=True, message="fake train")

    def evaluate(self, draft):
        return self._receipt(draft, "evaluate", message="fake evaluate")

    def deploy(self, draft):
        raise AssertionError("deploy must not be called by this test")


def test_liushanmen_adapter_is_opt_in_and_has_no_default_weight_or_runner() -> None:
    service = VisionTrainingService(
        specialized_factory_allowlist={"liushanmen-tray": ("F006",)},
    )
    project = service.save_draft(service.preview(spec(factory_id="F006", task_family="liushanmen-tray-label-detection")))
    with pytest.raises(ScopeViolationError, match="no explicit binding"):
        LiushanmenVisionLabAdapter().run_stage(project, StageName.TRAIN)
    adapter = LiushanmenVisionLabAdapter(allowed_factory_ids=("F006",))
    pending = adapter.run_stage(project, StageName.TRAIN)
    assert pending.status is ReceiptStatus.PENDING
    assert pending.training_executed is False
    assert not hasattr(adapter, "model_path")

    registry = AdapterRegistry()
    registry.register(LiushanmenVisionLabAdapter(FakeRunner(), allowed_factory_ids=("F006",)))
    assert registry.for_template(project.template).adapter_id == "liushanmen-visionlab-v1"
    fake_receipt = registry.for_template(project.template).run_stage(project, StageName.TRAIN)
    assert fake_receipt.status is ReceiptStatus.SUCCEEDED
    with pytest.raises(AdapterNotRegisteredError):
        AdapterRegistry().for_template(project.template)


def test_adapter_cannot_rewrite_cross_scope_receipt_or_fake_training_success() -> None:
    class CrossScopeRunner(FakeRunner):
        def train(self, draft):
            return {
                "receipt_id": "train-cross",
                "stage": "train",
                "status": "succeeded",
                "project_id": draft.project_id,
                "factory_id": "F999",
                "task_family": draft.spec.task_family,
                "training_executed": True,
            }

    class FakeUnexecutedRunner(FakeRunner):
        def train(self, draft):
            return self._receipt(draft, "train", training_executed=False)

    service = VisionTrainingService(
        specialized_factory_allowlist={"liushanmen-tray": ("F006",)},
    )
    project = service.save_draft(
        service.preview(spec(factory_id="F006", task_family="liushanmen-tray-label-detection"))
    )
    cross = LiushanmenVisionLabAdapter(CrossScopeRunner(), allowed_factory_ids=("F006",))
    with pytest.raises(ScopeViolationError, match="factory_id"):
        cross.run_stage(project, StageName.TRAIN)
    unexecuted = LiushanmenVisionLabAdapter(FakeUnexecutedRunner(), allowed_factory_ids=("F006",))
    with pytest.raises(InvalidTaskSpecError, match="training_executed=true"):
        unexecuted.run_stage(project, StageName.TRAIN)


def test_asset_hash_and_scope_limits_are_fail_closed() -> None:
    from pydantic import ValidationError

    with pytest.raises(ValidationError):
        invalid = receipt("project", "dataset").model_dump()
        invalid["sha256"] = "short"
        AssetReceipt(**invalid)
    with pytest.raises(ValidationError):
        TaskSpec(
            **{
                **spec().model_dump(),
                "product_scope": tuple(f"SKU-{index}" for index in range(101)),
            }
        )
