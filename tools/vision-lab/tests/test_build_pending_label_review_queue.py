import importlib.util
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "build_pending_label_review_queue.py"
SPEC = importlib.util.spec_from_file_location("pending_review", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def test_shallow_crop_adds_more_bottom_context():
    crop = MODULE.crop_rect([0.2, 0.2, 0.5, 0.22], 1000, 1000)
    assert crop == [155, 176, 545, 455]
    assert crop[3] - 220 > 5 * (220 - crop[1])


def test_shallow_detection_is_resolution_independent():
    box = [0.570178, 0.236006, 0.735951, 0.273211]
    crop = MODULE.crop_rect(box, 3072, 4096)
    detected_width = (box[2] - box[0]) * 3072
    assert crop[3] - crop[1] >= detected_width * 0.90
    geometry = MODULE.crop_geometry(box, crop, 3072, 4096)
    assert geometry["policy_version"] == "shallow-aspect-width-context-v2"
    assert geometry["shallow_by_aspect_ratio"] is True
    assert geometry["crop_height_to_tray_width"] >= 0.90
    assert geometry["context_requirement_met"] is True


def test_source_edge_limited_crop_is_recorded_fail_closed():
    box = [0.2, 0.92, 0.6, 0.98]
    crop = MODULE.crop_rect(box, 1000, 1000)
    geometry = MODULE.crop_geometry(box, crop, 1000, 1000)
    assert geometry["context_requirement_met"] is False
    assert geometry["source_frame_limited"] is True


def test_regular_crop_is_symmetric():
    crop = MODULE.crop_rect([0.2, 0.2, 0.5, 0.5], 1000, 1000)
    assert crop == [155, 155, 545, 545]


def test_transform_boxes_preserves_source_coordinates():
    annotation = {"boxes": [{"c": 1, "b": [0.25, 0.25, 0.75, 0.75]}]}
    converted = MODULE.transform_boxes(annotation, [100, 100, 300, 300], [50, 50, 350, 450])
    assert converted["boxes"][0]["b"] == [0.333333, 0.25, 0.666667, 0.5]
    assert converted["carried_from_previous_queue"] is True
