import importlib.util
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "label_small_tray_extended_ownership_diagnostic.py"
SPEC = importlib.util.spec_from_file_location("extended_ownership", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def test_extended_box_only_grows_downward():
    assert MODULE.extended_box([10, 20, 110, 70], 200, 1.5) == [10.0, 20.0, 110.0, 145.0]


def test_extended_box_clamps_to_image_bottom():
    assert MODULE.extended_box([10, 150, 110, 190], 200, 1.0) == [10.0, 150.0, 110.0, 200.0]


def test_verdict_requires_both_owned_classes():
    assert MODULE.verdict({0, 1}) == "NO_DEFECT"
    assert MODULE.verdict({0}) == "MISSING_COLOR_LABEL"
    assert MODULE.verdict({1}) == "MISSING_WHITE_LABEL"
    assert MODULE.verdict(set()) == "BOTH_MISSING"


def test_rgb_loader_applies_exif_contract(tmp_path):
    from PIL import Image

    path = tmp_path / "rgb.jpg"
    Image.new("RGB", (3, 2), (255, 0, 0)).save(path)
    frame = MODULE.load_rgb_image(path)
    assert frame.shape == (2, 3, 3)
    assert int(frame[0, 0, 0]) > int(frame[0, 0, 2])
