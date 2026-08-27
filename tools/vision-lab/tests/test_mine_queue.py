from __future__ import annotations

import importlib.util
import json
import sqlite3
import tempfile
import unittest
from dataclasses import dataclass
from pathlib import Path
from types import SimpleNamespace

import numpy as np


MODULE_PATH = Path(__file__).resolve().parents[1] / "mine_queue.py"
SPEC = importlib.util.spec_from_file_location("mine_queue", MODULE_PATH)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


@dataclass(frozen=True)
class Params:
    tray_conf: float = 0.60
    label_conf: float = 0.20


class MineQueueTests(unittest.TestCase):
    def test_scan_cache_upgrades_pre_sha_schema_without_dropping_rows(self):
        connection = sqlite3.connect(":memory:")
        connection.execute(
            """
            CREATE TABLE label_mining_clean_scans (
              photo_id TEXT NOT NULL,
              screening_fingerprint TEXT NOT NULL,
              screened_at TEXT NOT NULL,
              PRIMARY KEY(photo_id, screening_fingerprint)
            )
            """
        )
        connection.execute(
            "INSERT INTO label_mining_clean_scans VALUES('photo','old','time')"
        )
        connection.commit()
        module.ensure_scan_cache(connection)
        columns = {
            row[1] for row in connection.execute(
                "PRAGMA table_info(label_mining_clean_scans)"
            )
        }
        self.assertIn("photo_sha256", columns)
        self.assertEqual(
            connection.execute(
                "SELECT COUNT(*) FROM label_mining_clean_scans"
            ).fetchone()[0],
            1,
        )
        connection.close()

    def test_screening_fingerprint_pins_models_pipeline_policy_and_params(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            tray = root / "tray.onnx"
            label = root / "label.onnx"
            screening = root / "screening.py"
            detector = root / "yolo_detector.py"
            tray.write_bytes(b"tray-v1")
            label.write_bytes(b"label-v1")
            screening.write_text("screening-v1", encoding="utf-8")
            detector.write_text("detector-v1", encoding="utf-8")

            first = module.screening_fingerprint(
                tray, label, [screening, detector], Params()
            )
            self.assertEqual(
                first,
                module.screening_fingerprint(
                    tray, label, [screening, detector], Params()
                ),
            )
            self.assertNotEqual(
                first,
                module.screening_fingerprint(
                    tray, label, [screening, detector], Params(label_conf=0.25)
                ),
            )
            label.write_bytes(b"label-v2")
            self.assertNotEqual(
                first,
                module.screening_fingerprint(
                    tray, label, [screening, detector], Params()
                ),
            )

    def test_only_clean_scans_for_exact_fingerprint_are_skipped(self):
        connection = sqlite3.connect(":memory:")
        connection.row_factory = sqlite3.Row
        connection.execute(
            """
            CREATE TABLE photos (
              photo_id TEXT PRIMARY KEY,
              reviewed_at TEXT NOT NULL,
              annotations_json TEXT NOT NULL,
              sha256 TEXT NOT NULL,
              local_path TEXT NOT NULL,
              task_id TEXT NOT NULL,
              sku_code TEXT
            )
            """
        )
        normal = json.dumps([{"human_label": "NO_DEFECT"}])
        defect = json.dumps([{"human_label": "MISSING_WHITE_LABEL"}])
        for index, (photo_id, truth) in enumerate((
            ("p-new", normal),
            ("p-cached", normal),
            ("p-other-fingerprint", normal),
            ("p-defect", defect),
            ("p-excluded", normal),
        )):
            connection.execute(
                "INSERT INTO photos VALUES(?,?,?,?,?,?,?)",
                (
                    photo_id, f"2026-08-24T00:00:0{9 - index}", truth,
                    f"sha-{photo_id}", "x.jpg", "task", "sku",
                ),
            )
        connection.commit()
        module.ensure_scan_cache(connection)
        module.record_clean_scan(
            connection, "p-cached", "sha-p-cached", "fingerprint-current"
        )
        module.record_clean_scan(
            connection,
            "p-other-fingerprint",
            "sha-p-other-fingerprint",
            "fingerprint-old",
        )

        candidates, cache_hits = module.load_candidates(
            connection,
            {"p-excluded"},
            "fingerprint-current",
            10,
        )
        self.assertEqual(
            [row["photo_id"] for row in candidates],
            ["p-new", "p-other-fingerprint"],
        )
        self.assertEqual(cache_hits, 1)

        module.record_clean_scan(connection, "p-new", "sha-p-new", "fingerprint-current")
        module.record_clean_scan(connection, "p-new", "sha-p-new", "fingerprint-current")
        candidates, cache_hits = module.load_candidates(
            connection,
            {"p-excluded"},
            "fingerprint-current",
            10,
        )
        self.assertEqual(
            [row["photo_id"] for row in candidates],
            ["p-other-fingerprint"],
        )
        self.assertEqual(cache_hits, 2)
        self.assertEqual(
            connection.execute(
                "SELECT COUNT(*) FROM label_mining_clean_scans WHERE photo_id='p-new'"
            ).fetchone()[0],
            1,
        )

        candidates, cache_hits = module.load_candidates(
            connection,
            {"p-excluded"},
            "fingerprint-new-model",
            10,
        )
        self.assertEqual(
            [row["photo_id"] for row in candidates],
            ["p-new", "p-cached", "p-other-fingerprint"],
        )
        self.assertEqual(cache_hits, 0)

        connection.execute(
            "UPDATE photos SET sha256='sha-p-new-replaced' WHERE photo_id='p-new'"
        )
        connection.commit()
        candidates, cache_hits = module.load_candidates(
            connection,
            {"p-excluded"},
            "fingerprint-current",
            10,
        )
        self.assertIn("p-new", [row["photo_id"] for row in candidates])
        self.assertEqual(cache_hits, 1)
        connection.close()

    def test_uncertain_review_candidates_are_not_treated_as_clean(self):
        screening = SimpleNamespace(
            suspects=[],
            review_candidates=[SimpleNamespace(verdict="UNJUDGEABLE")],
        )
        self.assertEqual(
            [tray.verdict for tray in module.trays_requiring_review(screening)],
            ["UNJUDGEABLE"],
        )
        self.assertTrue(module.crop_can_be_reviewed(np.zeros((12, 9, 3), dtype=np.uint8)))
        self.assertFalse(module.crop_can_be_reviewed(np.zeros((0, 9, 3), dtype=np.uint8)))

    def test_source_edge_trays_are_rejected_but_clipped_padding_is_allowed(self):
        self.assertTrue(module.tray_touches_source_edge([0, 20, 80, 90], 200, 100))
        self.assertTrue(module.tray_touches_source_edge([20, 20, 199.5, 90], 200, 100))
        self.assertFalse(module.tray_touches_source_edge([20, 10, 180, 90], 200, 100))
        with self.assertRaisesRegex(ValueError, "dimensions"):
            module.tray_touches_source_edge([1, 1, 2, 2], 0, 100)


if __name__ == "__main__":
    unittest.main()
