"""Fast capture-time quality checks for Liushanmen label-QC photos.

This gate is deliberately narrower than the full label analysis pipeline.  It
uses the production tray detector only to answer whether the photographed tray
array is usable, and a conservative sharpness threshold to reject obvious
motion/focus blur.  It never makes white/color label claims.
"""
from __future__ import annotations

import asyncio
import io
from dataclasses import dataclass
from typing import Any, Iterable, Sequence

import cv2
import numpy as np
from PIL import Image, ImageOps

from label_qc.services.analyzer import MAX_IMAGE_BYTES

MIN_SHORT_EDGE = 960
MIN_LONG_EDGE = 1280
MIN_TRAY_COUNT = 3
MIN_SHARPNESS = 500.0
EDGE_MARGIN_RATIO = 0.01
SHARPNESS_MAX_EDGE = 1280


@dataclass(frozen=True)
class CaptureQualityResult:
    accepted: bool
    reason_code: str | None
    message: str
    image_width: int
    image_height: int
    tray_count: int
    sharpness: float
    left_margin_ratio: float | None
    right_margin_ratio: float | None

    def as_dict(self) -> dict[str, Any]:
        return {
            "accepted": self.accepted,
            "reasonCode": self.reason_code,
            "message": self.message,
            "imageWidth": self.image_width,
            "imageHeight": self.image_height,
            "trayCount": self.tray_count,
            "sharpness": round(self.sharpness, 1),
            "leftMarginRatio": (
                None if self.left_margin_ratio is None else round(self.left_margin_ratio, 4)
            ),
            "rightMarginRatio": (
                None if self.right_margin_ratio is None else round(self.right_margin_ratio, 4)
            ),
        }


def _decode(image_bytes: bytes) -> np.ndarray:
    if not image_bytes:
        raise ValueError("empty image")
    if len(image_bytes) > MAX_IMAGE_BYTES:
        raise ValueError("image exceeds 10 MB limit")
    try:
        source = Image.open(io.BytesIO(image_bytes))
        source = ImageOps.exif_transpose(source)
        source.load()
        return np.asarray(source.convert("RGB"))
    except Exception as exc:
        raise ValueError("invalid or unsupported image") from exc


def _sharpness(image: np.ndarray) -> float:
    height, width = image.shape[:2]
    scale = min(1.0, SHARPNESS_MAX_EDGE / max(width, height, 1))
    sample = image
    if scale < 1.0:
        sample = cv2.resize(
            image,
            (max(1, round(width * scale)), max(1, round(height * scale))),
            interpolation=cv2.INTER_AREA,
        )
    gray = cv2.cvtColor(sample, cv2.COLOR_RGB2GRAY)
    return float(cv2.Laplacian(gray, cv2.CV_64F).var())


def _box_values(detection: Any) -> Sequence[float]:
    as_xyxy = getattr(detection, "as_xyxy", None)
    values = as_xyxy() if callable(as_xyxy) else detection
    if not isinstance(values, Sequence) or len(values) != 4:
        raise ValueError("tray detector returned an invalid box")
    return [float(value) for value in values]


def evaluate_capture_quality(
    image: np.ndarray,
    detections: Iterable[Any],
) -> CaptureQualityResult:
    height, width = image.shape[:2]
    short_edge, long_edge = min(width, height), max(width, height)
    boxes = [_box_values(detection) for detection in detections]
    sharpness = _sharpness(image)

    if short_edge < MIN_SHORT_EDGE or long_edge < MIN_LONG_EDGE:
        return CaptureQualityResult(
            False,
            "RESOLUTION_TOO_LOW",
            "照片分辨率太低，请靠近托盘重新拍摄",
            width,
            height,
            len(boxes),
            sharpness,
            None,
            None,
        )

    if sharpness < MIN_SHARPNESS:
        return CaptureQualityResult(
            False,
            "PHOTO_BLURRY",
            "照片太糊，请拿稳手机重新拍摄",
            width,
            height,
            len(boxes),
            sharpness,
            None,
            None,
        )

    if len(boxes) < MIN_TRAY_COUNT:
        return CaptureQualityResult(
            False,
            "TRAYS_NOT_FOUND",
            "没有看清完整托盘，请对准整桌后重新拍摄",
            width,
            height,
            len(boxes),
            sharpness,
            None,
            None,
        )

    left_margin = max(0.0, min(box[0] for box in boxes)) / max(width, 1)
    right_margin = max(0.0, width - max(box[2] for box in boxes)) / max(width, 1)
    if left_margin < EDGE_MARGIN_RATIO or right_margin < EDGE_MARGIN_RATIO:
        return CaptureQualityResult(
            False,
            "TRAY_CROPPED_AT_SIDE",
            "左右下角托盘没有拍全，请后退一点或切换广角重拍",
            width,
            height,
            len(boxes),
            sharpness,
            left_margin,
            right_margin,
        )

    return CaptureQualityResult(
        True,
        None,
        "照片清晰，托盘已完整入框",
        width,
        height,
        len(boxes),
        sharpness,
        left_margin,
        right_margin,
    )


async def analyze_capture_quality(
    image_bytes: bytes,
    models: Any,
    tray_confidence: float,
) -> CaptureQualityResult:
    image = _decode(image_bytes)
    available = bool(getattr(models, "available", False))
    if not available:
        raise RuntimeError("tray detector unavailable")
    detections = await asyncio.to_thread(models.detect_trays, image, tray_confidence)
    return evaluate_capture_quality(image, detections)
