import importlib.util
import json
from pathlib import Path


MODULE = Path(__file__).parents[1] / "admit_reviewed_target_trays.py"
SPEC = importlib.util.spec_from_file_location("admit_reviewed_target_trays", MODULE)
admission = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(admission)


def test_validate_review_admits_confirmed_missing_complement_only():
    source = {"crop_id": "c1", "prior_human_verdict": "MISSING_WHITE_LABEL"}
    annotation = {
        "crop_id": "c1", "source": "human", "reviewed": True, "unjudgeable": False,
        "missing_confirmed_by_human": True, "declared_missing_classes": ["white_label"],
        "boxes": [{"c": 1, "b": [0.1, 0.2, 0.3, 0.4]}],
    }
    assert admission.validate_review(source, annotation) is None


def test_validate_review_excludes_prior_unjudgeable_even_when_reviewed():
    source = {"crop_id": "c1", "prior_human_verdict": "UNJUDGEABLE"}
    annotation = {
        "crop_id": "c1", "source": "human", "reviewed": True, "unjudgeable": False,
        "missing_confirmed_by_human": False, "declared_missing_classes": [],
        "boxes": [{"c": 0, "b": [0.1, 0.2, 0.3, 0.4]}],
    }
    assert admission.validate_review(source, annotation) == "not_a_confirmed_missing_label_hard_example"


def test_admit_preserves_source_and_writes_derived_queue(tmp_path: Path):
    source = tmp_path / "source"
    (source / "images").mkdir(parents=True)
    (source / "annotations-human").mkdir()
    image = source / "images" / "c1.jpg"
    image.write_bytes(b"image")
    annotation_path = source / "annotations-human" / "c1.json"
    annotation_path.write_text(json.dumps({
        "crop_id": "c1", "source": "human", "reviewed": True, "unjudgeable": False,
        "missing_confirmed_by_human": True, "declared_missing_classes": ["white_label"],
        "boxes": [{"c": 1, "b": [0.1, 0.2, 0.3, 0.4]}],
    }), encoding="utf-8")
    manifest = source / "manifest.json"
    manifest.write_text(json.dumps({"rows": [{
        "crop_id": "c1", "image": str(image), "image_sha256": admission.sha256_file(image),
        "source_task_id": "t1", "prior_human_verdict": "MISSING_WHITE_LABEL",
        "training_eligible": False,
    }]}), encoding="utf-8")
    before = {path: path.read_bytes() for path in (image, annotation_path, manifest)}

    receipt = admission.admit(source, tmp_path / "derived", tmp_path / "receipt.json", 1)

    assert receipt["admitted"] == 1
    assert receipt["originals_modified"] == 0
    assert all(path.read_bytes() == payload for path, payload in before.items())
    derived = json.loads((tmp_path / "derived" / "manifest.json").read_text(encoding="utf-8"))
    assert derived["rows"][0]["training_eligible"] is True
