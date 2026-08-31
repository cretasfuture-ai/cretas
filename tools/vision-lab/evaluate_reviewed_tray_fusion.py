#!/usr/bin/env python3
"""Evaluate production-preserving lower-edge tray fusion on one human round."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image, ImageOps

import evaluate_reviewed_tray_round as reviewed
import label_qc_tray_edge_context_eval as edge


class _TrayDetectorAdapter:
    def __init__(self, detector: Any) -> None:
        self.detector = detector

    def detect_trays(self, image, confidence: float) -> list[Any]:
        return list(self.detector.detect(image, confidence))


class _RuntimeTrayAdapter:
    def __init__(self, models: Any) -> None:
        self.models = models
        self.last_raw_detections: list[dict[str, Any]] = []
        self.last_stats: dict[str, Any] = {}

    def detect_trays(self, image, confidence: float) -> list[Any]:
        detections = list(self.models.detect_trays(image, confidence))
        self.last_raw_detections = list(self.models.last_raw_detections)
        self.last_stats = dict(self.models.last_stats)
        return detections


def evaluate(args: argparse.Namespace) -> dict[str, Any]:
    if args.output.exists():
        raise FileExistsError(f"refusing to overwrite fusion evaluation: {args.output}")
    backend = str((args.repo_root / "backend" / "python").resolve())
    if backend not in sys.path:
        sys.path.insert(0, backend)
    from label_qc.services.v8_pipeline import LabelQcV8Models
    from label_qc.services.yolo_detector import Detection, _OnnxDetector

    manifest = json.loads((args.queue / "manifest.json").read_text(encoding="utf-8"))
    review_round = json.loads(args.review_round.read_text(encoding="utf-8"))
    selected_ids = review_round.get("selected_ids") or []
    queue_rows = {
        str(row.get("packed_stem") or ""): row for row in manifest.get("rows") or []
    }
    if not selected_ids or any(item_id not in queue_rows for item_id in selected_ids):
        raise RuntimeError("review round selection does not match the queue")

    runtime = LabelQcV8Models(args.runtime_bundle)
    if not runtime.available:
        raise RuntimeError(runtime.load_error or "V8 runtime bundle unavailable")
    inner = runtime._require_models()
    production = _RuntimeTrayAdapter(inner)
    candidate = _TrayDetectorAdapter(_OnnxDetector(args.candidate_model, imgsz=960))
    fused = edge.EdgeContextTrayModels(
        production,
        Detection,
        edge_base=candidate,
        edge_confidence=args.edge_confidence,
        skip_full_layout_count=args.skip_full_layout_count,
        preserve_base_detections=True,
    ) if args.candidate_scope == "lower-edge" else edge.FullFrameSupplementTrayModels(
        production,
        candidate,
        candidate_confidence=args.edge_confidence,
    )

    results: dict[str, list[dict[str, Any]]] = {"production": [], "fusion": []}
    total_edge_raw = total_edge_accepted = total_edge_kept = 0
    for item_id in selected_ids:
        row = queue_rows[item_id]
        image_path = args.queue / str(row["packed_image"])
        annotation_path = args.queue / "annotations-human" / f"{item_id}.json"
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
        production_rows = production.detect_trays(frame, args.production_confidence)
        production_boxes = [detection.as_xyxy() for detection in production_rows]
        if args.candidate_scope == "full-frame":
            fused_rows = fused.detect_trays(frame, args.production_confidence)
            fusion_boxes = [detection.as_xyxy() for detection in fused_rows]
            stats = dict(fused.last_stats)
            edge_raw = int(stats.get("candidate_full_frame_detections") or 0)
            edge_kept = int(stats.get("candidate_supplements_kept") or 0)
            edge_accepted = edge_kept
        else:
            fusion_boxes = [
                detection.as_xyxy()
                for detection in fused.detect_trays(frame, args.production_confidence)
            ]
            stats = dict(fused.last_stats)
            edge_raw = int(stats.get("edge_raw_detections") or 0)
            edge_accepted = int(stats.get("edge_accepted_before_dedup") or 0)
            edge_kept = max(
                0, len(fusion_boxes) - int(stats.get("full_frame_detections") or 0),
            )
        total_edge_raw += edge_raw
        total_edge_accepted += edge_accepted
        total_edge_kept += edge_kept
        for name, boxes in (("production", production_boxes), ("fusion", fusion_boxes)):
            results[name].append({
                "packed_stem": item_id,
                "truth": len(truth),
                "predicted": len(boxes),
                **reviewed.match_boxes(truth, boxes),
                **({"fusion_stats": stats} if name == "fusion" else {}),
            })

    metrics = {name: reviewed.summarize(rows) for name, rows in results.items()}
    receipt = {
        "version": "vision-lab-reviewed-tray-preserving-fusion-v1",
        "queue": str(args.queue),
        "queue_manifest_sha256": reviewed.sha256(args.queue / "manifest.json"),
        "review_round": str(args.review_round),
        "review_round_sha256": reviewed.sha256(args.review_round),
        "runtime_bundle": str(args.runtime_bundle),
        "runtime_manifest_sha256": reviewed.sha256(args.runtime_bundle / "manifest.json"),
        "candidate_model": str(args.candidate_model),
        "candidate_model_sha256": reviewed.sha256(args.candidate_model),
        "production_confidence": args.production_confidence,
        "edge_confidence": args.edge_confidence,
        "candidate_scope": args.candidate_scope,
        "skip_full_layout_count": args.skip_full_layout_count,
        "preserve_base_detections": True,
        "metrics": metrics,
        "details": results,
        "fusion_recall_delta": metrics["fusion"]["recall"] - metrics["production"]["recall"],
        "fusion_mean_matched_iou_delta": (
            metrics["fusion"]["mean_matched_iou"]
            - metrics["production"]["mean_matched_iou"]
        ),
        "edge_raw_detections": total_edge_raw,
        "edge_accepted_before_dedup": total_edge_accepted,
        "edge_supplements_kept": total_edge_kept,
        "protected_holdout_used": False,
        "production_writes": 0,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", required=True, type=Path)
    parser.add_argument("--review-round", required=True, type=Path)
    parser.add_argument("--runtime-bundle", required=True, type=Path)
    parser.add_argument("--candidate-model", required=True, type=Path)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--production-confidence", type=float, default=0.60)
    parser.add_argument("--edge-confidence", type=float, default=0.30)
    parser.add_argument(
        "--candidate-scope", choices=("lower-edge", "full-frame"),
        default="lower-edge",
    )
    parser.add_argument("--skip-full-layout-count", type=int, default=301)
    args = parser.parse_args()
    for name in ("queue", "review_round", "runtime_bundle", "candidate_model", "repo_root"):
        setattr(args, name, getattr(args, name).resolve())
    args.output = args.output.resolve()
    receipt = evaluate(args)
    print(json.dumps({
        "receipt": str(args.output),
        "metrics": receipt["metrics"],
        "fusion_recall_delta": receipt["fusion_recall_delta"],
        "fusion_mean_matched_iou_delta": receipt["fusion_mean_matched_iou_delta"],
        "edge_supplements_kept": receipt["edge_supplements_kept"],
        "production_writes": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
