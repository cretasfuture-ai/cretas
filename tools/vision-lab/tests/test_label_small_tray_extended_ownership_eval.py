import importlib.util
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "label_small_tray_extended_ownership_eval.py"
SPEC = importlib.util.spec_from_file_location("extended_eval", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def test_evaluate_candidate_counts_defect_hit_and_normal_false_flag():
    details = [
        {
            "humanTruth": {"kind": "defect"},
            "defectInstances": [{"label": "MISSING_WHITE_LABEL", "coveringTrayIndices": [2]}],
            "candidate_trays": [{"index": 2, "candidate_verdict": "MISSING_WHITE_LABEL"}],
        },
        {
            "humanTruth": {"kind": "normal"}, "defectInstances": [],
            "candidate_trays": [{"index": 1, "candidate_verdict": "MISSING_COLOR_LABEL"}],
        },
    ]
    result = MODULE.evaluate_candidate(details)
    assert result["defectHits"] == 1
    assert result["normalPhotos"] == 1
    assert result["normalFalseFlags"] == 1
