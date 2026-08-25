"""Production runtime for the frozen Liushanmen v8 label-QC cascade.

The bundle keeps the current production tray and label detectors authoritative.
An additive tray detector is used only on the lower band of portrait workbench
photos, and the newer label cascade is used only for original padded tray crops
whose shorter side is 120-149 pixels.  Every unsafe post-processing rule fails
closed to ``UNJUDGEABLE`` so the task enters the customer risk queue.

Model files are external deployment artifacts.  ``manifest.json`` binds every
file by SHA-256 before any ONNX session is created.
"""
from __future__ import annotations

import hashlib
import json
import logging
import math
import os
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Optional, Sequence

import numpy as np
from PIL import Image, ImageOps

from label_qc.services.yolo_detector import Detection, LabelQcYoloModels, _OnnxDetector

logger = logging.getLogger(__name__)

PIPELINE_ID = "liushanmen-tray-label-shallow-v8-fallback-20260822"
MANIFEST_FILE = "manifest.json"
REQUIRED_MODELS = {
    "production_tray": "tray.onnx",
    "production_label": "label.onnx",
    "tray_supplement": "tray-supplement.onnx",
    "generic_label": "generic-label.onnx",
    "proposal_classifier": "proposal-classifier.onnx",
    "label_fallback": "label-fallback.onnx",
}

PRODUCTION_TRAY_CONFIDENCE = 0.60
CANDIDATE_TRAY_CONFIDENCE = 0.30
LABEL_CONFIDENCE = 0.20
DEFAULT_LABEL_FALLBACK_INPUT_SIZE = 640
SUPPORTED_LABEL_FALLBACK_INPUT_SIZES = frozenset({640, 960, 1280})
MAX_LABEL_FALLBACK_ONNX_THREADS = 8
PRODUCTION_MIN_CROP_PX = 150
CANDIDATE_MIN_CROP_PX = 120
PAD_RATIO = 0.14
MAX_TRAYS = 60
PROPOSAL_CLASSIFIER_THRESHOLD = 0.50
SUPPLEMENT_HEIGHT_FRACTION = 0.60
SUPPLEMENT_SKIP_TRAY_COUNT = 8
NESTED_IOS_THRESHOLD = 0.90

MISSING_VERDICTS = frozenset(
    {"MISSING_WHITE_LABEL", "MISSING_COLOR_LABEL", "BOTH_MISSING"}
)


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _label_fallback_input_size(manifest: dict[str, Any]) -> int:
    value = manifest.get("labelFallbackInputSize", DEFAULT_LABEL_FALLBACK_INPUT_SIZE)
    if type(value) is not int or value not in SUPPORTED_LABEL_FALLBACK_INPUT_SIZES:
        raise RuntimeError(f"invalid v8 label fallback input size: {value!r}")
    return value


def _validate_label_fallback_input_shape(detector: _OnnxDetector, size: int) -> None:
    shape = detector._session.get_inputs()[0].shape
    if len(shape) != 4 or tuple(shape[-2:]) != (size, size):
        raise RuntimeError(
            f"v8 label fallback input shape drift: expected {size}x{size}, got {shape!r}"
        )


def _label_fallback_onnx_threads(manifest: dict[str, Any]) -> int | None:
    value = manifest.get("labelFallbackOnnxThreads")
    if value is None:
        return None
    if type(value) is not int or not 1 <= value <= MAX_LABEL_FALLBACK_ONNX_THREADS:
        raise RuntimeError(f"invalid v8 label fallback ONNX threads: {value!r}")
    return value


def _box_iou(first: Sequence[float], second: Sequence[float]) -> float:
    x0 = max(float(first[0]), float(second[0]))
    y0 = max(float(first[1]), float(second[1]))
    x1 = min(float(first[2]), float(second[2]))
    y1 = min(float(first[3]), float(second[3]))
    intersection = max(0.0, x1 - x0) * max(0.0, y1 - y0)
    first_area = max(0.0, float(first[2]) - float(first[0])) * max(
        0.0, float(first[3]) - float(first[1])
    )
    second_area = max(0.0, float(second[2]) - float(second[0])) * max(
        0.0, float(second[3]) - float(second[1])
    )
    union = first_area + second_area - intersection
    return intersection / union if union > 0 else 0.0


def _intersection_over_smaller(
    first: Sequence[float], second: Sequence[float]
) -> float:
    x0 = max(float(first[0]), float(second[0]))
    y0 = max(float(first[1]), float(second[1]))
    x1 = min(float(first[2]), float(second[2]))
    y1 = min(float(first[3]), float(second[3]))
    intersection = max(0.0, x1 - x0) * max(0.0, y1 - y0)
    first_area = max(0.0, float(first[2]) - float(first[0])) * max(
        0.0, float(first[3]) - float(first[1])
    )
    second_area = max(0.0, float(second[2]) - float(second[0])) * max(
        0.0, float(second[3]) - float(second[1])
    )
    smaller = min(first_area, second_area)
    return intersection / smaller if smaller > 0 else 0.0


def _deduplicate(
    detections: Iterable[Detection], iou_threshold: float = 0.50,
    ios_threshold: float = 0.80,
) -> list[Detection]:
    kept: list[Detection] = []
    for detection in sorted(detections, key=lambda item: -float(item.confidence)):
        if any(
            int(detection.class_id) == int(chosen.class_id)
            and (
                _box_iou(detection.as_xyxy(), chosen.as_xyxy()) >= iou_threshold
                or _intersection_over_smaller(
                    detection.as_xyxy(), chosen.as_xyxy()
                ) >= ios_threshold
            )
            for chosen in kept
        ):
            continue
        kept.append(detection)
    return kept


def _detection_row(detection: Detection, source: str) -> dict[str, Any]:
    return {
        "source": source,
        "box": [round(float(value), 3) for value in detection.as_xyxy()],
        "confidence": round(float(detection.confidence), 6),
        "class_id": int(detection.class_id),
    }


def _box_key(box: Sequence[float]) -> tuple[float, float, float, float]:
    return tuple(round(float(value), 3) for value in box)  # type: ignore[return-value]


def _original_crop_minimum(
    image_shape: Sequence[int], box: Sequence[float], pad_ratio: float = PAD_RATIO
) -> int:
    height, width = int(image_shape[0]), int(image_shape[1])
    x0, y0, x1, y1 = (float(value) for value in box)
    box_width, box_height = x1 - x0, y1 - y0
    crop_x0 = max(0.0, x0 - box_width * pad_ratio)
    crop_y0 = max(0.0, y0 - box_height * pad_ratio)
    crop_x1 = min(float(width), x1 + box_width * pad_ratio)
    crop_y1 = min(float(height), y1 + box_height * pad_ratio)
    return min(int(crop_x1) - int(crop_x0), int(crop_y1) - int(crop_y0))


def _yolox_postprocess(
    raw: np.ndarray,
    confidence: float,
    nms_iou: float,
    resize_ratio: float,
    image_width: int,
    image_height: int,
) -> list[dict[str, Any]]:
    if raw.ndim != 3 or raw.shape[0] != 1 or raw.shape[2] != 6:
        raise ValueError(f"unexpected YOLOX output shape: {raw.shape}")
    rows = raw[0]
    scores = rows[:, 4] * rows[:, 5]
    selected = scores >= confidence
    if not np.any(selected):
        return []
    rows, scores = rows[selected], scores[selected]
    boxes = np.empty((len(rows), 4), dtype=np.float32)
    boxes[:, 0] = rows[:, 0] - rows[:, 2] / 2.0
    boxes[:, 1] = rows[:, 1] - rows[:, 3] / 2.0
    boxes[:, 2] = rows[:, 0] + rows[:, 2] / 2.0
    boxes[:, 3] = rows[:, 1] + rows[:, 3] / 2.0
    boxes /= resize_ratio
    boxes[:, [0, 2]] = boxes[:, [0, 2]].clip(0, image_width)
    boxes[:, [1, 3]] = boxes[:, [1, 3]].clip(0, image_height)
    order = np.argsort(-scores, kind="stable")
    kept: list[int] = []
    while len(order):
        current = int(order[0])
        kept.append(current)
        remaining = order[1:]
        if not len(remaining):
            break
        overlaps = np.asarray(
            [_box_iou(boxes[current], boxes[index]) for index in remaining]
        )
        order = remaining[overlaps <= nms_iou]
    return [
        {
            "box": [float(value) for value in boxes[index]],
            "confidence": float(scores[index]),
        }
        for index in kept
        if boxes[index, 2] - boxes[index, 0] > 1.0
        and boxes[index, 3] - boxes[index, 1] > 1.0
    ]


class _YoloxOnnxDetector:
    def __init__(self, model_path: Path, imgsz: int = 960) -> None:
        try:
            import cv2
            import onnxruntime as ort
        except ImportError as exc:  # v8 is fail-closed; no approximate fallback
            raise RuntimeError("v8 requires opencv-python and onnxruntime") from exc
        self._cv2 = cv2
        self._imgsz = imgsz
        options = ort.SessionOptions()
        options.intra_op_num_threads = int(os.getenv("LABEL_QC_ONNX_THREADS", "2"))
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self._session = ort.InferenceSession(
            str(model_path), sess_options=options, providers=["CPUExecutionProvider"]
        )
        self._input_name = self._session.get_inputs()[0].name
        self._lock = threading.Lock()

    def _raw(self, image: np.ndarray) -> tuple[np.ndarray, float, int, int]:
        height, width = image.shape[:2]
        ratio = min(self._imgsz / height, self._imgsz / width)
        resized_width, resized_height = int(width * ratio), int(height * ratio)
        bgr = np.ascontiguousarray(image[:, :, ::-1])
        resized = self._cv2.resize(
            bgr, (resized_width, resized_height), interpolation=self._cv2.INTER_LINEAR
        )
        canvas = np.full((self._imgsz, self._imgsz, 3), 114, dtype=np.uint8)
        canvas[:resized_height, :resized_width] = resized
        blob = np.ascontiguousarray(
            canvas.transpose(2, 0, 1)[None], dtype=np.float32
        )
        with self._lock:
            raw = self._session.run(None, {self._input_name: blob})[0]
        return raw, ratio, width, height

    def detect(self, image: np.ndarray, conf: float) -> list[Detection]:
        raw, ratio, width, height = self._raw(image)
        return [
            Detection(
                x0=row["box"][0], y0=row["box"][1],
                x1=row["box"][2], y1=row["box"][3],
                confidence=row["confidence"], class_id=0,
            )
            for row in _yolox_postprocess(raw, conf, 0.65, ratio, width, height)
        ]


class _GenericLabelTypeDetector(_YoloxOnnxDetector):
    def __init__(
        self, generic_model: Path, classifier_model: Path,
        threshold: float = PROPOSAL_CLASSIFIER_THRESHOLD,
    ) -> None:
        super().__init__(generic_model, imgsz=640)
        import onnxruntime as ort

        options = ort.SessionOptions()
        options.intra_op_num_threads = int(os.getenv("LABEL_QC_ONNX_THREADS", "2"))
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self._classifier = ort.InferenceSession(
            str(classifier_model),
            sess_options=options,
            providers=["CPUExecutionProvider"],
        )
        self._classifier_input = self._classifier.get_inputs()[0].name
        self._classifier_lock = threading.Lock()
        self._threshold = float(threshold)
        self.reset_stats()

    def reset_stats(self) -> None:
        self.calls = 0
        self.proposals = 0
        self.assigned_white = 0
        self.assigned_color = 0

    @staticmethod
    def _classifier_blob(image: np.ndarray, box: Sequence[float]) -> np.ndarray:
        height, width = image.shape[:2]
        x0, y0, x1, y1 = (float(value) for value in box)
        center_x, center_y = (x0 + x1) / 2.0, (y0 + y1) / 2.0
        expanded_width = (x1 - x0) * 1.32
        expanded_height = (y1 - y0) * 1.32
        left = max(0, int(round(center_x - expanded_width / 2.0)))
        top = max(0, int(round(center_y - expanded_height / 2.0)))
        right = min(width, int(round(center_x + expanded_width / 2.0)))
        bottom = min(height, int(round(center_y + expanded_height / 2.0)))
        if right - left < 2 or bottom - top < 2:
            raise RuntimeError("degenerate proposal classifier crop")
        cropped = Image.fromarray(image).crop((left, top, right, bottom))
        padded = ImageOps.pad(
            cropped,
            (160, 160),
            method=Image.Resampling.BILINEAR,
            color=(114, 114, 114),
            centering=(0.5, 0.5),
        )
        values = np.asarray(padded, dtype=np.float32) / 255.0
        values = (values - 0.5) / 0.5
        return np.ascontiguousarray(values.transpose(2, 0, 1)[None])

    def _type_probability(self, image: np.ndarray, box: Sequence[float]) -> float:
        blob = self._classifier_blob(image, box)
        with self._classifier_lock:
            logit = float(
                self._classifier.run(
                    None, {self._classifier_input: blob}
                )[0].reshape(-1)[0]
            )
        return float(1.0 / (1.0 + math.exp(-logit)))

    def detect(self, image: np.ndarray, conf: float) -> list[Detection]:
        self.calls += 1
        raw, ratio, width, height = self._raw(image)
        rows = _yolox_postprocess(raw, conf, 0.65, ratio, width, height)
        detections: list[Detection] = []
        for row in rows:
            probability = self._type_probability(image, row["box"])
            class_id = int(probability < self._threshold)
            self.proposals += 1
            self.assigned_white += int(class_id == 0)
            self.assigned_color += int(class_id == 1)
            detections.append(
                Detection(
                    x0=row["box"][0], y0=row["box"][1],
                    x1=row["box"][2], y1=row["box"][3],
                    confidence=row["confidence"], class_id=class_id,
                )
            )
        return detections

    def stats(self) -> dict[str, Any]:
        return {
            "calls": self.calls,
            "genericProposals": self.proposals,
            "assignedWhite": self.assigned_white,
            "assignedColor": self.assigned_color,
            "classifierThreshold": self._threshold,
        }


class _MissingClassFallbackDetector:
    def __init__(
        self, primary: Any, fallback: Any, fallback_confidence_floor: float = 0.0,
    ) -> None:
        self.primary = primary
        self.fallback = fallback
        self.fallback_confidence_floor = float(fallback_confidence_floor)
        self.reset_stats()

    def reset_stats(self) -> None:
        self.calls = 0
        self.fallback_calls = 0
        self.fallback_reasons = {0: 0, 1: 0}
        if hasattr(self.primary, "reset_stats"):
            self.primary.reset_stats()

    def detect(self, image: np.ndarray, conf: float) -> list[Detection]:
        self.calls += 1
        primary = list(self.primary.detect(image, conf))
        present = {int(row.class_id) for row in primary}
        missing = [class_id for class_id in (0, 1) if class_id not in present]
        if not missing:
            return primary
        self.fallback_calls += 1
        for class_id in missing:
            self.fallback_reasons[class_id] += 1
        return list(self.fallback.detect(
            image, max(float(conf), self.fallback_confidence_floor),
        ))

    def stats(self) -> dict[str, Any]:
        return {
            "calls": self.calls,
            "fallbackCalls": self.fallback_calls,
            "fallbackReasons": {
                "whiteLabel": self.fallback_reasons[0],
                "colorLabel": self.fallback_reasons[1],
            },
            "primary": self.primary.stats(),
        }


class _V8CascadeModels:
    """Exact v8 routing with production-authoritative tray merging."""

    def __init__(
        self,
        production: LabelQcYoloModels,
        tray_supplement: _YoloxOnnxDetector,
        production_label: _OnnxDetector,
        candidate_label: _MissingClassFallbackDetector,
    ) -> None:
        self.production = production
        self.tray_supplement = tray_supplement
        self.production_label = production_label
        self.candidate_label = candidate_label
        self.detection_type = Detection
        self.iou_threshold = 0.50
        self.ios_threshold = 0.80
        self.last_raw_detections: list[dict[str, Any]] = []
        self.last_stats: dict[str, Any] = {}
        self._label_routes: list[bool] = []
        self.reset_label_stats()

    def reset_label_stats(self) -> None:
        self.label_calls = 0
        self.production_label_calls = 0
        self.candidate_resolution_gap_calls = 0
        self._label_routes = []
        self.candidate_label.reset_stats()

    @staticmethod
    def _belongs_to_outer_band(
        box: Sequence[float], source: str, width: int, height: int
    ) -> bool:
        center_x = (float(box[0]) + float(box[2])) / 2.0 / max(width, 1)
        center_y = (float(box[1]) + float(box[3])) / 2.0 / max(height, 1)
        horizontal = center_x <= 0.40 if source == "lower_left" else center_x >= 0.60
        return horizontal and center_y >= 0.55

    @staticmethod
    def _duplicate(detection: Detection, references: Sequence[Detection]) -> bool:
        return any(
            int(detection.class_id) == int(reference.class_id)
            and (
                _box_iou(detection.as_xyxy(), reference.as_xyxy()) >= 0.50
                or _intersection_over_smaller(
                    detection.as_xyxy(), reference.as_xyxy()
                ) >= 0.80
            )
            for reference in references
        )

    def _set_label_routes(
        self, image: np.ndarray, detections: Sequence[Detection]
    ) -> None:
        ordered = sorted(detections, key=lambda row: -float(row.confidence))[:MAX_TRAYS]
        self._label_routes = [
            minimum < PRODUCTION_MIN_CROP_PX
            for minimum in (
                _original_crop_minimum(image.shape, row.as_xyxy()) for row in ordered
            )
            if minimum >= CANDIDATE_MIN_CROP_PX
        ]

    def detect_trays(self, image: np.ndarray, conf: float) -> list[Detection]:
        height, width = image.shape[:2]
        production = list(self.production.detect_trays(image, conf))
        if width >= height or len(production) >= SUPPLEMENT_SKIP_TRAY_COUNT:
            self.last_raw_detections = [
                _detection_row(row, "production_full") for row in production
            ]
            self.last_stats = {
                "mode": (
                    "production_non_portrait_supplement_skipped"
                    if width >= height
                    else "production_full_layout_supplement_skipped"
                ),
                "productionFullFrameDetections": len(production),
                "supplementSkippedOrientation": width >= height,
                "supplementSkippedFullLayout": len(production) >= SUPPLEMENT_SKIP_TRAY_COUNT,
                "productionPreservingMergeApplied": False,
            }
            self._set_label_routes(image, production)
            return production

        y0 = height - max(1, round(height * SUPPLEMENT_HEIGHT_FRACTION))
        raw_proposals = list(
            self.tray_supplement.detect(
                image[y0:height, :], CANDIDATE_TRAY_CONFIDENCE
            )
        )
        accepted_rows: list[dict[str, Any]] = []
        accepted: list[Detection] = []
        internal_rejected = outside_rejected = overlap_rejected = 0
        for proposal in raw_proposals:
            if y0 > 0 and float(proposal.y0) <= 2:
                internal_rejected += 1
                continue
            mapped = Detection(
                x0=float(proposal.x0), y0=float(proposal.y0) + y0,
                x1=float(proposal.x1), y1=float(proposal.y1) + y0,
                confidence=float(proposal.confidence), class_id=int(proposal.class_id),
            )
            box = mapped.as_xyxy()
            center_x = (float(box[0]) + float(box[2])) / 2.0 / max(width, 1)
            source = "lower_left" if center_x <= 0.50 else "lower_right"
            if not self._belongs_to_outer_band(box, source, width, height):
                outside_rejected += 1
                continue
            if any(
                int(mapped.class_id) == int(reference.class_id)
                and _intersection_over_smaller(
                    mapped.as_xyxy(), reference.as_xyxy()
                ) >= 0.60
                for reference in production
            ):
                overlap_rejected += 1
                continue
            accepted.append(mapped)
            accepted_rows.append(_detection_row(mapped, "candidate_lower_band"))

        supplement_kept: list[Detection] = []
        supplement_kept_rows: list[dict[str, Any]] = []
        supplement_rejected = 0
        by_box = {_box_key(row["box"]): row for row in accepted_rows}
        for detection in sorted(accepted, key=lambda row: -float(row.confidence)):
            if self._duplicate(detection, [*production, *supplement_kept]):
                supplement_rejected += 1
                continue
            supplement_kept.append(detection)
            supplement_kept_rows.append(by_box[_box_key(detection.as_xyxy())])
        preserved = [*production, *supplement_kept]
        self.last_raw_detections = [
            *[_detection_row(row, "production_full") for row in production],
            *supplement_kept_rows,
        ]
        self.last_stats = {
            "mode": "production_full_plus_candidate_lower_band",
            "productionFullFrameDetections": len(production),
            "candidateLowerBandDetections": len(raw_proposals),
            "candidateInternalBorderRejected": internal_rejected,
            "candidateOutsideEdgeBandRejected": outside_rejected,
            "candidateProductionOverlapRejected": overlap_rejected,
            "productionPreservingMergeApplied": True,
            "productionBoxesPreserved": len(production),
            "supplementBoxesKept": len(supplement_kept),
            "supplementBoxesRejected": supplement_rejected,
        }
        self._set_label_routes(image, preserved)
        return preserved

    def detect_labels(self, crop: np.ndarray, conf: float) -> list[Detection]:
        if not self._label_routes:
            raise RuntimeError("v8 label route queue is empty or screening order drifted")
        self.label_calls += 1
        use_candidate = self._label_routes.pop(0)
        if use_candidate:
            self.candidate_resolution_gap_calls += 1
            return list(self.candidate_label.detect(crop, conf))
        self.production_label_calls += 1
        return list(self.production_label.detect(crop, conf))

    def label_stats(self) -> dict[str, Any]:
        return {
            "calls": self.label_calls,
            "policy": "candidateOnlyInOriginal120To149PxCropGap",
            "productionCalls": self.production_label_calls,
            "candidateResolutionGapCalls": self.candidate_resolution_gap_calls,
            "remainingRouteQueue": len(self._label_routes),
            "candidate": self.candidate_label.stats(),
        }


def _source_lookup(models: _V8CascadeModels) -> dict[tuple[float, ...], str]:
    return {
        _box_key(row["box"]): str(row.get("source") or "unknown")
        for row in models.last_raw_detections
    }


def _geometry_reason(tray: Any, source: str) -> Optional[str]:
    if str(tray.verdict) not in MISSING_VERDICTS:
        return None
    x0, y0, x1, y1 = (float(value) for value in tray.box)
    width, height = max(0.0, x1 - x0), max(0.0, y1 - y0)
    aspect_ratio = width / height if height > 0 else 0.0
    classes = {int(label.class_id) for label in tray.labels}
    if int(tray.own_label_count) == 0 and y0 <= 1.0:
        return "zero_label_top_boundary_tray"
    if source == "candidate_lower_band" and tray.own_label_count >= 2 and len(classes) == 1:
        return "supplement_duplicate_single_class_labels"
    if aspect_ratio < 0.90:
        return "portrait_geometry_incomplete_tray"
    return None


def _padding_crosses_boundary(
    box: Sequence[float], image_width: int, image_height: int
) -> bool:
    x0, y0, x1, y1 = (float(value) for value in box)
    pad_x = max(0.0, x1 - x0) * PAD_RATIO
    pad_y = max(0.0, y1 - y0) * PAD_RATIO
    return (
        x0 - pad_x < 0 or y0 - pad_y < 0
        or x1 + pad_x > image_width or y1 + pad_y > image_height
    )


def _apply_fail_closed_policies(
    result: Any, image: np.ndarray, models: _V8CascadeModels
) -> list[dict[str, Any]]:
    sources = _source_lookup(models)
    events: list[dict[str, Any]] = []
    trays = list(result.trays)
    for tray in trays:
        if str(tray.verdict) not in MISSING_VERDICTS:
            continue
        source = sources.get(_box_key(tray.box), "unknown")
        crop_minimum = _original_crop_minimum(image.shape, tray.box, result.params.pad_ratio)
        nested_ios = 0.0
        if CANDIDATE_MIN_CROP_PX <= crop_minimum < PRODUCTION_MIN_CROP_PX:
            nested_ios = max(
                (
                    _intersection_over_smaller(tray.box, other.box)
                    for other in trays
                    if other is not tray
                    and float(other.confidence) > float(tray.confidence)
                ),
                default=0.0,
            )
        reason: Optional[str] = None
        if int(tray.dropped_neighbour_labels) > 0:
            reason = "ownership_dropped_neighbour_label"
        elif source == "candidate_lower_band" and int(tray.own_label_count) == 0:
            reason = "supplement_tray_has_no_owned_label"
        elif nested_ios >= NESTED_IOS_THRESHOLD:
            reason = "resolution_gap_tray_nested_in_higher_confidence_tray"
        if reason is None:
            reason = _geometry_reason(tray, source)
        if reason is None:
            classes = {int(label.class_id) for label in tray.labels}
            if (
                int(tray.own_label_count) >= 2
                and len(classes) == 1
                and _padding_crosses_boundary(
                    tray.box, int(image.shape[1]), int(image.shape[0])
                )
            ):
                reason = "boundary_clamped_duplicate_single_class_labels"
        if reason is None and source == "production_full":
            x0, y0, x1, y1 = (float(value) for value in tray.box)
            width, height = max(0.0, x1 - x0), max(0.0, y1 - y0)
            aspect_ratio = width / height if height > 0 else 0.0
            if height < 120.0 and aspect_ratio > 2.0:
                reason = "sub120_extreme_wide_production_tray"
        if reason is None:
            continue
        previous = str(tray.verdict)
        tray.verdict = "UNJUDGEABLE"
        events.append(
            {
                "trayIndex": int(tray.index),
                "source": source,
                "reason": reason,
                "previousVerdict": previous,
                "finalVerdict": "UNJUDGEABLE",
                "cropMinimumPx": int(crop_minimum),
                "nestedIos": round(float(nested_ios), 6),
                "box": [round(float(value), 3) for value in tray.box],
            }
        )
    return events


@dataclass(frozen=True)
class _BundleBinding:
    path: Path
    sha256: str


class LabelQcV8Models:
    customer_auto_pass_supported = True

    """Lazy, hash-bound facade matching the existing screening model API."""

    pipeline_name = "v8"
    fail_closed = True

    def __init__(self, model_dir: Optional[Path] = None) -> None:
        self._model_dir = Path(
            model_dir or os.getenv("LABEL_QC_MODEL_DIR") or ""
        ).resolve()
        self._models: Optional[_V8CascadeModels] = None
        self._loaded = False
        self._load_error: Optional[str] = None
        self._manifest: dict[str, Any] = {}
        self._lock = threading.Lock()
        self._runtime_audit: dict[str, Any] = {}

    @property
    def model_dir(self) -> Path:
        return self._model_dir

    def recommended_params(self) -> Any:
        from label_qc.services.screening import ScreeningParams

        return ScreeningParams(
            tray_conf=PRODUCTION_TRAY_CONFIDENCE,
            label_conf=LABEL_CONFIDENCE,
            min_crop_px=CANDIDATE_MIN_CROP_PX,
        )

    def _bindings(self) -> dict[str, _BundleBinding]:
        manifest_path = self._model_dir / MANIFEST_FILE
        payload = json.loads(manifest_path.read_text(encoding="utf-8"))
        if payload.get("pipelineId") != PIPELINE_ID:
            raise RuntimeError("unexpected v8 pipeline id")
        models = payload.get("models") or {}
        bindings: dict[str, _BundleBinding] = {}
        for name, filename in REQUIRED_MODELS.items():
            item = models.get(name) or {}
            if item.get("file") != filename:
                raise RuntimeError(f"v8 manifest filename drift: {name}")
            path = (self._model_dir / filename).resolve()
            expected = str(item.get("sha256") or "").lower()
            if not path.is_file() or _sha256_file(path) != expected:
                raise RuntimeError(f"v8 model binding drift: {name}")
            bindings[name] = _BundleBinding(path, expected)
        self._manifest = payload
        return bindings

    def _load(self) -> None:
        if self._loaded:
            return
        with self._lock:
            if self._loaded:
                return
            try:
                bindings = self._bindings()
                production = LabelQcYoloModels(self._model_dir)
                if not production.available:
                    raise RuntimeError(production.load_error or "production models unavailable")
                primary_label = _GenericLabelTypeDetector(
                    bindings["generic_label"].path,
                    bindings["proposal_classifier"].path,
                )
                fallback_confidence = float(
                    self._manifest.get("labelFallbackConfidence", LABEL_CONFIDENCE)
                )
                if not LABEL_CONFIDENCE <= fallback_confidence <= 1.0:
                    raise RuntimeError("invalid v8 label fallback confidence")
                fallback_input_size = _label_fallback_input_size(self._manifest)
                fallback_onnx_threads = _label_fallback_onnx_threads(self._manifest)
                fallback_detector = _OnnxDetector(
                    bindings["label_fallback"].path,
                    imgsz=fallback_input_size,
                    intra_op_num_threads=fallback_onnx_threads,
                )
                _validate_label_fallback_input_shape(
                    fallback_detector, fallback_input_size
                )
                candidate_label = _MissingClassFallbackDetector(
                    primary_label,
                    fallback_detector,
                    fallback_confidence,
                )
                self._models = _V8CascadeModels(
                    production=production,
                    tray_supplement=_YoloxOnnxDetector(
                        bindings["tray_supplement"].path, imgsz=960
                    ),
                    production_label=_OnnxDetector(
                        bindings["production_label"].path, imgsz=640
                    ),
                    candidate_label=candidate_label,
                )
                logger.info("Label QC v8 bundle loaded from %s", self._model_dir)
            except Exception as exc:  # environment and artifact dependent
                self._models = None
                self._load_error = f"{type(exc).__name__}: {exc}"
                logger.error("Label QC v8 load failed closed: %s", self._load_error)
            self._loaded = True

    @property
    def available(self) -> bool:
        self._load()
        return self._models is not None

    @property
    def load_error(self) -> Optional[str]:
        self._load()
        return self._load_error

    @property
    def runtime_audit(self) -> dict[str, Any]:
        return dict(self._runtime_audit)

    @property
    def model_id(self) -> str:
        self._load()
        return str(self._manifest.get("modelId") or PIPELINE_ID)

    def _require_models(self) -> _V8CascadeModels:
        self._load()
        if self._models is None:
            raise RuntimeError(self._load_error or "v8 models unavailable")
        return self._models

    def detect_trays(self, image: np.ndarray, conf: float) -> list[Detection]:
        return self._require_models().detect_trays(image, conf)

    def detect_labels(self, crop: np.ndarray, conf: float) -> list[Detection]:
        return self._require_models().detect_labels(crop, conf)

    def screen_image(self, image: np.ndarray, params: Any) -> Any:
        from label_qc.services import screening

        models = self._require_models()
        models.reset_label_stats()
        result = screening.screen_image(image, models, params)
        events = _apply_fail_closed_policies(result, image, models)
        audit = {
            "pipelineId": PIPELINE_ID,
            "modelId": self.model_id,
            "labelFallbackInputSize": _label_fallback_input_size(self._manifest),
            "labelFallbackOnnxThreads": _label_fallback_onnx_threads(self._manifest),
            "tray": dict(models.last_stats),
            "label": models.label_stats(),
            "failClosedEvents": events,
        }
        result.runtime_audit = audit
        self._runtime_audit = audit
        return result


def build_models_from_env() -> Any:
    pipeline = os.getenv("LABEL_QC_PIPELINE", "legacy").strip().lower()
    if pipeline == "v8":
        return LabelQcV8Models()
    if pipeline not in {"", "legacy", "v1"}:
        raise RuntimeError(f"unsupported LABEL_QC_PIPELINE: {pipeline}")
    return LabelQcYoloModels()
