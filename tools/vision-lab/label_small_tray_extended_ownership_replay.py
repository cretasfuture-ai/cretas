#!/usr/bin/env python3
"""Replay frozen extended ownership over every affected record in the 310-photo gate."""
from __future__ import annotations

import argparse
import datetime as dt
import importlib.util
import json
import sys
from collections import Counter
from pathlib import Path
from typing import Any, Sequence

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "extended_diagnostic", HERE / "label_small_tray_extended_ownership_diagnostic.py",
)
EXTENDED = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(EXTENDED)

DEFECTS = {"MISSING_WHITE_LABEL", "MISSING_COLOR_LABEL", "BOTH_MISSING"}
GROUPS = ("protected_7_plus_19", "independent_7_plus_77", "hard_negative_200")


def rows_from_manifest(manifest: dict[str, Any]) -> list[dict[str, Any]]:
    rows = manifest.get("records") or manifest.get("rows") or manifest.get("photos")
    if not isinstance(rows, list):
        raise RuntimeError("source manifest has no record list")
    return rows


def verdict_counts(trays: Sequence[Any], attr: str) -> dict[str, int]:
    return dict(Counter(str(getattr(tray, attr)) for tray in trays))


def added_defects(baseline: dict[str, int], candidate: dict[str, int]) -> dict[str, int]:
    return {
        name: int(candidate.get(name, 0)) - int(baseline.get(name, 0))
        for name in DEFECTS
        if int(candidate.get(name, 0)) > int(baseline.get(name, 0))
    }


def run(
    repo_root: Path, bundle: Path, expected_path: Path, manifests: Sequence[Path],
    corrected_review_path: Path, output: Path, bottom_ratio: float, threshold: float,
    runtime_candidate: bool = False,
) -> dict[str, Any]:
    expected, corrected = EXTENDED.load(expected_path), EXTENDED.load(corrected_review_path)
    if expected.get("version") != "vision-lab-today-human-v8-fallback-replay-v1":
        raise RuntimeError("unsupported expected replay")
    if corrected.get("version") != "liushanmen-small-tray-target-adjudication-v1":
        raise RuntimeError("unsupported corrected review")
    if output.exists():
        raise FileExistsError(f"refusing to overwrite replay: {output}")
    image_index: dict[str, dict[str, Any]] = {}
    manifest_inputs = []
    for manifest_path in manifests:
        manifest = EXTENDED.load(manifest_path)
        for row in rows_from_manifest(manifest):
            photo_id = str(row.get("photo_id") or row.get("photoId"))
            if photo_id and row.get("image"):
                image_index[photo_id] = row
        manifest_inputs.append({
            "path": str(manifest_path.resolve()), "sha256": EXTENDED.sha256_file(manifest_path),
        })
    inventory = []
    for group in GROUPS:
        for row in expected[group]["details"]:
            inventory.append({**row, "dataset": group})
    if len(inventory) != 310:
        raise RuntimeError("expected replay must contain exactly 310 records")
    affected = [row for row in inventory if int(row["verdict_counts"].get("UNJUDGEABLE", 0)) > 0]
    unique: dict[str, dict[str, Any]] = {}
    for row in affected:
        unique[str(row["photo_id"])] = row
    missing = set(unique) - set(image_index)
    if missing:
        raise RuntimeError(f"affected replay images missing from source manifests: {sorted(missing)}")

    sys.path.insert(0, str((repo_root / "backend" / "python").resolve()))
    from label_qc.services.screening import resize_crop
    from label_qc.services.v8_pipeline import LabelQcV8Models
    from label_qc.services.yolo_detector import LabelQcYoloModels

    runtime = LabelQcV8Models(bundle.resolve())
    production_labels = LabelQcYoloModels(bundle.resolve())
    if not runtime.available or not production_labels.available:
        raise RuntimeError(runtime.load_error or production_labels.load_error or "models unavailable")
    unique_results: dict[str, dict[str, Any]] = {}
    for index, (photo_id, expected_row) in enumerate(unique.items(), start=1):
        source = image_index[photo_id]
        image_path = Path(str(source["image"])).resolve()
        expected_hash = str(expected_row["image_sha256"])
        if not image_path.is_file() or EXTENDED.sha256_file(image_path) != expected_hash:
            raise RuntimeError(f"source image binding drift: {photo_id}")
        frame = EXTENDED.load_rgb_image(image_path)
        from label_qc.services.screening import ScreeningParams
        baseline_result = runtime.screen_image(frame, ScreeningParams(
            tray_conf=0.60, label_conf=threshold, pad_ratio=0.14,
            crop_width=640, min_crop_px=120, capture_trace=False,
        ))
        expected_counts = {str(k): int(v) for k, v in expected_row["verdict_counts"].items()}
        runtime_counts = verdict_counts(baseline_result.trays, "verdict")
        if runtime_candidate:
            if sum(runtime_counts.values()) != sum(expected_counts.values()):
                raise RuntimeError(
                    f"runtime candidate tray-count parity drift: {photo_id}; "
                    f"expected={expected_counts} actual={runtime_counts}"
                )
            baseline_counts = expected_counts
            candidate_counts = runtime_counts
            transitions = []
            for event in (baseline_result.runtime_audit or {}).get("extendedOwnershipEvents") or []:
                transitions.append({
                    "tray_index": int(event["trayIndex"]),
                    "from": str(event["previousVerdict"]),
                    "to": str(event["finalVerdict"]),
                    "classes": list(event.get("ownedClasses") or []),
                })
        else:
            baseline_counts = runtime_counts
        if not runtime_candidate and baseline_counts != expected_counts:
            raise RuntimeError(
                f"production baseline parity drift: {photo_id}; "
                f"expected={expected_row['verdict_counts']} actual={baseline_counts}"
            )
        if not runtime_candidate:
            candidate_trays = []
            transitions = []
            for tray in baseline_result.trays:
                candidate_verdict = str(tray.verdict)
                box = [float(value) for value in tray.box]
                width, height = box[2] - box[0], box[3] - box[1]
                shallow = height < 120.0 and height > 0.0 and width / height > 2.0
                if shallow and candidate_verdict == "UNJUDGEABLE":
                    physical = EXTENDED.extended_box(box, frame.shape[0], bottom_ratio)
                    rect = EXTENDED.crop_rect(physical, frame.shape[1], frame.shape[0], 0.14)
                    crop = frame[rect[1]:rect[3], rect[0]:rect[2]]
                    resized = resize_crop(crop, 640)
                    detections = production_labels.detect_labels(resized, threshold)
                    sx = (rect[2] - rect[0]) / max(1, resized.shape[1])
                    sy = (rect[3] - rect[1]) / max(1, resized.shape[0])
                    classes = set()
                    for detection in detections:
                        cx = rect[0] + ((detection.x0 + detection.x1) / 2) * sx
                        cy = rect[1] + ((detection.y0 + detection.y1) / 2) * sy
                        if physical[0] <= cx <= physical[2] and physical[1] <= cy <= physical[3]:
                            classes.add(int(detection.class_id))
                    proposed = EXTENDED.verdict(classes)
                    candidate_verdict = "UNJUDGEABLE" if proposed == "BOTH_MISSING" else proposed
                    transitions.append({
                        "tray_index": int(tray.index), "from": str(tray.verdict),
                        "to": candidate_verdict, "classes": sorted(classes),
                    })
                candidate_trays.append(type("Candidate", (), {"candidate_verdict": candidate_verdict})())
            candidate_counts = dict(Counter(row.candidate_verdict for row in candidate_trays))
        unique_results[photo_id] = {
            "photo_id": photo_id, "image_sha256": expected_hash,
            "baseline_verdict_counts": baseline_counts,
            "candidate_verdict_counts": candidate_counts,
            "added_defects": added_defects(baseline_counts, candidate_counts),
            "transitions": transitions,
        }
        print(json.dumps({"completed": index, "total": len(unique), "photo_id": photo_id}), flush=True)

    corrected_truth = {
        str(row["source_photo_id"]): str(row["human_verdict"])
        for row in corrected.get("results") or []
    }
    regressions = []
    allowed_corrections = []
    record_results = []
    for row in inventory:
        result = unique_results.get(str(row["photo_id"]))
        additions = result["added_defects"] if result else {}
        if additions:
            truth = corrected_truth.get(str(row["photo_id"]))
            allowed = len(additions) == 1 and additions.get(truth) == 1
            evidence = {
                "dataset": row["dataset"], "photo_id": row["photo_id"],
                "expected_kind": row["kind"], "added_defects": additions,
                "corrected_human_verdict": truth,
            }
            (allowed_corrections if allowed else regressions).append(evidence)
        record_results.append({
            "dataset": row["dataset"], "photo_id": row["photo_id"],
            "affected": result is not None, "added_defects": additions,
        })
    receipt = {
        "version": "liushanmen-small-tray-extended-ownership-replay-v1",
        "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "params_frozen_before_replay": True,
        "params": {"bottom_ratio": bottom_ratio, "threshold": threshold},
        "records": len(inventory), "affected_records": len(affected),
        "affected_unique_photos": len(unique),
        "baseline_parity_verified": not runtime_candidate,
        "baseline_expected_binding_verified": True,
        "runtime_candidate_mode": runtime_candidate,
        "candidate_tray_count_parity_verified": runtime_candidate,
        "allowed_human_truth_corrections": allowed_corrections,
        "unreviewed_or_false_positive_regressions": regressions,
        "gate_passed": not regressions,
        "record_results": record_results,
        "unique_results": list(unique_results.values()),
        "production_writes": 0, "originals_modified": 0,
        "production_changed": False, "deployment_started": False,
        "inputs": {
            "expected": {"path": str(expected_path.resolve()), "sha256": EXTENDED.sha256_file(expected_path)},
            "corrected_review": {"path": str(corrected_review_path.resolve()), "sha256": EXTENDED.sha256_file(corrected_review_path)},
            "source_manifests": manifest_inputs, "bundle": str(bundle.resolve()),
        },
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--expected", required=True, type=Path)
    parser.add_argument("--source-manifest", required=True, action="append", type=Path)
    parser.add_argument("--corrected-review", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--bottom-ratio", type=float, default=0.75)
    parser.add_argument("--threshold", type=float, default=0.20)
    parser.add_argument("--runtime-candidate", action="store_true")
    args = parser.parse_args()
    receipt = run(
        args.repo_root.resolve(), args.bundle.resolve(), args.expected.resolve(),
        [path.resolve() for path in args.source_manifest], args.corrected_review.resolve(),
        args.output.resolve(), args.bottom_ratio, args.threshold,
        args.runtime_candidate,
    )
    print(json.dumps({key: receipt[key] for key in (
        "records", "affected_records", "affected_unique_photos",
        "baseline_parity_verified", "allowed_human_truth_corrections",
        "unreviewed_or_false_positive_regressions", "gate_passed",
        "production_changed",
    )}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
