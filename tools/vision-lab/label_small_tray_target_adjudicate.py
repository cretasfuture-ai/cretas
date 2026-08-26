#!/usr/bin/env python3
"""Merge target-tray reviews, letting later complete crops supersede earlier ones."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
from typing import Any, Sequence


CLASSES = {0: "white_label", 1: "color_label"}
MISSING_TO_VERDICT = {
    frozenset({"white_label"}): "MISSING_WHITE_LABEL",
    frozenset({"color_label"}): "MISSING_COLOR_LABEL",
    frozenset({"white_label", "color_label"}): "BOTH_MISSING",
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise RuntimeError(f"expected JSON object: {path}")
    return value


def human_verdict(annotation: dict[str, Any], crop_id: str) -> str:
    if (
        annotation.get("crop_id") != crop_id
        or annotation.get("reviewed") is not True
        or annotation.get("source") != "human"
        or not isinstance(annotation.get("unjudgeable"), bool)
    ):
        raise RuntimeError(f"incomplete or invalid human annotation: {crop_id}")
    declared = annotation.get("declared_missing_classes")
    confirmed = annotation.get("missing_confirmed_by_human")
    if (
        not isinstance(declared, list)
        or not set(declared) <= set(CLASSES.values())
        or bool(declared) != (confirmed is True)
    ):
        raise RuntimeError(f"missing-class confirmation contract failed: {crop_id}")
    boxes = annotation.get("boxes") or []
    class_ids = {int(box["c"]) for box in boxes if isinstance(box, dict) and "c" in box}
    if not class_ids <= set(CLASSES):
        raise RuntimeError(f"unknown annotation class: {crop_id}")
    if annotation["unjudgeable"]:
        return "UNJUDGEABLE"
    if declared:
        return MISSING_TO_VERDICT[frozenset(declared)]
    if class_ids != set(CLASSES):
        raise RuntimeError(f"NO_DEFECT review must contain both label classes: {crop_id}")
    return "NO_DEFECT"


def adjudicate(queues: Sequence[Path], output: Path) -> dict[str, Any]:
    if len(queues) < 2:
        raise ValueError("at least two review queues are required for supersession")
    if output.exists():
        raise FileExistsError(f"refusing to overwrite adjudication: {output}")
    selected: dict[str, dict[str, Any]] = {}
    superseded: list[dict[str, str]] = []
    inputs = []
    for queue_arg in queues:
        queue = queue_arg.resolve()
        manifest_path = queue / "manifest.json"
        manifest = load_json(manifest_path)
        if manifest.get("version") != "liushanmen-small-tray-tight-target-review-v1":
            raise RuntimeError(f"unsupported target review manifest: {queue}")
        if manifest.get("production_writes") != 0 or manifest.get("originals_modified") != 0:
            raise RuntimeError("review queue violated read-only contract")
        rows = manifest.get("rows") or []
        if not rows or len(rows) != int(manifest.get("queue_count", -1)):
            raise RuntimeError("review queue count drift")
        inputs.append({"queue": str(queue), "manifest_sha256": sha256_file(manifest_path)})
        for row in rows:
            crop_id = str(row["crop_id"])
            annotation_path = queue / "annotations-human" / f"{crop_id}.json"
            annotation = load_json(annotation_path)
            photo_id = str(row["source_photo_id"])
            result = {
                "crop_id": crop_id,
                "source_photo_id": photo_id,
                "source_task_id": row["source_task_id"],
                "sku_code": row["sku_code"],
                "model_verdict": str(row["model_previous_verdict"]),
                "human_verdict": human_verdict(annotation, crop_id),
                "annotation": str(annotation_path.resolve()),
                "annotation_sha256": sha256_file(annotation_path),
                "queue": str(queue),
            }
            if photo_id in selected:
                superseded.append({
                    "source_photo_id": photo_id,
                    "superseded_crop_id": selected[photo_id]["crop_id"],
                    "replacement_crop_id": crop_id,
                })
            selected[photo_id] = result
    results = list(selected.values())
    receipt = {
        "version": "liushanmen-small-tray-target-adjudication-v1",
        "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "reviewed_unique_photos": len(results),
        "human_missing_confirmed": sum(
            row["human_verdict"].startswith("MISSING_") or row["human_verdict"] == "BOTH_MISSING"
            for row in results
        ),
        "human_no_defect": sum(row["human_verdict"] == "NO_DEFECT" for row in results),
        "human_unjudgeable": sum(row["human_verdict"] == "UNJUDGEABLE" for row in results),
        "superseded_reviews": superseded,
        "results": results,
        "inputs": inputs,
        "training_eligible": False,
        "promotion_evidence": False,
        "production_writes": 0,
        "originals_modified": 0,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", required=True, action="append", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    receipt = adjudicate(args.queue, args.output.resolve())
    print(json.dumps({key: receipt[key] for key in (
        "reviewed_unique_photos", "human_missing_confirmed", "human_no_defect",
        "human_unjudgeable", "superseded_reviews", "production_writes",
    )}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
