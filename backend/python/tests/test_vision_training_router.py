from __future__ import annotations

import multiprocessing as mp
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

from vision_training.router import create_file_backed_router, create_router
from vision_training.service import VisionTrainingService
from vision_training.store import CapacityLimitError


def app_with_context() -> FastAPI:
    app = FastAPI()

    def trusted_context(request: Request):
        from vision_training.models import FactoryContext

        return FactoryContext(factory_id="F001", actor_id="user-1", roles=("quality",))

    app.include_router(create_router(VisionTrainingService(), factory_context_dependency=trusted_context))
    return app


def valid_spec() -> dict[str, object]:
    return {
        "factory_id": "F001",
        "workstation_id": "ws-01",
        "task_family": "packaging-detection",
        "product_scope": ["SKU-01"],
        "packaging_scope": ["tray"],
        "data_version": "photos-v3",
        "recipe_version": "recipe-v2",
        "human_standard_version": "human-v1",
        "independent_acceptance_version": "acceptance-v1",
    }


def test_preview_endpoint_does_not_save_and_readback_requires_explicit_save() -> None:
    client = TestClient(app_with_context())
    response = client.post("/api/vision-training/preview", json={"spec": valid_spec()})
    assert response.status_code == 200
    assert "no draft was saved" in response.json()["message"]


def test_capacity_rejection_is_a_user_visible_422_on_factory_and_platform_save(tmp_path: Path) -> None:
    class FullService(VisionTrainingService):
        def save_draft(self, *args, **kwargs):
            raise CapacityLimitError("本厂检查项目已达五十个上限，请联系管理员归档或核验容量后再新增。")

    def context(_request: Request):
        from vision_training.models import FactoryContext
        return FactoryContext(factory_id="F001", actor_id="user-1", roles=("quality",), actor_type="PLATFORM_ADMIN")

    service = FullService()
    preview = service.preview(__import__("vision_training.models", fromlist=["TaskSpec"]).TaskSpec(**valid_spec()),
                              factory_context=context(None))
    payload = {"preview": preview.model_dump(mode="json")}
    app = FastAPI()
    app.include_router(create_router(service, factory_context_dependency=context))
    from vision_training.router import create_platform_router
    from vision_training.queue import VisionJobStore
    app.include_router(create_platform_router(service, VisionJobStore(tmp_path / "jobs.sqlite"), platform_context_dependency=context))
    client = TestClient(app)
    for path in ("/api/vision-training/drafts", "/api/vision-training/platform/factories/F001/drafts"):
        response = client.post(path, json=payload)
        assert response.status_code == 422
        assert "五十个上限" in response.json()["detail"]


def test_authenticated_templates_expose_schema_and_hide_unbound_specialized_template() -> None:
    client = TestClient(app_with_context())
    response = client.get("/api/vision-training/templates")
    assert response.status_code == 200
    data = response.json()["data"]
    template_ids = {item["template_id"] for item in data["templates"]}
    assert {"generic-packaging-detection", "generic-packaging-ocr"} <= template_ids
    assert "liushanmen-tray" not in template_ids
    assert "factory_id" in data["taskSpecSchema"]["properties"]


def test_default_dependency_fails_closed_without_trusted_context() -> None:
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from vision_training.router import router

    app = FastAPI()
    app.include_router(router)
    response = TestClient(app).post("/api/vision-training/preview", json={"spec": valid_spec()})
    assert response.status_code == 401


def test_jwt_middleware_state_accepts_positive_integer_user_id_and_factory_role() -> None:
    from starlette.middleware.base import BaseHTTPMiddleware

    class FakeJWTState(BaseHTTPMiddleware):
        async def dispatch(self, request, call_next):
            request.state.factory_id = "F001"
            request.state.user_id = 123
            request.state.role = "quality_manager"
            return await call_next(request)

    app = FastAPI()
    app.add_middleware(FakeJWTState)
    app.include_router(create_router(VisionTrainingService()))
    response = TestClient(app).post("/api/vision-training/preview", json={"spec": valid_spec()})
    assert response.status_code == 200


def test_platform_role_zero_and_boolean_user_ids_fail_closed() -> None:
    from starlette.middleware.base import BaseHTTPMiddleware

    class StateMiddleware(BaseHTTPMiddleware):
        def __init__(self, app, user_id, role):
            super().__init__(app)
            self.user_id = user_id
            self.role = role

        async def dispatch(self, request, call_next):
            request.state.factory_id = "F001"
            request.state.user_id = self.user_id
            request.state.role = self.role
            return await call_next(request)

    for user_id, role, expected in ((1, "PLATFORM_ADMIN", 403), (0, "quality_manager", 401), (True, "quality_manager", 401)):
        app = FastAPI()
        app.add_middleware(StateMiddleware, user_id=user_id, role=role)
        app.include_router(create_router(VisionTrainingService()))
        response = TestClient(app).post("/api/vision-training/preview", json={"spec": valid_spec()})
        assert response.status_code == expected


def test_file_backed_router_is_lazy(tmp_path: Path) -> None:
    root = tmp_path / "drafts"
    create_file_backed_router(root)
    assert not root.exists()


def _atomic_create_worker(root: str, payload: dict[str, object], result_queue) -> None:
    from vision_training.models import TrainingProjectDraft
    from vision_training.store import FileDraftStore

    draft = TrainingProjectDraft.model_validate(payload)
    result = FileDraftStore(root).create_if_absent(draft)
    result_queue.put(result.created_at.isoformat())


def test_file_store_atomic_create_returns_one_readback_for_two_processes(tmp_path: Path) -> None:
    from vision_training.service import VisionTrainingService
    from vision_training.models import TaskSpec
    from vision_training.store import FileDraftStore

    spec_payload = {
        "factory_id": "F001",
        "workstation_id": "ws-01",
        "task_family": "packaging-detection",
        "product_scope": ("SKU-01",),
        "packaging_scope": ("tray",),
        "data_version": "photos-v3",
        "recipe_version": "recipe-v2",
        "human_standard_version": "human-v1",
        "independent_acceptance_version": "acceptance-v1",
    }

    service = VisionTrainingService()
    draft = service.save_draft(service.preview(TaskSpec(**spec_payload)))
    payload = draft.model_dump(mode="json")
    root = tmp_path / "atomic"
    context = mp.get_context("spawn")
    result_queue = context.Queue()
    workers = [
        context.Process(target=_atomic_create_worker, args=(str(root), payload, result_queue))
        for _ in range(2)
    ]
    for worker in workers:
        worker.start()
    for worker in workers:
        worker.join(timeout=30)
        assert worker.exitcode == 0
    created_at_values = [result_queue.get(timeout=5) for _ in workers]
    readback = FileDraftStore(root).get(draft.project_id)
    assert readback is not None
    assert set(created_at_values) == {readback.created_at.isoformat()}
