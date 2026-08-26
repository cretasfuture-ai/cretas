#!/usr/bin/env python3
"""Evaluate frozen shallow-tray extended ownership on an independent audit receipt."""
from __future__ import annotations

import argparse
import datetime as dt
import importlib.util
import json
import sys
from collections import Counter
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "extended_diagnostic", HERE / "label_small_tray_extended_ownership_diagnostic.py",
)
EXTENDED = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(EXTENDED)

DEFECTS = {"MISSING_WHITE_LABEL", "MISSING_COLOR_LABEL", "BOTH_MISSING"}


def evaluate_candidate(details: list[dict[str, Any]]) -> dict[str, Any]:
    defect_total = defect_hits = normal_photos = normal_false_flags = 0
    for detail in details:
        human = detail["humanTruth"]
        if human["kind"] == "normal":
            normal_photos += 1
            normal_false_flags += int(any(
                tray["candidate_verdict"] in DEFECTS for tray in detail["candidate_trays"]
            ))
        for defect in detail.get("defectInstances") or []:
            defect_total += 1
            indices = {int(value) for value in defect.get("coveringTrayIndices") or []}
            defect_hits += int(any(
                int(tray["index"]) in indices and tray["candidate_verdict"] == defect["label"]
                for tray in detail["candidate_trays"]
            ))
    return {
        "defectInstances": defect_total,
        "defectHits": defect_hits,
        "defectRecall": round(defect_hits / defect_total, 6) if defect_total else None,
        "normalPhotos": normal_photos,
        "normalFalseFlags": normal_false_flags,
    }


def run(
    repo_root: Path, bundle: Path, audit_path: Path, output: Path,
    bottom_ratio: float, threshold: float,
) -> dict[str, Any]:
    audit = EXTENDED.load(audit_path)
    if audit.get("version") != "liushanmen-recent-label-photo-audit-v1":
        raise RuntimeError("unsupported recent audit receipt")
    if audit.get("productionWrites") != 0 or audit.get("originalsModified") != 0:
        raise RuntimeError("source audit violated read-only contract")
    if output.exists():
        raise FileExistsError(f"refusing to overwrite evaluation: {output}")
    sys.path.insert(0, str((repo_root / "backend" / "python").resolve()))
    from label_qc.services.screening import resize_crop
    from label_qc.services.yolo_detector import LabelQcYoloModels

    models = LabelQcYoloModels(bundle.resolve())
    if not models.available:
        raise RuntimeError(models.load_error or "production model unavailable")
    evaluated = []
    transition_counts: Counter[str] = Counter()
    for detail in audit.get("details") or []:
        image_path = Path(str(detail["image"])).resolve()
        if EXTENDED.sha256_file(image_path) != str(detail["imageSha256"]):
            raise RuntimeError(f"source image drift: {detail['photoId']}")
        frame = EXTENDED.load_rgb_image(image_path)
        candidate_trays = []
        for tray in detail["productionBlind"]["trays"]:
            box = [float(value) for value in tray["box"]]
            width, height = box[2] - box[0], box[3] - box[1]
            shallow = height < 120.0 and height > 0.0 and width / height > 2.0
            candidate_verdict = str(tray["verdict"])
            labels = []
            if shallow and candidate_verdict == "UNJUDGEABLE":
                physical = EXTENDED.extended_box(box, frame.shape[0], bottom_ratio)
                rect = EXTENDED.crop_rect(physical, frame.shape[1], frame.shape[0], 0.14)
                crop = frame[rect[1]:rect[3], rect[0]:rect[2]]
                resized = resize_crop(crop, 640)
                detections = models.detect_labels(resized, threshold)
                sx = (rect[2] - rect[0]) / max(1, resized.shape[1])
                sy = (rect[3] - rect[1]) / max(1, resized.shape[0])
                for detection in detections:
                    cx = rect[0] + ((detection.x0 + detection.x1) / 2) * sx
                    cy = rect[1] + ((detection.y0 + detection.y1) / 2) * sy
                    if physical[0] <= cx <= physical[2] and physical[1] <= cy <= physical[3]:
                        labels.append(int(detection.class_id))
                proposed = EXTENDED.verdict(set(labels))
                candidate_verdict = "UNJUDGEABLE" if proposed == "BOTH_MISSING" else proposed
                transition_counts[f"{tray['verdict']}->{candidate_verdict}"] += 1
            candidate_trays.append({
                "index": int(tray["index"]), "baseline_verdict": tray["verdict"],
                "candidate_verdict": candidate_verdict, "shallow": shallow,
                "extended_label_classes": sorted(set(labels)),
            })
        evaluated.append({
            "photoId": detail["photoId"], "humanTruth": detail["humanTruth"],
            "defectInstances": detail.get("defectInstances") or [],
            "candidate_trays": candidate_trays,
        })
    candidate = evaluate_candidate(evaluated)
    baseline = audit["blindEvaluation"]
    gate = (
        int(candidate["defectHits"]) >= int(baseline["defectHits"])
        and int(candidate["normalFalseFlags"]) <= int(baseline["normalFalseFlags"])
    )
    receipt = {
        "version": "liushanmen-small-tray-extended-ownership-evaluation-v1",
        "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "frozen_before_evaluation": True,
        "independent_from_calibration_photos": True,
        "promotion_evidence": False,
        "params": {"bottom_ratio": bottom_ratio, "threshold": threshold},
        "baseline": {
            "defectInstances": baseline["defectInstances"],
            "defectHits": baseline["defectHits"],
            "defectRecall": baseline["defectRecall"],
            "normalPhotos": baseline["normalPhotos"],
            "normalFalseFlags": baseline["normalFalseFlags"],
        },
        "candidate": candidate,
        "transitionCounts": dict(transition_counts),
        "evaluation_gate_passed": gate,
        "next_gate": "production-equivalent replay and protected holdout",
        "details": evaluated,
        "production_writes": 0,
        "originals_modified": 0,
        "production_changed": False,
        "deployment_started": False,
        "inputs": {
            "audit": {"path": str(audit_path.resolve()), "sha256": EXTENDED.sha256_file(audit_path)},
            "bundle": str(bundle.resolve()),
        },
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--audit", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--bottom-ratio", type=float, default=0.75)
    parser.add_argument("--threshold", type=float, default=0.20)
    args = parser.parse_args()
    if not 0.0 <= args.bottom_ratio <= 2.0 or not 0.0 < args.threshold < 1.0:
        raise ValueError("invalid candidate parameters")
    receipt = run(
        args.repo_root.resolve(), args.bundle.resolve(), args.audit.resolve(),
        args.output.resolve(), args.bottom_ratio, args.threshold,
    )
    print(json.dumps({key: receipt[key] for key in (
        "params", "baseline", "candidate", "transitionCounts",
        "evaluation_gate_passed", "next_gate", "production_changed",
    )}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
