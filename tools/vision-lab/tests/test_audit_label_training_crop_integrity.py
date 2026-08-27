import importlib.util
import json
from pathlib import Path

from PIL import Image


MODULE_PATH = Path(__file__).parents[1] / "audit_label_training_crop_integrity.py"
SPEC = importlib.util.spec_from_file_location("crop_audit", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding="utf-8")


def fixture(tmp_path: Path, *, white_status: str, crop: list[int]) -> tuple[Path, Path]:
    queue = tmp_path / "queue"
    dataset = tmp_path / "datasets" / "one"
    raw = tmp_path / "raw.jpg"
    Image.new("RGB", (1000, 1000), "white").save(raw)
    annotation = {
        "reviewed": True,
        "source": "human",
        "boxes": [
            {
                "id": "tray-1", "type": "TRAY", "bbox": [0.2, 0.2, 0.5, 0.3],
                "white_status": white_status, "color_status": "PRESENT",
            },
            {
                "id": "color", "type": "COLOR_LABEL", "parent_tray_id": "tray-1",
                "bbox": [0.22, 0.21, 0.32, 0.25],
            },
        ],
    }
    if white_status == "PRESENT":
        annotation["boxes"].append({
            "id": "white", "type": "WHITE_LABEL", "parent_tray_id": "tray-1",
            "bbox": [0.22, 0.25, 0.32, 0.29],
        })
    annotation_path = queue / "annotations-human" / "photo.json"
    write_json(annotation_path, annotation)
    write_json(queue / "manifest.json", {"records": [{
        "photo_id": "photo", "image_width": 1000, "image_height": 1000,
        "image": str(raw), "image_sha256": MODULE.sha256_file(raw),
    }]})
    image_path = dataset / "label" / "images" / "train" / "crop.jpg"
    label_path = dataset / "label" / "labels" / "train" / "crop.txt"
    image_path.parent.mkdir(parents=True)
    label_path.parent.mkdir(parents=True)
    Image.new("RGB", (crop[2] - crop[0], crop[3] - crop[1]), "white").save(image_path)
    labels = ["1 0.3 0.3 0.2 0.2"]
    if white_status == "PRESENT":
        labels.append("0 0.3 0.7 0.2 0.2")
    label_path.write_text("\n".join(labels) + "\n", encoding="utf-8")
    row = {
        "kind": "label", "photo_id": "photo", "task_id": "task",
        "source_image_sha256": MODULE.sha256_file(raw),
        "truth_sha256": MODULE.sha256_file(annotation_path),
        "truth_source": "local-human-sidecar", "parent_tray_id": "tray-1",
        "crop_box_px": crop, "white_status": white_status, "color_status": "PRESENT",
        "instance_count": len(labels), "derived_image": str(image_path),
        "derived_label": str(label_path),
        "derived_image_sha256": MODULE.sha256_file(image_path),
        "derived_label_sha256": MODULE.sha256_file(label_path),
    }
    manifest = {"version": "vision-lab-today-human-training-dataset-v1",
                "source_queue": str(queue), "derived_rows": [row]}
    manifest_path = dataset / "manifest.json"
    write_json(manifest_path, manifest)
    return tmp_path / "datasets", manifest_path


def test_positive_crop_with_contained_truth_is_safe(tmp_path: Path):
    root, production = fixture(tmp_path, white_status="PRESENT", crop=[150, 150, 550, 350])
    receipt = MODULE.audit(root, production)
    assert receipt["classification_counts"] == {"proven_safe": 1}
    assert receipt["production_reuse_gate_passed"] is True


def test_missing_class_shallow_crop_is_quarantined(tmp_path: Path):
    root, production = fixture(tmp_path, white_status="MISSING", crop=[150, 180, 550, 330])
    receipt = MODULE.audit(root, production)
    assert receipt["classification_counts"] == {"negative_context_risk": 1}
    assert receipt["production_reuse_gate_passed"] is False
    assert receipt["production_quarantine"][0]["warnings"] == [
        "missing_class_with_incomplete_physical_context"
    ]


def test_present_label_outside_crop_is_structural_failure(tmp_path: Path):
    root, production = fixture(tmp_path, white_status="PRESENT", crop=[200, 200, 500, 270])
    receipt = MODULE.audit(root, production)
    assert receipt["classification_counts"] == {"structural_failure": 1}
    assert "crop_does_not_contain_white_label" in receipt["production_quarantine"][0]["issues"]


def test_yolo_rounding_at_image_edge_is_not_out_of_bounds(tmp_path: Path):
    root, production = fixture(tmp_path, white_status="PRESENT", crop=[150, 150, 550, 350])
    manifest = json.loads(production.read_text(encoding="utf-8"))
    label_path = Path(manifest["derived_rows"][0]["derived_label"])
    label_path.write_text(
        "0 0.81487672 0.5 0.37024657 0.2\n1 0.5 0.5 0.2 0.2\n",
        encoding="utf-8",
    )
    manifest["derived_rows"][0]["derived_label_sha256"] = MODULE.sha256_file(label_path)
    production.write_text(json.dumps(manifest), encoding="utf-8")
    receipt = MODULE.audit(root, production)
    assert receipt["classification_counts"] == {"proven_safe": 1}
