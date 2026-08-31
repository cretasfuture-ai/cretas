#!/usr/bin/env python3
"""Replay the current production-equivalent runtime over isolated pending photos."""
from __future__ import annotations

import argparse
import collections
import datetime as dt
import importlib.util
import json
import statistics
import sys
import time
from pathlib import Path
from typing import Any, Sequence

import numpy as np
from PIL import Image, ImageOps


HERE = Path(__file__).resolve().parent
AUDIT_SPEC = importlib.util.spec_from_file_location(
    "recent_audit", HERE / "audit_recent_label_photos.py",
)
AUDIT = importlib.util.module_from_spec(AUDIT_SPEC)
assert AUDIT_SPEC.loader is not None
AUDIT_SPEC.loader.exec_module(AUDIT)

SUSPECTS = {"MISSING_WHITE_LABEL", "MISSING_COLOR_LABEL", "BOTH_MISSING", "UNJUDGEABLE"}


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise RuntimeError(f"expected JSON object: {path}")
    return value


def iou(left: Sequence[float], right: Sequence[float]) -> float:
    x0, y0 = max(left[0], right[0]), max(left[1], right[1])
    x1, y1 = min(left[2], right[2]), min(left[3], right[3])
    intersection = max(0.0, x1 - x0) * max(0.0, y1 - y0)
    left_area = max(0.0, left[2] - left[0]) * max(0.0, left[3] - left[1])
    right_area = max(0.0, right[2] - right[0]) * max(0.0, right[3] - right[1])
    union = left_area + right_area - intersection
    return intersection / union if union else 0.0


def normalized_current(tray: Any, width: int, height: int) -> dict[str, Any]:
    return {
        "index": int(tray.index),
        "bbox": [
            round(float(tray.box[0]) / width, 6), round(float(tray.box[1]) / height, 6),
            round(float(tray.box[2]) / width, 6), round(float(tray.box[3]) / height, 6),
        ],
        "verdict": str(tray.verdict),
        "hasWhite": bool(tray.has_white), "hasColor": bool(tray.has_color),
        "ownLabelCount": int(tray.own_label_count),
        "labels": [
            {
                "classId": int(label.class_id), "confidence": round(float(label.confidence), 6),
                "bbox": [
                    round(float(label.box[0]) / width, 6), round(float(label.box[1]) / height, 6),
                    round(float(label.box[2]) / width, 6), round(float(label.box[3]) / height, 6),
                ],
            }
            for label in tray.labels
        ],
    }


def compare_trays(old_trays: list[dict[str, Any]], current_trays: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if len(old_trays) != len(current_trays):
        raise RuntimeError(f"tray count parity drift: {len(old_trays)} != {len(current_trays)}")
    current_by_index = {int(row["index"]): row for row in current_trays}
    comparisons = []
    for old in old_trays:
        index = int(old["index"])
        current = current_by_index.get(index)
        if current is None:
            raise RuntimeError(f"tray index parity drift: {index}")
        overlap = iou(old["bbox"], current["bbox"])
        if overlap < 0.99:
            raise RuntimeError(f"tray box parity drift at index {index}: IoU={overlap:.6f}")
        old_verdict = str(old["screenVerdict"])
        current_verdict = str(current["verdict"])
        comparisons.append({
            "tray_index": index, "bbox": current["bbox"], "iou": round(overlap, 6),
            "old_verdict": old_verdict, "current_verdict": current_verdict,
            "transition": f"{old_verdict}->{current_verdict}",
            "old_suspect": old_verdict in SUSPECTS,
            "current_suspect": current_verdict in SUSPECTS,
            "current_labels": current["labels"],
        })
    return comparisons


def verify_old_params(screening: dict[str, Any], params: Any, photo_id: str) -> None:
    old = screening.get("params") or {}
    expected = {
        "trayConf": float(params.tray_conf),
        "labelConf": float(params.label_conf),
        "padRatio": float(params.pad_ratio),
    }
    for key, value in expected.items():
        if abs(float(old.get(key, -1)) - value) > 1e-9:
            raise RuntimeError(f"persisted production param drift for {photo_id}: {key}")


def protected_inventory(path: Path) -> tuple[set[str], set[str]]:
    payload = load_json(path)
    rows = payload.get("records") or []
    return (
        {str(row.get("photo_id")) for row in rows if row.get("photo_id")},
        {str(row.get("image_sha256")).lower() for row in rows if row.get("image_sha256")},
    )


def select_model_rows(rows: list[dict[str, Any]], model_id: str | None) -> list[dict[str, Any]]:
    if model_id is None:
        return rows
    return [row for row in rows if str(row.get("ai_model") or "") == model_id]


def replay(args: argparse.Namespace) -> dict[str, Any]:
    manifest = load_json(args.pending_manifest.resolve())
    if manifest.get("version") != "liushanmen-pending-label-photo-manifest-v1":
        raise RuntimeError("unsupported pending manifest")
    if manifest.get("training_eligible") is not False or manifest.get("production_writes") != 0:
        raise RuntimeError("pending manifest violated quarantine contract")
    rows = select_model_rows(manifest.get("rows") or [], args.model_id)
    if args.model_id is not None and not rows:
        raise RuntimeError(f"pending manifest has no photos for model: {args.model_id}")
    if args.expected_photos is not None and len(rows) != args.expected_photos:
        raise RuntimeError(f"pending replay count drift: {len(rows)} != {args.expected_photos}")

    bundle = args.production_bundle.resolve()
    bundle_manifest = AUDIT.verify_bundle(bundle)
    if args.model_id is not None and args.model_id != str(bundle_manifest.get("modelId") or ""):
        raise RuntimeError("selected model does not match production bundle")
    protected_ids, protected_hashes = protected_inventory(args.protected_manifest.resolve())
    overlap = [
        str(row["photo_id"]) for row in rows
        if str(row["photo_id"]) in protected_ids
        or str(row["image_sha256"]).lower() in protected_hashes
    ]
    if overlap:
        raise RuntimeError(f"pending manifest overlaps protected holdout: {overlap}")

    repo_root = args.repo_root.resolve()
    sys.path.insert(0, str(repo_root / "backend" / "python"))
    from label_qc.services.v8_pipeline import LabelQcV8Models

    runtime = LabelQcV8Models(bundle)
    if not runtime.available:
        raise RuntimeError(runtime.load_error or "production runtime unavailable")
    params = runtime.recommended_params()
    details = []
    all_transitions: collections.Counter[str] = collections.Counter()
    latencies = []
    for position, row in enumerate(rows, start=1):
        photo_id = str(row["photo_id"])
        if str(row.get("ai_model") or "") != str(bundle_manifest.get("modelId") or ""):
            raise RuntimeError(f"persisted model binding drift: {photo_id}")
        image = Path(str(row["image"])).resolve()
        if not image.is_file() or AUDIT.sha256_file(image) != str(row["image_sha256"]).lower():
            raise RuntimeError(f"pending image binding drift: {photo_id}")
        screening = row["screening_detail"]
        verify_old_params(screening, params, photo_id)
        with Image.open(image) as opened:
            frame = np.asarray(ImageOps.exif_transpose(opened).convert("RGB"))
        height, width = frame.shape[:2]
        if width != int(row["image_width"]) or height != int(row["image_height"]):
            raise RuntimeError(f"pending image dimension drift: {photo_id}")
        started = time.perf_counter()
        result = runtime.screen_image(frame, params)
        latency_ms = (time.perf_counter() - started) * 1000.0
        latencies.append(latency_ms)
        current_trays = [normalized_current(tray, width, height) for tray in result.trays]
        comparisons = compare_trays(screening["trays"], current_trays)
        all_transitions.update(row["transition"] for row in comparisons)
        residual = [row for row in comparisons if row["current_suspect"]]
        details.append({
            "photo_id": photo_id, "task_id": str(row["task_id"]),
            "sku_code": str(row["sku_code"]), "image": str(image),
            "image_sha256": str(row["image_sha256"]), "image_size": [width, height],
            "old_suspect_count": sum(row["old_suspect"] for row in comparisons),
            "current_suspect_count": len(residual),
            "resolved_suspect_count": sum(
                row["old_suspect"] and not row["current_suspect"] for row in comparisons
            ),
            "new_suspect_count": sum(
                not row["old_suspect"] and row["current_suspect"] for row in comparisons
            ),
            "latency_ms": round(latency_ms, 3),
            "comparisons": comparisons,
            "residual_targets": residual,
            "runtime_audit": result.runtime_audit,
        })
        print(json.dumps({
            "stage": "pending_replay", "completed": position, "total": len(rows),
            "photo_id": photo_id, "residual_targets": len(residual),
        }), flush=True)

    residual_photos = [row for row in details if row["residual_targets"]]
    payload = {
        "version": "liushanmen-pending-production-equivalent-replay-v1",
        "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "inputs": {
            "pending_manifest": str(args.pending_manifest.resolve()),
            "pending_manifest_sha256": AUDIT.sha256_file(args.pending_manifest.resolve()),
            "production_bundle": str(bundle),
            "production_manifest_sha256": AUDIT.sha256_file(bundle / "manifest.json"),
            "production_model_id": bundle_manifest.get("modelId"),
            "selected_persisted_model_id": args.model_id,
            "protected_manifest": str(args.protected_manifest.resolve()),
            "protected_manifest_sha256": AUDIT.sha256_file(args.protected_manifest.resolve()),
            "threshold_tuning_used": False,
            "params": {
                "trayConf": params.tray_conf, "labelConf": params.label_conf,
                "padRatio": params.pad_ratio, "cropWidth": params.crop_width,
                "minCropPx": params.min_crop_px,
            },
        },
        "inventory": {
            "photos": len(details), "tasks": len({row["task_id"] for row in details}),
            "protected_overlap_photos": 0,
            "old_suspect_photos": sum(row["old_suspect_count"] > 0 for row in details),
            "residual_suspect_photos": len(residual_photos),
            "old_suspect_targets": sum(row["old_suspect_count"] for row in details),
            "residual_suspect_targets": sum(row["current_suspect_count"] for row in details),
            "resolved_suspect_targets": sum(row["resolved_suspect_count"] for row in details),
            "new_suspect_targets": sum(row["new_suspect_count"] for row in details),
        },
        "transitions": dict(sorted(all_transitions.items())),
        "latency_ms": {
            "mean": round(statistics.mean(latencies), 3) if latencies else None,
            "p95": round(float(np.percentile(latencies, 95)), 3) if latencies else None,
            "max": round(max(latencies), 3) if latencies else None,
        },
        "review": {
            "needed": bool(residual_photos),
            "photo_ids": [row["photo_id"] for row in residual_photos],
            "target_count": sum(len(row["residual_targets"]) for row in residual_photos),
            "scope": "single target tray only",
        },
        "details": details,
        "human_truth_available": False,
        "training_allowed": False,
        "promotion_gate_evaluable": False,
        "production_reads": 0,
        "production_writes": 0,
        "originals_modified": 0,
        "production_changed": False,
        "deployment_started": False,
    }
    return payload


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--pending-manifest", required=True, type=Path)
    parser.add_argument("--production-bundle", required=True, type=Path)
    parser.add_argument("--protected-manifest", required=True, type=Path)
    parser.add_argument("--expected-photos", type=int)
    parser.add_argument("--model-id")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError(f"refusing to overwrite replay receipt: {output}")
    payload = replay(args)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "output": str(output), "inventory": payload["inventory"],
        "transitions": payload["transitions"], "latency_ms": payload["latency_ms"],
        "review": payload["review"], "training_allowed": False,
        "production_writes": 0, "production_changed": False,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
