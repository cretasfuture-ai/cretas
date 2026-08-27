#!/usr/bin/env python3
"""Build a minimal target-tray-only human queue from a recent blind audit."""
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


def containment(candidate: Sequence[float], truth: Sequence[float]) -> float:
    x0, y0 = max(candidate[0], truth[0]), max(candidate[1], truth[1])
    x1, y1 = min(candidate[2], truth[2]), min(candidate[3], truth[3])
    intersection = max(0.0, x1 - x0) * max(0.0, y1 - y0)
    truth_area = max(0.0, truth[2] - truth[0]) * max(0.0, truth[3] - truth[1])
    return intersection / truth_area if truth_area else 0.0


def context_crop(box: Sequence[float], width: int, height: int) -> list[int]:
    box_width = max(1.0, float(box[2]) - float(box[0]))
    box_height = max(1.0, float(box[3]) - float(box[1]))
    return [
        max(0, round(float(box[0]) - box_width * 0.50)),
        max(0, round(float(box[1]) - box_height * 1.50)),
        min(width, round(float(box[2]) + box_width * 0.50)),
        min(height, round(float(box[3]) + box_height * 1.50)),
    ]


def normalized_box(source: Sequence[float], crop: Sequence[int]) -> list[float] | None:
    crop_width, crop_height = crop[2] - crop[0], crop[3] - crop[1]
    clipped = [
        max(float(crop[0]), float(source[0])),
        max(float(crop[1]), float(source[1])),
        min(float(crop[2]), float(source[2])),
        min(float(crop[3]), float(source[3])),
    ]
    if clipped[2] <= clipped[0] or clipped[3] <= clipped[1]:
        return None
    return [
        round((clipped[0] - crop[0]) / crop_width, 6),
        round((clipped[1] - crop[1]) / crop_height, 6),
        round((clipped[2] - crop[0]) / crop_width, 6),
        round((clipped[3] - crop[1]) / crop_height, 6),
    ]


def selected_targets(audit: dict[str, Any]) -> list[dict[str, Any]]:
    review_ids = set(audit["review"]["photoIds"])
    targets: list[dict[str, Any]] = []
    for photo in audit["details"]:
        if photo["photoId"] not in review_ids:
            continue
        trays = {int(tray["index"]): tray for tray in photo["productionBlind"]["trays"]}
        for sequence, defect in enumerate(photo["defectInstances"], start=1):
            if defect["hit"]:
                continue
            indices = [int(value) for value in defect["coveringTrayIndices"]]
            if not indices:
                raise RuntimeError(f"missed defect has no target tray: {photo['photoId']}")
            targets.append({
                "photo": photo,
                "tray": trays[indices[0]],
                "reason": "production_missed_human_defect",
                "expectedHumanVerdict": defect["label"],
                "sourceSequence": sequence,
            })
        if photo["humanTruth"]["kind"] == "unjudgeable":
            for sequence, review in enumerate(photo["humanTruth"]["reviewRows"], start=1):
                bbox = review.get("bbox")
                if not isinstance(bbox, list) or len(bbox) != 4:
                    raise RuntimeError(f"unjudgeable review row has no box: {photo['photoId']}")
                width, height = photo["imageSize"]
                truth = [bbox[0] * width, bbox[1] * height, bbox[2] * width, bbox[3] * height]
                matches = sorted(
                    ((containment(tray["box"], truth), tray) for tray in trays.values()),
                    key=lambda pair: pair[0], reverse=True,
                )
                if not matches or matches[0][0] < 0.5:
                    raise RuntimeError(f"unjudgeable row has no covering target tray: {photo['photoId']}")
                targets.append({
                    "photo": photo,
                    "tray": matches[0][1],
                    "reason": "human_unjudgeable_target_tray",
                    "expectedHumanVerdict": "UNJUDGEABLE",
                    "sourceSequence": sequence,
                })
    return targets


def build(audit_path: Path, output: Path, expected_count: int, annotator_url: str) -> dict[str, Any]:
    audit = json.loads(audit_path.read_text(encoding="utf-8"))
    if audit.get("version") != "liushanmen-recent-label-photo-audit-v1":
        raise RuntimeError("unsupported recent audit version")
    if audit.get("productionWrites") != 0 or audit.get("originalsModified") != 0:
        raise RuntimeError("recent audit violated its read-only contract")
    targets = selected_targets(audit)
    if len(targets) != expected_count:
        raise RuntimeError(f"review target count drift: {len(targets)} != {expected_count}")
    if output.exists():
        raise FileExistsError(f"refusing to overwrite review queue: {output}")
    images, prelabels = output / "images", output / "prelabels"
    images.mkdir(parents=True)
    prelabels.mkdir()
    rows: list[dict[str, Any]] = []
    for index, target in enumerate(targets, start=1):
        photo, tray = target["photo"], target["tray"]
        source = Path(photo["image"])
        if not source.is_file() or sha256_file(source) != photo["imageSha256"]:
            raise RuntimeError(f"source image drift: {source}")
        with Image.open(source) as opened:
            frame = ImageOps.exif_transpose(opened).convert("RGB")
            crop_rect = context_crop(tray["box"], frame.width, frame.height)
            derivative = frame.crop(tuple(crop_rect))
            crop_id = f"recent_{photo['photoId'][:8]}_t{int(tray['index']):02d}_{index:03d}"
            image_path = images / f"{crop_id}.jpg"
            derivative.save(image_path, format="JPEG", quality=94, subsampling=0)
        proposal_boxes: list[dict[str, Any]] = []
        for label in tray["labels"]:
            normalized = normalized_box(label["sourceBox"], crop_rect)
            if normalized is None:
                continue
            proposal_boxes.append({
                "class_id": int(label["classId"]),
                "bbox_normalized_xyxy": normalized,
                "confidence": float(label["confidence"]),
            })
        prelabel_path = prelabels / f"{crop_id}.json"
        prelabel_path.write_text(json.dumps({
            "crop_id": crop_id,
            "source": "frozen_production_v8_target_tray_proposal",
            "is_ground_truth": False,
            "boxes": proposal_boxes,
        }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        rows.append({
            "crop_id": crop_id,
            "image": str(image_path.resolve()),
            "image_sha256": sha256_file(image_path),
            "source_photo_id": photo["photoId"],
            "source_task_id": photo["taskId"],
            "source_image_sha256": photo["imageSha256"],
            "sku_code": photo["skuCode"],
            "angle": "只审核画面正中央目标托盘（周边盒仅供颜色和样式参照）",
            "label_v1_verdict": str(tray["verdict"]),
            "human_qc_verdict": target["expectedHumanVerdict"],
            "target_tray_index": int(tray["index"]),
            "target_tray_box_px": tray["box"],
            "source_crop_rect_px": crop_rect,
            "review_reason": target["reason"],
            "prior_human_verdict": target["expectedHumanVerdict"],
            "prelabel_boxes": len(proposal_boxes),
            "review_scope": "target_tray_only",
            "training_eligible": False,
        })
    manifest = {
        "version": "liushanmen-recent-target-tray-review-v1",
        "queue_id": output.name,
        "queue_count": len(rows),
        "annotator_url": annotator_url,
        "source_audit": str(audit_path.resolve()),
        "source_audit_sha256": sha256_file(audit_path),
        "preannotations_are_not_ground_truth": True,
        "every_image_requires_full_human_review": True,
        "review_scope": "only the single target tray crop; do not relabel the whole photo",
        "missing_class_review_allowed": True,
        "factory_tray_box_annotation_required": False,
        "train_only_after_human_review": True,
        "evaluation_or_production_use_allowed": False,
        "production_writes": 0,
        "originals_modified": 0,
        "rows": rows,
    }
    manifest_path = output / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audit", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--expected-count", required=True, type=int)
    parser.add_argument("--annotator-url", default="http://127.0.0.1:8798/")
    args = parser.parse_args()
    manifest = build(args.audit.resolve(), args.output.resolve(), args.expected_count, args.annotator_url)
    print(json.dumps({
        "queue": str(args.output.resolve()),
        "count": manifest["queue_count"],
        "annotatorUrl": manifest["annotator_url"],
        "productionWrites": 0,
        "originalsModified": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
