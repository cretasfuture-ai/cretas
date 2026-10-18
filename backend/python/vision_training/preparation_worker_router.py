"""Bounded, authenticated photo transfer to a named factory-local worker."""
from io import BytesIO
from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import StreamingResponse
from .errors import VisionTrainingError
from .preparation import PreparationConflict


def create_preparation_worker_router(service, store, worker_dependency):
    router = APIRouter(prefix="/preparations/{project_id}")

    def owned(project_id, worker):
        try:
            draft = service.readback(project_id)
        except VisionTrainingError as exc:
            raise HTTPException(404, "preparation project not found") from exc
        # Preparation transfer precedes dataset input registration and queueing.
        # It therefore has an explicit source-project read grant, independent of
        # a later job lease. Factory-wide execution grants cannot read photos.
        if draft.spec.factory_id not in worker.factory_ids or project_id not in worker.project_ids:
            raise HTTPException(403, "named worker is not bound to this preparation project")
        return draft

    @router.get("/snapshot")
    def metadata(project_id: str, snapshotSha256: str = Query(pattern=r"^[0-9a-f]{64}$"), worker=Depends(worker_dependency)):
        draft = owned(project_id, worker)
        try:
            metadata = store.export(draft, include_content=False)
            if metadata["snapshotSha256"] != snapshotSha256:
                raise ValueError("frozen preparation version does not match the explicit transfer grant")
            return {"success": True, "data": metadata, "message": "frozen metadata only"}
        except ValueError as exc:
            raise HTTPException(409, str(exc)) from exc

    @router.get("/photos/{photo_id}/{kind}")
    def content(project_id: str, photo_id: str, kind: str,
                snapshotSha256: str = Query(pattern=r"^[0-9a-f]{64}$"), worker=Depends(worker_dependency)):
        draft = owned(project_id, worker)
        try:
            data, sha256 = store.frozen_content(draft, photo_id, kind, snapshotSha256)
        except PreparationConflict as exc:
            raise HTTPException(409, str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(404, str(exc)) from exc
        return StreamingResponse(BytesIO(data), media_type="application/octet-stream", headers={
            "X-Content-SHA256": sha256, "Cache-Control": "no-store", "Content-Length": str(len(data))})

    return router
