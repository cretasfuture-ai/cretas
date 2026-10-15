from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import time

import pytest

from vision_training import FactoryContext, TaskSpec, VisionTrainingService
from vision_training.errors import ScopeViolationError
from vision_training.models import AssetReceipt, JobReceipt, JobStatus, ReceiptStatus, StageName, StageReceipt
from vision_training.queue import (
    QueueConflictError,
    QueueLeaseError,
    VisionJobStore,
    build_identity,
    receipt_hash,
)


def make_draft(service: VisionTrainingService, factory: str = "F001"):
    spec = TaskSpec(
        factory_id=factory,
        workstation_id="ws-1",
        task_family="liushanmen-tray-label-detection",
        product_scope=("SKU-A",),
        packaging_scope=("tray",),
        data_version="data-v1",
        recipe_version="recipe-v1",
        human_standard_version="human-v1",
        independent_acceptance_version="accept-v1",
    )
    context = FactoryContext(factory_id=factory, actor_id="1", roles=("quality",))
    return service.save_draft(
        service.preview(spec, template_id="liushanmen-tray", factory_context=context),
        factory_context=context,
    )


def signed_receipt(job, *, status: ReceiptStatus = ReceiptStatus.SUCCEEDED, factory: str | None = None):
    stage = StageReceipt(
        receipt_id=f"{job.job_id}-receipt",
        stage=job.stage,
        status=status,
        project_id=job.identity.project_id,
        factory_id=factory or job.identity.factory_id,
        task_family=job.identity.task_family,
        training_executed=job.stage == StageName.TRAIN and status == ReceiptStatus.SUCCEEDED,
        message="synthetic receipt",
    )
    assets = ()
    if status == ReceiptStatus.SUCCEEDED and job.stage == StageName.TRAIN:
        assets = (AssetReceipt(
            receipt_id=f"{job.job_id}-model",
            kind="model", status=status, factory_id=factory or job.identity.factory_id,
            task_id=job.identity.project_id, task_family=job.identity.task_family,
            data_version=job.identity.data_version, recipe_version=job.identity.recipe_version,
            human_standard_version=job.identity.human_standard_version, sha256="3" * 64,
            training_success=True, acceptance_status="pending",
        ),)
    result = JobReceipt(
        job_id=job.job_id,
        identity=job.identity,
        stage=job.stage,
        status=status,
        receipt_hash="0" * 64,
        stage_receipt=stage,
        asset_receipts=assets,
    )
    return result.model_copy(update={"receipt_hash": receipt_hash(result)})


def seed_dataset(store: VisionJobStore, draft) -> None:
    dataset = store.enqueue(draft, stage=StageName.DATASET, config_hash="1" * 64, input_hash="2" * 64)
    claimed = store.claim_next(worker_id="dataset-worker", allowed_factory_ids=(draft.spec.factory_id,))
    assert claimed and claimed.job_id == dataset.job_id
    stage = StageReceipt(
        receipt_id=f"{dataset.job_id}-receipt", stage=StageName.DATASET, status=ReceiptStatus.SUCCEEDED,
        project_id=draft.project_id, factory_id=draft.spec.factory_id, task_family=draft.spec.task_family,
    )
    asset = AssetReceipt(
        receipt_id=f"{dataset.job_id}-dataset", kind="dataset", status=ReceiptStatus.SUCCEEDED,
        factory_id=draft.spec.factory_id, task_id=draft.project_id, task_family=draft.spec.task_family,
        data_version=draft.spec.data_version, recipe_version=draft.spec.recipe_version,
        human_standard_version=draft.spec.human_standard_version, sha256="4" * 64,
        reviewed_only=True, unreviewed_count=0, protected_holdout_excluded=True,
        acceptance_status="pending",
    )
    receipt = JobReceipt(
        job_id=dataset.job_id, identity=dataset.identity, stage=StageName.DATASET,
        status=ReceiptStatus.SUCCEEDED, receipt_hash="0" * 64, stage_receipt=stage,
        asset_receipts=(asset,),
    )
    store.complete(job_id=dataset.job_id, worker_id="dataset-worker", receipt=receipt.model_copy(update={"receipt_hash": receipt_hash(receipt)}))


def test_enqueue_is_idempotent_and_different_input_is_rejected(tmp_path):
    service = VisionTrainingService(specialized_factory_allowlist={"liushanmen-tray": ("F001",)})
    draft = make_draft(service)
    store = VisionJobStore(tmp_path / "jobs.db")
    seed_dataset(store, draft)
    with pytest.raises(QueueConflictError, match="source asset hash"):
        store.enqueue(
            draft, stage=StageName.TRAIN, config_hash="1" * 64, input_hash="2" * 64,
            source_asset_sha256="5" * 64,
        )
    first = store.enqueue(draft, stage=StageName.TRAIN, config_hash="1" * 64, input_hash="2" * 64, source_asset_sha256="4" * 64)
    second = store.enqueue(draft, stage=StageName.TRAIN, config_hash="1" * 64, input_hash="2" * 64, source_asset_sha256="4" * 64)
    assert first.job_id == second.job_id and first.created_at == second.created_at
    with pytest.raises(QueueConflictError):
        store.enqueue(draft, stage=StageName.TRAIN, config_hash="1" * 64, input_hash="3" * 64, source_asset_sha256="4" * 64)
    store.close()


def test_claim_is_single_winner_and_wrong_factory_cannot_claim(tmp_path):
    service = VisionTrainingService(specialized_factory_allowlist={"liushanmen-tray": ("F001",)})
    draft = make_draft(service)
    path = tmp_path / "jobs.db"
    initial = VisionJobStore(path)
    seed_dataset(initial, draft)
    initial.enqueue(draft, stage=StageName.TRAIN, config_hash="1" * 64, input_hash="2" * 64, source_asset_sha256="4" * 64)
    initial.close()

    def claim(worker: str):
        store = VisionJobStore(path)
        try:
            result = store.claim_next(worker_id=worker, allowed_factory_ids=("F001",))
            return result.job_id if result else None
        finally:
            store.close()

    with ThreadPoolExecutor(max_workers=2) as pool:
        winners = list(pool.map(claim, ("w1", "w2")))
    assert [value for value in winners if value] == [winners[0] if winners[0] else winners[1]]
    foreign = VisionJobStore(path)
    assert foreign.claim_next(worker_id="foreign", allowed_factory_ids=("F999",)) is None
    foreign.close()


def test_expired_lease_is_frozen_and_never_reclaimed(tmp_path):
    service = VisionTrainingService(specialized_factory_allowlist={"liushanmen-tray": ("F001",)})
    draft = make_draft(service)
    path = tmp_path / "jobs.db"
    store = VisionJobStore(path)
    seed_dataset(store, draft)
    queued = store.enqueue(draft, stage=StageName.TRAIN, config_hash="1" * 64, input_hash="2" * 64, source_asset_sha256="4" * 64)
    claimed = store.claim_next(worker_id="w1", allowed_factory_ids=("F001",), lease_seconds=1)
    assert claimed and claimed.job_id == queued.job_id
    time.sleep(1.1)
    assert store.claim_next(worker_id="w2", allowed_factory_ids=("F001",)) is None
    frozen = store.get(queued.job_id)
    assert frozen and frozen.status is JobStatus.FAILED
    with pytest.raises(QueueLeaseError):
        store.complete(job_id=queued.job_id, worker_id="w1", receipt=signed_receipt(claimed))
    store.close()


def test_complete_rejects_forged_scope_or_hash_and_persists_receipt(tmp_path):
    service = VisionTrainingService(specialized_factory_allowlist={"liushanmen-tray": ("F001",)})
    draft = make_draft(service)
    store = VisionJobStore(tmp_path / "jobs.db")
    seed_dataset(store, draft)
    queued = store.enqueue(draft, stage=StageName.TRAIN, config_hash="1" * 64, input_hash="2" * 64, source_asset_sha256="4" * 64)
    claimed = store.claim_next(worker_id="w1", allowed_factory_ids=("F001",))
    assert claimed
    forged = signed_receipt(claimed, factory="F999")
    with pytest.raises(ScopeViolationError):
        store.complete(job_id=queued.job_id, worker_id="w1", receipt=forged)
    forged_hash = signed_receipt(claimed).model_copy(update={"receipt_hash": "f" * 64})
    with pytest.raises(ScopeViolationError):
        store.complete(job_id=queued.job_id, worker_id="w1", receipt=forged_hash)
    valid_receipt = signed_receipt(claimed)
    result = store.complete(job_id=queued.job_id, worker_id="w1", receipt=valid_receipt)
    assert result.status is JobStatus.SUCCEEDED
    assert result.receipt is not None and result.receipt.receipt_hash == receipt_hash(result.receipt)
    resent = store.complete(job_id=queued.job_id, worker_id="w1", receipt=valid_receipt)
    assert resent.job_id == result.job_id and resent.receipt == result.receipt
    with pytest.raises(QueueLeaseError):
        store.complete(job_id=queued.job_id, worker_id="other-worker", receipt=valid_receipt)
    store.close()


def test_successful_artifact_stage_requires_one_evidenced_asset(tmp_path):
    service = VisionTrainingService(specialized_factory_allowlist={"liushanmen-tray": ("F001",)})
    draft = make_draft(service)
    store = VisionJobStore(tmp_path / "jobs.db")
    dataset = store.enqueue(draft, stage=StageName.DATASET, config_hash="1" * 64, input_hash="2" * 64)
    claimed = store.claim_next(worker_id="w1", allowed_factory_ids=("F001",))
    assert claimed and claimed.job_id == dataset.job_id
    receipt = JobReceipt(
        job_id=dataset.job_id, identity=dataset.identity, stage=StageName.DATASET,
        status=ReceiptStatus.SUCCEEDED, receipt_hash="0" * 64,
        stage_receipt=StageReceipt(
            receipt_id="missing-asset", stage=StageName.DATASET, status=ReceiptStatus.SUCCEEDED,
            project_id=draft.project_id, factory_id="F001", task_family=draft.spec.task_family,
        ),
    )
    with pytest.raises(ScopeViolationError, match="exactly one dataset"):
        store.complete(job_id=dataset.job_id, worker_id="w1", receipt=receipt.model_copy(update={"receipt_hash": receipt_hash(receipt)}))


def test_queue_receipt_is_the_plan_readback_source_and_completion_resend_is_idempotent(tmp_path):
    store = VisionJobStore(tmp_path / "jobs.db")
    service = VisionTrainingService(
        store=None,
        specialized_factory_allowlist={"liushanmen-tray": ("F001",)},
        job_store=store,
    )
    draft = make_draft(service)
    seed_dataset(store, draft)
    dataset_readback = service.readback(draft.project_id)
    assert any(item.kind == "dataset" for item in dataset_readback.asset_receipts)
    train = store.enqueue(draft, stage=StageName.TRAIN, config_hash="1" * 64, input_hash="2" * 64, source_asset_sha256="4" * 64)
    claimed = store.claim_next(worker_id="w1", allowed_factory_ids=("F001",))
    assert claimed and claimed.job_id == train.job_id
    receipt = signed_receipt(claimed)
    store.complete(job_id=train.job_id, worker_id="w1", receipt=receipt)
    store.complete(job_id=train.job_id, worker_id="w1", receipt=receipt)
    readback = service.readback(draft.project_id)
    assert any(item.stage == StageName.TRAIN for item in readback.receipts)
    assert any(item.kind == "model" for item in readback.asset_receipts)


def test_evaluate_requires_the_project_model_asset_hash(tmp_path):
    store = VisionJobStore(tmp_path / "jobs.db")
    service = VisionTrainingService(
        store=None,
        specialized_factory_allowlist={"liushanmen-tray": ("F001",)},
        job_store=store,
    )
    draft = make_draft(service)
    seed_dataset(store, draft)
    train = store.enqueue(
        draft, stage=StageName.TRAIN, config_hash="1" * 64, input_hash="2" * 64,
        source_asset_sha256="4" * 64,
    )
    claimed = store.claim_next(worker_id="w1", allowed_factory_ids=("F001",))
    assert claimed and claimed.job_id == train.job_id
    store.complete(job_id=train.job_id, worker_id="w1", receipt=signed_receipt(claimed))
    with pytest.raises(QueueConflictError, match="source asset hash"):
        store.enqueue(
            draft, stage=StageName.EVALUATE, config_hash="1" * 64, input_hash="2" * 64,
            source_asset_sha256="4" * 64,
        )


def test_generic_template_is_blocked_without_a_named_runner(tmp_path):
    service = VisionTrainingService()
    spec = TaskSpec(
        factory_id="F001", workstation_id="ws-1", task_family="packaging-detection",
        product_scope=("SKU-A",), packaging_scope=("bag",), data_version="d1",
        recipe_version="r1", human_standard_version="h1", independent_acceptance_version="a1",
    )
    draft = service.save_draft(service.preview(spec))
    job = VisionJobStore(tmp_path / "jobs.db").enqueue(
        draft, stage=StageName.TRAIN, config_hash="1" * 64, input_hash="2" * 64, source_asset_sha256="4" * 64,
    )
    assert job.status is JobStatus.BLOCKED
    assert job.blocked_reason == "NO_NAMED_RUNNER_REGISTERED_FOR_TEMPLATE"
