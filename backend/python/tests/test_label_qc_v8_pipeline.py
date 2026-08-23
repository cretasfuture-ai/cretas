from __future__ import annotations

from types import SimpleNamespace

import numpy as np

from label_qc.services.screening import (
    DetectedLabel,
    ScreeningParams,
    ScreeningResult,
    TrayResult,
)
from label_qc.services.v8_pipeline import (
    CANDIDATE_MIN_CROP_PX,
    LABEL_CONFIDENCE,
    PIPELINE_ID,
    PRODUCTION_TRAY_CONFIDENCE,
    LabelQcV8Models,
    _GenericLabelTypeDetector,
    _apply_fail_closed_policies,
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


def test_classifier_blob_matches_frozen_shape_and_normalization() -> None:
    image = np.full((100, 200, 3), 255, dtype=np.uint8)

    blob = _GenericLabelTypeDetector._classifier_blob(
        image, [50.0, 20.0, 150.0, 80.0]
    )

    assert blob.shape == (1, 3, 160, 160)
    assert blob.dtype == np.float32
    assert float(blob.max()) == 1.0
    assert float(blob.min()) > -0.2


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
    assert returned.runtime_audit["tray"] == {"count": 1}
    assert returned.runtime_audit["label"] == {"calls": 2}
