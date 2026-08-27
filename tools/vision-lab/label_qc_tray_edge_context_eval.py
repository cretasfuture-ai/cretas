#!/usr/bin/env python3
"""Evaluate a bounded lower-edge tray zoom path with an offline tray candidate.

Unlike full-image SAHI, this adds only two fixed lower-corner context windows,
rejects detections touching internal crop borders, and keeps detections whose
centres remain in the corresponding outer lower band.  It is an offline A/B;
the protected target is never used to choose a window at runtime.
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
import tempfile
from pathlib import Path
from typing import Any, Sequence

import label_qc_oracle_diagnostics as diagnostics


TARGET_PHOTO_ID = "locked-test-real_9827ccc7-7b30-48eb-bff2-c2493be09660"
DEFAULT_SOURCE_EDGE_MARGIN_RATIO = 0.01


def edge_regions(
    width: int, height: int, width_fraction: float, height_fraction: float,
) -> list[tuple[str, int, int, int, int]]:
    if not (0.25 <= width_fraction <= 0.75 and 0.25 <= height_fraction <= 0.75):
        raise ValueError("edge window fractions must be in [0.25, 0.75]")
    window_width = max(1, round(width * width_fraction))
    window_height = max(1, round(height * height_fraction))
    y0 = height - window_height
    return [
        ("lower_left", 0, y0, window_width, height),
        ("lower_right", width - window_width, y0, width, height),
    ]


def belongs_to_outer_band(
    box: Sequence[float], source: str, width: int, height: int,
    maximum_left_center: float, minimum_right_center: float, minimum_y_center: float,
) -> bool:
    center_x = (float(box[0]) + float(box[2])) / 2 / max(width, 1)
    center_y = (float(box[1]) + float(box[3])) / 2 / max(height, 1)
    horizontal = (
        center_x <= maximum_left_center if source == "lower_left"
        else center_x >= minimum_right_center
    )
    return horizontal and center_y >= minimum_y_center


def clears_physical_source_edges(
    box: Sequence[float], width: int, height: int,
    margin_ratio: float = DEFAULT_SOURCE_EDGE_MARGIN_RATIO,
) -> bool:
    """Require an edge-rescued tray to be visibly contained in the source photo.

    Edge-window inference can turn a cut-off physical tray into a confident box.
    A one-percent source margin is a resolution-relative localization guard, not
    a protected-sample coordinate: at 3072x4096 it is about 31x41 pixels.
    Full-frame detections are deliberately unaffected by this rescue-only gate.
    """
    if not 0 <= margin_ratio < 0.5:
        raise ValueError("source edge margin ratio must be in [0, 0.5)")
    left, top, right, bottom = (float(value) for value in box)
    margin_x = width * margin_ratio
    margin_y = height * margin_ratio
    return (
        left > margin_x
        and top > margin_y
        and right < width - margin_x
        and bottom < height - margin_y
    )


class EdgeContextTrayModels:
    def __init__(
        self,
        base: Any,
        detection_type: type,
        edge_base: Any | None = None,
        edge_confidence: float = 0.30,
        width_fraction: float = 0.50,
        height_fraction: float = 0.60,
        maximum_left_center: float = 0.40,
        minimum_right_center: float = 0.60,
        minimum_y_center: float = 0.55,
        source_edge_margin_ratio: float = DEFAULT_SOURCE_EDGE_MARGIN_RATIO,
        skip_full_layout_count: int = 8,
        production_overlap_ios: float = 0.60,
        iou_threshold: float = 0.50,
        ios_threshold: float = 0.80,
    ) -> None:
        self.base = base
        self.edge_base = edge_base or base
        self.detection_type = detection_type
        self.edge_confidence = edge_confidence
        self.width_fraction = width_fraction
        self.height_fraction = height_fraction
        self.maximum_left_center = maximum_left_center
        self.minimum_right_center = minimum_right_center
        self.minimum_y_center = minimum_y_center
        self.source_edge_margin_ratio = source_edge_margin_ratio
        self.skip_full_layout_count = skip_full_layout_count
        self.production_overlap_ios = production_overlap_ios
        self.iou_threshold = iou_threshold
        self.ios_threshold = ios_threshold
        self.last_stats: dict[str, Any] = {}
        self.last_raw_detections: list[dict[str, Any]] = []

    def detect_trays(self, image, conf: float) -> list[Any]:
        height, width = image.shape[:2]
        full = list(self.base.detect_trays(image, conf))
        all_detections = list(full)
        raw_rows = [diagnostics._detection_dict(row, "full") for row in full]
        if len(full) >= self.skip_full_layout_count:
            self.last_raw_detections = raw_rows
            self.last_stats = {
                "mode": "production_full_layout_edge_windows_skipped",
                "tiles": 0,
                "full_frame_detections": len(full),
                "edge_windows_skipped_full_layout": True,
                "skip_full_layout_count": self.skip_full_layout_count,
                "raw_detections": len(full),
                "kept_detections": len(full),
                "duplicates_removed": 0,
                "tile_edge_detections": 0,
            }
            return full
        edge_raw = edge_accepted = edge_rejected = internal_edge_rejected = 0
        source_edge_rejected = production_overlap_rejected = 0
        for name, x0, y0, x1, y1 in edge_regions(
            width, height, self.width_fraction, self.height_fraction,
        ):
            tile = image[y0:y1, x0:x1]
            for detection in self.edge_base.detect_trays(tile, self.edge_confidence):
                edge_raw += 1
                touches_internal_edge = (
                    (x0 > 0 and float(detection.x0) <= 2)
                    or (y0 > 0 and float(detection.y0) <= 2)
                    or (x1 < width and float(detection.x1) >= tile.shape[1] - 2)
                )
                if touches_internal_edge:
                    internal_edge_rejected += 1
                    continue
                mapped = self.detection_type(
                    x0=float(detection.x0) + x0,
                    y0=float(detection.y0) + y0,
                    x1=float(detection.x1) + x0,
                    y1=float(detection.y1) + y0,
                    confidence=float(detection.confidence),
                    class_id=int(detection.class_id),
                )
                if not belongs_to_outer_band(
                    mapped.as_xyxy(), name, width, height,
                    self.maximum_left_center, self.minimum_right_center, self.minimum_y_center,
                ):
                    edge_rejected += 1
                    continue
                if not clears_physical_source_edges(
                    mapped.as_xyxy(), width, height, self.source_edge_margin_ratio,
                ):
                    source_edge_rejected += 1
                    continue
                if any(
                    int(mapped.class_id) == int(reference.class_id)
                    and diagnostics.intersection_over_smaller(
                        mapped.as_xyxy(), reference.as_xyxy(),
                    ) >= self.production_overlap_ios
                    for reference in full
                ):
                    production_overlap_rejected += 1
                    continue
                all_detections.append(mapped)
                raw_rows.append(diagnostics._detection_dict(mapped, name))
                edge_accepted += 1
        kept = diagnostics.deduplicate_detections(
            all_detections, self.iou_threshold, self.ios_threshold,
        )
        self.last_raw_detections = raw_rows
        self.last_stats = {
            "mode": "candidate_full_plus_two_lower_edge_windows",
            "tiles": 2,
            "full_frame_detections": len(full),
            "edge_raw_detections": edge_raw,
            "edge_accepted_before_dedup": edge_accepted,
            "edge_outer_band_rejected": edge_rejected,
            "edge_internal_border_rejected": internal_edge_rejected,
            "edge_physical_source_border_rejected": source_edge_rejected,
            "edge_production_overlap_rejected": production_overlap_rejected,
            "source_edge_margin_ratio": self.source_edge_margin_ratio,
            "production_overlap_ios": self.production_overlap_ios,
            "skip_full_layout_count": self.skip_full_layout_count,
            "raw_detections": len(all_detections),
            "kept_detections": len(kept),
            "duplicates_removed": len(all_detections) - len(kept),
            "tile_edge_detections": internal_edge_rejected,
        }
        return kept

    def detect_labels(self, crop, conf: float) -> list[Any]:
        return self.base.detect_labels(crop, conf)


class RuntimeEdgeContextModels:
    """Inject the bounded tray rescue while preserving all V8 runtime policies."""

    def __init__(self, runtime: Any, edge_models: EdgeContextTrayModels, v8: Any) -> None:
        self.runtime = runtime
        self.edge_models = edge_models
        self.v8 = v8
        self.inner = runtime._require_models()
        self.inner.detect_trays = self._detect_trays
        self.last_stats: dict[str, Any] = {}
        self.last_raw_detections: list[dict[str, Any]] = []

    def _detect_trays(self, image, conf: float) -> list[Any]:
        kept = self.edge_models.detect_trays(image, conf)
        source_by_box = {
            self.v8._box_key(row["box"]): str(row["source"])
            for row in self.edge_models.last_raw_detections
        }
        runtime_rows = []
        for detection in kept:
            source = source_by_box.get(self.v8._box_key(detection.as_xyxy()), "full")
            runtime_rows.append(self.v8._detection_row(
                detection,
                "production_full" if source == "full" else "candidate_lower_band",
            ))
        self.inner.last_raw_detections = runtime_rows
        self.inner.last_stats = {
            **self.edge_models.last_stats,
            "runtime_mode": "production_preserving_two_lower_edge_windows",
        }
        self.inner._set_label_routes(image, kept)
        self.last_stats = dict(self.inner.last_stats)
        self.last_raw_detections = list(runtime_rows)
        return kept

    def screen_image(self, image, params: Any) -> Any:
        result = self.runtime.screen_image(image, params)
        self.last_stats = dict(self.inner.last_stats)
        self.last_raw_detections = list(self.inner.last_raw_detections)
        return result


def load_and_verify(path: Path, expected_hash: str | None = None) -> dict[str, Any]:
    if not path.is_file():
        raise FileNotFoundError(path)
    if expected_hash and diagnostics.sha256_file(path) != expected_hash:
        raise RuntimeError(f"hash drift: {path}")
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"expected object: {path}")
    return payload


def load_independent_normal_records(
    manifest_paths: Sequence[Path], protected_ids: set[str],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    records: list[dict[str, Any]] = []
    inputs: list[dict[str, Any]] = []
    seen: set[str] = set()
    for manifest_path in manifest_paths:
        resolved = manifest_path.resolve()
        loaded = diagnostics.load_records(resolved)
        selected = [row for row in loaded if row["human_label"] not in diagnostics.DEFECT_LABELS]
        for row in selected:
            photo_id = row["photo_id"]
            if photo_id in protected_ids:
                raise RuntimeError(f"independent normal overlaps protected photo: {photo_id}")
            if photo_id in seen:
                raise RuntimeError(f"duplicate independent normal photo: {photo_id}")
            seen.add(photo_id)
            records.append(row)
        inputs.append({
            "path": str(resolved),
            "sha256": diagnostics.sha256_file(resolved),
            "records": len(loaded),
            "normal_records_selected": len(selected),
        })
    return records, inputs


def corrected_gate(
    baseline: dict[str, Any], candidate: dict[str, Any], occlusion_audit: dict[str, Any],
    parity_mismatches: int, production_parity_mismatches: int,
    independent_normal: dict[str, Any] | None = None,
) -> dict[str, Any]:
    baseline_rows = {row["photo_id"]: row for row in baseline["details"]}
    candidate_rows = {row["photo_id"]: row for row in candidate["details"]}
    scope_rows = {row["photo_id"]: row for row in occlusion_audit.get("rows") or []}
    judgeable_targets = [
        photo_id for photo_id, row in scope_rows.items()
        if row.get("status") == "JUDGEABLE_HUMAN_TRAY_MATCH"
        and row.get("counted_as_tray_detector_miss") is True
    ]
    ignored_targets = [
        {"photo_id": photo_id, "status": row.get("status")}
        for photo_id, row in scope_rows.items()
        if photo_id not in judgeable_targets
    ]
    target_results = [
        {
            "photo_id": photo_id,
            "baseline_tray_covered": bool(baseline_rows[photo_id]["tray_target_covered"]),
            "candidate_tray_covered": bool(candidate_rows[photo_id]["tray_target_covered"]),
            "baseline_hit": bool(baseline_rows[photo_id]["hit"]),
            "candidate_hit": bool(candidate_rows[photo_id]["hit"]),
        }
        for photo_id in judgeable_targets
    ]
    defect_regressions = [
        photo_id for photo_id, before in baseline_rows.items()
        if before["kind"] == "defect" and before.get("hit")
        and not candidate_rows[photo_id].get("hit")
    ]
    normal_increases = [
        {
            "photo_id": photo_id,
            "before": int(before["false_flags"]),
            "after": int(candidate_rows[photo_id]["false_flags"]),
        }
        for photo_id, before in baseline_rows.items()
        if before["kind"] == "normal"
        and int(candidate_rows[photo_id]["false_flags"]) > int(before["false_flags"])
    ]
    errors: list[str] = []
    if not target_results or not all(row["candidate_tray_covered"] and row["candidate_hit"] for row in target_results):
        errors.append("judgeable protected tray miss was not recovered end-to-end")
    if defect_regressions:
        errors.append(f"protected defect regressions: {defect_regressions}")
    if int(candidate["false_flags"]) > int(baseline["false_flags"]):
        errors.append("total normal false flags increased")
    if float(candidate["p95_latency_ms"]) > float(baseline["p95_latency_ms"]) * 1.50:
        errors.append("CPU p95 latency exceeded 1.50x production")
    if parity_mismatches > production_parity_mismatches or parity_mismatches > 1:
        errors.append("PT/ONNX parity does not meet strict promotion gate")
    if independent_normal is not None and int(independent_normal["false_flags"]) > 0:
        errors.append("independent normal replay added false flags")
    return {
        "scope_corrected": True,
        "judgeable_targets": target_results,
        "ignored_legacy_targets": ignored_targets,
        "defect_regressions": defect_regressions,
        "normal_flag_increases": normal_increases,
        "baseline_false_flags": baseline["false_flags"],
        "candidate_false_flags": candidate["false_flags"],
        "baseline_p95_latency_ms": baseline["p95_latency_ms"],
        "candidate_p95_latency_ms": candidate["p95_latency_ms"],
        "onnx_parity_mismatches": parity_mismatches,
        "production_onnx_parity_mismatches": production_parity_mismatches,
        "independent_normal_records": (
            int(independent_normal["records"]) if independent_normal is not None else 0
        ),
        "independent_normal_false_flags": (
            int(independent_normal["false_flags"]) if independent_normal is not None else None
        ),
        "offline_development_passed": not errors,
        "promotion_allowed": False,
        "deployment_allowed": False,
        "errors": errors,
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--baseline-receipt", required=True, type=Path)
    parser.add_argument("--candidate-receipt", required=True, type=Path)
    parser.add_argument("--dataset-manifest", required=True, type=Path)
    parser.add_argument("--occlusion-audit", required=True, type=Path)
    parser.add_argument(
        "--truth-adjudication", type=Path,
        help="append-only reviewed sidecar applied without modifying the protected manifest",
    )
    parser.add_argument(
        "--runtime-bundle", type=Path,
        help="hash-bound V8 deployment bundle; enables production-equivalent fail-closed replay",
    )
    parser.add_argument(
        "--independent-normal-manifest", action="append", type=Path, default=[],
        help="reviewed manifest; only non-defect rows are replayed as reject-only false-positive gates",
    )
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--edge-confidence", type=float, default=0.30)
    parser.add_argument("--edge-width-fraction", type=float, default=0.50)
    parser.add_argument("--edge-height-fraction", type=float, default=0.60)
    parser.add_argument(
        "--source-edge-margin-ratio", type=float,
        default=DEFAULT_SOURCE_EDGE_MARGIN_RATIO,
    )
    return parser


def main() -> None:
    args = build_parser().parse_args()
    if args.runtime_bundle and not args.truth_adjudication:
        raise RuntimeError("production-equivalent replay requires reviewed truth adjudication")
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError(f"refusing to overwrite edge evaluation: {output}")
    baseline_receipt = load_and_verify(args.baseline_receipt.resolve())
    candidate_receipt = load_and_verify(args.candidate_receipt.resolve())
    dataset = load_and_verify(args.dataset_manifest.resolve())
    occlusion_audit = load_and_verify(args.occlusion_audit.resolve())
    if candidate_receipt.get("dataset_id") != dataset.get("dataset_id"):
        raise RuntimeError("candidate/dataset id mismatch")
    if candidate_receipt.get("dataset_sha256") != dataset.get("dataset_sha256"):
        raise RuntimeError("candidate/dataset hash mismatch")
    manifest = Path(str(baseline_receipt["inputs"]["manifest"]))
    production_tray = Path(str(baseline_receipt["inputs"]["tray_model"]))
    label_model = Path(str(baseline_receipt["inputs"]["label_model"]))
    candidate_tray = Path(str(candidate_receipt["artifact"]))
    for path, expected in (
        (manifest, baseline_receipt["inputs"]["manifest_sha256"]),
        (production_tray, baseline_receipt["inputs"]["tray_model_sha256"]),
        (label_model, baseline_receipt["inputs"]["label_model_sha256"]),
        (candidate_tray, candidate_receipt["artifact_sha256"]),
    ):
        if not path.is_file() or diagnostics.sha256_file(path) != expected:
            raise RuntimeError(f"bound input drift: {path}")
    records = diagnostics.load_records(manifest)
    records_before_truth_adjudication = len(records)
    truth_adjudication = None
    if args.truth_adjudication:
        from evaluate_tray_candidate import apply_truth_adjudication

        records, truth_adjudication = apply_truth_adjudication(
            records, args.truth_adjudication.resolve(), [manifest.resolve()],
        )
    independent_records, independent_inputs = load_independent_normal_records(
        args.independent_normal_manifest,
        {row["photo_id"] for row in records},
    )
    human_manifest = Path(str(baseline_receipt["inputs"]["human_audit"]["manifest"]))
    audits, human_audit = diagnostics.load_human_audits(human_manifest)
    sys.path.insert(0, str((args.repo_root.resolve() / "backend" / "python").resolve()))
    from label_qc.services import screening
    from label_qc.services import v8_pipeline
    from label_qc.services import yolo_detector as yolo

    params = screening.ScreeningParams(tray_conf=0.60, label_conf=0.20)
    runtime_input = None
    with tempfile.TemporaryDirectory(prefix="label-qc-edge-context-") as temporary:
        root = Path(temporary)
        production_dir, candidate_dir = root / "production", root / "candidate"
        production_dir.mkdir()
        candidate_dir.mkdir()
        shutil.copy2(production_tray, production_dir / "tray.onnx")
        shutil.copy2(candidate_tray, candidate_dir / "tray.onnx")
        shutil.copy2(label_model, production_dir / "label.onnx")
        shutil.copy2(label_model, candidate_dir / "label.onnx")
        production_base = yolo.LabelQcYoloModels(model_dir=production_dir)
        candidate_base = yolo.LabelQcYoloModels(model_dir=candidate_dir)
        if not production_base.available or not candidate_base.available:
            raise RuntimeError(production_base.load_error or candidate_base.load_error or "model unavailable")
        edge_models = EdgeContextTrayModels(
            production_base,
            yolo.Detection,
            edge_base=candidate_base,
            edge_confidence=args.edge_confidence,
            width_fraction=args.edge_width_fraction,
            height_fraction=args.edge_height_fraction,
            source_edge_margin_ratio=args.source_edge_margin_ratio,
        )
        if args.runtime_bundle:
            runtime_bundle = args.runtime_bundle.resolve()
            production_runtime = v8_pipeline.LabelQcV8Models(runtime_bundle)
            candidate_runtime = v8_pipeline.LabelQcV8Models(runtime_bundle)
            if not production_runtime.available or not candidate_runtime.available:
                raise RuntimeError(
                    production_runtime.load_error or candidate_runtime.load_error
                    or "V8 runtime bundle unavailable"
                )
            production_models = production_runtime
            candidate_models = RuntimeEdgeContextModels(
                candidate_runtime, edge_models, v8_pipeline,
            )
            params = production_runtime.recommended_params()
            runtime_manifest = runtime_bundle / v8_pipeline.MANIFEST_FILE
            runtime_input = {
                "bundle": str(runtime_bundle),
                "manifest": str(runtime_manifest),
                "manifest_sha256": diagnostics.sha256_file(runtime_manifest),
                "model_id": production_runtime.model_id,
                "production_equivalent_fail_closed": True,
            }
        else:
            production_models = diagnostics.FullFrameModels(production_base)
            candidate_models = edge_models
        oracles = diagnostics.build_truth_oracles(
            records, production_base, screening, yolo, params, audits,
        )
        baseline = diagnostics.evaluate_variant(
            "production_full_frame", records, production_models, screening, params, oracles,
        )
        candidate = diagnostics.evaluate_variant(
            "candidate_full_plus_two_lower_edge_windows",
            records, candidate_models, screening, params, oracles,
        )
        independent_normal = (
            diagnostics.evaluate_variant(
                "candidate_independent_normal_replay",
                independent_records, candidate_models, screening, params, {},
            )
            if independent_records else None
        )
    gate = corrected_gate(
        baseline,
        candidate,
        occlusion_audit,
        int(candidate_receipt.get("onnx_parity_mismatches", -1)),
        int(candidate_receipt.get("production_onnx_parity_mismatches", -1)),
        independent_normal,
    )
    payload = {
        "version": "label-qc-tray-edge-context-eval-v2",
        "created_at": diagnostics.utc_now(),
        "purpose": "bounded lower-edge zoom A/B after full-image candidate missed one judgeable tray",
        "inputs": {
            "baseline_receipt": str(args.baseline_receipt.resolve()),
            "baseline_receipt_sha256": diagnostics.sha256_file(args.baseline_receipt),
            "candidate_receipt": str(args.candidate_receipt.resolve()),
            "candidate_receipt_sha256": diagnostics.sha256_file(args.candidate_receipt),
            "dataset_manifest": str(args.dataset_manifest.resolve()),
            "dataset_manifest_sha256": diagnostics.sha256_file(args.dataset_manifest),
            "occlusion_audit": str(args.occlusion_audit.resolve()),
            "occlusion_audit_sha256": diagnostics.sha256_file(args.occlusion_audit),
            "protected_manifest": str(manifest),
            "protected_manifest_sha256": diagnostics.sha256_file(manifest),
            "records_before_truth_adjudication": records_before_truth_adjudication,
            "truth_adjudication": truth_adjudication,
            "edge_confidence": args.edge_confidence,
            "edge_width_fraction": args.edge_width_fraction,
            "edge_height_fraction": args.edge_height_fraction,
            "source_edge_margin_ratio": args.source_edge_margin_ratio,
            "fixed_windows": "lower_left_and_lower_right",
            "target_location_used_at_runtime": False,
            "independent_normal_manifests": independent_inputs,
            "runtime": runtime_input,
        },
        "human_audit": human_audit,
        "baseline": baseline,
        "candidate": candidate,
        "independent_normal": independent_normal,
        "comparison": diagnostics.compare_variants(baseline, candidate),
        "gate": gate,
        "training_started": False,
        "deployment_started": False,
        "production_writes": 0,
        "originals_modified": 0,
    }
    output.mkdir(parents=True)
    receipt = output / "receipt.json"
    receipt.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "receipt": str(receipt),
        "baseline": {key: baseline[key] for key in (
            "defect_hits", "tray_target_hits", "false_flags", "normal_flagged_photos", "p95_latency_ms",
        )},
        "candidate": {key: candidate[key] for key in (
            "defect_hits", "tray_target_hits", "false_flags", "normal_flagged_photos", "p95_latency_ms",
        )},
        "gate": gate,
        "deployment_started": False,
        "production_writes": 0,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
