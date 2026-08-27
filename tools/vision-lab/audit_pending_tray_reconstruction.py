#!/usr/bin/env python3
"""Build read-only full-tray reconstruction previews for pending label review."""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any, Sequence

import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageOps


VERSION = "liushanmen-pending-tray-reconstruction-audit-v1"


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


def axis_overlap(first: Sequence[float], second: Sequence[float], axis: int) -> float:
    start = max(float(first[axis]), float(second[axis]))
    end = min(float(first[axis + 2]), float(second[axis + 2]))
    first_length = max(1.0, float(first[axis + 2]) - float(first[axis]))
    second_length = max(1.0, float(second[axis + 2]) - float(second[axis]))
    return max(0.0, end - start) / min(first_length, second_length)


def reconstruct_full_tray(
    target: Sequence[float], proposals: Sequence[dict[str, Any]],
) -> tuple[list[float], list[int]]:
    """Merge a compartment proposal only with directly overlapping peer lobes.

    The seed aspect decides the compartment axis.  Direct-only matching avoids
    recursively bridging into a neighbouring physical tray.
    """
    target = [float(value) for value in target]
    width, height = target[2] - target[0], target[3] - target[1]
    if width <= 0 or height <= 0:
        raise ValueError("invalid target tray box")
    horizontal = width >= height
    orthogonal_axis = 1 if horizontal else 0
    selected: list[int] = []
    boxes = [target]
    for index, proposal in enumerate(proposals):
        box = [float(value) for value in proposal["box"]]
        if axis_overlap(target, box, orthogonal_axis) < 0.65:
            continue
        primary_overlap = axis_overlap(target, box, 0 if horizontal else 1)
        if primary_overlap <= 0.05:
            continue
        orthogonal_size = (
            box[3] - box[1] if horizontal else box[2] - box[0]
        )
        target_orthogonal = height if horizontal else width
        if not 0.55 <= orthogonal_size / max(1.0, target_orthogonal) <= 1.75:
            continue
        selected.append(index)
        boxes.append(box)
    merged = [
        min(box[0] for box in boxes), min(box[1] for box in boxes),
        max(box[2] for box in boxes), max(box[3] for box in boxes),
    ]
    return [round(value, 3) for value in merged], selected


def normalised_to_pixels(box: Sequence[float], width: int, height: int) -> list[float]:
    return [
        float(box[0]) * width, float(box[1]) * height,
        float(box[2]) * width, float(box[3]) * height,
    ]


def run(queue: Path, bundle: Path, repo_root: Path, output: Path, threshold: float) -> dict[str, Any]:
    if output.exists():
        raise FileExistsError(f"refusing to overwrite audit: {output}")
    manifest_path = queue / "manifest.json"
    manifest = load_json(manifest_path)
    pending_path = Path(str(manifest["source_pending_manifest"])).resolve()
    pending = load_json(pending_path)
    pending_rows = {str(row["photo_id"]): row for row in pending.get("rows") or []}
    sys.path.insert(0, str((repo_root / "backend" / "python").resolve()))
    from label_qc.services.v8_pipeline import LabelQcV8Models

    models = LabelQcV8Models(bundle)
    cascade = models._require_models()
    output.mkdir(parents=True)
    preview_dir = output / "previews"
    preview_dir.mkdir()
    rows = []
    for row in manifest.get("rows") or []:
        photo_id = str(row["source_photo_id"])
        source_row = pending_rows[photo_id]
        source = Path(str(source_row["image"])).resolve()
        if sha256_file(source) != str(row["source_image_sha256"]):
            raise RuntimeError(f"source image drift: {photo_id}")
        with Image.open(source) as opened:
            image = ImageOps.exif_transpose(opened).convert("RGB")
        frame = np.asarray(image)
        height, width = frame.shape[:2]
        target = normalised_to_pixels(row["target_tray_box_normalized"], width, height)
        proposals = []
        tile_height = max(1, round(height * 0.60))
        tile_starts = sorted({0, max(0, round(height * 0.20)), height - tile_height})
        for tile_y0 in tile_starts:
            tile = frame[tile_y0:tile_y0 + tile_height, :]
            for detection in cascade.tray_supplement.detect(tile, threshold):
                proposals.append({
                    "box": [
                        float(detection.x0), float(detection.y0) + tile_y0,
                        float(detection.x1), float(detection.y1) + tile_y0,
                    ],
                    "confidence": round(float(detection.confidence), 6),
                    "tile_y0": tile_y0,
                })
        reconstructed, selected = reconstruct_full_tray(target, proposals)
        preview = image.copy()
        draw = ImageDraw.Draw(preview)
        draw.rectangle(tuple(target), outline="#ff3b30", width=12)
        for proposal in proposals:
            draw.rectangle(tuple(proposal["box"]), outline="#2d8cff", width=5)
        draw.rectangle(tuple(reconstructed), outline="#ffdf56", width=14)
        label = f"red=runtime target  blue=raw proposals  yellow=reconstructed  selected={len(selected)}"
        draw.rectangle((10, 10, 10 + 10 * len(label), 54), fill="#000000")
        draw.text((18, 18), label, fill="#ffffff", font=ImageFont.load_default())
        preview_path = preview_dir / f"{row['crop_id']}.jpg"
        preview.thumbnail((1200, 1600), Image.Resampling.LANCZOS)
        preview.save(preview_path, "JPEG", quality=92)
        rows.append({
            "crop_id": row["crop_id"], "source_photo_id": photo_id,
            "target_box_px": [round(value, 3) for value in target],
            "raw_proposals": proposals, "selected_proposal_indices": selected,
            "reconstructed_box_px": reconstructed,
            "preview": str(preview_path.resolve()),
            "human_verified": False, "training_eligible": False,
        })
    receipt = {
        "version": VERSION, "queue": str(queue.resolve()),
        "queue_manifest_sha256": sha256_file(manifest_path),
        "bundle": str(bundle.resolve()), "threshold": threshold,
        "rows": rows, "requires_visual_verification": True,
        "production_writes": 0, "originals_modified": 0,
        "production_changed": False, "training_allowed": False,
    }
    (output / "receipt.json").write_text(
        json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", required=True, type=Path)
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--threshold", type=float, default=0.15)
    args = parser.parse_args()
    receipt = run(
        args.queue.resolve(), args.bundle.resolve(), args.repo_root.resolve(),
        args.output.resolve(), args.threshold,
    )
    print(json.dumps({
        "output": str(args.output.resolve()), "rows": len(receipt["rows"]),
        "production_writes": 0, "originals_modified": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
