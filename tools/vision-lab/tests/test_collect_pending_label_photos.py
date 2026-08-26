import importlib.util
from pathlib import Path

import pytest


MODULE_PATH = Path(__file__).parents[1] / "collect_pending_label_photos.py"
SPEC = importlib.util.spec_from_file_location("collect_pending", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def row():
    return {
        "photo_id": "photo-1", "task_id": "task-1", "sku_code": "SKU-1",
        "task_status": "NEEDS_REVIEW", "photo_status": "ANALYZED",
        "task_created_at": "2026-08-26T01:00:00", "photo_created_at": "2026-08-26T01:00:01",
        "order_index": 0, "image_width": 3072, "image_height": 4096,
        "file_url": "https://example.invalid/image.jpg", "ai_model": "model-1",
        "prompt_version": "prompt-1", "screening_detail": {"trays": []},
        "annotations": [{"ai_label": "UNJUDGEABLE", "human_label": None}],
    }


def test_query_is_read_only_and_status_scoped():
    sql = MODULE.query_sql("LIUSHANMEN", "2026-08-25T18:05:02.892937")
    assert "BEGIN TRANSACTION READ ONLY" in sql
    assert "t.status = 'NEEDS_REVIEW'" in sql
    assert "p.status = 'ANALYZED'" in sql
    assert "UPDATE " not in sql and "DELETE " not in sql and "INSERT " not in sql


def test_materialize_keeps_pending_rows_out_of_training(tmp_path):
    image = tmp_path / "cached.jpg"
    image.write_bytes(b"image")

    payload = MODULE.materialize(
        {"runtime_root": str(tmp_path), "source": {"factory_id": "LIUSHANMEN"}},
        [row()], tmp_path / "pending.json", "2026-08-25T18:05:02.892937", 1,
        prepare_object=lambda *_args: ("a" * 64, image, False),
    )

    assert payload["training_eligible"] is False
    assert payload["evaluation_truth_eligible"] is False
    assert payload["production_writes"] == 0
    assert payload["rows"][0]["human_truth_available"] is False
    assert payload["rows"][0]["training_eligible"] is False


def test_materialize_rejects_human_truth_or_status_drift(tmp_path):
    image = tmp_path / "cached.jpg"
    image.write_bytes(b"image")
    changed = row()
    changed["annotations"][0]["human_label"] = "NO_DEFECT"
    with pytest.raises(RuntimeError, match="human truth"):
        MODULE.materialize(
            {"runtime_root": str(tmp_path), "source": {"factory_id": "LIUSHANMEN"}},
            [changed], tmp_path / "pending.json", "2026-08-25T18:05:02.892937", 1,
            prepare_object=lambda *_args: ("a" * 64, image, False),
        )
