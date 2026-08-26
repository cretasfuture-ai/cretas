import importlib.util
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "decide_small_tray_policy_candidate.py"
SPEC = importlib.util.spec_from_file_location("small_tray_decision", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def test_replay_regressions_keeps_only_increased_false_flags():
    shard = {
        "version": "liushanmen-production-runtime-v8-replay-shard-v1",
        "productionWrites": 0,
        "originalsModified": 0,
        "mismatches": [
            {"photoId": "bad", "differences": {"false_flags": {"expected": 0, "actual": 1}}},
            {"photoId": "better", "differences": {"false_flags": {"expected": 1, "actual": 0}}},
            {"photoId": "count-only", "differences": {"verdict_counts": {"expected": {}, "actual": {}}}},
        ],
    }

    assert [row["photoId"] for row in MODULE.replay_regressions([shard])] == ["bad"]
