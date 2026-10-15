from __future__ import annotations

from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

from vision_training.router import create_file_backed_router, create_router
from vision_training.service import VisionTrainingService


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
