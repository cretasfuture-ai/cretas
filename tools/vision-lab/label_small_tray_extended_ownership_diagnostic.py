#!/usr/bin/env python3
"""Calibrate downward label ownership for shallow two-compartment tray boxes."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import sys
from pathlib import Path
from typing import Any, Sequence

import numpy as np
from PIL import Image, ImageOps


CLASSES = {0: "white_label", 1: "color_label"}


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


def load_rgb_image(path: Path) -> np.ndarray:
    with Image.open(path) as opened:
        return np.asarray(ImageOps.exif_transpose(opened).convert("RGB"))


def parse_ratios(value: str) -> list[float]:
    ratios = [float(item.strip()) for item in value.split(",") if item.strip()]
    if not ratios or any(ratio < 0.0 or ratio > 2.0 for ratio in ratios):
        raise argparse.ArgumentTypeError("bottom ratios must be within 0..2")
    return ratios


def extended_box(box: Sequence[float], image_height: int, bottom_ratio: float) -> list[float]:
    x0, y0, x1, y1 = (float(value) for value in box)
    height = max(1.0, y1 - y0)
    return [x0, y0, x1, min(float(image_height), y1 + height * bottom_ratio)]


def crop_rect(
    box: Sequence[float], image_width: int, image_height: int, side_pad_ratio: float,
) -> list[int]:
    x0, y0, x1, y1 = (float(value) for value in box)
    width = max(1.0, x1 - x0)
    top_pad = max(1.0, (y1 - y0) * 0.08)
    return [
        max(0, round(x0 - width * side_pad_ratio)),
        max(0, round(y0 - top_pad)),
        min(image_width, round(x1 + width * side_pad_ratio)),
        min(image_height, round(y1)),
    ]


def verdict(classes: set[int]) -> str:
    if classes == {0, 1}:
        return "NO_DEFECT"
    if classes == {0}:
        return "MISSING_COLOR_LABEL"
    if classes == {1}:
        return "MISSING_WHITE_LABEL"
    return "BOTH_MISSING"


def run(
    repo_root: Path, bundle: Path, scan_path: Path, review_path: Path,
    output: Path, bottom_ratios: Sequence[float], threshold: float,
) -> dict[str, Any]:
    for path in (bundle, scan_path, review_path):
        if not path.exists():
            raise FileNotFoundError(path)
    if output.exists():
        raise FileExistsError(f"refusing to overwrite diagnostic: {output}")
    sys.path.insert(0, str((repo_root / "backend" / "python").resolve()))
    from label_qc.services.screening import resize_crop
    from label_qc.services.yolo_detector import LabelQcYoloModels

    scan, review = load(scan_path), load(review_path)
    if scan.get("version") != "liushanmen-small-tray-policy-scan-v1":
        raise RuntimeError("unsupported source scan")
    if review.get("version") != "liushanmen-small-tray-target-adjudication-v1":
        raise RuntimeError("unsupported corrected review")
    selected = {str(row["photo_id"]): row for row in scan.get("selected") or []}
    results = review.get("results") or []
    if {str(row["source_photo_id"]) for row in results} != set(selected):
        raise RuntimeError("review and scan photo inventory drift")
    models = LabelQcYoloModels(bundle.resolve())
    if not models.available:
        raise RuntimeError(models.load_error or "production model unavailable")
    frames = {}
    for photo_id, row in selected.items():
        image_path = Path(str(row["image"])).resolve()
        if sha256_file(image_path) != str(row["image_sha256"]):
            raise RuntimeError(f"source image drift: {photo_id}")
        frame = load_rgb_image(image_path)
        frames[photo_id] = frame
    settings = []
    truth = {str(row["source_photo_id"]): str(row["human_verdict"]) for row in results}
    for ratio in bottom_ratios:
        rows = []
        for photo_id, source in selected.items():
            frame = frames[photo_id]
            physical = extended_box(source["tray"]["box"], frame.shape[0], ratio)
            rect = crop_rect(physical, frame.shape[1], frame.shape[0], 0.14)
            crop = frame[rect[1]:rect[3], rect[0]:rect[2]]
            resized = resize_crop(crop, 640)
            detections = models.detect_labels(resized, threshold)
            scale_x = (rect[2] - rect[0]) / max(1, resized.shape[1])
            scale_y = (rect[3] - rect[1]) / max(1, resized.shape[0])
            owned = []
            for detection in detections:
                center_x = rect[0] + ((detection.x0 + detection.x1) / 2) * scale_x
                center_y = rect[1] + ((detection.y0 + detection.y1) / 2) * scale_y
                if physical[0] <= center_x <= physical[2] and physical[1] <= center_y <= physical[3]:
                    owned.append({
                        "class_id": int(detection.class_id),
                        "confidence": round(float(detection.confidence), 6),
                        "center_px": [round(center_x, 3), round(center_y, 3)],
                    })
            candidate = verdict({row["class_id"] for row in owned})
            rows.append({
                "photo_id": photo_id, "human_verdict": truth[photo_id],
                "candidate_verdict": candidate, "agrees": candidate == truth[photo_id],
                "extended_box": [round(value, 3) for value in physical], "labels": owned,
            })
        settings.append({
            "bottom_ratio": ratio, "agreements": sum(row["agrees"] for row in rows),
            "total": len(rows), "rows": rows,
        })
    receipt = {
        "version": "liushanmen-small-tray-extended-ownership-diagnostic-v1",
        "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "calibration_only": True,
        "blind_evaluation": False,
        "promotion_evidence": False,
        "threshold": threshold,
        "settings": settings,
        "production_writes": 0,
        "originals_modified": 0,
        "production_changed": False,
        "inputs": {
            "bundle": str(bundle.resolve()),
            "scan": {"path": str(scan_path.resolve()), "sha256": sha256_file(scan_path)},
            "review": {"path": str(review_path.resolve()), "sha256": sha256_file(review_path)},
        },
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--scan", required=True, type=Path)
    parser.add_argument("--review", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--bottom-ratios", type=parse_ratios, default=parse_ratios("0.75,1.0,1.25,1.5"))
    parser.add_argument("--threshold", type=float, default=0.20)
    args = parser.parse_args()
    if not 0.0 < args.threshold < 1.0:
        raise ValueError("threshold must be within 0..1")
    receipt = run(
        args.repo_root.resolve(), args.bundle.resolve(), args.scan.resolve(),
        args.review.resolve(), args.output.resolve(), args.bottom_ratios, args.threshold,
    )
    print(json.dumps({
        "calibration_only": True,
        "settings": [{key: row[key] for key in ("bottom_ratio", "agreements", "total")} for row in receipt["settings"]],
        "production_changed": False,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
