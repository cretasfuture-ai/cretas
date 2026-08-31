#!/usr/bin/env python3
"""Admit reviewed sibling target trays with a task-disjoint validation split."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
from pathlib import Path
from typing import Any, Sequence

import label_qc_tray_patch_dataset as patch_dataset
import tray_workflow


SOURCE_VERSION = "liushanmen-task-sibling-tray-recovery-v1"
TRAIN_VERSION = "liushanmen-sibling-target-tray-training-queue-v1"
HOLDOUT_VERSION = "liushanmen-sibling-target-tray-independent-holdout-v1"


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise RuntimeError(f"expected JSON object: {path}")
    return value


def deterministic_split(
    rows: Sequence[dict[str, Any]], holdout_count: int,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    if holdout_count < 1 or len(rows) - holdout_count < 2:
        raise RuntimeError("split requires at least two train tasks and one holdout task")
    task_ids = [str(row.get("source_task_id") or "") for row in rows]
    if "" in task_ids or len(set(task_ids)) != len(task_ids):
        raise RuntimeError("sibling recovery rows must have unique nonblank task ids")
    ranked = sorted(
        rows,
        key=lambda row: hashlib.sha256(str(row["source_task_id"]).encode("utf-8")).hexdigest(),
    )
    holdout_tasks = {str(row["source_task_id"]) for row in ranked[:holdout_count]}
    train = [row for row in rows if str(row["source_task_id"]) not in holdout_tasks]
    holdout = [row for row in rows if str(row["source_task_id"]) in holdout_tasks]
    return train, holdout


def exclude_prior_overlap(
    rows: Sequence[dict[str, Any]], prior_manifest_path: Path,
) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    prior_manifest_path = prior_manifest_path.resolve()
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
    accepted: list[dict[str, Any]] = []
    excluded: list[dict[str, str]] = []
    for row in rows:
        task_id = str(row["source_task_id"])
        image_hash = str(row["image_sha256"])
        reason = (
            "prior_dataset_task_overlap" if task_id in prior_tasks
            else "prior_dataset_image_overlap" if image_hash in prior_hashes
            else None
        )
        if reason:
            excluded.append({"crop_id": str(row["crop_id"]), "task_id": task_id, "reason": reason})
        else:
            accepted.append(row)
    return accepted, excluded


def validate_sibling_rows(queue: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    manifest_path = queue / "manifest.json"
    manifest = load_json(manifest_path)
    rows = manifest.get("rows") or []
    if (
        manifest.get("version") != SOURCE_VERSION
        or manifest.get("one_replacement_photo_per_source_truncated_task") is not True
        or manifest.get("every_image_requires_full_human_review") is not True
        or manifest.get("evaluation_or_production_use_allowed") is not False
        or manifest.get("production_writes") != 0
        or len(rows) != int(manifest.get("queue_count", -1))
    ):
        raise RuntimeError("unsafe sibling recovery manifest")
    reviewed: list[dict[str, Any]] = []
    for row in rows:
        crop_id = str(row.get("crop_id") or "")
        image = Path(str(row.get("image") or "")).resolve()
        annotation_path = queue / "annotations-human" / f"{crop_id}.json"
        if (
            not image.is_file()
            or patch_dataset.sha256_file(image) != str(row.get("image_sha256") or "")
            or not annotation_path.is_file()
        ):
            raise RuntimeError(f"sibling recovery image/annotation drift: {crop_id}")
        annotation = load_json(annotation_path)
        target = annotation.get("target_tray_box")
        if (
            annotation.get("reviewed") is not True
            or annotation.get("source") != "human"
            or annotation.get("training_eligible") is not True
            or annotation.get("unjudgeable") is not False
            or annotation.get("incomplete_tray") is not False
            or annotation.get("target_tray_box_source") != "human"
            or not isinstance(target, list)
        ):
            raise RuntimeError(f"sibling target is not complete human tray truth: {crop_id}")
        target = patch_dataset.validate_box(target)
        if target[0] <= 0 or target[1] <= 0 or target[2] >= 1 or target[3] >= 1:
            raise RuntimeError(f"sibling target still touches the crop edge: {crop_id}")
        reviewed.append(dict(row) | {
            "queue": str(queue),
            "target_tray_box": target,
            "annotation_path": str(annotation_path.resolve()),
            "annotation_sha256": patch_dataset.sha256_file(annotation_path),
        })
    return manifest, reviewed


def copy_standard_row(
    source_queue: Path, row: dict[str, Any], temporary: Path, index: int,
) -> dict[str, Any]:
    old_stem = str(row["packed_stem"])
    stem = f"trayadmitted_{index:03d}_{old_stem}"
    image = source_queue / str(row["packed_image"])
    annotation = source_queue / "annotations-human" / f"{old_stem}.json"
    if (
        not image.is_file()
        or patch_dataset.sha256_file(image) != str(row["packed_image_sha256"])
        or not annotation.is_file()
    ):
        raise RuntimeError(f"existing admitted tray drift: {old_stem}")
    annotation_value = load_json(annotation)
    if (
        annotation_value.get("reviewed") is not True
        or annotation_value.get("source") != "human"
        or annotation_value.get("training_eligible") is not True
        or not annotation_value.get("boxes")
    ):
        raise RuntimeError(f"existing admitted tray is not eligible: {old_stem}")
    packed = temporary / "images" / f"{stem}.jpg"
    shutil.copy2(image, packed)
    annotation_value["photo_id"] = stem
    (temporary / "annotations-human" / f"{stem}.json").write_text(
        json.dumps(annotation_value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    return dict(row) | {
        "queue_index": index,
        "packed_stem": stem,
        "packed_image": str(Path("images") / packed.name),
        "source_path": str(packed.resolve()),
        "source_perceptual_hash": tray_workflow.phash(packed),
        "packed_image_sha256": patch_dataset.sha256_file(packed),
    }


def copy_sibling_row(row: dict[str, Any], temporary: Path, index: int) -> dict[str, Any]:
    image = Path(str(row["image"])).resolve()
    stem = f"traysibling_{str(row['source_photo_id'])[:8]}_{index:03d}"
    packed = temporary / "images" / f"{stem}.jpg"
    shutil.copy2(image, packed)
    annotation = {
        "photo_id": stem,
        "format": "normalised_xyxy",
        "reviewed": True,
        "source": "human",
        "preannotations_are_not_ground_truth": True,
        "training_eligible": True,
        "training_exclusion_reason": None,
        "boxes": [row["target_tray_box"]],
        "source_label_review_annotation": row["annotation_path"],
        "source_label_review_annotation_sha256": row["annotation_sha256"],
    }
    (temporary / "annotations-human" / f"{stem}.json").write_text(
        json.dumps(annotation, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    return {
        "queue_index": index,
        "packed_stem": stem,
        "packed_image": str(Path("images") / packed.name),
        "photo_id": str(row["source_photo_id"]),
        "source_photo_id": str(row["source_photo_id"]),
        "task_id": str(row["source_task_id"]),
        "sku_code": str(row.get("sku_code") or ""),
        "angle": "source_truncated_task_sibling_complete_tray",
        "source_path": str(packed.resolve()),
        "source_sha256": patch_dataset.sha256_file(packed),
        "source_perceptual_hash": tray_workflow.phash(packed),
        "packed_image_sha256": patch_dataset.sha256_file(packed),
        "selection_tags": ["task_sibling", "source_truncation_recovery", "human_reboxed"],
        "preannotations": [{
            "box": patch_dataset.validate_box(row["target_tray_guide_normalized"]),
            "source": "sibling_detector_read_only_not_ground_truth",
        }],
        "preannotations_are_not_ground_truth": True,
        "train_only": True,
        "protected_target": False,
    }


def write_queue(
    output: Path, rows: Sequence[dict[str, Any]], existing_queues: Sequence[Path],
    version: str, split_role: str, source_manifest: Path,
) -> dict[str, Any]:
    output = output.resolve()
    if output.exists():
        raise FileExistsError(f"refusing to overwrite admitted sibling queue: {output}")
    temporary = output.with_name(output.name + f".tmp.{os.getpid()}")
    (temporary / "images").mkdir(parents=True)
    (temporary / "annotations-human").mkdir()
    output_rows: list[dict[str, Any]] = []
    if split_role == "train":
        for queue in existing_queues:
            manifest = load_json(queue / "manifest.json")
            if (
                manifest.get("all_rows_train_only") is not True
                or manifest.get("protected_holdout_included") is not False
                or manifest.get("production_writes") != 0
            ):
                raise RuntimeError(f"unsafe existing training queue: {queue}")
            for row in manifest.get("rows") or []:
                output_rows.append(copy_standard_row(queue, row, temporary, len(output_rows) + 1))
    for row in rows:
        output_rows.append(copy_sibling_row(row, temporary, len(output_rows) + 1))
    for row in output_rows:
        row["source_path"] = str((output / str(row["packed_image"])).resolve())
    tasks = [str(row["task_id"]) for row in output_rows]
    hashes = [str(row["source_sha256"]) for row in output_rows]
    if len(set(tasks)) != len(tasks) or len(set(hashes)) != len(hashes):
        raise RuntimeError(f"{split_role} queue contains task or image overlap")
    manifest = {
        "version": version,
        "queue_id": output.name,
        "queue_count": len(output_rows),
        "split_role": split_role,
        "source_sibling_manifest": str(source_manifest.resolve()),
        "source_sibling_manifest_sha256": patch_dataset.sha256_file(source_manifest),
        "all_rows_train_only": split_role == "train",
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


def build(
    sibling_queue: Path, existing_train_queues: Sequence[Path], prior_manifest_path: Path,
    holdout_count: int,
    train_output: Path, holdout_output: Path,
) -> dict[str, Any]:
    sibling_queue = sibling_queue.resolve()
    source_manifest = sibling_queue / "manifest.json"
    _, reviewed = validate_sibling_rows(sibling_queue)
    independent, excluded = exclude_prior_overlap(reviewed, prior_manifest_path)
    train_rows, holdout_rows = deterministic_split(independent, holdout_count)
    train_manifest = write_queue(
        train_output, train_rows, [path.resolve() for path in existing_train_queues],
        TRAIN_VERSION, "train", source_manifest,
    )
    holdout_manifest = write_queue(
        holdout_output, holdout_rows, [], HOLDOUT_VERSION, "independent_validation", source_manifest,
    )
    train_tasks = {str(row["task_id"]) for row in train_manifest["rows"]}
    holdout_tasks = {str(row["task_id"]) for row in holdout_manifest["rows"]}
    if train_tasks & holdout_tasks:
        raise RuntimeError("task leakage between sibling train and holdout queues")
    round_path = holdout_output.resolve() / "review-round.json"
    round_path.write_text(json.dumps({
        "version": "vision-lab-tray-review-round-v1",
        "round_id": "sibling-independent-holdout",
        "requested_count": len(holdout_manifest["rows"]),
        "queue_count": len(holdout_manifest["rows"]),
        "selected_count": len(holdout_manifest["rows"]),
        "selected_ids": [str(row["packed_stem"]) for row in holdout_manifest["rows"]],
        "paused_count": 0,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {
        "train_count": len(train_manifest["rows"]),
        "new_sibling_train_count": len(train_rows),
        "holdout_count": len(holdout_rows),
        "excluded_prior_overlap": excluded,
        "train_tasks": sorted(train_tasks),
        "holdout_tasks": sorted(holdout_tasks),
        "production_writes": 0,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sibling-queue", required=True, type=Path)
    parser.add_argument("--existing-train-queue", action="append", default=[], type=Path)
    parser.add_argument("--prior-dataset-manifest", required=True, type=Path)
    parser.add_argument("--holdout-count", type=int, default=3)
    parser.add_argument("--train-output", required=True, type=Path)
    parser.add_argument("--holdout-output", required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(build(
        args.sibling_queue, args.existing_train_queue, args.prior_dataset_manifest,
        args.holdout_count,
        args.train_output, args.holdout_output,
    ), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
