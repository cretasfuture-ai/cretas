import importlib.util
import json
from pathlib import Path

import pytest


MODULE_PATH = Path(__file__).parents[1] / "admit_reviewed_full_trays.py"
SPEC = importlib.util.spec_from_file_location("admit_reviewed_full_trays", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding="utf-8")


def make_queue(tmp_path: Path, annotations: list[dict]) -> Path:
    queue = tmp_path / "source"
    rows = []
    for index, annotation in enumerate(annotations):
        crop_id = f"crop-{index}"
        image = queue / "images" / f"{crop_id}.jpg"
        image.parent.mkdir(parents=True, exist_ok=True)
        image.write_bytes(f"image-{index}".encode())
        rows.append({
            "crop_id": crop_id,
            "image": str(image),
            "image_sha256": MODULE.sha256_file(image),
            "source_task_id": f"task-{index}",
            "training_eligible": False,
        })
        write_json(queue / "annotations-human" / f"{crop_id}.json", annotation)
    write_json(queue / "manifest.json", {
        "version": MODULE.SUPPORTED_SOURCE_VERSION,
        "rows": rows,
    })
    return queue


def complete_annotation(**updates):
    value = {
        "reviewed": True,
        "unjudgeable": False,
        "incomplete_tray": False,
        "training_eligible": True,
        "target_tray_box": [0.1, 0.1, 0.9, 0.9],
        "boxes": [
            {"c": 0, "b": [0.2, 0.2, 0.3, 0.3]},
            {"c": 1, "b": [0.4, 0.4, 0.6, 0.6]},
        ],
    }
    value.update(updates)
    return value


def test_admits_complete_and_excludes_incomplete_without_mutating_source(tmp_path):
    source = make_queue(tmp_path, [
        complete_annotation(),
        complete_annotation(
            incomplete_tray=True,
            training_eligible=False,
            training_exclusion_reason="incomplete_tray_requeue",
        ),
    ])
    source_annotation = source / "annotations-human" / "crop-0.json"
    before = source_annotation.read_bytes()

    result = MODULE.admit(source, tmp_path / "admitted")

    assert result["admitted_count"] == 1
    assert result["excluded_count"] == 1
    assert result["exclusion_reasons"] == {"incomplete_tray_requeue": 1}
    assert result["rows"][0]["training_eligible"] is True
    assert source_annotation.read_bytes() == before
    admitted = MODULE.load_json(tmp_path / "admitted" / "annotations-human" / "crop-0.json")
    assert admitted["boxes"] == MODULE.load_json(source_annotation)["boxes"]
    assert admitted["admission"]["human_boxes_preserved"] is True


def test_edge_touching_complete_tray_is_excluded(tmp_path):
    source = make_queue(tmp_path, [complete_annotation(target_tray_box=[0.005, 0.1, 0.9, 0.9])])
    with pytest.raises(RuntimeError, match="no reviewed complete trays"):
        MODULE.admit(source, tmp_path / "admitted")


def test_rejects_image_drift(tmp_path):
    source = make_queue(tmp_path, [complete_annotation()])
    (source / "images" / "crop-0.jpg").write_bytes(b"drift")
    with pytest.raises(RuntimeError, match="review image drift"):
        MODULE.admit(source, tmp_path / "admitted")
