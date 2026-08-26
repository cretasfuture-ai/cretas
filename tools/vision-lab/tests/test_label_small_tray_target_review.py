import importlib.util
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "label_small_tray_target_review.py"
SPEC = importlib.util.spec_from_file_location("tight_target_review", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def test_tight_crop_keeps_target_centered_with_bounded_padding():
    assert MODULE.tight_crop([100, 200, 300, 300], 1000, 800, 0.15) == [70, 185, 330, 315]


def test_tight_crop_clamps_at_image_boundary():
    assert MODULE.tight_crop([0, 0, 200, 100], 1000, 800, 0.15) == [0, 0, 230, 115]


def test_tight_crop_can_expand_downward_for_partial_tray_detector_box():
    assert MODULE.tight_crop(
        [100, 200, 300, 300], 1000, 800, 0.15, 1.25,
    ) == [70, 185, 330, 425]


def test_normalise_box_clips_to_target_crop():
    assert MODULE.normalise_box([50, 200, 150, 260], [100, 180, 300, 320]) == [0.0, 0.142857, 0.25, 0.571429]
