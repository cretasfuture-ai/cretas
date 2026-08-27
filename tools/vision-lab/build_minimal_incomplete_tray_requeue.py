#!/usr/bin/env python3
"""Rebuild minimal-review incomplete trays from immutable full source photos."""
from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
import sys
from pathlib import Path
from typing import Any, Sequence

import numpy as np
from PIL import Image, ImageOps

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))
from audit_pending_tray_reconstruction import reconstruct_full_tray


VERSION = "liushanmen-incomplete-tray-requeue-v3"
SOURCE_VERSION = "liushanmen-minimal-active-label-review-v1"
MIN_RECONSTRUCTION_AREA_RATIO = 1.20
MAX_RECONSTRUCTION_CENTER_SHIFT_RATIO = 0.25


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


def area(box: Sequence[float]) -> float:
    return max(0.0, float(box[2]) - float(box[0])) * max(
        0.0, float(box[3]) - float(box[1])
    )


def center_shift_ratio(candidate: Sequence[float], target: Sequence[float]) -> float:
    target_scale = max(
        float(target[2]) - float(target[0]),
        float(target[3]) - float(target[1]),
    )
    if target_scale <= 0:
        raise RuntimeError("invalid target tray box")
    candidate_center = (
        (float(candidate[0]) + float(candidate[2])) / 2,
        (float(candidate[1]) + float(candidate[3])) / 2,
    )
    target_center = (
        (float(target[0]) + float(target[2])) / 2,
        (float(target[1]) + float(target[3])) / 2,
    )
    return max(
        abs(candidate_center[0] - target_center[0]),
        abs(candidate_center[1] - target_center[1]),
    ) / target_scale


def wide_review_crop(box: Sequence[float], width: int, height: int) -> list[int]:
    x0, y0, x1, y1 = (float(value) for value in box)
    target_scale = max(x1 - x0, y1 - y0)
    if target_scale <= 0:
        raise RuntimeError("invalid source target tray box")
    pad = target_scale * 1.25
    crop = [
        max(0, round(x0 - pad)), max(0, round(y0 - pad)),
        min(width, round(x1 + pad)), min(height, round(y1 + pad)),
    ]
    if crop[2] <= crop[0] or crop[3] <= crop[1]:
        raise RuntimeError("invalid wide review crop")
    return crop


def box_in_crop(box: Sequence[float], crop: Sequence[int]) -> list[float]:
    width, height = crop[2] - crop[0], crop[3] - crop[1]
    values = [
        (float(box[0]) - crop[0]) / width,
        (float(box[1]) - crop[1]) / height,
        (float(box[2]) - crop[0]) / width,
        (float(box[3]) - crop[1]) / height,
    ]
    if not all(0 <= value <= 1 for value in values):
        raise RuntimeError("candidate tray guide falls outside review crop")
    return [round(value, 6) for value in values]


def build(
    source_queue: Path, database: Path, bundle: Path, repo_root: Path,
    output: Path, annotator_url: str, threshold: float = 0.15,
) -> dict[str, Any]:
    if output.exists():
        raise FileExistsError(f"refusing to overwrite requeue: {output}")
    manifest_path = source_queue / "manifest.json"
    manifest = load_json(manifest_path)
    if manifest.get("version") != SOURCE_VERSION:
        raise RuntimeError("source queue is not a minimal active-label review queue")
    if manifest.get("preannotations_are_not_ground_truth") is not True:
        raise RuntimeError("source queue preannotation contract is missing")

    selected: list[tuple[dict[str, Any], Path]] = []
    for row in manifest.get("rows") or []:
        annotation_path = source_queue / "annotations-human" / f"{row['crop_id']}.json"
        if not annotation_path.is_file():
            raise RuntimeError(f"source review is incomplete: {row['crop_id']}")
        annotation = load_json(annotation_path)
        if annotation.get("reviewed") is not True:
            raise RuntimeError(f"source review is incomplete: {row['crop_id']}")
        if annotation.get("incomplete_tray") is True:
            if (
                annotation.get("training_eligible") is not False
                or annotation.get("review_round_action") != "REQUEUE_AFTER_TRAY_REDETECTION"
            ):
                raise RuntimeError(f"incomplete-tray safety fields drifted: {row['crop_id']}")
            selected.append((row, annotation_path))
    if not selected:
        raise RuntimeError("source queue has no incomplete trays to requeue")

    sys.path.insert(0, str((repo_root / "backend" / "python").resolve()))
    from label_qc.services.v8_pipeline import LabelQcV8Models

    tray_supplement = LabelQcV8Models(bundle)._require_models().tray_supplement
    connection = sqlite3.connect(database)
    try:
        source_rows = {
            str(photo_id): (str(local_path), str(source_sha))
            for photo_id, local_path, source_sha in connection.execute(
                "SELECT photo_id, local_path, sha256 FROM photos"
            )
        }
    finally:
        connection.close()

    for name in ("images", "prelabels", "annotations-human"):
        (output / name).mkdir(parents=True, exist_ok=False)
    output_rows = []
    reconstructed_count = 0
    human_target_required_count = 0
    for row, annotation_path in selected:
        photo_id = str(row["source_photo_id"])
        source_info = source_rows.get(photo_id)
        if source_info is None:
            raise RuntimeError(f"source photo missing from immutable store: {photo_id}")
        source = Path(source_info[0]).resolve()
        if not source.is_file() or sha256_file(source) != source_info[1]:
            raise RuntimeError(f"source photo drift: {photo_id}")
        with Image.open(source) as opened:
            image = ImageOps.exif_transpose(opened).convert("RGB")
        if [image.width, image.height] != list(row["source_image_size"]):
            raise RuntimeError(f"source image geometry drift: {photo_id}")
        frame = np.asarray(image)
        target = [float(value) for value in row["target_tray_box_source_xyxy"]]
        proposals = []
        tile_height = max(1, round(image.height * 0.60))
        tile_starts = sorted({0, max(0, round(image.height * 0.20)), image.height - tile_height})
        for tile_y0 in tile_starts:
            tile = frame[tile_y0:tile_y0 + tile_height, :]
            for detection in tray_supplement.detect(tile, threshold):
                proposals.append({
                    "box": [
                        float(detection.x0), float(detection.y0) + tile_y0,
                        float(detection.x1), float(detection.y1) + tile_y0,
                    ],
                    "confidence": round(float(detection.confidence), 6),
                    "tile_y0": tile_y0,
                })
        reconstructed, selected_indices = reconstruct_full_tray(target, proposals)
        ratio = area(reconstructed) / max(1.0, area(target))
        shift_ratio = center_shift_ratio(reconstructed, target)
        reconstruction_accepted = (
            ratio >= MIN_RECONSTRUCTION_AREA_RATIO
            and shift_ratio <= MAX_RECONSTRUCTION_CENTER_SHIFT_RATIO
        )
        crop_basis = reconstructed if reconstruction_accepted else target
        crop = wide_review_crop(crop_basis, image.width, image.height)
        crop_id = f"{row['crop_id']}_full_r02"
        derivative = output / "images" / f"{crop_id}.jpg"
        image.crop(tuple(crop)).save(derivative, "JPEG", quality=95, subsampling=0)

        # The annotator renders an unconfirmed manifest guide only as a centre
        # crosshair.  Keep the partial detector box for ownership/location even
        # when it is unsafe as a full-tray reconstruction; it is never copied to
        # the human target tray field or admitted as ground truth.
        guide = box_in_crop(target, crop)
        guide_source = "partial_detector_locator_only_human_t_key_required"
        if reconstruction_accepted:
            guide_source = "original_target_locator_with_center_stable_supplemental_reconstruction"
            reconstructed_count += 1
        else:
            human_target_required_count += 1
        (output / "prelabels" / f"{crop_id}.json").write_text(json.dumps({
            "crop_id": crop_id,
            "source": "no_label_boxes_carried_from_incomplete_review",
            "is_ground_truth": False,
            "boxes": [],
        }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        output_rows.append({
            "crop_id": crop_id,
            "image": str(derivative.resolve()),
            "image_sha256": sha256_file(derivative),
            "source_photo_id": photo_id,
            "source_task_id": str(row["source_task_id"]),
            "source_image_sha256": source_info[1],
            "sku_code": str(row["sku_code"]),
            "angle": "第2轮：先按 T 确认/重框完整物理托盘，再标该盘全部白标和彩标",
            "label_v1_verdict": "INCOMPLETE_TRAY_REQUEUE",
            "human_qc_verdict": "UNJUDGEABLE",
            "model_previous_verdict": str(row.get("label_v1_verdict") or "UNJUDGEABLE"),
            "model_current_verdict": "PENDING_FULL_TRAY_REVIEW",
            "target_tray_guide_normalized": guide,
            "target_tray_guide_source": guide_source,
            "source_crop_rect_px": crop,
            "previous_crop_rect_px": row["crop_rect_source_xyxy"],
            "previous_crop_id": str(row["crop_id"]),
            "previous_annotation_sha256": sha256_file(annotation_path),
            "review_crop_geometry": {
                "policy_version": "full-source-supplemental-reconstruction-v1",
                "source_frame_limited": any((crop[0] == 0, crop[1] == 0, crop[2] == image.width, crop[3] == image.height)),
                "reconstruction_area_ratio": round(ratio, 6),
                "reconstruction_center_shift_ratio": round(shift_ratio, 6),
                "reconstruction_accepted": reconstruction_accepted,
                "raw_proposal_count": len(proposals),
                "selected_proposal_indices": selected_indices,
            },
            "review_round": 2,
            "prelabel_boxes": 0,
            "review_scope": "single_complete_target_tray_only",
            "training_eligible": False,
            "training_exclusion_reason": "awaiting_incomplete_tray_requeue_review",
        })

    receipt = {
        "version": VERSION,
        "queue_id": output.name,
        "queue_count": len(output_rows),
        "review_round": 2,
        "annotator_url": annotator_url,
        "source_queue": str(source_queue.resolve()),
        "source_queue_manifest_sha256": sha256_file(manifest_path),
        "preannotations_are_not_ground_truth": True,
        "every_image_requires_full_human_review": True,
        "train_only_after_human_review": True,
        "incomplete_tray_can_requeue_again": True,
        "target_tray_guides_are_read_only": True,
        "target_tray_guides_are_not_ground_truth": True,
        "evaluation_or_production_use_allowed": False,
        "supplemental_reconstruction_count": reconstructed_count,
        "human_target_box_required_count": human_target_required_count,
        "production_writes": 0,
        "originals_modified": 0,
        "rows": output_rows,
    }
    (output / "manifest.json").write_text(
        json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-queue", required=True, type=Path)
    parser.add_argument("--database", required=True, type=Path)
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--annotator-url", default="http://127.0.0.1:8789/")
    parser.add_argument("--threshold", type=float, default=0.15)
    args = parser.parse_args()
    receipt = build(
        args.source_queue.resolve(), args.database.resolve(), args.bundle.resolve(),
        args.repo_root.resolve(), args.output.resolve(), args.annotator_url, args.threshold,
    )
    print(json.dumps({
        "queue": str(args.output.resolve()),
        "count": receipt["queue_count"],
        "supplemental_reconstruction_count": receipt["supplemental_reconstruction_count"],
        "human_target_box_required_count": receipt["human_target_box_required_count"],
        "annotator_url": receipt["annotator_url"],
        "production_writes": 0,
        "originals_modified": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
