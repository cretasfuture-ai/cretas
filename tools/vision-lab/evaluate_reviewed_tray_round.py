#!/usr/bin/env python3
"""Compare production and candidate tray boxes against one sealed human round."""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any, Sequence

import numpy as np
from PIL import Image, ImageOps


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def iou(left: Sequence[float], right: Sequence[float]) -> float:
    x0, y0 = max(left[0], right[0]), max(left[1], right[1])
    x1, y1 = min(left[2], right[2]), min(left[3], right[3])
    intersection = max(0.0, x1 - x0) * max(0.0, y1 - y0)
    left_area = max(0.0, left[2] - left[0]) * max(0.0, left[3] - left[1])
    right_area = max(0.0, right[2] - right[0]) * max(0.0, right[3] - right[1])
    union = left_area + right_area - intersection
    return intersection / union if union > 0 else 0.0


def scale_box(
    box: Sequence[float], factor: float, width: int, height: int,
) -> list[float]:
    if factor <= 0:
        raise ValueError("box scale must be positive")
    center_x = (float(box[0]) + float(box[2])) / 2.0
    center_y = (float(box[1]) + float(box[3])) / 2.0
    half_width = max(0.0, float(box[2]) - float(box[0])) * factor / 2.0
    half_height = max(0.0, float(box[3]) - float(box[1])) * factor / 2.0
    return [
        max(0.0, center_x - half_width),
        max(0.0, center_y - half_height),
        min(float(width), center_x + half_width),
        min(float(height), center_y + half_height),
    ]


def match_boxes(
    truth: list[list[float]], predicted: list[list[float]], threshold: float = 0.5,
) -> dict[str, Any]:
    candidates = sorted(
        ((iou(t, p), ti, pi) for ti, t in enumerate(truth) for pi, p in enumerate(predicted)),
        reverse=True,
    )
    truth_used: set[int] = set()
    predicted_used: set[int] = set()
    overlaps: list[float] = []
    for overlap, truth_index, predicted_index in candidates:
        if overlap < threshold:
            break
        if truth_index in truth_used or predicted_index in predicted_used:
            continue
        truth_used.add(truth_index)
        predicted_used.add(predicted_index)
        overlaps.append(overlap)
    return {
        "tp": len(overlaps), "fp": len(predicted) - len(overlaps),
        "fn": len(truth) - len(overlaps), "matched_ious": overlaps,
        "best_iou_per_truth": [
            max((iou(truth_box, predicted_box) for predicted_box in predicted), default=0.0)
            for truth_box in truth
        ],
    }


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    tp, fp, fn = (sum(int(row[key]) for row in rows) for key in ("tp", "fp", "fn"))
    overlaps = [float(value) for row in rows for value in row["matched_ious"]]
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    return {
        "images": len(rows), "tp": tp, "fp": fp, "fn": fn,
        "precision": precision, "recall": recall,
        "f1": 2 * precision * recall / (precision + recall) if precision + recall else 0.0,
        "mean_matched_iou": sum(overlaps) / len(overlaps) if overlaps else 0.0,
        "perfect_recall_images": sum(int(row["fn"] == 0) for row in rows),
    }


def evaluate(
    queue: Path, round_path: Path, production_model: Path, candidate_model: Path,
    repo_root: Path, output: Path, production_confidence: float,
    candidate_confidence: float, candidate_box_scale: float,
) -> dict[str, Any]:
    if output.exists():
        raise FileExistsError(f"refusing to overwrite reviewed-round evaluation: {output}")
    backend = str((repo_root / "backend" / "python").resolve())
    if backend not in sys.path:
        sys.path.insert(0, backend)
    from label_qc.services.yolo_detector import _OnnxDetector

    manifest = json.loads((queue / "manifest.json").read_text(encoding="utf-8"))
    round_data = json.loads(round_path.read_text(encoding="utf-8"))
    selected_ids = round_data.get("selected_ids") or []
    rows = {str(row.get("packed_stem") or ""): row for row in manifest.get("rows") or []}
    if not selected_ids or any(item_id not in rows for item_id in selected_ids):
        raise RuntimeError("review round selection does not match the queue")
    detectors = {
        "production": _OnnxDetector(production_model, imgsz=960),
        "candidate": _OnnxDetector(candidate_model, imgsz=960),
    }
    results: dict[str, list[dict[str, Any]]] = {name: [] for name in detectors}
    for item_id in selected_ids:
        row = rows[item_id]
        image_path = queue / str(row["packed_image"])
        annotation_path = queue / "annotations-human" / f"{item_id}.json"
        annotation = json.loads(annotation_path.read_text(encoding="utf-8"))
        if annotation.get("reviewed") is not True or annotation.get("source") != "human":
            raise RuntimeError(f"reviewed-round annotation is not human truth: {item_id}")
        with Image.open(image_path) as opened:
            frame = np.asarray(ImageOps.exif_transpose(opened).convert("RGB"))
        height, width = frame.shape[:2]
        truth = [
            [box[0] * width, box[1] * height, box[2] * width, box[3] * height]
            for box in annotation.get("boxes") or []
        ]
        for name, detector in detectors.items():
            confidence = (
                production_confidence if name == "production" else candidate_confidence
            )
            predicted = [detection.as_xyxy() for detection in detector.detect(frame, confidence)]
            if name == "candidate" and candidate_box_scale != 1.0:
                predicted = [
                    scale_box(box, candidate_box_scale, width, height) for box in predicted
                ]
            results[name].append({
                "packed_stem": item_id, "truth": len(truth), "predicted": len(predicted),
                "truth_boxes_xyxy": truth,
                "predicted_boxes_xyxy": predicted,
                **match_boxes(truth, predicted),
            })
    metrics = {name: summarize(values) for name, values in results.items()}
    receipt = {
        "version": "vision-lab-reviewed-tray-round-evaluation-v1",
        "queue": str(queue.resolve()), "queue_manifest_sha256": sha256(queue / "manifest.json"),
        "review_round": str(round_path.resolve()), "review_round_sha256": sha256(round_path),
        "production_confidence": production_confidence,
        "candidate_confidence": candidate_confidence,
        "candidate_box_scale": candidate_box_scale,
        "production_model": str(production_model.resolve()),
        "production_model_sha256": sha256(production_model),
        "candidate_model": str(candidate_model.resolve()),
        "candidate_model_sha256": sha256(candidate_model),
        "metrics": metrics, "details": results,
        "candidate_recall_delta": metrics["candidate"]["recall"] - metrics["production"]["recall"],
        "candidate_mean_matched_iou_delta": (
            metrics["candidate"]["mean_matched_iou"] - metrics["production"]["mean_matched_iou"]
        ),
        "protected_holdout_used": False, "production_writes": 0,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", required=True, type=Path)
    parser.add_argument("--review-round", required=True, type=Path)
    parser.add_argument("--production-model", required=True, type=Path)
    parser.add_argument("--candidate-model", required=True, type=Path)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--confidence", type=float, default=0.60)
    parser.add_argument("--candidate-confidence", type=float)
    parser.add_argument("--candidate-box-scale", type=float, default=1.0)
    args = parser.parse_args()
    receipt = evaluate(
        args.queue.resolve(), args.review_round.resolve(), args.production_model.resolve(),
        args.candidate_model.resolve(), args.repo_root.resolve(), args.output.resolve(), args.confidence,
        args.candidate_confidence if args.candidate_confidence is not None else args.confidence,
        args.candidate_box_scale,
    )
    print(json.dumps({
        "receipt": str(args.output.resolve()), "metrics": receipt["metrics"],
        "candidate_recall_delta": receipt["candidate_recall_delta"],
        "candidate_mean_matched_iou_delta": receipt["candidate_mean_matched_iou_delta"],
        "production_writes": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
