#!/usr/bin/env python3
"""Create an immutable training queue from reviewed, complete target trays."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path
from typing import Any, Sequence


VERSION = "liushanmen-reviewed-full-tray-admission-v1"
SUPPORTED_SOURCE_VERSION = "liushanmen-incomplete-tray-requeue-v3"
MINIMAL_SOURCE_VERSION = "liushanmen-minimal-active-label-review-v1"
SUPPORTED_SOURCE_VERSIONS = {SUPPORTED_SOURCE_VERSION, MINIMAL_SOURCE_VERSION}
DEFAULT_EDGE_MARGIN = 0.005


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


def valid_box(box: Sequence[float]) -> bool:
    if len(box) != 4:
        return False
    values = [float(value) for value in box]
    return all(0 <= value <= 1 for value in values) and (
        values[0] < values[2] and values[1] < values[3]
    )


def touches_crop_edge(box: Sequence[float], margin: float) -> bool:
    if not valid_box(box):
        raise RuntimeError("invalid human target tray box")
    values = [float(value) for value in box]
    return (
        values[0] <= margin or values[1] <= margin
        or values[2] >= 1 - margin or values[3] >= 1 - margin
    )


def validate_label_boxes(boxes: Any) -> list[dict[str, Any]]:
    if not isinstance(boxes, list):
        raise RuntimeError("human label boxes must be a list")
    validated = []
    for box in boxes:
        if not isinstance(box, dict) or int(box.get("c", -1)) not in (0, 1):
            raise RuntimeError("human label box has an invalid class")
        normalized = box.get("b")
        if not isinstance(normalized, list) or not valid_box(normalized):
            raise RuntimeError("human label box has invalid geometry")
        validated.append(box)
    return validated


def admit(source_queue: Path, output: Path, edge_margin: float = DEFAULT_EDGE_MARGIN) -> dict[str, Any]:
    if output.exists():
        raise FileExistsError(f"refusing to overwrite admission queue: {output}")
    if not 0 <= edge_margin < 0.5:
        raise ValueError("edge margin must be between zero and one half")

    manifest_path = source_queue / "manifest.json"
    manifest = load_json(manifest_path)
    source_version = str(manifest.get("version") or "")
    if source_version not in SUPPORTED_SOURCE_VERSIONS:
        raise RuntimeError("source queue does not implement a supported full-tray review contract")
    if source_version == MINIMAL_SOURCE_VERSION:
        selection = manifest.get("selection") or {}
        if (
            manifest.get("preannotations_are_not_ground_truth") is not True
            or manifest.get("evaluation_or_production_use_allowed") is not False
            or manifest.get("review_scope") != "single_complete_target_tray_labels_only"
            or float(selection.get("source_edge_margin_ratio", -1)) != DEFAULT_EDGE_MARGIN
        ):
            raise RuntimeError("minimal source queue full-tray contract is incomplete")

    planned: list[tuple[dict[str, Any], Path, dict[str, Any], str]] = []
    excluded: dict[str, int] = {}
    seen_crop_ids: set[str] = set()
    for row in manifest.get("rows") or []:
        crop_id = str(row.get("crop_id") or "")
        if not crop_id or crop_id in seen_crop_ids:
            raise RuntimeError(f"invalid or duplicate crop id: {crop_id}")
        seen_crop_ids.add(crop_id)
        annotation_path = source_queue / "annotations-human" / f"{crop_id}.json"
        if not annotation_path.is_file():
            raise RuntimeError(f"missing human annotation: {crop_id}")
        annotation = load_json(annotation_path)
        if annotation.get("reviewed") is not True:
            raise RuntimeError(f"source review is incomplete: {crop_id}")
        if annotation.get("unjudgeable") is True:
            reason = "unjudgeable"
        elif annotation.get("incomplete_tray") is True:
            reason = str(annotation.get("training_exclusion_reason") or "incomplete_tray")
        elif annotation.get("training_eligible") is not True:
            reason = str(annotation.get("training_exclusion_reason") or "human_ineligible")
        else:
            target = annotation.get("target_tray_box")
            if not isinstance(target, list) or touches_crop_edge(target, edge_margin):
                reason = "target_tray_box_touches_crop_edge"
            else:
                validate_label_boxes(annotation.get("boxes"))
                image = Path(str(row.get("image") or "")).resolve()
                expected_sha = str(row.get("image_sha256") or "")
                if not image.is_file() or sha256_file(image) != expected_sha:
                    raise RuntimeError(f"review image drift: {crop_id}")
                planned.append((row, annotation_path, annotation, sha256_file(annotation_path)))
                continue
        excluded[reason] = excluded.get(reason, 0) + 1

    if not planned:
        raise RuntimeError("source queue has no reviewed complete trays eligible for training")

    (output / "images").mkdir(parents=True)
    (output / "annotations-human").mkdir()
    output_rows = []
    for row, annotation_path, annotation, annotation_sha in planned:
        crop_id = str(row["crop_id"])
        source_image = Path(str(row["image"])).resolve()
        admitted_image = output / "images" / f"{crop_id}{source_image.suffix.lower()}"
        shutil.copy2(source_image, admitted_image)
        admitted_annotation = dict(annotation)
        admitted_annotation["admission"] = {
            "version": VERSION,
            "source_queue": str(source_queue.resolve()),
            "source_annotation_sha256": annotation_sha,
            "human_boxes_preserved": True,
            "edge_margin": edge_margin,
        }
        admitted_annotation_path = output / "annotations-human" / f"{crop_id}.json"
        admitted_annotation_path.write_text(
            json.dumps(admitted_annotation, ensure_ascii=False, indent=1) + "\n",
            encoding="utf-8",
        )
        admitted_row = dict(row)
        admitted_row.update({
            "image": str(admitted_image.resolve()),
            "image_sha256": sha256_file(admitted_image),
            "training_eligible": True,
            "training_exclusion_reason": None,
            "admitted_from_queue": str(source_queue.resolve()),
            "source_annotation_sha256": annotation_sha,
        })
        output_rows.append(admitted_row)

    receipt = {
        "version": VERSION,
        "queue_id": output.name,
        "created_from_reviewed_queue": str(source_queue.resolve()),
        "source_queue_manifest_sha256": sha256_file(manifest_path),
        "edge_margin": edge_margin,
        "admitted_count": len(output_rows),
        "excluded_count": sum(excluded.values()),
        "exclusion_reasons": excluded,
        "human_annotations_modified": 0,
        "human_boxes_preserved": True,
        "production_writes": 0,
        "evaluation_or_production_use_allowed": False,
        "rows": output_rows,
    }
    (output / "manifest.json").write_text(
        json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-queue", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--edge-margin", type=float, default=DEFAULT_EDGE_MARGIN)
    args = parser.parse_args()
    result = admit(args.source_queue.resolve(), args.output.resolve(), args.edge_margin)
    print(json.dumps({
        "queue": str(args.output.resolve()),
        "admitted_count": result["admitted_count"],
        "excluded_count": result["excluded_count"],
        "exclusion_reasons": result["exclusion_reasons"],
        "human_annotations_modified": 0,
        "production_writes": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
