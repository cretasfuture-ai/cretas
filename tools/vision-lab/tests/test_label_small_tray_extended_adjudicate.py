import importlib.util
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "label_small_tray_extended_adjudicate.py"
SPEC = importlib.util.spec_from_file_location("extended_adjudicate", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def test_reuses_target_human_verdict_contract():
    annotation = {
        "crop_id": "c1", "reviewed": True, "source": "human", "unjudgeable": False,
        "declared_missing_classes": ["white_label"],
        "missing_confirmed_by_human": True,
        "boxes": [{"c": 1, "b": [0.1, 0.1, 0.2, 0.2]}],
    }
    assert MODULE.TARGET.human_verdict(annotation, "c1") == "MISSING_WHITE_LABEL"
