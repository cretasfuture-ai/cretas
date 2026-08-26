import importlib.util
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "build_pending_label_review_queue.py"
SPEC = importlib.util.spec_from_file_location("pending_review", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def test_shallow_crop_adds_more_bottom_context():
    crop = MODULE.crop_rect([0.2, 0.2, 0.5, 0.22], 1000, 1000)
    assert crop == [155, 197, 545, 247]
    assert crop[3] - 220 > 220 - crop[1]


def test_regular_crop_is_symmetric():
    crop = MODULE.crop_rect([0.2, 0.2, 0.5, 0.5], 1000, 1000)
    assert crop == [155, 155, 545, 545]
