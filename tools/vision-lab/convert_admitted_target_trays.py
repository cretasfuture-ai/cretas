#!/usr/bin/env python3
"""Convert admitted target-tray boxes into a standard train-only tray queue."""
from __future__ import annotations

import argparse
import json
import os
import shutil
from pathlib import Path
from typing import Any, Sequence

import label_qc_tray_patch_dataset as patch_dataset
import tray_workflow


ADMISSION_VERSION = "liushanmen-reviewed-full-tray-admission-v1"
VERSION = "liushanmen-admitted-target-tray-training-queue-v1"


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise RuntimeError(f"expected JSON object: {path}")
    return value


def select_independent_rows(
    rows: Sequence[dict[str, Any]], prior_tasks: set[str], prior_hashes: set[str],
) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    accepted: list[dict[str, Any]] = []
    excluded: list[dict[str, str]] = []
    seen_tasks: set[str] = set()
    seen_hashes: set[str] = set()
    for row in rows:
        task_id = str(row.get("source_task_id") or "")
        image_hash = str(row.get("image_sha256") or "")
        crop_id = str(row.get("crop_id") or "")
        if not crop_id or not task_id or not image_hash:
            raise RuntimeError("admitted target row has a blank identity")
        reason = (
            "prior_dataset_task_overlap" if task_id in prior_tasks
            else "prior_dataset_image_overlap" if image_hash in prior_hashes
            else "same_batch_task_overlap" if task_id in seen_tasks
            else "same_batch_image_overlap" if image_hash in seen_hashes
            else None
        )
        if reason:
            excluded.append({"crop_id": crop_id, "task_id": task_id, "reason": reason})
            continue
        accepted.append(row)
        seen_tasks.add(task_id)
        seen_hashes.add(image_hash)
    return accepted, excluded


def build(
    admitted_queues: Sequence[Path], prior_manifest_path: Path, output: Path,
) -> dict[str, Any]:
    prior_manifest_path = prior_manifest_path.resolve()
    output = output.resolve()
    if output.exists():
        raise FileExistsError(f"refusing to overwrite tray training queue: {output}")
    prior = load_json(prior_manifest_path)
    prior_root = prior_manifest_path.parent
    if prior.get("dataset_sha256") != patch_dataset.dataset_digest(prior_root):
        raise RuntimeError("prior tray dataset content drift")
    provenance = load_json(prior_root / "provenance.json").get("rows") or []
    prior_tasks = {str(row.get("task_id") or "") for row in provenance}
    prior_hashes = {
        str(row.get("source_sha256") or row.get("packed_image_sha256") or "")
        for row in provenance
    }

    rows: list[dict[str, Any]] = []
    source_manifests = []
    for queue_value in admitted_queues:
        queue = queue_value.resolve()
        manifest_path = queue / "manifest.json"
        manifest = load_json(manifest_path)
        if (
            manifest.get("version") != ADMISSION_VERSION
            or manifest.get("human_boxes_preserved") is not True
            or manifest.get("production_writes") != 0
        ):
            raise RuntimeError(f"unsafe admitted target-tray queue: {queue}")
        source_manifests.append({
            "queue": str(queue),
            "manifest_sha256": patch_dataset.sha256_file(manifest_path),
        })
        for row in manifest.get("rows") or []:
            rows.append(dict(row) | {"admitted_queue": str(queue)})
    accepted, excluded = select_independent_rows(rows, prior_tasks, prior_hashes)
    if len(accepted) < 2:
        raise RuntimeError("fewer than two independent admitted target trays")

    temporary = output.with_name(output.name + f".tmp.{os.getpid()}")
    if temporary.exists():
        raise RuntimeError(f"stale temporary queue: {temporary}")
    (temporary / "images").mkdir(parents=True)
    (temporary / "annotations-human").mkdir()
    output_rows = []
    for index, row in enumerate(accepted, start=1):
        queue = Path(str(row["admitted_queue"]))
        crop_id = str(row["crop_id"])
        image = Path(str(row["image"])).resolve()
        annotation_path = queue / "annotations-human" / f"{crop_id}.json"
        if not image.is_file() or patch_dataset.sha256_file(image) != str(row["image_sha256"]):
            raise RuntimeError(f"admitted target image drift: {crop_id}")
        annotation = load_json(annotation_path)
        target = annotation.get("target_tray_box")
        if (
            annotation.get("reviewed") is not True
            or annotation.get("source") != "human"
            or annotation.get("training_eligible") is not True
            or annotation.get("target_tray_box_source") != "human"
            or not isinstance(target, list)
        ):
            raise RuntimeError(f"admitted target is not eligible human tray truth: {crop_id}")
        target = patch_dataset.validate_box(target)
        stem = f"traypending_{str(row['source_photo_id'])[:8]}_{index:03d}"
        packed = temporary / "images" / f"{stem}.jpg"
        shutil.copy2(image, packed)
        tray_annotation = {
            "photo_id": stem,
            "format": "normalised_xyxy",
            "reviewed": True,
            "source": "human",
            "preannotations_are_not_ground_truth": True,
            "training_eligible": True,
            "training_exclusion_reason": None,
            "boxes": [target],
            "source_label_review_annotation": str(annotation_path.resolve()),
            "source_label_review_annotation_sha256": patch_dataset.sha256_file(annotation_path),
        }
        (temporary / "annotations-human" / f"{stem}.json").write_text(
            json.dumps(tray_annotation, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
        )
        guide = row.get("target_tray_guide_normalized")
        preannotations = []
        if isinstance(guide, list):
            preannotations.append({"box": patch_dataset.validate_box(guide), "source": "prior_target_detection"})
        output_rows.append({
            "queue_index": index,
            "packed_stem": stem,
            "packed_image": str(Path("images") / packed.name),
            "photo_id": str(row["source_photo_id"]),
            "source_photo_id": str(row["source_photo_id"]),
            "task_id": str(row["source_task_id"]),
            "sku_code": str(row.get("sku_code") or ""),
            "angle": "recovered_incomplete_target_tray",
            "source_path": str(image),
            "source_sha256": str(row["image_sha256"]),
            "source_perceptual_hash": tray_workflow.phash(image),
            "packed_image_sha256": patch_dataset.sha256_file(packed),
            "selection_tags": ["new_upload", "incomplete_recovered", "human_reboxed"],
            "preannotations": preannotations,
            "preannotations_are_not_ground_truth": True,
            "train_only": True,
            "protected_target": False,
        })

    manifest = {
        "version": VERSION,
        "queue_id": output.name,
        "queue_count": len(output_rows),
        "source_admission_manifests": source_manifests,
        "prior_dataset": str(prior_root),
        "prior_dataset_sha256": str(prior["dataset_sha256"]),
        "excluded_count": len(excluded),
        "excluded": excluded,
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
        "rows": output_rows,
    }
    (temporary / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    temporary.replace(output)
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--admitted-queue", action="append", required=True, type=Path)
    parser.add_argument("--prior-dataset-manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    result = build(args.admitted_queue, args.prior_dataset_manifest, args.output)
    print(json.dumps({
        "queue": str(args.output.resolve()),
        "admitted": result["queue_count"],
        "excluded": result["excluded"],
        "production_writes": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
