import importlib.util
import sys
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "filter_tray_teacher_review_queue.py"
sys.path.insert(0, str(SCRIPT.parent))
SPEC = importlib.util.spec_from_file_location("filter_tray_teacher_review_queue", SCRIPT)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def test_retained_proposals_keeps_detector_and_only_independent_multi_prompt_teacher():
    rows = [
        {"box": [0.1, 0.1, 0.3, 0.3], "source": "tray_detector", "confidence": 0.9, "priority": 3},
        {"box": [0.4, 0.4, 0.6, 0.6], "source": "locateanything_teacher", "confidence": 0.02, "priority": 1, "support": ["crop0:all", "crop0:edge"]},
        {"box": [0.6, 0.6, 0.8, 0.8], "source": "locateanything_teacher", "confidence": 0.02, "priority": 1, "support": ["crop0:all"]},
        {"box": [0.12, 0.12, 0.32, 0.32], "source": "locateanything_teacher", "confidence": 0.4, "priority": 1, "support": ["crop0:all", "crop0:edge", "tray_detector_low_iou"]},
    ]
    retained = module.retained_proposals(rows)
    assert [row["box"] for row in retained] == [rows[0]["box"], rows[1]["box"]]
