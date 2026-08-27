#!/usr/bin/env python3
"""Issue a fail-closed decision for the small-tray policy candidate."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
from typing import Any, Sequence


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


def replay_regressions(shards: Sequence[dict[str, Any]]) -> list[dict[str, Any]]:
    regressions = []
    for shard in shards:
        if (
            shard.get("version") != "liushanmen-production-runtime-v8-replay-shard-v1"
            or shard.get("productionWrites") != 0
            or shard.get("originalsModified") != 0
        ):
            raise RuntimeError("runtime replay shard safety contract drift")
        for mismatch in shard.get("mismatches") or []:
            change = (mismatch.get("differences") or {}).get("false_flags")
            if change and int(change["actual"]) > int(change["expected"]):
                regressions.append(mismatch)
    return regressions


def decide(args: argparse.Namespace) -> dict[str, Any]:
    baseline_path = args.baseline_recent.resolve()
    candidate_path = args.candidate_recent.resolve()
    missing_path = args.confirmed_missing_review.resolve()
    normal_path = args.false_positive_review.resolve()
    shard_paths = [path.resolve() for path in args.replay_shard]
    baseline, candidate = load(baseline_path), load(candidate_path)
    if baseline.get("version") != candidate.get("version") or baseline.get("version") != "liushanmen-recent-label-photo-audit-v1":
        raise RuntimeError("recent audit version mismatch")
    if baseline.get("productionWrites") != 0 or candidate.get("productionWrites") != 0:
        raise RuntimeError("recent audit violated read-only contract")
    if [row["photoId"] for row in baseline["details"]] != [row["photoId"] for row in candidate["details"]]:
        raise RuntimeError("recent replay photo inventory drift")
    for key in ("productionManifestSha256", "protectedManifestSha256"):
        if baseline["inputs"][key] != candidate["inputs"][key]:
            raise RuntimeError(f"recent replay binding drift: {key}")

    missing_review, normal_review = load(missing_path), load(normal_path)
    if (
        missing_review.get("version") != "liushanmen-small-tray-policy-adjudication-v1"
        or normal_review.get("version") != "liushanmen-small-tray-policy-adjudication-v1"
        or missing_review.get("reviewed") != 2
        or missing_review.get("human_missing_confirmed") != 2
        or normal_review.get("reviewed") != 3
        or normal_review.get("human_no_defect") != 3
    ):
        raise RuntimeError("human policy adjudication contract drift")
    shards = [load(path) for path in shard_paths]
    indexes = sorted(int(row["global_index"]) for shard in shards for row in shard.get("records") or [])
    if indexes != list(range(310)):
        raise RuntimeError("runtime replay does not cover exactly 310 records")
    regressions = replay_regressions(shards)
    baseline_blind = baseline["blindEvaluation"]
    candidate_blind = candidate["blindEvaluation"]
    recent_improved = (
        int(candidate_blind["defectHits"]) > int(baseline_blind["defectHits"])
        and int(candidate_blind["normalFalseFlags"]) <= int(baseline_blind["normalFalseFlags"])
    )
    passed = recent_improved and not regressions
    receipt = {
        "version": "liushanmen-small-tray-policy-candidate-decision-v1",
        "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "candidate": "preserve production-full missing verdict only when padded crop minimum is at least 120px",
        "recent_blind": {
            "photos": len(candidate["details"]),
            "baseline_defect_hits": baseline_blind["defectHits"],
            "candidate_defect_hits": candidate_blind["defectHits"],
            "defect_total": candidate_blind["defectInstances"],
            "baseline_normal_false_flags": baseline_blind["normalFalseFlags"],
            "candidate_normal_false_flags": candidate_blind["normalFalseFlags"],
        },
        "human_adjudication": {
            "confirmed_missing_examples": missing_review["human_missing_confirmed"],
            "confirmed_false_positive_examples": normal_review["human_no_defect"],
        },
        "production_equivalent_replay": {
            "photos": len(indexes),
            "regressions": len(regressions),
            "details": regressions,
        },
        "gate_passed": passed,
        "decision": "PROMOTION_ELIGIBLE" if passed else "REJECTED_PROTECTED_FALSE_POSITIVE_REGRESSION",
        "production_changed": False,
        "deployment_started": False,
        "production_writes": 0,
        "originals_modified": 0,
        "inputs": {
            "baseline_recent": {"path": str(baseline_path), "sha256": sha256_file(baseline_path)},
            "candidate_recent": {"path": str(candidate_path), "sha256": sha256_file(candidate_path)},
            "confirmed_missing_review": {"path": str(missing_path), "sha256": sha256_file(missing_path)},
            "false_positive_review": {"path": str(normal_path), "sha256": sha256_file(normal_path)},
            "replay_shards": [{"path": str(path), "sha256": sha256_file(path)} for path in shard_paths],
        },
    }
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError(f"refusing to overwrite decision receipt: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-recent", required=True, type=Path)
    parser.add_argument("--candidate-recent", required=True, type=Path)
    parser.add_argument("--confirmed-missing-review", required=True, type=Path)
    parser.add_argument("--false-positive-review", required=True, type=Path)
    parser.add_argument("--replay-shard", required=True, action="append", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    receipt = decide(args)
    print(json.dumps({
        "decision": receipt["decision"],
        "recent_blind": receipt["recent_blind"],
        "human_adjudication": receipt["human_adjudication"],
        "production_equivalent_replay": receipt["production_equivalent_replay"],
        "deployment_started": receipt["deployment_started"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
