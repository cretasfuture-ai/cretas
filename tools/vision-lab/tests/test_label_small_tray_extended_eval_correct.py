import importlib.util
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "label_small_tray_extended_eval_correct.py"
SPEC = importlib.util.spec_from_file_location("extended_eval_correct", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def test_module_loads():
    assert callable(MODULE.correct)
