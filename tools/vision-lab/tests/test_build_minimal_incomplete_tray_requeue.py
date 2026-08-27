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


def test_partial_detector_can_be_retained_as_locator_without_becoming_truth():
    locator = MODULE.box_in_crop([400, 300, 600, 400], [150, 50, 850, 650])
    assert locator == [0.357143, 0.416667, 0.642857, 0.583333]


def test_reconstruction_rejects_center_drift_across_adjacent_trays():
    target = [100, 100, 300, 200]
    adjacent_union = [100, 100, 550, 200]
    assert MODULE.center_shift_ratio(adjacent_union, target) == 0.625
    assert (
        MODULE.center_shift_ratio(adjacent_union, target)
        > MODULE.MAX_RECONSTRUCTION_CENTER_SHIFT_RATIO
    )


def test_center_stable_reconstruction_remains_eligible_for_review_crop():
    target = [100, 100, 300, 200]
    expanded = [80, 90, 320, 210]
    assert MODULE.center_shift_ratio(expanded, target) == 0


def test_ambiguous_locator_exclusions_must_match_reviewed_incomplete_rows():
    try:
        MODULE.validate_requested_exclusions({"crop-a", "crop-b"}, {"crop-a"})
    except RuntimeError as error:
        assert "crop-b" in str(error)
    else:
        raise AssertionError("expected unmatched ambiguous locator exclusion failure")


def test_all_matched_ambiguous_locator_exclusions_are_accepted():
    MODULE.validate_requested_exclusions({"crop-a"}, {"crop-a"})
