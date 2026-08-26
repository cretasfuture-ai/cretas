import importlib.util
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "audit_pending_tray_reconstruction.py"
SPEC = importlib.util.spec_from_file_location("tray_reconstruction", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def test_reconstructs_horizontal_two_compartment_tray_without_bridging_below():
    target = [225, 2043, 1306, 2792]
    proposals = [
        {"box": [191, 1818, 1347, 2946]},
        {"box": [1026, 1739, 2232, 2948]},
        {"box": [874, 2873, 1828, 3592]},
    ]
    merged, selected = MODULE.reconstruct_full_tray(target, proposals)
    assert selected == [0, 1]
    assert merged == [191.0, 1739.0, 2232.0, 2948.0]


def test_reconstructs_rotated_vertical_two_compartment_tray():
    target = [200, 100, 500, 600]
    proposals = [
        {"box": [180, 450, 520, 980]},
        {"box": [600, 100, 900, 600]},
    ]
    merged, selected = MODULE.reconstruct_full_tray(target, proposals)
    assert selected == [0]
    assert merged == [180.0, 100.0, 520.0, 980.0]
