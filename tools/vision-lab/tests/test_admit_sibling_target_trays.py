import importlib.util
import sys
from pathlib import Path

import pytest


MODULE_PATH = Path(__file__).parents[1] / "admit_sibling_target_trays.py"
sys.path.insert(0, str(MODULE_PATH.parent))
SPEC = importlib.util.spec_from_file_location("admit_sibling_target_trays", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def test_deterministic_split_is_task_disjoint_and_stable():
    rows = [{"source_task_id": f"task-{index}"} for index in range(12)]
    train, holdout = MODULE.deterministic_split(rows, 3)
    train_tasks = {row["source_task_id"] for row in train}
    holdout_tasks = {row["source_task_id"] for row in holdout}
    assert len(train) == 9
    assert len(holdout) == 3
    assert not train_tasks & holdout_tasks
    assert MODULE.deterministic_split(list(reversed(rows)), 3)[1] == list(reversed(holdout))


def test_deterministic_split_rejects_duplicate_tasks():
    with pytest.raises(RuntimeError, match="unique nonblank task ids"):
        MODULE.deterministic_split([
            {"source_task_id": "same"}, {"source_task_id": "same"},
            {"source_task_id": "other"},
        ], 1)


def test_output_source_paths_do_not_retain_temporary_directory(tmp_path, monkeypatch):
    source_queue = tmp_path / "source"
    (source_queue / "annotations-human").mkdir(parents=True)
    (source_queue / "images").mkdir()
    image = source_queue / "images" / "one.jpg"
    image.write_bytes(b"image")
    annotation = {
        "reviewed": True, "source": "human", "training_eligible": True,
        "boxes": [[0.1, 0.1, 0.9, 0.9]],
    }
    (source_queue / "annotations-human" / "one.json").write_text(
        MODULE.json.dumps(annotation), encoding="utf-8",
    )
    image_sha = MODULE.patch_dataset.sha256_file(image)
    (source_queue / "manifest.json").write_text(MODULE.json.dumps({
        "all_rows_train_only": True,
        "protected_holdout_included": False,
        "production_writes": 0,
        "rows": [{
            "packed_stem": "one", "packed_image": "images/one.jpg",
            "packed_image_sha256": image_sha, "source_sha256": image_sha,
            "task_id": "task-one",
        }],
    }), encoding="utf-8")
    monkeypatch.setattr(MODULE.tray_workflow, "phash", lambda _: "phash")
    output = tmp_path / "output"
    manifest = MODULE.write_queue(
        output, [], [source_queue], MODULE.TRAIN_VERSION, "train",
        source_queue / "manifest.json",
    )
    assert manifest["rows"][0]["source_path"] == str((output / "images" / "trayadmitted_001_one.jpg").resolve())
    assert ".tmp." not in manifest["rows"][0]["source_path"]


def test_exclude_prior_overlap_filters_task_and_image(monkeypatch, tmp_path):
    prior = tmp_path / "prior"
    prior.mkdir()
    manifest_path = prior / "manifest.json"
    manifest_path.write_text(MODULE.json.dumps({"dataset_sha256": "digest"}), encoding="utf-8")
    (prior / "provenance.json").write_text(MODULE.json.dumps({"rows": [
        {"task_id": "old-task", "source_sha256": "old-hash"},
    ]}), encoding="utf-8")
    monkeypatch.setattr(MODULE.patch_dataset, "dataset_digest", lambda _: "digest")
    accepted, excluded = MODULE.exclude_prior_overlap([
        {"crop_id": "a", "source_task_id": "old-task", "image_sha256": "new-hash"},
        {"crop_id": "b", "source_task_id": "new-task", "image_sha256": "old-hash"},
        {"crop_id": "c", "source_task_id": "kept-task", "image_sha256": "kept-hash"},
    ], manifest_path)
    assert [row["crop_id"] for row in accepted] == ["c"]
    assert [row["reason"] for row in excluded] == [
        "prior_dataset_task_overlap", "prior_dataset_image_overlap",
    ]
