from __future__ import annotations

import importlib.util
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location(
    "label_qc_tray_edge_context_eval", ROOT / "label_qc_tray_edge_context_eval.py",
)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


@dataclass(frozen=True)
class _Detection:
    x0: float
    y0: float
    x1: float
    y1: float
    confidence: float
    class_id: int = 0

    def as_xyxy(self):
        return [self.x0, self.y0, self.x1, self.y1]


class _StaticModels:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = 0

    def detect_trays(self, image, conf):
        response = self.responses[min(self.calls, len(self.responses) - 1)]
        self.calls += 1
        return list(response)

    def detect_labels(self, crop, conf):
        return []


def test_edge_regions_are_fixed_symmetric_lower_windows():
    regions = module.edge_regions(1000, 800, 0.5, 0.6)

    assert regions == [
        ("lower_left", 0, 320, 500, 800),
        ("lower_right", 500, 320, 1000, 800),
    ]


def test_outer_band_filter_keeps_only_corresponding_lower_corner_centres():
    assert module.belongs_to_outer_band(
        [20, 500, 320, 700], "lower_left", 1000, 800, 0.4, 0.6, 0.55,
    ) is True
    assert module.belongs_to_outer_band(
        [680, 500, 980, 700], "lower_right", 1000, 800, 0.4, 0.6, 0.55,
    ) is True
    assert module.belongs_to_outer_band(
        [420, 500, 620, 700], "lower_left", 1000, 800, 0.4, 0.6, 0.55,
    ) is False
    assert module.belongs_to_outer_band(
        [20, 100, 320, 300], "lower_left", 1000, 800, 0.4, 0.6, 0.55,
    ) is False


def test_physical_source_edge_gate_rejects_cut_off_rescues_only():
    width, height = 3072, 4096

    assert module.clears_physical_source_edges(
        [2005.931, 3002.792, 3072.0, 4096.0], width, height,
    ) is False
    assert module.clears_physical_source_edges(
        [2070.64, 3265.273, 3040.037, 4063.058], width, height,
    ) is False
    assert module.clears_physical_source_edges(
        [208.559, 2723.698, 889.615, 3453.052], width, height,
    ) is True


def test_physical_source_edge_gate_validates_margin_ratio():
    try:
        module.clears_physical_source_edges([10, 10, 20, 20], 100, 100, 0.5)
    except ValueError as error:
        assert "source edge margin ratio" in str(error)
    else:
        raise AssertionError("invalid source edge margin must fail closed")


def test_full_layout_preserves_production_and_skips_edge_model():
    full = [_Detection(index * 10, 100, index * 10 + 8, 120, 0.9) for index in range(8)]
    production = _StaticModels([full])
    edge = _StaticModels([[_Detection(10, 10, 20, 20, 0.8)]])
    wrapper = module.EdgeContextTrayModels(
        production, _Detection, edge_base=edge, skip_full_layout_count=8,
    )

    result = wrapper.detect_trays(np.zeros((800, 1000, 3), dtype=np.uint8), 0.6)

    assert result == full
    assert production.calls == 1
    assert edge.calls == 0
    assert wrapper.last_stats["edge_windows_skipped_full_layout"] is True


def test_edge_candidate_overlapping_production_is_rejected_before_merge():
    production_detection = _Detection(100, 500, 400, 700, 0.9)
    production = _StaticModels([[production_detection]])
    edge = _StaticModels([
        [_Detection(110, 180, 410, 380, 0.5)],
        [],
    ])
    wrapper = module.EdgeContextTrayModels(
        production, _Detection, edge_base=edge,
        production_overlap_ios=0.60,
    )

    result = wrapper.detect_trays(np.zeros((800, 1000, 3), dtype=np.uint8), 0.6)

    assert result == [production_detection]
    assert wrapper.last_stats["edge_production_overlap_rejected"] == 1


def test_preserving_merge_never_replaces_a_base_box_with_higher_confidence_edge_box():
    production_detection = _Detection(100, 500, 400, 700, 0.5)
    candidate_detection = _Detection(130, 180, 430, 380, 0.9)
    production = _StaticModels([[production_detection]])
    edge = _StaticModels([[candidate_detection], []])
    wrapper = module.EdgeContextTrayModels(
        production,
        _Detection,
        edge_base=edge,
        production_overlap_ios=0.95,
        preserve_base_detections=True,
    )

    result = wrapper.detect_trays(np.zeros((800, 1000, 3), dtype=np.uint8), 0.6)

    assert result == [production_detection]
    assert wrapper.last_stats["duplicates_removed"] == 1


def test_runtime_wrapper_uses_original_v8_cascade_as_preserved_base():
    production_detection = _Detection(100, 500, 400, 700, 0.7)

    class _Inner:
        def __init__(self):
            self.last_raw_detections = []
            self.last_stats = {}
            self.routes = []

        def detect_trays(self, _image, _conf):
            self.last_raw_detections = [{
                "source": "candidate_lower_band",
                "box": production_detection.as_xyxy(),
                "confidence": production_detection.confidence,
                "class_id": production_detection.class_id,
            }]
            self.last_stats = {"mode": "v8_preserved_cascade"}
            return [production_detection]

        def _set_label_routes(self, _image, detections):
            self.routes = list(detections)

    class _Runtime:
        def __init__(self, inner):
            self.inner = inner

        def _require_models(self):
            return self.inner

    class _V8:
        @staticmethod
        def _box_key(box):
            return tuple(round(float(value), 3) for value in box)

        @staticmethod
        def _detection_row(detection, source):
            return {
                "source": source,
                "box": detection.as_xyxy(),
                "confidence": detection.confidence,
                "class_id": detection.class_id,
            }

    inner = _Inner()
    edge_models = module.EdgeContextTrayModels(
        _StaticModels([[]]), _Detection, edge_base=_StaticModels([[], []]),
    )
    wrapper = module.RuntimeEdgeContextModels(_Runtime(inner), edge_models, _V8)

    result = wrapper._detect_trays(
        np.zeros((800, 1000, 3), dtype=np.uint8), 0.6,
    )

    assert result == [production_detection]
    assert inner.last_raw_detections[0]["source"] == "candidate_lower_band"
    assert inner.routes == [production_detection]


def test_full_frame_supplement_preserves_base_and_adds_only_new_box():
    production_detection = _Detection(0, 0, 100, 100, 0.5)
    replacement = _Detection(5, 5, 105, 105, 0.9)
    supplement = _Detection(200, 200, 300, 300, 0.7)
    wrapper = module.FullFrameSupplementTrayModels(
        _StaticModels([[production_detection]]),
        _StaticModels([[replacement, supplement]]),
        candidate_confidence=0.5,
    )

    result = wrapper.detect_trays(np.zeros((400, 400, 3), dtype=np.uint8), 0.6)

    assert result == [production_detection, supplement]
    assert wrapper.last_stats["candidate_production_overlap_rejected"] == 1
    assert wrapper.last_stats["candidate_supplements_kept"] == 1


def test_corrected_gate_ignores_invalid_legacy_targets_but_requires_judgeable_target():
    baseline = {
        "false_flags": 4, "p95_latency_ms": 100,
        "details": [
            {"photo_id": "target", "kind": "defect", "hit": False, "tray_target_covered": False},
            {"photo_id": "invalid", "kind": "defect", "hit": False, "tray_target_covered": False},
            {"photo_id": "normal", "kind": "normal", "false_flags": 4},
        ],
    }
    candidate = {
        "false_flags": 3, "p95_latency_ms": 120,
        "details": [
            {"photo_id": "target", "kind": "defect", "hit": True, "tray_target_covered": True},
            {"photo_id": "invalid", "kind": "defect", "hit": False, "tray_target_covered": False},
            {"photo_id": "normal", "kind": "normal", "false_flags": 3},
        ],
    }
    audit = {"rows": [
        {"photo_id": "target", "status": "JUDGEABLE_HUMAN_TRAY_MATCH", "counted_as_tray_detector_miss": True},
        {"photo_id": "invalid", "status": "OCCLUDED_OR_INVALID_LEGACY_TARGET_IGNORE", "counted_as_tray_detector_miss": False},
    ]}

    gate = module.corrected_gate(baseline, candidate, audit, 0, 0)

    assert gate["offline_development_passed"] is True
    assert gate["judgeable_targets"][0]["candidate_hit"] is True
    assert gate["ignored_legacy_targets"] == [
        {"photo_id": "invalid", "status": "OCCLUDED_OR_INVALID_LEGACY_TARGET_IGNORE"},
    ]
    assert gate["promotion_allowed"] is False


def test_corrected_gate_rejects_independent_normal_false_flags():
    baseline = {
        "false_flags": 0, "p95_latency_ms": 100,
        "details": [{
            "photo_id": "target", "kind": "defect", "hit": False,
            "tray_target_covered": False,
        }],
    }
    candidate = {
        "false_flags": 0, "p95_latency_ms": 110,
        "details": [{
            "photo_id": "target", "kind": "defect", "hit": True,
            "tray_target_covered": True,
        }],
    }
    audit = {"rows": [{
        "photo_id": "target", "status": "JUDGEABLE_HUMAN_TRAY_MATCH",
        "counted_as_tray_detector_miss": True,
    }]}

    gate = module.corrected_gate(
        baseline, candidate, audit, 0, 0,
        {"records": 277, "false_flags": 1},
    )

    assert gate["offline_development_passed"] is False
    assert gate["independent_normal_records"] == 277
    assert gate["independent_normal_false_flags"] == 1
    assert "independent normal replay added false flags" in gate["errors"]
