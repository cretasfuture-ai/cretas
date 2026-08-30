#!/usr/bin/env python3
"""Seal one focused tray-review round for immutable cumulative training."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path
from typing import Any

import label_qc_tray_patch_dataset as patch_dataset


VERSION = "liushanmen-focused-tray-review-admission-v1"
ROUND_VERSION = "vision-lab-tray-review-round-v1"


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise RuntimeError(f"expected JSON object: {path}")
    return value


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_boxes(boxes: Any) -> list[list[float]]:
    if not isinstance(boxes, list) or not boxes:
        raise RuntimeError("reviewed tray annotation must contain boxes")
    validated: list[list[float]] = []
    for raw in boxes:
        if not isinstance(raw, list) or len(raw) != 4:
            raise RuntimeError("tray box must be normalized xyxy")
        values = [float(value) for value in raw]
        if not (
            all(0 <= value <= 1 for value in values)
            and values[0] < values[2] and values[1] < values[3]
        ):
            raise RuntimeError("tray box is outside normalized image bounds")
        validated.append(values)
    return validated


def admit(
    source_queue: Path, round_path: Path, prior_manifest_path: Path, output: Path,
) -> dict[str, Any]:
    source_queue = source_queue.resolve()
    round_path = round_path.resolve()
    prior_manifest_path = prior_manifest_path.resolve()
    output = output.resolve()
    if output.exists():
        raise FileExistsError(f"refusing to overwrite focused admission queue: {output}")

    source_manifest_path = source_queue / "manifest.json"
    source_manifest = load_json(source_manifest_path)
    source_rows = source_manifest.get("rows") or []
    if (
        source_manifest.get("protected_holdout_included") is not False
        or source_manifest.get("preannotations_are_not_ground_truth") is not True
        or len(source_rows) != int(source_manifest.get("queue_count", -1))
    ):
        raise RuntimeError("source tray queue protection/count contract is unsafe")

    round_data = load_json(round_path)
    selected_ids = round_data.get("selected_ids")
    if (
        round_data.get("version") != ROUND_VERSION
        or not isinstance(selected_ids, list) or not selected_ids
        or len(selected_ids) != len(set(selected_ids))
        or int(round_data.get("selected_count", -1)) != len(selected_ids)
        or int(round_data.get("queue_count", -1)) != len(source_rows)
    ):
        raise RuntimeError("focused review round contract is unsafe")

    prior = load_json(prior_manifest_path)
    prior_root = prior_manifest_path.parent
    if (
        prior.get("protected_holdout_included") is not False
        or prior.get("dataset_sha256") != patch_dataset.dataset_digest(prior_root)
    ):
        raise RuntimeError("prior tray dataset protection/content contract is unsafe")
    provenance = load_json(prior_root / "provenance.json").get("rows") or []
    existing_tasks = {str(row.get("task_id")) for row in provenance if row.get("task_id")}
    existing_hashes = {
        str(row.get("source_sha256")) for row in provenance if row.get("source_sha256")
    }

    by_id = {str(row.get("packed_stem") or ""): row for row in source_rows}
    if any(not item_id or item_id not in by_id for item_id in selected_ids):
        raise RuntimeError("focused review round contains an unknown tray image")

    accepted: list[tuple[dict[str, Any], Path, Path]] = []
    excluded: list[dict[str, str]] = []
    reviewed_box_count = 0
    for item_id in selected_ids:
        row = by_id[item_id]
        packed = source_queue / str(row["packed_image"])
        annotation_path = source_queue / "annotations-human" / f"{item_id}.json"
        source = Path(str(row["source_path"])).resolve()
        if not packed.is_file() or sha256(packed) != str(row.get("packed_image_sha256") or ""):
            raise RuntimeError(f"packed tray image drift: {item_id}")
        if not source.is_file() or sha256(source) != str(row.get("source_sha256") or ""):
            raise RuntimeError(f"source tray image drift: {item_id}")
        if not annotation_path.is_file():
            raise RuntimeError(f"missing focused human annotation: {item_id}")
        annotation = load_json(annotation_path)
        if (
            annotation.get("photo_id") != item_id
            or annotation.get("reviewed") is not True
            or annotation.get("source") != "human"
            or annotation.get("training_eligible") is not True
            or annotation.get("format") != "normalised_xyxy"
        ):
            raise RuntimeError(f"focused tray annotation is not eligible human truth: {item_id}")
        reviewed_box_count += len(validate_boxes(annotation.get("boxes")))
        task_id, source_sha = str(row.get("task_id") or ""), str(row.get("source_sha256") or "")
        reason = (
            "prior_dataset_task_overlap" if task_id in existing_tasks
            else "prior_dataset_image_overlap" if source_sha in existing_hashes
            else None
        )
        if reason:
            excluded.append({"packed_stem": item_id, "task_id": task_id, "reason": reason})
        else:
            accepted.append((row, packed, annotation_path))

    if len(accepted) < 2:
        raise RuntimeError("focused review round has fewer than two independent training images")
    (output / "images").mkdir(parents=True)
    (output / "annotations-human").mkdir()
    admitted_rows: list[dict[str, Any]] = []
    admitted_boxes = 0
    for row, packed, annotation_path in accepted:
        item_id = str(row["packed_stem"])
        image_out = output / "images" / packed.name
        annotation_out = output / "annotations-human" / annotation_path.name
        shutil.copy2(packed, image_out)
        shutil.copy2(annotation_path, annotation_out)
        annotation = load_json(annotation_out)
        admitted_boxes += len(annotation["boxes"])
        admitted = dict(row)
        admitted.update({
            "packed_image": str(Path("images") / image_out.name),
            "packed_image_sha256": sha256(image_out),
            "train_only": True,
            "protected_target": False,
            "admitted_from_review_round": str(round_path),
            "source_annotation_sha256": sha256(annotation_out),
        })
        admitted_rows.append(admitted)

    manifest = {
        "version": VERSION,
        "queue_id": output.name,
        "queue_count": len(admitted_rows),
        "source_reviewed_count": len(selected_ids),
        "source_reviewed_boxes": reviewed_box_count,
        "admitted_count": len(admitted_rows),
        "admitted_boxes": admitted_boxes,
        "excluded_count": len(excluded),
        "excluded": excluded,
        "source_queue": str(source_queue),
        "source_manifest_sha256": sha256(source_manifest_path),
        "review_round": str(round_path),
        "review_round_sha256": sha256(round_path),
        "prior_dataset": str(prior_root),
        "prior_dataset_sha256": str(prior["dataset_sha256"]),
        "human_review_complete": True,
        "all_rows_train_only": True,
        "protected_holdout_included": False,
        "preannotations_are_not_ground_truth": True,
        "annotation_policy": {
            "occluded_lower_trays": "ignore_not_negative",
            "visible_lower_labels": "separate_label_side_view_truth",
        },
        "human_annotations_modified": 0,
        "originals_modified": False,
        "production_writes": 0,
        "rows": admitted_rows,
    }
    (output / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-queue", required=True, type=Path)
    parser.add_argument("--review-round", required=True, type=Path)
    parser.add_argument("--prior-dataset-manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    result = admit(
        args.source_queue, args.review_round, args.prior_dataset_manifest, args.output,
    )
    print(json.dumps({
        "queue": str(args.output.resolve()),
        "reviewed": result["source_reviewed_count"],
        "admitted": result["admitted_count"],
        "excluded": result["excluded"],
        "boxes": result["admitted_boxes"],
        "production_writes": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
