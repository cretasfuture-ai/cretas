#!/usr/bin/env python3
"""Reduce an edge-safe active-learning queue to a small balanced review set."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from collections import Counter
from pathlib import Path
from typing import Any, Sequence


VERSION = "liushanmen-minimal-active-label-review-v1"
EDGE_MARGIN_RATIO = 0.005
EXPLICIT_DISAGREEMENTS = {
    "BOTH_MISSING": 0,
    "MISSING_WHITE_LABEL": 1,
    "MISSING_COLOR_LABEL": 2,
}


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object: {path}")
    return value


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def complete_source_tray(row: dict[str, Any]) -> bool:
    size = row.get("source_image_size")
    box = row.get("target_tray_box_source_xyxy")
    if not isinstance(size, list) or len(size) != 2 or not isinstance(box, list) or len(box) != 4:
        return False
    width, height = float(size[0]), float(size[1])
    if width <= 0 or height <= 0:
        return False
    x0, y0, x1, y1 = (float(value) for value in box)
    return (
        x0 > width * EDGE_MARGIN_RATIO
        and y0 > height * EDGE_MARGIN_RATIO
        and x1 < width * (1.0 - EDGE_MARGIN_RATIO)
        and y1 < height * (1.0 - EDGE_MARGIN_RATIO)
        and x1 > x0 and y1 > y0
    )


def select_rows(
    rows: Sequence[dict[str, Any]], max_items: int, max_per_sku: int,
    max_per_task: int,
) -> tuple[list[dict[str, Any]], Counter[str]]:
    exclusions: Counter[str] = Counter()
    ranked: list[dict[str, Any]] = []
    for row in rows:
        verdict = str(row.get("label_v1_verdict") or "")
        if verdict not in EXPLICIT_DISAGREEMENTS:
            exclusions["not_explicit_model_disagreement"] += 1
            continue
        if not complete_source_tray(row):
            exclusions["target_tray_touches_source_edge"] += 1
            continue
        ranked.append(row)
    ranked.sort(key=lambda row: (
        EXPLICIT_DISAGREEMENTS[str(row["label_v1_verdict"])],
        str(row.get("sku_code") or "UNKNOWN"),
        str(row.get("source_task_id") or ""),
        str(row.get("crop_id") or ""),
    ))
    selected: list[dict[str, Any]] = []
    per_sku: Counter[str] = Counter()
    per_task: Counter[str] = Counter()
    photos: set[str] = set()
    while ranked and len(selected) < max_items:
        progress = False
        verdicts = sorted(
            {str(row["label_v1_verdict"]) for row in ranked},
            key=EXPLICIT_DISAGREEMENTS.__getitem__,
        )
        skus = sorted({str(row.get("sku_code") or "UNKNOWN") for row in ranked})
        for verdict in verdicts:
            for sku in skus:
                if len(selected) >= max_items:
                    break
                candidate_index = None
                for index, row in enumerate(ranked):
                    row_sku = str(row.get("sku_code") or "UNKNOWN")
                    task = str(row.get("source_task_id") or "")
                    photo = str(row.get("source_photo_id") or "")
                    if str(row["label_v1_verdict"]) != verdict or row_sku != sku:
                        continue
                    if per_sku[sku] >= max_per_sku:
                        continue
                    if per_task[task] >= max_per_task or photo in photos:
                        continue
                    candidate_index = index
                    break
                if candidate_index is None:
                    continue
                row = ranked.pop(candidate_index)
                selected.append(row)
                per_sku[sku] += 1
                per_task[str(row.get("source_task_id") or "")] += 1
                photos.add(str(row.get("source_photo_id") or ""))
                progress = True
            if len(selected) >= max_items:
                break
        if not progress:
            break
    exclusions["eligible_not_selected"] += len(ranked)
    return selected, exclusions


def build(source: Path, output: Path, max_items: int, max_per_sku: int, max_per_task: int) -> dict[str, Any]:
    if output.exists():
        raise FileExistsError(f"refusing to overwrite queue: {output}")
    manifest_path = source / "manifest.json"
    manifest = load_json(manifest_path)
    if manifest.get("preannotations_are_not_ground_truth") is not True:
        raise RuntimeError("source queue preannotations must not be ground truth")
    if manifest.get("evaluation_or_production_use_allowed") is not False:
        raise RuntimeError("source queue must be isolated from evaluation and production")
    selected, exclusions = select_rows(
        list(manifest.get("rows") or []), max_items, max_per_sku, max_per_task,
    )
    if not selected:
        raise RuntimeError("no eligible explicit disagreements")
    for name in ("images", "prelabels", "annotations-human"):
        (output / name).mkdir(parents=True, exist_ok=False)
    copied_rows = []
    for row in selected:
        crop_id = str(row["crop_id"])
        source_image = Path(str(row["image"])).resolve()
        source_prelabel = source / "prelabels" / f"{crop_id}.json"
        if sha256_file(source_image) != str(row["image_sha256"]):
            raise RuntimeError(f"source image drift: {crop_id}")
        if not source_prelabel.is_file():
            raise FileNotFoundError(source_prelabel)
        image = output / "images" / source_image.name
        prelabel = output / "prelabels" / source_prelabel.name
        shutil.copy2(source_image, image)
        shutil.copy2(source_prelabel, prelabel)
        copied = dict(row)
        copied["image"] = str(image.resolve())
        copied["review_scope"] = "single_complete_target_tray_labels_only"
        copied["training_eligible"] = False
        copied_rows.append(copied)
    receipt = {
        "version": VERSION,
        "source_queue": str(source.resolve()),
        "source_manifest_sha256": sha256_file(manifest_path),
        "preannotations_are_not_ground_truth": True,
        "every_image_requires_full_human_review": True,
        "review_scope": "single_complete_target_tray_labels_only",
        "train_only": True,
        "evaluation_or_production_use_allowed": False,
        "queue_count": len(copied_rows),
        "selection": {
            "max_items": max_items,
            "max_per_sku": max_per_sku,
            "max_per_task": max_per_task,
            "max_per_photo": 1,
            "explicit_disagreements_only": True,
            "source_edge_margin_ratio": EDGE_MARGIN_RATIO,
        },
        "sku_counts": dict(Counter(str(row.get("sku_code") or "UNKNOWN") for row in copied_rows)),
        "verdict_counts": dict(Counter(str(row["label_v1_verdict"]) for row in copied_rows)),
        "exclusions": dict(exclusions),
        "rows": copied_rows,
        "production_reads": 0,
        "production_writes": 0,
        "originals_modified": 0,
    }
    (output / "manifest.json").write_text(
        json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--max-items", type=int, default=15)
    parser.add_argument("--max-per-sku", type=int, default=4)
    parser.add_argument("--max-per-task", type=int, default=2)
    args = parser.parse_args()
    if min(args.max_items, args.max_per_sku, args.max_per_task) <= 0:
        raise ValueError("selection limits must be positive")
    receipt = build(
        args.source.resolve(), args.output.resolve(), args.max_items,
        args.max_per_sku, args.max_per_task,
    )
    print(json.dumps({
        "queue": str(args.output.resolve()), "queue_count": receipt["queue_count"],
        "sku_counts": receipt["sku_counts"], "verdict_counts": receipt["verdict_counts"],
        "exclusions": receipt["exclusions"], "production_writes": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
