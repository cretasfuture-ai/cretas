#!/usr/bin/env python3
"""Audit every historical human-label training crop against its full-image truth."""
from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

from PIL import Image


VERSION = "liushanmen-label-training-crop-integrity-audit-v1"
MIN_MISSING_CLASS_CROP_HEIGHT_TO_TRAY_WIDTH = 0.90
LABEL_TYPES = {"WHITE_LABEL": 0, "COLOR_LABEL": 1}


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise RuntimeError(f"expected JSON object: {path}")
    return value


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def material_key(row: dict[str, Any]) -> str:
    payload = {
        "source_image_sha256": row.get("source_image_sha256"),
        "truth_sha256": row.get("truth_sha256"),
        "parent_tray_id": row.get("parent_tray_id"),
        "crop_box_px": row.get("crop_box_px"),
        "white_status": row.get("white_status"),
        "color_status": row.get("color_status"),
    }
    return hashlib.sha256(
        json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def source_queue_for(manifest: dict[str, Any], row: dict[str, Any]) -> Path | None:
    source_manifest = row.get("source_dataset_manifest")
    if source_manifest:
        source = load_json(Path(str(source_manifest)))
        queue = source.get("source_queue")
    else:
        queue = manifest.get("source_queue")
    return Path(str(queue)) if isinstance(queue, str) and queue else None


def normalized_to_pixels(box: Iterable[Any], width: int, height: int) -> list[float]:
    x0, y0, x1, y1 = (float(value) for value in box)
    return [x0 * width, y0 * height, x1 * width, y1 * height]


def contains(outer: Iterable[Any], inner: Iterable[Any], tolerance: float = 2.0) -> bool:
    ox0, oy0, ox1, oy1 = (float(value) for value in outer)
    ix0, iy0, ix1, iy1 = (float(value) for value in inner)
    return (
        ix0 >= ox0 - tolerance and iy0 >= oy0 - tolerance
        and ix1 <= ox1 + tolerance and iy1 <= oy1 + tolerance
    )


def read_yolo_labels(path: Path) -> list[tuple[int, list[float]]]:
    labels = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        parts = line.split()
        if len(parts) != 5:
            raise RuntimeError(f"invalid YOLO label line: {path}: {line}")
        class_id = int(parts[0])
        values = [float(value) for value in parts[1:]]
        labels.append((class_id, values))
    return labels


def audit(
    dataset_root: Path,
    production_manifest_path: Path,
    *,
    verify_hashes: bool = True,
) -> dict[str, Any]:
    production_manifest_path = production_manifest_path.resolve()
    production = load_json(production_manifest_path)
    production_keys = {
        material_key(row)
        for row in production.get("derived_rows") or []
        if row.get("kind") == "label"
    }

    manifests: list[tuple[Path, dict[str, Any]]] = []
    appearances: dict[str, list[str]] = defaultdict(list)
    materials: dict[str, tuple[Path, dict[str, Any], dict[str, Any]]] = {}
    for path in sorted(dataset_root.rglob("manifest.json")):
        try:
            manifest = load_json(path)
        except (OSError, ValueError, RuntimeError):
            continue
        label_rows = [
            row for row in manifest.get("derived_rows") or []
            if row.get("kind") == "label"
        ]
        if not label_rows:
            continue
        manifests.append((path.resolve(), manifest))
        for row in label_rows:
            key = material_key(row)
            appearances[key].append(str(path.resolve()))
            materials.setdefault(key, (path.resolve(), manifest, row))

    json_cache: dict[Path, dict[str, Any]] = {}
    queue_records: dict[Path, dict[str, dict[str, Any]]] = {}
    hash_cache: dict[Path, str] = {}
    image_size_cache: dict[Path, tuple[int, int]] = {}

    def cached_json(path: Path) -> dict[str, Any]:
        resolved = path.resolve()
        if resolved not in json_cache:
            json_cache[resolved] = load_json(resolved)
        return json_cache[resolved]

    def cached_sha(path: Path) -> str:
        resolved = path.resolve()
        if resolved not in hash_cache:
            hash_cache[resolved] = sha256_file(resolved)
        return hash_cache[resolved]

    def records_for(queue: Path) -> dict[str, dict[str, Any]]:
        resolved = queue.resolve()
        if resolved not in queue_records:
            queue_manifest = cached_json(resolved / "manifest.json")
            queue_records[resolved] = {
                str(record["photo_id"]): record
                for record in queue_manifest.get("records") or []
            }
        return queue_records[resolved]

    results = []
    for key, (manifest_path, manifest, row) in sorted(materials.items()):
        issues: list[str] = []
        warnings: list[str] = []
        queue = source_queue_for(manifest, row)
        photo_id = str(row.get("photo_id"))
        parent_tray_id = str(row.get("parent_tray_id"))
        crop = row.get("crop_box_px")
        annotation_path: Path | None = None
        record: dict[str, Any] | None = None
        annotation: dict[str, Any] | None = None
        tray: dict[str, Any] | None = None
        children: list[dict[str, Any]] = []
        geometry: dict[str, Any] | None = None

        if queue is None or not queue.is_dir():
            issues.append("source_queue_missing")
        else:
            try:
                record = records_for(queue).get(photo_id)
            except (OSError, ValueError, RuntimeError):
                issues.append("source_queue_manifest_invalid")
            annotation_path = queue / "annotations-human" / f"{photo_id}.json"
            if not annotation_path.is_file():
                issues.append("human_annotation_missing")
            else:
                try:
                    annotation = cached_json(annotation_path)
                except (OSError, ValueError, RuntimeError):
                    issues.append("human_annotation_invalid")

        if record is None:
            issues.append("source_photo_record_missing")
        if not isinstance(crop, list) or len(crop) != 4:
            issues.append("crop_box_invalid")
        if annotation is not None:
            boxes = annotation.get("boxes") or []
            tray = next((box for box in boxes if str(box.get("id")) == parent_tray_id), None)
            if tray is None or tray.get("type") != "TRAY":
                issues.append("parent_tray_missing")
            children = [
                box for box in boxes
                if str(box.get("parent_tray_id")) == parent_tray_id
                and box.get("type") in LABEL_TYPES
            ]
            if annotation.get("reviewed") is not True or annotation.get("source") != "human":
                issues.append("truth_not_human_reviewed")
            if verify_hashes and cached_sha(annotation_path) != row.get("truth_sha256"):
                issues.append("truth_sha256_mismatch")

        if record is not None and tray is not None and isinstance(crop, list) and len(crop) == 4:
            width, height = int(record["image_width"]), int(record["image_height"])
            tray_px = normalized_to_pixels(tray["bbox"], width, height)
            tray_width = tray_px[2] - tray_px[0]
            tray_height = tray_px[3] - tray_px[1]
            crop_width = float(crop[2]) - float(crop[0])
            crop_height = float(crop[3]) - float(crop[1])
            if tray_width <= 0 or tray_height <= 0 or crop_width <= 0 or crop_height <= 0:
                issues.append("non_positive_geometry")
            else:
                aspect = tray_width / tray_height
                height_ratio = crop_height / tray_width
                shallow = aspect > 2.0
                missing_classes = [
                    name for name, status in (
                        ("WHITE_LABEL", row.get("white_status")),
                        ("COLOR_LABEL", row.get("color_status")),
                    ) if status != "PRESENT"
                ]
                geometry = {
                    "tray_aspect_ratio": round(aspect, 6),
                    "crop_height_to_tray_width": round(height_ratio, 6),
                    "shallow_by_aspect_ratio": shallow,
                    "missing_classes": missing_classes,
                }
                if not contains(crop, tray_px):
                    issues.append("crop_does_not_contain_parent_tray")
                for child in children:
                    child_px = normalized_to_pixels(child["bbox"], width, height)
                    if not contains(crop, child_px):
                        issues.append(f"crop_does_not_contain_{str(child['type']).lower()}")
                for label_type in LABEL_TYPES:
                    expected_present = row.get(
                        "white_status" if label_type == "WHITE_LABEL" else "color_status"
                    ) == "PRESENT"
                    actual_present = any(child.get("type") == label_type for child in children)
                    if expected_present != actual_present:
                        issues.append(f"{label_type.lower()}_status_truth_mismatch")
                if missing_classes and shallow and height_ratio < MIN_MISSING_CLASS_CROP_HEIGHT_TO_TRAY_WIDTH:
                    warnings.append("missing_class_with_incomplete_physical_context")

        derived_image = Path(str(row.get("derived_image", "")))
        derived_label = Path(str(row.get("derived_label", "")))
        if not derived_image.is_file():
            issues.append("derived_image_missing")
        if not derived_label.is_file():
            issues.append("derived_label_missing")
        if derived_image.is_file() and isinstance(crop, list) and len(crop) == 4:
            resolved = derived_image.resolve()
            if resolved not in image_size_cache:
                with Image.open(resolved) as opened:
                    image_size_cache[resolved] = opened.size
            expected_size = (int(crop[2]) - int(crop[0]), int(crop[3]) - int(crop[1]))
            if image_size_cache[resolved] != expected_size:
                issues.append("derived_image_size_mismatch")
            if verify_hashes and cached_sha(resolved) != row.get("derived_image_sha256"):
                issues.append("derived_image_sha256_mismatch")
        if derived_label.is_file():
            try:
                yolo_labels = read_yolo_labels(derived_label)
                if len(yolo_labels) != int(row.get("instance_count", -1)):
                    issues.append("derived_label_instance_count_mismatch")
                for class_id, values in yolo_labels:
                    if class_id not in (0, 1):
                        issues.append("derived_label_unknown_class")
                    x, y, width, height = values
                    epsilon = 1e-6
                    if min(width, height) <= 0 or not (
                        -epsilon <= x - width / 2 <= x + width / 2 <= 1 + epsilon
                        and -epsilon <= y - height / 2 <= y + height / 2 <= 1 + epsilon
                    ):
                        issues.append("derived_label_out_of_bounds")
                if verify_hashes and cached_sha(derived_label) != row.get("derived_label_sha256"):
                    issues.append("derived_label_sha256_mismatch")
            except (OSError, ValueError, RuntimeError):
                issues.append("derived_label_invalid")

        if issues:
            classification = "structural_failure"
        elif warnings:
            classification = "negative_context_risk"
        elif geometry is None:
            classification = "unknown_geometry"
        else:
            classification = "proven_safe"
        results.append({
            "material_key": key,
            "classification": classification,
            "in_production_dataset": key in production_keys,
            "dataset_appearances": appearances[key],
            "source_queue": str(queue.resolve()) if queue else None,
            "photo_id": photo_id,
            "task_id": row.get("task_id"),
            "source_image_sha256": row.get("source_image_sha256"),
            "truth_sha256": row.get("truth_sha256"),
            "parent_tray_id": parent_tray_id,
            "crop_box_px": crop,
            "white_status": row.get("white_status"),
            "color_status": row.get("color_status"),
            "geometry": geometry,
            "issues": sorted(set(issues)),
            "warnings": sorted(set(warnings)),
        })

    def counts(rows: Iterable[dict[str, Any]]) -> dict[str, int]:
        return dict(sorted(Counter(row["classification"] for row in rows).items()))

    production_results = [row for row in results if row["in_production_dataset"]]
    unsafe = [row for row in results if row["classification"] != "proven_safe"]
    production_unsafe = [row for row in production_results if row["classification"] != "proven_safe"]
    return {
        "version": VERSION,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "inputs": {
            "dataset_root": str(dataset_root.resolve()),
            "production_manifest": str(production_manifest_path),
            "production_manifest_sha256": sha256_file(production_manifest_path),
            "verify_hashes": verify_hashes,
        },
        "policy": {
            "missing_class_negative_requires_physical_context": True,
            "shallow_tray_aspect_ratio_threshold": 2.0,
            "minimum_missing_class_crop_height_to_tray_width": (
                MIN_MISSING_CLASS_CROP_HEIGHT_TO_TRAY_WIDTH
            ),
            "positive_crop_is_safe_only_when_human_truth_and_all_present_boxes_are_contained": True,
        },
        "dataset_manifests": [
            {"path": str(path), "version": manifest.get("version")}
            for path, manifest in manifests
        ],
        "dataset_manifest_count": len(manifests),
        "material_appearance_count": sum(len(value) for value in appearances.values()),
        "unique_material_count": len(results),
        "production_unique_material_count": len(production_results),
        "classification_counts": counts(results),
        "production_classification_counts": counts(production_results),
        "unsafe_material_count": len(unsafe),
        "production_unsafe_material_count": len(production_unsafe),
        "training_gate_passed": len(unsafe) == 0,
        "production_reuse_gate_passed": len(production_unsafe) == 0,
        "production_change_allowed": False,
        "quarantine": unsafe,
        "production_quarantine": production_unsafe,
        "production_writes": 0,
        "originals_modified": 0,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-root", required=True, type=Path)
    parser.add_argument("--production-manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--skip-hashes", action="store_true")
    parser.add_argument("--require-production-reuse-pass", action="store_true")
    args = parser.parse_args()
    receipt = audit(
        args.dataset_root.resolve(), args.production_manifest.resolve(),
        verify_hashes=not args.skip_hashes,
    )
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError(f"refusing to overwrite audit receipt: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "output": str(output),
        "unique_material_count": receipt["unique_material_count"],
        "classification_counts": receipt["classification_counts"],
        "production_classification_counts": receipt["production_classification_counts"],
        "training_gate_passed": receipt["training_gate_passed"],
        "production_reuse_gate_passed": receipt["production_reuse_gate_passed"],
        "production_writes": 0,
        "originals_modified": 0,
    }, ensure_ascii=False, indent=2))
    if args.require_production_reuse_pass and not receipt["production_reuse_gate_passed"]:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
