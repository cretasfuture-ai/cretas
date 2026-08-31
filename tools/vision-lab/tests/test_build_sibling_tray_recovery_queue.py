from __future__ import annotations

import importlib.util
from pathlib import Path


PATH = Path(__file__).resolve().parents[1] / "build_sibling_tray_recovery_queue.py"
SPEC = importlib.util.spec_from_file_location("build_sibling_tray_recovery_queue", PATH)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def row(photo, task, order, status, box, confidence=0.9):
    return {
        "photo_id": photo, "task_id": task, "order_index": order, "photo_status": status,
        "screening_detail": {"trays": [{
            "index": 0, "bbox": box, "trayConfidence": confidence,
        }]},
    }


def test_selects_one_edge_safe_nearest_sibling_per_task():
    manifest = {
        "version": module.SOURCE_VERSION,
        "scope": {"excluded_source_rows": [
            {"task_id": "task-a", "order_index": 2},
            {"task_id": "task-b", "order_index": 1},
        ]},
        "rows": [
            row("far", "task-a", 0, "AUTO_PASSED", [0.1, 0.1, 0.4, 0.4]),
            row("near", "task-a", 1, "AUTO_PASSED", [0.2, 0.2, 0.5, 0.5]),
            row("edge", "task-b", 0, "AUTO_PASSED", [0.0, 0.1, 0.5, 0.5]),
            row("safe", "task-b", 2, "REVIEWED", [0.2, 0.2, 0.6, 0.6]),
        ],
    }

    selected = module.select_rows(manifest)

    assert [item[0]["photo_id"] for item in selected] == ["near", "safe"]


def test_wide_crop_keeps_target_inside_derivative():
    crop = module.wide_crop([0.2, 0.2, 0.5, 0.5], 1000, 800)
    guide = module.box_in_crop([0.2, 0.2, 0.5, 0.5], crop, 1000, 800)

    assert all(0 <= value <= 1 for value in guide)
    assert guide[0] < guide[2]
    assert guide[1] < guide[3]
