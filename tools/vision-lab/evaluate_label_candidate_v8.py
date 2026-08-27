#!/usr/bin/env python3
"""Fail-closed, hash-bound V8 replay for a label-model candidate."""

from __future__ import annotations

import argparse
import copy
import json
import shutil
import sys
import tempfile
from pathlib import Path
from typing import Any

import label_qc_oracle_diagnostics as diagnostics
from evaluate_tray_candidate import apply_truth_adjudication


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def require_hash(path: Path, expected: str) -> None:
    if not path.is_file() or diagnostics.sha256_file(path) != expected.lower():
        raise RuntimeError(f"bound input drift: {path}")


def promotion_gate(
    baseline: dict[str, Any], candidate: dict[str, Any], *,
    parity_mismatches: int, max_p95_ms: float, max_latency_regression: float,
) -> dict[str, Any]:
    errors: list[str] = []
    baseline_details = {row["photo_id"]: row for row in baseline.get("details", [])}
    candidate_details = {row["photo_id"]: row for row in candidate.get("details", [])}
    regressions = sorted(
        photo_id for photo_id, row in baseline_details.items()
        if row.get("kind") == "defect" and row.get("hit") is True
        and candidate_details.get(photo_id, {}).get("hit") is not True
    )
    if int(baseline.get("defect_hits", -1)) != int(baseline.get("defect_total", 0)):
        errors.append("production V8 baseline does not cover every current-truth defect")
    if regressions:
        errors.append(f"protected defect regressions: {regressions}")
    if int(candidate.get("defect_hits", -1)) < int(baseline.get("defect_hits", 0)):
        errors.append("protected defect recall regressed")
    if int(candidate.get("false_flags", -1)) > int(baseline.get("false_flags", 0)):
        errors.append("normal false flags increased")
    baseline_p95 = float(baseline.get("p95_latency_ms", 0.0))
    candidate_p95 = float(candidate.get("p95_latency_ms", float("inf")))
    if candidate_p95 > max_p95_ms:
        errors.append("candidate P95 latency exceeds absolute limit")
    if baseline_p95 <= 0 or candidate_p95 > baseline_p95 * (1.0 + max_latency_regression):
        errors.append("candidate P95 latency regressed beyond relative limit")
    if parity_mismatches != 0:
        errors.append("PT/ONNX parity gate failed")
    return {
        "passed": not errors,
        "promotion_allowed": not errors,
        "deployment_allowed": not errors,
        "errors": errors,
        "defect_regressions": regressions,
        "baseline_defect_hits": int(baseline.get("defect_hits", 0)),
        "candidate_defect_hits": int(candidate.get("defect_hits", 0)),
        "baseline_false_flags": int(baseline.get("false_flags", 0)),
        "candidate_false_flags": int(candidate.get("false_flags", 0)),
        "baseline_p95_latency_ms": baseline_p95,
        "candidate_p95_latency_ms": candidate_p95,
        "onnx_parity_mismatches": parity_mismatches,
        "production_pipeline_replay": True,
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--baseline-receipt", required=True, type=Path)
    parser.add_argument("--candidate-receipt", required=True, type=Path)
    parser.add_argument("--truth-adjudication", required=True, type=Path)
    parser.add_argument("--runtime-bundle", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--max-p95-ms", type=float, default=8000.0)
    parser.add_argument("--max-latency-regression", type=float, default=0.15)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError(f"refusing to overwrite V8 label evaluation: {output}")
    baseline_receipt = load_json(args.baseline_receipt.resolve())
    candidate_receipt = load_json(args.candidate_receipt.resolve())
    candidate_artifact = Path(str(candidate_receipt["artifact"])).resolve()
    require_hash(candidate_artifact, str(candidate_receipt["artifact_sha256"]))
    parity_mismatches = int(candidate_receipt.get("onnx_parity_mismatches", -1))

    manifest = Path(str(baseline_receipt["inputs"]["protected_manifest"])).resolve()
    require_hash(manifest, str(baseline_receipt["inputs"]["protected_manifest_sha256"]))
    records = diagnostics.load_records(manifest)
    records_before_truth = len(records)
    records, truth = apply_truth_adjudication(
        records, args.truth_adjudication.resolve(), [manifest],
    )
    human_manifest = Path(str(baseline_receipt["human_audit"]["manifest"])).resolve()
    require_hash(human_manifest, str(baseline_receipt["human_audit"]["manifest_sha256"]))
    audits, human_audit = diagnostics.load_human_audits(human_manifest)

    repo_python = (args.repo_root.resolve() / "backend" / "python").resolve()
    sys.path.insert(0, str(repo_python))
    from label_qc.services import screening
    from label_qc.services import v8_pipeline
    from label_qc.services import yolo_detector as yolo

    runtime_bundle = args.runtime_bundle.resolve()
    runtime_manifest = runtime_bundle / v8_pipeline.MANIFEST_FILE
    runtime_manifest_payload = load_json(runtime_manifest)
    with tempfile.TemporaryDirectory(prefix="label-qc-v8-label-candidate-") as temporary:
        candidate_bundle = Path(temporary) / "bundle"
        shutil.copytree(runtime_bundle, candidate_bundle)
        shutil.copy2(candidate_artifact, candidate_bundle / "label.onnx")
        candidate_manifest = copy.deepcopy(runtime_manifest_payload)
        candidate_manifest["modelId"] = str(candidate_receipt["model_id"])
        candidate_manifest["models"]["production_label"]["sha256"] = diagnostics.sha256_file(candidate_artifact)
        write_json(candidate_bundle / v8_pipeline.MANIFEST_FILE, candidate_manifest)

        production_runtime = v8_pipeline.LabelQcV8Models(runtime_bundle)
        candidate_runtime = v8_pipeline.LabelQcV8Models(candidate_bundle)
        if not production_runtime.available or not candidate_runtime.available:
            raise RuntimeError(
                production_runtime.load_error or candidate_runtime.load_error
                or "V8 runtime bundle unavailable"
            )
        params = production_runtime.recommended_params()
        production_base = yolo.LabelQcYoloModels(model_dir=runtime_bundle)
        if not production_base.available:
            raise RuntimeError(production_base.load_error or "production base models unavailable")
        oracles = diagnostics.build_truth_oracles(
            records, production_base, screening, yolo, params, audits,
        )
        baseline = diagnostics.evaluate_variant(
            "production_v8", records, production_runtime, screening, params, oracles,
        )
        candidate = diagnostics.evaluate_variant(
            "candidate_label_v8", records, candidate_runtime, screening, params, oracles,
        )

    gate = promotion_gate(
        baseline, candidate, parity_mismatches=parity_mismatches,
        max_p95_ms=args.max_p95_ms,
        max_latency_regression=args.max_latency_regression,
    )
    payload = {
        "version": "label-qc-v8-label-candidate-eval-v1",
        "created_at": diagnostics.utc_now(),
        "inputs": {
            "baseline_receipt": str(args.baseline_receipt.resolve()),
            "baseline_receipt_sha256": diagnostics.sha256_file(args.baseline_receipt),
            "candidate_receipt": str(args.candidate_receipt.resolve()),
            "candidate_receipt_sha256": diagnostics.sha256_file(args.candidate_receipt),
            "candidate_artifact_sha256": diagnostics.sha256_file(candidate_artifact),
            "protected_manifest": str(manifest),
            "protected_manifest_sha256": diagnostics.sha256_file(manifest),
            "truth_adjudication": truth,
            "runtime_bundle": str(runtime_bundle),
            "runtime_manifest_sha256": diagnostics.sha256_file(runtime_manifest),
            "records_before_truth_adjudication": records_before_truth,
        },
        "human_audit": human_audit,
        "baseline": baseline,
        "candidate": candidate,
        "gate": gate,
        "production_writes": 0,
        "originals_modified": 0,
        "deployment_started": False,
    }
    write_json(output, payload)
    print(json.dumps({"output": str(output), "gate": gate}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
