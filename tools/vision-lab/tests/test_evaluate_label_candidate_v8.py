import importlib.util
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "evaluate_label_candidate_v8.py"
sys.path.insert(0, str(MODULE_PATH.parent))
SPEC = importlib.util.spec_from_file_location("evaluate_label_candidate_v8", MODULE_PATH)
module = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(module)


class V8LabelCandidateGateTests(unittest.TestCase):
    def test_parser_accepts_production_preserving_supplement(self):
        parser = module.build_parser()
        args = parser.parse_args([
            "--repo-root", ".", "--baseline-receipt", "base.json",
            "--candidate-receipt", "candidate.json", "--truth-adjudication", "truth.json",
            "--runtime-bundle", "bundle", "--output", "result.json",
            "--production-preserving-supplement",
        ])
        self.assertTrue(args.production_preserving_supplement)

    def _metrics(self):
        return {
            "defect_total": 7,
            "defect_hits": 7,
            "false_flags": 0,
            "p95_latency_ms": 6000.0,
            "details": [{"photo_id": "d1", "kind": "defect", "hit": True}],
        }

    def test_equal_protected_metrics_pass(self):
        baseline = self._metrics()
        candidate = self._metrics()
        gate = module.promotion_gate(
            baseline, candidate, parity_mismatches=0,
            max_p95_ms=8000.0, max_latency_regression=0.15,
        )
        self.assertTrue(gate["passed"])
        self.assertTrue(gate["production_pipeline_replay"])

    def test_single_protected_regression_fails(self):
        baseline = self._metrics()
        candidate = self._metrics()
        candidate["defect_hits"] = 6
        candidate["details"] = [{"photo_id": "d1", "kind": "defect", "hit": False}]
        gate = module.promotion_gate(
            baseline, candidate, parity_mismatches=0,
            max_p95_ms=8000.0, max_latency_regression=0.15,
        )
        self.assertFalse(gate["passed"])
        self.assertEqual(gate["defect_regressions"], ["d1"])

    def test_false_flag_or_latency_regression_fails(self):
        baseline = self._metrics()
        candidate = self._metrics()
        candidate.update({"false_flags": 1, "p95_latency_ms": 7000.0})
        gate = module.promotion_gate(
            baseline, candidate, parity_mismatches=0,
            max_p95_ms=8000.0, max_latency_regression=0.15,
        )
        self.assertFalse(gate["passed"])
        self.assertIn("normal false flags increased", gate["errors"])
        self.assertIn("candidate P95 latency regressed beyond relative limit", gate["errors"])


if __name__ == "__main__":
    unittest.main()
