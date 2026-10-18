import base64
from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
import multiprocessing
from pathlib import Path
import sqlite3

import pytest
from PIL import Image

from vision_training.models import FactoryContext, TaskSpec
from vision_training.preparation import PreparationStore, UploadPhoto
from vision_training.runtime import create_file_backed_runtime
from vision_training.store import FileDraftStore


def _photo() -> str:
    output = BytesIO()
    Image.new("RGB", (32, 32), (12, 34, 56)).save(output, format="PNG")
    return base64.b64encode(output.getvalue()).decode()


def _draft(runtime, factory="FA", project="p1"):
    context = FactoryContext(factory_id=factory, actor_id="actor")
    spec = TaskSpec(factory_id=factory, workstation_id="line", task_family="packaging-detection",
                    product_scope=("sku",), packaging_scope=("bag",), data_version="v1",
                    recipe_version="v1", human_standard_version="v1",
                    independent_acceptance_version="v1")
    draft = runtime.service.preview(spec, factory_context=context)
    saved = runtime.service.save_draft(draft, factory_context=context)
    return saved.model_copy(update={"project_id": project})


def test_photo_factory_global_and_disk_limits_fail_before_write(tmp_path, monkeypatch):
    import vision_training.preparation as module

    runtime = create_file_backed_runtime(draft_root=tmp_path / "drafts", job_db=tmp_path / "jobs.sqlite")
    draft = _draft(runtime)
    store = PreparationStore(tmp_path / "prep.sqlite")
    payload = UploadPhoto(expectedRevision=0, fileName="a.png", contentBase64=_photo(),
                          purpose="training", captureGroup="batch")
    monkeypatch.setattr(module, "MAX_FACTORY_STORED_BYTES", 1)
    with pytest.raises(ValueError, match="本厂照片资料"):
        store.upload(draft, FactoryContext(factory_id="FA", actor_id="actor"), payload)
    assert store.view(draft)["photos"] == []
    monkeypatch.setattr(module, "MAX_FACTORY_STORED_BYTES", 2 * 1024 * 1024 * 1024)
    monkeypatch.setattr(module, "MAX_GLOBAL_STORED_BYTES", 1)
    with pytest.raises(ValueError, match="系统照片资料"):
        store.upload(draft, FactoryContext(factory_id="FA", actor_id="actor"), payload)
    assert store.view(draft)["photos"] == []
    monkeypatch.setattr(module, "MAX_GLOBAL_STORED_BYTES", 10 * 1024 * 1024 * 1024)
    monkeypatch.setattr(module.shutil, "disk_usage", lambda _: (100, 100, 0))
    with pytest.raises(ValueError, match="磁盘余量"):
        store.upload(draft, FactoryContext(factory_id="FA", actor_id="actor"), payload)
    assert store.view(draft)["photos"] == []


def test_photo_project_limit_is_factory_scoped(tmp_path, monkeypatch):
    import vision_training.preparation as module

    runtime = create_file_backed_runtime(draft_root=tmp_path / "drafts", job_db=tmp_path / "jobs.sqlite")
    first, second = _draft(runtime, project="p1"), _draft(runtime, project="p2")
    store = PreparationStore(tmp_path / "prep.sqlite")
    context = FactoryContext(factory_id="FA", actor_id="actor")
    monkeypatch.setattr(module, "MAX_FACTORY_PHOTO_PROJECTS", 1)
    request = UploadPhoto(expectedRevision=0, fileName="a.png", contentBase64=_photo(),
                          purpose="training", captureGroup="batch")
    store.upload(first, context, request)
    with pytest.raises(ValueError, match="五十个上限"):
        store.upload(second, context, request)
    foreign = _draft(runtime, factory="FB", project="foreign")
    store.upload(foreign, FactoryContext(factory_id="FB", actor_id="actor"), request)


def test_factory_capacity_response_never_discloses_other_factories(tmp_path):
    runtime = create_file_backed_runtime(draft_root=tmp_path / "drafts", job_db=tmp_path / "jobs.sqlite")
    factory_a = _draft(runtime, project="a")
    factory_b = _draft(runtime, factory="FB", project="b")
    store = PreparationStore(tmp_path / "prep.sqlite")
    request = UploadPhoto(expectedRevision=0, fileName="a.png", contentBase64=_photo(),
                          purpose="training", captureGroup="batch")
    store.upload(factory_a, FactoryContext(factory_id="FA", actor_id="actor"), request)
    before = store.view(factory_a)["limits"]
    store.upload(factory_b, FactoryContext(factory_id="FB", actor_id="actor"), request)
    after = store.view(factory_a)["limits"]
    assert after == before
    assert after["factoryStoredBytes"] > 0
    assert after["factoryPhotoProjects"] == 1
    assert "globalStoredBytes" not in after
    assert "maxGlobalStoredBytes" not in after


def _publish_process(root, payload):
    draft = __import__("vision_training.models", fromlist=["TrainingProjectDraft"]).TrainingProjectDraft.model_validate(payload)
    try:
        FileDraftStore(root).create_if_absent(draft)
        return "ok"
    except ValueError as exc:
        return str(exc)


def test_file_store_capacity_is_idempotent_and_bounded(tmp_path, monkeypatch):
    import vision_training.store as module

    runtime = create_file_backed_runtime(draft_root=tmp_path / "service-drafts", job_db=tmp_path / "jobs.sqlite")
    first = _draft(runtime, project="p1")
    second = _draft(runtime, project="p2")
    store = FileDraftStore(tmp_path / "capacity-drafts")
    monkeypatch.setattr(module, "MAX_FACTORY_PROJECTS", 1)
    monkeypatch.setattr(module, "MAX_GLOBAL_PROJECTS", 2)
    assert store.create_if_absent(first) == first
    assert store.create_if_absent(first) == first
    with pytest.raises(ValueError, match="五十个上限"):
        store.create_if_absent(second)
    assert store.create_if_absent(first) == first
    other = second.model_copy(update={"project_id": "p3", "spec": second.spec.model_copy(update={"factory_id": "FB"})})
    monkeypatch.setattr(module, "MAX_FACTORY_PROJECTS", 2)
    monkeypatch.setattr(module, "MAX_GLOBAL_PROJECTS", 1)
    with pytest.raises(ValueError, match="一千个上限"):
        FileDraftStore(tmp_path / "capacity-drafts").put(other)


def test_file_store_two_processes_race_for_last_factory_project(tmp_path):
    runtime = create_file_backed_runtime(draft_root=tmp_path / "service-drafts", job_db=tmp_path / "jobs.sqlite")
    root = tmp_path / "capacity-drafts"
    store = FileDraftStore(root)
    for index in range(49):
        store.put(_draft(runtime, project=f"seed-{index}"))
    candidates = [_draft(runtime, project="race-a"), _draft(runtime, project="race-b")]
    context = multiprocessing.get_context("spawn")
    with context.Pool(2) as pool:
        results = pool.starmap(_publish_process, [(str(root), item.model_dump(mode="json")) for item in candidates])
    assert results.count("ok") == 1
    assert sum("五十个上限" in result for result in results) == 1
    assert len(FileDraftStore(root).list("FA")) == 50


def test_preparation_concurrent_last_bytes_one_wins_and_loser_is_empty(tmp_path, monkeypatch):
    import vision_training.preparation as module

    runtime = create_file_backed_runtime(draft_root=tmp_path / "drafts", job_db=tmp_path / "jobs.sqlite")
    first, second = _draft(runtime, project="bytes-a"), _draft(runtime, project="bytes-b")
    store_path = tmp_path / "prep.sqlite"
    request = UploadPhoto(expectedRevision=0, fileName="a.png", contentBase64=_photo(),
                          purpose="training", captureGroup="batch")
    probe = PreparationStore(store_path)
    probe_store = probe.upload(first, FactoryContext(factory_id="FA", actor_id="actor"), request)
    byte_limit = probe_store["storedBytes"]
    # Reset the probe project while preserving an exact one-photo budget.
    store_path.unlink()
    monkeypatch.setattr(module, "MAX_FACTORY_STORED_BYTES", byte_limit)

    def upload_one(draft):
        try:
            return (draft.project_id, "ok", PreparationStore(store_path).upload(
                draft, FactoryContext(factory_id="FA", actor_id="actor"), request))
        except Exception as exc:
            return (draft.project_id, type(exc).__name__, str(exc))

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(upload_one, (first, second)))
    assert sum(result[1] == "ok" for result in results) == 1
    loser = next(result[0] for result in results if result[1] != "ok")
    loser_state = PreparationStore(store_path).view(next(draft for draft in (first, second) if draft.project_id == loser))
    assert loser_state["revision"] == 0 and loser_state["photos"] == [] and loser_state.get("storedBytes", 0) == 0
    with sqlite3.connect(store_path) as db:
        assert db.execute("SELECT COUNT(*) FROM preparation_photo_reservations WHERE project_id=?", (loser,)).fetchone()[0] == 0
    winner = next(result for result in results if result[1] == "ok")
    winner_draft = next(draft for draft in (first, second) if draft.project_id == winner[0])
    replay = PreparationStore(store_path).upload(winner_draft, FactoryContext(factory_id="FA", actor_id="actor"),
        request.model_copy(update={"expectedRevision": 1}))
    assert replay["revision"] == 1 and len(replay["photos"]) == 1


def test_preparation_factory_byte_quota_isolated_but_global_shared(tmp_path, monkeypatch):
    import vision_training.preparation as module

    runtime = create_file_backed_runtime(draft_root=tmp_path / "drafts", job_db=tmp_path / "jobs.sqlite")
    first, foreign, third = (_draft(runtime, factory=factory, project=project)
                             for factory, project in (("FA", "a"), ("FB", "b"), ("FC", "c")))
    store = PreparationStore(tmp_path / "prep.sqlite")
    context = lambda factory: FactoryContext(factory_id=factory, actor_id="actor")
    request = UploadPhoto(expectedRevision=0, fileName="a.png", contentBase64=_photo(),
                          purpose="training", captureGroup="batch")
    first_state = store.upload(first, context("FA"), request)
    monkeypatch.setattr(module, "MAX_FACTORY_STORED_BYTES", first_state["storedBytes"])
    monkeypatch.setattr(module, "MAX_GLOBAL_STORED_BYTES", first_state["storedBytes"] * 2)
    foreign_state = store.upload(foreign, context("FB"), request)
    assert foreign_state["revision"] == 1
    with pytest.raises(ValueError, match="系统照片资料"):
        store.upload(third, context("FC"), request)
    assert store.view(third)["photos"] == []
