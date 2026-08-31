#!/usr/bin/env python3
"""Create an immutable tray dataset with fully reviewed historical corrections."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path
from typing import Any

import label_qc_tray_patch_dataset as patch_dataset
from label_qc_tray_full_image_dataset import audit_preannotations


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object: {path}")
    return value


def old_boxes(path: Path) -> list[list[float]]:
    result = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        class_id, cx, cy, width, height = map(float, line.split())
        if class_id != 0:
            raise RuntimeError(f"unexpected tray class in {path}")
        result.append([
            max(0.0, cx - width / 2), max(0.0, cy - height / 2),
            min(1.0, cx + width / 2), min(1.0, cy + height / 2),
        ])
    return result


def build_dataset(
    prior_manifest_path: Path, reviewed_queue: Path, output_root: Path,
    derived_repeats: int | None = None,
) -> tuple[Path, dict[str, Any]]:
    prior_manifest_path = prior_manifest_path.resolve()
    reviewed_queue = reviewed_queue.resolve()
    output_root = output_root.resolve()
    prior_root = prior_manifest_path.parent
    prior = load_json(prior_manifest_path)
    patch_dataset.validate_prior_truth_boundary(prior)
    if prior.get("dataset_sha256") != patch_dataset.dataset_digest(prior_root):
        raise RuntimeError("prior dataset content drift")
    queue = load_json(reviewed_queue / "manifest.json")
    required = {
        "preannotations_are_not_ground_truth": True,
        "every_image_requires_full_human_review": True,
        "protected_holdout_included": False,
        "validation_images_included": False,
        "derived_recent_patches_included": False,
        "all_rows_historical_train_only": True,
    }
    for field, expected in required.items():
        if queue.get(field) is not expected:
            raise RuntimeError(f"review queue contract drift: {field}")
    rows = queue.get("rows") or []
    if len(rows) < 2 or len(rows) != int(queue.get("queue_count", -1)):
        raise RuntimeError("review queue count drift")

    provenance = load_json(prior_root / "provenance.json")
    provenance_rows = provenance.get("rows") or []
    provenance_by_stem = {str(row.get("stem")): row for row in provenance_rows}
    if len(provenance_by_stem) != len(provenance_rows):
        raise RuntimeError("prior provenance stems are not unique")
    audits = []
    annotation_identity = []
    reviewed_boxes: dict[str, list[list[float]]] = {}
    task_ids = set()
    for row in rows:
        stem = str(row["packed_stem"])
        source = provenance_by_stem.get(stem)
        if source is None or source.get("split") != "train" or source.get("derived_crop") is True:
            raise RuntimeError(f"reviewed stem is not historical train truth: {stem}")
        if str(source.get("task_id")) != str(row.get("task_id")):
            raise RuntimeError(f"task identity drift: {stem}")
        if row["task_id"] in task_ids:
            raise RuntimeError("review queue is not independent by task")
        task_ids.add(row["task_id"])
        packed_image = reviewed_queue / str(row["packed_image"])
        prior_image = prior_root / "images" / "train" / f"{stem}.jpg"
        if patch_dataset.sha256_file(packed_image) != patch_dataset.sha256_file(prior_image):
            raise RuntimeError(f"review image does not match prior dataset: {stem}")
        annotation_path = reviewed_queue / "annotations-human" / f"{stem}.json"
        annotation = load_json(annotation_path)
        if annotation.get("reviewed") is not True or annotation.get("source") != "human":
            raise RuntimeError(f"annotation not fully reviewed: {stem}")
        boxes = [patch_dataset.validate_box(box) for box in annotation.get("boxes") or []]
        if not boxes:
            raise RuntimeError(f"reviewed image has no complete tray truth: {stem}")
        prior_label = prior_root / "labels" / "train" / f"{stem}.txt"
        proposals = old_boxes(prior_label)
        audit = audit_preannotations(
            {"packed_stem": stem, "preannotations": [{"box": box} for box in proposals]}, boxes,
        )
        audit["task_id"] = row["task_id"]
        audit["annotation_sha256"] = patch_dataset.sha256_file(annotation_path)
        audits.append(audit)
        reviewed_boxes[stem] = boxes
        annotation_identity.append({
            "stem": stem, "task_id": row["task_id"],
            "annotation_sha256": audit["annotation_sha256"], "box_count": len(boxes),
        })

    prior_repeats = int((prior.get("focus_replay_policy") or {}).get("derived_repeats", 1))
    requested_repeats = prior_repeats if derived_repeats is None else derived_repeats
    if not 1 <= requested_repeats <= 8:
        raise ValueError("derived repeats must be between 1 and 8")
    identity = {
        "version": "label-qc-tray-historical-correction-dataset-v1",
        "prior_dataset_sha256": prior["dataset_sha256"],
        "queue_manifest_sha256": patch_dataset.sha256_file(reviewed_queue / "manifest.json"),
        "annotations": annotation_identity,
        "deleted_proposal_policy": "exclude_entire_image_from_training",
        "safe_rebox_policy": "replace_existing_historical_label_only_when_no_proposal_was_removed",
        "derived_repeats": requested_repeats,
    }
    identity_hash = hashlib.sha256(json.dumps(identity, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    dataset_id = f"tray-history-corrected-{identity_hash[:12]}"
    output = output_root / dataset_id
    if output.exists():
        existing = load_json(output / "manifest.json")
        if existing.get("identity") != identity or existing.get("dataset_sha256") != patch_dataset.dataset_digest(output):
            raise RuntimeError(f"immutable output drift: {output}")
        return output, existing
    temporary = output_root / f".{dataset_id}.building"
    if temporary.exists():
        raise RuntimeError(f"stale build directory exists: {temporary}")
    shutil.copytree(prior_root, temporary, ignore=shutil.ignore_patterns("manifest.json", "*.cache"))

    safe_stems = {row["packed_stem"] for row in audits if row["training_eligible"]}
    excluded_stems = {row["packed_stem"] for row in audits if not row["training_eligible"]}
    for stem in safe_stems:
        label = temporary / "labels" / "train" / f"{stem}.txt"
        label.write_text("\n".join(patch_dataset.yolo_line(box) for box in reviewed_boxes[stem]) + "\n", encoding="utf-8")
    focus_path = temporary / "train-focus.txt"
    focus_lines = [line for line in focus_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    focus_lines = [line for line in focus_lines if Path(line).stem not in excluded_stems]
    derived_stems = [
        str(row["stem"]) for row in provenance_rows
        if row.get("split") == "train" and row.get("derived_crop") is True
    ]
    for stem in derived_stems:
        occurrences = sum(Path(line).stem == stem for line in focus_lines)
        if occurrences > requested_repeats:
            remaining = requested_repeats
            reduced = []
            for line in focus_lines:
                if Path(line).stem != stem or remaining > 0:
                    reduced.append(line)
                    if Path(line).stem == stem:
                        remaining -= 1
            focus_lines = reduced
        elif occurrences < requested_repeats:
            focus_lines.extend([f"./images/train/{stem}.jpg"] * (requested_repeats - occurrences))
    focus_path.write_text("\n".join(focus_lines) + "\n", encoding="utf-8")
    if "train-replay.txt" in {path.name for path in temporary.iterdir()}:
        replay_path = temporary / "train-replay.txt"
        replay_lines = [line for line in replay_path.read_text(encoding="utf-8").splitlines() if line.strip()]
        replay_lines = [line for line in replay_lines if Path(line).stem not in excluded_stems]
        replay_path.write_text("\n".join(replay_lines) + "\n", encoding="utf-8")
    data_yaml = temporary / "data.yaml"
    text = data_yaml.read_text(encoding="utf-8")
    text = text.replace(prior_root.as_posix(), output.as_posix()).replace(str(prior_root).replace("\\", "/"), output.as_posix())
    data_yaml.write_text(text, encoding="utf-8")
    for row in provenance_rows:
        stem = str(row.get("stem"))
        if stem in reviewed_boxes:
            audit = next(item for item in audits if item["packed_stem"] == stem)
            row["historical_correction_reviewed"] = True
            row["historical_correction_annotation_sha256"] = audit["annotation_sha256"]
            row["training_excluded"] = stem in excluded_stems
            row["training_exclusion_reason"] = "contains_ignore_not_negative_region" if stem in excluded_stems else None
            if stem in safe_stems:
                row["box_count"] = len(reviewed_boxes[stem])
    (temporary / "provenance.json").write_text(json.dumps(provenance, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    active_stems = {Path(line).stem for line in focus_lines}
    active_boxes = sum(len(old_boxes(temporary / "labels" / "train" / f"{stem}.txt")) for stem in active_stems)
    manifest = dict(prior)
    manifest.update({
        "version": identity["version"], "dataset_id": dataset_id, "identity": identity,
        "prior_dataset": str(prior_root), "prior_dataset_id": prior["dataset_id"],
        "prior_dataset_sha256": prior["dataset_sha256"], "reviewed_queue": str(reviewed_queue),
        "reviewed_images": len(audits), "reviewed_boxes": sum(row["human_box_count"] for row in audits),
        "corrected_training_images": len(safe_stems), "corrected_training_boxes": sum(len(reviewed_boxes[stem]) for stem in safe_stems),
        "excluded_ignore_region_images": len(excluded_stems), "excluded_ignore_region_stems": sorted(excluded_stems),
        "historical_correction_audit": audits,
        "counts": {**prior["counts"], "train_images": len(active_stems), "train_boxes": active_boxes},
        "effective_train_occurrences": len(focus_lines),
        "focus_replay_policy": {
            **(prior.get("focus_replay_policy") or {}),
            "derived_repeats": requested_repeats,
            "effective_train_occurrences": len(focus_lines),
            "unique_train_images": len(active_stems),
            "validation_unchanged": True,
        },
        "validation_unchanged_from_base_dataset": True, "protected_holdout_included": False,
        "preannotations_used_as_truth": False, "deleted_proposals_used_as_background": False,
        "images_with_ignore_regions_excluded_from_training": True,
        "training_allowed": True, "promotion_allowed": False, "production_writes": 0,
        "data_yaml": str(output / "data.yaml"),
    })
    manifest.pop("dataset_sha256", None)
    manifest["dataset_sha256"] = patch_dataset.dataset_digest(temporary)
    (temporary / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.rename(output)
    return output, manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prior-dataset-manifest", required=True, type=Path)
    parser.add_argument("--reviewed-queue", required=True, type=Path)
    parser.add_argument("--output-root", required=True, type=Path)
    parser.add_argument("--derived-repeats", type=int)
    args = parser.parse_args()
    output, manifest = build_dataset(
        args.prior_dataset_manifest, args.reviewed_queue, args.output_root, args.derived_repeats,
    )
    print(json.dumps({
        "dataset": str(output), "dataset_id": manifest["dataset_id"],
        "dataset_sha256": manifest["dataset_sha256"], "train_images": manifest["counts"]["train_images"],
        "effective_train_occurrences": manifest["effective_train_occurrences"],
        "corrected_training_images": manifest["corrected_training_images"],
        "excluded_ignore_region_images": manifest["excluded_ignore_region_images"],
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
