import importlib.util
from pathlib import Path


MODULE = Path(__file__).parents[1] / "audit_recent_label_photos.py"
SPEC = importlib.util.spec_from_file_location("audit_recent_label_photos", MODULE)
audit = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(audit)


def test_human_truth_prefers_defect_instances_over_normal_rows():
    truth = audit.human_truth([
        {"human_label": "NO_DEFECT", "bbox": [0.1, 0.1, 0.2, 0.2]},
        {"human_label": "MISSING_WHITE_LABEL", "bbox": [0.2, 0.2, 0.3, 0.3]},
    ])
    assert truth["kind"] == "defect"
    assert len(truth["defects"]) == 1


def test_near_duplicate_clusters_do_not_merge_distant_hashes():
    rows = [
        {"photo_id": "a", "task_id": "t1", "perceptual_hash": 0},
        {"photo_id": "b", "task_id": "t1", "perceptual_hash": 1},
        {"photo_id": "c", "task_id": "t2", "perceptual_hash": (1 << 63) - 1},
    ]
    assert audit.near_duplicate_clusters(rows) == [
        {"photo_ids": ["a", "b"], "task_ids": ["t1"], "size": 2}
    ]


def test_containment_measures_truth_coverage():
    assert audit.containment([0, 0, 100, 100], [25, 25, 50, 50]) == 1.0
    assert audit.containment([0, 0, 10, 10], [20, 20, 30, 30]) == 0.0
