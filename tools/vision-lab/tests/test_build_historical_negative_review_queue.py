import importlib.util
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "build_historical_negative_review_queue.py"
SPEC = importlib.util.spec_from_file_location("historical_review", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def test_missing_class_crop_uses_tray_width_for_physical_context():
    crop = MODULE.physical_context_crop([0.2, 0.2, 0.5, 0.25], 1000, 1000)
    assert crop == [155, 176, 545, 455]
    assert crop[3] - crop[1] >= 0.9 * 300


def test_physical_context_policy_is_resolution_independent():
    box = [0.570178, 0.236006, 0.735951, 0.273211]
    crop = MODULE.physical_context_crop(box, 3072, 4096)
    tray_width = (box[2] - box[0]) * 3072
    assert crop[3] - crop[1] >= 0.9 * tray_width
