from __future__ import annotations

import importlib.util
from pathlib import Path


PATH = Path(__file__).resolve().parents[1] / "evaluate_reviewed_tray_round.py"
SPEC = importlib.util.spec_from_file_location("evaluate_reviewed_tray_round", PATH)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def test_matching_is_one_to_one_and_prefers_highest_iou():
    result = module.match_boxes(
        [[0, 0, 10, 10], [20, 20, 30, 30]],
        [[0, 0, 10, 10], [1, 1, 9, 9], [20, 20, 30, 30]],
    )
    assert result["tp"] == 2
    assert result["fp"] == 1
    assert result["fn"] == 0
    assert result["best_iou_per_truth"] == [1.0, 1.0]


def test_matching_reports_best_subthreshold_iou_for_boundary_diagnostics():
    result = module.match_boxes(
        [[0, 0, 10, 10]],
        [[0, 0, 4, 10]],
    )

    assert result["tp"] == 0
    assert result["best_iou_per_truth"] == [0.4]


def test_summary_reports_precision_recall_and_boundary_iou():
    summary = module.summarize([
        {"tp": 2, "fp": 1, "fn": 0, "matched_ious": [1.0, 0.5]},
        {"tp": 0, "fp": 0, "fn": 1, "matched_ious": []},
    ])
    assert summary["precision"] == 2 / 3
    assert summary["recall"] == 2 / 3
    assert summary["mean_matched_iou"] == 0.75
    assert summary["perfect_recall_images"] == 1


def test_candidate_confidence_can_be_calibrated_independently():
    import inspect

    parameters = inspect.signature(module.evaluate).parameters

    assert "production_confidence" in parameters
    assert "candidate_confidence" in parameters


def test_scale_box_expands_about_center_and_clips_to_image():
    assert module.scale_box([20, 20, 40, 40], 1.2, 100, 100) == [18, 18, 42, 42]
    assert module.scale_box([0, 0, 20, 20], 1.2, 100, 100) == [0, 0, 22, 22]
