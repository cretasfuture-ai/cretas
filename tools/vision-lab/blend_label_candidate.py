#!/usr/bin/env python3
"""Build a guarded label-model candidate by interpolating two compatible checkpoints."""
from __future__ import annotations

import argparse
import datetime as dt
import json
import shutil
from pathlib import Path
from typing import Any, Mapping

import torch

from vision_lab import (
    DEFAULT_TRAY_PAD_RATIO,
    VERSION,
    evaluate_label_presence,
    evaluate_label_presence_gate,
    sha256_file,
    verify_export_parity,
    write_json,
)


def blend_state_dicts(
    baseline: Mapping[str, torch.Tensor],
    trained: Mapping[str, torch.Tensor],
    alpha: float,
) -> tuple[dict[str, torch.Tensor], dict[str, Any]]:
    if not 0.0 < alpha < 1.0:
        raise ValueError("alpha must be strictly between 0 and 1")
    if list(baseline) != list(trained):
        raise ValueError("checkpoint state dictionaries have different keys or ordering")

    blended: dict[str, torch.Tensor] = {}
    floating_tensors = 0
    changed_floating_tensors = 0
    non_floating_tensors = 0
    differing_non_floating_tensors: list[str] = []
    for key, baseline_tensor in baseline.items():
        trained_tensor = trained[key]
        if baseline_tensor.shape != trained_tensor.shape:
            raise ValueError(f"checkpoint tensor shape differs: {key}")
        if baseline_tensor.dtype != trained_tensor.dtype:
            raise ValueError(f"checkpoint tensor dtype differs: {key}")
        if baseline_tensor.is_floating_point():
            floating_tensors += 1
            if not torch.equal(baseline_tensor, trained_tensor):
                changed_floating_tensors += 1
            blended[key] = (
                baseline_tensor.float().mul(1.0 - alpha)
                .add(trained_tensor.float(), alpha=alpha)
                .to(dtype=baseline_tensor.dtype)
            )
        else:
            non_floating_tensors += 1
            if not torch.equal(baseline_tensor, trained_tensor):
                differing_non_floating_tensors.append(key)
            # Counters such as BatchNorm num_batches_tracked are metadata, not
            # learnable values.  Preserve the production-equivalent baseline.
            blended[key] = baseline_tensor.clone()
    return blended, {
        "floating_tensors": floating_tensors,
        "changed_floating_tensors": changed_floating_tensors,
        "non_floating_tensors": non_floating_tensors,
        "differing_non_floating_tensors_preserved_from_baseline": differing_non_floating_tensors,
    }


def runtime_shape_gate(artifact_imgsz: int, required_imgsz: int) -> dict[str, Any]:
    if artifact_imgsz <= 0 or required_imgsz <= 0:
        raise ValueError("runtime image sizes must be positive")
    passed = artifact_imgsz == required_imgsz
    return {
        "passed": passed,
        "artifact_input_size": artifact_imgsz,
        "required_runtime_input_size": required_imgsz,
        "error": None if passed else (
            f"runtime input size mismatch: candidate={artifact_imgsz}, required={required_imgsz}"
        ),
    }


def build_candidate(args: argparse.Namespace) -> dict[str, Any]:
    from ultralytics import YOLO

    baseline_path = args.baseline.resolve()
    trained_path = args.trained.resolve()
    dataset_manifest_path = args.dataset_manifest.resolve()
    for path in (baseline_path, trained_path, dataset_manifest_path):
        if not path.is_file():
            raise FileNotFoundError(path)
    dataset = json.loads(dataset_manifest_path.read_text(encoding="utf-8"))
    validation_dir = Path(dataset["data_yaml"]).resolve().parent / "images" / "val"
    if not validation_dir.is_dir():
        raise RuntimeError(f"validation directory is missing: {validation_dir}")

    baseline_yolo = YOLO(str(baseline_path))
    trained_yolo = YOLO(str(trained_path))
    blended, tensor_audit = blend_state_dicts(
        baseline_yolo.model.state_dict(), trained_yolo.model.state_dict(), args.alpha,
    )
    baseline_yolo.model.load_state_dict(blended, strict=True)

    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    model_id = args.model_id or f"label-blend-a{round(args.alpha * 100):02d}-{stamp}"
    experiment_dir = args.output_root.resolve() / "experiments" / model_id
    registry_dir = args.output_root.resolve() / "models" / "registry" / model_id
    experiment_dir.mkdir(parents=True, exist_ok=False)
    registry_dir.mkdir(parents=True, exist_ok=False)
    best_pt = experiment_dir / "blend.pt"
    baseline_yolo.save(str(best_pt))

    exported = YOLO(str(best_pt)).export(
        format="onnx", imgsz=args.imgsz, opset=12, simplify=True,
        nms=True, dynamic=False, conf=args.export_floor,
    )
    artifact = registry_dir / "label.onnx"
    shutil.copy2(Path(exported), artifact)
    parity_mismatches = verify_export_parity(
        best_pt, artifact, validation_dir, args.threshold, args.imgsz,
    )
    presence = {
        "validation_dir": str(validation_dir),
        "protected_holdout_included": False,
        "baseline": evaluate_label_presence(
            baseline_path, validation_dir, args.threshold, args.imgsz, args.ownership_pad_ratio,
        ),
        "candidate": evaluate_label_presence(
            artifact, validation_dir, args.threshold, args.imgsz, args.ownership_pad_ratio,
        ),
    }
    receipt = {
        "version": VERSION,
        "model_id": model_id,
        "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "dataset_id": dataset.get("dataset_id"),
        "base_model": str(baseline_path),
        "base_model_sha256": sha256_file(baseline_path),
        "source_trained_model": str(trained_path),
        "source_trained_model_sha256": sha256_file(trained_path),
        "best_pt": str(best_pt),
        "artifact": str(artifact),
        "artifact_sha256": sha256_file(artifact),
        "onnx_parity_mismatches": parity_mismatches,
        "status": "candidate",
        "offline_only": True,
        "production_writes": 0,
        "runtime_compatibility": runtime_shape_gate(args.imgsz, args.runtime_imgsz),
        "label_presence_validation": presence,
        "candidate_construction": {
            "strategy": "linear-checkpoint-interpolation-v1",
            "alpha": args.alpha,
            "non_floating_policy": "preserve-baseline",
            "tensor_audit": tensor_audit,
            "imgsz": args.imgsz,
            "threshold": args.threshold,
        },
    }
    receipt_path = registry_dir / "training-receipt.json"
    write_json(receipt_path, receipt)
    gate = evaluate_label_presence_gate(receipt)
    if not receipt["runtime_compatibility"]["passed"]:
        gate["passed"] = False
        gate["errors"].append(receipt["runtime_compatibility"]["error"])
    gate["candidate_status"] = (
        "presence_gate_passed_pending_protected_replay"
        if gate["passed"] else "rejected_before_protected_replay"
    )
    gate["production_writes"] = 0
    gate["deployment_started"] = False
    write_json(registry_dir / "presence-gate.json", gate)
    return receipt | {"presence_gate": gate, "receipt": str(receipt_path)}


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--baseline", required=True, type=Path)
    result.add_argument("--trained", required=True, type=Path)
    result.add_argument("--dataset-manifest", required=True, type=Path)
    result.add_argument("--output-root", required=True, type=Path)
    result.add_argument("--alpha", required=True, type=float)
    result.add_argument("--imgsz", type=int, default=960)
    result.add_argument("--runtime-imgsz", type=int, default=640)
    result.add_argument("--threshold", type=float, default=0.20)
    result.add_argument("--ownership-pad-ratio", type=float, default=DEFAULT_TRAY_PAD_RATIO)
    result.add_argument("--export-floor", type=float, default=0.05)
    result.add_argument("--model-id")
    return result


def main() -> int:
    result = build_candidate(parser().parse_args())
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
