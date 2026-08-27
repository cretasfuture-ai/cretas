from __future__ import annotations

import importlib.util
import json
import tempfile
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "build_minimal_active_label_review_queue.py"
SPEC = importlib.util.spec_from_file_location("build_minimal_active_label_review_queue", MODULE_PATH)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def row(root: Path, index: int, sku: str, verdict: str, box=None) -> dict:
    crop_id = f"crop_{index}"
    image = root / "images" / f"{crop_id}.jpg"
    image.write_bytes(f"image-{index}".encode())
    (root / "prelabels" / f"{crop_id}.json").write_text(
        json.dumps({"crop_id": crop_id, "is_ground_truth": False, "boxes": []}),
        encoding="utf-8",
    )
    return {
        "crop_id": crop_id, "image": str(image),
        "image_sha256": module.sha256_file(image),
        "source_photo_id": f"photo-{index}", "source_task_id": f"task-{index // 2}",
        "sku_code": sku, "label_v1_verdict": verdict,
        "source_image_size": [1000, 1000],
        "target_tray_box_source_xyxy": box or [10, 10, 990, 990],
    }


def test_build_balances_explicit_disagreements_and_rejects_edges():
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        source = root / "source"
        (source / "images").mkdir(parents=True)
        (source / "prelabels").mkdir()
        rows = [
            row(source, 1, "SKU-A", "BOTH_MISSING"),
            row(source, 2, "SKU-A", "MISSING_WHITE_LABEL"),
            row(source, 3, "SKU-B", "MISSING_COLOR_LABEL"),
            row(source, 4, "SKU-B", "UNJUDGEABLE"),
            row(source, 5, "SKU-C", "MISSING_WHITE_LABEL", [0, 10, 500, 500]),
        ]
        (source / "manifest.json").write_text(json.dumps({
            "preannotations_are_not_ground_truth": True,
            "evaluation_or_production_use_allowed": False,
            "rows": rows,
        }), encoding="utf-8")
        output = root / "output"
        receipt = module.build(source, output, 3, 2, 2)
        assert receipt["queue_count"] == 3
        assert {item["crop_id"] for item in receipt["rows"]} == {"crop_1", "crop_2", "crop_3"}
        assert receipt["exclusions"]["not_explicit_model_disagreement"] == 1
        assert receipt["exclusions"]["target_tray_touches_source_edge"] == 1
        assert all(item["review_scope"] == "single_complete_target_tray_labels_only" for item in receipt["rows"])
        assert set(receipt["verdict_counts"]) == {
            "BOTH_MISSING", "MISSING_WHITE_LABEL", "MISSING_COLOR_LABEL",
        }
        assert receipt["production_writes"] == 0


def test_complete_source_tray_requires_geometry_and_strict_margin():
    assert module.complete_source_tray({
        "source_image_size": [1000, 1000],
        "target_tray_box_source_xyxy": [6, 6, 994, 994],
    })
    assert not module.complete_source_tray({
        "source_image_size": [1000, 1000],
        "target_tray_box_source_xyxy": [5, 6, 994, 994],
    })
    assert not module.complete_source_tray({})
