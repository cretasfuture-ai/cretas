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
        sha256=f"sha-{kind}",
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

    altered = preview.model_copy(update={"spec": preview.spec.model_copy(update={"data_version": "photos-v4"})})
    with pytest.raises(PreviewMismatchError):
        service.save_draft(altered)


def test_authenticated_factory_context_is_required_to_match_requested_scope() -> None:
    service = VisionTrainingService()
    with pytest.raises(ScopeViolationError):
        service.preview(
            spec(factory_id="F001"),
            factory_context=FactoryContext(factory_id="F002", actor_id="user-1"),
        )


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
    def collect(self, draft):
        return {"receipt_id": "collect-1", "status": "succeeded", "message": "fake collect"}

    def build_dataset(self, draft):
        return {"receipt_id": "dataset-1", "status": "succeeded", "message": "fake dataset"}

    def train(self, draft):
        return {"receipt_id": "train-1", "status": "succeeded", "training_executed": False, "message": "not run"}

    def evaluate(self, draft):
        return {"receipt_id": "eval-1", "status": "pending", "message": "not run"}

    def deploy(self, draft):
        raise AssertionError("deploy must not be called by this test")


def test_liushanmen_adapter_is_opt_in_and_has_no_default_weight_or_runner() -> None:
    service = VisionTrainingService()
    project = service.save_draft(service.preview(spec(factory_id="F006", task_family="liushanmen-tray-label-detection")))
    adapter = LiushanmenVisionLabAdapter()
    pending = adapter.run_stage(project, StageName.TRAIN)
    assert pending.status is ReceiptStatus.PENDING
    assert pending.training_executed is False
    assert not hasattr(adapter, "model_path")

    registry = AdapterRegistry()
    registry.register(LiushanmenVisionLabAdapter(FakeRunner()))
    assert registry.for_template(project.template).adapter_id == "liushanmen-visionlab-v1"
    fake_receipt = registry.for_template(project.template).run_stage(project, StageName.TRAIN)
    assert fake_receipt.status is ReceiptStatus.SUCCEEDED
    with pytest.raises(AdapterNotRegisteredError):
        AdapterRegistry().for_template(project.template)
