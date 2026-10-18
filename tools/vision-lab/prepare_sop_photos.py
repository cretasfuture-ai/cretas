"""Turn a frozen factory photo-preparation export into local SOP inputs.

This module is an intentionally small, file-only bridge.  It accepts the
immutable export returned by ``PreparationStore.export`` and writes a new,
empty external project root.  It never downloads files, invokes a command, or
touches a registry.  The existing VisionLab dataset builder remains the only
dataset builder used after this bridge has finished.
"""
from __future__ import annotations

import argparse
import base64
from io import BytesIO
import hashlib
import json
import math
import os
from pathlib import Path
import re
from typing import Any
import urllib.error
import urllib.request
from urllib.parse import quote, urlparse

from PIL import Image, ImageOps, UnidentifiedImageError


BUNDLE_SCHEMA = "factory-vision-preparation-v1"
TASK_FAMILY = "liushanmen-tray-label-detection"
LABEL_CLASS_IDS = {"white_label": 0, "color_label": 1}
PHOTO_ID_RE = re.compile(r"^photo-[0-9a-f]{24}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


class PreparationExportError(ValueError):
    """The frozen export is incomplete, mutated, or outside the fixed scope."""


class _RejectRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Keep worker credentials bound to the registered origin."""

    def redirect_request(self, req: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str) -> Any:
        raise urllib.error.URLError("preparation transfer redirects are not allowed")


_NO_REDIRECT_OPENER = urllib.request.build_opener(_RejectRedirectHandler)


def canonical(value: object) -> bytes:
    return json.dumps(
        value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False
    ).encode("utf-8")


def digest(value: object) -> str:
    return hashlib.sha256(canonical(value)).hexdigest()


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _write_json(path: Path, value: object) -> None:
    path.write_bytes(canonical(value) + b"\n")


def _is_reparse(path: Path) -> bool:
    if path.is_symlink():
        return True
    checker = getattr(path, "is_junction", None)
    return bool(checker and checker())


def _assert_real_chain(path: Path) -> None:
    cursor = Path(path.anchor) if path.anchor else Path()
    for part in path.parts[1:] if path.anchor else path.parts:
        cursor = cursor / part
        if _is_reparse(cursor):
            raise PreparationExportError("path must not traverse a symlink or reparse point")


def _reject_url(value: str, label: str) -> None:
    parsed = urlparse(value)
    # ``urlparse`` treats ``C:\\...`` as a scheme on Windows; only reject
    # actual URL schemes and explicit authority syntax.
    if parsed.scheme.lower() in {"http", "https", "ftp", "file"} or "://" in value:
        raise PreparationExportError(f"{label} must be a local path, not a URL")


def _safe_project_root(value: str | Path) -> Path:
    raw = str(value)
    _reject_url(raw, "project root")
    root = Path(raw).expanduser()
    if not root.is_absolute():
        root = Path.cwd() / root
    root = root.absolute()
    if _is_reparse(root):
        raise PreparationExportError("project root must not be a symlink or reparse point")
    repo_root = Path(__file__).resolve().parents[2]
    if root == repo_root or _within(root, repo_root) or _within(repo_root, root):
        raise PreparationExportError("project root must be an external directory")
    if root.exists():
        if not root.is_dir():
            raise PreparationExportError("project root must be a directory")
        if any(_is_reparse(child) or child.exists() for child in root.iterdir()):
            raise PreparationExportError("project root must be new and empty")
    else:
        root.mkdir(parents=True, exist_ok=False)
    return root


def _within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def _new_dir(root: Path, relative: str) -> Path:
    target = root / relative
    if not _within(target.absolute(), root) or _is_reparse(target):
        raise PreparationExportError("generated path escapes the external project root")
    target.mkdir(parents=True, exist_ok=False)
    return target


def _safe_file(root: Path, relative: str) -> Path:
    target = (root / relative).absolute()
    if not _within(target, root) or _is_reparse(target):
        raise PreparationExportError("generated file escapes the external project root")
    if target.exists():
        raise PreparationExportError("generated output already exists")
    return target


def _strict_base64(value: Any, label: str) -> bytes:
    if not isinstance(value, str) or not value:
        raise PreparationExportError(f"{label} is missing")
    try:
        return base64.b64decode(value, validate=True)
    except (ValueError, TypeError) as exc:
        raise PreparationExportError(f"{label} is not valid base64") from exc


def _bundle_file(bundle_root: Path, value: Any, label: str) -> bytes:
    if not isinstance(value, str) or not value or "\\" in value or value.startswith("/") or re.match(r"^[A-Za-z]:", value) or any(part == ".." for part in Path(value).parts):
        raise PreparationExportError(f"{label} must be a relative bundle file")
    _reject_url(value, label)
    raw = bundle_root / value
    _assert_real_chain(bundle_root)
    try:
        relative = raw.relative_to(bundle_root)
    except ValueError as exc:
        raise PreparationExportError(f"{label} escapes the bundle directory") from exc
    cursor = bundle_root
    for part in relative.parts:
        cursor = cursor / part
        if _is_reparse(cursor):
            raise PreparationExportError(f"{label} must not use a symlink or reparse point")
    path = raw.resolve()
    if not _within(path, bundle_root) or not path.is_file():
        raise PreparationExportError(f"{label} is missing from the bundle directory")
    try:
        return path.read_bytes()
    except OSError as exc:
        raise PreparationExportError(f"{label} cannot be read") from exc


def _payload_bytes(payload: Any, bundle_root: Path, *, base64_key: str, file_key: str, label: str) -> bytes:
    if not isinstance(payload, dict):
        raise PreparationExportError(f"{label} payload is malformed")
    has_base64 = base64_key in payload
    has_file = file_key in payload
    if has_base64 == has_file:
        raise PreparationExportError(f"{label} must use exactly one local payload form")
    return _strict_base64(payload.get(base64_key), label) if has_base64 else _bundle_file(bundle_root, payload.get(file_key), label)


def _pixel_sha256(rendered: bytes) -> tuple[str, int, int, str]:
    try:
        with Image.open(BytesIO(rendered)) as source:
            if source.format != "PNG" or getattr(source, "n_frames", 1) != 1:
                raise PreparationExportError("rendered photo must be a single PNG")
            image = ImageOps.exif_transpose(source).convert("RGB")
            image.load()
    except (UnidentifiedImageError, OSError) as exc:
        raise PreparationExportError("rendered photo is not a readable PNG") from exc
    pixel_hash = sha256_bytes(f"{image.width}x{image.height}:".encode() + image.tobytes())
    tiny = list(image.convert("L").resize((9, 8)).getdata())
    perceptual = sum(
        (tiny[y * 9 + x] > tiny[y * 9 + x + 1]) << (y * 8 + x)
        for y in range(8) for x in range(8)
    )
    return pixel_hash, image.width, image.height, f"{perceptual:016x}"


def _validate_original(original: bytes, width: int, height: int) -> None:
    try:
        with Image.open(BytesIO(original)) as source:
            if source.format not in {"JPEG", "PNG"} or getattr(source, "n_frames", 1) != 1:
                raise PreparationExportError("original photo must be a single JPEG or PNG")
            image = ImageOps.exif_transpose(source).convert("RGB")
            image.load()
    except (UnidentifiedImageError, OSError) as exc:
        raise PreparationExportError("original photo is not a readable JPEG or PNG") from exc
    if image.width != width or image.height != height:
        raise PreparationExportError("original and rendered photo dimensions do not match")


def _validate_box(box: Any) -> dict[str, Any]:
    if not isinstance(box, dict) or box.get("label") not in LABEL_CLASS_IDS:
        raise PreparationExportError("photo annotation contains an unknown label")
    coords = [box.get(key) for key in ("x0", "y0", "x1", "y1")]
    if any(isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(float(value)) for value in coords):
        raise PreparationExportError("photo annotation box coordinates are not finite numbers")
    x0, y0, x1, y1 = map(float, coords)
    if not (0 <= x0 < x1 <= 1 and 0 <= y0 < y1 <= 1):
        raise PreparationExportError("photo annotation box is outside normalized bounds")
    return {"c": LABEL_CLASS_IDS[box["label"]], "b": [x0, y0, x1, y1]}


def _validate_snapshot(bundle: Any, bundle_root: Path, execution_project_id: str | None, expected_factory_id: str | None) -> tuple[dict[str, Any], dict[str, dict[str, bytes]], str]:
    if not isinstance(bundle, dict) or set(bundle) != {"snapshot", "snapshotSha256", "photos"}:
        raise PreparationExportError("preparation export must contain snapshot, snapshotSha256, and photos")
    snapshot = bundle["snapshot"]
    snapshot_sha = str(bundle["snapshotSha256"] or "").lower()
    if not isinstance(snapshot, dict) or snapshot.get("schema") != BUNDLE_SCHEMA:
        raise PreparationExportError("unsupported preparation snapshot schema")
    if not SHA256_RE.fullmatch(snapshot_sha) or digest(snapshot) != snapshot_sha:
        raise PreparationExportError("preparation snapshot hash does not match its content")
    factory_id = snapshot.get("factory_id")
    source_project_id = snapshot.get("project_id")
    if not isinstance(factory_id, str) or not factory_id.strip() or not isinstance(source_project_id, str) or not source_project_id.strip():
        raise PreparationExportError("preparation snapshot identity is incomplete")
    if expected_factory_id is not None and factory_id != expected_factory_id:
        raise PreparationExportError("preparation snapshot crosses factory scope")
    if execution_project_id is not None and (not execution_project_id.strip() or execution_project_id == source_project_id):
        raise PreparationExportError("execution project must be an explicit project distinct from the preparation project")
    if snapshot.get("task_family") != TASK_FAMILY:
        raise PreparationExportError("generic or OCR preparation has no named local runner")
    photos = snapshot.get("photos")
    payloads = bundle.get("photos")
    if not isinstance(photos, list) or not photos or not isinstance(payloads, dict):
        raise PreparationExportError("preparation snapshot has no photos or photo payloads")
    if set(payloads) != {str(photo.get("photoId")) for photo in photos}:
        raise PreparationExportError("photo payloads do not exactly match the frozen snapshot")
    standard = snapshot.get("standard")
    if not isinstance(standard, dict) or standard.get("confirmed") is not True:
        raise PreparationExportError("the quality standard is not confirmed")
    for key in ("passCriteria", "failCriteria", "confirmedBy", "confirmedAt"):
        if not isinstance(standard.get(key), str) or not standard[key].strip():
            raise PreparationExportError("the confirmed quality standard is incomplete")
    for key in ("maxMissRate", "maxFalseAlarmRate"):
        if isinstance(standard.get(key), bool) or not isinstance(standard.get(key), (int, float)) or not math.isfinite(float(standard[key])) or not 0 <= float(standard[key]) <= 100:
            raise PreparationExportError("the confirmed quality standard has an invalid rate")

    seen_ids: set[str] = set()
    seen_originals: set[str] = set()
    seen_pixels: set[str] = set()
    seen_batches: dict[str, str] = {}
    prepared: dict[str, dict[str, Any]] = {}
    for photo in photos:
        if not isinstance(photo, dict):
            raise PreparationExportError("photo metadata is malformed")
        photo_id = str(photo.get("photoId") or "")
        if not PHOTO_ID_RE.fullmatch(photo_id) or photo_id in seen_ids:
            raise PreparationExportError("photo IDs must be unique server-generated IDs")
        seen_ids.add(photo_id)
        purpose = photo.get("purpose")
        verdict = photo.get("verdict")
        if purpose not in {"training", "acceptance"} or verdict not in {"good", "defect", "uncertain"}:
            raise PreparationExportError("photo purpose or verdict is invalid")
        if photo.get("source") != "human" or photo.get("reviewed") is not True:
            raise PreparationExportError("every photo must have a human review")
        for key in ("reviewer", "reviewedAt", "captureGroup", "fileName"):
            if not isinstance(photo.get(key), str) or not photo[key].strip():
                raise PreparationExportError("photo human review or capture identity is incomplete")
        expected_eligible = purpose == "training" and verdict != "uncertain"
        if photo.get("training_eligible") is not expected_eligible:
            raise PreparationExportError("training_eligible does not match the frozen purpose and verdict")
        if not isinstance(photo.get("boxes"), list):
            raise PreparationExportError("photo boxes are malformed")
        boxes = [_validate_box(box) for box in photo["boxes"]]
        if verdict != "uncertain" and not boxes:
            raise PreparationExportError("a judged photo must contain at least one label box")
        original = _payload_bytes(payloads[photo_id], bundle_root, base64_key="originalBase64", file_key="originalFile", label="original photo")
        rendered = _payload_bytes(payloads[photo_id], bundle_root, base64_key="renderedBase64", file_key="renderedFile", label="rendered photo")
        image_sha = str(photo.get("imageSha256") or "").lower()
        render_sha = str(photo.get("renderSha256") or "").lower()
        pixel_sha = str(photo.get("pixelSha256") or "").lower()
        if not SHA256_RE.fullmatch(image_sha) or sha256_bytes(original) != image_sha:
            raise PreparationExportError("original photo hash does not match the frozen metadata")
        if not SHA256_RE.fullmatch(render_sha) or sha256_bytes(rendered) != render_sha:
            raise PreparationExportError("rendered photo hash does not match the frozen metadata")
        actual_pixel, width, height, perceptual = _pixel_sha256(rendered)
        _validate_original(original, width, height)
        if actual_pixel != pixel_sha or photo.get("width") != width or photo.get("height") != height or photo.get("perceptualHash") != perceptual:
            raise PreparationExportError("rendered photo pixels do not match the frozen metadata")
        if image_sha in seen_originals or pixel_sha in seen_pixels:
            raise PreparationExportError("duplicate original or decoded pixels are not allowed")
        seen_originals.add(image_sha)
        seen_pixels.add(pixel_sha)
        batch = photo["captureGroup"].casefold()
        other_purpose = "acceptance" if purpose == "training" else "training"
        if batch in seen_batches and seen_batches[batch] != purpose:
            raise PreparationExportError("training and independent photos share a capture batch")
        seen_batches[batch] = purpose
        # Keep only validated metadata here.  Directory bundles can contain
        # hundreds of megabytes of image bytes; the caller reads one payload
        # again while copying it into the external project root.
        prepared[photo_id] = {"metadata": photo, "boxes": boxes}

    training_pixels = [item["metadata"]["perceptualHash"] for item in prepared.values() if item["metadata"]["purpose"] == "training"]
    acceptance_pixels = [item["metadata"]["perceptualHash"] for item in prepared.values() if item["metadata"]["purpose"] == "acceptance"]
    if any((int(left, 16) ^ int(right, 16)).bit_count() <= 2 for left in training_pixels for right in acceptance_pixels):
        raise PreparationExportError("training and independent photos are too visually similar")
    return snapshot, payloads, snapshot_sha


def _read_bundle(bundle_path: str | Path) -> tuple[dict[str, Any], Path]:
    raw_path = str(bundle_path)
    _reject_url(raw_path, "bundle path")
    path = Path(raw_path).expanduser().absolute()
    _assert_real_chain(path.parent)
    if _is_reparse(path):
        raise PreparationExportError("bundle path must be a real local file or directory")
    if path.is_dir():
        bundle_file = path / "bundle.json"
        bundle_root = path
    else:
        bundle_file = path
        bundle_root = path.parent
    if not bundle_file.is_file() or _is_reparse(bundle_file):
        raise PreparationExportError("bundle path must be a real local file or directory")
    try:
        bundle = json.loads(bundle_file.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise PreparationExportError("bundle is not valid UTF-8 JSON") from exc
    return bundle, bundle_root


def validate_preparation_export(bundle_path: str | Path, *, expected_factory_id: str | None = None, execution_project_id: str | None = None) -> tuple[dict[str, Any], dict[str, Any], str]:
    bundle, bundle_root = _read_bundle(bundle_path)
    return _validate_snapshot(bundle, bundle_root, execution_project_id, expected_factory_id)


def _trusted_get(registration: Any, path: str) -> tuple[Any, dict[str, str]]:
    """GET one fixed worker-transfer path with the registration credentials."""
    registration.validate(require_transport=True)
    fixed_path = path.split("?", 1)[0]
    if not re.fullmatch(r"/api/vision-training/worker/preparations/[A-Za-z0-9_.:-]+/(?:snapshot|photos/[A-Za-z0-9_.:-]+/(?:original|rendered))", fixed_path):
        raise PreparationExportError("preparation transfer path is outside the fixed worker API")
    token = os.environ.get(registration.worker_token_env, "")
    internal_secret = os.environ.get(registration.internal_secret_env, "")
    if not token or not internal_secret:
        raise PreparationExportError("worker transfer credentials are not configured")
    request = urllib.request.Request(
        registration.server_url.rstrip("/") + path,
        headers={"X-Worker-Id": registration.worker_id, "X-Worker-Token": token, "X-Internal-Secret": internal_secret},
        method="GET",
    )
    try:
        # The opener deliberately rejects 301/302/303/307/308.  Following a
        # redirect would forward worker credentials outside the allowlisted
        # registration origin, even when the initial URL was trusted.
        response = _NO_REDIRECT_OPENER.open(request, timeout=30)
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise PreparationExportError("preparation transfer request failed") from exc
    return response, {str(key).lower(): str(value) for key, value in response.headers.items()}


def _validate_download_transport(registration: Any) -> None:
    """Apply the preparation-transfer transport policy to one registration."""
    registration.validate(require_transport=True)
    parsed = urlparse(registration.server_url)
    host = (parsed.hostname or "").lower().rstrip(".")
    if parsed.scheme == "http" and host not in {"127.0.0.1", "localhost", "::1"}:
        raise PreparationExportError("remote preparation transfer requires HTTPS")


def _download_file(response: Any, headers: dict[str, str], destination: Path, expected_sha256: str, max_bytes: int) -> None:
    content_length = headers.get("content-length")
    if content_length is not None:
        try:
            if int(content_length) < 0 or int(content_length) > max_bytes:
                raise PreparationExportError("preparation photo exceeds the fixed transfer limit")
        except ValueError as exc:
            raise PreparationExportError("preparation photo content length is invalid") from exc
    header_sha = headers.get("x-content-sha256", "").lower()
    if not SHA256_RE.fullmatch(header_sha) or header_sha != expected_sha256.lower():
        raise PreparationExportError("preparation photo content hash header does not match the frozen snapshot")
    if destination.exists():
        raise PreparationExportError("download destination already exists")
    partial = destination.with_name(destination.name + ".part")
    if partial.exists():
        raise PreparationExportError("download destination contains an incomplete prior transfer")
    digest = hashlib.sha256()
    total = 0
    try:
        with partial.open("wb") as output:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > max_bytes:
                    raise PreparationExportError("preparation photo exceeds the fixed transfer limit")
                digest.update(chunk)
                output.write(chunk)
        if content_length is not None and total != int(content_length):
            raise PreparationExportError("preparation photo transfer was truncated")
        if digest.hexdigest() != expected_sha256.lower():
            raise PreparationExportError("preparation photo bytes do not match the frozen snapshot")
        partial.replace(destination)
    except PreparationExportError:
        partial.unlink(missing_ok=True)
        raise
    except Exception as exc:
        partial.unlink(missing_ok=True)
        raise PreparationExportError("preparation photo could not be written") from exc
    finally:
        response.close()


def download_preparation_bundle(registration: Any, source_project_id: str, expected_snapshot_sha256: str, destination: str | Path) -> Path:
    """Download one immutable preparation export through the named worker API.

    The destination is created empty and receives a directory bundle only
    after the metadata and every original/rendered object pass their checks.
    """
    if not isinstance(source_project_id, str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,100}", source_project_id):
        raise PreparationExportError("source preparation project ID is invalid")
    expected = str(expected_snapshot_sha256 or "").lower()
    if not SHA256_RE.fullmatch(expected):
        raise PreparationExportError("expected preparation snapshot hash is invalid")
    _validate_download_transport(registration)
    destination_root = _safe_project_root(destination)
    metadata_path = f"/api/vision-training/worker/preparations/{quote(source_project_id, safe='')}/snapshot?snapshotSha256={quote(expected, safe='')}"
    response, headers = _trusted_get(registration, metadata_path)
    try:
        if headers.get("content-type", "").split(";", 1)[0].lower() != "application/json":
            raise PreparationExportError("preparation metadata response is not JSON")
        try:
            envelope = json.loads(response.read().decode("utf-8"))
        except (UnicodeError, json.JSONDecodeError) as exc:
            raise PreparationExportError("preparation metadata response is malformed") from exc
    finally:
        response.close()
    if not isinstance(envelope, dict) or envelope.get("success") is not True or not isinstance(envelope.get("data"), dict):
        raise PreparationExportError("preparation metadata response is not a successful worker response")
    data = envelope["data"]
    snapshot = data.get("snapshot")
    if not isinstance(snapshot, dict) or snapshot.get("factory_id") != registration.factory_id or snapshot.get("project_id") != source_project_id:
        raise PreparationExportError("preparation metadata crosses the worker factory or source-project binding")
    if str(data.get("snapshotSha256") or "").lower() != expected or digest(snapshot) != expected:
        raise PreparationExportError("preparation metadata hash does not match the requested frozen snapshot")
    photo_ids = data.get("photoIds")
    expected_ids = [photo.get("photoId") for photo in snapshot.get("photos", []) if isinstance(photo, dict)]
    if photo_ids != expected_ids or not photo_ids or any(not isinstance(photo_id, str) or not PHOTO_ID_RE.fullmatch(photo_id) for photo_id in photo_ids):
        raise PreparationExportError("preparation metadata photo IDs do not match the frozen snapshot")
    if data.get("contentTransfer") not in {"trusted-local-directory-export", "streaming-worker-transfer-v1"}:
        raise PreparationExportError("preparation metadata does not advertise the fixed content transfer")

    (destination_root / "originals").mkdir()
    (destination_root / "rendered").mkdir()
    payloads: dict[str, dict[str, str]] = {}
    try:
        for photo in snapshot["photos"]:
            photo_id = photo["photoId"]
            values: dict[str, str] = {}
            for kind, folder, limit, digest_key, suffix in (
                ("original", "originals", 5 * 1024 * 1024, "imageSha256", ".bin"),
                ("rendered", "rendered", 32 * 1024 * 1024, "renderSha256", ".png"),
            ):
                path = f"/api/vision-training/worker/preparations/{quote(source_project_id, safe='')}/photos/{quote(photo_id, safe='')}/{kind}?snapshotSha256={quote(expected, safe='')}"
                response, response_headers = _trusted_get(registration, path)
                try:
                    relative = f"{folder}/{photo_id}{suffix}"
                    target = _safe_file(destination_root, relative)
                    _download_file(response, response_headers, target, str(photo.get(digest_key) or ""), limit)
                except Exception:
                    response.close()
                    raise
                values[f"{kind}File"] = relative
            payloads[photo_id] = values
        bundle = {"snapshot": snapshot, "snapshotSha256": expected, "photos": payloads}
        bundle_path = _safe_file(destination_root, "bundle.json")
        _write_json(bundle_path, bundle)
        return bundle_path
    except Exception:
        # No bundle.json is published on partial transfer; callers can inspect
        # the incomplete external directory and retry into a new destination.
        raise


def prepare_sop_photos(bundle_path: str | Path, project_root: str | Path, execution_project_id: str | None = None, *, expected_factory_id: str | None = None) -> dict[str, Any]:
    """Validate one frozen export and materialise external SOP inputs."""
    bundle, bundle_root = _read_bundle(bundle_path)
    snapshot, payloads, snapshot_sha = _validate_snapshot(
        bundle, bundle_root, execution_project_id, expected_factory_id
    )
    root = _safe_project_root(project_root)
    execution_id = execution_project_id or f"prepared-{snapshot_sha[:24]}"
    if execution_id == snapshot["project_id"]:
        raise PreparationExportError("execution project must be distinct from the preparation project")
    originals = _new_dir(root, "originals")
    rendered = _new_dir(root, "rendered")
    queue_root = _new_dir(root, "queue")
    annotations = _new_dir(queue_root, "annotations-human")
    acceptance_root = _new_dir(root, "independent-acceptance")
    acceptance_originals = _new_dir(acceptance_root, "originals")
    acceptance_rendered = _new_dir(acceptance_root, "rendered")
    preparation_path = _safe_file(root, "preparation-snapshot.json")
    _write_json(preparation_path, snapshot)

    queue_rows: list[dict[str, Any]] = []
    acceptance_rows: list[dict[str, Any]] = []
    for photo in snapshot["photos"]:
        photo_id = photo["photoId"]
        item = payloads[photo_id]
        boxes = [_validate_box(box) for box in photo.get("boxes", [])]
        original = _payload_bytes(item, bundle_root, base64_key="originalBase64", file_key="originalFile", label="original photo")
        rendered_bytes = _payload_bytes(item, bundle_root, base64_key="renderedBase64", file_key="renderedFile", label="rendered photo")
        if sha256_bytes(original) != photo["imageSha256"] or sha256_bytes(rendered_bytes) != photo["renderSha256"]:
            raise PreparationExportError("photo payload changed while preparing the external project")
        original_suffix = ".png" if original.startswith(b"\x89PNG") else ".jpg"
        original_path = _safe_file(root, f"originals/{photo_id}{original_suffix}")
        rendered_path = _safe_file(root, f"rendered/{photo_id}.png")
        original_path.write_bytes(original)
        rendered_path.write_bytes(rendered_bytes)
        row_base = {
            "crop_id": photo_id, "source_photo_id": photo_id,
            "source_task_id": photo["captureGroup"], "task_id": photo["captureGroup"],
            "image": str(rendered_path), "image_sha256": photo["renderSha256"],
            "original": str(original_path), "original_sha256": photo["imageSha256"],
            "pixel_sha256": photo["pixelSha256"], "purpose": photo["purpose"],
            "verdict": photo["verdict"], "training_eligible": photo["training_eligible"],
            "reviewer": photo["reviewer"], "reviewed": photo["reviewed"], "source": "human",
        }
        annotation = {
            "schema": "vision-human-annotation-v1", "crop_id": photo_id,
            "source_photo_id": photo_id, "source_task_id": photo["captureGroup"],
            "format": "normalised_xyxy", "reviewed": True, "unjudgeable": photo["verdict"] == "uncertain",
            "source": "human", "reviewer": photo["reviewer"], "reviewedAt": photo["reviewedAt"],
            "training_eligible": photo["training_eligible"], "verdict": photo["verdict"],
            "boxes": boxes,
        }
        if photo["purpose"] == "training" and photo["training_eligible"] and photo["verdict"] != "uncertain":
            annotation_path = _safe_file(root, f"queue/annotations-human/{photo_id}.json")
            _write_json(annotation_path, annotation)
            row_base["annotation_sha256"] = sha256_bytes(annotation_path.read_bytes())
            queue_rows.append(row_base)
        elif (
            photo["purpose"] == "acceptance"
            and photo["verdict"] in {"good", "defect"}
            and photo["reviewed"] is True
            and photo.get("source") == "human"
        ):
            independent_original = _safe_file(root, f"independent-acceptance/originals/{photo_id}{original_suffix}")
            independent_rendered = _safe_file(root, f"independent-acceptance/rendered/{photo_id}.png")
            independent_original.write_bytes(original)
            independent_rendered.write_bytes(rendered_bytes)
            acceptance_rows.append({**row_base, "image": str(independent_rendered), "original": str(independent_original)})

    queue_manifest = {
        "version": "factory-vision-preparation-queue-v1", "schema": "vision-human-queue-v1",
        "factory_id": snapshot["factory_id"], "project_id": execution_id,
        "preparation_project_id": snapshot["project_id"], "preparation_sha256": snapshot_sha,
        "task_family": snapshot["task_family"], "train_only": True,
        "photo_level_human_verdict_authoritative": True, "rows": queue_rows,
    }
    queue_manifest_path = _safe_file(root, "queue/manifest.json")
    _write_json(queue_manifest_path, queue_manifest)
    acceptance_manifest = {
        "version": "factory-vision-preparation-independent-acceptance-v1", "schema": "vision-independent-acceptance-v1",
        "factory_id": snapshot["factory_id"], "project_id": execution_id,
        "preparation_project_id": snapshot["project_id"], "preparation_sha256": snapshot_sha,
        "task_family": snapshot["task_family"], "training_included": False, "rows": acceptance_rows,
    }
    acceptance_manifest_path = _safe_file(root, "independent-acceptance/manifest.json")
    _write_json(acceptance_manifest_path, acceptance_manifest)
    standard_version = "standard-" + digest(snapshot["standard"])
    acceptance_version = "accept-" + digest([photo for photo in snapshot["photos"] if photo["purpose"] == "acceptance"])
    dataset_input = {
        "factory_id": snapshot["factory_id"], "project_id": execution_id,
        "queue_manifests": [{"path": "queue/manifest.json", "sha256": sha256_bytes(queue_manifest_path.read_bytes())}],
        "preparation_snapshot": snapshot, "preparation_sha256": snapshot_sha,
        "preparation_project_id": snapshot["project_id"], "data_version": "prep-" + snapshot_sha,
        "human_standard_version": standard_version, "independent_acceptance_version": acceptance_version,
        "independent_acceptance_manifest": "independent-acceptance/manifest.json",
        "independent_acceptance_manifest_sha256": sha256_bytes(acceptance_manifest_path.read_bytes()),
    }
    dataset_input_path = _safe_file(root, "dataset-input.json")
    _write_json(dataset_input_path, dataset_input)
    return {
        "factory_id": snapshot["factory_id"], "preparation_project_id": snapshot["project_id"],
        "execution_project_id": execution_id, "preparation_sha256": snapshot_sha,
        "data_version": dataset_input["data_version"], "human_standard_version": standard_version,
        "independent_acceptance_version": acceptance_version,
        "queue_root": str(queue_root), "queue_manifest": str(queue_manifest_path),
        "dataset_input": str(dataset_input_path), "independent_acceptance_manifest": str(acceptance_manifest_path),
        "training_rows": len(queue_rows), "acceptance_rows": len(acceptance_rows),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Materialise or download a frozen factory preparation export for the named SOP worker.")
    parser.add_argument("--bundle", type=Path)
    parser.add_argument("--project-root", required=True, type=Path)
    parser.add_argument("--execution-project-id")
    parser.add_argument("--factory-id")
    parser.add_argument("--registration", type=Path, help="download through the fixed worker registration transport")
    parser.add_argument("--source-project-id")
    parser.add_argument("--expected-snapshot-sha256")
    args = parser.parse_args(argv)
    if args.registration:
        if args.bundle or not args.source_project_id or not args.expected_snapshot_sha256:
            parser.error("--registration requires --source-project-id and --expected-snapshot-sha256, and cannot be combined with --bundle")
        import sys
        tools_root = Path(__file__).resolve().parent
        if str(tools_root) not in sys.path:
            sys.path.insert(0, str(tools_root))
        from sop_worker import WorkerRegistration
        result = {"bundle": str(download_preparation_bundle(
            WorkerRegistration.from_json(args.registration), args.source_project_id,
            args.expected_snapshot_sha256, args.project_root,
        ))}
    else:
        if not args.bundle:
            parser.error("--bundle is required unless --registration is used for download")
        result = prepare_sop_photos(args.bundle, args.project_root, args.execution_project_id, expected_factory_id=args.factory_id)
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
