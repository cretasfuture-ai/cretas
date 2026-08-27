import importlib.util
import json
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "label_small_tray_target_adjudicate.py"
SPEC = importlib.util.spec_from_file_location("target_adjudicate", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def annotation(crop_id, classes):
    missing = [name for class_id, name in MODULE.CLASSES.items() if class_id not in classes]
    return {
        "crop_id": crop_id, "reviewed": True, "source": "human", "unjudgeable": False,
        "declared_missing_classes": missing,
        "missing_confirmed_by_human": bool(missing),
        "boxes": [{"c": class_id, "b": [0.1, 0.1, 0.2, 0.2]} for class_id in classes],
    }


def make_queue(root, name, rows, annotations):
    queue = root / name
    (queue / "annotations-human").mkdir(parents=True)
    manifest = {
        "version": "liushanmen-small-tray-tight-target-review-v1",
        "queue_count": len(rows), "rows": rows,
        "production_writes": 0, "originals_modified": 0,
    }
    (queue / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    for crop_id, value in annotations.items():
        (queue / "annotations-human" / f"{crop_id}.json").write_text(
            json.dumps(value), encoding="utf-8",
        )
    return queue


def test_later_complete_crop_supersedes_earlier_review(tmp_path):
    base = {"source_photo_id": "p1", "source_task_id": "t1", "sku_code": "s1",
            "model_previous_verdict": "MISSING_WHITE_LABEL"}
    first = make_queue(tmp_path, "first", [{**base, "crop_id": "old"}],
                       {"old": annotation("old", {1})})
    second = make_queue(tmp_path, "second", [{**base, "crop_id": "new"}],
                        {"new": annotation("new", {0, 1})})
    receipt = MODULE.adjudicate([first, second], tmp_path / "receipt.json")
    assert receipt["reviewed_unique_photos"] == 1
    assert receipt["human_no_defect"] == 1
    assert receipt["results"][0]["crop_id"] == "new"
    assert receipt["superseded_reviews"][0]["superseded_crop_id"] == "old"


def test_requires_two_queues(tmp_path):
    try:
        MODULE.adjudicate([tmp_path / "one"], tmp_path / "receipt.json")
    except ValueError as exc:
        assert "two review queues" in str(exc)
    else:
        raise AssertionError("expected ValueError")
