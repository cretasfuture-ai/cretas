#!/usr/bin/env python3
"""Seal, verify, and crop-smoke-test the fixed offline LocateAnything teacher."""
from __future__ import annotations

import argparse
import json
import multiprocessing
import queue
import time
from pathlib import Path
from typing import Any

from PIL import Image, ImageOps

from mine_tray_queue import (
    BOX_PATTERN,
    DEFAULT_TEACHER_PATH,
    TEACHER_LICENSE,
    TEACHER_MAX_NEW_TOKENS,
    TEACHER_MAX_SIDE,
    TEACHER_MODEL_ID,
    TEACHER_MODEL_RECEIPT,
    TEACHER_PROMPTS,
    TEACHER_REVISION,
    LocateAnythingTeacher,
    parse_teacher_boxes,
    seal_teacher_model,
    utc_now,
    verify_teacher_model,
    write_json,
)


SMOKE_MAX_SECONDS = 30.0
SMOKE_MAX_RAW_BOXES = 64
SMOKE_MAX_CROP_AREA_RATIO = 0.72
SEAL_CONFIRMATION = "SEAL-NEW-SNAPSHOT"


def parse_crop(value: str) -> tuple[int, int, int, int]:
    try:
        coordinates = tuple(int(part.strip()) for part in value.split(","))
    except ValueError as exc:
        raise argparse.ArgumentTypeError("crop must be x0,y0,x1,y1 pixels") from exc
    if len(coordinates) != 4:
        raise argparse.ArgumentTypeError("crop must contain four pixel coordinates")
    x0, y0, x1, y1 = coordinates
    if x0 < 0 or y0 < 0 or x1 <= x0 or y1 <= y0:
        raise argparse.ArgumentTypeError("crop must have positive area")
    return coordinates


def validate_seal_request(model_path: Path | None, confirmation: str | None) -> Path:
    if model_path is None:
        raise RuntimeError("seal requires an explicit new snapshot --model-path")
    if confirmation != SEAL_CONFIRMATION:
        raise RuntimeError(f"seal requires --confirm-seal {SEAL_CONFIRMATION}")
    if model_path.resolve() == DEFAULT_TEACHER_PATH.resolve():
        raise RuntimeError("seal is forbidden for the fixed B teacher path")
    if not model_path.is_dir():
        raise RuntimeError(f"new snapshot directory unavailable: {model_path}")
    receipt_path = model_path / TEACHER_MODEL_RECEIPT
    if receipt_path.exists():
        raise RuntimeError(f"snapshot is already sealed and immutable: {receipt_path}")
    return model_path


def validate_smoke_crop(
    image_path: Path, crop_box: tuple[int, int, int, int],
) -> tuple[tuple[int, int], float]:
    with Image.open(image_path) as opened:
        image = ImageOps.exif_transpose(opened).convert("RGB")
    x0, y0, x1, y1 = crop_box
    if x1 > image.width or y1 > image.height:
        raise RuntimeError(f"crop exceeds image bounds {image.size}: {crop_box}")
    crop_area_ratio = ((x1 - x0) * (y1 - y0)) / (image.width * image.height)
    if crop_area_ratio >= SMOKE_MAX_CROP_AREA_RATIO:
        raise RuntimeError(
            "smoke crop must remain local: "
            f"area ratio {crop_area_ratio:.4f} must be below {SMOKE_MAX_CROP_AREA_RATIO:.2f}"
        )
    return image.size, crop_area_ratio


def _smoke_worker_body(
    model_path: Path,
    revision: str,
    image_path: Path,
    crop_box: tuple[int, int, int, int],
    prompt_name: str,
) -> dict[str, object]:
    integrity = verify_teacher_model(model_path, revision)
    with Image.open(image_path) as opened:
        image = ImageOps.exif_transpose(opened).convert("RGB")
    crop = image.crop(crop_box)
    original_crop_size = crop.size
    if max(crop.size) > TEACHER_MAX_SIDE:
        crop.thumbnail((TEACHER_MAX_SIDE, TEACHER_MAX_SIDE), Image.Resampling.LANCZOS)
    teacher = LocateAnythingTeacher(str(model_path), revision)
    allocated_after_load = teacher.torch.cuda.memory_allocated()
    teacher.torch.cuda.reset_peak_memory_stats()
    started = time.perf_counter()
    answer = teacher.predict(crop, TEACHER_PROMPTS[prompt_name])
    elapsed = round(time.perf_counter() - started, 3)
    raw_box_count = len(BOX_PATTERN.findall(answer))
    boxes = parse_teacher_boxes(answer)
    peak_memory = teacher.torch.cuda.max_memory_allocated()
    return {
        "model_integrity": integrity,
        "original_crop_size": list(original_crop_size),
        "inference_size": list(crop.size),
        "elapsed_seconds": elapsed,
        "raw_box_count": raw_box_count,
        "deduplicated_box_count": len(boxes),
        "boxes": boxes,
        "gpu_allocated_after_load_bytes": allocated_after_load,
        "gpu_peak_allocated_bytes": peak_memory,
        "answer": answer,
    }


def _smoke_worker_entry(result_queue: Any, *args: Any) -> None:
    try:
        result_queue.put({"ok": True, "result": _smoke_worker_body(*args)})
    except BaseException as exc:  # child must report failure without writing a receipt
        result_queue.put({"ok": False, "error_type": type(exc).__name__, "error": str(exc)})


def enforce_process_timeout(process: Any, timeout_seconds: float) -> None:
    process.join(timeout_seconds)
    if not process.is_alive():
        return
    process.terminate()
    process.join(2.0)
    if process.is_alive():
        process.kill()
        process.join(2.0)
    raise TimeoutError(f"LocateAnything crop smoke exceeded {timeout_seconds:g}s")


def run_smoke_worker(
    model_path: Path,
    revision: str,
    image_path: Path,
    crop_box: tuple[int, int, int, int],
    prompt_name: str,
) -> dict[str, object]:
    context = multiprocessing.get_context("spawn")
    result_queue = context.Queue()
    process = context.Process(
        target=_smoke_worker_entry,
        args=(result_queue, model_path, revision, image_path, crop_box, prompt_name),
    )
    process.start()
    try:
        enforce_process_timeout(process, SMOKE_MAX_SECONDS)
    except TimeoutError:
        result_queue.close()
        raise
    try:
        message = result_queue.get(timeout=1.0)
    except queue.Empty as exc:
        raise RuntimeError(f"LocateAnything smoke worker exited without result: {process.exitcode}") from exc
    finally:
        result_queue.close()
    if message.get("ok") is not True:
        raise RuntimeError(f"{message.get('error_type')}: {message.get('error')}")
    result = message.get("result")
    if not isinstance(result, dict):
        raise RuntimeError("LocateAnything smoke worker returned an invalid result")
    return result


def smoke(
    model_path: Path,
    revision: str,
    image_path: Path,
    crop_box: tuple[int, int, int, int],
    prompt_name: str,
    receipt_path: Path,
) -> dict[str, object]:
    image_size, crop_area_ratio = validate_smoke_crop(image_path, crop_box)
    worker = run_smoke_worker(model_path, revision, image_path, crop_box, prompt_name)
    elapsed = float(worker["elapsed_seconds"])
    raw_box_count = int(worker["raw_box_count"])
    if elapsed > SMOKE_MAX_SECONDS or raw_box_count > SMOKE_MAX_RAW_BOXES:
        raise RuntimeError("LocateAnything crop smoke exceeded bounded result limits")
    payload: dict[str, object] = {
        "version": "vision-lab-locateanything-smoke-v1",
        "created_at": utc_now(),
        "status": "passed",
        "model_id": TEACHER_MODEL_ID,
        "revision": revision,
        "license_scope": TEACHER_LICENSE,
        "model_integrity": worker["model_integrity"],
        "image": str(image_path),
        "image_size": list(image_size),
        "crop_box_pixels": list(crop_box),
        "crop_area_ratio": crop_area_ratio,
        "maximum_crop_area_ratio": SMOKE_MAX_CROP_AREA_RATIO,
        "original_crop_size": worker["original_crop_size"],
        "inference_size": worker["inference_size"],
        "crop_max_side": TEACHER_MAX_SIDE,
        "full_image_inference": False,
        "teacher_is_ground_truth": False,
        "cloud_calls": 0,
        "prompt": prompt_name,
        "max_new_tokens": TEACHER_MAX_NEW_TOKENS,
        "elapsed_seconds": elapsed,
        "maximum_elapsed_seconds": SMOKE_MAX_SECONDS,
        "raw_box_count": raw_box_count,
        "maximum_raw_boxes": SMOKE_MAX_RAW_BOXES,
        "deduplicated_box_count": worker["deduplicated_box_count"],
        "boxes": worker["boxes"],
        "gpu_allocated_after_load_bytes": worker["gpu_allocated_after_load_bytes"],
        "gpu_peak_allocated_bytes": worker["gpu_peak_allocated_bytes"],
        "answer": worker["answer"],
    }
    write_json(receipt_path, payload)
    return payload


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("seal", "verify", "smoke"))
    parser.add_argument("--model-path", type=Path)
    parser.add_argument("--revision", default=TEACHER_REVISION)
    parser.add_argument("--confirm-seal")
    parser.add_argument("--image", type=Path)
    parser.add_argument("--crop", type=parse_crop)
    parser.add_argument("--prompt", choices=tuple(TEACHER_PROMPTS), default="all")
    parser.add_argument("--receipt", type=Path)
    args = parser.parse_args()
    if args.action == "seal":
        try:
            model_path = validate_seal_request(args.model_path, args.confirm_seal)
        except RuntimeError as exc:
            parser.error(str(exc))
        result = seal_teacher_model(model_path, args.revision)
    elif args.action == "verify":
        result = verify_teacher_model(args.model_path or DEFAULT_TEACHER_PATH, args.revision)
    else:
        if args.image is None or args.crop is None or args.receipt is None:
            parser.error("smoke requires --image, --crop, and --receipt")
        result = smoke(
            args.model_path or DEFAULT_TEACHER_PATH, args.revision, args.image,
            args.crop, args.prompt, args.receipt,
        )
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
