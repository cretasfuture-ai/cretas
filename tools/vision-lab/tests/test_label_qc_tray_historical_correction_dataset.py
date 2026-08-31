from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import label_qc_tray_historical_correction_dataset as subject
import label_qc_tray_patch_dataset as patch_dataset


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value) + "\n", encoding="utf-8")


def test_build_replaces_safe_rebox_and_excludes_removed_proposal(tmp_path: Path) -> None:
    prior = tmp_path / "prior"
    for split in ("train", "val"):
        (prior / "images" / split).mkdir(parents=True)
        (prior / "labels" / split).mkdir(parents=True)
    for stem in ("safe", "removed"):
        (prior / "images" / "train" / f"{stem}.jpg").write_bytes(stem.encode())
        (prior / "labels" / "train" / f"{stem}.txt").write_text(
            "0 0.500000 0.500000 0.400000 0.400000\n", encoding="utf-8",
        )
    (prior / "images" / "val" / "v.jpg").write_bytes(b"val")
    (prior / "labels" / "val" / "v.txt").write_text(
        "0 0.500000 0.500000 0.200000 0.200000\n", encoding="utf-8",
    )
    (prior / "train-focus.txt").write_text(
        "./images/train/safe.jpg\n./images/train/removed.jpg\n", encoding="utf-8",
    )
    (prior / "data.yaml").write_text(
        f"path: {prior.as_posix()}\ntrain: train-focus.txt\nval: images/val\nnames:\n  0: tray\n",
        encoding="utf-8",
    )
    write_json(prior / "provenance.json", {"rows": [
        {"stem": "safe", "split": "train", "task_id": "task-safe", "box_count": 1},
        {"stem": "removed", "split": "train", "task_id": "task-removed", "box_count": 1},
        {"stem": "v", "split": "val", "task_id": "task-val", "box_count": 1},
    ]})
    prior_manifest = {
        "version": "prior", "dataset_id": "prior", "counts": {"train_images": 2, "train_boxes": 2, "val_images": 1, "val_boxes": 1},
        "focus_replay_policy": {"derived_repeats": 1}, "protected_holdout": str(tmp_path / "holdout.json"),
        "protected_holdout_included": False, "preannotations_used_as_truth": False,
        "deleted_proposals_used_as_background": False, "images_with_ignore_regions_excluded_from_training": True,
        "validation_unchanged_from_base_dataset": True, "training_allowed": True, "promotion_allowed": False,
        "data_yaml": str(prior / "data.yaml"), "dataset_sha256": patch_dataset.dataset_digest(prior),
    }
    write_json(prior / "manifest.json", prior_manifest)

    queue = tmp_path / "queue"
    (queue / "images").mkdir(parents=True)
    (queue / "labels").mkdir()
    rows = []
    for stem, task in (("safe", "task-safe"), ("removed", "task-removed")):
        (queue / "images" / f"{stem}.jpg").write_bytes(stem.encode())
        (queue / "labels" / f"{stem}.txt").write_text(
            "0 0.500000 0.500000 0.400000 0.400000\n", encoding="utf-8",
        )
        rows.append({"packed_stem": stem, "packed_image": f"images/{stem}.jpg", "packed_label": f"labels/{stem}.txt", "task_id": task})
    queue_manifest = {
        "preannotations_are_not_ground_truth": True, "every_image_requires_full_human_review": True,
        "protected_holdout_included": False, "validation_images_included": False,
        "derived_recent_patches_included": False, "all_rows_historical_train_only": True,
        "queue_count": 2, "rows": rows,
    }
    write_json(queue / "manifest.json", queue_manifest)
    write_json(queue / "annotations-human" / "safe.json", {
        "reviewed": True, "source": "human", "boxes": [[0.29, 0.29, 0.71, 0.71]],
    })
    write_json(queue / "annotations-human" / "removed.json", {
        "reviewed": True, "source": "human", "boxes": [[0.10, 0.10, 0.20, 0.20]],
    })

    output, manifest = subject.build_dataset(prior / "manifest.json", queue, tmp_path / "datasets")

    assert manifest["corrected_training_images"] == 1
    assert manifest["excluded_ignore_region_images"] == 1
    assert manifest["deleted_proposals_used_as_background"] is False
    assert (output / "train-focus.txt").read_text(encoding="utf-8").splitlines() == ["./images/train/safe.jpg"]
    assert (output / "labels" / "train" / "safe.txt").read_text(encoding="utf-8").strip() == "0 0.500000 0.500000 0.420000 0.420000"
