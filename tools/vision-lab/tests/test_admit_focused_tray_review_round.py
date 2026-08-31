from __future__ import annotations

import importlib.util
import sys
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "admit_focused_tray_review_round.py"
sys.path.insert(0, str(MODULE_PATH.parent))
SPEC = importlib.util.spec_from_file_location("admit_focused_tray_review_round", MODULE_PATH)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def test_box_validation_accepts_normalized_xyxy():
    assert module.validate_boxes([[0.1, 0.2, 0.8, 0.9]]) == [[0.1, 0.2, 0.8, 0.9]]


def test_box_validation_rejects_empty_or_invalid_truth():
    for boxes in ([], [[0.8, 0.2, 0.1, 0.9]], [[0.1, 0.2, 1.1, 0.9]]):
        try:
            module.validate_boxes(boxes)
        except RuntimeError:
            pass
        else:
            raise AssertionError(f"invalid boxes were accepted: {boxes}")
