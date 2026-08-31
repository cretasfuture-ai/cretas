from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import pytest


PATH = Path(__file__).resolve().parents[1] / "collect_label_task_siblings.py"
SPEC = importlib.util.spec_from_file_location("collect_label_task_siblings", PATH)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)

TASK = "11111111-1111-4111-8111-111111111111"
SOURCE = "22222222-2222-4222-8222-222222222222"
SIBLING = "33333333-3333-4333-8333-333333333333"


def test_query_is_read_only_and_task_scoped():
    sql = module.query_sql("LIUSHANMEN", [TASK])

    assert "BEGIN TRANSACTION READ ONLY" in sql
    assert "t.id = ANY" in sql
    assert "::text[]" in sql
    assert TASK in sql


def test_materialize_excludes_source_photo_and_stays_non_training(tmp_path):
    image = tmp_path / "cached.jpg"
    image.write_bytes(b"image")
    rows = [
        {"photo_id": SOURCE, "task_id": TASK, "order_index": 2, "photo_status": "ANALYZED"},
        {
            "photo_id": SIBLING, "task_id": TASK, "sku_code": "SKU", "order_index": 1,
            "image_width": 100, "image_height": 80, "file_url": "https://example.test/a.jpg",
            "screening_detail": json.dumps({"trays": []}), "annotations": [],
        },
    ]

    payload = module.materialize(
        {"runtime_root": str(tmp_path), "source": {"factory_id": "LIUSHANMEN"}},
        rows, tmp_path / "siblings.json", [TASK], [SOURCE],
        prepare_object=lambda *_: ("a" * 64, image, True),
    )

    assert payload["inventory"]["queried_photos"] == 2
    assert payload["inventory"]["sibling_photos"] == 1
    assert payload["rows"][0]["photo_id"] == SIBLING
    assert payload["scope"]["excluded_source_rows"][0]["order_index"] == 2
    assert payload["training_eligible"] is False
    assert payload["production_writes"] == 0


@pytest.mark.parametrize("value", ["", "not-a-uuid"])
def test_rejects_invalid_task_id(value):
    with pytest.raises(ValueError):
        module.query_sql("LIUSHANMEN", [value])
