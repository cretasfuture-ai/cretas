"""Optional FastAPI router for conversational factory setup."""

from __future__ import annotations

from collections.abc import Callable, Collection, Mapping
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, ConfigDict

from .errors import VisionTrainingError
from .models import FactoryContext, ProjectPreview, TaskSpec
from .service import VisionTrainingService
from .store import DraftStore, FileDraftStore
from .templates import SOP_VERSION


FACTORY_ROLES = frozenset(
    {
        "factory_super_admin",
        "quality_manager",
        "factory_admin",
        "factory_manager",
        "factory_user",
        "quality",
        "owner",
    }
)


class PreviewRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    spec: TaskSpec
    template_id: str | None = None


class SaveDraftRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    preview: ProjectPreview


def require_factory_context(request: Request) -> FactoryContext:
    """Fail closed unless trusted auth middleware populated request.state.

    ``factory_id`` in the JSON body is checked against this context by the
    service; it is never used to authenticate the caller.
    """

    state = request.state
    factory_id = getattr(state, "factory_id", None)
    actor_id = getattr(state, "user_id", None)
    if not isinstance(factory_id, str) or not factory_id.strip():
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="trusted factory context is required")
    if isinstance(actor_id, bool) or actor_id is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="trusted actor context is required")
    if isinstance(actor_id, int):
        if actor_id <= 0:
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="trusted actor context is required")
        actor_id = str(actor_id)
    elif isinstance(actor_id, str):
        actor_id = actor_id.strip()
        if not actor_id or (actor_id.isdigit() and int(actor_id) <= 0):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="trusted actor context is required")
    else:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="trusted actor context is required")
    roles = getattr(state, "role", ())
    if isinstance(roles, str):
        roles = (roles,)
    trusted_roles = tuple(role for role in (roles or ()) if isinstance(role, str) and role in FACTORY_ROLES)
    if not trusted_roles:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="factory role is required")
    return FactoryContext(factory_id=factory_id, actor_id=actor_id, roles=trusted_roles)


def create_router(
    service: VisionTrainingService | None = None,
    *,
    store: DraftStore | None = None,
    specialized_factory_allowlist: Mapping[str, Collection[str]] | None = None,
    factory_context_dependency: Callable[[Request], FactoryContext] = require_factory_context,
) -> APIRouter:
    if service is not None and (store is not None or specialized_factory_allowlist is not None):
        raise ValueError("pass either service or store/specialized_factory_allowlist, not both")
    coordinator = service or VisionTrainingService(
        store=store,
        specialized_factory_allowlist=specialized_factory_allowlist,
    )
    router = APIRouter(prefix="/api/vision-training", tags=["Vision Training"])
    @router.post("/preview")
    def preview(request: PreviewRequest, context: FactoryContext = Depends(factory_context_dependency)) -> dict[str, object]:
        try:
            result = coordinator.preview(request.spec, template_id=request.template_id, factory_context=context)
        except VisionTrainingError as exc:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc
        return {"success": True, "message": "preview generated; no draft was saved", "data": result.model_dump(mode="json")}

    @router.post("/drafts")
    def save_draft(request: SaveDraftRequest, context: FactoryContext = Depends(factory_context_dependency)) -> dict[str, object]:
        try:
            result = coordinator.save_draft(request.preview, factory_context=context)
        except VisionTrainingError as exc:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc
        return {"success": True, "message": "draft saved explicitly; no training was executed", "data": result.model_dump(mode="json")}

    @router.get("/templates")
    def templates(context: FactoryContext = Depends(factory_context_dependency)) -> dict[str, object]:
        return {
            "success": True,
            "message": "templates available for authenticated factory",
            "data": {
                "sopVersion": SOP_VERSION,
                "templates": [
                    template.model_dump(mode="json")
                    for template in coordinator.available_templates(context.factory_id)
                ],
                "taskSpecSchema": TaskSpec.model_json_schema(),
            },
        }

    @router.get("/drafts/{project_id}")
    def readback(project_id: str, context: FactoryContext = Depends(factory_context_dependency)) -> dict[str, object]:
        try:
            draft = coordinator.readback(project_id)
            if draft.spec.factory_id != context.factory_id:
                raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="factory scope mismatch")
        except VisionTrainingError as exc:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc
        return {"success": True, "message": "draft readback", "data": draft.model_dump(mode="json")}

    @router.get("/drafts/{project_id}/plan")
    def plan(project_id: str, context: FactoryContext = Depends(factory_context_dependency)) -> dict[str, object]:
        try:
            draft = coordinator.readback(project_id)
            if draft.spec.factory_id != context.factory_id:
                raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="factory scope mismatch")
            result = coordinator.stage_plan(project_id, factory_context=context)
        except VisionTrainingError as exc:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc
        return {"success": True, "message": "stage plan readback", "data": result.model_dump(mode="json")}

    return router


def create_file_backed_router(
    store_root: str | Path,
    *,
    specialized_factory_allowlist: Mapping[str, Collection[str]] | None = None,
    factory_context_dependency: Callable[[Request], FactoryContext] = require_factory_context,
) -> APIRouter:
    """Build a router with lazy local persistence for the host entry point.

    ``FileDraftStore`` does not create its directory until the first draft is
    written, so importing this module remains side-effect free.
    """

    return create_router(
        store=FileDraftStore(store_root),
        specialized_factory_allowlist=specialized_factory_allowlist,
        factory_context_dependency=factory_context_dependency,
    )


router = create_router()
