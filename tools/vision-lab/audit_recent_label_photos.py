#!/usr/bin/env python3
"""Read-only audit and frozen-production blind replay for newly collected photos."""
from __future__ import annotations

import argparse
import collections
import datetime as dt
import hashlib
import json
import sqlite3
import sys
import time
from pathlib import Path
from typing import Any, Iterable, Sequence

import cv2
import numpy as np
from PIL import Image, ImageOps


DEFECT_LABELS = {"MISSING_WHITE_LABEL", "MISSING_COLOR_LABEL", "BOTH_MISSING"}
NORMAL_LABEL = "NO_DEFECT"
UNJUDGEABLE_LABEL = "UNJUDGEABLE"
PHASH_DISTANCE = 6


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sqlite_read_only_uri(path: Path) -> str:
    return f"file:{path.resolve().as_posix()}?mode=ro"


def load_rows(state_db: Path, after: tuple[str, str], before: tuple[str, str] | None) -> list[dict[str, Any]]:
    if not state_db.is_file():
        raise FileNotFoundError(state_db)
    where = "(reviewed_at > ? OR (reviewed_at = ? AND photo_id > ?))"
    values: list[str] = [after[0], after[0], after[1]]
    if before is not None:
        where += " AND (reviewed_at < ? OR (reviewed_at = ? AND photo_id <= ?))"
        values.extend([before[0], before[0], before[1]])
    query = f"""
        SELECT photo_id, task_id, reviewed_at, collected_at, sku_code, object_ref,
               sha256, local_path, annotations_json
          FROM photos
         WHERE {where}
         ORDER BY reviewed_at, photo_id
    """
    connection = sqlite3.connect(sqlite_read_only_uri(state_db), uri=True)
    connection.row_factory = sqlite3.Row
    try:
        rows = [dict(row) for row in connection.execute(query, values).fetchall()]
    finally:
        connection.close()
    if not rows:
        raise RuntimeError("watermark interval contains no photos")
    return rows


def historical_sha_duplicates(state_db: Path, batch_hashes: Iterable[str]) -> dict[str, list[str]]:
    hashes = sorted({str(value).lower() for value in batch_hashes})
    placeholders = ",".join("?" for _ in hashes)
    connection = sqlite3.connect(sqlite_read_only_uri(state_db), uri=True)
    try:
        rows = connection.execute(
            f"SELECT sha256, photo_id FROM photos WHERE lower(sha256) IN ({placeholders}) ORDER BY sha256, photo_id",
            hashes,
        ).fetchall()
    finally:
        connection.close()
    grouped: dict[str, list[str]] = collections.defaultdict(list)
    for image_hash, photo_id in rows:
        grouped[str(image_hash).lower()].append(str(photo_id))
    return {key: value for key, value in grouped.items() if len(value) > 1}


def parse_watermark(value: str) -> tuple[str, str]:
    parts = value.split(",", 1)
    if len(parts) != 2 or not parts[0] or not parts[1]:
        raise ValueError("watermark must be reviewed_at,photo_id")
    return parts[0], parts[1]


def perceptual_hash(frame: np.ndarray) -> int:
    gray = cv2.cvtColor(frame, cv2.COLOR_RGB2GRAY)
    sample = cv2.resize(gray, (32, 32), interpolation=cv2.INTER_AREA).astype(np.float32)
    low = cv2.dct(sample)[:8, :8].reshape(-1)
    median = float(np.median(low[1:]))
    bits = low > median
    value = 0
    for bit in bits:
        value = (value << 1) | int(bit)
    return value


def hamming(left: int, right: int) -> int:
    return (left ^ right).bit_count()


def near_duplicate_clusters(rows: Sequence[dict[str, Any]]) -> list[dict[str, Any]]:
    parent = list(range(len(rows)))

    def find(index: int) -> int:
        while parent[index] != index:
            parent[index] = parent[parent[index]]
            index = parent[index]
        return index

    def union(left: int, right: int) -> None:
        left_root, right_root = find(left), find(right)
        if left_root != right_root:
            parent[right_root] = left_root

    for left in range(len(rows)):
        for right in range(left + 1, len(rows)):
            if hamming(int(rows[left]["perceptual_hash"]), int(rows[right]["perceptual_hash"])) <= PHASH_DISTANCE:
                union(left, right)
    groups: dict[int, list[dict[str, Any]]] = collections.defaultdict(list)
    for index, row in enumerate(rows):
        groups[find(index)].append(row)
    return [
        {
            "photo_ids": sorted(str(row["photo_id"]) for row in group),
            "task_ids": sorted({str(row["task_id"]) for row in group}),
            "size": len(group),
        }
        for group in groups.values()
        if len(group) > 1
    ]


def containment(candidate: Sequence[float], truth: Sequence[float]) -> float:
    x0, y0 = max(candidate[0], truth[0]), max(candidate[1], truth[1])
    x1, y1 = min(candidate[2], truth[2]), min(candidate[3], truth[3])
    intersection = max(0.0, x1 - x0) * max(0.0, y1 - y0)
    truth_area = max(0.0, truth[2] - truth[0]) * max(0.0, truth[3] - truth[1])
    return intersection / truth_area if truth_area else 0.0


def human_truth(annotations: Any) -> dict[str, Any]:
    if not isinstance(annotations, list) or not annotations:
        raise RuntimeError("photo lacks annotation rows")
    reviewed = [row for row in annotations if isinstance(row, dict) and row.get("human_label") is not None]
    if not reviewed:
        raise RuntimeError("photo lacks human-reviewed truth")
    defects = [row for row in reviewed if str(row.get("human_label")) in DEFECT_LABELS]
    labels = {str(row.get("human_label")) for row in reviewed}
    if defects:
        return {"kind": "defect", "labels": sorted(labels), "defects": defects, "reviewRows": reviewed}
    if labels == {NORMAL_LABEL}:
        return {"kind": "normal", "labels": [NORMAL_LABEL], "defects": [], "reviewRows": reviewed}
    if labels <= {NORMAL_LABEL, UNJUDGEABLE_LABEL} and UNJUDGEABLE_LABEL in labels:
        return {"kind": "unjudgeable", "labels": sorted(labels), "defects": [], "reviewRows": reviewed}
    raise RuntimeError(f"unsupported human truth combination: {sorted(labels)}")


def glare_ratio(frame: np.ndarray) -> float:
    hsv = cv2.cvtColor(frame, cv2.COLOR_RGB2HSV)
    return float(np.mean((hsv[:, :, 2] >= 245) & (hsv[:, :, 1] <= 40)))


def serialize_screening(result: Any) -> dict[str, Any]:
    trays = [
        {
            "index": int(tray.index),
            "box": [round(float(value), 3) for value in tray.box],
            "confidence": round(float(tray.confidence), 6),
            "verdict": str(tray.verdict),
            "hasWhite": bool(tray.has_white),
            "hasColor": bool(tray.has_color),
            "ownLabelCount": int(tray.own_label_count),
            "labels": [
                {
                    "classId": int(label.class_id),
                    "confidence": round(float(label.confidence), 6),
                    "sourceBox": [round(float(value), 3) for value in label.box],
                }
                for label in tray.labels
            ],
        }
        for tray in result.trays
    ]
    return {
        "trayCount": len(trays),
        "verdictCounts": dict(collections.Counter(str(tray.verdict) for tray in result.trays)),
        "trays": trays,
        "suspects": [tray for tray in trays if tray["verdict"] in DEFECT_LABELS],
        "runtimeAudit": result.runtime_audit,
    }


def verify_bundle(bundle: Path) -> dict[str, Any]:
    manifest_path = bundle / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    for name, item in (manifest.get("models") or {}).items():
        model = bundle / str(item.get("file") or "")
        if not model.is_file() or sha256_file(model) != str(item.get("sha256") or "").lower():
            raise RuntimeError(f"production bundle drift: {name}")
    return manifest


def protected_inventory(path: Path) -> tuple[set[str], set[str], dict[str, Any]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    records = payload.get("records") or []
    return (
        {str(row.get("photo_id")) for row in records if row.get("photo_id")},
        {str(row.get("image_sha256")).lower() for row in records if row.get("image_sha256")},
        payload,
    )


def audit(args: argparse.Namespace) -> dict[str, Any]:
    repo_root = args.repo_root.resolve()
    sys.path.insert(0, str(repo_root / "backend" / "python"))
    from label_qc.services.capture_quality import evaluate_capture_quality
    from label_qc.services.v8_pipeline import LabelQcV8Models
    from label_qc.services.yolo_detector import LabelQcYoloModels

    bundle = args.production_bundle.resolve()
    manifest = verify_bundle(bundle)
    protected_ids, protected_hashes, protected = protected_inventory(args.protected_manifest.resolve())
    rows = load_rows(args.state_db.resolve(), parse_watermark(args.after), parse_watermark(args.before) if args.before else None)
    if args.expected_photos is not None and len(rows) != args.expected_photos:
        raise RuntimeError(f"recent photo count drift: {len(rows)} != {args.expected_photos}")

    v8 = LabelQcV8Models(bundle)
    production = LabelQcYoloModels(bundle)
    if not v8.available or not production.available:
        raise RuntimeError(v8.load_error or production.load_error or "production model unavailable")
    params = v8.recommended_params()
    details: list[dict[str, Any]] = []
    seen_hashes: set[str] = set()
    for index, row in enumerate(rows, start=1):
        image = Path(str(row["local_path"])).resolve()
        expected_hash = str(row["sha256"]).lower()
        if not image.is_file() or sha256_file(image) != expected_hash:
            raise RuntimeError(f"cached image drift: {image}")
        if expected_hash in seen_hashes:
            raise RuntimeError(f"exact duplicate content in recent batch: {expected_hash}")
        seen_hashes.add(expected_hash)
        with Image.open(image) as opened:
            frame = np.asarray(ImageOps.exif_transpose(opened).convert("RGB"))

        # Model inference is completed with frozen production parameters before human truth is parsed.
        started = time.perf_counter()
        screening = v8.screen_image(frame, params)
        latency_ms = (time.perf_counter() - started) * 1000.0
        capture = evaluate_capture_quality(frame, production.detect_trays(frame, params.tray_conf))
        model_result = serialize_screening(screening)
        truth = human_truth(json.loads(str(row["annotations_json"])))
        height, width = frame.shape[:2]
        defect_rows: list[dict[str, Any]] = []
        for defect in truth["defects"]:
            normalized = defect.get("bbox")
            if not isinstance(normalized, list) or len(normalized) != 4:
                defect_rows.append({"label": defect["human_label"], "bbox": None, "hit": False})
                continue
            box = [normalized[0] * width, normalized[1] * height, normalized[2] * width, normalized[3] * height]
            covering = [tray for tray in screening.trays if containment(tray.box, box) >= 0.5]
            defect_rows.append({
                "label": str(defect["human_label"]),
                "bbox": [round(float(value), 3) for value in box],
                "edgeScene": min(normalized[0], normalized[1], 1 - normalized[2], 1 - normalized[3]) <= 0.02,
                "coveringTrayIndices": [int(tray.index) for tray in covering],
                "hit": any(str(tray.verdict) == str(defect["human_label"]) for tray in covering),
            })
        details.append({
            "photoId": str(row["photo_id"]),
            "taskId": str(row["task_id"]),
            "skuCode": row["sku_code"],
            "reviewedAt": str(row["reviewed_at"]),
            "collectedAt": str(row["collected_at"]),
            "image": str(image),
            "imageSha256": expected_hash,
            "imageSize": [width, height],
            "perceptual_hash": perceptual_hash(frame),
            "glareRatio": round(glare_ratio(frame), 6),
            "highGlareScene": glare_ratio(frame) >= 0.02,
            "protectedOverlap": str(row["photo_id"]) in protected_ids or expected_hash in protected_hashes,
            "captureQuality": capture.as_dict(),
            "modelLatencyMs": round(latency_ms, 3),
            "productionBlind": model_result,
            "humanTruth": truth,
            "defectInstances": defect_rows,
        })
        if index % 5 == 0 or index == len(rows):
            print(json.dumps({"stage": "blind_audit", "completed": index, "total": len(rows)}), flush=True)

    near = near_duplicate_clusters(details)
    historical_duplicates = historical_sha_duplicates(
        args.state_db.resolve(), (row["imageSha256"] for row in details),
    )
    defects = [instance for row in details for instance in row["defectInstances"]]
    normals = [row for row in details if row["humanTruth"]["kind"] == "normal"]
    unjudgeable = [row for row in details if row["humanTruth"]["kind"] == "unjudgeable"]
    quality_reasons = collections.Counter(
        row["captureQuality"]["reasonCode"] or "ACCEPTED" for row in details
    )
    review_photo_ids = sorted({
        row["photoId"]
        for row in details
        if row["humanTruth"]["kind"] == "unjudgeable"
        or any(not instance["hit"] for instance in row["defectInstances"])
        or (row["humanTruth"]["kind"] == "normal" and bool(row["productionBlind"]["suspects"]))
    })
    eligible = [
        row for row in details
        if row["captureQuality"]["accepted"]
        and not row["protectedOverlap"]
        and row["humanTruth"]["kind"] != "unjudgeable"
    ]
    payload = {
        "version": "liushanmen-recent-label-photo-audit-v1",
        "createdAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "scope": {
            "factoryId": "LIUSHANMEN",
            "afterExclusive": args.after,
            "beforeInclusive": args.before,
            "stateDb": str(args.state_db.resolve()),
            "stateDbOpenMode": "read_only",
        },
        "inputs": {
            "productionBundle": str(bundle),
            "productionPipelineId": manifest.get("pipelineId"),
            "productionModelId": manifest.get("modelId"),
            "productionManifestSha256": sha256_file(bundle / "manifest.json"),
            "productionParams": {
                "trayConf": params.tray_conf,
                "labelConf": params.label_conf,
                "padRatio": params.pad_ratio,
                "cropWidth": params.crop_width,
                "minCropPx": params.min_crop_px,
            },
            "thresholdTuningUsed": False,
            "protectedManifest": str(args.protected_manifest.resolve()),
            "protectedManifestSha256": sha256_file(args.protected_manifest.resolve()),
            "protectedRecords": len(protected.get("records") or []),
        },
        "inventory": {
            "uploaded": len(details),
            "localHashVerified": len(details),
            "exactDuplicatePhotos": 0,
            "exactDuplicateWithHistoricalPhotos": sum(len(value) - 1 for value in historical_duplicates.values()),
            "historicalDuplicateGroups": historical_duplicates,
            "nearDuplicateClusters": near,
            "tasks": len({row["taskId"] for row in details}),
            "skuCoverage": dict(sorted(collections.Counter(str(row["skuCode"]) for row in details).items())),
            "quality": dict(sorted(quality_reasons.items())),
            "protectedOverlapPhotos": sum(row["protectedOverlap"] for row in details),
            "humanNormalPhotos": len(normals),
            "humanUnjudgeablePhotos": len(unjudgeable),
            "humanDefectPhotos": sum(row["humanTruth"]["kind"] == "defect" for row in details),
            "humanDefectInstances": len(defects),
            "miningEligiblePhotos": len(eligible),
            "miningRejectedPhotos": len(details) - len(eligible),
            "directTrainingEligiblePhotos": 0,
            "directTrainingEligibilityReason": "whole-photo human verdicts do not promote model boxes to human box truth",
            "highGlarePhotos": sum(row["highGlareScene"] for row in details),
        },
        "blindEvaluation": {
            "productionModelFrozenBeforeTruthComparison": True,
            "defectInstances": len(defects),
            "defectHits": sum(instance["hit"] for instance in defects),
            "defectRecall": round(sum(instance["hit"] for instance in defects) / len(defects), 6) if defects else None,
            "byClass": {
                label: {
                    "total": sum(instance["label"] == label for instance in defects),
                    "hits": sum(instance["label"] == label and instance["hit"] for instance in defects),
                }
                for label in sorted({instance["label"] for instance in defects})
            },
            "normalPhotos": len(normals),
            "normalFlaggedPhotos": sum(bool(row["productionBlind"]["suspects"]) for row in normals),
            "normalFalseFlags": sum(len(row["productionBlind"]["suspects"]) for row in normals),
            "edgeDefectInstances": sum(instance.get("edgeScene", False) for instance in defects),
            "edgeDefectHits": sum(instance.get("edgeScene", False) and instance["hit"] for instance in defects),
            "blurryPhotos": sum(row["captureQuality"]["reasonCode"] == "PHOTO_BLURRY" for row in details),
            "highGlarePhotos": sum(row["highGlareScene"] for row in details),
        },
        "review": {
            "needed": bool(review_photo_ids),
            "photoIds": review_photo_ids,
            "count": len(review_photo_ids),
            "reason": "model suspicion, missed human defect, or human unjudgeable only",
        },
        "details": details,
        "productionWrites": 0,
        "originalsModified": 0,
        "trainingStarted": False,
        "deploymentStarted": False,
    }
    return payload


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--repo-root", required=True, type=Path)
    result.add_argument("--state-db", required=True, type=Path)
    result.add_argument("--production-bundle", required=True, type=Path)
    result.add_argument("--protected-manifest", required=True, type=Path)
    result.add_argument("--after", required=True)
    result.add_argument("--before")
    result.add_argument("--expected-photos", type=int)
    result.add_argument("--output", required=True, type=Path)
    return result


def main() -> None:
    args = parser().parse_args()
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError(f"refusing to overwrite audit receipt: {output}")
    payload = audit(args)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "output": str(output),
        "inventory": payload["inventory"],
        "blindEvaluation": payload["blindEvaluation"],
        "review": payload["review"],
        "productionWrites": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
