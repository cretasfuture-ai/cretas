import importlib.util
from pathlib import Path

import pytest


MODULE_PATH = Path(__file__).parents[1] / "replay_pending_label_photos.py"
SPEC = importlib.util.spec_from_file_location("pending_replay", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def test_compare_trays_reports_resolved_and_new_suspicions():
    old = [
        {"index": 0, "bbox": [0.1, 0.1, 0.2, 0.2], "screenVerdict": "UNJUDGEABLE"},
        {"index": 1, "bbox": [0.3, 0.3, 0.4, 0.4], "screenVerdict": "CLEAR"},
    ]
    current = [
        {"index": 0, "bbox": [0.1, 0.1, 0.2, 0.2], "verdict": "CLEAR", "labels": []},
        {"index": 1, "bbox": [0.3, 0.3, 0.4, 0.4], "verdict": "MISSING_WHITE_LABEL", "labels": []},
    ]
    compared = MODULE.compare_trays(old, current)
    assert compared[0]["transition"] == "UNJUDGEABLE->CLEAR"
    assert compared[0]["old_suspect"] is True and compared[0]["current_suspect"] is False
    assert compared[1]["transition"] == "CLEAR->MISSING_WHITE_LABEL"
    assert compared[1]["old_suspect"] is False and compared[1]["current_suspect"] is True


def test_compare_trays_fails_closed_on_geometry_drift():
    old = [{"index": 0, "bbox": [0.1, 0.1, 0.2, 0.2], "screenVerdict": "CLEAR"}]
    current = [{"index": 0, "bbox": [0.2, 0.2, 0.3, 0.3], "verdict": "CLEAR", "labels": []}]
    with pytest.raises(RuntimeError, match="box parity drift"):
        MODULE.compare_trays(old, current)


def test_select_model_rows_keeps_only_matching_persisted_model():
    rows = [
        {"photo_id": "old", "ai_model": "model-old"},
        {"photo_id": "new", "ai_model": "model-new"},
    ]
    assert MODULE.select_model_rows(rows, "model-new") == [rows[1]]
    assert MODULE.select_model_rows(rows, None) is rows
