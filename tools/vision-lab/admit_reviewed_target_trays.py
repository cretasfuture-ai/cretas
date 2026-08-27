#!/usr/bin/env python3
"""Validate a completed target-tray review and derive an immutable training queue."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import shutil
from pathlib import Path
from typing import Any


CLASSES = {0: "white_label", 1: "color_label"}
MISSING_VERDICTS = {
    "MISSING_WHITE_LABEL": ["white_label"],
    "MISSING_COLOR_LABEL": ["color_label"],
    "BOTH_MISSING": ["white_label", "color_label"],
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def validate_boxes(annotation: dict[str, Any]) -> None:
    boxes = annotation.get("boxes")
    if not isinstance(boxes, list):
        raise RuntimeError("human annotation boxes must be a list")
    for box in boxes:
        if not isinstance(box, dict) or box.get("c") not in CLASSES:
            raise RuntimeError("human annotation contains an unsupported class")
        coordinates = box.get("b")
        if not isinstance(coordinates, list) or len(coordinates) != 4:
            raise RuntimeError("human annotation box must be normalized xyxy")
        if not all(isinstance(value, (int, float)) and 0 <= value <= 1 for value in coordinates):
            raise RuntimeError("human annotation box is outside [0, 1]")
        if coordinates[2] <= coordinates[0] or coordinates[3] <= coordinates[1]:
            raise RuntimeError("human annotation box has no area")


def validate_review(source: dict[str, Any], annotation: dict[str, Any]) -> str | None:
    crop_id = str(source.get("crop_id") or "")
    if annotation.get("crop_id") != crop_id:
        raise RuntimeError(f"annotation crop id drift: {crop_id}")
    if annotation.get("source") != "human" or annotation.get("reviewed") is not True:
        raise RuntimeError(f"crop is not human-reviewed: {crop_id}")
    validate_boxes(annotation)
    if annotation.get("unjudgeable") is True:
        return "human_unjudgeable"
    prior = str(source.get("prior_human_verdict") or "")
    if prior not in MISSING_VERDICTS:
        return "not_a_confirmed_missing_label_hard_example"
    if annotation.get("missing_confirmed_by_human") is not True:
        return "missing_label_not_confirmed"
    declared = sorted(str(value) for value in annotation.get("declared_missing_classes") or [])
    if declared != sorted(MISSING_VERDICTS[prior]):
        raise RuntimeError(f"declared missing class conflicts with source truth: {crop_id}")
    present_classes = {int(box["c"]) for box in annotation["boxes"]}
    missing_ids = {class_id for class_id, name in CLASSES.items() if name in declared}
    if present_classes & missing_ids:
        raise RuntimeError(f"declared missing class also has a human box: {crop_id}")
    if not present_classes:
        raise RuntimeError(f"confirmed missing crop lacks complementary label evidence: {crop_id}")
    return None


def admit(source_queue: Path, output_queue: Path, receipt_path: Path, expected_reviews: int) -> dict[str, Any]:
    source_queue = source_queue.resolve()
    output_queue = output_queue.resolve()
    receipt_path = receipt_path.resolve()
    if output_queue.exists() or receipt_path.exists():
        raise FileExistsError("refusing to overwrite an admission artifact")
    manifest_path = source_queue / "manifest.json"
    manifest = load_json(manifest_path)
    rows = manifest.get("rows") if isinstance(manifest, dict) else None
    if not isinstance(rows, list) or len(rows) != expected_reviews:
        raise RuntimeError(f"review inventory drift: {len(rows) if isinstance(rows, list) else 0} != {expected_reviews}")

    admitted: list[tuple[dict[str, Any], dict[str, Any], Path, Path]] = []
    exclusions: list[dict[str, str]] = []
    source_hashes: dict[str, dict[str, str]] = {}
    for source in rows:
        crop_id = str(source.get("crop_id") or "")
        image = Path(str(source.get("image") or "")).resolve()
        annotation_path = source_queue / "annotations-human" / f"{crop_id}.json"
        if not image.is_file() or sha256_file(image) != source.get("image_sha256"):
            raise RuntimeError(f"review image drift: {crop_id}")
        if not annotation_path.is_file():
            raise RuntimeError(f"missing human annotation: {crop_id}")
        annotation = load_json(annotation_path)
        reason = validate_review(source, annotation)
        source_hashes[crop_id] = {
            "image_sha256": sha256_file(image),
            "annotation_sha256": sha256_file(annotation_path),
        }
        if reason:
            exclusions.append({"crop_id": crop_id, "reason": reason})
        else:
            admitted.append((source, annotation, image, annotation_path))
    if not admitted:
        raise RuntimeError("no reviewed hard examples qualified for training")

    images = output_queue / "images"
    annotations = output_queue / "annotations-human"
    images.mkdir(parents=True)
    annotations.mkdir()
    derived_rows: list[dict[str, Any]] = []
    for source, annotation, image, _annotation_path in admitted:
        image_out = images / image.name
        annotation_out = annotations / f"{source['crop_id']}.json"
        shutil.copy2(image, image_out)
        annotation_out.write_text(json.dumps(annotation | {
            "training_eligible": True,
            "training_admission": "human_confirmed_recent_missing_label_hard_example",
        }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        derived_rows.append(source | {
            "image": str(image_out),
            "image_sha256": sha256_file(image_out),
            "training_eligible": True,
            "training_admission": "human_confirmed_recent_missing_label_hard_example",
        })

    derived_manifest = {
        "version": "liushanmen-reviewed-target-training-admission-v1",
        "queue_id": output_queue.name,
        "source_queue": str(source_queue),
        "source_manifest_sha256": sha256_file(manifest_path),
        "human_review_complete": True,
        "evaluation_or_production_use_allowed": False,
        "protected_holdout_included": False,
        "production_writes": 0,
        "originals_modified": 0,
        "rows": derived_rows,
    }
    derived_manifest_path = output_queue / "manifest.json"
    derived_manifest_path.write_text(
        json.dumps(derived_manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    for crop_id, hashes in source_hashes.items():
        source = next(row for row in rows if row["crop_id"] == crop_id)
        if sha256_file(Path(source["image"])) != hashes["image_sha256"]:
            raise RuntimeError(f"source image changed during admission: {crop_id}")
        if sha256_file(source_queue / "annotations-human" / f"{crop_id}.json") != hashes["annotation_sha256"]:
            raise RuntimeError(f"source annotation changed during admission: {crop_id}")
    receipt = {
        "version": "liushanmen-reviewed-target-training-admission-receipt-v1",
        "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "source_queue": str(source_queue),
        "source_manifest_sha256": sha256_file(manifest_path),
        "reviewed": len(rows),
        "admitted": len(admitted),
        "excluded": exclusions,
        "derived_queue": str(output_queue),
        "derived_manifest_sha256": sha256_file(derived_manifest_path),
        "source_artifact_sha256": source_hashes,
        "production_writes": 0,
        "originals_modified": 0,
    }
    receipt_path.parent.mkdir(parents=True, exist_ok=True)
    receipt_path.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-queue", required=True, type=Path)
    parser.add_argument("--output-queue", required=True, type=Path)
    parser.add_argument("--receipt", required=True, type=Path)
    parser.add_argument("--expected-reviews", required=True, type=int)
    args = parser.parse_args()
    receipt = admit(args.source_queue, args.output_queue, args.receipt, args.expected_reviews)
    print(json.dumps(receipt, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
