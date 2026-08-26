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
