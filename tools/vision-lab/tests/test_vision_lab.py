from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock


MODULE_PATH = Path(__file__).resolve().parents[1] / "vision_lab.py"
SPEC = importlib.util.spec_from_file_location("vision_lab", MODULE_PATH)
assert SPEC and SPEC.loader
vision_lab = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(vision_lab)


def config(root: Path) -> dict:
    return {
        "version": vision_lab.VERSION,
        "runtime_root": str(root),
        "source": {
            "factory_id": "LIUSHANMEN", "ssh_host": "root@example",
            "database": "db", "initial_watermark": "2026-08-01T00:00:00",
            "max_records_per_run": 10,
        },
        "queue_roots": [], "annotator_url": "http://127.0.0.1:8792",
        "training": {"min_reviewed_images": 2, "validation_percent": 50},
        "promotion_gates": {
            "min_independent_defects": 2,
            "min_defect_hits": 2,
            "required_full_recall_groups": ["new_blind_defect"],
            "min_false_flag_improvement": 0.05,
            "max_p95_latency_ms": 8000,
            "max_latency_regression": 0.15,
        },
        "cloud_vl": {"enabled": False, "max_calls_per_run": 0, "monthly_budget_cny": 0},
    }


class VisionLabTests(unittest.TestCase):
    def test_export_parity_uses_matching_square_cpu_preprocessing(self):
        class FakeTensor:
            def __init__(self, values):
                self.values = values

            def cpu(self):
                return self

            def tolist(self):
                return self.values

        class FakeYolo:
            calls = []

            def __init__(self, model_path, task):
                self.model_path = model_path
                self.task = task

            def predict(self, image, **kwargs):
                self.calls.append((self.model_path, image, kwargs))
                class FakeBoxes:
                    cls = FakeTensor([0.0, 1.0])
                    conf = FakeTensor([0.8, 0.9])

                    def __len__(self):
                        return 2

                boxes = FakeBoxes()
                return [SimpleNamespace(boxes=boxes)]

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            validation = root / "validation"
            validation.mkdir()
            (validation / "wide.jpg").write_bytes(b"image")
            pt = root / "best.pt"
            onnx = root / "label.onnx"
            pt.write_bytes(b"pt")
            onnx.write_bytes(b"onnx")
            fake_ultralytics = SimpleNamespace(YOLO=FakeYolo)

            with mock.patch.dict(sys.modules, {"ultralytics": fake_ultralytics}):
                mismatches = vision_lab.verify_export_parity(pt, onnx, validation, 0.2, 960)

        self.assertEqual(mismatches, 0)
        self.assertEqual(len(FakeYolo.calls), 2)
        for _, _, kwargs in FakeYolo.calls:
            self.assertEqual(kwargs["device"], "cpu")
            self.assertIs(kwargs["rect"], False)
            self.assertEqual(kwargs["imgsz"], 960)

    def test_train_candidate_passes_incremental_finetune_options_and_records_them(self):
        class FakeYolo:
            train_calls = []

            def __init__(self, model_path):
                self.model_path = Path(model_path)

            def train(self, **kwargs):
                self.train_calls.append(kwargs)
                best = Path(kwargs["project"]) / kwargs["name"] / "weights" / "best.pt"
                best.parent.mkdir(parents=True)
                best.write_bytes(b"best")

            def export(self, **_kwargs):
                exported = self.model_path.with_suffix(".onnx")
                exported.write_bytes(b"onnx")
                return str(exported)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            base = root / "accepted-v8-fallback.pt"
            base.write_bytes(b"base")
            data_yaml = root / "dataset" / "data.yaml"
            (data_yaml.parent / "images" / "val").mkdir(parents=True)
            data_yaml.write_text("path: .\n", encoding="utf-8")
            cfg = config(root)
            cfg["training"] |= {
                "base_model": str(base),
                "epochs": 14,
                "imgsz": 960,
                "batch": 4,
                "patience": 5,
                "optimizer": "AdamW",
                "lr0": 0.00002,
                "lrf": 0.1,
                "weight_decay": 0.0005,
                "warmup_epochs": 1.0,
                "freeze": 10,
                "mosaic": 0.1,
                "hsv_s": 0.15,
            }
            dataset = {"dataset_id": "expanded-human", "data_yaml": str(data_yaml)}
            fake_ultralytics = SimpleNamespace(YOLO=FakeYolo)

            with (
                mock.patch.dict(sys.modules, {"ultralytics": fake_ultralytics}),
                mock.patch.object(vision_lab, "verify_export_parity", return_value=0) as parity,
                mock.patch.object(
                    vision_lab, "evaluate_label_presence",
                    side_effect=[
                        {"total_images": 10, "exact_matches": 9, "classes": {}},
                        {"total_images": 10, "exact_matches": 10, "classes": {}},
                    ],
                ) as presence,
            ):
                result = vision_lab.train_candidate(cfg, dataset)

        options = FakeYolo.train_calls[-1]
        self.assertEqual(options["optimizer"], "AdamW")
        self.assertEqual(options["lr0"], 0.00002)
        self.assertEqual(options["freeze"], 10)
        self.assertEqual(options["imgsz"], 960)
        self.assertEqual(options["mosaic"], 0.1)
        self.assertEqual(options["hsv_s"], 0.15)
        self.assertEqual(result["training"]["warmup_epochs"], 1.0)
        self.assertEqual(parity.call_args.args[-1], 960)
        self.assertEqual(presence.call_count, 2)
        self.assertEqual(result["label_presence_validation"]["baseline"]["exact_matches"], 9)

    def test_label_presence_gate_rejects_any_fp_or_fn_regression(self):
        model = {
            "model_id": "regressed",
            "label_presence_validation": {
                "baseline": {
                    "total_images": 99,
                    "exact_matches": 95,
                    "classes": {
                        "white_label": {"fp": 0, "fn": 2},
                        "color_label": {"fp": 0, "fn": 2},
                    },
                },
                "candidate": {
                    "total_images": 99,
                    "exact_matches": 94,
                    "classes": {
                        "white_label": {"fp": 1, "fn": 2},
                        "color_label": {"fp": 0, "fn": 3},
                    },
                },
            },
        }

        result = vision_lab.evaluate_label_presence_gate(model)

        self.assertFalse(result["passed"])
        self.assertIn("label-presence exact accuracy regressed", result["errors"])
        self.assertIn("label-presence white_label fp regressed", result["errors"])
        self.assertIn("label-presence color_label fn regressed", result["errors"])

    def test_label_presence_gate_accepts_candidate_that_preserves_baseline(self):
        model = {
            "model_id": "preserved",
            "label_presence_validation": {
                "baseline": {
                    "total_images": 99,
                    "exact_matches": 95,
                    "classes": {
                        "white_label": {"fp": 0, "fn": 2},
                        "color_label": {"fp": 0, "fn": 2},
                    },
                },
                "candidate": {
                    "total_images": 99,
                    "exact_matches": 95,
                    "classes": {
                        "white_label": {"fp": 0, "fn": 2},
                        "color_label": {"fp": 0, "fn": 2},
                    },
                },
            },
        }

        result = vision_lab.evaluate_label_presence_gate(model)

        self.assertTrue(result["passed"], result["errors"])

    def test_training_source_forces_offline_without_amp_probe(self):
        source = MODULE_PATH.read_text(encoding="utf-8")
        self.assertIn('os.environ["YOLO_OFFLINE"] = "true"', source)
        self.assertIn('"pretrained": False', source)
        self.assertIn('"amp": False', source)

    def test_train_candidate_rejects_string_boolean_training_options(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            base = root / "base.pt"
            base.write_bytes(b"base")
            cfg = config(root)
            cfg["training"] |= {"base_model": str(base), "cache": "false"}
            dataset = {"dataset_id": "invalid-bool", "data_yaml": str(root / "data.yaml")}
            fake_ultralytics = SimpleNamespace(YOLO=mock.Mock())

            with mock.patch.dict(sys.modules, {"ultralytics": fake_ultralytics}):
                with self.assertRaisesRegex(ValueError, "cache must be a boolean"):
                    vision_lab.train_candidate(cfg, dataset)

    def test_explicit_queue_roots_disable_globs_and_fail_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            queue = root / "queue"
            queue.mkdir()
            (queue / "manifest.json").write_text(
                json.dumps({"rows": [{"crop_id": "c1"}]}), encoding="utf-8",
            )
            config = {"queue_roots": ["old"], "queue_globs": ["*"]}
            overridden = vision_lab.config_with_queue_roots(config, [queue])
            self.assertEqual(overridden["queue_roots"], [str(queue.resolve())])
            self.assertEqual(overridden["queue_globs"], [])
            self.assertEqual(config["queue_globs"], ["*"])
            with self.assertRaisesRegex(RuntimeError, "no annotation rows"):
                vision_lab.config_with_queue_roots(config, [root / "missing"])
            with self.assertRaisesRegex(RuntimeError, "duplicates"):
                vision_lab.config_with_queue_roots(config, [queue, queue])

    def test_evaluate_candidate_passes_optional_truth_adjudication(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "registry" / "label.onnx"
            artifact.parent.mkdir()
            artifact.write_bytes(b"candidate")
            truth = root / "truth-adjudication.json"
            truth.write_text("{}", encoding="utf-8")
            cfg = config(root / "runtime")
            cfg["evaluation"] = {
                "repo_root": str(root / "repo"),
                "tray_model": str(root / "tray.onnx"),
                "production_label_model": str(root / "production-label.onnx"),
                "threshold": 0.2,
                "manifests": [str(root / "protected.json")],
                "truth_adjudication": str(truth),
            }
            model = {
                "artifact": str(artifact), "onnx_parity_mismatches": 1,
            }

            def complete(command, **_kwargs):
                output = Path(command[command.index("--output") + 1])
                output.write_text(json.dumps({"status": "evaluated"}), encoding="utf-8")
                return mock.Mock(returncode=0)

            with mock.patch.object(vision_lab.subprocess, "run", side_effect=complete) as run:
                output, metrics = vision_lab.evaluate_candidate(cfg, model)

            command = run.call_args.args[0]
            self.assertEqual(
                command[command.index("--truth-adjudication") + 1],
                str(truth.resolve()),
            )
            self.assertTrue(output.is_file())
            self.assertEqual(metrics["status"], "evaluated")

    def test_collect_is_content_addressed_and_advances_watermark(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            cfg = config(root)
            state = vision_lab.State(root)
            try:
                vision_lab.init_layout(cfg, state)
                rows = [
                    {
                        "photo_id": "p1", "task_id": "t1", "reviewed_at": "2026-08-02T01:02:03",
                        "sku_code": "SKU1", "file_url": "https://objects.example/photo.jpg?secret=ignored",
                        "annotations": [],
                    },
                    {
                        "photo_id": "p2", "task_id": "t2", "reviewed_at": "2026-08-02T01:02:04",
                        "sku_code": "SKU1", "file_url": "https://objects.example/photo-copy.jpg",
                        "annotations": [],
                    },
                ]
                with mock.patch.object(vision_lab, "download_bytes", return_value=b"image-bytes") as download:
                    result = vision_lab.collect(cfg, state, rows)
                self.assertEqual(result["records_prepared"], 2)
                self.assertEqual(result["object_downloads"], 2)
                self.assertEqual(result["object_cache_hits"], 0)
                self.assertEqual(download.call_count, 2)
                self.assertEqual(state.get_meta("watermark"), "2026-08-02T01:02:04")
                self.assertEqual(state.db.execute("SELECT COUNT(*) FROM photos").fetchone()[0], 2)
                self.assertEqual(len(list((root / "raw" / "sha256").rglob("*.jpg"))), 1)
                row = state.db.execute("SELECT * FROM photos WHERE photo_id='p1'").fetchone()
                self.assertNotIn("secret", row["object_ref"])
                self.assertTrue(Path(row["local_path"]).is_file())
                self.assertTrue(Path(result["receipt"]).is_file())
            finally:
                state.close()

    def test_collect_reuses_completed_object_download_after_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            cfg = config(root)
            state = vision_lab.State(root)
            try:
                vision_lab.init_layout(cfg, state)
                rows = [
                    {
                        "photo_id": "p1", "task_id": "t1", "reviewed_at": "2026-08-02T01:02:03",
                        "file_url": "https://objects.example/photo.jpg?signature=first", "annotations": [],
                    },
                    {
                        "photo_id": "p2", "task_id": "t2", "reviewed_at": "2026-08-02T01:02:04",
                        "file_url": "https://objects.example/photo-2.jpg", "annotations": [],
                    },
                ]
                with mock.patch.object(
                    vision_lab, "download_bytes", side_effect=[b"first-image", RuntimeError("interrupted")],
                ):
                    with self.assertRaisesRegex(RuntimeError, "interrupted"):
                        vision_lab.collect(cfg, state, rows)
                self.assertEqual(state.get_meta("watermark"), "2026-08-01T00:00:00")

                rows[0]["file_url"] = "https://objects.example/photo.jpg?signature=refreshed"
                with mock.patch.object(vision_lab, "download_bytes", return_value=b"second-image") as download:
                    result = vision_lab.collect(cfg, state, rows)
                self.assertEqual(download.call_count, 1)
                self.assertEqual(result["object_cache_hits"], 1)
                self.assertEqual(result["object_downloads"], 1)
                self.assertEqual(state.get_meta("watermark"), "2026-08-02T01:02:04")
            finally:
                state.close()

    def test_pending_queue_creates_mark(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            queue = root / "source-queue"
            (queue / "annotations-human").mkdir(parents=True)
            manifest = {"version": "queue-v1", "rows": [{"crop_id": "a"}, {"crop_id": "b"}]}
            (queue / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            (queue / "annotations-human" / "a.json").write_text(
                json.dumps({"reviewed": True, "unjudgeable": False}), encoding="utf-8"
            )
            cfg = config(root / "runtime")
            cfg["queue_roots"] = [str(queue)]
            state = vision_lab.State(Path(cfg["runtime_root"]))
            try:
                vision_lab.init_layout(cfg, state)
                result = vision_lab.scan_queues(cfg, state)
                self.assertEqual(result["pending_queues"], 1)
                mark = Path(result["mark"])
                self.assertTrue(mark.is_file())
                self.assertEqual(json.loads(mark.read_text(encoding="utf-8"))["status"], "NEEDS_ANNOTATION")
            finally:
                state.close()

    def test_dataset_uses_task_level_split_and_human_reviews_only(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            queue = root / "queue"
            (queue / "images").mkdir(parents=True)
            (queue / "annotations-human").mkdir()
            rows = []
            for index, task in enumerate(("task-a", "task-b", "task-c", "task-d")):
                image = queue / "images" / f"c{index}.jpg"
                image.write_bytes(f"image-{index}".encode())
                rows.append({
                    "crop_id": f"c{index}", "image": str(image),
                    "image_sha256": vision_lab.sha256_file(image), "source_task_id": task,
                })
                (queue / "annotations-human" / f"c{index}.json").write_text(json.dumps({
                    "reviewed": True, "unjudgeable": False,
                    "boxes": [
                        {"c": 0, "b": [0.1, 0.1, 0.4, 0.4]},
                        {"c": 1, "b": [0.5, 0.5, 0.9, 0.9]},
                    ],
                }), encoding="utf-8")
            (queue / "manifest.json").write_text(json.dumps({"version": "q", "rows": rows}), encoding="utf-8")
            cfg = config(root / "runtime")
            cfg["queue_roots"] = [str(queue)]
            dataset = vision_lab.build_dataset(cfg)
            self.assertTrue(dataset["task_level_split"])
            self.assertFalse(dataset["protected_holdout_included"])
            self.assertEqual(dataset["counts"]["white_boxes"], 4)
            self.assertEqual(dataset["counts"]["color_boxes"], 4)
            by_task = {}
            for row in dataset["rows"]:
                by_task.setdefault(row["task_id"], set()).add(row["split"])
            self.assertTrue(all(len(splits) == 1 for splits in by_task.values()))

    def test_promotion_gate_fails_closed_on_recall_regression(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "label.onnx"
            artifact.write_bytes(b"candidate")
            model = {
                "model_id": "m1", "artifact": str(artifact),
                "artifact_sha256": vision_lab.sha256_file(artifact),
            }
            metrics = {
                "artifact_sha256": model["artifact_sha256"],
                "production_pipeline_replay": True, "onnx_parity_mismatches": 0,
                "baseline": {"defect_hits": 2, "false_flags": 100},
                "candidate": {"defect_total": 2, "defect_hits": 1, "false_flags": 50, "p95_latency_ms": 100,
                              "groups": {"new_blind_defect": {"defect_total": 2, "defect_hits": 1}}},
            }
            metrics_path = root / "metrics.json"
            metrics_path.write_text(json.dumps(metrics), encoding="utf-8")
            result = vision_lab.evaluate_gate(config(root), model, metrics_path)
            self.assertFalse(result["passed"])
            self.assertIn("real defect recall regressed", result["errors"])
            self.assertIn("minimum protected-defect hit gate not met", result["errors"])
            self.assertIn("required defect group did not reach full recall: new_blind_defect", result["errors"])

    def test_promotion_gate_accepts_measured_improvement(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "label.onnx"
            artifact.write_bytes(b"candidate")
            digest = vision_lab.sha256_file(artifact)
            model = {"model_id": "m2", "artifact": str(artifact), "artifact_sha256": digest}
            metrics = {
                "artifact_sha256": digest, "production_pipeline_replay": True,
                "onnx_parity_mismatches": 0,
                "baseline": {"defect_hits": 3, "false_flags": 100, "p95_latency_ms": 1000},
                "candidate": {
                    "defect_total": 7, "defect_hits": 4, "false_flags": 80, "p95_latency_ms": 1050,
                    "groups": {"new_blind_defect": {"defect_total": 2, "defect_hits": 2}},
                },
            }
            metrics_path = root / "metrics.json"
            metrics_path.write_text(json.dumps(metrics), encoding="utf-8")
            result = vision_lab.evaluate_gate(config(root), model, metrics_path)
            self.assertTrue(result["passed"], result["errors"])

    def test_promotion_gate_rejects_evaluation_only_truth_adjudication(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "label.onnx"
            artifact.write_bytes(b"candidate")
            truth = root / "truth-adjudication.json"
            truth.write_text(json.dumps({"promotion_ready": False}), encoding="utf-8")
            digest = vision_lab.sha256_file(artifact)
            cfg = config(root)
            cfg["evaluation"] = {"truth_adjudication": str(truth)}
            model = {"model_id": "truth-shadow", "artifact": str(artifact), "artifact_sha256": digest}
            metrics = {
                "artifact_sha256": digest,
                "production_pipeline_replay": True,
                "onnx_parity_mismatches": 0,
                "truth_adjudication": {
                    "path": str(truth.resolve()),
                    "sha256": vision_lab.sha256_file(truth),
                    "promotion_ready": False,
                },
                "baseline": {"defect_hits": 2, "false_flags": 1, "p95_latency_ms": 1000},
                "candidate": {
                    "defect_total": 2, "defect_hits": 2, "false_flags": 0,
                    "p95_latency_ms": 900,
                    "groups": {"new_blind_defect": {"defect_total": 2, "defect_hits": 2}},
                },
            }
            metrics_path = root / "metrics.json"
            metrics_path.write_text(json.dumps(metrics), encoding="utf-8")

            result = vision_lab.evaluate_gate(cfg, model, metrics_path)

            self.assertFalse(result["passed"])
            self.assertIn(
                "truth adjudication is evaluation-only and not promotion-ready",
                result["errors"],
            )

    def test_promotion_gate_accepts_preserved_zero_false_flag_baseline(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "label.onnx"
            artifact.write_bytes(b"candidate")
            digest = vision_lab.sha256_file(artifact)
            model = {"model_id": "zero-fp", "artifact": str(artifact), "artifact_sha256": digest}
            metrics = {
                "artifact_sha256": digest,
                "production_pipeline_replay": True,
                "onnx_parity_mismatches": 0,
                "baseline": {
                    "defect_hits": 2, "false_flags": 0, "p95_latency_ms": 1000,
                },
                "candidate": {
                    "defect_total": 2, "defect_hits": 2,
                    "false_flags": 0, "p95_latency_ms": 1000,
                    "groups": {
                        "new_blind_defect": {"defect_total": 1, "defect_hits": 1},
                    },
                },
            }
            metrics_path = root / "metrics.json"
            metrics_path.write_text(json.dumps(metrics), encoding="utf-8")

            result = vision_lab.evaluate_gate(config(root), model, metrics_path)

            self.assertTrue(result["passed"], result["errors"])

    def test_promotion_gate_can_write_an_evaluation_scoped_receipt(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "label.onnx"
            artifact.write_bytes(b"candidate")
            digest = vision_lab.sha256_file(artifact)
            model = {"model_id": "scoped", "artifact": str(artifact), "artifact_sha256": digest}
            metrics = {
                "artifact_sha256": digest,
                "production_pipeline_replay": True,
                "onnx_parity_mismatches": 0,
                "baseline": {"defect_hits": 2, "false_flags": 0, "p95_latency_ms": 1000},
                "candidate": {
                    "defect_total": 2, "defect_hits": 2,
                    "false_flags": 0, "p95_latency_ms": 1000,
                    "groups": {"new_blind_defect": {"defect_total": 1, "defect_hits": 1}},
                },
            }
            metrics_path = root / "metrics.json"
            metrics_path.write_text(json.dumps(metrics), encoding="utf-8")
            receipt = root / "evaluation" / "gate.json"

            result = vision_lab.evaluate_gate(
                config(root), model, metrics_path, output_path=receipt,
            )

            self.assertTrue(result["passed"], result["errors"])
            self.assertTrue(receipt.is_file())
            self.assertFalse((root / "promotion-gate.json").exists())

    def test_failed_gate_still_refuses_deployment_without_explicit_override(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "label.onnx"
            artifact.write_bytes(b"candidate")
            cfg = config(root)
            cfg["deployment"] = {"auto_deploy": True}
            state = vision_lab.State(root)
            try:
                with self.assertRaisesRegex(RuntimeError, "promotion gate did not pass"):
                    vision_lab.deploy_candidate(
                        cfg, state,
                        {"model_id": "m3", "artifact": str(artifact),
                         "artifact_sha256": vision_lab.sha256_file(artifact)},
                        {"passed": False, "errors": [
                            "required defect group did not reach full recall: new_blind_defect"
                        ]},
                    )
            finally:
                state.close()

    def test_operator_override_cannot_waive_non_recall_gate_errors(self):
        with self.assertRaisesRegex(RuntimeError, "cannot waive non-recall"):
            vision_lab.validate_operator_recall_override(
                {"passed": False, "errors": ["latency regressed against production"]},
                vision_lab.OPERATOR_RECALL_OVERRIDE_TOKEN,
                "User accepted the measured candidate tradeoff for this release.",
            )

    def test_operator_override_rejects_a_gate_receipt_for_another_artifact(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "label.onnx"
            artifact.write_bytes(b"candidate")
            model = {
                "model_id": "mismatch", "artifact": str(artifact),
                "artifact_sha256": vision_lab.sha256_file(artifact),
            }
            state = vision_lab.State(root)
            try:
                with self.assertRaisesRegex(RuntimeError, "artifact hash does not match"):
                    vision_lab.deploy_candidate(
                        config(root) | {"deployment": {"auto_deploy": True}}, state, model,
                        {
                            "passed": False,
                            "errors": ["required defect group did not reach full recall: new_blind_defect"],
                            "model_id": model["model_id"],
                            "metrics": {"artifact_sha256": "b" * 64},
                        },
                        operator_override_token=vision_lab.OPERATOR_RECALL_OVERRIDE_TOKEN,
                        operator_override_reason=(
                            "User accepts incomplete new-blind recall because total defect hits improved."
                        ),
                    )
            finally:
                state.close()

    def test_operator_recall_override_is_recorded_in_deployment_receipt(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "receipts").mkdir()
            artifact = root / "label.onnx"
            artifact.write_bytes(b"candidate")
            candidate_sha = vision_lab.sha256_file(artifact)
            previous_sha = "a" * 64
            cfg = config(root)
            cfg["deployment"] = {
                "auto_deploy": True,
                "confirm_token": "YES-PROD",
                "ssh_host": "root@example",
                "remote_model_path": "/models/label.onnx",
                "service": "cretas-python",
                "health_url": "http://127.0.0.1:8083/health",
            }
            model = {"model_id": "m4", "artifact": str(artifact), "artifact_sha256": candidate_sha}
            gate_error = "required defect group did not reach full recall: new_blind_defect"
            gate = {
                "passed": False,
                "errors": [gate_error],
                "model_id": model["model_id"],
                "metrics": {"artifact_sha256": candidate_sha},
            }
            state = vision_lab.State(root)
            state.set_meta("production_model_sha256", previous_sha)
            try:
                with (
                    mock.patch.object(vision_lab, "ssh_run", side_effect=[
                        previous_sha, "", candidate_sha, "", "active", "200", candidate_sha,
                    ]),
                    mock.patch.object(
                        vision_lab.subprocess, "run", return_value=mock.Mock(returncode=0, stdout="")
                    ),
                ):
                    result = vision_lab.deploy_candidate(
                        cfg, state, model, gate,
                        operator_override_token=vision_lab.OPERATOR_RECALL_OVERRIDE_TOKEN,
                        operator_override_reason=(
                            "User accepts incomplete new-blind recall because total defect hits improved."
                        ),
                    )
                self.assertTrue(result["succeeded"])
                self.assertFalse(result["promotion_gate_passed"])
                self.assertEqual(result["promotion_gate_errors"], [gate_error])
                self.assertEqual(result["operator_override"]["waived_errors"], [gate_error])
                self.assertEqual(state.get_meta("production_model_sha256"), candidate_sha)
                self.assertTrue(Path(result["receipt"]).is_file())
            finally:
                state.close()

    def test_queue_globs_are_discovered_without_duplicates(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            queue = root / "queues" / "q1"
            queue.mkdir(parents=True)
            (queue / "manifest.json").write_text(
                json.dumps({"rows": [{"crop_id": "c1"}]}), encoding="utf-8",
            )
            cfg = config(root / "runtime")
            cfg["queue_roots"] = [str(queue)]
            cfg["queue_globs"] = [str(root / "queues" / "*")]
            self.assertEqual(vision_lab.discover_queue_roots(cfg), [queue.resolve()])

    def test_queue_globs_ignore_evaluation_manifests_without_annotation_rows(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evaluation = root / "queues" / "evaluation-replay"
            evaluation.mkdir(parents=True)
            (evaluation / "manifest.json").write_text(
                json.dumps({"records": [{"photo_id": "p1"}]}), encoding="utf-8",
            )
            cfg = config(root / "runtime")
            cfg["queue_globs"] = [str(root / "queues" / "*")]

            self.assertEqual(vision_lab.discover_queue_roots(cfg), [])

    def test_explicit_queue_root_without_annotation_rows_fails_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evaluation = root / "evaluation-replay"
            evaluation.mkdir()
            (evaluation / "manifest.json").write_text(
                json.dumps({"records": []}), encoding="utf-8",
            )
            cfg = config(root / "runtime")
            cfg["queue_roots"] = [str(evaluation)]

            with self.assertRaisesRegex(RuntimeError, "no annotation rows"):
                vision_lab.discover_queue_roots(cfg)

    def test_cloud_vl_cannot_be_enabled_without_budget(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "config.json"
            cfg = config(Path(temporary) / "runtime")
            cfg["cloud_vl"]["enabled"] = True
            path.write_text(json.dumps(cfg), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "budget caps"):
                vision_lab.load_config(path)


if __name__ == "__main__":
    unittest.main()
