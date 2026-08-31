#!/usr/bin/env python3
"""Build one focused replacement tray review item per source-truncated task."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any, Sequence

from PIL import Image, ImageOps


VERSION = "liushanmen-task-sibling-tray-recovery-v1"
SOURCE_VERSION = "liushanmen-label-task-sibling-manifest-v1"
STATUS_PRIORITY = {"AUTO_PASSED": 3, "REVIEWED": 2, "ANALYZED": 1}


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise RuntimeError(f"expected JSON object: {path}")
    return value


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def valid_box(box: Any, edge_margin: float = 0.02) -> bool:
    if not isinstance(box, list) or len(box) != 4:
        return False
    x0, y0, x1, y1 = (float(value) for value in box)
    return (
        edge_margin <= x0 < x1 <= 1 - edge_margin
        and edge_margin <= y0 < y1 <= 1 - edge_margin
    )


def box_area(box: Sequence[float]) -> float:
    return (float(box[2]) - float(box[0])) * (float(box[3]) - float(box[1]))


def best_tray(row: dict[str, Any]) -> dict[str, Any] | None:
    candidates = []
    for tray in (row.get("screening_detail") or {}).get("trays") or []:
        box = tray.get("bbox")
        if not valid_box(box):
            continue
        confidence = float(tray.get("trayConfidence") or 0.0)
        candidates.append((box_area(box), confidence, -int(tray.get("index") or 0), tray))
    return max(candidates, default=(None, None, None, None))[-1]


def select_rows(manifest: dict[str, Any]) -> list[tuple[dict[str, Any], dict[str, Any]]]:
    if manifest.get("version") != SOURCE_VERSION:
        raise RuntimeError("unsupported sibling manifest")
    excluded_by_task = {
        str(row["task_id"]): int(row["order_index"])
        for row in (manifest.get("scope") or {}).get("excluded_source_rows") or []
    }
    rows_by_task: dict[str, list[dict[str, Any]]] = {}
    for row in manifest.get("rows") or []:
        rows_by_task.setdefault(str(row.get("task_id") or ""), []).append(row)
    if set(rows_by_task) != set(excluded_by_task):
        raise RuntimeError("sibling/excluded task coverage drift")

    selected = []
    for task_id in sorted(rows_by_task):
        source_order = excluded_by_task[task_id]
        ranked = []
        for row in rows_by_task[task_id]:
            tray = best_tray(row)
            if tray is None:
                continue
            ranked.append((
                -abs(int(row["order_index"]) - source_order),
                STATUS_PRIORITY.get(str(row.get("photo_status") or ""), 0),
                box_area(tray["bbox"]), float(tray.get("trayConfidence") or 0.0),
                str(row["photo_id"]), row, tray,
            ))
        if not ranked:
            raise RuntimeError(f"task has no edge-safe sibling tray: {task_id}")
        winner = max(ranked)
        selected.append((winner[-2], winner[-1]))
    return selected


def wide_crop(box: Sequence[float], width: int, height: int) -> list[int]:
    x0, y0, x1, y1 = (
        float(box[0]) * width, float(box[1]) * height,
        float(box[2]) * width, float(box[3]) * height,
    )
    scale = max(x1 - x0, y1 - y0)
    pad = scale * 1.25
    crop = [
        max(0, round(x0 - pad)), max(0, round(y0 - pad)),
        min(width, round(x1 + pad)), min(height, round(y1 + pad)),
    ]
    if crop[2] <= crop[0] or crop[3] <= crop[1]:
        raise RuntimeError("invalid sibling review crop")
    return crop


def box_in_crop(box: Sequence[float], crop: Sequence[int], width: int, height: int) -> list[float]:
    source = [
        float(box[0]) * width, float(box[1]) * height,
        float(box[2]) * width, float(box[3]) * height,
    ]
    crop_width, crop_height = crop[2] - crop[0], crop[3] - crop[1]
    return [
        round((source[0] - crop[0]) / crop_width, 6),
        round((source[1] - crop[1]) / crop_height, 6),
        round((source[2] - crop[0]) / crop_width, 6),
        round((source[3] - crop[1]) / crop_height, 6),
    ]


def build(source_manifest: Path, output: Path, annotator_url: str) -> dict[str, Any]:
    if output.exists():
        raise FileExistsError(f"refusing to overwrite sibling recovery queue: {output}")
    manifest = load_json(source_manifest)
    selected = select_rows(manifest)
    for name in ("images", "prelabels", "annotations-human"):
        (output / name).mkdir(parents=True, exist_ok=False)

    output_rows = []
    for index, (row, tray) in enumerate(selected, start=1):
        source = Path(str(row["image"])).resolve()
        if not source.is_file() or sha256_file(source) != str(row["image_sha256"]):
            raise RuntimeError(f"sibling source image drift: {row['photo_id']}")
        with Image.open(source) as opened:
            image = ImageOps.exif_transpose(opened).convert("RGB")
        crop = wide_crop(tray["bbox"], image.width, image.height)
        crop_id = f"sibling_{str(row['photo_id'])[:8]}_{index:03d}"
        derivative = output / "images" / f"{crop_id}.jpg"
        image.crop(tuple(crop)).save(derivative, "JPEG", quality=95, subsampling=0)
        guide = box_in_crop(tray["bbox"], crop, image.width, image.height)
        (output / "prelabels" / f"{crop_id}.json").write_text(json.dumps({
            "crop_id": crop_id, "source": "sibling_detector_guide_only",
            "is_ground_truth": False, "boxes": [],
        }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        output_rows.append({
            "crop_id": crop_id,
            "image": str(derivative.resolve()),
            "image_sha256": sha256_file(derivative),
            "source_photo_id": str(row["photo_id"]),
            "source_task_id": str(row["task_id"]),
            "source_image_sha256": str(row["image_sha256"]),
            "sku_code": str(row.get("sku_code") or ""),
            "angle": "同任务替代照片：只确认或重框中央完整物理托盘",
            "model_previous_verdict": str(tray.get("screenVerdict") or ""),
            "target_tray_index": int(tray.get("index") or 0),
            "target_tray_guide_normalized": guide,
            "target_tray_guide_source": "sibling_detector_read_only_not_ground_truth",
            "source_crop_rect_px": crop,
            "review_scope": "single_complete_target_tray_only",
            "prelabel_boxes": 0,
            "training_eligible": False,
            "training_exclusion_reason": "awaiting_sibling_human_review",
        })
    payload = {
        "version": VERSION,
        "queue_id": output.name,
        "queue_count": len(output_rows),
        "annotator_url": annotator_url,
        "source_sibling_manifest": str(source_manifest.resolve()),
        "source_sibling_manifest_sha256": sha256_file(source_manifest),
        "one_replacement_photo_per_source_truncated_task": True,
        "preannotations_are_not_ground_truth": True,
        "every_image_requires_full_human_review": True,
        "train_only_after_human_review": True,
        "target_tray_guides_are_read_only": True,
        "evaluation_or_production_use_allowed": False,
        "production_writes": 0,
        "originals_modified": 0,
        "rows": output_rows,
    }
    (output / "manifest.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    return payload


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--annotator-url", default="http://127.0.0.1:8800/")
    args = parser.parse_args()
    payload = build(args.source_manifest.resolve(), args.output.resolve(), args.annotator_url)
    print(json.dumps({
        "queue": str(args.output.resolve()), "queue_count": payload["queue_count"],
        "production_writes": 0, "training_eligible": False,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
