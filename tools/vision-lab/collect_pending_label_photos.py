#!/usr/bin/env python3
"""Collect pending Label QC photos into an isolated, non-training manifest."""
from __future__ import annotations

import argparse
import collections
import datetime as dt
import importlib.util
import json
import re
import shlex
import subprocess
from pathlib import Path
from typing import Any, Callable


HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("vision_lab", HERE / "vision_lab.py")
VISION_LAB = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(VISION_LAB)

FACTORY_PATTERN = re.compile(r"[A-Z0-9_-]{1,50}")
PHOTO_STATUS = "ANALYZED"
TASK_STATUS = "NEEDS_REVIEW"


def parse_timestamp(value: str) -> str:
    parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is not None:
        parsed = parsed.astimezone(dt.timezone.utc).replace(tzinfo=None)
    return parsed.strftime("%Y-%m-%d %H:%M:%S.%f")


def query_sql(factory_id: str, after_task_created_at: str) -> str:
    if not FACTORY_PATTERN.fullmatch(factory_id):
        raise ValueError("invalid factory id")
    stamp = parse_timestamp(after_task_created_at)
    return f"""
BEGIN TRANSACTION READ ONLY;
SELECT COALESCE(jsonb_agg(row_data ORDER BY row_data->>'task_created_at',
                          (row_data->>'order_index')::int), '[]'::jsonb)
FROM (
  SELECT jsonb_build_object(
    'photo_id', p.id, 'task_id', t.id, 'sku_code', t.sku_code,
    'task_status', t.status, 'photo_status', p.status,
    'task_created_at', t.created_at, 'photo_created_at', p.created_at,
    'order_index', p.order_index, 'image_width', p.image_width,
    'image_height', p.image_height, 'file_url', a.file_url,
    'ai_model', p.ai_model, 'prompt_version', p.prompt_version,
    'screening_detail', p.screening_detail,
    'annotations', COALESCE((
      SELECT jsonb_agg(jsonb_build_object(
        'source', q.source, 'audience', q.audience, 'ai_label', q.ai_label,
        'human_label', q.human_label, 'ai_confidence', q.ai_confidence,
        'bbox', CASE WHEN q.x_min IS NULL THEN NULL
          ELSE jsonb_build_array(q.x_min, q.y_min, q.x_max, q.y_max) END
      ) ORDER BY q.created_at, q.id)
      FROM label_qc_annotations q
      WHERE q.factory_id = '{factory_id}' AND q.photo_id = p.id
        AND q.deleted_at IS NULL
    ), '[]'::jsonb)
  ) AS row_data
  FROM label_qc_photos p
  JOIN label_qc_tasks t ON t.id = p.task_id
    AND t.factory_id = p.factory_id AND t.deleted_at IS NULL
  JOIN attachments a ON a.id = p.attachment_id
    AND a.factory_id = p.factory_id AND a.deleted_at IS NULL
  WHERE p.factory_id = '{factory_id}' AND p.deleted_at IS NULL
    AND t.status = '{TASK_STATUS}' AND p.status = '{PHOTO_STATUS}'
    AND t.created_at > TIMESTAMP '{stamp}'
  ORDER BY t.created_at, p.order_index, p.id
) rows;
COMMIT;
"""


def query_production(config: dict[str, Any], after_task_created_at: str) -> list[dict[str, Any]]:
    source = config["source"]
    sql = query_sql(str(source["factory_id"]), after_task_created_at)
    command = [
        "ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15",
        str(source["ssh_host"]),
        f"sudo -u postgres psql -X -qAt {shlex.quote(str(source.get('database', 'cretas_prod_db')))}",
    ]
    result = subprocess.run(
        command, input=sql, text=True, encoding="utf-8", errors="strict",
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=90,
    )
    if result.returncode != 0:
        raise RuntimeError(f"production read failed: {result.stderr.strip()}")
    rows = json.loads(result.stdout or "[]")
    if not isinstance(rows, list):
        raise RuntimeError("production query did not return a JSON list")
    return rows


def parse_screening(value: Any, photo_id: str) -> dict[str, Any]:
    if isinstance(value, str):
        value = json.loads(value)
    if not isinstance(value, dict) or not isinstance(value.get("trays"), list):
        raise RuntimeError(f"pending photo lacks structured screening detail: {photo_id}")
    return value


def materialize(
    config: dict[str, Any], rows: list[dict[str, Any]], output: Path,
    after_task_created_at: str, expected_count: int | None,
    prepare_object: Callable[..., tuple[str, Path, bool]] = VISION_LAB.prepare_object,
) -> dict[str, Any]:
    if output.exists():
        raise FileExistsError(f"refusing to overwrite pending manifest: {output}")
    if expected_count is not None and len(rows) != expected_count:
        raise RuntimeError(f"pending photo count drift: {len(rows)} != {expected_count}")
    photo_ids = [str(row.get("photo_id") or "") for row in rows]
    if not all(photo_ids) or len(photo_ids) != len(set(photo_ids)):
        raise RuntimeError("pending query returned blank or duplicate photo ids")

    root = Path(config["runtime_root"]).resolve()
    prepared = []
    cache_hits = 0
    for row in rows:
        photo_id = str(row["photo_id"])
        if row.get("task_status") != TASK_STATUS or row.get("photo_status") != PHOTO_STATUS:
            raise RuntimeError(f"pending status drift: {photo_id}")
        if any(annotation.get("human_label") is not None for annotation in row.get("annotations") or []):
            raise RuntimeError(f"pending photo unexpectedly contains human truth: {photo_id}")
        screening = parse_screening(row.get("screening_detail"), photo_id)
        url = str(row.get("file_url") or "")
        ref = VISION_LAB.object_ref(url)
        digest, image, cache_hit = prepare_object(
            root, url, ref, "Cretas-VisionLab-pending-readonly/1.0",
        )
        cache_hits += int(cache_hit)
        prepared.append({
            "photo_id": photo_id,
            "task_id": str(row["task_id"]),
            "sku_code": str(row.get("sku_code") or ""),
            "task_created_at": str(row["task_created_at"]),
            "photo_created_at": str(row["photo_created_at"]),
            "order_index": int(row["order_index"]),
            "image_width": int(row["image_width"]),
            "image_height": int(row["image_height"]),
            "object_ref": ref,
            "image": str(image.resolve()),
            "image_sha256": digest,
            "ai_model": str(row.get("ai_model") or ""),
            "prompt_version": str(row.get("prompt_version") or ""),
            "screening_detail": screening,
            "annotations": row.get("annotations") or [],
            "task_status_at_collection": TASK_STATUS,
            "photo_status_at_collection": PHOTO_STATUS,
            "human_truth_available": False,
            "training_eligible": False,
        })

    payload = {
        "version": "liushanmen-pending-label-photo-manifest-v1",
        "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "scope": {
            "factory_id": str(config["source"]["factory_id"]),
            "after_task_created_at_exclusive": after_task_created_at,
            "task_status": TASK_STATUS,
            "photo_status": PHOTO_STATUS,
        },
        "inventory": {
            "photos": len(prepared),
            "tasks": len({row["task_id"] for row in prepared}),
            "sku_counts": dict(sorted(collections.Counter(row["sku_code"] for row in prepared).items())),
            "annotation_counts": dict(sorted(collections.Counter(
                str(annotation.get("ai_label") or "")
                for row in prepared for annotation in row["annotations"]
            ).items())),
            "object_cache_hits": cache_hits,
            "object_downloads": len(prepared) - cache_hits,
        },
        "human_truth_available": False,
        "training_eligible": False,
        "evaluation_truth_eligible": False,
        "production_reads": 1,
        "production_writes": 0,
        "originals_modified": 0,
        "rows": prepared,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    VISION_LAB.write_json(output, payload)
    return payload


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--after-task-created-at", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--expected-count", type=int)
    args = parser.parse_args()
    config = VISION_LAB.load_json(args.config.resolve())
    rows = query_production(config, args.after_task_created_at)
    payload = materialize(
        config, rows, args.output.resolve(), args.after_task_created_at, args.expected_count,
    )
    print(json.dumps({
        "output": str(args.output.resolve()), "inventory": payload["inventory"],
        "training_eligible": False, "production_reads": 1, "production_writes": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
