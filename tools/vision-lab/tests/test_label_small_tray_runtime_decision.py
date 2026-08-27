import importlib.util
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "label_small_tray_runtime_decision.py"
SPEC = importlib.util.spec_from_file_location("runtime_decision", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def test_latency_reports_stable_percentiles():
    rows = [{"modelLatencyMs": value} for value in (100, 200, 300, 400, 500)]
    assert MODULE.latency(rows) == {"mean_ms": 300.0, "p95_ms": 400.0, "max_ms": 500.0}
