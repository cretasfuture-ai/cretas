#!/usr/bin/env python3
"""Derive a low-clutter tray queue from a sealed teacher-assisted queue."""
from __future__ import annotations

import argparse
import copy
import json
import shutil
from pathlib import Path
from typing import Any

import mine_tray_queue as mine


def retained_proposals(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    retained: list[dict[str, Any]] = []
    for row in rows:
        if row.get("source") == "tray_detector":
            retained.append(copy.deepcopy(row))
            continue
        if row.get("source") != "locateanything_teacher":
            continue
        prompts = [item for item in row.get("support") or [] if item != "tray_detector_low_iou"]
        if float(row.get("confidence", 1.0)) < 0.10 and len(set(prompts)) >= 2:
            kept = copy.deepcopy(row)
            kept["support"] = sorted(set(prompts))
            retained.append(kept)
    return mine.deduplicate_boxes(retained)


def derive(source: Path, output: Path) -> dict[str, Any]:
    source, output = source.resolve(), output.resolve()
    if output.exists():
        raise FileExistsError(f"refusing to overwrite derived tray queue: {output}")
    manifest_path = source / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if (
        manifest.get("protected_holdout_included") is not False
        or manifest.get("preannotations_are_not_ground_truth") is not True
        or manifest.get("teacher", {}).get("enabled") is not True
    ):
        raise RuntimeError("source teacher queue contract is unsafe")
    for name in ("images", "labels", "annotations-human", "contact-sheets"):
        (output / name).mkdir(parents=True, exist_ok=False)

    rows: list[dict[str, Any]] = []
    teacher_before = teacher_after = detector_count = 0
    for row in manifest.get("rows") or []:
        stem = str(row["packed_stem"])
        annotation_path = source / "annotations-human" / f"{stem}.json"
        annotation = json.loads(annotation_path.read_text(encoding="utf-8"))
        if annotation.get("reviewed") is True or annotation.get("source") == "human":
            raise RuntimeError(f"source queue already contains human truth: {stem}")
        proposals = retained_proposals(row.get("preannotations") or [])
        detector_count += sum(item.get("source") == "tray_detector" for item in proposals)
        teacher_before += sum(
            item.get("source") == "locateanything_teacher"
            for item in row.get("preannotations") or []
        )
        teacher_after += sum(item.get("source") == "locateanything_teacher" for item in proposals)

        image_source = source / str(row["packed_image"])
        image_target = output / "images" / image_source.name
        shutil.copy2(image_source, image_target)
        label_target = output / "labels" / f"{stem}.txt"
        label_target.write_text(
            "".join(mine.yolo_line(item["box"]) for item in proposals), encoding="utf-8",
        )
        mine.write_json(output / "annotations-human" / f"{stem}.json", {
            "photo_id": stem,
            "source_photo_id": str(row.get("source_photo_id") or row.get("photo_id")),
            "format": "normalised_xyxy",
            "reviewed": False,
            "source": "filtered_detector_and_teacher_proposals_require_full_human_review",
            "boxes": [item["box"] for item in proposals],
        })
        packed = copy.deepcopy(row)
        packed.update({
            "packed_image": f"images/{image_target.name}",
            "packed_label": f"labels/{label_target.name}",
            "packed_image_sha256": mine.sha256_file(image_target),
            "preannotations": proposals,
            "preannotation_count": len(proposals),
            "selection_tags": sorted(set(
                tag for tag in packed.get("selection_tags") or [] if tag != "teacher_added"
            ) | ({"teacher_added"} if any(
                item.get("source") == "locateanything_teacher" for item in proposals
            ) else set())),
            "packed_image_absolute": str(image_target),
        })
        rows.append(packed)

    sheets = mine.make_contact_sheets(rows, output / "contact-sheets")
    payload = copy.deepcopy(manifest)
    payload.update({
        "version": "liushanmen-tray-filtered-teacher-review-v1",
        "created_at": mine.utc_now(),
        "source_queue": str(source),
        "source_manifest_sha256": mine.sha256_file(manifest_path),
        "teacher_filter": {
            "policy": "multi_prompt_and_detector_iou_below_0.10",
            "detector_boxes": detector_count,
            "teacher_boxes_before": teacher_before,
            "teacher_boxes_after": teacher_after,
        },
        "contact_sheets": sheets,
        "rows": [{key: value for key, value in row.items() if key != "packed_image_absolute"} for row in rows],
        "production_writes": 0,
        "originals_modified": False,
    })
    mine.write_json(output / "manifest.json", payload)
    return payload


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    payload = derive(args.source, args.output)
    print(json.dumps({
        "queue": str(args.output.resolve()), "queue_count": payload["queue_count"],
        "teacher_filter": payload["teacher_filter"], "production_writes": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
