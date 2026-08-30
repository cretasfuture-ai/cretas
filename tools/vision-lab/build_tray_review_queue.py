#!/usr/bin/env python3
"""Pack selected historical tray images into a manifest-bound review queue."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_selection(raw: str) -> dict:
    stem, minimum, mean, below = raw.split(",", 3)
    return {
        "stem": stem,
        "min_best_iou": float(minimum),
        "mean_best_iou": float(mean),
        "ground_truth_boxes_below_iou_050": int(below),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dataset", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--selection", action="append", required=True, type=parse_selection)
    parser.add_argument("--detector", required=True, type=Path)
    parser.add_argument("--detector-sha256", required=True)
    args = parser.parse_args()

    source = args.source_dataset.resolve()
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError(f"refusing to overwrite queue: {output}")
    provenance_path = source / "provenance.json"
    provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
    by_stem = {row["stem"]: row for row in provenance["rows"] if row.get("split") == "train"}
    if len({item["stem"] for item in args.selection}) != len(args.selection):
        raise RuntimeError("selection stems must be unique")

    (output / "images").mkdir(parents=True)
    (output / "labels").mkdir()
    rows = []
    task_ids = set()
    for index, selection in enumerate(args.selection, 1):
        stem = selection["stem"]
        source_row = by_stem.get(stem)
        if source_row is None:
            raise RuntimeError(f"stem is not a historical train row: {stem}")
        if source_row["task_id"] in task_ids:
            raise RuntimeError(f"duplicate independent task: {source_row['task_id']}")
        task_ids.add(source_row["task_id"])
        image_source = source / "images" / "train" / f"{stem}.jpg"
        label_source = source / "labels" / "train" / f"{stem}.txt"
        if not image_source.is_file() or not label_source.is_file():
            raise FileNotFoundError(stem)
        image_dest = output / "images" / image_source.name
        label_dest = output / "labels" / label_source.name
        shutil.copy2(image_source, image_dest)
        shutil.copy2(label_source, label_dest)
        image_hash = sha256(image_source)
        label_hash = sha256(label_source)
        if sha256(image_dest) != image_hash or sha256(label_dest) != label_hash:
            raise RuntimeError(f"copy hash mismatch: {stem}")
        box_count = sum(1 for line in label_dest.read_text(encoding="utf-8").splitlines() if line.strip())
        if box_count != int(source_row["box_count"]):
            raise RuntimeError(f"label count drift: {stem}")
        rows.append({
            "queue_index": index,
            "packed_stem": stem,
            "packed_image": f"images/{image_source.name}",
            "packed_label": f"labels/{label_source.name}",
            "packed_image_sha256": image_hash,
            "packed_label_sha256": label_hash,
            "photo_id": stem,
            "source_photo_id": source_row["source_photo_id"],
            "task_id": source_row["task_id"],
            "source_queue": source_row["queue"],
            "source_sha256": source_row["source_sha256"],
            "source_annotation_sha256": source_row["annotation_sha256"],
            "selection_tags": source_row.get("selection_tags", []),
            "preannotation_count": box_count,
            **selection,
        })

    detector_hash = sha256(args.detector.resolve())
    if detector_hash != args.detector_sha256:
        raise RuntimeError("detector hash does not match the mining receipt")
    manifest = {
        "version": "liushanmen-historical-tray-boundary-hard-review-v1",
        "queue_id": output.name,
        "purpose": "full human re-review of historical tray boundaries with low production-R6 IoU",
        "selection_method": "rank historical human boxes by best production-R6 prediction IoU at confidence floor 0.05, then deduplicate by task",
        "detector_model": str(args.detector.resolve()),
        "detector_sha256": detector_hash,
        "source_dataset": str(source),
        "source_dataset_manifest_sha256": sha256(source / "manifest.json"),
        "source_provenance_sha256": sha256(provenance_path),
        "preannotations_are_not_ground_truth": True,
        "every_image_requires_full_human_review": True,
        "protected_holdout_included": False,
        "validation_images_included": False,
        "derived_recent_patches_included": False,
        "all_rows_historical_train_only": True,
        "queue_count": len(rows),
        "independent_task_count": len(task_ids),
        "rows": rows,
        "production_reads": 0,
        "production_writes": 0,
        "originals_modified": 0,
    }
    (output / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps({
        "queue": str(output),
        "rows": len(rows),
        "independent_tasks": len(task_ids),
        "manifest_sha256": sha256(output / "manifest.json"),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
