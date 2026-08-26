import importlib.util
import json
from pathlib import Path

import pytest


MODULE_PATH = Path(__file__).parents[1] / "adjudicate_small_tray_policy_review.py"
SPEC = importlib.util.spec_from_file_location("small_tray_adjudication", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding="utf-8")


def queue(tmp_path: Path, annotation: dict) -> Path:
    root = tmp_path / "queue"
    write_json(root / "manifest.json", {
        "version": "liushanmen-small-tray-policy-review-v1",
        "queue_count": 1,
        "production_writes": 0,
        "originals_modified": 0,
        "rows": [{
            "crop_id": "crop-1", "source_photo_id": "photo-1",
            "source_task_id": "task-1", "sku_code": "sku-1",
            "model_previous_verdict": "MISSING_WHITE_LABEL",
        }],
    })
    write_json(root / "annotations-human" / "crop-1.json", {
        "crop_id": "crop-1", "reviewed": True, "source": "human",
        "unjudgeable": False, **annotation,
    })
    return root


def test_adjudicates_confirmed_missing_class(tmp_path):
    root = queue(tmp_path, {
        "declared_missing_classes": ["white_label"],
        "missing_confirmed_by_human": True,
        "boxes": [{"c": 1}],
    })

    receipt = MODULE.adjudicate(root, root / "receipt.json")

    assert receipt["human_missing_confirmed"] == 1
    assert receipt["model_agreements"] == 1


def test_adjudicates_both_present_as_no_defect(tmp_path):
    root = queue(tmp_path, {
        "declared_missing_classes": [],
        "missing_confirmed_by_human": False,
        "boxes": [{"c": 0}, {"c": 1}],
    })

    receipt = MODULE.adjudicate(root, root / "receipt.json")

    assert receipt["human_no_defect"] == 1
    assert receipt["model_agreements"] == 0


def test_rejects_unconfirmed_missing_declaration(tmp_path):
    root = queue(tmp_path, {
        "declared_missing_classes": ["white_label"],
        "missing_confirmed_by_human": False,
        "boxes": [{"c": 1}],
    })

    with pytest.raises(RuntimeError, match="confirmation contract"):
        MODULE.adjudicate(root, root / "receipt.json")
