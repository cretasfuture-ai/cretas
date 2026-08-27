#!/usr/bin/env python3
"""Correct independent evaluation metrics with target-level human adjudication."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
from typing import Any


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise RuntimeError(f"expected JSON object: {path}")
    return value


def correct(evaluation_path: Path, review_path: Path, output: Path) -> dict[str, Any]:
    evaluation_path, review_path, output = (
        evaluation_path.resolve(), review_path.resolve(), output.resolve()
    )
    evaluation, review = load(evaluation_path), load(review_path)
    if evaluation.get("version") != "liushanmen-small-tray-extended-ownership-evaluation-v1":
        raise RuntimeError("unsupported independent evaluation")
    if (
        review.get("version") != "liushanmen-small-tray-extended-human-adjudication-v1"
        or review.get("reviewed") != 1
        or review.get("human_missing_confirmed") != 1
        or review.get("human_no_defect") != 0
    ):
        raise RuntimeError("extended human review contract drift")
    reviewed = review["results"][0]
    matching = []
    for photo in evaluation.get("details") or []:
        if str(photo["photoId"]) != str(reviewed["source_photo_id"]):
            continue
        for tray in photo.get("candidate_trays") or []:
            if str(tray["candidate_verdict"]) == str(reviewed["human_verdict"]):
                matching.append({"photo_id": photo["photoId"], "tray_index": tray["index"]})
    if len(matching) != 1:
        raise RuntimeError("human correction does not bind exactly to one candidate flag")
    baseline = dict(evaluation["baseline"])
    candidate = dict(evaluation["candidate"])
    baseline["defectInstances"] = int(baseline["defectInstances"]) + 1
    baseline["defectRecall"] = round(
        int(baseline["defectHits"]) / int(baseline["defectInstances"]), 6,
    )
    baseline["normalPhotos"] = int(baseline["normalPhotos"]) - 1
    candidate["defectInstances"] = int(candidate["defectInstances"]) + 1
    candidate["defectHits"] = int(candidate["defectHits"]) + 1
    candidate["defectRecall"] = round(
        int(candidate["defectHits"]) / int(candidate["defectInstances"]), 6,
    )
    candidate["normalPhotos"] = int(candidate["normalPhotos"]) - 1
    candidate["normalFalseFlags"] = int(candidate["normalFalseFlags"]) - 1
    gate = (
        candidate["defectHits"] > baseline["defectHits"]
        and candidate["normalFalseFlags"] <= baseline["normalFalseFlags"]
    )
    receipt = {
        "version": "liushanmen-small-tray-extended-ownership-corrected-evaluation-v1",
        "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "truth_correction": {
            **matching[0], "old_truth": "NO_DEFECT",
            "human_truth": reviewed["human_verdict"],
        },
        "baseline": baseline, "candidate": candidate,
        "evaluation_gate_passed": gate,
        "next_gate": "production-equivalent replay and protected holdout",
        "production_writes": 0, "originals_modified": 0,
        "production_changed": False, "deployment_started": False,
        "inputs": {
            "evaluation": {"path": str(evaluation_path), "sha256": sha256_file(evaluation_path)},
            "human_review": {"path": str(review_path), "sha256": sha256_file(review_path)},
        },
    }
    if output.exists():
        raise FileExistsError(f"refusing to overwrite corrected evaluation: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evaluation", required=True, type=Path)
    parser.add_argument("--human-review", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    receipt = correct(args.evaluation, args.human_review, args.output)
    print(json.dumps({key: receipt[key] for key in (
        "truth_correction", "baseline", "candidate", "evaluation_gate_passed",
        "next_gate", "production_changed",
    )}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
