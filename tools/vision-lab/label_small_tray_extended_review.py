#!/usr/bin/env python3
"""Build a minimal human queue for extended-ownership normal-photo flags."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
from typing import Any

from PIL import Image, ImageOps


HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "target_review", HERE / "label_small_tray_target_review.py",
)
TARGET = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(TARGET)

DEFECTS = {"MISSING_WHITE_LABEL", "MISSING_COLOR_LABEL", "BOTH_MISSING"}


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


def select_flags(evaluation: dict[str, Any]) -> list[dict[str, Any]]:
    selected = []
    for photo in evaluation.get("details") or []:
        if (photo.get("humanTruth") or {}).get("kind") != "normal":
            continue
        for tray in photo.get("candidate_trays") or []:
            if tray.get("candidate_verdict") in DEFECTS:
                selected.append({
                    "photo_id": str(photo["photoId"]), "tray_index": int(tray["index"]),
                    "candidate_verdict": str(tray["candidate_verdict"]),
                })
    return selected


def build(audit_path: Path, evaluation_path: Path, output: Path, annotator_url: str) -> dict[str, Any]:
    audit, evaluation = load(audit_path), load(evaluation_path)
    if audit.get("version") != "liushanmen-recent-label-photo-audit-v1":
        raise RuntimeError("unsupported recent audit")
    if evaluation.get("version") != "liushanmen-small-tray-extended-ownership-evaluation-v1":
        raise RuntimeError("unsupported extended ownership evaluation")
    bound = (evaluation.get("inputs") or {}).get("audit") or {}
    if bound.get("sha256") != sha256_file(audit_path):
        raise RuntimeError("evaluation audit binding drift")
    if output.exists():
        raise FileExistsError(f"refusing to overwrite review queue: {output}")
    selected = select_flags(evaluation)
    if not selected:
        raise RuntimeError("evaluation contains no normal-photo candidate flags")
    details = {str(row["photoId"]): row for row in audit.get("details") or []}
    for name in ("images", "prelabels", "annotations-human"):
        (output / name).mkdir(parents=True, exist_ok=False)
    rows = []
    for position, flag in enumerate(selected, start=1):
        detail = details[flag["photo_id"]]
        tray = next(
            row for row in detail["productionBlind"]["trays"]
            if int(row["index"]) == flag["tray_index"]
        )
        source = Path(str(detail["image"])).resolve()
        if sha256_file(source) != str(detail["imageSha256"]):
            raise RuntimeError(f"source image drift: {source}")
        with Image.open(source) as opened:
            image = ImageOps.exif_transpose(opened).convert("RGB")
            crop = TARGET.tight_crop(tray["box"], image.width, image.height, 0.15, 1.35)
            crop_id = f"extended_{flag['photo_id'][:8]}_t{flag['tray_index']:02d}_{position:03d}"
            image_path = output / "images" / f"{crop_id}.jpg"
            image.crop(tuple(crop)).save(image_path, "JPEG", quality=95, subsampling=0)
        (output / "prelabels" / f"{crop_id}.json").write_text(json.dumps({
            "crop_id": crop_id, "source": "empty_to_avoid_extended_ownership_review_bias",
            "is_ground_truth": False, "boxes": [],
        }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        rows.append({
            "crop_id": crop_id, "image": str(image_path.resolve()),
            "image_sha256": sha256_file(image_path), "source_photo_id": flag["photo_id"],
            "source_task_id": detail["taskId"], "source_image_sha256": detail["imageSha256"],
            "sku_code": detail["skuCode"],
            "angle": "完整目标托盘：只标这一只托盘自己的白标和彩标",
            "label_v1_verdict": flag["candidate_verdict"],
            "human_qc_verdict": "UNJUDGEABLE",
            "model_previous_verdict": flag["candidate_verdict"],
            "target_tray_index": flag["tray_index"], "target_tray_box_px": tray["box"],
            "source_crop_rect_px": crop,
            "review_reason": "extended_ownership_normal_photo_candidate_flag",
            "prelabel_boxes": 0, "review_scope": "single_complete_target_tray_only",
            "training_eligible": False,
        })
    manifest = {
        "version": "liushanmen-small-tray-tight-target-review-v1",
        "queue_id": output.name, "queue_count": len(rows), "annotator_url": annotator_url,
        "source_audit": str(audit_path.resolve()), "source_audit_sha256": sha256_file(audit_path),
        "source_evaluation": str(evaluation_path.resolve()),
        "source_evaluation_sha256": sha256_file(evaluation_path),
        "preannotations_are_not_ground_truth": True,
        "every_image_requires_full_human_review": True,
        "review_scope": "single complete target tray only",
        "factory_tray_box_annotation_required": False,
        "evaluation_or_production_use_allowed": False,
        "production_writes": 0, "originals_modified": 0, "rows": rows,
    }
    (output / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audit", required=True, type=Path)
    parser.add_argument("--evaluation", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--annotator-url", default="http://127.0.0.1:8798/")
    args = parser.parse_args()
    manifest = build(
        args.audit.resolve(), args.evaluation.resolve(), args.output.resolve(), args.annotator_url,
    )
    print(json.dumps({
        "queue": str(args.output.resolve()), "count": manifest["queue_count"],
        "annotator_url": manifest["annotator_url"], "production_writes": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
