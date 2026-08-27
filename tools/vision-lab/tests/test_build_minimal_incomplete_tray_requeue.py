import importlib.util
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "build_minimal_incomplete_tray_requeue.py"
SPEC = importlib.util.spec_from_file_location("minimal_incomplete_requeue", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def test_wide_review_crop_keeps_large_context_around_partial_detection():
    crop = MODULE.wide_review_crop([400, 300, 600, 400], 1000, 800)
    assert crop == [150, 50, 850, 650]


def test_wide_review_crop_records_source_frame_bounds_without_overflow():
    crop = MODULE.wide_review_crop([10, 20, 210, 120], 1000, 800)
    assert crop == [0, 0, 460, 370]


def test_box_in_crop_maps_reconstructed_source_geometry():
    guide = MODULE.box_in_crop([200, 100, 600, 300], [100, 50, 900, 450])
    assert guide == [0.125, 0.125, 0.625, 0.625]


def test_reconstruction_area_ratio_requires_material_expansion():
    target = [0, 0, 100, 100]
    assert MODULE.area([0, 0, 119, 100]) / MODULE.area(target) < MODULE.MIN_RECONSTRUCTION_AREA_RATIO
    assert MODULE.area([0, 0, 120, 100]) / MODULE.area(target) >= MODULE.MIN_RECONSTRUCTION_AREA_RATIO
