#!/usr/bin/env python3
"""Build a minimal full-context review queue for quarantined historical negatives."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any, Sequence

from PIL import Image, ImageOps


QUEUE_VERSION = "liushanmen-historical-negative-context-review-v1"
REVIEW_CROP_POLICY_VERSION = "missing-class-width-context-v1"


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


def physical_context_crop(box: Sequence[float], width: int, height: int) -> list[int]:
    x0, y0, x1, y1 = (
        float(box[0]) * width, float(box[1]) * height,
        float(box[2]) * width, float(box[3]) * height,
    )
    tray_width, tray_height = x1 - x0, y1 - y0
    if tray_width <= 0 or tray_height <= 0:
        raise RuntimeError(f"invalid tray box: {box}")
    physical_height = max(tray_height, tray_width * 0.75)
    crop = [
        max(0, round(x0 - tray_width * 0.15)),
        max(0, round(y0 - tray_width * 0.08)),
        min(width, round(x1 + tray_width * 0.15)),
        min(height, round(y0 + physical_height + tray_width * 0.10)),
    ]
    if crop[2] <= crop[0] or crop[3] <= crop[1]:
        raise RuntimeError(f"invalid physical review crop: {box}")
    return crop


def build(audit_path: Path, output: Path, annotator_url: str) -> dict[str, Any]:
    audit = load_json(audit_path)
    if audit.get("version") != "liushanmen-label-training-crop-integrity-audit-v1":
        raise RuntimeError("unsupported historical crop audit")
    if audit.get("production_reuse_gate_passed") is not False:
        raise RuntimeError("audit has no blocked production material")
    if output.exists():
        raise FileExistsError(f"refusing to overwrite review queue: {output}")
    review = [
        row for row in audit.get("production_quarantine") or []
        if row.get("classification") == "negative_context_risk"
    ]
    if not review:
        raise RuntimeError("audit has no negative-context risk rows")
    for name in ("images", "prelabels", "annotations-human"):
        (output / name).mkdir(parents=True, exist_ok=False)

    queue_cache: dict[Path, dict[str, dict[str, Any]]] = {}
    rows = []
    for position, risk in enumerate(review, start=1):
        queue = Path(str(risk["source_queue"])).resolve()
        if queue not in queue_cache:
            queue_manifest = load_json(queue / "manifest.json")
            queue_cache[queue] = {
                str(record["photo_id"]): record
                for record in queue_manifest.get("records") or []
            }
        photo_id = str(risk["photo_id"])
        record = queue_cache[queue].get(photo_id)
        if record is None:
            raise RuntimeError(f"source photo missing from queue: {photo_id}")
        annotation_path = queue / "annotations-human" / f"{photo_id}.json"
        annotation = load_json(annotation_path)
        if sha256_file(annotation_path) != risk["truth_sha256"]:
            raise RuntimeError(f"historical truth drift: {photo_id}")
        tray = next((
            box for box in annotation.get("boxes") or []
            if str(box.get("id")) == str(risk["parent_tray_id"])
            and box.get("type") == "TRAY"
        ), None)
        if tray is None:
            raise RuntimeError(f"historical parent tray missing: {photo_id}")
        source = Path(str(record["image"])).resolve()
        if not source.is_file() or sha256_file(source) != risk["source_image_sha256"]:
            raise RuntimeError(f"historical source image drift: {photo_id}")
        with Image.open(source) as opened:
            image = ImageOps.exif_transpose(opened).convert("RGB")
            crop = physical_context_crop(tray["bbox"], image.width, image.height)
            crop_id = f"historical_{risk['material_key'][:16]}_{position:03d}"
            derivative = output / "images" / f"{crop_id}.jpg"
            image.crop(tuple(crop)).save(derivative, "JPEG", quality=95, subsampling=0)
        prelabel = {
            "crop_id": crop_id,
            "source": "empty_to_avoid_historical_truth_bias",
            "is_ground_truth": False,
            "boxes": [],
        }
        (output / "prelabels" / f"{crop_id}.json").write_text(
            json.dumps(prelabel, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
        )
        rows.append({
            "crop_id": crop_id,
            "image": str(derivative.resolve()),
            "image_sha256": sha256_file(derivative),
            "source_photo_id": photo_id,
            "source_task_id": str(risk["task_id"]),
            "sku_code": str(record.get("sku_code") or "LIUSHANMEN-HISTORICAL"),
            "source_image_sha256": risk["source_image_sha256"],
            "source_truth_sha256": risk["truth_sha256"],
            "source_parent_tray_id": str(risk["parent_tray_id"]),
            "source_crop_rect_px": crop,
            "quarantined_crop_rect_px": risk["crop_box_px"],
            "quarantined_white_status": risk["white_status"],
            "quarantined_color_status": risk["color_status"],
            "angle": "只复核画面中央这一只完整目标托盘；只框真实存在的白标/彩标，不补托盘框",
            "label_v1_verdict": "UNJUDGEABLE",
            "human_qc_verdict": "UNJUDGEABLE",
            "model_previous_verdict": "UNJUDGEABLE",
            "model_current_verdict": "UNJUDGEABLE",
            "review_reason": "historical_missing_class_negative_with_incomplete_physical_context",
            "review_scope": "single_complete_target_tray_only",
            "prelabel_boxes": 0,
            "training_eligible": False,
        })

    manifest = {
        "version": QUEUE_VERSION,
        "queue_id": output.name,
        "queue_count": len(rows),
        "annotator_url": annotator_url,
        "source_audit": str(audit_path.resolve()),
        "source_audit_sha256": sha256_file(audit_path),
        "review_crop_policy_version": REVIEW_CROP_POLICY_VERSION,
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
    parser.add_argument("--audit", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--annotator-url", default="http://127.0.0.1:8800/")
    args = parser.parse_args()
    manifest = build(args.audit.resolve(), args.output.resolve(), args.annotator_url)
    print(json.dumps({
        "queue": str(args.output.resolve()),
        "count": manifest["queue_count"],
        "annotator_url": manifest["annotator_url"],
        "production_writes": 0,
        "originals_modified": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
