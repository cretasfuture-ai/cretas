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
    LabelQcV8Models,
    _GenericLabelTypeDetector,
    _MissingClassFallbackDetector,
    _apply_fail_closed_policies,
    _apply_shallow_extended_ownership,
    _label_fallback_input_size,
    _label_fallback_onnx_threads,
    _validate_label_fallback_input_shape,
)


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
    "class_ids,expected",
    [([1], "MISSING_WHITE_LABEL"), ([0, 1], "CLEAR")],
)
def test_reviewed_shallow_tray_rechecks_extended_lower_compartment(
    class_ids: list[int], expected: str,
) -> None:
    tray = TrayResult(
        index=0, box=[100.0, 20.0, 300.0, 100.0], confidence=0.8,
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
    assert tray.own_label_count == len(class_ids)
    assert events[0]["finalVerdict"] == expected


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
