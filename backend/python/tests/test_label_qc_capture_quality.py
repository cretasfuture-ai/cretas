from __future__ import annotations

import cv2
import numpy as np

from label_qc.services.capture_quality import (
    MIN_SHARPNESS,
    evaluate_capture_quality,
)


def _sharp_photo() -> np.ndarray:
    image = np.zeros((1600, 1200, 3), dtype=np.uint8)
    for y in range(0, 1600, 24):
        cv2.line(image, (0, y), (1199, y), (255, 255, 255), 4)
    for x in range(0, 1200, 24):
        cv2.line(image, (x, 0), (x, 1599), (255, 255, 255), 4)
    return image


FULL_TRAYS = [
    [72, 250, 365, 700],
    [445, 250, 755, 700],
    [835, 250, 1128, 700],
]


def test_accepts_sharp_photo_with_side_margin() -> None:
    result = evaluate_capture_quality(_sharp_photo(), FULL_TRAYS)

    assert result.accepted is True
    assert result.reason_code is None
    assert result.tray_count == 3
    assert result.sharpness >= MIN_SHARPNESS


def test_rejects_obviously_blurry_photo() -> None:
    blurred = cv2.GaussianBlur(_sharp_photo(), (25, 25), 0)
    result = evaluate_capture_quality(blurred, FULL_TRAYS)

    assert result.accepted is False
    assert result.reason_code == "PHOTO_BLURRY"
    assert "重新拍摄" in result.message


def test_rejects_tray_cut_off_at_right_side() -> None:
    cropped = [*FULL_TRAYS[:2], [835, 250, 1198, 700]]
    result = evaluate_capture_quality(_sharp_photo(), cropped)

    assert result.accepted is False
    assert result.reason_code == "TRAY_CROPPED_AT_SIDE"
    assert "广角" in result.message


def test_rejects_when_detector_cannot_find_a_full_row() -> None:
    result = evaluate_capture_quality(_sharp_photo(), FULL_TRAYS[:2])

    assert result.accepted is False
    assert result.reason_code == "TRAYS_NOT_FOUND"


def test_resolution_gate_runs_before_detector_coverage() -> None:
    small = np.zeros((640, 480, 3), dtype=np.uint8)
    result = evaluate_capture_quality(small, [])

    assert result.accepted is False
    assert result.reason_code == "RESOLUTION_TOO_LOW"
