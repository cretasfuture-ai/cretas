"""Targeted proof for the frozen preparation -> existing VisionLab builder path."""
from __future__ import annotations

import base64
from io import BytesIO
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import random
import sys
from urllib.parse import parse_qs, urlparse

import pytest
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "vision_training"))
sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "tools" / "vision-lab"))
from vision_training.preparation import digest
import prepare_sop_photos
import sop_worker


VISION_LAB_PATH = Path(__file__).resolve().parents[3] / "tools" / "vision-lab" / "vision_lab.py"


def _vision_lab():
    spec = importlib.util.spec_from_file_location("vision_lab_prepared_test", VISION_LAB_PATH)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _png(seed: int) -> bytes:
    rng = random.Random(seed)
    image = Image.new("RGB", (32, 32))
    image.putdata([(rng.randrange(256), rng.randrange(256), rng.randrange(256)) for _ in range(1024)])
    output = BytesIO()
    image.save(output, format="PNG")
    return output.getvalue()


def _photo(photo_id: str, seed: int, purpose: str, group: str, verdict: str, label: str) -> tuple[dict, dict]:
    rendered = _png(seed)
    pixel_sha, width, height, perceptual = prepare_sop_photos._pixel_sha256(rendered)
    original = rendered
    metadata = {
        "photoId": photo_id, "fileName": f"现场-{seed}.png", "purpose": purpose,
        "captureGroup": group, "width": width, "height": height,
        "imageSha256": hashlib.sha256(original).hexdigest(),
        "renderSha256": hashlib.sha256(rendered).hexdigest(), "pixelSha256": pixel_sha,
        "perceptualHash": perceptual, "verdict": verdict, "reviewed": True,
        "boxes": [{"label": label, "x0": 0.1, "y0": 0.1, "x1": 0.8, "y1": 0.8}],
        "transcription": "", "reviewer": "quality-1", "reviewedAt": "2026-10-15T00:00:00+00:00",
        "source": "human", "training_eligible": purpose == "training",
    }
    return metadata, {"originalBase64": base64.b64encode(original).decode(), "renderedBase64": base64.b64encode(rendered).decode()}


def _bundle(tmp_path: Path) -> tuple[Path, dict]:
    tmp_path.mkdir(parents=True, exist_ok=True)
    photos: list[dict] = []
    payloads: dict[str, dict] = {}
    # Find stable task IDs for both halves of VisionLab's deterministic split.
    train_groups: list[str] = []
    val_groups: list[str] = []
    for index in range(100):
        group = f"task-{index}"
        target = val_groups if int(hashlib.sha256(group.encode()).hexdigest()[:8], 16) % 100 < 50 else train_groups
        if len(target) < 2:
            target.append(group)
        if len(train_groups) >= 2 and len(val_groups) >= 2:
            break
    groups = [(train_groups[0], 1), (train_groups[1], 2), (val_groups[0], 3), (val_groups[1], 4)]
    for index, (group, seed) in enumerate(groups):
        photo_id = f"photo-{seed:024x}"
        metadata, payload = _photo(photo_id, seed, "training", group, "good" if index % 2 == 0 else "defect", "white_label" if index % 2 == 0 else "color_label")
        photos.append(metadata)
        payloads[photo_id] = payload
    for seed in (11, 12):
        photo_id = f"photo-{seed:024x}"
        metadata, payload = _photo(photo_id, seed, "acceptance", f"acceptance-{seed}", "good", "white_label")
        metadata["training_eligible"] = False
        photos.append(metadata)
        payloads[photo_id] = payload
    snapshot = {
        "schema": "factory-vision-preparation-v1", "factory_id": "FA", "project_id": "vtp-source",
        "preview_hash": "a" * 64, "task_family": "liushanmen-tray-label-detection", "photos": photos,
        "standard": {"passCriteria": "完整", "failCriteria": "缺失", "maxMissRate": 1.0,
                      "maxFalseAlarmRate": 2.0, "confirmed": True, "confirmedBy": "quality-1",
                      "confirmedAt": "2026-10-15T00:00:00+00:00"},
    }
    bundle = {"snapshot": snapshot, "snapshotSha256": digest(snapshot), "photos": payloads}
    path = tmp_path / "bundle.json"
    path.write_text(json.dumps(bundle, ensure_ascii=False), encoding="utf-8")
    return path, bundle


def test_prepared_export_builds_real_dataset_and_excludes_independent_photos(tmp_path, monkeypatch):
    bundle_path, bundle = _bundle(tmp_path)
    project = tmp_path / "external-project"
    result = prepare_sop_photos.prepare_sop_photos(bundle_path, project, "exec-1", expected_factory_id="FA")
    queue_manifest = Path(result["queue_manifest"])
    assert result["training_rows"] == 4 and result["acceptance_rows"] == 2
    assert all((project / "originals" / f"photo-{seed:024x}.png").is_file() for seed in (1, 2, 3, 4, 11, 12))
    queue = json.loads(queue_manifest.read_text(encoding="utf-8"))
    assert {row["purpose"] for row in queue["rows"]} == {"training"}
    assert not {f"photo-{seed:024x}" for seed in (11, 12)} & {row["crop_id"] for row in queue["rows"]}

    vision_lab = _vision_lab()
    config = {"runtime_root": str(project), "queue_roots": [str(project / "queue")], "training": {"min_reviewed_images": 2, "validation_percent": 50}}
    dataset = vision_lab.build_dataset(config)
    assert dataset["counts"]["train"] > 0 and dataset["counts"]["val"] > 0
    assert dataset["counts"]["white_boxes"] > 0 and dataset["counts"]["color_boxes"] > 0
    dataset_ids = {row["crop_id"] for row in dataset["rows"]}
    assert not dataset_ids & {f"photo-{seed:024x}" for seed in (11, 12)}

    config_path = project / "config.json"
    config_value = {"version": "vision-lab-v1", "runtime_root": str(project), "source": {"factory_id": "FA"}, "execution": {"mode": "local", "remote_enabled": False}, "queue_roots": [str(project / "queue")]}
    config_path.write_text(json.dumps(config_value), encoding="utf-8")
    input_path = project / "dataset-input.json"
    registration = sop_worker.WorkerRegistration(
        factory_id="FA", project_id="exec-1", adapter_id="liushanmen-visionlab-v1", project_root=project,
        config_path=config_path, config_sha256=hashlib.sha256(config_path.read_bytes()).hexdigest(),
        input_refs={"dataset": "dataset-input.json"}, input_hashes={"dataset": hashlib.sha256(input_path.read_bytes()).hexdigest()},
    )
    monkeypatch.setattr(sop_worker.sys, "platform", "win32")
    sop_worker._validate_dataset_input_snapshot(json.loads(input_path.read_text(encoding="utf-8")), config, vision_lab, registration)

    # A train-stage job gets the real dataset-result; the preparation input is
    # loaded separately only to verify the frozen provenance binding.
    dataset_result_path = project / "dataset-result.json"
    dataset_result_path.write_text(json.dumps(dataset), encoding="utf-8")
    base_model = project / "base.pt"
    base_model.write_bytes(b"base-model")
    config_value["training"] = {"base_model": str(base_model), "base_model_sha256": hashlib.sha256(base_model.read_bytes()).hexdigest()}
    config_path.write_text(json.dumps(config_value), encoding="utf-8")
    registration = sop_worker.WorkerRegistration(
        factory_id="FA", project_id="exec-1", adapter_id="liushanmen-visionlab-v1", project_root=project,
        config_path=config_path, config_sha256=hashlib.sha256(config_path.read_bytes()).hexdigest(),
        input_refs={"dataset": "dataset-input.json", "train": "dataset-result.json"},
        input_hashes={"dataset": hashlib.sha256(input_path.read_bytes()).hexdigest(), "train": hashlib.sha256(dataset_result_path.read_bytes()).hexdigest()},
    )
    captured: dict[str, object] = {}
    class FakeVisionLab:
        @staticmethod
        def assert_local_execution(_config):
            return None
        @staticmethod
        def discover_queue_roots(value):
            return vision_lab.discover_queue_roots(value)
        @staticmethod
        def train_candidate(_config, value):
            captured["dataset"] = value
            return {"artifact": str(project / "candidate.onnx"), "artifact_sha256": "b" * 64}
    monkeypatch.setattr(sop_worker, "_load_vision_lab", lambda: FakeVisionLab)
    monkeypatch.setattr(sop_worker, "_validate_model_result", lambda *_args: {"artifact": "candidate", "artifact_sha256": "b" * 64})
    worker = sop_worker.VisionLabSOPWorker(registration)
    worker.execute("train")
    assert captured["dataset"] == dataset
    assert "preparation_snapshot" not in captured["dataset"]


def test_prepared_export_rejects_mutated_payload_unknown_label_and_external_root(tmp_path):
    bundle_path, bundle = _bundle(tmp_path)
    value = json.loads(bundle_path.read_text(encoding="utf-8"))
    first = value["snapshot"]["photos"][0]["photoId"]
    value["photos"][first]["renderedBase64"] = base64.b64encode(b"changed").decode()
    bundle_path.write_text(json.dumps(value), encoding="utf-8")
    with pytest.raises(prepare_sop_photos.PreparationExportError, match="rendered photo hash"):
        prepare_sop_photos.prepare_sop_photos(bundle_path, tmp_path / "external-project", "exec-1")

    bundle_path, _ = _bundle(tmp_path / "second")
    value = json.loads(bundle_path.read_text(encoding="utf-8"))
    value["snapshot"]["photos"][0]["boxes"][0]["label"] = "object"
    value["snapshotSha256"] = digest(value["snapshot"])
    bundle_path.write_text(json.dumps(value), encoding="utf-8")
    with pytest.raises(prepare_sop_photos.PreparationExportError, match="unknown label"):
        prepare_sop_photos.prepare_sop_photos(bundle_path, tmp_path / "second-project", "exec-1")

    valid_bundle, _ = _bundle(tmp_path / "third")
    with pytest.raises(prepare_sop_photos.PreparationExportError, match="external"):
        prepare_sop_photos.prepare_sop_photos(valid_bundle, Path(__file__).resolve().parents[3], "exec-1")


def test_prepared_export_accepts_directory_bundle_with_relative_payload_files(tmp_path):
    source, bundle = _bundle(tmp_path / "source")
    bundle_dir = tmp_path / "directory-bundle"
    (bundle_dir / "originals").mkdir(parents=True)
    (bundle_dir / "rendered").mkdir()
    directory_bundle = json.loads(source.read_text(encoding="utf-8"))
    for photo_id, payload in directory_bundle["photos"].items():
        original_name = f"originals/{photo_id}.bin"
        rendered_name = f"rendered/{photo_id}.png"
        (bundle_dir / original_name).write_bytes(base64.b64decode(payload["originalBase64"]))
        (bundle_dir / rendered_name).write_bytes(base64.b64decode(payload["renderedBase64"]))
        directory_bundle["photos"][photo_id] = {"originalFile": original_name, "renderedFile": rendered_name}
    (bundle_dir / "bundle.json").write_text(json.dumps(directory_bundle), encoding="utf-8")
    result = prepare_sop_photos.prepare_sop_photos(bundle_dir, tmp_path / "external-project", "exec-1")
    assert result["training_rows"] == 4 and result["acceptance_rows"] == 2


def test_download_preparation_bundle_streams_fixed_worker_transfer_and_rejects_truncation(tmp_path, monkeypatch):
    source, bundle = _bundle(tmp_path / "source")
    snapshot = bundle["snapshot"]
    content = {
        photo_id: {
            "original": base64.b64decode(value["originalBase64"]),
            "rendered": base64.b64decode(value["renderedBase64"]),
        }
        for photo_id, value in bundle["photos"].items()
    }
    state = {"truncate": False, "headers": None, "redirect": False, "redirect_url": "", "second_requests": 0}

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802
            state["headers"] = {key.lower(): value for key, value in self.headers.items()}
            parsed = urlparse(self.path)
            prefix = "/api/vision-training/worker/preparations/vtp-source"
            if parsed.path == prefix + "/snapshot":
                assert parse_qs(parsed.query).get("snapshotSha256") == [bundle["snapshotSha256"]]
                if state["redirect"]:
                    self.send_response(302)
                    self.send_header("Location", state["redirect_url"])
                    self.end_headers()
                    return
                body = json.dumps({"success": True, "data": {
                    "snapshot": snapshot, "snapshotSha256": bundle["snapshotSha256"],
                    "photoIds": [photo["photoId"] for photo in snapshot["photos"]],
                    "contentTransfer": "trusted-local-directory-export",
                }}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            parts = parsed.path.split("/")
            photo_id, kind = parts[-2], parts[-1]
            expected = content[photo_id][kind]
            body = expected[:-1] if state["truncate"] else expected
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("X-Content-SHA256", hashlib.sha256(expected).hexdigest())
            self.send_header("Content-Length", str(len(expected)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_args):
            return

    class RedirectTargetHandler(BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802
            state["second_requests"] += 1
            self.send_response(200)
            self.end_headers()

        def log_message(self, *_args):
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = __import__("threading").Thread(target=server.serve_forever, daemon=True)
    thread.start()
    redirect_server = ThreadingHTTPServer(("127.0.0.1", 0), RedirectTargetHandler)
    redirect_thread = __import__("threading").Thread(target=redirect_server.serve_forever, daemon=True)
    redirect_thread.start()
    worker_root = tmp_path / "worker"
    worker_root.mkdir()
    config = worker_root / "config.json"
    config.write_text("{}", encoding="utf-8")
    registration = sop_worker.WorkerRegistration(
        factory_id="FA", project_id="exec-1", adapter_id="liushanmen-visionlab-v1", project_root=worker_root,
        config_path=config, config_sha256=hashlib.sha256(config.read_bytes()).hexdigest(), input_refs={},
        server_url=f"http://127.0.0.1:{server.server_port}", worker_id="worker-1",
    )
    monkeypatch.setattr(sop_worker.sys, "platform", "win32")
    monkeypatch.setenv("VISION_SOP_WORKER_TOKEN", "worker-secret")
    monkeypatch.setenv("VISION_SOP_WORKER_INTERNAL_SECRET", "internal-secret")
    try:
        downloaded = prepare_sop_photos.download_preparation_bundle(
            registration, "vtp-source", bundle["snapshotSha256"], tmp_path / "downloaded"
        )
        assert downloaded.name == "bundle.json"
        assert {key: state["headers"][key] for key in ("x-worker-id", "x-worker-token", "x-internal-secret")} == {
            "x-worker-id": "worker-1", "x-worker-token": "worker-secret", "x-internal-secret": "internal-secret"
        }
        prepared = prepare_sop_photos.prepare_sop_photos(downloaded.parent, tmp_path / "prepared", "exec-1")
        assert prepared["training_rows"] == 4

        state["truncate"] = True
        with pytest.raises(prepare_sop_photos.PreparationExportError, match="truncated"):
            prepare_sop_photos.download_preparation_bundle(
                registration, "vtp-source", bundle["snapshotSha256"], tmp_path / "truncated"
            )
        assert not (tmp_path / "truncated" / "bundle.json").exists()

        state["redirect_url"] = f"http://127.0.0.1:{redirect_server.server_port}/outside"
        state["redirect"] = True
        with pytest.raises(prepare_sop_photos.PreparationExportError, match="request failed"):
            prepare_sop_photos.download_preparation_bundle(
                registration, "vtp-source", bundle["snapshotSha256"], tmp_path / "redirected"
            )
        assert state["second_requests"] == 0
        assert not (tmp_path / "redirected" / "bundle.json").exists()
    finally:
        server.shutdown()
        thread.join(timeout=2)
        redirect_server.shutdown()
        redirect_thread.join(timeout=2)


def test_uncertain_acceptance_is_retained_as_audit_but_excluded_from_effective_manifest(tmp_path):
    bundle_path, bundle = _bundle(tmp_path / "uncertain-source")
    uncertain = bundle["snapshot"]["photos"][-1]
    uncertain["verdict"] = "uncertain"
    uncertain["boxes"] = []
    uncertain["training_eligible"] = False
    bundle["snapshotSha256"] = digest(bundle["snapshot"])
    bundle_path.write_text(json.dumps(bundle, ensure_ascii=False), encoding="utf-8")

    project = tmp_path / "uncertain-project"
    result = prepare_sop_photos.prepare_sop_photos(bundle_path, project, "exec-1", expected_factory_id="FA")
    manifest = json.loads((project / "independent-acceptance" / "manifest.json").read_text(encoding="utf-8"))
    effective_ids = {row["crop_id"] for row in manifest["rows"]}
    assert result["acceptance_rows"] == 1
    assert effective_ids == {"photo-00000000000000000000000b"}
    assert uncertain["photoId"] not in effective_ids
    saved_snapshot = json.loads((project / "preparation-snapshot.json").read_text(encoding="utf-8"))
    assert saved_snapshot["photos"][-1]["verdict"] == "uncertain"
    assert (project / "originals" / f"{uncertain['photoId']}.png").is_file()
    assert result["independent_acceptance_version"] == "accept-" + digest(
        [photo for photo in bundle["snapshot"]["photos"] if photo["purpose"] == "acceptance"]
    )


def test_download_transport_requires_https_for_non_loopback_allowlisted_hosts(tmp_path, monkeypatch):
    worker_root = tmp_path / "transport-worker"
    worker_root.mkdir()
    config = worker_root / "config.json"
    config.write_text("{}", encoding="utf-8")

    def registration(server_url, hosts):
        return sop_worker.WorkerRegistration(
            factory_id="FA", project_id="exec-1", adapter_id="liushanmen-visionlab-v1",
            project_root=worker_root, config_path=config,
            config_sha256=hashlib.sha256(config.read_bytes()).hexdigest(), input_refs={},
            server_url=server_url, worker_id="worker-transport", allowed_server_hosts=frozenset(hosts),
        )

    monkeypatch.setattr(sop_worker.sys, "platform", "win32")
    loopback_http = registration("http://127.0.0.1:8083", {"127.0.0.1"})
    prepare_sop_photos._validate_download_transport(loopback_http)

    remote_http = registration("http://worker.example.test:8083", {"worker.example.test"})
    with pytest.raises(prepare_sop_photos.PreparationExportError, match="requires HTTPS"):
        prepare_sop_photos.download_preparation_bundle(
            remote_http, "vtp-source", "a" * 64, tmp_path / "remote-http"
        )

    remote_https = registration("https://worker.example.test:8443", {"worker.example.test"})
    prepare_sop_photos._validate_download_transport(remote_https)
