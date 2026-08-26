#!/usr/bin/env python3
"""Build a minimal single-target review queue from a pending replay receipt."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any, Sequence

from PIL import Image, ImageOps


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise RuntimeError(f"expected JSON object: {path}")
    return value


def crop_rect(box: Sequence[float], width: int, height: int) -> list[int]:
    x0, y0, x1, y1 = (
        float(box[0]) * width, float(box[1]) * height,
        float(box[2]) * width, float(box[3]) * height,
    )
    box_width, box_height = x1 - x0, y1 - y0
    if box_width <= 0 or box_height <= 0:
        raise RuntimeError(f"invalid normalized tray box: {box}")
    shallow = box_height < 120.0 and box_width / box_height > 2.0
    pad_x = box_width * 0.15
    pad_top = box_height * 0.15
    pad_bottom = box_height * (1.35 if shallow else 0.15)
    crop = [
        max(0, round(x0 - pad_x)), max(0, round(y0 - pad_top)),
        min(width, round(x1 + pad_x)), min(height, round(y1 + pad_bottom)),
    ]
    if crop[2] <= crop[0] or crop[3] <= crop[1]:
        raise RuntimeError(f"invalid target crop: {box}")
    return crop


def build(
    pending_path: Path, replay_path: Path, output: Path, annotator_url: str,
) -> dict[str, Any]:
    pending, replay = load_json(pending_path), load_json(replay_path)
    if pending.get("version") != "liushanmen-pending-label-photo-manifest-v1":
        raise RuntimeError("unsupported pending manifest")
    if replay.get("version") != "liushanmen-pending-production-equivalent-replay-v1":
        raise RuntimeError("unsupported pending replay")
    if replay.get("inputs", {}).get("pending_manifest_sha256") != sha256_file(pending_path):
        raise RuntimeError("pending replay manifest binding drift")
    if replay.get("training_allowed") is not False or replay.get("production_writes") != 0:
        raise RuntimeError("pending replay violated quarantine contract")
    if output.exists():
        raise FileExistsError(f"refusing to overwrite review queue: {output}")

    pending_rows = {str(row["photo_id"]): row for row in pending.get("rows") or []}
    targets = [
        (detail, target)
        for detail in replay.get("details") or []
        for target in detail.get("residual_targets") or []
    ]
    expected = int(replay.get("review", {}).get("target_count", -1))
    if not targets or len(targets) != expected:
        raise RuntimeError(f"review target count drift: {len(targets)} != {expected}")
    for name in ("images", "prelabels", "annotations-human"):
        (output / name).mkdir(parents=True, exist_ok=False)

    rows = []
    for position, (detail, target) in enumerate(targets, start=1):
        photo_id = str(detail["photo_id"])
        source_row = pending_rows[photo_id]
        source = Path(str(source_row["image"])).resolve()
        expected_hash = str(source_row["image_sha256"])
        if not source.is_file() or sha256_file(source) != expected_hash:
            raise RuntimeError(f"pending source image drift: {photo_id}")
        with Image.open(source) as opened:
            image = ImageOps.exif_transpose(opened).convert("RGB")
            crop = crop_rect(target["bbox"], image.width, image.height)
            crop_id = f"pending_{photo_id[:8]}_t{int(target['tray_index']):02d}_{position:03d}"
            derivative = output / "images" / f"{crop_id}.jpg"
            image.crop(tuple(crop)).save(derivative, "JPEG", quality=95, subsampling=0)
        (output / "prelabels" / f"{crop_id}.json").write_text(json.dumps({
            "crop_id": crop_id,
            "source": "empty_to_avoid_pending_review_bias",
            "is_ground_truth": False,
            "boxes": [],
        }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        rows.append({
            "crop_id": crop_id,
            "image": str(derivative.resolve()),
            "image_sha256": sha256_file(derivative),
            "source_photo_id": photo_id,
            "source_task_id": str(detail["task_id"]),
            "source_image_sha256": expected_hash,
            "sku_code": str(detail["sku_code"]),
            "angle": "只审核画面中央这一只完整目标托盘；只补白标/彩标框，不补托盘框",
            "label_v1_verdict": str(target["current_verdict"]),
            "human_qc_verdict": "UNJUDGEABLE",
            "model_previous_verdict": str(target["old_verdict"]),
            "model_current_verdict": str(target["current_verdict"]),
            "target_tray_index": int(target["tray_index"]),
            "target_tray_box_normalized": target["bbox"],
            "source_crop_rect_px": crop,
            "review_reason": "pending_photo_residual_after_production_equivalent_replay",
            "prelabel_boxes": 0,
            "review_scope": "single_complete_target_tray_only",
            "training_eligible": False,
        })
        if sha256_file(source) != expected_hash:
            raise RuntimeError(f"pending source original changed: {photo_id}")

    manifest = {
        "version": "liushanmen-pending-target-tray-review-v1",
        "queue_id": output.name,
        "queue_count": len(rows),
        "annotator_url": annotator_url,
        "source_pending_manifest": str(pending_path.resolve()),
        "source_pending_manifest_sha256": sha256_file(pending_path),
        "source_replay": str(replay_path.resolve()),
        "source_replay_sha256": sha256_file(replay_path),
        "preannotations_are_not_ground_truth": True,
        "every_image_requires_full_human_review": True,
        "review_scope": "single complete target tray only",
        "missing_class_review_allowed": True,
        "factory_tray_box_annotation_required": False,
        "train_only_after_human_review": True,
        "evaluation_or_production_use_allowed": False,
        "production_writes": 0,
        "originals_modified": 0,
        "rows": rows,
    }
    (output / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pending-manifest", required=True, type=Path)
    parser.add_argument("--replay", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--annotator-url", default="http://127.0.0.1:8799/")
    args = parser.parse_args()
    manifest = build(
        args.pending_manifest.resolve(), args.replay.resolve(), args.output.resolve(),
        args.annotator_url,
    )
    print(json.dumps({
        "queue": str(args.output.resolve()), "count": manifest["queue_count"],
        "annotator_url": manifest["annotator_url"], "production_writes": 0,
        "originals_modified": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
