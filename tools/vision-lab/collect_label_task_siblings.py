#!/usr/bin/env python3
"""Collect read-only sibling photos for tasks whose target tray was source-truncated."""
from __future__ import annotations

import argparse
import datetime as dt
import importlib.util
import json
import re
import shlex
import subprocess
from pathlib import Path
from typing import Any, Callable, Sequence


HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("vision_lab", HERE / "vision_lab.py")
VISION_LAB = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(VISION_LAB)

FACTORY_PATTERN = re.compile(r"[A-Z0-9_-]{1,50}")
UUID_PATTERN = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")


def validated_ids(values: Sequence[str], pattern: re.Pattern[str], name: str) -> list[str]:
    result = sorted(set(values))
    if not result or any(not pattern.fullmatch(value) for value in result):
        raise ValueError(f"invalid {name}")
    return result


def query_sql(factory_id: str, task_ids: Sequence[str]) -> str:
    if not FACTORY_PATTERN.fullmatch(factory_id):
        raise ValueError("invalid factory id")
    tasks = validated_ids(task_ids, UUID_PATTERN, "task ids")
    literals = ",".join(f"'{task_id}'" for task_id in tasks)
    return f"""
BEGIN TRANSACTION READ ONLY;
SELECT COALESCE(jsonb_agg(row_data ORDER BY row_data->>'task_id',
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
    AND t.id = ANY (ARRAY[{literals}]::text[])
  ORDER BY t.id, p.order_index, p.id
) rows;
COMMIT;
"""


def query_production(config: dict[str, Any], task_ids: Sequence[str]) -> list[dict[str, Any]]:
    source = config["source"]
    sql = query_sql(str(source["factory_id"]), task_ids)
    command = [
        "ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15",
        str(source["ssh_host"]),
        f"sudo -u postgres psql -X -v ON_ERROR_STOP=1 -qAt "
        f"{shlex.quote(str(source.get('database', 'cretas_prod_db')))}",
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


def materialize(
    config: dict[str, Any], rows: Sequence[dict[str, Any]], output: Path,
    task_ids: Sequence[str], excluded_photo_ids: Sequence[str],
    prepare_object: Callable[..., tuple[str, Path, bool]] = VISION_LAB.prepare_object,
) -> dict[str, Any]:
    if output.exists():
        raise FileExistsError(f"refusing to overwrite sibling manifest: {output}")
    tasks = set(validated_ids(task_ids, UUID_PATTERN, "task ids"))
    excluded = set(validated_ids(excluded_photo_ids, UUID_PATTERN, "excluded photo ids"))
    returned_tasks = {str(row.get("task_id") or "") for row in rows}
    if returned_tasks != tasks:
        raise RuntimeError("sibling query task coverage drift")
    selected = [row for row in rows if str(row.get("photo_id") or "") not in excluded]
    excluded_rows = [row for row in rows if str(row.get("photo_id") or "") in excluded]
    photo_ids = [str(row.get("photo_id") or "") for row in selected]
    if not all(photo_ids) or len(photo_ids) != len(set(photo_ids)):
        raise RuntimeError("sibling query returned blank or duplicate photo ids")

    root = Path(config["runtime_root"]).resolve()
    prepared: list[dict[str, Any]] = []
    cache_hits = 0
    for row in selected:
        photo_id = str(row["photo_id"])
        url = str(row.get("file_url") or "")
        ref = VISION_LAB.object_ref(url)
        digest, image, cache_hit = prepare_object(
            root, url, ref, "Cretas-VisionLab-task-siblings-readonly/1.0",
        )
        cache_hits += int(cache_hit)
        screening = row.get("screening_detail")
        if isinstance(screening, str):
            screening = json.loads(screening)
        prepared.append({
            "photo_id": photo_id,
            "task_id": str(row["task_id"]),
            "sku_code": str(row.get("sku_code") or ""),
            "task_status": str(row.get("task_status") or ""),
            "photo_status": str(row.get("photo_status") or ""),
            "task_created_at": str(row.get("task_created_at") or ""),
            "photo_created_at": str(row.get("photo_created_at") or ""),
            "order_index": int(row["order_index"]),
            "image_width": int(row["image_width"]),
            "image_height": int(row["image_height"]),
            "object_ref": ref,
            "image": str(image.resolve()),
            "image_sha256": digest,
            "ai_model": str(row.get("ai_model") or ""),
            "prompt_version": str(row.get("prompt_version") or ""),
            "screening_detail": screening if isinstance(screening, dict) else {},
            "annotations": row.get("annotations") or [],
            "training_eligible": False,
        })
    payload = {
        "version": "liushanmen-label-task-sibling-manifest-v1",
        "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "scope": {
            "factory_id": str(config["source"]["factory_id"]),
            "task_ids": sorted(tasks),
            "excluded_source_photo_ids": sorted(excluded),
            "excluded_source_rows": [
                {
                    "photo_id": str(row["photo_id"]), "task_id": str(row["task_id"]),
                    "order_index": int(row["order_index"]),
                    "photo_status": str(row.get("photo_status") or ""),
                }
                for row in excluded_rows
            ],
        },
        "inventory": {
            "queried_photos": len(rows),
            "excluded_source_photos": len(rows) - len(selected),
            "sibling_photos": len(prepared),
            "tasks": len(tasks),
            "object_cache_hits": cache_hits,
            "object_downloads": len(prepared) - cache_hits,
        },
        "rows": prepared,
        "human_review_required": True,
        "training_eligible": False,
        "production_reads": 1,
        "production_writes": 0,
        "originals_modified": 0,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    VISION_LAB.write_json(output, payload)
    return payload


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--task-id", action="append", required=True)
    parser.add_argument("--exclude-photo", action="append", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    config = VISION_LAB.load_json(args.config.resolve())
    rows = query_production(config, args.task_id)
    payload = materialize(
        config, rows, args.output.resolve(), args.task_id, args.exclude_photo,
    )
    print(json.dumps({
        "output": str(args.output.resolve()), "inventory": payload["inventory"],
        "training_eligible": False, "production_reads": 1, "production_writes": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
