from __future__ import annotations

from types import SimpleNamespace

import numpy as np
import pytest

from label_qc.services.screening import (
    DetectedLabel,
    ScreeningParams,
    ScreeningResult,
    TrayResult,
)
from label_qc.services.v8_pipeline import (
    CANDIDATE_MIN_CROP_PX,
    LABEL_CONFIDENCE,
    DEFAULT_LABEL_FALLBACK_INPUT_SIZE,
    PIPELINE_ID,
    PRODUCTION_TRAY_CONFIDENCE,
    TRAY_SUPPLEMENT_FORMAT_ULTRALYTICS_NMS,
    TRAY_SUPPLEMENT_MODE_FULL_FRAME_WORK_AREA,
    LabelQcV8Models,
    _GenericLabelTypeDetector,
    _MissingClassFallbackDetector,
    _ProductionPreservingSupplementDetector,
    _V8CascadeModels,
    _apply_fail_closed_policies,
    _apply_shallow_extended_ownership,
    _label_fallback_input_size,
    _label_fallback_onnx_threads,
    _label_supplement_classes,
    _tray_full_frame_supplement_contract,
    _validate_label_fallback_input_shape,
)


def test_legacy_tray_supplement_contract_remains_unchanged() -> None:
    assert _tray_full_frame_supplement_contract({}) is None


def test_full_frame_work_area_contract_is_explicit() -> None:
    assert _tray_full_frame_supplement_contract({
        "models": {"tray_full_frame_supplement": {}},
        "trayFullFrameSupplementFormat": "ultralytics_nms",
        "trayFullFrameSupplementMode": "full_frame_work_area_v1",
        "trayFullFrameSupplementConfidence": 0.40,
    }) == (
        TRAY_SUPPLEMENT_FORMAT_ULTRALYTICS_NMS,
        TRAY_SUPPLEMENT_MODE_FULL_FRAME_WORK_AREA,
        0.40,
    )


@pytest.mark.parametrize(
    "manifest",
    [
        {"trayFullFrameSupplementFormat": "ultralytics_nms"},
        {"models": {"tray_full_frame_supplement": {}}},
        {
            "models": {"tray_full_frame_supplement": {}},
            "trayFullFrameSupplementFormat": "yolox",
            "trayFullFrameSupplementMode": "full_frame_work_area_v1",
            "trayFullFrameSupplementConfidence": 0.40,
        },
        {
            "models": {"tray_full_frame_supplement": {}},
            "trayFullFrameSupplementFormat": "ultralytics_nms",
            "trayFullFrameSupplementMode": "full_frame_work_area_v1",
            "trayFullFrameSupplementConfidence": 0.01,
        },
    ],
)
def test_invalid_tray_supplement_contract_fails_closed(manifest: dict) -> None:
    with pytest.raises(RuntimeError, match="invalid v8 full-frame tray supplement contract"):
        _tray_full_frame_supplement_contract(manifest)


def test_full_frame_work_area_supplement_preserves_base_and_filters_far_background() -> None:
    from label_qc.services.yolo_detector import Detection

    class _Production:
        def detect_trays(self, image, confidence):
            assert image.shape == (1000, 500, 3)
            assert confidence == 0.60
            return [Detection(100, 300, 250, 450, 0.90, 0)]

    class _Supplement:
        def __init__(self) -> None:
            self.shape = None

        def detect(self, image, confidence):
            self.shape = image.shape
            assert confidence == 0.40
            return [
                Detection(105, 305, 245, 445, 0.95, 0),  # production overlap
                Detection(100, 50, 120, 80, 0.90, 0),   # reviewed far-background rule
                Detection(200, 30, 260, 90, 0.85, 0),   # top but not tiny: retain
                Detection(50, 500, 180, 650, 0.80, 0),  # workbench supplement
            ]

    class _LegacySupplement:
        def detect(self, image, confidence):
            return []

    supplement = _Supplement()
    models = _V8CascadeModels(
        production=_Production(),
        tray_supplement=_LegacySupplement(),
        production_label=SimpleNamespace(),
        candidate_label=SimpleNamespace(reset_stats=lambda: None),
        tray_full_frame_supplement=supplement,
        full_frame_tray_confidence=0.40,
    )

    rows = models.detect_trays(np.zeros((1000, 500, 3), dtype=np.uint8), 0.60)

    assert supplement.shape == (1000, 500, 3)
    assert [row.as_xyxy() for row in rows] == [
        [100, 300, 250, 450],
        [200, 30, 260, 90],
        [50, 500, 180, 650],
    ]
    assert models.last_stats == {
        "mode": "production_legacy_plus_candidate_full_frame_work_area",
        "legacyBaseMode": "production_full_plus_candidate_lower_band",
        "legacyBaseDetections": 1,
        "candidateFullFrameDetections": 4,
        "candidateProductionOverlapRejected": 1,
        "candidateFarBackgroundRejected": 1,
        "candidateDuplicateRejected": 0,
        "farBackgroundMaxCenterYRatio": 0.18,
        "farBackgroundMaxHeightRatio": 0.037,
        "productionPreservingMergeApplied": True,
        "legacyBoxesPreserved": 1,
        "supplementBoxesKept": 2,
    }


class _PolicyModels:
    def __init__(self, source: str) -> None:
        self.last_raw_detections = [
            {
                "source": source,
                "box": [10.0, 10.0, 260.0, 100.0],
                "confidence": 0.8,
                "class_id": 0,
            }
        ]


def _result(tray: TrayResult) -> SimpleNamespace:
    return SimpleNamespace(trays=[tray], params=ScreeningParams(min_crop_px=120))


def test_v8_recommended_parameters_are_frozen() -> None:
    models = LabelQcV8Models()
    params = models.recommended_params()

    assert models.customer_auto_pass_supported is True
    assert params.tray_conf == PRODUCTION_TRAY_CONFIDENCE
    assert params.label_conf == LABEL_CONFIDENCE
    assert params.min_crop_px == CANDIDATE_MIN_CROP_PX


def test_shallow_production_missing_claim_fails_closed() -> None:
    tray = TrayResult(
        index=0,
        box=[10.0, 10.0, 260.0, 100.0],
        confidence=0.8,
        has_white=True,
        has_color=False,
        verdict="MISSING_COLOR_LABEL",
        own_label_count=1,
        labels=[DetectedLabel(class_id=0, confidence=0.9, box=[20, 20, 60, 50])],
    )

    events = _apply_fail_closed_policies(
        _result(tray), np.zeros((800, 600, 3), dtype=np.uint8),
        _PolicyModels("production_full"),
    )

    assert tray.verdict == "UNJUDGEABLE"
    assert events[0]["reason"] == "sub120_extreme_wide_production_tray"


def test_complete_production_missing_claim_is_preserved() -> None:
    tray = TrayResult(
        index=0,
        box=[10.0, 10.0, 260.0, 210.0],
        confidence=0.8,
        has_white=True,
        has_color=False,
        verdict="MISSING_COLOR_LABEL",
        own_label_count=1,
        labels=[DetectedLabel(class_id=0, confidence=0.9, box=[20, 20, 60, 50])],
    )
    models = _PolicyModels("production_full")
    models.last_raw_detections[0]["box"] = [10.0, 10.0, 260.0, 210.0]

    events = _apply_fail_closed_policies(
        _result(tray), np.zeros((800, 600, 3), dtype=np.uint8), models
    )

    assert tray.verdict == "MISSING_COLOR_LABEL"
    assert events == []


class _ExtendedOwnershipDetector:
    def __init__(self, class_ids: list[int]) -> None:
        self.class_ids = class_ids

    def detect(self, _image, _conf):
        from label_qc.services.yolo_detector import Detection

        return [
            Detection(
                x0=180.0, y0=70.0 + index * 150.0,
                x1=260.0, y1=130.0 + index * 150.0,
                confidence=0.9, class_id=class_id,
            )
            for index, class_id in enumerate(self.class_ids)
        ]


@pytest.mark.parametrize(
    "class_ids,top,expected",
    [
        ([1], 20.0, "MISSING_WHITE_LABEL"),
        ([0, 1], 20.0, "CLEAR"),
        ([0], 0.0, "UNJUDGEABLE"),
        ([1], 1.0, "UNJUDGEABLE"),
        ([0, 1], 0.0, "CLEAR"),
        ([0], 1.01, "MISSING_COLOR_LABEL"),
    ],
)
def test_reviewed_shallow_tray_rechecks_extended_lower_compartment(
    class_ids: list[int], top: float, expected: str,
) -> None:
    tray = TrayResult(
        index=0, box=[100.0, top, 300.0, top + 80.0], confidence=0.8,
        has_white=False, has_color=True, verdict="UNJUDGEABLE",
    )
    result = _result(tray)
    models = SimpleNamespace(
        production_label=_ExtendedOwnershipDetector(class_ids),
    )

    events = _apply_shallow_extended_ownership(
        result,
        np.zeros((240, 400, 3), dtype=np.uint8),
        models,
        [{
            "trayIndex": 0, "reason": "sub120_extreme_wide_production_tray",
            "finalVerdict": "UNJUDGEABLE",
        }],
    )

    assert tray.verdict == expected
    assert tray.needs_review == (expected != "CLEAR")
    assert tray.has_white == (0 in class_ids)
    assert tray.has_color == (1 in class_ids)
    assert tray.own_label_count == len(class_ids)
    assert events[0]["finalVerdict"] == expected
    assert events[0]["reason"] == (
        "top_boundary_shallow_single_class_requires_review"
        if expected == "UNJUDGEABLE"
        else "reviewed_shallow_two_compartment_extended_ownership"
    )


def test_extended_ownership_does_not_reopen_non_shallow_unjudgeable() -> None:
    tray = TrayResult(
        index=0, box=[100.0, 20.0, 300.0, 180.0], confidence=0.8,
        has_white=False, has_color=True, verdict="UNJUDGEABLE",
    )
    events = _apply_shallow_extended_ownership(
        _result(tray), np.zeros((240, 400, 3), dtype=np.uint8),
        SimpleNamespace(production_label=_ExtendedOwnershipDetector([0, 1])),
        [{
            "trayIndex": 0, "reason": "portrait_geometry_incomplete_tray",
            "finalVerdict": "UNJUDGEABLE",
        }],
    )

    assert tray.verdict == "UNJUDGEABLE"
    assert events == []


def test_shallow_crop_below_minimum_can_use_extended_ownership() -> None:
    tray = TrayResult(
        index=0, box=[100.0, 20.0, 300.0, 80.0], confidence=0.8,
        has_white=False, has_color=False, verdict="UNJUDGEABLE",
    )

    events = _apply_shallow_extended_ownership(
        _result(tray), np.zeros((240, 400, 3), dtype=np.uint8),
        SimpleNamespace(production_label=_ExtendedOwnershipDetector([1])),
        [],
    )

    assert tray.verdict == "MISSING_WHITE_LABEL"
    assert events[0]["sourceFailClosedReason"] == (
        "crop_below_minimum_or_preexisting_unjudgeable"
    )
def test_classifier_blob_matches_frozen_shape_and_normalization() -> None:
    image = np.full((100, 200, 3), 255, dtype=np.uint8)

    blob = _GenericLabelTypeDetector._classifier_blob(
        image, [50.0, 20.0, 150.0, 80.0]
    )

    assert blob.shape == (1, 3, 160, 160)
    assert blob.dtype == np.float32
    assert float(blob.max()) == 1.0
    assert float(blob.min()) > -0.2


def test_fallback_detector_applies_bundle_confidence_floor() -> None:
    class _Primary:
        @staticmethod
        def detect(_image, _conf):
            return [SimpleNamespace(class_id=0)]

        @staticmethod
        def reset_stats() -> None:
            return None

    class _Fallback:
        calls: list[float] = []

        def detect(self, _image, conf):
            self.calls.append(conf)
            return []

    fallback = _Fallback()
    detector = _MissingClassFallbackDetector(
        _Primary(), fallback, fallback_confidence_floor=0.40,
    )

    detector.detect(np.zeros((120, 160, 3), dtype=np.uint8), 0.20)

    assert fallback.calls == [0.40]


def test_fallback_detector_keeps_both_present_labels_on_fast_path() -> None:
    class _Primary:
        @staticmethod
        def detect(_image, _conf):
            return [SimpleNamespace(class_id=0), SimpleNamespace(class_id=1)]

        @staticmethod
        def reset_stats() -> None:
            return None

        @staticmethod
        def stats() -> dict[str, int]:
            return {"calls": 1}

    class _Fallback:
        calls = 0

        def detect(self, _image, _conf):
            self.calls += 1
            return []

    fallback = _Fallback()
    detector = _MissingClassFallbackDetector(_Primary(), fallback)

    labels = detector.detect(np.zeros((120, 160, 3), dtype=np.uint8), 0.20)

    assert {label.class_id for label in labels} == {0, 1}
    assert fallback.calls == 0
    assert detector.stats()["fallbackCalls"] == 0


def test_production_preserving_supplement_admits_only_best_owned_missing_class() -> None:
    from label_qc.services.yolo_detector import Detection

    class _Detector:
        def __init__(self, rows):
            self.rows = rows
            self.calls = 0

        def detect(self, _image, _conf):
            self.calls += 1
            return list(self.rows)

    primary = _Detector([
        Detection(20, 20, 50, 50, 0.8, 0),
    ])
    supplement = _Detector([
        Detection(30, 30, 60, 60, 0.70, 1),
        Detection(35, 35, 65, 65, 0.90, 1),
        Detection(0, 0, 10, 10, 0.99, 1),
        Detection(40, 40, 70, 70, 0.99, 0),
    ])
    detector = _ProductionPreservingSupplementDetector(primary, supplement)

    rows = detector.detect(np.zeros((100, 100, 3), dtype=np.uint8), 0.20)

    assert [(row.class_id, row.confidence) for row in rows] == [(0, 0.8), (1, 0.90)]
    assert detector.stats()["admitted"] == {"whiteLabel": 0, "colorLabel": 1}


def test_production_preserving_supplement_skips_when_owned_classes_exist() -> None:
    rows = [SimpleNamespace(class_id=0, x0=20, y0=20, x1=40, y1=40),
            SimpleNamespace(class_id=1, x0=50, y0=50, x1=70, y1=70)]
    primary = SimpleNamespace(detect=lambda _image, _conf: rows)
    supplement = SimpleNamespace(detect=lambda *_args: pytest.fail("must not run"))
    detector = _ProductionPreservingSupplementDetector(primary, supplement)

    assert detector.detect(np.zeros((100, 100, 3), dtype=np.uint8), 0.20) == rows


def test_color_only_supplement_requires_production_white_evidence() -> None:
    supplement = SimpleNamespace(detect=lambda *_args: pytest.fail("must not run"))
    detector = _ProductionPreservingSupplementDetector(
        SimpleNamespace(detect=lambda *_args: []), supplement, allowed_classes=(1,),
    )

    assert detector.detect(np.zeros((100, 100, 3), dtype=np.uint8), 0.20) == []


def test_bundle_binds_explicit_supplement_classes() -> None:
    assert _label_supplement_classes({"labelSupplementClasses": [1]}) == frozenset({1})


@pytest.mark.parametrize("value", [None, [], [2], [1, 1], [True], "1"])
def test_invalid_supplement_classes_fail_closed(value: object) -> None:
    with pytest.raises(RuntimeError, match="invalid v8 label supplement classes"):
        _label_supplement_classes({"labelSupplementClasses": value})


def test_legacy_bundle_keeps_existing_fallback_resolution() -> None:
    assert _label_fallback_input_size({}) == DEFAULT_LABEL_FALLBACK_INPUT_SIZE


@pytest.mark.parametrize("size", [640, 960, 1280])
def test_bundle_can_select_validated_fallback_resolution(size: int) -> None:
    assert _label_fallback_input_size({"labelFallbackInputSize": size}) == size


@pytest.mark.parametrize("size", [0, 639, 641, 1920, True, "1280", 1280.0, None])
def test_invalid_fallback_resolution_fails_closed(size: object) -> None:
    with pytest.raises(RuntimeError, match="invalid v8 label fallback input size"):
        _label_fallback_input_size({"labelFallbackInputSize": size})


def test_legacy_bundle_keeps_shared_onnx_thread_setting() -> None:
    assert _label_fallback_onnx_threads({}) is None


@pytest.mark.parametrize("threads", [1, 2, 4, 6, 8])
def test_bundle_can_select_bounded_fallback_threads(threads: int) -> None:
    assert _label_fallback_onnx_threads({"labelFallbackOnnxThreads": threads}) == threads


@pytest.mark.parametrize("threads", [0, 9, True, "6", 6.0])
def test_invalid_fallback_threads_fail_closed(threads: object) -> None:
    with pytest.raises(RuntimeError, match="invalid v8 label fallback ONNX threads"):
        _label_fallback_onnx_threads({"labelFallbackOnnxThreads": threads})


def test_configured_fallback_resolution_must_match_bound_onnx_shape() -> None:
    detector = SimpleNamespace(
        _session=SimpleNamespace(
            get_inputs=lambda: [SimpleNamespace(shape=[1, 3, 1280, 1280])]
        )
    )

    _validate_label_fallback_input_shape(detector, 1280)

    with pytest.raises(RuntimeError, match="v8 label fallback input shape drift"):
        _validate_label_fallback_input_shape(detector, 640)


def test_bundle_hash_drift_disables_v8_without_legacy_fallback(tmp_path) -> None:
    (tmp_path / "manifest.json").write_text(
        '{"pipelineId":"' + PIPELINE_ID + '","models":{}}',
        encoding="utf-8",
    )
    models = LabelQcV8Models(tmp_path)

    assert models.available is False
    assert models.fail_closed is True
    assert "manifest filename drift" in str(models.load_error)


def test_runtime_audit_is_bound_to_each_screening_result(monkeypatch) -> None:
    class _Cascade:
        last_stats = {"count": 1}
        last_raw_detections = []

        def reset_label_stats(self) -> None:
            return None

        def label_stats(self) -> dict[str, int]:
            return {"calls": 2}

    result = ScreeningResult(
        trays=[],
        image_width=640,
        image_height=480,
        params=ScreeningParams(),
    )
    monkeypatch.setattr(
        "label_qc.services.screening.screen_image",
        lambda image, models, params: result,
    )
    models = LabelQcV8Models()
    models._models = _Cascade()
    models._loaded = True
    models._manifest = {"modelId": "bound-model"}

    returned = models.screen_image(
        np.zeros((480, 640, 3), dtype=np.uint8), ScreeningParams(),
    )

    assert returned.runtime_audit["modelId"] == "bound-model"
    assert returned.runtime_audit["labelFallbackInputSize"] == 640
    assert returned.runtime_audit["labelFallbackOnnxThreads"] is None
    assert returned.runtime_audit["tray"] == {"count": 1}
    assert returned.runtime_audit["label"] == {"calls": 2}
