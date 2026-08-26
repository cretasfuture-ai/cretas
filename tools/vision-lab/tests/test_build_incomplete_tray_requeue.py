import importlib.util
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "build_incomplete_tray_requeue.py"
SPEC = importlib.util.spec_from_file_location("incomplete_requeue", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def test_round_two_expands_around_target_center():
    crop, geometry = MODULE.expanded_crop([0.3, 0.3, 0.5, 0.4], 1000, 1000, 2)
    assert crop == [170, 170, 630, 530]
    assert geometry["review_round"] == 2
    assert geometry["expansion_pad_to_target_scale"] == 0.65


def test_later_round_expands_more_and_records_source_edge():
    round_two, _ = MODULE.expanded_crop([0.05, 0.05, 0.2, 0.12], 1000, 1000, 2)
    round_three, geometry = MODULE.expanded_crop([0.05, 0.05, 0.2, 0.12], 1000, 1000, 3)
    assert (round_three[2] - round_three[0]) > (round_two[2] - round_two[0])
    assert geometry["source_frame_limited"] is True


def test_carried_boxes_preserve_source_coordinates():
    boxes = [{"c": 1, "b": [0.25, 0.25, 0.75, 0.75]}]
    carried = MODULE.transform_boxes(boxes, [100, 100, 300, 300], [0, 0, 400, 500])
    assert carried[0]["class_id"] == 1
    assert carried[0]["bbox_normalized_xyxy"] == [0.375, 0.3, 0.625, 0.5]
    assert carried[0]["is_ground_truth"] is False


def test_target_guide_maps_source_detection_into_review_crop():
    guide = MODULE.target_guide_in_crop(
        [0.25, 0.2, 0.75, 0.6], 1000, 500, [100, 50, 900, 450],
    )
    assert guide == [0.1875, 0.125, 0.8125, 0.625]


def test_target_guide_rejects_crop_that_does_not_contain_detection():
    try:
        MODULE.target_guide_in_crop([0.1, 0.1, 0.5, 0.5], 1000, 1000, [200, 200, 800, 800])
    except RuntimeError as error:
        assert "does not contain" in str(error)
    else:
        raise AssertionError("expected target guide containment failure")


def test_pre_guide_review_is_carried_only_as_untrusted_proposal(tmp_path):
    carried = MODULE.carry_for_target_guide_reconfirmation(
        {"reviewed": True, "training_eligible": True, "boxes": [{"c": 1, "b": [0, 0, 1, 1]}]},
        tmp_path,
        "a" * 64,
    )
    assert carried["reviewed"] is False
    assert carried["training_eligible"] is False
    assert carried["boxes"] == [{"c": 1, "b": [0, 0, 1, 1]}]
    assert carried["review_round_action"] == "RECONFIRM_WITH_TARGET_GUIDE"
    assert carried["training_exclusion_reason"] == "awaiting_target_tray_guide_reconfirmation"
