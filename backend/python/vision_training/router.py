"""Factory-scoped visual training, receipts and independent acceptance routes."""

from __future__ import annotations

from collections.abc import Callable, Collection, Mapping
from dataclasses import dataclass
import hmac
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, ConfigDict

from .errors import InvalidTaskSpecError, ScopeViolationError, VisionTrainingError
from .models import (
    EnqueueRequest,
    FactoryContext,
    JobStatus,
    ProjectPreview,
    RecipeAvailability,
    TaskSpec,
    WorkerClaimRequest,
    WorkerCompletion,
    WorkerHeartbeatRequest,
)
from .queue import QueueConflictError, QueueLeaseError, VisionJobStore
from .service import VisionTrainingService
from .store import CapacityLimitError
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


def _bound_recipe(
    recipe_catalog: Collection[RecipeAvailability],
    *,
    draft: object,
    request: EnqueueRequest,
    factory_id: str,
) -> tuple[str, str, str | None]:
    """Resolve hashes from a server-registered recipe, never from browser input."""

    if request.recipe_id is None:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="recipe_id is required for queue dispatch")
    recipe = next(
        (
            item for item in recipe_catalog
            if item.recipe_id == request.recipe_id
            and item.factory_id == factory_id
            and item.available
        ),
        None,
    )
    if recipe is None:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="recipe is not registered and available for this factory")
    spec = draft.spec
    if (
        recipe.task_family != spec.task_family
        or recipe.recipe_version != spec.recipe_version
        or recipe.template_id != draft.template.template_id
        or recipe.template_version != draft.template.version
        or recipe.sop_version != draft.sop_version
        or recipe.data_version != spec.data_version
        or recipe.human_standard_version != spec.human_standard_version
        or recipe.independent_acceptance_version != spec.independent_acceptance_version
        or recipe.ocr_rules_version != spec.ocr_rules_version
        or not recipe.config_sha256
        or request.stage.value not in recipe.stage_input_hashes
    ):
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="recipe binding does not match the saved project/version")
    config_hash = recipe.config_sha256
    input_hash = recipe.stage_input_hashes[request.stage.value]
    source_asset_hash = recipe.stage_source_asset_hashes.get(request.stage.value)
    if request.config_hash is not None and request.config_hash.lower() != config_hash.lower():
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="client config_hash does not match the registered recipe")
    if request.input_hash is not None and request.input_hash.lower() != input_hash.lower():
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="client input_hash does not match the registered recipe")
    return config_hash, input_hash, source_asset_hash


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
        if not actor_id or not actor_id.isdigit() or int(actor_id) <= 0:
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="trusted actor context is required")
    else:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="trusted actor context is required")
    roles = getattr(state, "role", ())
    if isinstance(roles, str):
        roles = (roles,)
    trusted_roles = tuple(role for role in (roles or ()) if isinstance(role, str) and role in FACTORY_ROLES)
    if not trusted_roles:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="factory role is required")
    return FactoryContext(
        factory_id=factory_id,
        actor_id=actor_id,
        roles=trusted_roles,
        actor_type="FACTORY_USER",
    )


def require_factory_bridge_context(request: Request) -> FactoryContext:
    """The deployed daily API is entered through Java's live membership check."""
    if getattr(request.state, "auth_method", None) != "internal":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="internal factory bridge authentication required")
    if request.headers.get("X-Actor-Type") != "FACTORY_USER":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="explicit factory actor is required")
    if getattr(request.state, "role", None) not in {"factory_super_admin", "quality_manager"}:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="factory quality management role is required")
    return require_factory_context(request)


def create_router(
    service: VisionTrainingService | None = None,
    *,
    store: DraftStore | None = None,
    specialized_factory_allowlist: Mapping[str, Collection[str]] | None = None,
    factory_context_dependency: Callable[[Request], FactoryContext] = require_factory_context,
    job_store: VisionJobStore | None = None,
    recipe_catalog: Collection[RecipeAvailability] = (),
) -> APIRouter:
    if service is not None and (store is not None or specialized_factory_allowlist is not None):
        raise ValueError("pass either service or store/specialized_factory_allowlist, not both")
    coordinator = service or VisionTrainingService(
        store=store,
        specialized_factory_allowlist=specialized_factory_allowlist,
        job_store=job_store,
    )
    if service is not None and job_store is not None:
        coordinator.attach_job_store(job_store)
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
        except CapacityLimitError as exc:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc
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
                "recipes": [item.model_dump(mode="json") for item in recipe_catalog if item.factory_id == context.factory_id],
            },
        }

    @router.get("/recipes")
    def recipes(context: FactoryContext = Depends(factory_context_dependency)) -> dict[str, object]:
        return {
            "success": True,
            "message": "registered recipes for authenticated factory",
            "data": [item.model_dump(mode="json") for item in recipe_catalog if item.factory_id == context.factory_id],
        }

    @router.get("/drafts")
    def drafts(context: FactoryContext = Depends(factory_context_dependency)) -> dict[str, object]:
        result = coordinator.list_drafts(context.factory_id, factory_context=context)
        return {"success": True, "message": "factory vision drafts", "data": [item.model_dump(mode="json") for item in result]}

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

    if job_store is not None:
        @router.post("/drafts/{project_id}/jobs")
        def enqueue_job(
            project_id: str,
            request: EnqueueRequest,
            context: FactoryContext = Depends(factory_context_dependency),
        ) -> dict[str, object]:
            try:
                draft = coordinator.readback(project_id)
                if draft.spec.factory_id != context.factory_id:
                    raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="factory scope mismatch")
                if draft.spec.recipe_id != request.recipe_id:
                    raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="recipe_id does not match the saved project specification")
                config_hash, input_hash, source_asset_hash = _bound_recipe(
                    recipe_catalog, draft=draft, request=request, factory_id=context.factory_id,
                )
                result = job_store.enqueue(
                    draft,
                    stage=request.stage,
                    config_hash=config_hash,
                    input_hash=input_hash,
                    source_asset_sha256=source_asset_hash,
                )
            except QueueConflictError as exc:
                raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
            except VisionTrainingError as exc:
                raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc
            return {"success": True, "message": "vision stage queued or blocked by missing named runner", "data": result.model_dump(mode="json")}

        @router.get("/jobs/{job_id}")
        def job_readback(
            job_id: str,
            context: FactoryContext = Depends(factory_context_dependency),
        ) -> dict[str, object]:
            result = job_store.get(job_id)
            if result is None:
                raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="vision job not found")
            if result.identity.factory_id != context.factory_id:
                raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="factory scope mismatch")
            return {"success": True, "message": "vision job readback", "data": result.model_dump(mode="json")}

        @router.get("/drafts/{project_id}/jobs")
        def project_jobs(
            project_id: str,
            context: FactoryContext = Depends(factory_context_dependency),
        ) -> dict[str, object]:
            draft = coordinator.readback(project_id)
            if draft.spec.factory_id != context.factory_id:
                raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="factory scope mismatch")
            result = job_store.list_for_project(project_id, factory_id=context.factory_id)
            return {"success": True, "message": "project vision jobs", "data": [item.model_dump(mode="json") for item in result]}

    return router


def create_file_backed_router(
    store_root: str | Path,
    *,
    specialized_factory_allowlist: Mapping[str, Collection[str]] | None = None,
    factory_context_dependency: Callable[[Request], FactoryContext] = require_factory_context,
    job_store: VisionJobStore | None = None,
    recipe_catalog: Collection[RecipeAvailability] = (),
) -> APIRouter:
    """Build a router with lazy local persistence for the host entry point.

    ``FileDraftStore`` does not create its directory until the first draft is
    written, so importing this module remains side-effect free.
    """

    return create_router(
        store=FileDraftStore(store_root),
        specialized_factory_allowlist=specialized_factory_allowlist,
        factory_context_dependency=factory_context_dependency,
        job_store=job_store,
        recipe_catalog=recipe_catalog,
    )


@dataclass(frozen=True)
class WorkerBinding:
    """Named local worker credential and the factories it may serve."""

    worker_id: str
    token: str
    factory_ids: frozenset[str]
    project_ids: frozenset[str] = frozenset()


@dataclass(frozen=True)
class WorkerContext:
    worker_id: str
    factory_ids: frozenset[str]
    project_ids: frozenset[str]


def make_worker_context_dependency(
    bindings: Collection[WorkerBinding],
) -> Callable[[Request], WorkerContext]:
    by_worker = {binding.worker_id: binding for binding in bindings}

    def require_worker_context(request: Request) -> WorkerContext:
        state = request.state
        if getattr(state, "auth_method", None) != "internal":
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="internal worker authentication required")
        worker_id = request.headers.get("X-Worker-Id", "")
        token = request.headers.get("X-Worker-Token", "")
        binding = by_worker.get(worker_id)
        if binding is None or not token or not hmac.compare_digest(token, binding.token):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid named worker credential")
        if not binding.factory_ids:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="worker has no factory binding")
        return WorkerContext(worker_id=worker_id, factory_ids=binding.factory_ids, project_ids=binding.project_ids)

    return require_worker_context


def require_platform_context(request: Request, factory_id: str) -> FactoryContext:
    """Require the explicit internal PLATFORM_ADMIN planning namespace."""

    state = request.state
    if getattr(state, "auth_method", None) != "internal":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="internal platform authentication required")
    if getattr(state, "role", None) != "super_admin":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="super_admin role is required")
    if request.headers.get("X-Actor-Type") != "PLATFORM_ADMIN":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="PLATFORM_ADMIN actor type is required")
    state_factory = getattr(state, "factory_id", None)
    actor_id = getattr(state, "user_id", None)
    if not isinstance(state_factory, str) or not state_factory.strip() or state_factory != factory_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="target factory does not match trusted state")
    if isinstance(actor_id, bool) or actor_id is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="trusted platform actor is required")
    if isinstance(actor_id, int):
        if actor_id <= 0:
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="trusted platform actor is required")
        actor_id = str(actor_id)
    elif isinstance(actor_id, str) and actor_id.strip().isdigit() and int(actor_id.strip()) > 0:
        actor_id = actor_id.strip()
    else:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="trusted platform actor is required")
    return FactoryContext(
        factory_id=factory_id,
        actor_id=actor_id,
        roles=("super_admin",),
        actor_type="PLATFORM_ADMIN",
    )


def create_platform_router(
    service: VisionTrainingService,
    job_store: VisionJobStore,
    *,
    platform_context_dependency: Callable[..., FactoryContext] = require_platform_context,
    recipe_catalog: Collection[RecipeAvailability] = (),
) -> APIRouter:
    """Internal planning proxy; it never authenticates as a factory user."""

    service.attach_job_store(job_store)
    router = APIRouter(prefix="/api/vision-training/platform", tags=["Vision Training Platform"])

    @router.get("/factories/{factory_id}/templates")
    def platform_templates(
        factory_id: str,
        context: FactoryContext = Depends(platform_context_dependency),
    ) -> dict[str, object]:
        if context.factory_id != factory_id:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="target factory scope mismatch")
        return {
            "success": True,
            "message": "templates available for target factory",
            "data": {
                "sopVersion": SOP_VERSION,
                "templates": [item.model_dump(mode="json") for item in service.available_templates(factory_id)],
                "taskSpecSchema": TaskSpec.model_json_schema(),
                "recipes": [item.model_dump(mode="json") for item in recipe_catalog if item.factory_id == factory_id],
            },
        }

    @router.post("/factories/{factory_id}/preview")
    def platform_preview(
        factory_id: str,
        request: PreviewRequest,
        context: FactoryContext = Depends(platform_context_dependency),
    ) -> dict[str, object]:
        if request.spec.factory_id != factory_id or context.factory_id != factory_id:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="target factory scope mismatch")
        try:
            result = service.preview(request.spec, template_id=request.template_id, factory_context=context)
        except VisionTrainingError as exc:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc
        return {"success": True, "message": "platform preview generated; no draft was saved", "data": result.model_dump(mode="json")}

    @router.post("/factories/{factory_id}/drafts")
    def platform_save_draft(
        factory_id: str,
        request: SaveDraftRequest,
        context: FactoryContext = Depends(platform_context_dependency),
    ) -> dict[str, object]:
        if request.preview.spec.factory_id != factory_id or context.factory_id != factory_id:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="target factory scope mismatch")
        try:
            result = service.save_draft(request.preview, factory_context=context)
        except CapacityLimitError as exc:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc
        except VisionTrainingError as exc:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc
        return {"success": True, "message": "platform draft saved; no training was executed", "data": result.model_dump(mode="json")}

    @router.get("/factories/{factory_id}/drafts")
    def platform_drafts(
        factory_id: str,
        context: FactoryContext = Depends(platform_context_dependency),
    ) -> dict[str, object]:
        if context.factory_id != factory_id:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="target factory scope mismatch")
        result = service.list_drafts(factory_id, factory_context=context)
        return {"success": True, "message": "target factory vision drafts", "data": [item.model_dump(mode="json") for item in result]}

    @router.get("/factories/{factory_id}/drafts/{project_id}")
    def platform_readback(
        factory_id: str,
        project_id: str,
        context: FactoryContext = Depends(platform_context_dependency),
    ) -> dict[str, object]:
        if context.factory_id != factory_id:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="target factory scope mismatch")
        try:
            result = service.readback(project_id)
        except VisionTrainingError as exc:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc
        if result.spec.factory_id != factory_id:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="target factory scope mismatch")
        return {"success": True, "message": "target factory draft readback", "data": result.model_dump(mode="json")}

    @router.get("/factories/{factory_id}/drafts/{project_id}/plan")
    def platform_plan(
        factory_id: str,
        project_id: str,
        context: FactoryContext = Depends(platform_context_dependency),
    ) -> dict[str, object]:
        if context.factory_id != factory_id:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="target factory scope mismatch")
        try:
            result = service.readback(project_id)
            if result.spec.factory_id != factory_id:
                raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="target factory scope mismatch")
            plan = service.stage_plan(project_id, factory_context=context)
        except VisionTrainingError as exc:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc
        return {"success": True, "message": "target factory stage plan readback", "data": plan.model_dump(mode="json")}

    @router.get("/factories/{factory_id}/recipes")
    def platform_recipes(
        factory_id: str,
        context: FactoryContext = Depends(platform_context_dependency),
    ) -> dict[str, object]:
        if context.factory_id != factory_id:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="target factory scope mismatch")
        return {
            "success": True,
            "message": "registered recipes for target factory",
            "data": [item.model_dump(mode="json") for item in recipe_catalog if item.factory_id == factory_id],
        }

    @router.post("/factories/{factory_id}/drafts/{project_id}/jobs")
    def platform_enqueue(
        factory_id: str,
        project_id: str,
        request: EnqueueRequest,
        context: FactoryContext = Depends(platform_context_dependency),
    ) -> dict[str, object]:
        try:
            draft = service.readback(project_id)
            if draft.spec.factory_id != factory_id or context.factory_id != factory_id:
                raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="target factory scope mismatch")
            if draft.spec.recipe_id != request.recipe_id:
                raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="recipe_id does not match the saved project specification")
            config_hash, input_hash, source_asset_hash = _bound_recipe(
                recipe_catalog, draft=draft, request=request, factory_id=factory_id,
            )
            result = job_store.enqueue(
                draft,
                stage=request.stage,
                config_hash=config_hash,
                input_hash=input_hash,
                source_asset_sha256=source_asset_hash,
            )
        except QueueConflictError as exc:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
        except VisionTrainingError as exc:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc
        return {"success": True, "message": "platform planning job queued or blocked", "data": result.model_dump(mode="json")}

    @router.get("/factories/{factory_id}/drafts/{project_id}/jobs")
    def platform_project_jobs(
        factory_id: str,
        project_id: str,
        context: FactoryContext = Depends(platform_context_dependency),
    ) -> dict[str, object]:
        if context.factory_id != factory_id:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="target factory scope mismatch")
        draft = service.readback(project_id)
        if draft.spec.factory_id != factory_id:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="target factory scope mismatch")
        result = job_store.list_for_project(project_id, factory_id=factory_id)
        return {"success": True, "message": "target factory project vision jobs", "data": [item.model_dump(mode="json") for item in result]}

    @router.get("/factories/{factory_id}/drafts/{project_id}/jobs/{job_id}")
    def platform_job_readback(
        factory_id: str,
        project_id: str,
        job_id: str,
        context: FactoryContext = Depends(platform_context_dependency),
    ) -> dict[str, object]:
        if context.factory_id != factory_id:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="target factory scope mismatch")
        result = job_store.get(job_id)
        if result is None or result.identity.factory_id != factory_id or result.identity.project_id != project_id:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="vision job not found")
        return {"success": True, "message": "target factory vision job readback", "data": result.model_dump(mode="json")}

    return router


def create_worker_router(
    service: VisionTrainingService,
    job_store: VisionJobStore,
    *,
    worker_context_dependency: Callable[[Request], WorkerContext],
) -> APIRouter:
    """Internal worker transport with named-token and factory-binding checks."""

    service.attach_job_store(job_store)
    router = APIRouter(prefix="/api/vision-training/worker", tags=["Vision Training Worker"])

    @router.post("/jobs/claim")
    def claim_job(
        request: WorkerClaimRequest = WorkerClaimRequest(),
        context: WorkerContext = Depends(worker_context_dependency),
    ) -> dict[str, object]:
        project_ids = (request.project_id,) if request.project_id else context.project_ids
        if request.project_id and context.project_ids and request.project_id not in context.project_ids:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="worker project binding mismatch")
        result = job_store.claim_next(
            worker_id=context.worker_id,
            allowed_factory_ids=context.factory_ids,
            allowed_project_ids=project_ids,
            lease_seconds=request.lease_seconds,
        )
        return {"success": True, "message": "no queued job" if result is None else "job claimed", "data": result.model_dump(mode="json") if result else None}

    @router.post("/jobs/{job_id}/heartbeat")
    def heartbeat_job(
        job_id: str,
        request: WorkerHeartbeatRequest = WorkerHeartbeatRequest(),
        context: WorkerContext = Depends(worker_context_dependency),
    ) -> dict[str, object]:
        current = job_store.get(job_id)
        if current is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="vision job not found")
        if current.identity.factory_id not in context.factory_ids or (context.project_ids and current.identity.project_id not in context.project_ids):
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="worker binding mismatch")
        try:
            result = job_store.heartbeat(job_id=job_id, worker_id=context.worker_id, lease_seconds=request.lease_seconds)
        except (QueueLeaseError, ValueError) as exc:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
        return {"success": True, "message": "worker lease extended", "data": result.model_dump(mode="json")}

    @router.post("/jobs/{job_id}/complete")
    def complete_job(
        job_id: str,
        request: WorkerCompletion,
        context: WorkerContext = Depends(worker_context_dependency),
    ) -> dict[str, object]:
        current = job_store.get(job_id)
        if current is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="vision job not found")
        if (
            request.job_id != job_id
            or current.identity.factory_id not in context.factory_ids
            or (context.project_ids and current.identity.project_id not in context.project_ids)
            or request.receipt.identity.factory_id != current.identity.factory_id
            or request.receipt.identity.project_id != current.identity.project_id
        ):
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="worker receipt factory binding mismatch")
        try:
            result = job_store.complete(job_id=job_id, worker_id=context.worker_id, receipt=request.receipt)
            service.record_job_receipt(
                result.identity.project_id,
                request.receipt,
                factory_context=FactoryContext(
                    factory_id=result.identity.factory_id,
                    actor_id=context.worker_id,
                    actor_type="WORKER",
                ),
            )
        except (QueueLeaseError, ScopeViolationError, InvalidTaskSpecError, ValueError) as exc:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
        return {"success": True, "message": "job receipt stored", "data": result.model_dump(mode="json")}

    return router


router = create_router()
