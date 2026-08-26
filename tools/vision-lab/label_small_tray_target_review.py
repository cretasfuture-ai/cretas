#!/usr/bin/env python3
"""Repack ambiguous small-tray reviews as tight, single-target crops."""
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


def tight_crop(
    box: Sequence[float], width: int, height: int, pad_ratio: float,
    bottom_pad_ratio: float | None = None,
) -> list[int]:
    x0, y0, x1, y1 = (float(value) for value in box)
    pad_x, pad_y = (x1 - x0) * pad_ratio, (y1 - y0) * pad_ratio
    bottom_pad_y = (y1 - y0) * (
        pad_ratio if bottom_pad_ratio is None else bottom_pad_ratio
    )
    crop = [
        max(0, round(x0 - pad_x)),
        max(0, round(y0 - pad_y)),
        min(width, round(x1 + pad_x)),
        min(height, round(y1 + bottom_pad_y)),
    ]
    if crop[2] <= crop[0] or crop[3] <= crop[1]:
        raise RuntimeError(f"invalid target crop: {box}")
    return crop


def normalise_box(box: Sequence[float], crop: Sequence[int]) -> list[float] | None:
    clipped = [
        max(float(crop[0]), float(box[0])), max(float(crop[1]), float(box[1])),
        min(float(crop[2]), float(box[2])), min(float(crop[3]), float(box[3])),
    ]
    if clipped[2] <= clipped[0] or clipped[3] <= clipped[1]:
        return None
    width, height = crop[2] - crop[0], crop[3] - crop[1]
    return [
        round((clipped[0] - crop[0]) / width, 6),
        round((clipped[1] - crop[1]) / height, 6),
        round((clipped[2] - crop[0]) / width, 6),
        round((clipped[3] - crop[1]) / height, 6),
    ]


def build(
    source_queue: Path, output: Path, crop_ids: Sequence[str],
    annotator_url: str, pad_ratio: float, bottom_pad_ratio: float | None = None,
) -> dict[str, Any]:
    source_queue = source_queue.resolve()
    output = output.resolve()
    manifest_path = source_queue / "manifest.json"
    scan_path = source_queue / "scan-receipt.json"
    manifest, scan = load_json(manifest_path), load_json(scan_path)
    if manifest.get("version") != "liushanmen-small-tray-policy-review-v1":
        raise RuntimeError("unsupported source review manifest")
    if scan.get("version") != "liushanmen-small-tray-policy-scan-v1":
        raise RuntimeError("unsupported source scan receipt")
    if manifest.get("source_scan_receipt_sha256") != sha256_file(scan_path):
        raise RuntimeError("source scan receipt hash drift")
    if output.exists():
        raise FileExistsError(f"refusing to overwrite target review queue: {output}")
    wanted = list(crop_ids)
    if not wanted or len(wanted) != len(set(wanted)):
        raise ValueError("crop ids must be non-empty and unique")
    source_rows = {str(row["crop_id"]): row for row in manifest.get("rows") or []}
    selected_scan = {str(row["photo_id"]): row for row in scan.get("selected") or []}
    if set(wanted) - set(source_rows):
        raise RuntimeError("requested crop id is absent from source queue")

    for name in ("images", "prelabels", "annotations-human"):
        (output / name).mkdir(parents=True, exist_ok=False)
    rows = []
    for index, crop_id in enumerate(wanted, start=1):
        source_row = source_rows[crop_id]
        scan_row = selected_scan[str(source_row["source_photo_id"])]
        source = Path(str(scan_row["image"])).resolve()
        if not source.is_file() or sha256_file(source) != str(scan_row["image_sha256"]):
            raise RuntimeError(f"source image drift: {source}")
        tray = scan_row["tray"]
        with Image.open(source) as opened:
            image = ImageOps.exif_transpose(opened).convert("RGB")
            crop = tight_crop(
                tray["box"], image.width, image.height, pad_ratio, bottom_pad_ratio,
            )
            derivative = image.crop(tuple(crop))
            target_id = f"target_{source_row['source_photo_id'][:8]}_t{int(tray['index']):02d}_{index:03d}"
            image_path = output / "images" / f"{target_id}.jpg"
            derivative.save(image_path, format="JPEG", quality=95, subsampling=0)
        proposals = []
        for label in tray.get("labels") or []:
            box = normalise_box(label["sourceBox"], crop)
            if box is not None:
                proposals.append({
                    "class_id": int(label["classId"]),
                    "bbox_normalized_xyxy": box,
                    "confidence": float(label["confidence"]),
                })
        prelabel_path = output / "prelabels" / f"{target_id}.json"
        prelabel_path.write_text(json.dumps({
            "crop_id": target_id,
            "source": "frozen_production_owned_labels_target_tray_only",
            "is_ground_truth": False,
            "boxes": proposals,
        }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        if sha256_file(source) != str(scan_row["image_sha256"]):
            raise RuntimeError(f"source original changed: {source}")
        rows.append({
            "crop_id": target_id,
            "image": str(image_path.resolve()),
            "image_sha256": sha256_file(image_path),
            "source_photo_id": source_row["source_photo_id"],
            "source_task_id": source_row["source_task_id"],
            "source_image_sha256": source_row["source_image_sha256"],
            "sku_code": source_row["sku_code"],
            "angle": "紧裁单个目标盒：只标这个盒自身的白标和彩标",
            "label_v1_verdict": source_row["model_previous_verdict"],
            "human_qc_verdict": "UNJUDGEABLE",
            "model_previous_verdict": source_row["model_previous_verdict"],
            "source_review_crop_id": crop_id,
            "target_tray_index": int(tray["index"]),
            "target_tray_box_px": tray["box"],
            "source_crop_rect_px": crop,
            "review_reason": "supersede_ambiguous_context_crop_with_tight_target_crop",
            "prelabel_boxes": len(proposals),
            "review_scope": "single_tight_target_tray_only",
            "training_eligible": False,
        })
    output_manifest = {
        "version": "liushanmen-small-tray-tight-target-review-v1",
        "queue_id": output.name,
        "queue_count": len(rows),
        "annotator_url": annotator_url,
        "source_queue": str(source_queue),
        "source_manifest_sha256": sha256_file(manifest_path),
        "source_scan_receipt_sha256": sha256_file(scan_path),
        "source_context_review_superseded": True,
        "preannotations_are_not_ground_truth": True,
        "every_image_requires_full_human_review": True,
        "review_scope": "single tight target tray only",
        "missing_class_review_allowed": True,
        "factory_tray_box_annotation_required": False,
        "train_only_after_human_review": True,
        "evaluation_or_production_use_allowed": False,
        "production_writes": 0,
        "originals_modified": 0,
        "rows": rows,
    }
    (output / "manifest.json").write_text(
        json.dumps(output_manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    return output_manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-queue", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--crop-id", required=True, action="append")
    parser.add_argument("--pad-ratio", type=float, default=0.15)
    parser.add_argument(
        "--bottom-pad-ratio", type=float,
        help="optional extra downward padding for detector boxes that cover only a tray top",
    )
    parser.add_argument("--annotator-url", default="http://127.0.0.1:8798/")
    args = parser.parse_args()
    if not 0.0 <= args.pad_ratio <= 0.30:
        raise ValueError("pad ratio must be between 0 and 0.30")
    if args.bottom_pad_ratio is not None and not 0.0 <= args.bottom_pad_ratio <= 2.0:
        raise ValueError("bottom pad ratio must be between 0 and 2.0")
    manifest = build(
        args.source_queue, args.output, args.crop_id, args.annotator_url, args.pad_ratio,
        args.bottom_pad_ratio,
    )
    print(json.dumps({
        "queue": str(args.output.resolve()), "count": manifest["queue_count"],
        "annotator_url": manifest["annotator_url"], "production_writes": 0,
        "originals_modified": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
