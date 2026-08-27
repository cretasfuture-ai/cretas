import importlib.util
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "label_small_tray_extended_review.py"
SPEC = importlib.util.spec_from_file_location("extended_review", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def test_select_flags_only_returns_normal_photo_defects():
    evaluation = {"details": [
        {"photoId": "normal", "humanTruth": {"kind": "normal"}, "candidate_trays": [
            {"index": 2, "candidate_verdict": "MISSING_WHITE_LABEL"},
            {"index": 3, "candidate_verdict": "NO_DEFECT"},
        ]},
        {"photoId": "defect", "humanTruth": {"kind": "defect"}, "candidate_trays": [
            {"index": 1, "candidate_verdict": "MISSING_COLOR_LABEL"},
        ]},
    ]}
    assert MODULE.select_flags(evaluation) == [{
        "photo_id": "normal", "tray_index": 2,
        "candidate_verdict": "MISSING_WHITE_LABEL",
    }]
