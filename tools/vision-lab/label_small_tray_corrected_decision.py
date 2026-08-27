#!/usr/bin/env python3
"""Amend the rejected small-tray policy decision with target-aware human truth."""
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


def decide(prior_path: Path, review_path: Path, output: Path) -> dict[str, Any]:
    prior_path, review_path, output = prior_path.resolve(), review_path.resolve(), output.resolve()
    prior, review = load(prior_path), load(review_path)
    if (
        prior.get("version") != "liushanmen-small-tray-policy-candidate-decision-v1"
        or prior.get("decision") != "REJECTED_PROTECTED_FALSE_POSITIVE_REGRESSION"
        or prior.get("production_changed") is not False
        or prior.get("deployment_started") is not False
    ):
        raise RuntimeError("prior fail-closed decision contract drift")
    if (
        review.get("version") != "liushanmen-small-tray-target-adjudication-v1"
        or review.get("reviewed_unique_photos") != 3
        or review.get("human_no_defect") != 2
        or review.get("human_missing_confirmed") != 1
        or review.get("human_unjudgeable") != 0
    ):
        raise RuntimeError("corrected target review contract drift")
    regressions = prior["production_equivalent_replay"]["details"]
    regression_ids = {str(row["photoId"]) for row in regressions}
    results = review["results"]
    if regression_ids != {str(row["source_photo_id"]) for row in results}:
        raise RuntimeError("corrected reviews do not bind exactly to replay differences")
    confirmed_false_positives = [row for row in results if row["human_verdict"] == "NO_DEFECT"]
    confirmed_true_defects = [row for row in results if row["human_verdict"].startswith("MISSING_")]
    receipt = {
        "version": "liushanmen-small-tray-policy-corrected-decision-v1",
        "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "prior_decision": prior["decision"],
        "corrected_human_truth": {
            "replay_differences_reviewed": len(results),
            "confirmed_candidate_false_positives": len(confirmed_false_positives),
            "confirmed_candidate_true_defects": len(confirmed_true_defects),
            "false_positive_photo_ids": [row["source_photo_id"] for row in confirmed_false_positives],
            "true_defect_photo_ids": [row["source_photo_id"] for row in confirmed_true_defects],
        },
        "gate_passed": not confirmed_false_positives,
        "decision": "REJECTED_CONFIRMED_PROTECTED_FALSE_POSITIVES",
        "production_changed": False,
        "deployment_started": False,
        "production_writes": 0,
        "originals_modified": 0,
        "inputs": {
            "prior_decision": {"path": str(prior_path), "sha256": sha256_file(prior_path)},
            "corrected_review": {"path": str(review_path), "sha256": sha256_file(review_path)},
        },
    }
    if output.exists():
        raise FileExistsError(f"refusing to overwrite corrected decision: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prior-decision", required=True, type=Path)
    parser.add_argument("--corrected-review", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    receipt = decide(args.prior_decision, args.corrected_review, args.output)
    print(json.dumps({key: receipt[key] for key in (
        "corrected_human_truth", "gate_passed", "decision", "production_changed",
        "deployment_started",
    )}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
