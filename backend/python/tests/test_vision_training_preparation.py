"""Actual image bytes, human review, persistent revisions and real queue dispatch."""
import base64
from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
import json
from pathlib import Path
import random
import sys

import pytest
from PIL import Image
from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from vision_training.models import FactoryContext, TaskSpec, RecipeAvailability, AssetReceipt, StageReceipt, JobReceipt, StageName
from vision_training.queue import receipt_hash
from vision_training.preparation import (PreparationStore, PreparationConflict, UploadPhoto, ReviewPhoto,
    ConfirmStandard, SubmitPreparation, digest)
from vision_training.preparation_router import create_preparation_router, snapshot_versions
from vision_training.runtime import create_file_backed_runtime
from vision_training.router import WorkerBinding


CTX = FactoryContext(factory_id="FA", actor_id="62", roles=("quality_manager",))


def photo(seed=1, **options):
    rng = random.Random(seed)
    image = Image.new("RGB", (32, 32))
    image.putdata([(rng.randrange(256), rng.randrange(256), rng.randrange(256)) for _ in range(1024)])
    buf = BytesIO()
    image.save(buf, format="PNG", **options)
    return base64.b64encode(buf.getvalue()).decode()


def setup(tmp_path, specialized=False):
    runtime = create_file_backed_runtime(draft_root=tmp_path / "drafts", job_db=tmp_path / "jobs.sqlite",
        specialized_factory_allowlist={"liushanmen-tray": ("FA",)})
    family = "liushanmen-tray-label-detection" if specialized else "packaging-detection"
    spec = TaskSpec(factory_id="FA", workstation_id="包装线", task_family=family, product_scope=("猪蹄",),
        packaging_scope=("袋装",), data_version="v1", recipe_version="v1", human_standard_version="v1", independent_acceptance_version="v1")
    draft = runtime.service.save_draft(runtime.service.preview(spec, factory_context=CTX), factory_context=CTX)
    return runtime, draft, PreparationStore(tmp_path / "prep.sqlite")


def upload(store, draft, revision, seed=1, purpose="training", group="学习批次"):
    return store.upload(draft, CTX, UploadPhoto(expectedRevision=revision, fileName="现场照片.png",
        contentBase64=photo(seed), purpose=purpose, captureGroup=group))


def review(store, draft, state, verdict="good"):
    return store.review(draft, CTX, ReviewPhoto(expectedRevision=state["revision"],
        photoId=state["photos"][-1]["photoId"], verdict=verdict,
        boxes=[] if verdict == "uncertain" else [{"label": "white_label" if draft.template.scope == "specialized" else "object",
            "x0": 0.1, "y0": 0.1, "x1": 0.9, "y1": 0.9}]))


def ready(store, draft):
    state = store.view(draft)
    for seed, purpose, verdict in ((1,"training","good"),(2,"training","defect"),(3,"acceptance","good"),(4,"acceptance","defect")):
        state = upload(store, draft, state["revision"], seed, purpose, "学习批次" if purpose == "training" else "另一天独立拍摄")
        state = review(store, draft, state, verdict)
    return store.standard(draft, CTX, ConfirmStandard(expectedRevision=state["revision"], passCriteria="包装完整，标签齐全", failCriteria="缺少标签或者包装破损",
        maxMissRate=1.0, maxFalseAlarmRate=2.0, confirmed=True))


def test_real_photo_review_standard_freeze_survive_restart_and_preserve_originals(tmp_path):
    runtime, draft, store = setup(tmp_path)
    state = ready(store, draft)
    assert state["readyToSubmit"] and state["standard"]["confirmedBy"] == "62"
    state = store.submit(draft, CTX, SubmitPreparation(expectedRevision=state["revision"], confirmed=True), lambda *_: None)
    assert state["submission"]["status"] == "waiting_configuration"
    assert runtime.job_store.list_for_project(draft.project_id) == ()
    again = PreparationStore(store.path).view(draft)
    assert again == state
    bundle = store.export(draft)
    assert digest(bundle["snapshot"]) == state["submission"]["snapshotSha256"]
    assert bundle["photos"][state["photos"][0]["photoId"]]["originalBase64"] == photo(1)
    assert all(p["source"] == "human" and p["reviewer"] == "62" for p in bundle["snapshot"]["photos"])
    assert all(not p["training_eligible"] for p in bundle["snapshot"]["photos"] if p["purpose"] == "acceptance")
    with pytest.raises(PreparationConflict): upload(store, draft, state["revision"], 8)
    with pytest.raises(PreparationConflict): review(store, draft, state)
    assert runtime.service.readback(draft.project_id) == draft


def test_duplicate_pixels_capture_batch_and_cross_purpose_are_rejected(tmp_path):
    _, draft, store = setup(tmp_path)
    state = upload(store, draft, 0)
    duplicate = upload(store, draft, state["revision"])
    assert duplicate == state
    for seed, group in ((1,"独立批次"),(2,"学习批次")):
        with pytest.raises(PreparationConflict): upload(store, draft, state["revision"], seed, "acceptance", group)
    # Same decoded pixels with another compression level cannot enter holdout.
    with pytest.raises(PreparationConflict):
        store.upload(draft, CTX, UploadPhoto(expectedRevision=state["revision"], fileName="换编码.png", contentBase64=photo(1,compress_level=0),purpose="acceptance",captureGroup="独立批次"))
    assert len(store.view(draft)["photos"]) == 1


def test_uncertain_not_admitted_and_photo_changes_require_standard_reconfirmation(tmp_path):
    _, draft, store = setup(tmp_path)
    state = ready(store, draft)
    state = upload(store, draft, state["revision"], 5)
    assert not state["standard"]["confirmed"] and state["standard"]["passCriteria"] == "包装完整，标签齐全"
    state = review(store, draft, state, "uncertain")
    assert not state["photos"][-1]["training_eligible"]
    assert state["photos"][-1]["reviewer"] == "62"
    with pytest.raises(ValueError):
        store.submit(draft, CTX, SubmitPreparation(expectedRevision=state["revision"],confirmed=True),lambda *_: None)


@pytest.mark.parametrize("bad", ["", "http://example.com/image.png", "e30=", base64.b64encode(b"not an image").decode()])
def test_invalid_image_is_not_written(tmp_path,bad):
    _, draft, store = setup(tmp_path)
    with pytest.raises(ValueError):
        store.upload(draft,CTX,UploadPhoto(expectedRevision=0,fileName="bad.png",contentBase64=bad,purpose="training",captureGroup="第一批"))
    assert store.view(draft)["photos"] == []


def test_stale_revision_and_concurrent_upload_do_not_overwrite_each_other(tmp_path):
    _, draft, store = setup(tmp_path)
    def save(seed):
        try: return upload(PreparationStore(store.path),draft,0,seed)
        except PreparationConflict: return None
    with ThreadPoolExecutor(2) as pool: values=list(pool.map(save,[1,2]))
    assert sum(v is not None for v in values) == 1
    assert store.view(draft)["revision"] == 1 and len(store.view(draft)["photos"]) == 1


def test_review_requires_real_box_and_server_labels(tmp_path):
    _, draft, store = setup(tmp_path)
    state=upload(store,draft,0)
    for boxes in ([],[{"label":"other-factory-label","x0":0.1,"y0":0.1,"x1":0.9,"y1":0.9}],
                  [{"label":"object","x0":0.9,"y0":0.1,"x1":0.1,"y1":0.9}]):
        with pytest.raises(ValueError): store.review(draft,CTX,ReviewPhoto(expectedRevision=1,photoId=state["photos"][0]["photoId"],verdict="good",boxes=boxes))
    assert not store.view(draft)["photos"][0]["reviewed"]


def test_holdout_reservations_apply_across_projects_of_the_same_factory(tmp_path):
    runtime,draft,store=setup(tmp_path)
    state=ready(store,draft)
    spec=draft.spec.model_copy(update={"workstation_id":"另一条包装线"})
    other=runtime.service.save_draft(runtime.service.preview(spec,factory_context=CTX),factory_context=CTX)
    with pytest.raises(PreparationConflict): upload(store,other,0,3,"training","新学习批次")
    with pytest.raises(PreparationConflict): upload(store,other,0,1,"acceptance","新试用批次")
    with pytest.raises(PreparationConflict): upload(store,other,0,8,"training","另一天独立拍摄")
    assert store.view(other)["photos"]==[]
    assert len(store.view(draft)["photos"])==4


def test_freeze_commits_before_queue_and_survives_dispatch_readback_failure(tmp_path,monkeypatch):
    runtime,draft,store=setup(tmp_path,specialized=True)
    state=ready(store,draft)
    original_save=store._save; calls=0
    def fail_second_save(db,draft,state):
        nonlocal calls
        calls+=1
        if calls==2: raise OSError("simulated readback commit failure after queue publish")
        original_save(db,draft,state)
    def dispatch(snapshot,sha):
        already_frozen=PreparationStore(store.path).view(draft)
        assert already_frozen["submission"]["snapshotSha256"]==sha
        job=runtime.job_store.enqueue(draft,stage=StageName.DATASET,config_hash="a"*64,input_hash="b"*64)
        return {"status":"queued","jobId":job.job_id,"executionProjectId":draft.project_id}
    monkeypatch.setattr(store,"_save",fail_second_save)
    with pytest.raises(OSError): store.submit(draft,CTX,SubmitPreparation(expectedRevision=state["revision"],confirmed=True),dispatch)
    persisted=PreparationStore(store.path).view(draft)
    assert persisted["submission"]["status"]=="waiting_configuration"
    assert len(runtime.job_store.list_for_project(draft.project_id))==1
    with pytest.raises(PreparationConflict): upload(store,draft,persisted["revision"],9)
    monkeypatch.setattr(store,"_save",original_save)
    recovered=store.submit(draft,CTX,SubmitPreparation(expectedRevision=persisted["revision"],confirmed=True),dispatch)
    assert recovered["submission"]["status"]=="queued"
    assert len(runtime.job_store.list_for_project(draft.project_id))==1


def test_directory_export_is_bounded_metadata_api_has_no_photo_content(tmp_path):
    _,draft,store=setup(tmp_path)
    state=ready(store,draft)
    store.submit(draft,CTX,SubmitPreparation(expectedRevision=state["revision"],confirmed=True),lambda *_:None)
    metadata=store.export(draft,include_content=False)
    assert "photos" not in metadata and "photoIds" in metadata
    bundle_path=store.export_directory(draft,tmp_path/"export-new")
    bundle=json.loads(bundle_path.read_bytes())
    assert digest(bundle["snapshot"])==metadata["snapshotSha256"]
    first=bundle["photos"][metadata["photoIds"][0]]
    assert (bundle_path.parent/first["originalFile"]).read_bytes()==base64.b64decode(photo(1))
    assert not any("Base64" in key for value in bundle["photos"].values() for key in value)
    with pytest.raises(FileExistsError): store.export_directory(draft,tmp_path/"export-new")


def test_preparation_probe_fails_startup_on_unwritable_or_invalid_database(tmp_path):
    runtime,_,_=setup(tmp_path)
    runtime.startup_probe()
    runtime.preparation_store.path.write_bytes(b"corrupt database")
    with pytest.raises(Exception): runtime.startup_probe()


def test_valid_batch_over_inline_limit_exports_all_files_without_giant_json(tmp_path):
    _,draft,store=setup(tmp_path)
    state=ready(store,draft)
    for index in range(6):
        image=Image.frombytes("RGB",(1024,1024),random.Random(500+index).randbytes(1024*1024*3))
        stream=BytesIO();image.save(stream,format="PNG")
        state=store.upload(draft,CTX,UploadPhoto(expectedRevision=state["revision"],fileName=f"大照片{index}.png",
            contentBase64=base64.b64encode(stream.getvalue()).decode(),purpose="training",captureGroup="新学习批次"))
        state=review(store,draft,state)
    state=store.standard(draft,CTX,ConfirmStandard(expectedRevision=state["revision"],passCriteria="标签齐全",failCriteria="标签缺少",
        maxMissRate=1.0,maxFalseAlarmRate=1.0,confirmed=True))
    assert state["limits"]["storedBytes"]>32*1024*1024
    store.submit(draft,CTX,SubmitPreparation(expectedRevision=state["revision"],confirmed=True),lambda *_:None)
    metadata=store.export(draft,include_content=False)
    assert len(json.dumps(metadata))<100_000
    with pytest.raises(ValueError,match="可信本地目录"):store.export(draft)
    path=store.export_directory(draft,tmp_path/"large-complete")
    exported=json.loads(path.read_bytes())
    import hashlib
    for info in exported["snapshot"]["photos"]:
        files=exported["photos"][info["photoId"]]
        assert hashlib.sha256((path.parent/files["originalFile"]).read_bytes()).hexdigest()==info["imageSha256"]
        assert hashlib.sha256((path.parent/files["renderedFile"]).read_bytes()).hexdigest()==info["renderSha256"]
    assert len(exported["photos"])==10


def test_stored_byte_budget_rejects_before_photo_is_persisted(tmp_path,monkeypatch):
    import vision_training.preparation as module
    _,draft,store=setup(tmp_path)
    monkeypatch.setattr(module,"MAX_STORED_BYTES",1)
    with pytest.raises(ValueError,match="累计大小"):upload(store,draft,0)
    assert store.view(draft)["photos"]==[]


def test_worker_transfer_needs_explicit_source_project_and_expected_snapshot(tmp_path):
    from vision_training.preparation_worker_router import create_preparation_worker_router
    from vision_training.router import make_worker_context_dependency
    runtime,draft,store=setup(tmp_path)
    state=ready(store,draft)
    frozen=store.submit(draft,CTX,SubmitPreparation(expectedRevision=state["revision"],confirmed=True),lambda *_:None)
    sha=frozen["submission"]["snapshotSha256"]
    bindings=[WorkerBinding("named-a","a"*40,frozenset({"FA"}),frozenset({draft.project_id})),
              WorkerBinding("broad-a","b"*40,frozenset({"FA"})),
              WorkerBinding("foreign-b","c"*40,frozenset({"FB"}),frozenset({draft.project_id}))]
    app=FastAPI()
    @app.middleware("http")
    async def trusted_internal(request,call_next):
        if request.headers.get("X-Internal-Secret")=="internal-test":request.state.auth_method="internal"
        return await call_next(request)
    app.include_router(create_preparation_worker_router(runtime.service,store,make_worker_context_dependency(bindings)))
    client=TestClient(app);prefix=f"/preparations/{draft.project_id}"
    headers={"X-Internal-Secret":"internal-test","X-Worker-Id":"named-a","X-Worker-Token":"a"*40}
    assert client.get(prefix+"/snapshot",params={"snapshotSha256":sha}).status_code==401
    for worker,token in (("broad-a","b"*40),("foreign-b","c"*40)):
        assert client.get(prefix+"/snapshot",params={"snapshotSha256":sha},headers={**headers,"X-Worker-Id":worker,"X-Worker-Token":token}).status_code==403
    assert client.get(prefix+"/snapshot",params={"snapshotSha256":"f"*64},headers=headers).status_code==409
    metadata=client.get(prefix+"/snapshot",params={"snapshotSha256":sha},headers=headers).json()["data"]
    assert metadata["snapshotSha256"]==sha and "photos" not in metadata
    first=metadata["photoIds"][0]
    assert client.get(prefix+f"/photos/{first}/original",params={"snapshotSha256":"f"*64},headers=headers).status_code==409
    content=client.get(prefix+f"/photos/{first}/original",params={"snapshotSha256":sha},headers=headers)
    assert content.status_code==200 and content.content==base64.b64decode(photo(1))
    import hashlib
    assert content.headers["X-Content-SHA256"]==hashlib.sha256(content.content).hexdigest()
    assert client.get(prefix.replace(draft.project_id,"vtp-"+"1"*20)+"/snapshot",params={"snapshotSha256":sha},headers=headers).status_code==404


def test_real_router_rejects_foreign_factory_and_platform_without_storage_write(tmp_path):
    runtime,draft,store=setup(tmp_path)
    def context(request:Request):
        return FactoryContext(factory_id=request.headers.get("x-test-factory","FA"),actor_id="62",actor_type=request.headers.get("x-test-actor","FACTORY_USER"))
    app=FastAPI();app.include_router(create_preparation_router(runtime.service,runtime.job_store,store,(),context))
    client=TestClient(app);path=f"/drafts/{draft.project_id}/preparation"
    for headers in ({"x-test-factory":"FB"},{"x-test-actor":"PLATFORM_ADMIN"}):
        assert client.get(path,headers=headers).status_code == 403
        assert client.post(path+"/photos",headers=headers,json={"expectedRevision":0,"fileName":"a.png","contentBase64":photo(),"purpose":"training","captureGroup":"一批"}).status_code==403
    assert not store.path.exists()
    assert client.get(path).json()["data"]["revision"]==0


def test_only_exact_frozen_factory_binding_queues_existing_dataset_stage(tmp_path):
    runtime,draft,store=setup(tmp_path,specialized=True)
    state=ready(store,draft)
    # Private duplicate fingerprints are part of the sealed evidence, so use export.
    frozen=store.submit(draft,CTX,SubmitPreparation(expectedRevision=state["revision"],confirmed=True),lambda *_: None)
    bundle=store.export(draft);sha=bundle["snapshotSha256"]
    versions=snapshot_versions(bundle["snapshot"],sha)
    recipe=RecipeAvailability(recipe_id="recipe-prepared",factory_id="FA",task_family=draft.spec.task_family,recipe_version="recipe-v1",available=True,
        template_id=draft.template.template_id,template_version="v1",sop_version=draft.sop_version,config_sha256="a"*64,
        **versions,stage_input_hashes={"dataset":"b"*64},preparation_project_id=draft.project_id,preparation_sha256=sha)
    def ctx():return CTX
    execution_spec=TaskSpec.model_validate({**draft.spec.model_dump(),**versions,"recipe_id":recipe.recipe_id,
        "recipe_version":recipe.recipe_version,"ocr_rules_version":recipe.ocr_rules_version})
    execution_preview=runtime.service.preview(execution_spec,factory_context=CTX)
    execution_id="vtp-"+execution_preview.preview_hash[:20]
    binding=WorkerBinding("local-a","a"*40,frozenset({"FA"}),frozenset({draft.project_id,execution_id}))
    def client_for(recipes,bindings=(binding,)):
        app=FastAPI();app.include_router(create_preparation_router(runtime.service,runtime.job_store,store,recipes,ctx,bindings));return TestClient(app)
    path=f"/drafts/{draft.project_id}/preparation/submit"
    wrong=recipe.model_copy(update={"preparation_sha256":"c"*64})
    state=client_for([wrong]).post(path,json={"expectedRevision":frozen["revision"],"confirmed":True}).json()["data"]
    assert state["submission"]["status"]=="waiting_configuration"
    read_only_binding=WorkerBinding("read-only","r"*40,frozenset({"FA"}),frozenset({draft.project_id}))
    state=client_for([recipe],(read_only_binding,)).post(path,json={"expectedRevision":state["revision"],"confirmed":True}).json()["data"]
    assert state["submission"]["status"]=="waiting_configuration"
    assert runtime.job_store.list_for_project(execution_id)==()
    state=client_for([recipe]).post(path,json={"expectedRevision":state["revision"],"confirmed":True}).json()["data"]
    assert state["submission"]["status"]=="queued"
    job=runtime.job_store.get(state["submission"]["jobId"])
    assert job.stage.value=="dataset" and job.status.value=="queued" and job.attempts==0
    assert job.identity.data_version==versions["data_version"]
    assert job.identity.factory_id=="FA" and job.adapter_id=="liushanmen-visionlab-v1"
    same=client_for([recipe]).post(path,json={"expectedRevision":state["revision"],"confirmed":True}).json()["data"]
    assert same["submission"]["jobId"]==job.job_id
    assert len(runtime.job_store.list_for_project(job.identity.project_id))==1
    from vision_training.router import create_worker_router,make_worker_context_dependency
    claim_app=FastAPI()
    @claim_app.middleware("http")
    async def internal(request,call_next):
        if request.headers.get("X-Internal-Secret")=="internal-test":request.state.auth_method="internal"
        return await call_next(request)
    source_only=WorkerBinding("source-only","s"*40,frozenset({"FA"}),frozenset({draft.project_id}))
    claim_app.include_router(create_worker_router(runtime.service,runtime.job_store,
        worker_context_dependency=make_worker_context_dependency((binding,source_only))))
    claims=TestClient(claim_app)
    headers={"X-Internal-Secret":"internal-test","X-Worker-Id":"local-a","X-Worker-Token":"a"*40}
    assert claims.post("/api/vision-training/worker/jobs/claim",headers={**headers,"X-Worker-Id":"source-only","X-Worker-Token":"s"*40},json={"project_id":execution_id}).status_code==403
    leased=claims.post("/api/vision-training/worker/jobs/claim",headers=headers,json={"project_id":execution_id})
    assert leased.status_code==200 and leased.json()["data"]["job_id"]==job.job_id
    stage_receipt=StageReceipt(receipt_id="synthetic-dataset-stage",stage="dataset",status="succeeded",project_id=job.identity.project_id,
        factory_id="FA",task_family=draft.spec.task_family,training_executed=False,message="queue contract fixture; no GPU training")
    asset=AssetReceipt(receipt_id="synthetic-dataset-asset",kind="dataset",status="succeeded",factory_id="FA",task_id=job.identity.project_id,
        task_family=draft.spec.task_family,data_version=job.identity.data_version,recipe_version=job.identity.recipe_version,
        human_standard_version=job.identity.human_standard_version,sha256="d"*64,reviewed_only=True,protected_holdout_excluded=True)
    receipt=JobReceipt(job_id=job.job_id,identity=job.identity,stage="dataset",status="succeeded",receipt_hash="0"*64,
        stage_receipt=stage_receipt,asset_receipts=(asset,))
    receipt=receipt.model_copy(update={"receipt_hash":receipt_hash(receipt)})
    completed=claims.post(f"/api/vision-training/worker/jobs/{job.job_id}/complete",headers=headers,
        json={"job_id":job.job_id,"receipt":receipt.model_dump(mode="json")})
    assert completed.status_code==200
    bad_source=recipe.model_copy(update={"stage_input_hashes":{"dataset":"b"*64,"train":"e"*64},"stage_source_asset_hashes":{"train":"f"*64}})
    failed=client_for([bad_source]).post(path,json={"expectedRevision":same["revision"],"confirmed":True}).json()
    assert failed["success"] is False and failed["code"]==409
    assert len(runtime.job_store.list_for_project(job.identity.project_id))==1
    train_recipe=bad_source.model_copy(update={"stage_source_asset_hashes":{"train":"d"*64}})
    for invalid in (bad_source,train_recipe.model_copy(update={"task_family":"packaging-detection"}),
                    train_recipe.model_copy(update={"template_id":"generic-packaging-detection"}),
                    train_recipe.model_copy(update={"human_standard_version":"another-standard"})):
        assert client_for([invalid]).get(path.removesuffix("/submit")).json()["data"]["canContinueTraining"] is False
    assert client_for([train_recipe],(read_only_binding,)).get(path.removesuffix("/submit")).json()["data"]["canContinueTraining"] is False
    good_client=client_for([train_recipe])
    readback=good_client.get(path.removesuffix("/submit")).json()["data"]
    assert readback["canContinueTraining"] is True
    trained=good_client.post(path,json={"expectedRevision":readback["revision"],"confirmed":True}).json()["data"]
    train_job=runtime.job_store.get(trained["submission"]["jobId"])
    assert train_job.stage.value=="train" and train_job.status.value=="queued"
    assert train_job.identity.source_asset_sha256=="d"*64 and train_job.attempts==0
    assert len(runtime.job_store.list_for_project(job.identity.project_id))==2
