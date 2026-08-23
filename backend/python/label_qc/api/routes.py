from __future__ import annotations

import logging
import os
from typing import Any, Dict

from fastapi import APIRouter, File, HTTPException, UploadFile

from label_qc.services.analyzer import LabelQcAnalyzer, MAX_IMAGE_BYTES

logger = logging.getLogger(__name__)
router = APIRouter()


def _screening_enabled() -> bool:
    return os.getenv("LABEL_QC_SCREENING", "1").strip().lower() in {"1", "true", "yes", "on"}


def _build_analyzer():
    """YOLO screening + VL review when enabled and importable.

    Legacy configuration preserves the historical VL-only fallback. The
    hash-bound v8 production route is deliberately fail-closed: missing code,
    dependencies, manifest, or model bytes must surface as analysis failure and
    customer review, never as a silent pipeline downgrade.
    """
    if not _screening_enabled():
        logger.info("Label QC: screening disabled by LABEL_QC_SCREENING, using VL-only")
        return LabelQcAnalyzer()
    try:
        from label_qc.services.hybrid_analyzer import HybridLabelQcAnalyzer

        return HybridLabelQcAnalyzer()
    except Exception as exc:  # onnxruntime / opencv missing on this host
        if os.getenv("LABEL_QC_PIPELINE", "legacy").strip().lower() == "v8":
            raise
        logger.warning("Label QC: hybrid analyzer unavailable (%s: %s), using VL-only",
                       type(exc).__name__, exc)
        return LabelQcAnalyzer()


analyzer = _build_analyzer()


@router.post("/analyze")
async def analyze_label_photo(
    image: UploadFile = File(...),
) -> Dict[str, Any]:
    image_bytes = await image.read(MAX_IMAGE_BYTES + 1)
    if len(image_bytes) > MAX_IMAGE_BYTES:
        raise HTTPException(status_code=413, detail="Image exceeds 10 MB limit")
    try:
        result = await analyzer.analyze(image_bytes)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return {"success": True, "data": result}


@router.get("/screening-status")
async def screening_status() -> Dict[str, Any]:
    """Whether YOLO screening is actually live -- for post-deploy verification."""
    active = type(analyzer).__name__
    data: Dict[str, Any] = {
        "analyzer": active,
        "screeningEnabledByConfig": _screening_enabled(),
        "screeningActive": active == "HybridLabelQcAnalyzer",
    }
    models = getattr(analyzer, "_models", None)
    if models is not None:
        data["modelDir"] = str(models.model_dir)
        data["modelsAvailable"] = models.available
        data["loadError"] = models.load_error
        data["pipeline"] = getattr(models, "pipeline_name", "legacy")
        data["failClosed"] = bool(getattr(models, "fail_closed", False))
        data["customerAutoPassSupported"] = bool(
            getattr(models, "customer_auto_pass_supported", False)
        )
        if models.available:
            data["modelId"] = getattr(models, "model_id", None)
    return {"success": True, "data": data}
