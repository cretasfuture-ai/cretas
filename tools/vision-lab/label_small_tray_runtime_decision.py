#!/usr/bin/env python3
"""Issue the fail-closed release eligibility decision for extended ownership."""
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


def latency(details: list[dict[str, Any]]) -> dict[str, float]:
    values = sorted(float(row["modelLatencyMs"]) for row in details)
    return {
        "mean_ms": round(sum(values) / len(values), 1),
        "p95_ms": round(values[int((len(values) - 1) * 0.95)], 1),
        "max_ms": round(values[-1], 1),
    }


def decide(
    baseline_path: Path, candidate_path: Path, human_path: Path,
    replay_path: Path, output: Path,
) -> dict[str, Any]:
    paths = [path.resolve() for path in (
        baseline_path, candidate_path, human_path, replay_path,
    )]
    baseline, candidate, human, replay = [load(path) for path in paths]
    if (
        baseline.get("version") != "liushanmen-recent-label-photo-audit-v1"
        or candidate.get("version") != "liushanmen-recent-label-photo-audit-v1"
        or baseline.get("productionWrites") != 0
        or candidate.get("productionWrites") != 0
    ):
        raise RuntimeError("recent runtime audit contract drift")
    if [row["photoId"] for row in baseline["details"]] != [row["photoId"] for row in candidate["details"]]:
        raise RuntimeError("recent runtime photo inventory drift")
    if (
        human.get("version") != "liushanmen-small-tray-extended-human-adjudication-v1"
        or human.get("reviewed") != 1
        or human.get("human_missing_confirmed") != 1
    ):
        raise RuntimeError("human correction contract drift")
    correction = human["results"][0]
    flagged = {
        str(row["photoId"])
        for row in candidate["details"]
        if row["humanTruth"]["kind"] == "normal" and row["productionBlind"]["suspects"]
    }
    if flagged != {str(correction["source_photo_id"])}:
        raise RuntimeError("candidate normal flag is not exactly the human correction")
    if (
        replay.get("version") != "liushanmen-small-tray-extended-ownership-replay-v1"
        or replay.get("runtime_candidate_mode") is not True
        or replay.get("records") != 310
        or replay.get("candidate_tray_count_parity_verified") is not True
        or replay.get("gate_passed") is not True
        or replay.get("unreviewed_or_false_positive_regressions") != []
    ):
        raise RuntimeError("310-photo runtime replay gate failed")
    baseline_blind, candidate_blind = baseline["blindEvaluation"], candidate["blindEvaluation"]
    corrected_baseline = {
        "defect_instances": int(baseline_blind["defectInstances"]) + 1,
        "defect_hits": int(baseline_blind["defectHits"]),
        "normal_photos": int(baseline_blind["normalPhotos"]) - 1,
        "normal_false_flags": int(baseline_blind["normalFalseFlags"]),
    }
    corrected_candidate = {
        "defect_instances": int(candidate_blind["defectInstances"]) + 1,
        "defect_hits": int(candidate_blind["defectHits"]) + 1,
        "normal_photos": int(candidate_blind["normalPhotos"]) - 1,
        "normal_false_flags": int(candidate_blind["normalFalseFlags"]) - 1,
    }
    passed = (
        corrected_candidate["defect_hits"] > corrected_baseline["defect_hits"]
        and corrected_candidate["normal_false_flags"] <= corrected_baseline["normal_false_flags"]
    )
    baseline_latency, candidate_latency = latency(baseline["details"]), latency(candidate["details"])
    receipt = {
        "version": "liushanmen-small-tray-runtime-decision-v1",
        "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "candidate": "extend ownership downward by 0.75x height only for shallow wide UNJUDGEABLE trays",
        "corrected_recent_evaluation": {
            "baseline": corrected_baseline, "candidate": corrected_candidate,
        },
        "production_equivalent_replay": {
            "records": replay["records"], "affected_records": replay["affected_records"],
            "affected_unique_photos": replay["affected_unique_photos"],
            "unreviewed_regressions": 0,
            "allowed_confirmed_truth_corrections": len(replay["allowed_human_truth_corrections"]),
        },
        "latency": {
            "baseline": baseline_latency, "candidate": candidate_latency,
            "mean_delta_ms": round(candidate_latency["mean_ms"] - baseline_latency["mean_ms"], 1),
        },
        "gate_passed": passed,
        "decision": (
            "PROMOTION_ELIGIBLE_PENDING_GIT_AND_DEPLOYMENT_GATES"
            if passed else "REJECTED"
        ),
        "production_changed": False, "deployment_started": False,
        "production_writes": 0, "originals_modified": 0,
        "inputs": [
            {"path": str(path), "sha256": sha256_file(path)} for path in paths
        ],
    }
    if output.exists():
        raise FileExistsError(f"refusing to overwrite decision: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", required=True, type=Path)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--human-review", required=True, type=Path)
    parser.add_argument("--runtime-replay", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    receipt = decide(
        args.baseline, args.candidate, args.human_review,
        args.runtime_replay, args.output,
    )
    print(json.dumps({key: receipt[key] for key in (
        "corrected_recent_evaluation", "production_equivalent_replay",
        "latency", "gate_passed", "decision", "production_changed",
    )}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
