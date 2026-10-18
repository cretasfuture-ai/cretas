"""Daily photo workflow, using the exact existing factory and queue boundaries."""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from .errors import VisionTrainingError
from .models import FactoryContext, JobStatus, StageName, TaskSpec
from .preparation import (PreparationStore, PreparationConflict, UploadPhoto, PhotoReference,
                          ReviewPhoto, ConfirmStandard, SubmitPreparation, digest)


def snapshot_versions(snapshot: dict, sha256: str) -> dict[str, str]:
    return {"data_version": "prep-" + sha256,
            "human_standard_version": "standard-" + digest(snapshot["standard"]),
            "independent_acceptance_version": "accept-" + digest([p for p in snapshot["photos"] if p["purpose"] == "acceptance"])}


def create_preparation_router(service, jobs, store: PreparationStore, recipes, context_dependency, worker_bindings=()):
    router = APIRouter(prefix="/drafts/{project_id}/preparation")

    def owned(project_id: str, context: FactoryContext):
        try:
            draft = service.readback(project_id)
        except VisionTrainingError as exc:
            raise HTTPException(404, "没有找到本厂这项检查，请刷新检查列表。") from exc
        if context.actor_type != "FACTORY_USER" or draft.spec.factory_id != context.factory_id:
            raise HTTPException(403, "只能查看和准备当前工厂的检查资料。")
        return draft

    def result(operation):
        try:
            return {"success": True, "message": "本厂检查资料已保存", "data": operation()}
        except PreparationConflict as exc:
            return {"success": False, "message": str(exc), "code": 409, "data": None}
        except (ValueError, VisionTrainingError) as exc:
            return {"success": False, "message": str(exc), "code": 422, "data": None}

    def exact_binding(draft, context, snapshot, sha256, *, strict=True):
        versions = snapshot_versions(snapshot, sha256)
        matches = [recipe for recipe in recipes if recipe.available
                   and recipe.factory_id == context.factory_id and recipe.task_family == draft.spec.task_family
                   and recipe.preparation_project_id == draft.project_id and recipe.preparation_sha256 == sha256
                   and recipe.template_id == draft.template.template_id and recipe.template_version == draft.template.version
                   and recipe.sop_version == draft.sop_version and draft.template.adapter_id
                   and recipe.config_sha256 and recipe.stage_input_hashes.get("dataset")
                   and all(getattr(recipe, key) == value for key, value in versions.items())]
        if not matches:
            return None
        if len(matches) != 1:
            if strict:
                raise PreparationConflict("本批资料对应多个执行设置，请维护人员明确指定后再提交。")
            return None
        recipe = matches[0]
        # No arbitrary runner or specialization can be selected by this API.
        # The loaded registry has already validated the factory/template/worker.
        if (recipe.template_id != draft.template.template_id or recipe.template_version != draft.template.version
                or recipe.sop_version != draft.sop_version or not draft.template.adapter_id):
            return None
        spec = TaskSpec.model_validate({**draft.spec.model_dump(), **versions,
            "recipe_id": recipe.recipe_id, "recipe_version": recipe.recipe_version,
            "ocr_rules_version": recipe.ocr_rules_version})
        preview = service.preview(spec, template_id=draft.template.template_id, factory_context=context)
        execution_id = "vtp-" + preview.preview_hash[:20]
        if not any(context.factory_id in worker.factory_ids and execution_id in worker.project_ids
                   for worker in worker_bindings):
            return None
        return recipe, preview

    def dispatch(draft, context, snapshot, sha256):
        binding = exact_binding(draft, context, snapshot, sha256)
        if not binding or jobs is None:
            return None
        recipe, preview = binding
        execution = service.save_draft(preview, factory_context=context)
        stage = StageName.DATASET
        predecessor_hash = None
        history = jobs.list_for_project(execution.project_id, factory_id=context.factory_id)
        dataset_job = next((job for job in history if job.stage == StageName.DATASET), None)
        if dataset_job and dataset_job.status == JobStatus.SUCCEEDED:
            if not recipe.stage_input_hashes.get("train"):
                return {"status": "queued", "stage": "dataset", "executionProjectId": execution.project_id,
                        "jobId": dataset_job.job_id, "message": "学习数据已整理，等待本厂训练输入登记；尚未开始模型训练。"}
            if not dataset_job.receipt:
                raise PreparationConflict("没有找到数据整理的完整回执，请维护人员核对。")
            asset = next((asset for asset in dataset_job.receipt.asset_receipts if asset.kind == "dataset"), None)
            if not asset or recipe.stage_source_asset_hashes.get("train") != asset.sha256:
                raise PreparationConflict("本厂训练输入与实际整理的数据不一致，请维护人员核对，不能提交学习。")
            stage = StageName.TRAIN
            predecessor_hash = asset.sha256
        job = jobs.enqueue(execution, stage=stage, config_hash=recipe.config_sha256,
                           input_hash=recipe.stage_input_hashes[stage.value], source_asset_sha256=predecessor_hash)
        return {"status": "queued", "executionProjectId": execution.project_id, "jobId": job.job_id,
                "stage": stage.value, "message": "已提交本厂模型学习，尚未完成训练，后续还需效果检查和本厂独立试用。" if stage == StageName.TRAIN
                else "本厂资料已进入现有处理队列，先整理学习数据；完成后仍需提交学习并单独验收，尚未启用模型。"}

    def presented(draft, context):
        state = store.view(draft)
        state["canContinueTraining"] = False
        submission = state.get("submission")
        if submission and jobs is not None and submission.get("executionProjectId"):
            history = jobs.list_for_project(submission["executionProjectId"], factory_id=context.factory_id)
            latest = next((job for job in history if job.stage == StageName.TRAIN), None)
            if latest is None:
                latest = next((job for job in history if job.stage == StageName.DATASET), None)
            if latest:
                submission["jobStatus"] = latest.status.value
                submission["stage"] = latest.stage.value
                if latest.status in {JobStatus.FAILED, JobStatus.BLOCKED}:
                    submission["message"] = "本厂处理没有完成，请维护人员核对执行记录；不会自动重试或启用模型。"
                elif latest.stage == StageName.TRAIN and latest.status == JobStatus.SUCCEEDED:
                    submission["message"] = "本厂模型学习已完成，仍需效果检查和本厂新照片试用；模型尚未启用。"
                if latest.stage == StageName.DATASET and latest.status == JobStatus.SUCCEEDED:
                    snapshot = store.export(draft, include_content=False)["snapshot"]
                    binding = exact_binding(draft, context, snapshot, submission["snapshotSha256"], strict=False)
                    asset = next((asset for asset in latest.receipt.asset_receipts if asset.kind == "dataset"), None) if latest.receipt else None
                    if binding and asset:
                        recipe, preview = binding
                        state["canContinueTraining"] = ("vtp-" + preview.preview_hash[:20] == latest.identity.project_id
                            and bool(recipe.stage_input_hashes.get("train"))
                            and recipe.stage_source_asset_hashes.get("train") == asset.sha256)
                    submission["message"] = "学习数据已整理，请提交本厂模型学习。" if state["canContinueTraining"] else "学习数据已整理，等待本厂训练输入接通；尚未开始模型训练。"
        return state

    @router.get("")
    def read(project_id: str, context: FactoryContext = Depends(context_dependency)):
        return result(lambda: presented(owned(project_id, context), context))

    @router.post("/photos")
    def upload(project_id: str, request: UploadPhoto, context: FactoryContext = Depends(context_dependency)):
        return result(lambda: store.upload(owned(project_id, context), context, request))

    @router.post("/image")
    def image(project_id: str, request: PhotoReference, context: FactoryContext = Depends(context_dependency)):
        return result(lambda: store.image(owned(project_id, context), request.photoId))

    @router.post("/review")
    def review(project_id: str, request: ReviewPhoto, context: FactoryContext = Depends(context_dependency)):
        return result(lambda: store.review(owned(project_id, context), context, request))

    @router.post("/standard")
    def standard(project_id: str, request: ConfirmStandard, context: FactoryContext = Depends(context_dependency)):
        return result(lambda: store.standard(owned(project_id, context), context, request))

    @router.post("/submit")
    def submit(project_id: str, request: SubmitPreparation, context: FactoryContext = Depends(context_dependency)):
        draft = owned(project_id, context)
        return result(lambda: store.submit(draft, context, request, lambda snap, sha: dispatch(draft, context, snap, sha)))

    @router.post("/export")
    def export(project_id: str, context: FactoryContext = Depends(context_dependency)):
        return result(lambda: store.export(owned(project_id, context), include_content=False))

    return router
