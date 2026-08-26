#!/usr/bin/env python3
"""Build the next review round for crops marked as an incomplete tray."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any, Sequence

from PIL import Image, ImageOps


VERSION = "liushanmen-incomplete-tray-requeue-v2"


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


def expanded_crop(
    box: Sequence[float], width: int, height: int, review_round: int,
) -> tuple[list[int], dict[str, Any]]:
    if review_round < 2:
        raise RuntimeError("incomplete-tray requeue round must be at least 2")
    x0, y0, x1, y1 = (
        float(box[0]) * width, float(box[1]) * height,
        float(box[2]) * width, float(box[3]) * height,
    )
    target_width, target_height = x1 - x0, y1 - y0
    if target_width <= 0 or target_height <= 0:
        raise RuntimeError(f"invalid target tray box: {box}")
    target_scale = max(target_width, target_height)
    pad_scale = min(1.35, 0.65 + 0.35 * (review_round - 2))
    pad = target_scale * pad_scale
    crop = [
        max(0, round(x0 - pad)), max(0, round(y0 - pad)),
        min(width, round(x1 + pad)), min(height, round(y1 + pad)),
    ]
    if crop[2] <= crop[0] or crop[3] <= crop[1]:
        raise RuntimeError(f"invalid expanded crop: {box}")
    geometry = {
        "policy_version": "human-incomplete-centered-expansion-v1",
        "review_round": review_round,
        "target_scale_px": round(target_scale, 3),
        "expansion_pad_to_target_scale": pad_scale,
        "source_frame_limited": (
            crop[0] == 0 or crop[1] == 0 or crop[2] == width or crop[3] == height
        ),
    }
    return crop, geometry


def transform_boxes(
    boxes: list[dict[str, Any]], old_crop: Sequence[int], new_crop: Sequence[int],
) -> list[dict[str, Any]]:
    old_width, old_height = old_crop[2] - old_crop[0], old_crop[3] - old_crop[1]
    new_width, new_height = new_crop[2] - new_crop[0], new_crop[3] - new_crop[1]
    transformed = []
    for box in boxes:
        normalized = box.get("b")
        if not isinstance(normalized, list) or len(normalized) != 4:
            raise RuntimeError("incomplete-tray annotation has invalid label box")
        source = [
            old_crop[0] + float(normalized[0]) * old_width,
            old_crop[1] + float(normalized[1]) * old_height,
            old_crop[0] + float(normalized[2]) * old_width,
            old_crop[1] + float(normalized[3]) * old_height,
        ]
        if (
            source[0] < new_crop[0] or source[1] < new_crop[1]
            or source[2] > new_crop[2] or source[3] > new_crop[3]
        ):
            raise RuntimeError("expanded crop does not contain carried human label box")
        transformed.append({
            "class_id": int(box["c"]),
            "bbox_normalized_xyxy": [
                round((source[0] - new_crop[0]) / new_width, 6),
                round((source[1] - new_crop[1]) / new_height, 6),
                round((source[2] - new_crop[0]) / new_width, 6),
                round((source[3] - new_crop[1]) / new_height, 6),
            ],
            "source": "carried_human_box_from_incomplete_prior_round",
            "is_ground_truth": False,
        })
    return transformed


def target_guide_in_crop(
    box: Sequence[float], image_width: int, image_height: int, crop: Sequence[int],
) -> list[float]:
    """Map the source-image target detection into the review crop.

    This is an ownership guide only.  It deliberately remains separate from label
    boxes and must never be exported as tray ground truth.
    """
    crop_width, crop_height = crop[2] - crop[0], crop[3] - crop[1]
    if crop_width <= 0 or crop_height <= 0:
        raise RuntimeError("invalid review crop for target guide")
    source = [
        float(box[0]) * image_width, float(box[1]) * image_height,
        float(box[2]) * image_width, float(box[3]) * image_height,
    ]
    guide = [
        (source[0] - crop[0]) / crop_width,
        (source[1] - crop[1]) / crop_height,
        (source[2] - crop[0]) / crop_width,
        (source[3] - crop[1]) / crop_height,
    ]
    if not all(0 <= value <= 1 for value in guide) or not (
        guide[0] < guide[2] and guide[1] < guide[3]
    ):
        raise RuntimeError("review crop does not contain target tray guide")
    return [round(value, 6) for value in guide]


def build(
    source_queue: Path, output: Path, review_round: int, annotator_url: str,
    carry_reviewed_from: Path | None = None,
) -> dict[str, Any]:
    manifest_path = source_queue / "manifest.json"
    manifest = load_json(manifest_path)
    pending_path_value = manifest.get("source_pending_manifest")
    if manifest.get("version") != "liushanmen-pending-target-tray-review-v1" or not pending_path_value:
        raise RuntimeError("source queue is not a pending target-tray review queue")
    pending_path = Path(str(pending_path_value)).resolve()
    pending = load_json(pending_path)
    pending_rows = {str(row["photo_id"]): row for row in pending.get("rows") or []}
    if output.exists():
        raise FileExistsError(f"refusing to overwrite requeue: {output}")

    selected: list[tuple[dict[str, Any], dict[str, Any]]] = []
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
            selected.append((row, annotation))
    if not selected:
        raise RuntimeError("source queue has no incomplete trays to requeue")

    carried_reviewed: dict[str, dict[str, Any]] = {}
    if carry_reviewed_from is not None:
        carry_manifest = load_json(carry_reviewed_from / "manifest.json")
        if (
            carry_manifest.get("source_queue_manifest_sha256") != sha256_file(manifest_path)
            or int(carry_manifest.get("review_round", -1)) != review_round
        ):
            raise RuntimeError("review carry queue does not match source queue and round")
        for carry_row in carry_manifest.get("rows") or []:
            carry_id = str(carry_row.get("crop_id") or "")
            annotation_path = carry_reviewed_from / "annotations-human" / f"{carry_id}.json"
            if annotation_path.is_file():
                annotation = load_json(annotation_path)
                if annotation.get("reviewed") is True:
                    carried_reviewed[carry_id] = annotation

    for name in ("images", "prelabels", "annotations-human"):
        (output / name).mkdir(parents=True, exist_ok=False)
    output_rows = []
    for position, (row, annotation) in enumerate(selected, start=1):
        photo_id = str(row["source_photo_id"])
        pending_row = pending_rows.get(photo_id)
        if pending_row is None:
            raise RuntimeError(f"pending source photo missing: {photo_id}")
        source = Path(str(pending_row["image"])).resolve()
        expected_sha = str(row["source_image_sha256"])
        if not source.is_file() or sha256_file(source) != expected_sha:
            raise RuntimeError(f"pending source image drift: {photo_id}")
        with Image.open(source) as opened:
            image = ImageOps.exif_transpose(opened).convert("RGB")
            crop, geometry = expanded_crop(
                row["target_tray_box_normalized"], image.width, image.height, review_round,
            )
            target_guide = target_guide_in_crop(
                row["target_tray_box_normalized"], image.width, image.height, crop,
            )
            crop_id = f"{row['crop_id']}_r{review_round:02d}"
            derivative = output / "images" / f"{crop_id}.jpg"
            image.crop(tuple(crop)).save(derivative, "JPEG", quality=95, subsampling=0)
        carried = transform_boxes(
            list(annotation.get("boxes") or []), row["source_crop_rect_px"], crop,
        )
        (output / "prelabels" / f"{crop_id}.json").write_text(json.dumps({
            "crop_id": crop_id,
            "source": "carried_human_boxes_require_full_round_review",
            "is_ground_truth": False,
            "boxes": carried,
        }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        carried_annotation = carried_reviewed.get(crop_id)
        if carried_annotation is not None:
            carried_annotation = dict(carried_annotation)
            carried_annotation["carried_from_queue"] = str(carry_reviewed_from.resolve())
            (output / "annotations-human" / f"{crop_id}.json").write_text(
                json.dumps(carried_annotation, ensure_ascii=False, indent=1) + "\n",
                encoding="utf-8",
            )
        output_rows.append({
            "crop_id": crop_id,
            "image": str(derivative.resolve()),
            "image_sha256": sha256_file(derivative),
            "source_photo_id": photo_id,
            "source_task_id": str(row["source_task_id"]),
            "source_image_sha256": expected_sha,
            "sku_code": str(row["sku_code"]),
            "angle": f"第{review_round}轮：只审核画面中央目标托盘；仍不完整请再次按 I 回流",
            "label_v1_verdict": "INCOMPLETE_TRAY_REQUEUE",
            "human_qc_verdict": "UNJUDGEABLE",
            "model_previous_verdict": str(row.get("model_current_verdict") or "UNJUDGEABLE"),
            "model_current_verdict": "PENDING_SECOND_ROUND_REVIEW",
            "target_tray_index": int(row["target_tray_index"]),
            "target_tray_box_normalized": row["target_tray_box_normalized"],
            "target_tray_guide_normalized": target_guide,
            "target_tray_guide_source": "prior_round_target_detection_read_only_not_ground_truth",
            "source_crop_rect_px": crop,
            "previous_crop_rect_px": row["source_crop_rect_px"],
            "previous_crop_id": str(row["crop_id"]),
            "previous_annotation_sha256": sha256_file(
                source_queue / "annotations-human" / f"{row['crop_id']}.json"
            ),
            "review_crop_geometry": geometry,
            "review_round": review_round,
            "prelabel_boxes": len(carried),
            "review_scope": "single_complete_target_tray_only",
            "training_eligible": False,
            "training_exclusion_reason": "awaiting_incomplete_tray_requeue_review",
        })

    output_manifest = {
        "version": VERSION,
        "queue_id": output.name,
        "queue_count": len(output_rows),
        "review_round": review_round,
        "annotator_url": annotator_url,
        "source_queue": str(source_queue.resolve()),
        "source_queue_manifest_sha256": sha256_file(manifest_path),
        "source_pending_manifest": str(pending_path),
        "source_pending_manifest_sha256": sha256_file(pending_path),
        "preannotations_are_not_ground_truth": True,
        "every_image_requires_full_human_review": True,
        "train_only_after_human_review": True,
        "incomplete_tray_can_requeue_again": True,
        "target_tray_guides_are_read_only": True,
        "target_tray_guides_are_not_ground_truth": True,
        "reviewed_annotations_carried": len(carried_reviewed),
        "evaluation_or_production_use_allowed": False,
        "production_writes": 0,
        "originals_modified": 0,
        "rows": output_rows,
    }
    (output / "manifest.json").write_text(
        json.dumps(output_manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    return output_manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-queue", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--round", required=True, type=int)
    parser.add_argument("--annotator-url", default="http://127.0.0.1:8799/")
    parser.add_argument("--carry-reviewed-from", type=Path)
    args = parser.parse_args()
    manifest = build(
        args.source_queue.resolve(), args.output.resolve(), args.round, args.annotator_url,
        args.carry_reviewed_from.resolve() if args.carry_reviewed_from else None,
    )
    print(json.dumps({
        "queue": str(args.output.resolve()),
        "count": manifest["queue_count"],
        "review_round": manifest["review_round"],
        "reviewed_annotations_carried": manifest["reviewed_annotations_carried"],
        "annotator_url": manifest["annotator_url"],
        "production_writes": 0,
        "originals_modified": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
