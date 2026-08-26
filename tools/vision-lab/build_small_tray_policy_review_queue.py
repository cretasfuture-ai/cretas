#!/usr/bin/env python3
"""Build a target-tray-only review queue for the v8 small-tray safety rule.

The source database and production bundle are read only.  Whole-photo human
``NO_DEFECT`` is used only to find possible counterexamples; every selected
tray remains untrusted until the reviewer confirms its labels.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import sqlite3
import sys
import time
from pathlib import Path
from typing import Any, Sequence

import numpy as np
from PIL import Image, ImageOps

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import audit_recent_label_photos as recent
from build_recent_label_review_queue import context_crop, normalized_box


RULE = "sub120_extreme_wide_production_tray"


def latest_normal_rows(state_db: Path, limit: int) -> list[dict[str, Any]]:
    connection = sqlite3.connect(recent.sqlite_read_only_uri(state_db), uri=True)
    connection.row_factory = sqlite3.Row
    try:
        source = connection.execute(
            """
            SELECT photo_id, task_id, reviewed_at, sku_code, sha256, local_path,
                   annotations_json
              FROM photos
             WHERE reviewed_at IS NOT NULL
             ORDER BY reviewed_at DESC, photo_id DESC
            """
        )
        rows: list[dict[str, Any]] = []
        for raw in source:
            row = dict(raw)
            try:
                truth = recent.human_truth(json.loads(str(row["annotations_json"])))
            except (RuntimeError, TypeError, ValueError, json.JSONDecodeError):
                continue
            if truth["kind"] != "normal":
                continue
            rows.append(row)
            if len(rows) == limit:
                break
    finally:
        connection.close()
    if len(rows) != limit:
        raise RuntimeError(f"only found {len(rows)} human-normal photos, expected {limit}")
    return rows


def normal_rows_by_photo_id(state_db: Path, photo_ids: Sequence[str]) -> list[dict[str, Any]]:
    if not photo_ids or len(set(photo_ids)) != len(photo_ids):
        raise ValueError("explicit photo ids must be non-empty and unique")
    connection = sqlite3.connect(recent.sqlite_read_only_uri(state_db), uri=True)
    connection.row_factory = sqlite3.Row
    try:
        rows = []
        for photo_id in photo_ids:
            raw = connection.execute(
                """
                SELECT photo_id, task_id, reviewed_at, sku_code, sha256, local_path,
                       annotations_json
                  FROM photos WHERE photo_id = ?
                """,
                (photo_id,),
            ).fetchone()
            if raw is None:
                raise RuntimeError(f"photo id not found: {photo_id}")
            row = dict(raw)
            truth = recent.human_truth(json.loads(str(row["annotations_json"])))
            if truth["kind"] != "normal":
                raise RuntimeError(f"explicit photo is not human-normal: {photo_id}")
            rows.append(row)
    finally:
        connection.close()
    return rows


def geometry_matches(detections: Sequence[Any]) -> bool:
    for row in detections:
        box = row.as_xyxy()
        height = float(box[3]) - float(box[1])
        if height < 120.0 and (float(box[2]) - float(box[0])) / max(1.0, height) > 2.0:
            return True
    return False


def exact_events(runtime_audit: dict[str, Any] | None) -> list[dict[str, Any]]:
    return [
        event for event in (runtime_audit or {}).get("failClosedEvents") or []
        if event.get("reason") == RULE
        and event.get("source") == "production_full"
        and event.get("previousVerdict") in recent.DEFECT_LABELS
        and event.get("finalVerdict") == recent.UNJUDGEABLE_LABEL
    ]


def released_missing_events(
    screening: Any, image_shape: Sequence[int], raw_detections: Sequence[dict[str, Any]],
) -> list[dict[str, Any]]:
    sources = {
        tuple(round(float(value), 3) for value in row["box"]): str(row.get("source"))
        for row in raw_detections
    }
    events = []
    for tray in screening.trays:
        if str(tray.verdict) not in recent.DEFECT_LABELS:
            continue
        box = [float(value) for value in tray.box]
        source = sources.get(tuple(round(value, 3) for value in box), "unknown")
        width, height = box[2] - box[0], box[3] - box[1]
        x_pad, y_pad = width * screening.params.pad_ratio, height * screening.params.pad_ratio
        crop_width = min(image_shape[1], box[2] + x_pad) - max(0.0, box[0] - x_pad)
        crop_height = min(image_shape[0], box[3] + y_pad) - max(0.0, box[1] - y_pad)
        crop_minimum = int(min(crop_width, crop_height))
        if (
            source == "production_full"
            and height < 120.0
            and width / max(1.0, height) > 2.0
            and crop_minimum >= 120
        ):
            events.append({
                "trayIndex": int(tray.index),
                "source": source,
                "reason": "released_sub120_extreme_wide_production_tray",
                "previousVerdict": str(tray.verdict),
                "finalVerdict": str(tray.verdict),
                "cropMinimumPx": int(crop_minimum),
                "box": [round(value, 3) for value in box],
            })
    return events


def perceptual_hash(frame: np.ndarray) -> int:
    return recent.perceptual_hash(frame)


def build(args: argparse.Namespace) -> tuple[Path, dict[str, Any]]:
    repo_root = args.repo_root.resolve()
    state_db = args.state_db.resolve()
    bundle = args.production_bundle.resolve()
    protected_path = args.protected_manifest.resolve()
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError(f"refusing to overwrite review queue: {output}")

    sys.path.insert(0, str(repo_root / "backend" / "python"))
    from label_qc.services.v8_pipeline import LabelQcV8Models
    from label_qc.services.yolo_detector import LabelQcYoloModels

    bundle_manifest = recent.verify_bundle(bundle)
    protected_ids, protected_hashes, protected = recent.protected_inventory(protected_path)
    rows = (
        normal_rows_by_photo_id(state_db, args.photo_id)
        if args.photo_id else latest_normal_rows(state_db, args.latest_normal_photos)
    )
    v8 = LabelQcV8Models(bundle)
    production = LabelQcYoloModels(bundle)
    if not v8.available or not production.available:
        raise RuntimeError(v8.load_error or production.load_error or "production model unavailable")
    params = v8.recommended_params()

    candidates: list[dict[str, Any]] = []
    geometry_photos = 0
    for index, row in enumerate(rows, start=1):
        source = Path(str(row["local_path"])).resolve()
        expected_hash = str(row["sha256"]).lower()
        if not source.is_file() or recent.sha256_file(source) != expected_hash:
            raise RuntimeError(f"source image drift: {source}")
        with Image.open(source) as opened:
            image = ImageOps.exif_transpose(opened).convert("RGB")
        frame = np.asarray(image)
        if not geometry_matches(production.detect_trays(frame, params.tray_conf)):
            continue
        geometry_photos += 1
        started = time.perf_counter()
        screening = v8.screen_image(frame, params)
        events = (
            released_missing_events(
                screening, frame.shape, v8._require_models().last_raw_detections,
            )
            if args.candidate_mode == "released-missing"
            else exact_events(screening.runtime_audit)
        )
        trays = {int(tray.index): tray for tray in screening.trays}
        serialized_trays = {
            int(tray["index"]): tray for tray in recent.serialize_screening(screening)["trays"]
        }
        for event in events:
            tray_index = int(event["trayIndex"])
            tray = trays.get(tray_index)
            if tray is None:
                raise RuntimeError(f"runtime event references missing tray {tray_index}")
            candidates.append({
                "photo_id": str(row["photo_id"]),
                "task_id": str(row["task_id"]),
                "sku_code": str(row["sku_code"]),
                "reviewed_at": str(row["reviewed_at"]),
                "image": str(source),
                "image_sha256": expected_hash,
                "image_size": [image.width, image.height],
                "perceptual_hash": perceptual_hash(frame),
                "protected_overlap": str(row["photo_id"]) in protected_ids or expected_hash in protected_hashes,
                "model_latency_ms": round((time.perf_counter() - started) * 1000.0, 3),
                "event": event,
                "tray": serialized_trays[tray_index],
            })
        if index % 10 == 0:
            print(json.dumps({"stage": "small_tray_scan", "completed": index, "total": len(rows)}), flush=True)

    eligible = [row for row in candidates if not row["protected_overlap"]]
    duplicate_pairs: list[list[str]] = []
    selected: list[dict[str, Any]] = []
    for row in eligible:
        duplicate = next(
            (kept for kept in selected if recent.hamming(row["perceptual_hash"], kept["perceptual_hash"]) <= recent.PHASH_DISTANCE),
            None,
        )
        if duplicate is not None:
            duplicate_pairs.append([duplicate["photo_id"], row["photo_id"]])
            continue
        selected.append(row)
    if len(selected) != args.expected_count:
        raise RuntimeError(f"review target count drift: {len(selected)} != {args.expected_count}")

    images = output / "images"
    prelabels = output / "prelabels"
    annotations = output / "annotations-human"
    for directory in (images, prelabels, annotations):
        directory.mkdir(parents=True, exist_ok=False)
    manifest_rows: list[dict[str, Any]] = []
    for index, row in enumerate(selected, start=1):
        tray = row["tray"]
        source = Path(row["image"])
        with Image.open(source) as opened:
            frame = ImageOps.exif_transpose(opened).convert("RGB")
            crop_rect = context_crop(tray["box"], frame.width, frame.height)
            derivative = frame.crop(tuple(crop_rect))
            crop_id = f"smalltray_{row['photo_id'][:8]}_t{int(tray['index']):02d}_{index:03d}"
            image_path = images / f"{crop_id}.jpg"
            derivative.save(image_path, format="JPEG", quality=94, subsampling=0)
        proposals = []
        for label in tray["labels"]:
            box = normalized_box(label["sourceBox"], crop_rect)
            if box is not None:
                proposals.append({
                    "class_id": int(label["classId"]),
                    "bbox_normalized_xyxy": box,
                    "confidence": float(label["confidence"]),
                })
        prelabel_path = prelabels / f"{crop_id}.json"
        prelabel_path.write_text(json.dumps({
            "crop_id": crop_id,
            "source": "frozen_production_v8_small_tray_policy_proposal",
            "is_ground_truth": False,
            "boxes": proposals,
        }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        manifest_rows.append({
            "crop_id": crop_id,
            "image": str(image_path.resolve()),
            "image_sha256": recent.sha256_file(image_path),
            "source_photo_id": row["photo_id"],
            "source_task_id": row["task_id"],
            "source_image_sha256": row["image_sha256"],
            "sku_code": row["sku_code"],
            "angle": "只审核画面正中央目标托盘（周边盒仅供颜色和样式参照）",
            "label_v1_verdict": str(tray["verdict"]),
            "human_qc_verdict": recent.UNJUDGEABLE_LABEL,
            "target_tray_index": int(tray["index"]),
            "target_tray_box_px": tray["box"],
            "source_crop_rect_px": crop_rect,
            "review_reason": "small_tray_fail_closed_policy_calibration",
            "prior_photo_truth": recent.NORMAL_LABEL,
            "model_previous_verdict": row["event"]["previousVerdict"],
            "model_final_verdict": row["event"]["finalVerdict"],
            "prelabel_boxes": len(proposals),
            "review_scope": "target_tray_only",
            "training_eligible": False,
        })

    scan_receipt = {
        "version": "liushanmen-small-tray-policy-scan-v1",
        "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "latest_human_normal_photos_scanned": len(rows),
        "explicit_photo_ids": list(args.photo_id),
        "candidate_mode": args.candidate_mode,
        "geometry_prefilter_photos": geometry_photos,
        "exact_policy_events": len(candidates),
        "protected_excluded": len(candidates) - len(eligible),
        "near_duplicate_excluded": len(duplicate_pairs),
        "near_duplicate_pairs": duplicate_pairs,
        "selected": selected,
        "production_bundle": str(bundle),
        "production_manifest_sha256": recent.sha256_file(bundle / "manifest.json"),
        "production_pipeline_id": bundle_manifest.get("pipelineId"),
        "protected_manifest": str(protected_path),
        "protected_manifest_sha256": recent.sha256_file(protected_path),
        "protected_records": len(protected.get("records") or []),
        "threshold_tuning_used": False,
        "state_db": str(state_db),
        "state_db_open_mode": "read_only",
        "production_writes": 0,
        "originals_modified": 0,
    }
    scan_path = output / "scan-receipt.json"
    scan_path.write_text(json.dumps(scan_receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    manifest = {
        "version": "liushanmen-small-tray-policy-review-v1",
        "queue_id": output.name,
        "queue_count": len(manifest_rows),
        "annotator_url": args.annotator_url,
        "source_scan_receipt": str(scan_path.resolve()),
        "source_scan_receipt_sha256": recent.sha256_file(scan_path),
        "preannotations_are_not_ground_truth": True,
        "every_image_requires_full_human_review": True,
        "review_scope": "only the single target tray crop; do not relabel the whole photo",
        "missing_class_review_allowed": True,
        "factory_tray_box_annotation_required": False,
        "train_only_after_human_review": True,
        "evaluation_or_production_use_allowed": False,
        "production_writes": 0,
        "originals_modified": 0,
        "rows": manifest_rows,
    }
    manifest_path = output / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return output, manifest


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--repo-root", required=True, type=Path)
    result.add_argument("--state-db", required=True, type=Path)
    result.add_argument("--production-bundle", required=True, type=Path)
    result.add_argument("--protected-manifest", required=True, type=Path)
    result.add_argument("--output", required=True, type=Path)
    result.add_argument("--latest-normal-photos", type=int, default=50)
    result.add_argument("--photo-id", action="append", default=[])
    result.add_argument(
        "--candidate-mode", choices=("fail-closed-events", "released-missing"),
        default="fail-closed-events",
    )
    result.add_argument("--expected-count", required=True, type=int)
    result.add_argument("--annotator-url", default="http://127.0.0.1:8798/")
    return result


def main() -> None:
    args = parser().parse_args()
    output, manifest = build(args)
    print(json.dumps({
        "queue": str(output),
        "count": manifest["queue_count"],
        "annotator_url": manifest["annotator_url"],
        "production_writes": 0,
        "originals_modified": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
