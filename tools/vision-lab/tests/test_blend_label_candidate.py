from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

import torch


SCRIPT = Path(__file__).resolve().parents[1] / "blend_label_candidate.py"
sys.path.insert(0, str(SCRIPT.parent))
SPEC = importlib.util.spec_from_file_location("blend_label_candidate", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class BlendStateDictsTest(unittest.TestCase):
    def test_blends_floating_tensors_and_preserves_integer_counters(self):
        baseline = {
            "weight": torch.tensor([0.0, 4.0], dtype=torch.float32),
            "num_batches_tracked": torch.tensor(7, dtype=torch.int64),
        }
        trained = {
            "weight": torch.tensor([10.0, 0.0], dtype=torch.float32),
            "num_batches_tracked": torch.tensor(99, dtype=torch.int64),
        }

        blended, audit = MODULE.blend_state_dicts(baseline, trained, 0.2)

        torch.testing.assert_close(blended["weight"], torch.tensor([2.0, 3.2]))
        self.assertEqual(blended["num_batches_tracked"].item(), 7)
        self.assertEqual(audit["changed_floating_tensors"], 1)
        self.assertEqual(
            audit["differing_non_floating_tensors_preserved_from_baseline"],
            ["num_batches_tracked"],
        )

    def test_rejects_unsafe_alpha_and_incompatible_state(self):
        state = {"weight": torch.tensor([1.0])}
        for alpha in (0.0, 1.0, -0.1, 1.1):
            with self.subTest(alpha=alpha), self.assertRaisesRegex(ValueError, "alpha"):
                MODULE.blend_state_dicts(state, state, alpha)
        with self.assertRaisesRegex(ValueError, "different keys"):
            MODULE.blend_state_dicts(state, {"other": torch.tensor([1.0])}, 0.2)
        with self.assertRaisesRegex(ValueError, "shape differs"):
            MODULE.blend_state_dicts(state, {"weight": torch.tensor([1.0, 2.0])}, 0.2)

    def test_runtime_shape_gate_fails_closed(self):
        self.assertTrue(MODULE.runtime_shape_gate(640, 640)["passed"])
        mismatch = MODULE.runtime_shape_gate(960, 640)
        self.assertFalse(mismatch["passed"])
        self.assertIn("candidate=960", mismatch["error"])
        with self.assertRaisesRegex(ValueError, "positive"):
            MODULE.runtime_shape_gate(0, 640)


if __name__ == "__main__":
    unittest.main()
