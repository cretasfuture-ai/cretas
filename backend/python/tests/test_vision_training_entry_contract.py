"""Real JWT middleware + production router, with disposable draft storage."""
from pathlib import Path
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import jwt
from fastapi import FastAPI
from fastapi.testclient import TestClient

from auth_middleware import JWTAuthMiddleware
from vision_training.router import create_file_backed_router
from vision_training.runtime import resolve_draft_root, verify_draft_storage

SECRET = "vision-training-local-contract-key-32-bytes"


def token(factory="VT-TEST", role="quality_manager"):
    return jwt.encode({"factoryId": factory, "userId": 123, "role": role, "exp": time.time() + 60}, SECRET, algorithm="HS256")


def spec(factory="VT-TEST"):
    return {"factory_id": factory, "workstation_id": "ws-1", "task_family": "packaging-detection",
            "product_scope": ["SKU-A"], "packaging_scope": ["bag"], "data_version": "data-v1",
            "recipe_version": "recipe-v1", "human_standard_version": "human-v1",
            "independent_acceptance_version": "accept-v1"}


def client_for(root):
    app = FastAPI()
    app.add_middleware(JWTAuthMiddleware, jwt_secret=SECRET)
    app.include_router(create_file_backed_router(root))
    return TestClient(app)


def test_auth_save_readback_and_restarted_service_use_the_same_draft(tmp_path):
    root = tmp_path / "drafts"
    client = client_for(root)
    headers = {"Authorization": "Bearer " + token()}
    preview = client.post("/api/vision-training/preview", json={"spec": spec()}, headers=headers)
    assert preview.status_code == 200
    assert not root.exists()
    saved = client.post("/api/vision-training/drafts", json={"preview": preview.json()["data"]}, headers=headers)
    assert saved.status_code == 200
    draft = saved.json()["data"]
    assert draft["status"] == "draft"
    readback = client_for(root).get("/api/vision-training/drafts/" + draft["project_id"], headers=headers)
    assert readback.status_code == 200
    assert readback.json()["data"] == draft
    assert len(list(root.glob("*.json"))) == 1
    plan = client.get("/api/vision-training/drafts/" + draft["project_id"] + "/plan", headers=headers).json()["data"]
    assert plan["training_success"] is False and plan["accepted"] is False and plan["enabled"] is False


def test_anonymous_cross_factory_and_platform_callers_cannot_create_drafts(tmp_path):
    client = client_for(tmp_path)
    assert client.post("/api/vision-training/preview", json={"spec": spec()}).status_code == 401
    assert client.post("/api/vision-training/preview", json={"spec": spec("FOREIGN")},
                       headers={"Authorization": "Bearer " + token()}).status_code in (403, 422)
    assert client.post("/api/vision-training/preview", json={"spec": spec()},
                       headers={"Authorization": "Bearer " + token(role="PLATFORM_ADMIN")}).status_code == 403
    assert not list(tmp_path.glob("*.json"))


def test_main_mounts_persistent_router_outside_optional_feature_imports():
    import ast
    main = ast.parse((Path(__file__).resolve().parents[1] / "main.py").read_text(encoding="utf-8"))
    mounts = [node.value.args[0] for node in main.body
               if isinstance(node, ast.Expr) and isinstance(node.value, ast.Call)
               and isinstance(node.value.func, ast.Attribute) and node.value.func.attr == "include_router"
               and node.value.args]
    for namespace in ("factory", "platform", "worker"):
        assert any(isinstance(arg, ast.Attribute) and arg.attr == namespace
                   and isinstance(arg.value, ast.Attribute) and arg.value.attr == "routers"
                   and isinstance(arg.value.value, ast.Name)
                   and arg.value.value.id == "_vision_training_runtime" for arg in mounts)
    runtime = next(node.value for node in main.body if isinstance(node, ast.Assign)
                   and any(isinstance(target, ast.Name) and target.id == "_vision_training_runtime"
                           for target in node.targets))
    assert isinstance(runtime, ast.Call) and runtime.func.id == "create_file_backed_runtime"
    assert {keyword.arg for keyword in runtime.keywords} >= {
        "draft_root", "job_db", "recipe_catalog", "worker_bindings", "specialized_factory_allowlist"}


def test_durable_directory_cannot_be_inside_the_deployed_tree(tmp_path):
    import pytest
    source_root = Path(__file__).resolve().parents[3]
    assert not resolve_draft_root().is_relative_to(source_root)
    with pytest.raises(ValueError, match="absolute"):
        resolve_draft_root("relative-drafts")
    with pytest.raises(ValueError, match="source tree"):
        resolve_draft_root(str(source_root / "backend" / "python" / "data" / "drafts"))
    root = resolve_draft_root(str(tmp_path / "persistent"))
    verify_draft_storage(root)
    assert root.is_dir() and not list(root.iterdir())


def test_unwritable_storage_fails_closed(tmp_path, monkeypatch):
    import pytest
    import vision_training.runtime as runtime
    def denied(*args, **kwargs):
        raise PermissionError("test read-only volume")
    monkeypatch.setattr(runtime.os, "replace", denied)
    with pytest.raises(PermissionError):
        verify_draft_storage(tmp_path / "persistent")
    assert not list((tmp_path / "persistent").iterdir())


def test_storage_without_atomic_draft_creation_fails_startup(tmp_path, monkeypatch):
    import pytest
    import vision_training.runtime as runtime
    def denied(*args, **kwargs):
        raise OSError("test volume does not support hard links")
    monkeypatch.setattr(runtime.os, "link", denied)
    with pytest.raises(OSError):
        verify_draft_storage(tmp_path / "persistent")
    assert not list((tmp_path / "persistent").iterdir())


def test_shared_runtime_enforces_real_middleware_for_platform_and_named_worker(tmp_path, monkeypatch):
    from vision_training.router import WorkerBinding
    from vision_training.runtime import create_file_backed_runtime

    internal = "local-internal-transport-secret-32-bytes"
    worker_token = "local-independent-worker-secret-32-bytes"
    monkeypatch.setenv("INTERNAL_API_SECRET", internal)
    root = tmp_path / "persistent"
    runtime = create_file_backed_runtime(draft_root=root, job_db=root / "jobs.sqlite3",
        worker_bindings=(WorkerBinding("local-A", worker_token, frozenset({"VT-TEST"})),))
    assert not root.exists()
    app = FastAPI()
    app.add_middleware(JWTAuthMiddleware, jwt_secret=SECRET)
    for router in (runtime.routers.factory, runtime.routers.platform, runtime.routers.worker):
        app.include_router(router)
    client = TestClient(app)
    worker_headers = {"X-Worker-Id": "local-A", "X-Worker-Token": worker_token}
    claim = "/api/vision-training/worker/jobs/claim"
    assert client.post(claim, headers=worker_headers, json={}).status_code == 401
    factory_headers = {**worker_headers, "Authorization": "Bearer " + token()}
    assert client.post(claim, headers=factory_headers, json={}).status_code == 401
    assert not root.exists()
    internal_headers = {**worker_headers, "X-Internal-Secret": internal}
    assert client.post(claim, headers={**internal_headers, "X-Worker-Token": "wrong"}, json={}).status_code == 401
    claimed = client.post(claim, headers=internal_headers, json={})
    assert claimed.status_code == 200 and claimed.json()["data"] is None
    platform = "/api/vision-training/platform/factories/VT-TEST/templates"
    assert client.get(platform, headers={"Authorization": "Bearer " + token(role="super_admin")}).status_code == 401
    platform_headers = {"X-Internal-Secret": internal, "X-Actor-Type": "PLATFORM_ADMIN",
                        "X-User-Id": "123", "X-User-Role": "super_admin", "X-Factory-Id": "VT-TEST"}
    response = client.get(platform, headers=platform_headers)
    assert response.status_code == 200
    assert all(item["scope"] == "generic" for item in response.json()["data"]["templates"])
    assert client.get(platform, headers={**platform_headers, "X-Actor-Type": "FACTORY_USER"}).status_code == 403
    assert client.get(platform, headers={**platform_headers, "X-Factory-Id": "FOREIGN"}).status_code == 403
    assert not list(root.glob("*.json"))
    runtime.close()
