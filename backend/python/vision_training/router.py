"""Optional FastAPI router for conversational factory setup."""

from __future__ import annotations

from collections.abc import Callable

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, ConfigDict

from .errors import VisionTrainingError
from .models import FactoryContext, ProjectPreview, TaskSpec
from .service import VisionTrainingService


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
    if not isinstance(actor_id, str) or not actor_id.strip():
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="trusted actor context is required")
    roles = getattr(state, "role", ())
    if isinstance(roles, str):
        roles = (roles,)
    return FactoryContext(factory_id=factory_id, actor_id=actor_id, roles=tuple(roles or ()))


def create_router(
    service: VisionTrainingService | None = None,
    *,
    factory_context_dependency: Callable[[Request], FactoryContext] = require_factory_context,
) -> APIRouter:
    coordinator = service or VisionTrainingService()
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


router = create_router()
