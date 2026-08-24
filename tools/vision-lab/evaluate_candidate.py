#!/usr/bin/env python3
"""Run a bounded legacy two-model comparison for candidate diagnostics.

This is not a replay of the production v8 multi-model cascade and therefore
must never satisfy the production-pipeline promotion gate.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import tempfile
import time
from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image, ImageOps


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def percentile(values: list[float], quantile: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round((len(ordered) - 1) * quantile))))
    return ordered[index]


def containment(tray: list[float], truth: list[float]) -> float:
    tx0, ty0, tx1, ty1 = tray
    x0, y0, x1, y1 = truth
    intersection = max(0.0, min(tx1, x1) - max(tx0, x0)) * max(0.0, min(ty1, y1) - max(ty0, y0))
    area = max(1e-9, (x1 - x0) * (y1 - y0))
    return intersection / area


def load_records(paths: list[Path]) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for path in paths:
        payload = json.loads(path.read_text(encoding="utf-8"))
        source = payload.get("records", payload.get("selected", payload)) if isinstance(payload, dict) else payload
        if not isinstance(source, list):
            raise ValueError(f"unsupported evaluation manifest: {path}")
        for row in source:
            image = Path(row.get("image") or row.get("path") or "")
            if not image.is_file():
                raise FileNotFoundError(image)
            human = str(row.get("human_label") or row.get("label") or "NO_DEFECT").upper()
            records.append({
                "photo_id": str(row.get("photo_id") or image.stem),
                "task_id": str(row.get("task_id") or row.get("photo_id") or image.stem),
                "image": image,
                "human_label": human,
                "bbox": row.get("bbox"),
                "group": str(row.get("group") or "unspecified"),
            })
    if len({row["photo_id"] for row in records}) != len(records):
        raise ValueError("duplicate photo ids in evaluation manifests")
    return records


def apply_configured_truth_adjudication(
    records: list[dict[str, Any]], manifest_paths: list[Path],
    adjudication_path: Path | None,
) -> tuple[list[dict[str, Any]], dict[str, Any] | None]:
    if adjudication_path is None:
        return records, None
    resolved_adjudication = adjudication_path.resolve()
    if not resolved_adjudication.is_file():
        raise FileNotFoundError(resolved_adjudication)
    # The tray evaluator owns the shared, fail-closed sidecar verifier. Import
    # lazily to avoid its existing import of the helpers in this module.
    from evaluate_tray_candidate import apply_truth_adjudication

    adjusted, audit = apply_truth_adjudication(
        records, resolved_adjudication,
        [path.resolve() for path in manifest_paths],
    )
    sidecar = json.loads(resolved_adjudication.read_text(encoding="utf-8"))
    audit["promotion_ready"] = sidecar.get("promotion_ready") is True
    return adjusted, audit


def evaluate(model_dir: Path, records: list[dict[str, Any]], screening_module, yolo_module, threshold: float) -> dict[str, Any]:
    params = screening_module.ScreeningParams(tray_conf=0.60, label_conf=threshold)
    models = yolo_module.LabelQcYoloModels(model_dir=model_dir)
    if not models.available:
        raise RuntimeError(models.load_error or "ONNX models unavailable")
    defect_total = defect_hits = false_flags = normal_photos = 0
    groups: dict[str, dict[str, int]] = {}
    latencies: list[float] = []
    details = []
    for row in records:
        with Image.open(row["image"]) as opened:
            frame = np.array(ImageOps.exif_transpose(opened).convert("RGB"))
        started = time.perf_counter()
        result = screening_module.screen_image(frame, models, params)
        latencies.append((time.perf_counter() - started) * 1000)
        if row["human_label"] in {"MISSING_WHITE_LABEL", "MISSING_COLOR_LABEL"}:
            defect_total += 1
            height, width = frame.shape[:2]
            bbox = row.get("bbox")
            truth = None
            if isinstance(bbox, list) and len(bbox) == 4:
                truth = [float(bbox[0]) * width, float(bbox[1]) * height,
                         float(bbox[2]) * width, float(bbox[3]) * height]
            hits = [
                tray for tray in result.suspects
                if tray.verdict == row["human_label"]
                and (truth is None or containment(tray.box, truth) >= 0.5)
            ]
            defect_hits += int(bool(hits))
            group = groups.setdefault(row["group"], {"defect_total": 0, "defect_hits": 0, "normal_photos": 0, "false_flags": 0})
            group["defect_total"] += 1
            group["defect_hits"] += int(bool(hits))
            details.append({"photo_id": row["photo_id"], "group": row["group"], "kind": "defect", "hit": bool(hits), "suspects": len(result.suspects)})
        else:
            normal_photos += 1
            false_flags += len(result.suspects)
            group = groups.setdefault(row["group"], {"defect_total": 0, "defect_hits": 0, "normal_photos": 0, "false_flags": 0})
            group["normal_photos"] += 1
            group["false_flags"] += len(result.suspects)
            details.append({"photo_id": row["photo_id"], "group": row["group"], "kind": "normal", "false_flags": len(result.suspects)})
    return {
        "defect_total": defect_total, "defect_hits": defect_hits,
        "normal_photos": normal_photos, "false_flags": false_flags,
        "p95_latency_ms": round(percentile(latencies, 0.95), 3), "groups": groups, "details": details,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--tray", required=True, type=Path)
    parser.add_argument("--production-label", required=True, type=Path)
    parser.add_argument("--candidate-label", required=True, type=Path)
    parser.add_argument("--manifest", required=True, action="append", type=Path)
    parser.add_argument("--threshold", type=float, default=0.20)
    parser.add_argument("--onnx-parity-mismatches", type=int, required=True)
    parser.add_argument("--truth-adjudication", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    for path in (args.tray, args.production_label, args.candidate_label):
        if not path.is_file():
            raise FileNotFoundError(path)
    sys.path.insert(0, str((args.repo_root / "backend" / "python").resolve()))
    from label_qc.services import screening as screening_module
    from label_qc.services import yolo_detector as yolo_module

    records = load_records(args.manifest)
    original_record_count = len(records)
    records, truth_adjudication = apply_configured_truth_adjudication(
        records, args.manifest, args.truth_adjudication,
    )
    with tempfile.TemporaryDirectory(prefix="visionlab-eval-") as temporary:
        temp = Path(temporary)
        baseline_dir = temp / "baseline"
        candidate_dir = temp / "candidate"
        baseline_dir.mkdir()
        candidate_dir.mkdir()
        for directory, label in ((baseline_dir, args.production_label), (candidate_dir, args.candidate_label)):
            shutil.copy2(args.tray, directory / "tray.onnx")
            shutil.copy2(label, directory / "label.onnx")
        baseline = evaluate(baseline_dir, records, screening_module, yolo_module, args.threshold)
        candidate = evaluate(candidate_dir, records, screening_module, yolo_module, args.threshold)
    payload = {
        "version": "vision-lab-evaluation-v1",
        "evaluation_pipeline": "legacy_two_model",
        "artifact_sha256": sha256_file(args.candidate_label),
        "production_artifact_sha256": sha256_file(args.production_label),
        "tray_artifact_sha256": sha256_file(args.tray),
        "production_pipeline_replay": False,
        "onnx_parity_mismatches": args.onnx_parity_mismatches,
        "threshold": args.threshold, "records": len(records),
        "records_before_truth_adjudication": original_record_count,
        "truth_adjudication": truth_adjudication,
        "baseline": baseline, "candidate": candidate,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(payload, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
