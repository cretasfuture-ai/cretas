from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
from fastapi import FastAPI, Request
from fastapi.testclient import TestClient
from starlette.middleware.base import BaseHTTPMiddleware

from vision_training import (
    FactoryContext,
    TaskSpec,
    VisionTrainingService,
    VisionJobStore,
    WorkerBinding,
    create_platform_router,
    create_worker_router,
    make_worker_context_dependency,
    create_file_backed_runtime,
    safe_example_registration,
)
from vision_training.models import AssetReceipt, JobIdentity, JobReceipt, ReceiptStatus, StageName, StageReceipt, VisionJob
from vision_training.queue import receipt_hash
from vision_training.router import create_router, require_platform_context


TOOLS = Path(__file__).resolve().parents[3] / "tools" / "vision-lab"
sys.path.insert(0, str(TOOLS))
import sop_worker  # noqa: E402


def spec(factory: str = "F001") -> TaskSpec:
    return TaskSpec(
        factory_id=factory, workstation_id="ws-1", task_family="liushanmen-tray-label-detection",
        product_scope=("SKU-A",), packaging_scope=("tray",), data_version="d1",
        recipe_version="r1", human_standard_version="h1", independent_acceptance_version="a1",
    )


def draft_for(service: VisionTrainingService, factory: str = "F001"):
    context = FactoryContext(factory_id=factory, actor_id="1", roles=("quality",))
    preview = service.preview(spec(factory), template_id="liushanmen-tray", factory_context=context)
    return service.save_draft(preview, factory_context=context)


def seed_dataset(store: VisionJobStore, draft) -> None:
    dataset = store.enqueue(draft, stage=StageName.DATASET, config_hash="1" * 64, input_hash="2" * 64)
    claimed = store.claim_next(worker_id="dataset-worker", allowed_factory_ids=(draft.spec.factory_id,))
    stage = StageReceipt(
        receipt_id=f"{dataset.job_id}-receipt", stage=StageName.DATASET, status=ReceiptStatus.SUCCEEDED,
        project_id=draft.project_id, factory_id=draft.spec.factory_id, task_family=draft.spec.task_family,
    )
    asset = AssetReceipt(
        receipt_id=f"{dataset.job_id}-dataset", kind="dataset", status=ReceiptStatus.SUCCEEDED,
        factory_id=draft.spec.factory_id, task_id=draft.project_id, task_family=draft.spec.task_family,
        data_version=draft.spec.data_version, recipe_version=draft.spec.recipe_version,
        human_standard_version=draft.spec.human_standard_version, sha256="4" * 64,
        reviewed_only=True, protected_holdout_excluded=True,
    )
    receipt = JobReceipt(
        job_id=dataset.job_id, identity=dataset.identity, stage=StageName.DATASET,
        status=ReceiptStatus.SUCCEEDED, receipt_hash="0" * 64, stage_receipt=stage,
        asset_receipts=(asset,),
    )
    store.complete(job_id=dataset.job_id, worker_id="dataset-worker", receipt=receipt.model_copy(update={"receipt_hash": receipt_hash(receipt)}))


def test_factory_context_defaults_to_factory_user():
    context = FactoryContext(factory_id="F001", actor_id="1")
    assert context.actor_type == "FACTORY_USER"


def test_runtime_factory_is_lazy_and_safe_defaults_have_no_bindings(tmp_path):
    assert safe_example_registration()["worker_bindings"] == []
    draft_root = tmp_path / "drafts"
    runtime = create_file_backed_runtime(draft_root=draft_root, job_db=tmp_path / "jobs.db")
    assert not draft_root.exists()
    assert not (tmp_path / "jobs.db").exists()
    runtime.startup_probe()
    assert (tmp_path / "jobs.db").exists()
    assert runtime.routers.factory.prefix == "/api/vision-training"
    assert runtime.routers.platform.prefix == "/api/vision-training/platform"
    runtime.close()
    unopened = create_file_backed_runtime(draft_root=tmp_path / "unused-drafts", job_db=tmp_path / "never.db")
    unopened.close()
    assert not (tmp_path / "never.db").exists()


def test_worker_main_returns_nonzero_for_failed_once(monkeypatch, capsys):
    monkeypatch.setattr(sop_worker.WorkerRegistration, "from_json", lambda _path: object())
    monkeypatch.setattr(
        sop_worker,
        "run_once",
        lambda _registration: {"status": "completed", "job_status": "failed", "data": {}},
    )
    assert sop_worker.main(["--registration", "unused.json", "--once"]) == 2
    assert '"job_status": "failed"' in capsys.readouterr().out


def test_platform_context_requires_internal_namespace_and_target_match():
    scope = {"type": "http", "path": "/api/vision-training/platform/factories/F001/jobs", "headers": [], "query_string": b"", "method": "GET", "scheme": "http", "client": ("test", 1), "server": ("test", 80), "state": {"auth_method": "internal", "role": "super_admin", "factory_id": "F001", "user_id": 9}}
    request = Request(scope)
    request.headers  # force immutable headers construction
    with pytest.raises(Exception):
        # Missing explicit actor type must fail closed.
        require_platform_context(request, "F001")
    scope["headers"] = [(b"x-actor-type", b"PLATFORM_ADMIN")]
    context = require_platform_context(Request(scope), "F001")
    assert context.actor_type == "PLATFORM_ADMIN" and context.factory_id == "F001"
    with pytest.raises(Exception):
        require_platform_context(Request({**scope, "state": {**scope["state"], "factory_id": "F002"}}), "F001")


def test_factory_api_contract_lists_recipes_drafts_and_queues_without_paths(tmp_path):
    service = VisionTrainingService()
    recipe = {
        "recipe_id": "recipe-1", "factory_id": "F001", "task_family": "packaging-detection",
        "recipe_version": "r1", "data_version": "d1", "human_standard_version": "h1",
        "independent_acceptance_version": "a1", "ocr_rules_version": None, "available": True,
        "template_id": "generic-packaging-detection", "template_version": "v1", "sop_version": "vision-training-sop-v1",
        "config_sha256": "1" * 64, "stage_input_hashes": {"train": "2" * 64},
    }

    def trusted_context(request: Request):
        return FactoryContext(factory_id="F001", actor_id="1", roles=("quality",))

    store = VisionJobStore(tmp_path / "jobs.db")
    from vision_training.models import RecipeAvailability
    app = FastAPI()
    app.include_router(create_router(
        service,
        factory_context_dependency=trusted_context,
        job_store=store,
        recipe_catalog=(RecipeAvailability(**recipe),),
    ))
    client = TestClient(app)
    payload = {
        "spec": {
            "factory_id": "F001", "workstation_id": "ws-1", "task_family": "packaging-detection",
            "product_scope": ["SKU-A"], "packaging_scope": ["bag"], "data_version": "d1",
            "recipe_version": "r1", "recipe_id": "recipe-1", "human_standard_version": "h1",
            "independent_acceptance_version": "a1",
        }
    }
    preview = client.post("/api/vision-training/preview", json=payload).json()["data"]
    saved = client.post("/api/vision-training/drafts", json={"preview": preview}).json()["data"]
    assert client.get("/api/vision-training/drafts").json()["data"][0]["project_id"] == saved["project_id"]
    assert client.get("/api/vision-training/recipes").json()["data"][0]["recipe_id"] == "recipe-1"
    queued = client.post(
        f"/api/vision-training/drafts/{saved['project_id']}/jobs",
        json={"stage": "train", "config_hash": "1" * 64, "input_hash": "2" * 64, "recipe_id": "recipe-1"},
    )
    assert queued.status_code == 200
    job_id = queued.json()["data"]["job_id"]
    assert client.get(f"/api/vision-training/jobs/{job_id}").json()["data"]["identity"]["factory_id"] == "F001"
    assert client.get(f"/api/vision-training/drafts/{saved['project_id']}/jobs").json()["data"][0]["job_id"] == job_id
    assert "path" not in queued.text.lower() and "command" not in queued.text.lower()


def test_platform_router_has_separate_internal_planning_namespace(tmp_path):
    service = VisionTrainingService()
    store = VisionJobStore(tmp_path / "jobs.db")

    class InternalPlatformState(BaseHTTPMiddleware):
        async def dispatch(self, request, call_next):
            request.state.auth_method = "internal"
            request.state.role = "super_admin"
            request.state.factory_id = "F001"
            request.state.user_id = 9
            return await call_next(request)

    app = FastAPI()
    app.add_middleware(InternalPlatformState)
    app.include_router(create_platform_router(service, store))
    client = TestClient(app)
    headers = {"X-Actor-Type": "PLATFORM_ADMIN"}
    body = {
        "spec": {
            "factory_id": "F001", "workstation_id": "ws-1", "task_family": "packaging-detection",
            "product_scope": ["SKU-A"], "packaging_scope": ["bag"], "data_version": "d1",
            "recipe_version": "r1", "human_standard_version": "h1", "independent_acceptance_version": "a1",
        }
    }
    preview = client.post("/api/vision-training/platform/factories/F001/preview", json=body, headers=headers)
    assert preview.status_code == 200
    saved = client.post(
        "/api/vision-training/platform/factories/F001/drafts",
        json={"preview": preview.json()["data"]}, headers=headers,
    )
    assert saved.status_code == 200
    assert client.get("/api/vision-training/platform/factories/F001/drafts", headers=headers).status_code == 200
    assert client.get("/api/vision-training/platform/factories/F001/templates", headers=headers).status_code == 200
    assert client.get("/api/vision-training/platform/factories/F002/templates", headers=headers).status_code == 403


def test_worker_dependency_rejects_jwt_and_wrong_named_token():
    dependency = make_worker_context_dependency((WorkerBinding("w1", "token-1", frozenset({"F001"})),))

    class StateMiddleware(BaseHTTPMiddleware):
        async def dispatch(self, request, call_next):
            request.state.auth_method = "jwt"
            return await call_next(request)

    app = FastAPI()
    app.add_middleware(StateMiddleware)
    app.include_router(create_worker_router(VisionTrainingService(), VisionJobStore(":memory:"), worker_context_dependency=dependency))
    assert TestClient(app).post("/api/vision-training/worker/jobs/claim", headers={"X-Worker-Id": "w1", "X-Worker-Token": "token-1"}).status_code == 401


def test_worker_complete_persists_receipt_and_worker_binding(tmp_path):
    service = VisionTrainingService(specialized_factory_allowlist={"liushanmen-tray": ("F001",)})
    draft = draft_for(service)
    store = VisionJobStore(tmp_path / "jobs.db")
    seed_dataset(store, draft)
    job = store.enqueue(draft, stage=StageName.TRAIN, config_hash="1" * 64, input_hash="2" * 64, source_asset_sha256="4" * 64)

    class InternalState(BaseHTTPMiddleware):
        async def dispatch(self, request, call_next):
            request.state.auth_method = "internal"
            return await call_next(request)

    dependency = make_worker_context_dependency((WorkerBinding("w1", "token-1", frozenset({"F001"})),))
    app = FastAPI()
    app.add_middleware(InternalState)
    app.include_router(create_worker_router(service, store, worker_context_dependency=dependency))
    client = TestClient(app)
    headers = {"X-Worker-Id": "w1", "X-Worker-Token": "token-1"}
    claimed = client.post("/api/vision-training/worker/jobs/claim", headers=headers)
    assert claimed.status_code == 200
    claimed_data = claimed.json()["data"]
    stage = StageReceipt(
        receipt_id="worker-train-1", stage="train", status="succeeded",
        project_id=draft.project_id, factory_id="F001", task_family=draft.spec.task_family,
        training_executed=True,
    )
    receipt = JobReceipt(
        job_id=job.job_id, identity=job.identity, stage="train", status="succeeded",
        receipt_hash="0" * 64, stage_receipt=stage,
        asset_receipts=(AssetReceipt(
            receipt_id="worker-model-1", kind="model", status="succeeded", factory_id="F001",
            task_id=draft.project_id, task_family=draft.spec.task_family, data_version=draft.spec.data_version,
            recipe_version=draft.spec.recipe_version, human_standard_version=draft.spec.human_standard_version,
            sha256="3" * 64, training_success=True,
        ),),
    )
    receipt = receipt.model_copy(update={"receipt_hash": receipt_hash(receipt)})
    completed = client.post(
        f"/api/vision-training/worker/jobs/{job.job_id}/complete",
        headers=headers,
        json={"job_id": job.job_id, "receipt": receipt.model_dump(mode="json")},
    )
    assert completed.status_code == 200
    assert any(item.receipt_id == "worker-train-1" for item in service.readback(draft.project_id).receipts)
    assert store.get(job.job_id).status.value == "succeeded"


def test_worker_registration_rejects_escape_and_generic_runner(tmp_path, monkeypatch):
    monkeypatch.setattr(sop_worker.sys, "platform", "win32")
    root = tmp_path / "F001" / "project"
    root.mkdir(parents=True)
    config = root / "config.json"
    config.write_text(json.dumps({"version": "vision-lab-v1", "source": {"factory_id": "F001"}, "execution": {"mode": "local", "remote_enabled": False}}), encoding="utf-8")
    registration = sop_worker.WorkerRegistration(
        factory_id="F001", project_id="p1", adapter_id="generic-runner",
        project_root=root, config_path=config, config_sha256=hashlib.sha256(config.read_bytes()).hexdigest(),
        input_refs={"train": "datasets/train.json"},
    )
    registration.validate()
    with pytest.raises(sop_worker.WorkerBlockedError, match="generic"):
        sop_worker.VisionLabSOPWorker(registration).execute("train")
    escaping = registration.__class__(
        **{**registration.__dict__, "input_refs": {"train": "..\\outside.json"}}
    )
    with pytest.raises(sop_worker.WorkerConfigurationError, match="escapes"):
        escaping.validate()


def test_worker_collect_never_defaults_to_production_query(tmp_path, monkeypatch):
    monkeypatch.setattr(sop_worker.sys, "platform", "win32")
    root = tmp_path / "F001" / "project"
    root.mkdir(parents=True)
    config = root / "config.json"
    config.write_text(json.dumps({"version": "vision-lab-v1", "source": {"factory_id": "F001"}, "execution": {"mode": "local", "remote_enabled": False}}), encoding="utf-8")
    registration = sop_worker.WorkerRegistration(
        factory_id="F001", project_id="p1", adapter_id="liushanmen-visionlab-v1",
        project_root=root, config_path=config, config_sha256=hashlib.sha256(config.read_bytes()).hexdigest(),
        input_refs={}, allowed_source_hosts=frozenset({"trusted.example"}),
    )
    with pytest.raises(sop_worker.WorkerBlockedError, match="manifest"):
        sop_worker.VisionLabSOPWorker(registration).execute("collect")


def test_worker_job_receipt_repeats_identity_and_keeps_training_candidate_pending(tmp_path, monkeypatch):
    monkeypatch.setattr(sop_worker.sys, "platform", "win32")
    root = tmp_path / "F001" / "project"
    root.mkdir(parents=True)
    config = root / "config.json"
    config.write_text(json.dumps({
        "version": "vision-lab-v1", "source": {"factory_id": "F001"},
        "execution": {"mode": "local", "remote_enabled": False},
    }), encoding="utf-8")
    config_hash = hashlib.sha256(config.read_bytes()).hexdigest()
    input_hash = "2" * 64
    registration = sop_worker.WorkerRegistration(
        factory_id="F001", project_id="project-1", adapter_id="liushanmen-visionlab-v1",
        project_root=root, config_path=config, config_sha256=config_hash,
        input_refs={"train": "dataset.json"}, input_hashes={"train": input_hash},
    )
    worker = sop_worker.VisionLabSOPWorker(registration)
    artifact_hash = "3" * 64
    monkeypatch.setattr(worker, "execute", lambda stage: {
        "version": "vision-lab-v1", "model_id": "candidate-1", "artifact_sha256": artifact_hash,
        "artifact": str(root / "candidate.onnx"), "status": "candidate",
        "_worker_validation": {"source_asset_sha256": input_hash},
    })
    identity = JobIdentity(
        factory_id="F001", project_id="project-1", task_family="tray-labels",
        template_id="liushanmen-tray", template_version="v1", sop_version="vision-sop-v1",
        data_version="d1", recipe_version="r1", human_standard_version="h1",
        independent_acceptance_version="a1", preview_hash="4" * 64,
        config_hash=config_hash, input_hash=input_hash, source_asset_sha256=input_hash,
    )
    job = VisionJob(job_id="job-1", identity=identity, stage="train", adapter_id="liushanmen-visionlab-v1")
    receipt = worker.execute_job(job)
    assert receipt.identity == identity
    assert receipt.stage == StageName.TRAIN
    assert receipt.stage_receipt.training_executed is True
    assert receipt.asset_receipts[0].factory_id == "F001"
    assert receipt.asset_receipts[0].task_id == "project-1"
    assert receipt.asset_receipts[0].acceptance_status == "pending"
    assert receipt.asset_receipts[0].enabled is False


def test_worker_once_uses_fixed_loopback_transport_and_submits_failed_or_success_receipt(tmp_path, monkeypatch):
    monkeypatch.setattr(sop_worker.sys, "platform", "win32")
    root = tmp_path / "F001" / "project"
    root.mkdir(parents=True)
    config = root / "config.json"
    config.write_text(json.dumps({
        "version": "vision-lab-v1", "source": {"factory_id": "F001"},
        "execution": {"mode": "local", "remote_enabled": False},
    }), encoding="utf-8")
    config_hash = hashlib.sha256(config.read_bytes()).hexdigest()
    input_hash = "2" * 64
    registration = sop_worker.WorkerRegistration(
        factory_id="F001", project_id="project-1", adapter_id="liushanmen-visionlab-v1",
        project_root=root, config_path=config, config_sha256=config_hash,
        input_refs={"train": "dataset.json"}, input_hashes={"train": input_hash},
        server_url="http://127.0.0.1:0", worker_id="worker-1",
    )
    identity = JobIdentity(
        factory_id="F001", project_id="project-1", task_family="tray-labels",
        template_id="liushanmen-tray", template_version="v1", sop_version="vision-sop-v1",
        data_version="d1", recipe_version="r1", human_standard_version="h1",
        independent_acceptance_version="a1", preview_hash="4" * 64,
        config_hash=config_hash, input_hash=input_hash,
    )
    from vision_training.models import VisionJob
    job = VisionJob(job_id="job-loopback", identity=identity, stage="train", adapter_id="liushanmen-visionlab-v1")
    stage = StageReceipt(
        receipt_id="job-loopback:train", stage="train", status="failed", project_id="project-1",
        factory_id="F001", task_family="tray-labels", message="synthetic failure",
    )
    failed = JobReceipt(
        job_id=job.job_id, identity=job.identity, stage="train", status="failed",
        receipt_hash="0" * 64, stage_receipt=stage,
    )
    from vision_training.queue import receipt_hash
    failed = failed.model_copy(update={"receipt_hash": receipt_hash(failed)})
    received: list[dict] = []

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):  # noqa: N802
            length = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(length) or b"{}")
            assert self.headers.get("X-Worker-Id") == "worker-1"
            assert self.headers.get("X-Internal-Secret") == "fixture-internal-secret"
            if self.path.endswith("/claim"):
                body = {"success": True, "data": job.model_dump(mode="json")}
            elif self.path.endswith("/complete"):
                received.append(payload)
                body = {"success": True, "data": {"job_id": job.job_id, "status": "failed"}}
            else:
                body = {"success": True, "data": {}}
            encoded = json.dumps(body).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)
        def log_message(self, *_args):
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    registration = registration.__class__(**{**registration.__dict__, "server_url": f"http://127.0.0.1:{server.server_port}"})
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    monkeypatch.setenv("VISION_SOP_WORKER_TOKEN", "fixture-token")
    monkeypatch.setenv("VISION_SOP_WORKER_INTERNAL_SECRET", "fixture-internal-secret")
    monkeypatch.setattr(sop_worker.VisionLabSOPWorker, "execute_job", lambda self, claimed: failed)
    try:
        result = sop_worker.run_once(registration)
    finally:
        server.shutdown()
        thread.join(timeout=2)
    assert result["status"] == "completed"
    assert result["job_status"] == "failed"
    assert received and received[0]["receipt"]["status"] == "failed"


def test_worker_once_without_job_does_not_execute_or_make_extra_requests(tmp_path, monkeypatch):
    monkeypatch.setattr(sop_worker.sys, "platform", "win32")
    root = tmp_path / "F001" / "project"
    root.mkdir(parents=True)
    config = root / "config.json"
    config.write_text(json.dumps({
        "version": "vision-lab-v1", "source": {"factory_id": "F001"},
        "execution": {"mode": "local", "remote_enabled": False},
    }), encoding="utf-8")
    registration = sop_worker.WorkerRegistration(
        factory_id="F001", project_id="project-1", adapter_id="liushanmen-visionlab-v1",
        project_root=root, config_path=config, config_sha256=hashlib.sha256(config.read_bytes()).hexdigest(),
        input_refs={}, server_url="http://127.0.0.1:0", worker_id="worker-1",
    )
    requests: list[str] = []
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):  # noqa: N802
            requests.append(self.path)
            body = json.dumps({"success": True, "data": None}).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        def log_message(self, *_args):
            return
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    registration = registration.__class__(**{**registration.__dict__, "server_url": f"http://127.0.0.1:{server.server_port}"})
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    monkeypatch.setenv("VISION_SOP_WORKER_TOKEN", "fixture-token")
    monkeypatch.setenv("VISION_SOP_WORKER_INTERNAL_SECRET", "fixture-internal-secret")
    def should_not_run(*_args, **_kwargs):
        raise AssertionError("worker executed despite an empty claim")
    monkeypatch.setattr(sop_worker.VisionLabSOPWorker, "execute_job", should_not_run)
    try:
        result = sop_worker.run_once(registration)
    finally:
        server.shutdown()
        thread.join(timeout=2)
    assert result["status"] == "no_job"
    assert requests == ["/api/vision-training/worker/jobs/claim"]


def test_worker_rejects_mutated_registered_input_hash(tmp_path, monkeypatch):
    monkeypatch.setattr(sop_worker.sys, "platform", "win32")
    root = tmp_path / "F001" / "project"
    root.mkdir(parents=True)
    input_file = root / "manifest.json"
    input_file.write_text('{"factory_id":"F001"}', encoding="utf-8")
    registration = sop_worker.WorkerRegistration(
        factory_id="F001", project_id="p1", adapter_id="liushanmen-visionlab-v1",
        project_root=root, config_path=root / "manifest.json", config_sha256=hashlib.sha256(input_file.read_bytes()).hexdigest(),
        input_refs={"train": "manifest.json"}, input_hashes={"train": "0" * 64},
    )
    with pytest.raises(sop_worker.WorkerBlockedError, match="hash changed"):
        sop_worker._load_fixed_input(registration, "train")


def test_worker_evaluate_uses_evaluate_input_and_rechecks_model_path_hash(tmp_path, monkeypatch):
    monkeypatch.setattr(sop_worker.sys, "platform", "win32")
    root = tmp_path / "F001" / "project"
    root.mkdir(parents=True)
    artifact = root / "candidate.onnx"
    artifact.write_bytes(b"candidate")
    base = root / "base.pt"
    base.write_bytes(b"base")
    config = root / "config.json"
    config_value = {
        "version": "vision-lab-v1", "source": {"factory_id": "F001"},
        "execution": {"mode": "local", "remote_enabled": False},
        "training": {"base_model": str(base), "base_model_sha256": hashlib.sha256(base.read_bytes()).hexdigest()},
        "evaluation": {"repo_root": str(Path(sop_worker.__file__).resolve().parents[2])},
    }
    config.write_text(json.dumps(config_value), encoding="utf-8")
    config_hash = hashlib.sha256(config.read_bytes()).hexdigest()
    model = {"model_id": "eval-model", "artifact": str(artifact), "artifact_sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(), "best_pt": str(artifact)}
    evaluate_input = root / "evaluate.json"
    evaluate_input.write_text(json.dumps(model), encoding="utf-8")
    train_input = root / "train.json"
    train_input.write_text(json.dumps({"model_id": "wrong-train-model"}), encoding="utf-8")
    registration = sop_worker.WorkerRegistration(
        factory_id="F001", project_id="p1", adapter_id="liushanmen-visionlab-v1",
        project_root=root, config_path=config, config_sha256=config_hash,
        input_refs={"train": "train.json", "evaluate": "evaluate.json"},
        input_hashes={"train": hashlib.sha256(train_input.read_bytes()).hexdigest(), "evaluate": hashlib.sha256(evaluate_input.read_bytes()).hexdigest()},
    )
    captured: dict[str, object] = {}
    metrics_path = root / "metrics.json"
    class FakeVisionLab:
        @staticmethod
        def assert_local_execution(_config):
            return None
        @staticmethod
        def evaluate_candidate(_config, candidate):
            captured["model_id"] = candidate["model_id"]
            metrics = {"artifact_sha256": model["artifact_sha256"], "production_pipeline_replay": {"status": "fixture"}}
            metrics_path.write_text(json.dumps(metrics), encoding="utf-8")
            return metrics_path, metrics
        @staticmethod
        def evaluate_gate(_config, _candidate, _path):
            return {"passed": True}
    monkeypatch.setattr(sop_worker, "_load_vision_lab", lambda: FakeVisionLab)
    result = sop_worker.VisionLabSOPWorker(registration).execute("evaluate")
    assert captured["model_id"] == "eval-model"
    assert result["metrics"]["artifact_sha256"] == model["artifact_sha256"]


def test_dataset_receipt_uses_real_manifest_and_human_evidence_hash(tmp_path):
    root = tmp_path / "F001" / "project"
    queue_root = root / "queue"
    dataset_root = root / "datasets" / "d1"
    (queue_root / "annotations-human").mkdir(parents=True)
    (dataset_root / "images" / "train").mkdir(parents=True)
    (dataset_root / "images" / "val").mkdir(parents=True)
    (dataset_root / "labels" / "train").mkdir(parents=True)
    image = dataset_root / "images" / "train" / "crop-1.jpg"
    label = dataset_root / "labels" / "train" / "crop-1.txt"
    image.write_bytes(b"image")
    label.write_text("0 0.5 0.5 0.2 0.2\n", encoding="utf-8")
    annotation = queue_root / "annotations-human" / "crop-1.json"
    annotation.write_text(json.dumps({"reviewed": True, "unjudgeable": False, "boxes": [{"c": 0, "b": [0.4, 0.4, 0.6, 0.6]}]}), encoding="utf-8")
    annotation_hash = hashlib.sha256(annotation.read_bytes()).hexdigest()
    queue_manifest = queue_root / "manifest.json"
    queue_manifest.write_text(json.dumps({"rows": [{"crop_id": "crop-1"}]}), encoding="utf-8")
    dataset_manifest = dataset_root / "manifest.json"
    dataset_manifest.write_text(json.dumps({
        "train_only": True, "protected_holdout_included": False,
        "source_manifest_sha256": [hashlib.sha256(queue_manifest.read_bytes()).hexdigest()],
        "rows": [{"crop_id": "crop-1", "split": "train", "image": str(image), "label": str(label), "annotation_sha256": annotation_hash, "source_sha256": hashlib.sha256(image.read_bytes()).hexdigest()}],
    }), encoding="utf-8")
    registration = sop_worker.WorkerRegistration(
        factory_id="F001", project_id="p1", adapter_id="liushanmen-visionlab-v1",
        project_root=root, config_path=queue_manifest, config_sha256=hashlib.sha256(queue_manifest.read_bytes()).hexdigest(),
        input_refs={},
    )
    (dataset_root / "data.yaml").write_text("path: .\ntrain: images/train\nval: images/val\nnames:\n  0: white_label\n  1: color_label\n", encoding="utf-8")
    value = sop_worker._validate_dataset_result({"queue_roots": [str(queue_root)]}, {"data_yaml": str(dataset_root / "data.yaml")}, registration)
    assert value["protected_holdout_excluded"] is True
    image.write_bytes(b"mutated")
    with pytest.raises(sop_worker.WorkerBlockedError, match="image hash"):
        sop_worker._validate_dataset_result({"queue_roots": [str(queue_root)]}, {"data_yaml": str(dataset_root / "data.yaml")}, registration)


def test_dataset_rejects_protected_holdout_overlap_and_accepts_disjoint(tmp_path):
    root = tmp_path / "F001" / "project"
    queue_root = root / "queue"
    dataset_root = root / "datasets" / "d1"
    (queue_root / "annotations-human").mkdir(parents=True)
    (dataset_root / "images" / "train").mkdir(parents=True)
    (dataset_root / "images" / "val").mkdir(parents=True)
    (dataset_root / "labels" / "train").mkdir(parents=True)
    image = dataset_root / "images" / "train" / "crop-1.jpg"
    image.write_bytes(b"image")
    (dataset_root / "labels" / "train" / "crop-1.txt").write_text("0 0.5 0.5 0.2 0.2\n", encoding="utf-8")
    annotation = queue_root / "annotations-human" / "crop-1.json"
    annotation.write_text(json.dumps({"reviewed": True, "boxes": [{"c": 0, "b": [0.4, 0.4, 0.6, 0.6]}]}), encoding="utf-8")
    queue_manifest = queue_root / "manifest.json"
    queue_manifest.write_text(json.dumps({"rows": [{"crop_id": "crop-1"}]}), encoding="utf-8")
    dataset_manifest = dataset_root / "manifest.json"
    dataset_manifest.write_text(json.dumps({
        "train_only": True, "protected_holdout_included": False,
        "source_manifest_sha256": [hashlib.sha256(queue_manifest.read_bytes()).hexdigest()],
        "rows": [{
            "crop_id": "crop-1", "task_id": "task-1", "split": "train",
            "image": str(image), "label": str(dataset_root / "labels" / "train" / "crop-1.txt"),
            "annotation_sha256": hashlib.sha256(annotation.read_bytes()).hexdigest(),
            "source_sha256": hashlib.sha256(image.read_bytes()).hexdigest(),
        }],
    }), encoding="utf-8")
    (dataset_root / "data.yaml").write_text(
        "path: .\ntrain: images/train\nval: images/val\nnames:\n  0: white_label\n  1: color_label\n",
        encoding="utf-8",
    )
    holdout = root / "evaluation" / "protected-holdout.json"
    holdout.parent.mkdir()
    holdout.write_text(json.dumps({"records": [{"crop_id": "crop-1", "image_sha256": hashlib.sha256(image.read_bytes()).hexdigest()}]}), encoding="utf-8")
    registration = sop_worker.WorkerRegistration(
        factory_id="F001", project_id="p1", adapter_id="liushanmen-visionlab-v1",
        project_root=root, config_path=queue_manifest, config_sha256=hashlib.sha256(queue_manifest.read_bytes()).hexdigest(), input_refs={},
    )
    result = {"data_yaml": str(dataset_root / "data.yaml")}
    config = {"queue_roots": [str(queue_root)], "evaluation": {"manifests": [str(holdout)], "manifest_sha256": [hashlib.sha256(holdout.read_bytes()).hexdigest()]}}
    with pytest.raises(sop_worker.WorkerBlockedError, match="protected evaluation holdout"):
        sop_worker._validate_dataset_result(config, result, registration)
    holdout.write_text(json.dumps({"records": [{"crop_id": "crop-2", "image_sha256": "f" * 64}]}), encoding="utf-8")
    config["evaluation"]["manifest_sha256"] = [hashlib.sha256(holdout.read_bytes()).hexdigest()]
    assert sop_worker._validate_dataset_result(config, result, registration)["protected_holdout_excluded"] is True


def test_worker_requires_and_rechecks_consumed_file_hashes(tmp_path):
    root = tmp_path / "F001" / "project"
    root.mkdir(parents=True)
    config_path = root / "config.json"
    config_path.write_text("{}", encoding="utf-8")
    registration = sop_worker.WorkerRegistration(
        factory_id="F001", project_id="p1", adapter_id="liushanmen-visionlab-v1",
        project_root=root, config_path=config_path, config_sha256=hashlib.sha256(config_path.read_bytes()).hexdigest(), input_refs={},
    )
    base = root / "base.pt"
    base.write_bytes(b"base")
    train_config = {"training": {"base_model": str(base)}}
    with pytest.raises(sop_worker.WorkerBlockedError, match="base model.*sha256"):
        sop_worker._validate_evaluation_inputs(train_config, registration, stage="train")
    train_config["training"]["base_model_sha256"] = hashlib.sha256(base.read_bytes()).hexdigest()
    assert sop_worker._validate_evaluation_inputs(train_config, registration, stage="train") == (set(), set())
    base.write_bytes(b"mutated")
    with pytest.raises(sop_worker.WorkerBlockedError, match="base model hash changed"):
        sop_worker._validate_evaluation_inputs(train_config, registration, stage="train")

    manifest = root / "eval.json"
    manifest.write_text(json.dumps({"records": []}), encoding="utf-8")
    tray = root / "tray.onnx"
    label = root / "label.onnx"
    truth = root / "truth.json"
    for path in (tray, label, truth):
        path.write_bytes(path.name.encode("utf-8"))
    evaluation = {
        "manifests": [str(manifest)], "manifest_sha256": [hashlib.sha256(manifest.read_bytes()).hexdigest()],
        "tray_model": str(tray), "tray_model_sha256": hashlib.sha256(tray.read_bytes()).hexdigest(),
        "production_label_model": str(label), "production_label_model_sha256": hashlib.sha256(label.read_bytes()).hexdigest(),
        "truth_adjudication": str(truth), "truth_adjudication_sha256": hashlib.sha256(truth.read_bytes()).hexdigest(),
    }
    assert sop_worker._validate_evaluation_inputs({"evaluation": evaluation}, registration, stage="evaluate") == (set(), set())
    truth.write_bytes(b"mutated")
    with pytest.raises(sop_worker.WorkerBlockedError, match="truth adjudication hash changed"):
        sop_worker._validate_evaluation_inputs({"evaluation": evaluation}, registration, stage="evaluate")


@pytest.mark.parametrize("path_key", ["image", "image_path", "path"])
def test_evaluation_manifest_locks_referenced_image_bytes_not_only_its_json(tmp_path, path_key):
    image = tmp_path / "holdout.png"
    image.write_bytes(b"original independent sample")
    digest = hashlib.sha256(image.read_bytes()).hexdigest()
    manifest = tmp_path / "holdout.json"
    record = {path_key: str(image), "image_sha256": digest, "photo_id": "holdout-photo"}
    manifest.write_text(json.dumps({"records": [record]}), encoding="utf-8")
    registration = sop_worker.WorkerRegistration(factory_id="F001", project_id="p1",
        adapter_id="liushanmen-visionlab-v1", project_root=tmp_path, config_path=manifest,
        config_sha256=hashlib.sha256(manifest.read_bytes()).hexdigest(), input_refs={})
    config = {"evaluation": {"manifests": [str(manifest)],
        "manifest_sha256": [hashlib.sha256(manifest.read_bytes()).hexdigest()]}}
    assert digest in sop_worker._validate_evaluation_inputs(config, registration, stage="evaluate")[0]
    image.write_bytes(b"changed independent sample")
    with pytest.raises(sop_worker.WorkerBlockedError, match="evaluation image hash changed"):
        sop_worker._validate_evaluation_inputs(config, registration, stage="evaluate")
    image.write_bytes(b"original independent sample")
    del record["image_sha256"]
    manifest.write_text(json.dumps({"records": [record]}), encoding="utf-8")
    config["evaluation"]["manifest_sha256"] = [hashlib.sha256(manifest.read_bytes()).hexdigest()]
    with pytest.raises(sop_worker.WorkerBlockedError, match="evaluation image.*registered sha256"):
        sop_worker._validate_evaluation_inputs(config, registration, stage="evaluate")
    record[path_key] = image.name
    record["image_sha256"] = digest
    manifest.write_text(json.dumps({"records": [record]}), encoding="utf-8")
    config["evaluation"]["manifest_sha256"] = [hashlib.sha256(manifest.read_bytes()).hexdigest()]
    with pytest.raises(sop_worker.WorkerBlockedError, match="absolute project-local path"):
        sop_worker._validate_evaluation_inputs(config, registration, stage="evaluate")


def test_yaml_validation_uses_consumer_semantics_and_rejects_duplicate_keys(tmp_path):
    path = tmp_path / "data.yaml"
    prefix = "path: .\ntrain: images/train\nval: images/val\n"
    valid = "names:\n  0: white_label\n  1: color_label\n"
    for names in (valid, "names: [white_label, color_label]\n"):
        path.write_text(prefix + names, encoding="utf-8")
        assert sop_worker._read_data_yaml(path)["train"] == "images/train"
    for bad in (valid + "names: [color_label, white_label]\n",
                "names: [color_label, white_label]\n",
                valid + "train: /outside/project\n"):
        path.write_text(prefix + bad, encoding="utf-8")
        with pytest.raises(sop_worker.WorkerBlockedError):
            sop_worker._read_data_yaml(path)
