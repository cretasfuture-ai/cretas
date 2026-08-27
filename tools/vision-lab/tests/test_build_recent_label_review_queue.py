import importlib.util
from pathlib import Path


MODULE = Path(__file__).parents[1] / "build_recent_label_review_queue.py"
SPEC = importlib.util.spec_from_file_location("build_recent_label_review_queue", MODULE)
queue = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(queue)


def test_context_crop_keeps_extra_scene_around_small_target():
    assert queue.context_crop([100, 100, 200, 150], 1000, 1000) == [50, 25, 250, 225]


def test_normalized_box_clips_to_crop():
    assert queue.normalized_box([0, 75, 150, 250], [50, 100, 250, 200]) == [0.0, 0.0, 0.5, 1.0]


def test_selected_targets_keeps_only_misses_and_unjudgeable_target():
    audit = {
        "review": {"photoIds": ["p1"]},
        "details": [{
            "photoId": "p1", "imageSize": [100, 100],
            "productionBlind": {"trays": [{"index": 3, "box": [10, 10, 40, 40]}]},
            "defectInstances": [{
                "hit": False, "label": "MISSING_WHITE_LABEL", "coveringTrayIndices": [3],
            }],
            "humanTruth": {"kind": "defect", "reviewRows": []},
        }],
    }
    targets = queue.selected_targets(audit)
    assert len(targets) == 1
    assert targets[0]["tray"]["index"] == 3
