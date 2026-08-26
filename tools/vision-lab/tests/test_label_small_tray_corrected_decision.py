import importlib.util
import json
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "label_small_tray_corrected_decision.py"
SPEC = importlib.util.spec_from_file_location("corrected_decision", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def write(path, value):
    path.write_text(json.dumps(value), encoding="utf-8")
    return path


def test_confirmed_false_positives_keep_candidate_rejected(tmp_path):
    ids = ["p1", "p2", "p3"]
    prior = write(tmp_path / "prior.json", {
        "version": "liushanmen-small-tray-policy-candidate-decision-v1",
        "decision": "REJECTED_PROTECTED_FALSE_POSITIVE_REGRESSION",
        "production_changed": False, "deployment_started": False,
        "production_equivalent_replay": {"details": [{"photoId": value} for value in ids]},
    })
    review = write(tmp_path / "review.json", {
        "version": "liushanmen-small-tray-target-adjudication-v1",
        "reviewed_unique_photos": 3, "human_no_defect": 2,
        "human_missing_confirmed": 1, "human_unjudgeable": 0,
        "results": [
            {"source_photo_id": "p1", "human_verdict": "NO_DEFECT"},
            {"source_photo_id": "p2", "human_verdict": "MISSING_COLOR_LABEL"},
            {"source_photo_id": "p3", "human_verdict": "NO_DEFECT"},
        ],
    })
    receipt = MODULE.decide(prior, review, tmp_path / "output.json")
    assert receipt["gate_passed"] is False
    assert receipt["corrected_human_truth"]["confirmed_candidate_false_positives"] == 2
    assert receipt["corrected_human_truth"]["confirmed_candidate_true_defects"] == 1
