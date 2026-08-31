import importlib.util
import sys
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "convert_admitted_target_trays.py"
sys.path.insert(0, str(MODULE_PATH.parent))
SPEC = importlib.util.spec_from_file_location("convert_admitted_target_trays", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def test_select_independent_rows_excludes_prior_and_same_batch_overlap():
    rows = [
        {"crop_id": "prior", "source_task_id": "task-prior", "image_sha256": "hash-a"},
        {"crop_id": "one", "source_task_id": "task-one", "image_sha256": "hash-b"},
        {"crop_id": "same-task", "source_task_id": "task-one", "image_sha256": "hash-c"},
        {"crop_id": "two", "source_task_id": "task-two", "image_sha256": "hash-d"},
    ]
    accepted, excluded = MODULE.select_independent_rows(rows, {"task-prior"}, set())
    assert [row["crop_id"] for row in accepted] == ["one", "two"]
    assert [row["reason"] for row in excluded] == [
        "prior_dataset_task_overlap", "same_batch_task_overlap",
    ]
