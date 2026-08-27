import importlib.util
import json
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "quarantine_edge_touching_target_trays.py"
SPEC = importlib.util.spec_from_file_location("edge_quarantine", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def test_edge_rule_is_fail_closed_with_small_margin():
    assert MODULE.touches_crop_edge([0.0, 0.1, 0.8, 0.9])
    assert MODULE.touches_crop_edge([0.1, 0.1, 0.995, 0.9])
    assert not MODULE.touches_crop_edge([0.01, 0.01, 0.98, 0.98])


def test_quarantine_preserves_human_work_and_excludes_training(tmp_path):
    queue = tmp_path / "queue"
    annotations = queue / "annotations-human"
    annotations.mkdir(parents=True)
    (queue / "manifest.json").write_text(json.dumps({
        "rows": [{"crop_id": "edge"}, {"crop_id": "inside"}],
    }), encoding="utf-8")
    base = {
        "reviewed": True,
        "incomplete_tray": False,
        "training_eligible": True,
        "boxes": [{"c": 0, "b": [0.2, 0.2, 0.3, 0.3]}],
    }
    edge = base | {"crop_id": "edge", "target_tray_box": [0.0, 0.1, 0.8, 0.9]}
    inside = base | {"crop_id": "inside", "target_tray_box": [0.1, 0.1, 0.8, 0.9]}
    (annotations / "edge.json").write_text(json.dumps(edge), encoding="utf-8")
    (annotations / "inside.json").write_text(json.dumps(inside), encoding="utf-8")

    receipt = MODULE.quarantine(queue, tmp_path / "receipt")
    updated = json.loads((annotations / "edge.json").read_text(encoding="utf-8"))
    untouched = json.loads((annotations / "inside.json").read_text(encoding="utf-8"))
    assert receipt["quarantined_count"] == 1
    assert updated["boxes"] == edge["boxes"]
    assert updated["target_tray_box"] == edge["target_tray_box"]
    assert updated["incomplete_tray"] is True
    assert updated["training_eligible"] is False
    assert updated["review_round_action"] == "REQUEUE_AFTER_TRAY_REDETECTION"
    assert untouched == inside
    assert (tmp_path / "receipt" / "original-annotations" / "edge.json").is_file()
