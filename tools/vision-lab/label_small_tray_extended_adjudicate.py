#!/usr/bin/env python3
"""Validate the minimal extended-ownership human review queue."""
from __future__ import annotations

import argparse
import datetime as dt
import importlib.util
import json
from pathlib import Path
from typing import Any


HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "target_adjudicate", HERE / "label_small_tray_target_adjudicate.py",
)
TARGET = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(TARGET)


def load(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise RuntimeError(f"expected JSON object: {path}")
    return value


def adjudicate(queue: Path, output: Path) -> dict[str, Any]:
    queue, output = queue.resolve(), output.resolve()
    manifest_path = queue / "manifest.json"
    manifest = load(manifest_path)
    if manifest.get("version") != "liushanmen-small-tray-tight-target-review-v1":
        raise RuntimeError("unsupported extended review manifest")
    if manifest.get("production_writes") != 0 or manifest.get("originals_modified") != 0:
        raise RuntimeError("review queue violated read-only contract")
    rows = manifest.get("rows") or []
    if not rows or len(rows) != int(manifest.get("queue_count", -1)):
        raise RuntimeError("review queue count drift")
    if output.exists():
        raise FileExistsError(f"refusing to overwrite adjudication: {output}")
    results = []
    for row in rows:
        crop_id = str(row["crop_id"])
        annotation_path = queue / "annotations-human" / f"{crop_id}.json"
        annotation = load(annotation_path)
        verdict = TARGET.human_verdict(annotation, crop_id)
        results.append({
            "crop_id": crop_id, "source_photo_id": row["source_photo_id"],
            "source_task_id": row["source_task_id"], "sku_code": row["sku_code"],
            "candidate_verdict": row["model_previous_verdict"],
            "human_verdict": verdict,
            "candidate_agrees_with_human": verdict == row["model_previous_verdict"],
            "annotation": str(annotation_path.resolve()),
            "annotation_sha256": TARGET.sha256_file(annotation_path),
        })
    receipt = {
        "version": "liushanmen-small-tray-extended-human-adjudication-v1",
        "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "reviewed": len(results),
        "human_missing_confirmed": sum(row["human_verdict"].startswith("MISSING_") for row in results),
        "human_no_defect": sum(row["human_verdict"] == "NO_DEFECT" for row in results),
        "human_unjudgeable": sum(row["human_verdict"] == "UNJUDGEABLE" for row in results),
        "results": results,
        "training_eligible": False, "promotion_evidence": False,
        "production_writes": 0, "originals_modified": 0,
        "inputs": {
            "queue": str(queue), "manifest_sha256": TARGET.sha256_file(manifest_path),
        },
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    receipt = adjudicate(args.queue, args.output)
    print(json.dumps({key: receipt[key] for key in (
        "reviewed", "human_missing_confirmed", "human_no_defect",
        "human_unjudgeable", "production_writes",
    )}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
