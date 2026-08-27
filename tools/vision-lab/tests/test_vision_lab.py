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
                "warmup_bias_lr": 0.00002,
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
        self.assertEqual(options["warmup_bias_lr"], 0.00002)
        self.assertEqual(options["freeze"], 10)
        self.assertEqual(options["imgsz"], 960)
        self.assertEqual(options["mosaic"], 0.1)
        self.assertEqual(options["hsv_s"], 0.15)
        self.assertEqual(result["training"]["warmup_epochs"], 1.0)
        self.assertEqual(result["training"]["warmup_bias_lr"], 0.00002)
        self.assertEqual(parity.call_args.args[-1], 960)
        self.assertEqual(presence.call_count, 2)
        self.assertEqual(presence.call_args_list[0].args[-1], vision_lab.DEFAULT_TRAY_PAD_RATIO)
        self.assertEqual(presence.call_args_list[1].args[-1], vision_lab.DEFAULT_TRAY_PAD_RATIO)
        self.assertEqual(result["label_presence_validation"]["baseline"]["exact_matches"], 9)

    def test_train_candidate_rejects_unsafe_bias_warmup_before_training(self):
        for bias_lr in (0.1, -0.000001):
            with self.subTest(warmup_bias_lr=bias_lr), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                base = root / "accepted-v8-fallback.pt"
                base.write_bytes(b"base")
                cfg = config(root)
                cfg["training"] |= {
                    "base_model": str(base), "lr0": 0.00002, "warmup_bias_lr": bias_lr,
                }
                fake_yolo = mock.Mock()
                with mock.patch.dict(sys.modules, {"ultralytics": SimpleNamespace(YOLO=fake_yolo)}):
                    with self.assertRaisesRegex(ValueError, "warmup_bias_lr"):
                        vision_lab.train_candidate(
                            cfg, {"dataset_id": "guarded", "data_yaml": str(root / "data.yaml")}
                        )
                fake_yolo.assert_not_called()

    def test_label_presence_keeps_human_truth_and_drops_absent_class_border_prediction(self):
        class FakeTensor:
            def __init__(self, values):
                self.values = values

            def cpu(self):
                return self

            def tolist(self):
                return self.values

        class FakeBoxes:
            cls = FakeTensor([0.0, 1.0])
            conf = FakeTensor([0.9, 0.8])
            xyxyn = FakeTensor([
                [0.0, 0.3, 0.08, 0.7],
                [0.0, 0.3, 0.08, 0.7],
            ])

            def __len__(self):
                return 2

        class FakeYolo:
            def __init__(self, _model_path, task):
                self.task = task

            def predict(self, *_args, **_kwargs):
                return [SimpleNamespace(boxes=FakeBoxes())]

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            validation = root / "images" / "val"
            labels = root / "labels" / "val"
            validation.mkdir(parents=True)
            labels.mkdir(parents=True)
            (validation / "sample.jpg").write_bytes(b"image")
            # Human truth is authoritative even at a clipped crop boundary.
            # The absent colour class prediction is neighbouring context and
            # is dropped by the ownership approximation.
            (labels / "sample.txt").write_text(
                "0 0.040000 0.500000 0.080000 0.200000\n",
                encoding="utf-8",
            )
            model = root / "model.pt"
            model.write_bytes(b"model")
            fake_ultralytics = SimpleNamespace(YOLO=FakeYolo)

            with mock.patch.dict(sys.modules, {"ultralytics": fake_ultralytics}):
                result = vision_lab.evaluate_label_presence(model, validation, 0.2)

        self.assertEqual(result["exact_matches"], 1)
        self.assertEqual(result["classes"]["white_label"], {"tp": 1, "fp": 0, "fn": 0, "tn": 0})
        self.assertEqual(result["classes"]["color_label"], {"tp": 0, "fp": 0, "fn": 0, "tn": 1})
        self.assertEqual(
            result["ownership"]["dropped_predictions_at_or_above_threshold"]["color_label"], 1,
        )
        self.assertEqual(result["ownership"]["dropped_prediction_details"][0]["image"], "sample.jpg")

    def test_label_presence_rejects_ownership_pad_ratio_outside_supported_range(self):
        with self.assertRaisesRegex(ValueError, "ownership pad ratio"):
            vision_lab.evaluate_label_presence(Path("model.pt"), Path("val"), 0.2, ownership_pad_ratio=0.6)

    def test_label_presence_gate_rejects_any_fp_or_fn_regression(self):
        ownership = {
            "rule": "human_truth_authoritative_absent_class_prediction_center_inside_target",
            "pad_ratio": 0.14,
            "normalized_margin": 0.109375,
        }
        model = {
            "model_id": "regressed",
            "label_presence_validation": {
                "baseline": {
                    "ownership": ownership,
                    "total_images": 99,
                    "exact_matches": 95,
                    "classes": {
                        "white_label": {"fp": 0, "fn": 2},
                        "color_label": {"fp": 0, "fn": 2},
                    },
                },
                "candidate": {
                    "ownership": ownership,
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
        ownership = {
            "rule": "human_truth_authoritative_absent_class_prediction_center_inside_target",
            "pad_ratio": 0.14,
            "normalized_margin": 0.109375,
        }
        model = {
            "model_id": "preserved",
            "label_presence_validation": {
                "baseline": {
                    "ownership": ownership,
                    "total_images": 99,
                    "exact_matches": 95,
                    "classes": {
                        "white_label": {"fp": 0, "fn": 2},
                        "color_label": {"fp": 0, "fn": 2},
                    },
                },
                "candidate": {
                    "ownership": ownership,
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

    def test_label_presence_gate_rejects_missing_or_mismatched_ownership_contract(self):
        model = {
            "model_id": "ownership-mismatch",
            "label_presence_validation": {
                "baseline": {
                    "ownership": {
                        "rule": "human_truth_authoritative_absent_class_prediction_center_inside_target",
                        "pad_ratio": 0.14,
                        "normalized_margin": 0.109375,
                    },
                    "total_images": 1,
                    "exact_matches": 1,
                    "classes": {
                        "white_label": {"fp": 0, "fn": 0},
                        "color_label": {"fp": 0, "fn": 0},
                    },
                },
                "candidate": {
                    "ownership": {
                        "rule": "human_truth_authoritative_absent_class_prediction_center_inside_target",
                        "pad_ratio": 0.10,
                        "normalized_margin": 1 / 12,
                    },
                    "total_images": 1,
                    "exact_matches": 1,
                    "classes": {
                        "white_label": {"fp": 0, "fn": 0},
                        "color_label": {"fp": 0, "fn": 0},
                    },
                },
            },
        }

        result = vision_lab.evaluate_label_presence_gate(model)

        self.assertFalse(result["passed"])
        self.assertIn(
            "label-presence ownership contract is missing or inconsistent",
            result["errors"],
        )

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
            # Keep both split buckets represented after the final blurry sample
            # is explicitly excluded from training.
            for index, task in enumerate(("task-a", "task-d", "task-e", "task-b")):
                image = queue / "images" / f"c{index}.jpg"
                image.write_bytes(f"image-{index}".encode())
                rows.append({
                    "crop_id": f"c{index}", "image": str(image),
                    "image_sha256": vision_lab.sha256_file(image), "source_task_id": task,
                })
                annotation = {
                    "reviewed": True, "unjudgeable": False,
                    "boxes": [
                        {"c": 0, "b": [0.1, 0.1, 0.4, 0.4]},
                        {"c": 1, "b": [0.5, 0.5, 0.9, 0.9]},
                    ],
                }
                if index == 3:
                    annotation |= {
                        "training_eligible": False,
                        "training_exclusion_reason": "too blurry for stable boxes",
                    }
                (queue / "annotations-human" / f"c{index}.json").write_text(
                    json.dumps(annotation), encoding="utf-8",
                )
            (queue / "manifest.json").write_text(json.dumps({"version": "q", "rows": rows}), encoding="utf-8")
            cfg = config(root / "runtime")
            cfg["queue_roots"] = [str(queue)]
            dataset = vision_lab.build_dataset(cfg)
            self.assertTrue(dataset["task_level_split"])
            self.assertFalse(dataset["protected_holdout_included"])
            self.assertEqual(dataset["counts"]["white_boxes"], 3)
            self.assertEqual(dataset["counts"]["color_boxes"], 3)
            self.assertEqual(dataset["training_exclusions"]["total"], 1)
            self.assertEqual(
                dataset["training_exclusions"]["reasons"],
                {"too blurry for stable boxes": 1},
            )
            self.assertNotIn("c3", {row["crop_id"] for row in dataset["rows"]})
            by_task = {}
            for row in dataset["rows"]:
                by_task.setdefault(row["task_id"], set()).add(row["split"])
            self.assertTrue(all(len(splits) == 1 for splits in by_task.values()))

            excluded_annotation = queue / "annotations-human" / "c3.json"
            changed = json.loads(excluded_annotation.read_text(encoding="utf-8"))
            changed["training_exclusion_reason"] = "operator clarified blur severity"
            excluded_annotation.write_text(json.dumps(changed), encoding="utf-8")
            changed_manifest = json.loads((queue / "manifest.json").read_text(encoding="utf-8"))
            changed_manifest["rows"][3]["training_eligible"] = False
            (queue / "manifest.json").write_text(
                json.dumps(changed_manifest), encoding="utf-8",
            )
            rebuilt = vision_lab.build_dataset(cfg)
            self.assertEqual(rebuilt["dataset_id"], dataset["dataset_id"])
            self.assertEqual(
                rebuilt["training_exclusions"]["reasons"],
                {"operator clarified blur severity": 1},
            )

    def test_pending_review_crop_must_prove_physical_context_before_training(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            queue = root / "pending-review"
            (queue / "images").mkdir(parents=True)
            (queue / "annotations-human").mkdir()
            image = queue / "images" / "pending.jpg"
            image.write_bytes(b"pending")
            source = {
                "crop_id": "pending-1", "image": str(image),
                "image_sha256": vision_lab.sha256_file(image), "source_task_id": "task-1",
                "training_eligible": True,
                "review_crop_geometry": {
                    "policy_version": "shallow-aspect-width-context-v1",
                    "shallow_by_aspect_ratio": True,
                    "crop_height_to_tray_width": 0.40,
                },
            }
            (queue / "manifest.json").write_text(json.dumps({
                "version": "liushanmen-pending-target-tray-review-v1",
                "review_crop_policy_version": "shallow-aspect-width-context-v1",
                "rows": [source],
            }), encoding="utf-8")
            (queue / "annotations-human" / "pending-1.json").write_text(json.dumps({
                "reviewed": True, "unjudgeable": False,
                "boxes": [{"c": 0, "b": [0.1, 0.1, 0.4, 0.4]}],
            }), encoding="utf-8")
            cfg = config(root / "runtime")
            cfg["queue_roots"] = [str(queue)]
            with self.assertRaisesRegex(RuntimeError, "physical-context policy"):
                vision_lab.build_dataset(cfg)

            source["review_crop_geometry"] = {
                "policy_version": "shallow-aspect-width-context-v2",
                "shallow_by_aspect_ratio": True,
                "crop_height_to_tray_width": 0.40,
            }
            manifest = json.loads((queue / "manifest.json").read_text(encoding="utf-8"))
            manifest["review_crop_policy_version"] = "shallow-aspect-width-context-v2"
            manifest["rows"] = [source]
            (queue / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "incomplete physical context"):
                vision_lab.build_dataset(cfg)

    def test_dataset_repeats_only_isolated_confirmed_missing_train_samples(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            queue = root / "queue"
            (queue / "images").mkdir(parents=True)
            (queue / "annotations-human").mkdir()
            rows = []
            samples = (
                ("missing-train", "task-d", "white_label", [{"c": 1, "b": [0.1, 0.1, 0.4, 0.4]}]),
                ("missing-color-train", "task-d", "color_label", [{"c": 0, "b": [0.1, 0.1, 0.4, 0.4]}]),
                ("missing-val", "task-a", "white_label", [{"c": 1, "b": [0.1, 0.1, 0.4, 0.4]}]),
                ("normal-train", "task-e", None, [
                    {"c": 0, "b": [0.1, 0.1, 0.4, 0.4]},
                    {"c": 1, "b": [0.5, 0.5, 0.9, 0.9]},
                ]),
                ("ambiguous-train", "task-d", "white_label", [
                    {"c": 1, "b": [0.1, 0.1, 0.4, 0.4]},
                    {"c": 1, "b": [0.5, 0.5, 0.9, 0.9]},
                ]),
            )
            for crop_id, task, missing_class, boxes in samples:
                image = queue / "images" / f"{crop_id}.jpg"
                image.write_bytes(crop_id.encode())
                rows.append({
                    "crop_id": crop_id, "image": str(image),
                    "image_sha256": vision_lab.sha256_file(image),
                    "source_task_id": task,
                })
                annotation = {
                    "reviewed": True, "unjudgeable": False, "boxes": boxes,
                    "missing_confirmed_by_human": missing_class is not None,
                    "declared_missing_classes": [missing_class] if missing_class else [],
                }
                (queue / "annotations-human" / f"{crop_id}.json").write_text(
                    json.dumps(annotation), encoding="utf-8",
                )
            (queue / "manifest.json").write_text(
                json.dumps({"version": "q", "rows": rows}), encoding="utf-8",
            )
            cfg = config(root / "runtime")
            cfg["queue_roots"] = [str(queue)]
            default_dataset = vision_lab.build_dataset(cfg)
            cfg["training"]["isolated_missing_repeat"] = 1
            explicit_disabled_dataset = vision_lab.build_dataset(cfg)
            cfg["training"]["isolated_missing_repeat"] = 3

            dataset = vision_lab.build_dataset(cfg)

            self.assertEqual(default_dataset["dataset_id"], explicit_disabled_dataset["dataset_id"])
            self.assertNotEqual(default_dataset["dataset_id"], dataset["dataset_id"])
            self.assertEqual(default_dataset["counts"]["train"], 4)
            self.assertEqual(dataset["counts"]["unique_train"], 4)
            self.assertEqual(dataset["counts"]["unique_val"], 1)
            self.assertEqual(dataset["counts"]["train"], 8)
            self.assertEqual(dataset["counts"]["val"], 1)
            self.assertEqual(dataset["counts"]["isolated_missing_train_unique"], 2)
            self.assertEqual(dataset["counts"]["isolated_missing_val_unique"], 1)
            self.assertEqual(dataset["counts"]["isolated_missing_train_copies"], 4)
            repeats = [row for row in dataset["rows"] if row["sample_role"] == "isolated_missing_repeat"]
            self.assertEqual(
                [row["crop_id"] for row in repeats],
                ["missing-train", "missing-train", "missing-color-train", "missing-color-train"],
            )
            self.assertTrue(all(row["split"] == "train" for row in repeats))
            ambiguous = [row for row in dataset["rows"] if row["crop_id"] == "ambiguous-train"]
            self.assertEqual(len(ambiguous), 1)
            self.assertIsNone(ambiguous[0]["isolated_missing_class"])

            cfg["training"]["isolated_missing_repeat_by_class"] = {
                "white_label": 1,
                "color_label": 3,
            }
            class_balanced = vision_lab.build_dataset(cfg)
            self.assertNotEqual(class_balanced["dataset_id"], dataset["dataset_id"])
            self.assertEqual(class_balanced["counts"]["train"], 6)
            self.assertEqual(
                class_balanced["counts"]["isolated_missing_train_copies_by_class"],
                {"white_label": 0, "color_label": 2},
            )
            self.assertEqual(
                class_balanced["isolated_missing_supervision"]["train_repeat_by_class"],
                {"white_label": 1, "color_label": 3},
            )

    def test_confirmed_missing_annotation_rejects_contradictory_missing_box(self):
        annotation = {
            "missing_confirmed_by_human": True,
            "declared_missing_classes": ["white_label"],
            "boxes": [
                {"c": 0, "b": [0.1, 0.1, 0.4, 0.4]},
                {"c": 1, "b": [0.5, 0.5, 0.9, 0.9]},
            ],
        }
        with self.assertRaisesRegex(RuntimeError, "contains white_label box"):
            vision_lab.isolated_missing_class(annotation)

    def test_isolated_missing_class_accepts_legacy_numeric_class_and_skips_two_class_empty(self):
        legacy = {
            "missing_confirmed_by_human": True,
            "declared_missing_classes": [0],
            "boxes": [{"c": 1, "b": [0.1, 0.1, 0.4, 0.4]}],
        }
        empty = {
            "missing_confirmed_by_human": True,
            "declared_missing_classes": [0, 1],
            "boxes": [],
        }
        self.assertEqual(vision_lab.isolated_missing_class(legacy), "white_label")
        self.assertIsNone(vision_lab.isolated_missing_class(empty))

    def test_isolated_missing_repeat_rejects_unsafe_values(self):
        for value in (True, 0, 11, 2.5, "3"):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    vision_lab.isolated_missing_repeat({"isolated_missing_repeat": value})

        for overrides in (
            [],
            {},
            {"unknown": 2},
            {"white_label": True},
            {"color_label": 0},
            {"color_label": 11},
        ):
            with self.subTest(overrides=overrides):
                with self.assertRaises(ValueError):
                    vision_lab.isolated_missing_repeats({
                        "isolated_missing_repeat_by_class": overrides,
                    })

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

    def test_quarantine_receipt_excludes_queue_and_same_task_rows(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            safe_queue = root / "queues" / "safe"
            blurry_queue = root / "queues" / "blurry"
            for queue in (safe_queue, blurry_queue):
                (queue / "images").mkdir(parents=True)
                (queue / "annotations-human").mkdir()

            safe_rows = []
            for index, task_id in enumerate(("task-a", "task-d", "quarantined-task")):
                image = safe_queue / "images" / f"safe-{index}.jpg"
                image.write_bytes(f"safe-image-{index}".encode())
                safe_rows.append({
                    "crop_id": f"safe-{index}", "image": str(image),
                    "image_sha256": vision_lab.sha256_file(image), "source_task_id": task_id,
                })
                (safe_queue / "annotations-human" / f"safe-{index}.json").write_text(
                    json.dumps({"reviewed": True, "unjudgeable": False, "boxes": [
                        {"c": 0, "b": [0.1, 0.1, 0.4, 0.4]},
                        {"c": 1, "b": [0.5, 0.5, 0.9, 0.9]},
                    ]}), encoding="utf-8",
                )
            (safe_queue / "manifest.json").write_text(json.dumps({"rows": safe_rows}), encoding="utf-8")
            (blurry_queue / "manifest.json").write_text(json.dumps({"rows": [
                {"crop_id": "blurry-1", "source_task_id": "quarantined-task"},
            ]}), encoding="utf-8")
            receipt = root / "quarantine.json"
            receipt.write_text(json.dumps({
                "excluded_blurry_queue": str(blurry_queue),
                "excluded_blurry_queue_crop_count": 1,
                "excluded_blurry_queue_task_count": 1,
                "quarantined_rows_by_reason": {"labels_exist_but_crop_too_blurry": 1},
                "production_writes": 0,
            }), encoding="utf-8")
            cfg = config(root / "runtime")
            cfg["queue_globs"] = [str(root / "queues" / "*")]
            cfg["training_quarantine_receipts"] = [str(receipt)]

            dataset = vision_lab.build_dataset(cfg)

            self.assertEqual(vision_lab.discover_queue_roots(cfg), [safe_queue.resolve()])
            self.assertEqual({row["task_id"] for row in dataset["rows"]}, {"task-a", "task-d"})
            self.assertEqual(dataset["training_exclusions"]["reasons"], {
                "labels_exist_but_crop_too_blurry": 2,
            })

    def test_quarantine_receipt_inventory_drift_fails_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            queue = root / "queue"
            queue.mkdir()
            (queue / "manifest.json").write_text(json.dumps({"rows": [
                {"crop_id": "c1", "source_task_id": "task-a"},
            ]}), encoding="utf-8")
            receipt = root / "quarantine.json"
            receipt.write_text(json.dumps({
                "excluded_blurry_queue": str(queue),
                "excluded_blurry_queue_crop_count": 2,
                "excluded_blurry_queue_task_count": 1,
                "production_writes": 0,
            }), encoding="utf-8")
            cfg = config(root / "runtime")
            cfg["training_quarantine_receipts"] = [str(receipt)]
            with self.assertRaisesRegex(RuntimeError, "no longer matches its queue"):
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
