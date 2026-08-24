from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location("evaluate_candidate", ROOT / "evaluate_candidate.py")
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = module
SPEC.loader.exec_module(module)


class EvaluateCandidateTests(unittest.TestCase):
    def test_legacy_evaluator_cannot_claim_production_pipeline_replay(self):
        source = (ROOT / "evaluate_candidate.py").read_text(encoding="utf-8")

        self.assertIn('"evaluation_pipeline": "legacy_two_model"', source)
        self.assertIn('"production_pipeline_replay": False', source)

    def test_load_records_without_adjudication_is_unchanged(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            image = root / "photo.jpg"
            Image.new("RGB", (8, 8), "white").save(image)
            manifest = root / "protected.json"
            manifest.write_text(json.dumps({"records": [{
                "photo_id": "p1", "task_id": "t1", "image": str(image),
                "human_label": "NO_DEFECT", "group": "normal",
            }]}), encoding="utf-8")

            records, audit = module.apply_configured_truth_adjudication(
                module.load_records([manifest]), [manifest], None,
            )

            self.assertEqual([row["photo_id"] for row in records], ["p1"])
            self.assertIsNone(audit)

    def test_label_evaluation_uses_hash_bound_human_sidecar(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            image = root / "photo.jpg"
            Image.new("RGB", (8, 8), "white").save(image)
            manifest = root / "protected.json"
            manifest.write_text(json.dumps({"records": [{
                "photo_id": "p1", "task_id": "t1", "image": str(image),
                "human_label": "MISSING_WHITE_LABEL", "group": "blind",
            }]}), encoding="utf-8")
            evidence = root / "human.json"
            evidence.write_text(json.dumps({
                "reviewed": True, "source": "human", "boxes": [],
            }), encoding="utf-8")
            adjudication = root / "adjudication.json"
            adjudication.write_text(json.dumps({
                "version": "label-qc-protected-truth-adjudication-v1",
                "promotion_ready": False,
                "protected_manifests": [{
                    "path": str(manifest.resolve()),
                    "sha256": module.sha256_file(manifest),
                }],
                "records": [{
                    "photo_id": "p1", "source_sha256": module.sha256_file(image),
                    "original_human_label": "MISSING_WHITE_LABEL",
                    "action": "exclude", "reason": "human_repair",
                    "evidence": [{
                        "path": str(evidence), "sha256": module.sha256_file(evidence),
                    }],
                }],
            }), encoding="utf-8")

            records, audit = module.apply_configured_truth_adjudication(
                module.load_records([manifest]), [manifest], adjudication,
            )

            self.assertEqual(records, [])
            self.assertEqual(audit["excluded_photo_ids"], ["p1"])
            self.assertEqual(audit["source"], "reviewed_human_sidecar")
            self.assertFalse(audit["protected_manifest_modified"])
            self.assertFalse(audit["promotion_ready"])

    def test_cli_writes_truth_adjudication_audit_to_metrics(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repo_root = root / "repo"
            services = repo_root / "backend" / "python" / "label_qc" / "services"
            services.mkdir(parents=True)
            for package in (services.parent, services):
                (package / "__init__.py").write_text("", encoding="utf-8")
            (services / "screening.py").write_text(
                "class ScreeningParams:\n"
                "    def __init__(self, tray_conf, label_conf):\n"
                "        self.tray_conf = tray_conf\n"
                "        self.label_conf = label_conf\n",
                encoding="utf-8",
            )
            (services / "yolo_detector.py").write_text(
                "class LabelQcYoloModels:\n"
                "    def __init__(self, model_dir):\n"
                "        self.available = True\n"
                "        self.load_error = None\n",
                encoding="utf-8",
            )

            image = root / "photo.jpg"
            Image.new("RGB", (8, 8), "white").save(image)
            manifest = root / "protected.json"
            manifest.write_text(json.dumps({"records": [{
                "photo_id": "p1", "task_id": "t1", "image": str(image),
                "human_label": "MISSING_WHITE_LABEL", "group": "blind",
            }]}), encoding="utf-8")
            evidence = root / "human.json"
            evidence.write_text(json.dumps({
                "reviewed": True, "source": "human", "boxes": [],
            }), encoding="utf-8")
            adjudication = root / "adjudication.json"
            adjudication.write_text(json.dumps({
                "version": "label-qc-protected-truth-adjudication-v1",
                "promotion_ready": False,
                "protected_manifests": [{
                    "path": str(manifest.resolve()),
                    "sha256": module.sha256_file(manifest),
                }],
                "records": [{
                    "photo_id": "p1", "source_sha256": module.sha256_file(image),
                    "original_human_label": "MISSING_WHITE_LABEL",
                    "action": "exclude", "reason": "human_repair",
                    "evidence": [{
                        "path": str(evidence), "sha256": module.sha256_file(evidence),
                    }],
                }],
            }), encoding="utf-8")
            tray = root / "tray.onnx"
            production = root / "production.onnx"
            candidate = root / "candidate.onnx"
            for artifact in (tray, production, candidate):
                artifact.write_bytes(artifact.name.encode("ascii"))
            output = root / "metrics.json"

            completed = subprocess.run([
                sys.executable, "-B", str(ROOT / "evaluate_candidate.py"),
                "--repo-root", str(repo_root),
                "--tray", str(tray),
                "--production-label", str(production),
                "--candidate-label", str(candidate),
                "--manifest", str(manifest),
                "--onnx-parity-mismatches", "0",
                "--truth-adjudication", str(adjudication),
                "--output", str(output),
            ], check=False, capture_output=True, text=True)

            self.assertEqual(completed.returncode, 0, completed.stderr)
            metrics = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(metrics["records_before_truth_adjudication"], 1)
            self.assertEqual(metrics["records"], 0)
            self.assertEqual(metrics["truth_adjudication"]["excluded_photo_ids"], ["p1"])
            self.assertFalse(metrics["truth_adjudication"]["promotion_ready"])


if __name__ == "__main__":
    unittest.main()
