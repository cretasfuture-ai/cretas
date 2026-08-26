import importlib.util
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "label_small_tray_extended_ownership_replay.py"
SPEC = importlib.util.spec_from_file_location("extended_replay", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def test_added_defects_only_reports_increases():
    assert MODULE.added_defects(
        {"CLEAR": 18, "UNJUDGEABLE": 1},
        {"CLEAR": 18, "MISSING_COLOR_LABEL": 1},
    ) == {"MISSING_COLOR_LABEL": 1}
    assert MODULE.added_defects({"CLEAR": 18}, {"CLEAR": 18}) == {}
