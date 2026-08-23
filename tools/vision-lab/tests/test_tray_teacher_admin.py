from __future__ import annotations

import argparse
import importlib.util
import json
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

from PIL import Image


MODULE_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(MODULE_DIR))
MODULE_PATH = MODULE_DIR / "locateanything_teacher_admin.py"
SPEC = importlib.util.spec_from_file_location("locateanything_teacher_admin", MODULE_PATH)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class FakeCuda:
    @staticmethod
    def memory_allocated() -> int:
        return 123

    @staticmethod
    def reset_peak_memory_stats() -> None:
        return None

    @staticmethod
    def max_memory_allocated() -> int:
        return 456


class FakeTeacher:
    def __init__(self, model_path: str, revision: str) -> None:
        self.model_path = model_path
        self.revision = revision
        self.torch = type("FakeTorch", (), {"cuda": FakeCuda})()

    @staticmethod
    def predict(image: Image.Image, _prompt: str) -> str:
        assert max(image.size) <= module.TEACHER_MAX_SIDE
        return (
            "<ref>tray</ref><box><100><200><500><500></box>"
            "<ref>duplicate</ref><box><101><201><501><501></box>"
        )


class TrayTeacherAdminTests(unittest.TestCase):
    def test_parse_crop_requires_explicit_positive_pixel_region(self):
        self.assertEqual(module.parse_crop("10,20,310,220"), (10, 20, 310, 220))
        for value in ("10,20,30", "10,20,5,30", "-1,0,20,20", "x,0,20,20"):
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                module.parse_crop(value)

    def test_seal_requires_explicit_new_snapshot_and_confirmation(self):
        with tempfile.TemporaryDirectory() as temporary:
            snapshot = Path(temporary)
            with self.assertRaisesRegex(RuntimeError, "explicit new snapshot"):
                module.validate_seal_request(None, module.SEAL_CONFIRMATION)
            with self.assertRaisesRegex(RuntimeError, "confirm-seal"):
                module.validate_seal_request(snapshot, None)
            with self.assertRaisesRegex(RuntimeError, "fixed B teacher path"):
                module.validate_seal_request(module.DEFAULT_TEACHER_PATH, module.SEAL_CONFIRMATION)
            self.assertEqual(
                module.validate_seal_request(snapshot, module.SEAL_CONFIRMATION), snapshot,
            )
            (snapshot / module.TEACHER_MODEL_RECEIPT).write_text("{}", encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "already sealed"):
                module.validate_seal_request(snapshot, module.SEAL_CONFIRMATION)

    def test_smoke_worker_is_bounded_and_receipt_records_integrity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            image_path = root / "readonly-source.png"
            Image.new("RGB", (1200, 800), (30, 40, 50)).save(image_path)
            receipt_path = root / "receipts" / "smoke.json"
            integrity = {"revision": module.TEACHER_REVISION, "files_verified": 38}
            with (
                mock.patch.object(module, "verify_teacher_model", return_value=integrity),
                mock.patch.object(module, "LocateAnythingTeacher", FakeTeacher),
            ):
                worker = module._smoke_worker_body(
                    root / "model", module.TEACHER_REVISION, image_path,
                    (100, 100, 1100, 700), "all",
                )
            with mock.patch.object(module, "run_smoke_worker", return_value=worker):
                result = module.smoke(
                    root / "model", module.TEACHER_REVISION, image_path,
                    (100, 100, 1100, 700), "all", receipt_path,
                )
            persisted = json.loads(receipt_path.read_text(encoding="utf-8"))
            self.assertEqual(result["status"], "passed")
            self.assertEqual(result["inference_size"], [640, 384])
            self.assertEqual(result["max_new_tokens"], 256)
            self.assertLess(result["crop_area_ratio"], module.SMOKE_MAX_CROP_AREA_RATIO)
            self.assertEqual(result["raw_box_count"], 2)
            self.assertEqual(result["deduplicated_box_count"], 1)
            self.assertFalse(result["full_image_inference"])
            self.assertEqual(result["model_integrity"], integrity)
            self.assertEqual(persisted, result)

    def test_worker_fails_closed_before_model_load_when_integrity_fails(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            image_path = root / "readonly-source.png"
            Image.new("RGB", (100, 100), (30, 40, 50)).save(image_path)
            with (
                mock.patch.object(
                    module, "verify_teacher_model",
                    side_effect=RuntimeError("teacher model receipt missing"),
                ),
                mock.patch.object(module, "LocateAnythingTeacher") as teacher,
            ):
                with self.assertRaisesRegex(RuntimeError, "receipt missing"):
                    module._smoke_worker_body(
                        root / "model", module.TEACHER_REVISION, image_path,
                        (0, 0, 50, 50), "all",
                    )
            teacher.assert_not_called()

    def test_smoke_rejects_full_or_oversized_crop_before_worker_or_receipt(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            image_path = root / "readonly-source.png"
            Image.new("RGB", (100, 100), (30, 40, 50)).save(image_path)
            for crop_box in ((0, 0, 100, 100), (0, 0, 90, 80)):
                with self.subTest(crop_box=crop_box):
                    receipt_path = root / f"receipt-{crop_box[2]}.json"
                    with mock.patch.object(module, "run_smoke_worker") as worker:
                        with self.assertRaisesRegex(RuntimeError, "must remain local"):
                            module.smoke(
                                root / "model", module.TEACHER_REVISION, image_path,
                                crop_box, "all", receipt_path,
                            )
                    worker.assert_not_called()
                    self.assertFalse(receipt_path.exists())

    def test_smoke_timeout_terminates_worker_and_never_writes_success_receipt(self):
        context = module.multiprocessing.get_context("spawn")
        process = context.Process(target=time.sleep, args=(5.0,))
        process.start()
        with self.assertRaisesRegex(TimeoutError, "exceeded 0.05s"):
            module.enforce_process_timeout(process, 0.05)
        self.assertFalse(process.is_alive())

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            image_path = root / "readonly-source.png"
            receipt_path = root / "smoke.json"
            Image.new("RGB", (100, 100), (30, 40, 50)).save(image_path)
            with mock.patch.object(
                module, "run_smoke_worker", side_effect=TimeoutError("exceeded 30s"),
            ):
                with self.assertRaisesRegex(TimeoutError, "exceeded 30s"):
                    module.smoke(
                        root / "model", module.TEACHER_REVISION, image_path,
                        (0, 0, 50, 50), "all", receipt_path,
                    )
            self.assertFalse(receipt_path.exists())


if __name__ == "__main__":
    unittest.main()
