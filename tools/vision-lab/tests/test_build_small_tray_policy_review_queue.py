import importlib.util
from pathlib import Path
from types import SimpleNamespace


MODULE_PATH = Path(__file__).parents[1] / "build_small_tray_policy_review_queue.py"
SPEC = importlib.util.spec_from_file_location("small_tray_queue", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def test_exact_events_accepts_only_bound_fail_closed_missing_verdicts():
    audit = {"failClosedEvents": [
        {
            "trayIndex": 1,
            "source": "production_full",
            "reason": MODULE.RULE,
            "previousVerdict": "MISSING_WHITE_LABEL",
            "finalVerdict": "UNJUDGEABLE",
        },
        {
            "trayIndex": 2,
            "source": "production_full",
            "reason": MODULE.RULE,
            "previousVerdict": "NO_DEFECT",
            "finalVerdict": "UNJUDGEABLE",
        },
        {
            "trayIndex": 3,
            "source": "edge_window",
            "reason": MODULE.RULE,
            "previousVerdict": "MISSING_COLOR_LABEL",
            "finalVerdict": "UNJUDGEABLE",
        },
    ]}

    assert [row["trayIndex"] for row in MODULE.exact_events(audit)] == [1]


def test_geometry_prefilter_requires_both_small_and_extreme_wide():
    class Detection:
        def __init__(self, box):
            self.box = box

        def as_xyxy(self):
            return self.box

    assert MODULE.geometry_matches([Detection([0, 0, 250, 100])]) is True
    assert MODULE.geometry_matches([Detection([0, 0, 180, 100])]) is False
    assert MODULE.geometry_matches([Detection([0, 0, 400, 150])]) is False


def test_released_missing_event_requires_usable_crop_and_production_source():
    tray = SimpleNamespace(
        index=4,
        box=[200.0, 200.0, 450.0, 300.0],
        verdict="MISSING_WHITE_LABEL",
    )
    screening = SimpleNamespace(
        trays=[tray], params=SimpleNamespace(pad_ratio=0.14),
    )
    raw = [{"box": list(tray.box), "source": "production_full"}]

    events = MODULE.released_missing_events(screening, (800, 600, 3), raw)

    assert events == [{
        "trayIndex": 4,
        "source": "production_full",
        "reason": "released_sub120_extreme_wide_production_tray",
        "previousVerdict": "MISSING_WHITE_LABEL",
        "finalVerdict": "MISSING_WHITE_LABEL",
        "cropMinimumPx": 128,
        "box": [200.0, 200.0, 450.0, 300.0],
    }]
