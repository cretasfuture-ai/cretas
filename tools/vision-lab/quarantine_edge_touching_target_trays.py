#!/usr/bin/env python3
"""Fail closed reviewed tray annotations whose human tray box touches a crop edge."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any, Sequence


VERSION = "liushanmen-edge-touching-tray-quarantine-v1"
DEFAULT_MARGIN = 0.005


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise RuntimeError(f"expected JSON object: {path}")
    return value


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def touches_crop_edge(box: Sequence[float], margin: float = DEFAULT_MARGIN) -> bool:
    if len(box) != 4:
        raise ValueError("target tray box must contain four coordinates")
    values = [float(value) for value in box]
    if not 0 <= margin < 0.5:
        raise ValueError("edge margin must be between zero and one half")
    if not all(0 <= value <= 1 for value in values) or not (
        values[0] < values[2] and values[1] < values[3]
    ):
        raise ValueError("invalid target tray box")
    return (
        values[0] <= margin or values[1] <= margin
        or values[2] >= 1 - margin or values[3] >= 1 - margin
    )


def quarantine(queue: Path, output: Path, margin: float = DEFAULT_MARGIN) -> dict[str, Any]:
    if output.exists():
        raise FileExistsError(f"refusing to overwrite quarantine receipt: {output}")
    manifest_path = queue / "manifest.json"
    manifest_raw = manifest_path.read_bytes()
    manifest = json.loads(manifest_raw)
    rows = manifest.get("rows") or []
    if not isinstance(rows, list) or not rows:
        raise RuntimeError("queue manifest has no rows")

    planned: list[tuple[Path, bytes, dict[str, Any]]] = []
    audit_rows = []
    for row in rows:
        crop_id = str(row.get("crop_id") or "")
        path = queue / "annotations-human" / f"{crop_id}.json"
        raw = path.read_bytes()
        annotation = json.loads(raw)
        target = annotation.get("target_tray_box")
        if (
            annotation.get("reviewed") is True
            and annotation.get("incomplete_tray") is not True
            and annotation.get("training_eligible") is True
            and isinstance(target, list)
            and touches_crop_edge(target, margin)
        ):
            before_sha = sha256_bytes(raw)
            updated = dict(annotation)
            updated.update({
                "incomplete_tray": True,
                "review_round_action": "REQUEUE_AFTER_TRAY_REDETECTION",
                "training_eligible": False,
                "training_exclusion_reason": "target_tray_box_touches_crop_edge",
                "platform_safety_override": {
                    "version": VERSION,
                    "reason": "human_target_tray_box_touches_crop_edge",
                    "edge_margin": margin,
                    "previous_annotation_sha256": before_sha,
                    "human_label_boxes_preserved": True,
                    "human_target_tray_box_preserved": True,
                },
            })
            planned.append((path, raw, updated))
            audit_rows.append({
                "crop_id": crop_id,
                "previous_annotation_sha256": before_sha,
                "target_tray_box": target,
                "label_box_count": len(annotation.get("boxes") or []),
            })
    if not planned:
        raise RuntimeError("queue has no eligible edge-touching target tray annotations")

    originals = output / "original-annotations"
    originals.mkdir(parents=True)
    for path, raw, updated in planned:
        (originals / path.name).write_bytes(raw)
        encoded = (json.dumps(updated, ensure_ascii=False, indent=1) + "\n").encode("utf-8")
        temporary = path.with_suffix(".edge-quarantine.tmp")
        temporary.write_bytes(encoded)
        temporary.replace(path)
    for audit, (path, _, _) in zip(audit_rows, planned):
        audit["quarantined_annotation_sha256"] = sha256_bytes(path.read_bytes())

    receipt = {
        "version": VERSION,
        "queue": str(queue.resolve()),
        "queue_manifest_sha256": sha256_bytes(manifest_raw),
        "edge_margin": margin,
        "quarantined_count": len(planned),
        "human_label_boxes_preserved": True,
        "human_target_tray_boxes_preserved": True,
        "training_allowed": False,
        "production_writes": 0,
        "originals_modified": 0,
        "annotation_sidecars_updated": len(planned),
        "rows": audit_rows,
    }
    (output / "receipt.json").write_text(
        json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--margin", type=float, default=DEFAULT_MARGIN)
    args = parser.parse_args()
    receipt = quarantine(args.queue.resolve(), args.output.resolve(), args.margin)
    print(json.dumps({
        "output": str(args.output.resolve()),
        "quarantined_count": receipt["quarantined_count"],
        "training_allowed": False,
        "production_writes": 0,
        "originals_modified": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
