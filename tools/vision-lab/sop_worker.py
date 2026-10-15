"""Constrained Windows worker for the factory-scoped vision SOP.

This module is deliberately a named-function bridge.  It does not expose the
VisionLab CLI, arbitrary subprocesses, URLs, or client filesystem paths.  A
local registration file chooses one factory/project root and fixed input
references; the Linux API only supplies the immutable job identity.
"""

from __future__ import annotations

import hashlib
import json
import os
import sys
import argparse
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


VERSION = "vision-sop-worker-v1"
import re

SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")


class WorkerConfigurationError(ValueError):
    pass


class WorkerBlockedError(RuntimeError):
    """The named worker cannot safely execute this stage."""


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def _resolve_under_root(value: str, root: Path) -> Path:
    expanded = os.path.expandvars(value)
    candidate = Path(expanded)
    if not candidate.is_absolute():
        candidate = root / candidate
    return candidate.resolve()


def _raw_under_root(value: str, root: Path) -> Path:
    expanded = os.path.expandvars(value)
    candidate = Path(expanded)
    return candidate if candidate.is_absolute() else root / candidate


@dataclass(frozen=True)
class WorkerRegistration:
    factory_id: str
    project_id: str
    adapter_id: str
    project_root: Path
    config_path: Path
    config_sha256: str
    input_refs: dict[str, str]
    allowed_source_hosts: frozenset[str] = frozenset()
    input_hashes: dict[str, str] = field(default_factory=dict)
    server_url: str = "http://127.0.0.1:8083"
    worker_id: str = ""
    worker_token_env: str = "VISION_SOP_WORKER_TOKEN"
    internal_secret_env: str = "VISION_SOP_WORKER_INTERNAL_SECRET"
    allowed_server_hosts: frozenset[str] = frozenset({"127.0.0.1", "localhost", "::1"})

    @classmethod
    def from_json(cls, path: str | Path) -> "WorkerRegistration":
        registration_path = Path(path).resolve()
        value = json.loads(registration_path.read_text(encoding="utf-8"))
        if not isinstance(value, dict):
            raise WorkerConfigurationError("worker registration must be an object")
        try:
            result = cls(
                factory_id=str(value["factory_id"]),
                project_id=str(value["project_id"]),
                adapter_id=str(value["adapter_id"]),
                project_root=Path(str(value["project_root"])).resolve(),
                config_path=Path(str(value["config_path"])).resolve(),
                config_sha256=str(value["config_sha256"]),
                input_refs={str(k): str(v) for k, v in dict(value["input_refs"]).items()},
                allowed_source_hosts=frozenset(str(v).lower() for v in value.get("allowed_source_hosts", ())),
                input_hashes={str(k): str(v) for k, v in dict(value.get("input_hashes", {})).items()},
                server_url=str(value.get("server_url", "http://127.0.0.1:8083")),
                worker_id=str(value.get("worker_id", "")),
                worker_token_env=str(value.get("worker_token_env", "VISION_SOP_WORKER_TOKEN")),
                internal_secret_env=str(value.get("internal_secret_env", "VISION_SOP_WORKER_INTERNAL_SECRET")),
                allowed_server_hosts=frozenset(str(v).lower() for v in value.get("allowed_server_hosts", ("127.0.0.1", "localhost", "::1"))),
            )
        except (KeyError, TypeError, ValueError) as exc:
            raise WorkerConfigurationError("incomplete worker registration") from exc
        result.validate(require_transport=True)
        return result

    def validate(self, *, require_transport: bool = False) -> None:
        if sys.platform != "win32":
            raise WorkerConfigurationError("VisionLab worker is local Windows only")
        if not self.factory_id or not self.project_id or not self.adapter_id:
            raise WorkerConfigurationError("worker registration identity is incomplete")
        if not SHA256_RE.fullmatch(self.config_sha256):
            raise WorkerConfigurationError("config_sha256 must be a 64-character hex digest")
        for stage, digest in (self.input_hashes or {}).items():
            if stage not in {"collect", "dataset", "train", "evaluate"} or not SHA256_RE.fullmatch(digest):
                raise WorkerConfigurationError("input_hashes must bind named stages to sha256 digests")
        if self.project_root.is_symlink() or not self.project_root.is_absolute():
            raise WorkerConfigurationError("project root must be a real absolute directory")
        if self.config_path.is_symlink() or not _within(self.config_path, self.project_root):
            raise WorkerConfigurationError("config path must be inside the registered project root")
        for stage, reference in self.input_refs.items():
            if stage not in {"collect", "dataset", "train", "evaluate"}:
                raise WorkerConfigurationError(f"unsupported fixed input stage: {stage}")
            raw_target = _raw_under_root(reference, self.project_root)
            target = raw_target.resolve()
            if raw_target.is_symlink() or not _within(target, self.project_root):
                raise WorkerConfigurationError(f"input reference escapes the project root: {reference}")
        if any(not host or "/" in host or ":" in host for host in self.allowed_source_hosts):
            raise WorkerConfigurationError("source host allowlist contains an invalid host")
        if require_transport:
            parsed_server = urlparse(self.server_url)
            if parsed_server.scheme not in {"http", "https"} or not parsed_server.hostname:
                raise WorkerConfigurationError("worker server_url must be an HTTP(S) URL")
            if parsed_server.hostname.lower() not in self.allowed_server_hosts:
                raise WorkerConfigurationError("worker server_url is outside the explicit trusted host allowlist")
            if parsed_server.username or parsed_server.password or "?" in self.server_url or "#" in self.server_url:
                raise WorkerConfigurationError("worker server_url cannot contain credentials, query, or fragment")
            if parsed_server.path not in {"", "/"}:
                raise WorkerConfigurationError("worker server_url cannot include an API path")
            if not self.worker_id.strip() or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,100}", self.worker_id):
                raise WorkerConfigurationError("worker_id is required and must be a named binding")
            if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,100}", self.worker_token_env):
                raise WorkerConfigurationError("worker_token_env must name an environment variable")
            if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,100}", self.internal_secret_env):
                raise WorkerConfigurationError("internal_secret_env must name an environment variable")


def _load_config(registration: WorkerRegistration) -> dict[str, Any]:
    registration.validate()
    if not registration.config_path.is_file():
        raise WorkerBlockedError("registered local VisionLab config is missing")
    if _sha256(registration.config_path).lower() != registration.config_sha256.lower():
        raise WorkerBlockedError("registered VisionLab config hash changed")
    value = json.loads(registration.config_path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise WorkerConfigurationError("VisionLab config must be an object")
    if value.get("version") != "vision-lab-v1":
        raise WorkerConfigurationError("unsupported VisionLab config version")
    source = value.get("source") or {}
    if source.get("factory_id") != registration.factory_id:
        raise WorkerConfigurationError("VisionLab config factory does not match worker binding")
    execution = value.get("execution") or {}
    if execution.get("mode", "local") != "local" or execution.get("remote_enabled", False) is not False:
        raise WorkerConfigurationError("remote VisionLab execution is disabled")
    deployment = value.get("deployment") or {}
    if deployment.get("auto_deploy") is True:
        raise WorkerConfigurationError("SOP worker cannot execute an auto-deploy config")
    _validate_config_paths(value, registration)
    _normalize_config_paths(value, registration.project_root)
    value["runtime_root"] = str(registration.project_root)
    return value


def _validate_config_paths(config: dict[str, Any], registration: WorkerRegistration) -> None:
    """Accept only the fixed per-project asset paths in a trusted config."""

    project_root = registration.project_root
    path_values: list[str] = []
    path_values.extend(str(item) for item in config.get("queue_roots", ()))
    path_values.extend(str(item) for item in config.get("queue_globs", ()))
    for quarantine in config.get("quarantines", ()):
        if isinstance(quarantine, str):
            path_values.append(quarantine)
        elif isinstance(quarantine, dict):
            for key in ("root", "manifest", "path"):
                if quarantine.get(key):
                    path_values.append(str(quarantine[key]))
    path_values.extend(str(item) for item in (config.get("evaluation") or {}).get("manifests", ()))
    training_base = (config.get("training") or {}).get("base_model")
    if training_base:
        path_values.append(str(training_base))
    evaluation = config.get("evaluation") or {}
    for key in ("tray_model", "production_label_model", "truth_adjudication"):
        if evaluation.get(key):
            path_values.append(str(evaluation[key]))
    tray = config.get("tray_active_learning") or {}
    for key in ("queue_root", "protected_holdout", "base_model_pt", "production_tray_onnx", "production_label_onnx"):
        if tray.get(key):
            path_values.append(str(tray[key]))
    for raw in path_values:
        if "*" in raw or "?" in raw:
            raise WorkerConfigurationError("wildcard filesystem paths are not accepted by the SOP worker")
        raw_candidate = _raw_under_root(raw, project_root)
        candidate = raw_candidate.resolve()
        if raw_candidate.is_symlink() or not _within(candidate, project_root):
            raise WorkerConfigurationError(f"registered config path escapes the project root: {raw}")
    repo_root = evaluation.get("repo_root")
    expected_repo = Path(__file__).resolve().parents[2]
    if repo_root and _resolve_under_root(str(repo_root), project_root) != expected_repo:
        raise WorkerConfigurationError("evaluation repo_root must be the checked-in VisionLab repository")


def _normalize_config_paths(config: dict[str, Any], root: Path) -> None:
    def normalize_list(container: dict[str, Any], key: str) -> None:
        values = container.get(key)
        if isinstance(values, list):
            container[key] = [str(_resolve_under_root(str(item), root)) for item in values]

    normalize_list(config, "queue_roots")
    evaluation = config.get("evaluation") or {}
    normalize_list(evaluation, "manifests")
    for key in ("tray_model", "production_label_model", "truth_adjudication", "repo_root"):
        if evaluation.get(key):
            evaluation[key] = str(_resolve_under_root(str(evaluation[key]), root))
    training = config.get("training") or {}
    for key in ("base_model",):
        if training.get(key):
            training[key] = str(_resolve_under_root(str(training[key]), root))
    tray = config.get("tray_active_learning") or {}
    for key in ("queue_root", "protected_holdout", "base_model_pt", "production_tray_onnx", "production_label_onnx"):
        if tray.get(key):
            tray[key] = str(_resolve_under_root(str(tray[key]), root))


def _load_fixed_input(registration: WorkerRegistration, stage: str) -> Any:
    reference = registration.input_refs.get(stage)
    if not reference:
        raise WorkerBlockedError(f"no registered local input manifest for {stage}")
    path = _safe_local_path(reference, registration.project_root, f"registered local input for {stage}")
    expected_hash = (registration.input_hashes or {}).get(stage)
    if not expected_hash:
        raise WorkerBlockedError(f"registered local input hash is missing for {stage}")
    if _sha256(path).lower() != expected_hash.lower():
        raise WorkerBlockedError(f"registered local input hash changed for {stage}")
    value = json.loads(path.read_text(encoding="utf-8"))
    return value


def _load_vision_lab() -> Any:
    tools_root = Path(__file__).resolve().parent
    if str(tools_root) not in sys.path:
        sys.path.insert(0, str(tools_root))
    import vision_lab  # type: ignore

    return vision_lab


def _validate_dataset_input_snapshot(
    snapshot: Any,
    config: dict[str, Any],
    vision_lab: Any,
    registration: WorkerRegistration,
) -> None:
    """Bind dataset discovery to the exact queue manifests claimed by the job."""

    if not isinstance(snapshot, dict) or snapshot.get("factory_id") not in {None, registration.factory_id}:
        raise WorkerBlockedError("registered dataset input snapshot is malformed or crosses factory scope")
    if snapshot.get("project_id") not in {None, registration.project_id}:
        raise WorkerBlockedError("registered dataset input snapshot crosses project scope")
    registered = snapshot.get("queue_manifests")
    if not isinstance(registered, list) or not registered:
        raise WorkerBlockedError("dataset input must be an exact queue manifest snapshot")
    expected: dict[str, str] = {}
    for item in registered:
        if not isinstance(item, dict) or not isinstance(item.get("path"), str) or not SHA256_RE.fullmatch(str(item.get("sha256") or "")):
            raise WorkerBlockedError("dataset input queue manifest snapshot is malformed")
        path = _safe_local_path(item["path"], registration.project_root, "registered queue manifest")
        expected[str(path)] = str(item["sha256"]).lower()
    try:
        discovered_roots = vision_lab.discover_queue_roots(config)
    except Exception as exc:
        raise WorkerBlockedError("dataset queue discovery failed") from exc
    actual: dict[str, str] = {}
    for discovered in discovered_roots:
        queue_root = _safe_local_dir(str(discovered), registration.project_root, "discovered queue root")
        manifest = _safe_local_path(str(queue_root / "manifest.json"), registration.project_root, "discovered queue manifest")
        actual[str(manifest)] = _sha256(manifest).lower()
    if actual != expected:
        raise WorkerBlockedError("registered dataset input does not match discovered queue manifests")


def _validate_collect_rows(rows: Any, registration: WorkerRegistration) -> list[dict[str, Any]]:
    if not isinstance(rows, list) or not rows:
        raise WorkerBlockedError("collect requires a non-empty registered local manifest")
    validated: list[dict[str, Any]] = []
    for row in rows:
        if not isinstance(row, dict) or row.get("factory_id") != registration.factory_id:
            raise WorkerConfigurationError("collect manifest row crosses factory scope")
        expected_sha = str(row.get("sha256") or row.get("file_sha256") or "")
        if not SHA256_RE.fullmatch(expected_sha):
            raise WorkerConfigurationError("collect manifest row must contain a sha256")
        url = str(row.get("file_url") or "")
        parsed = urlparse(url)
        if parsed.scheme not in {"http", "https"} or parsed.hostname not in registration.allowed_source_hosts:
            raise WorkerConfigurationError("collect URL is outside the fixed source host allowlist")
        validated.append(dict(row))
    return validated


def _validate_collected_hashes(state: Any, rows: list[dict[str, Any]]) -> None:
    """Check VisionLab's local receipt against every immutable source digest."""

    for row in rows:
        expected = str(row.get("sha256") or row.get("file_sha256") or "").lower()
        photo_id = str(row.get("photo_id") or "")
        saved = state.db.execute("SELECT sha256 FROM photos WHERE photo_id=?", (photo_id,)).fetchone()
        actual = str(saved[0] if saved else "").lower()
        if not SHA256_RE.fullmatch(actual) or actual != expected:
            raise WorkerBlockedError("collect output hash does not match the registered source manifest")


def _safe_local_path(value: Any, root: Path, label: str, *, must_exist: bool = True) -> Path:
    if not isinstance(value, str) or not value or "*" in value or "?" in value:
        raise WorkerBlockedError(f"{label} is not a fixed local path")
    raw_path = _raw_under_root(value, root)
    try:
        relative = raw_path.relative_to(root)
    except ValueError:
        raise WorkerBlockedError(f"{label} escapes or is missing from the project root")
    cursor = root
    for part in relative.parts:
        cursor = cursor / part
        if cursor.is_symlink():
            raise WorkerBlockedError(f"{label} escapes or is missing from the project root")
    path = raw_path.resolve()
    if not _within(path, root) or (must_exist and not path.is_file()):
        raise WorkerBlockedError(f"{label} escapes or is missing from the project root")
    return path


def _yolo_line(box: Any) -> str:
    if not isinstance(box, dict):
        raise WorkerBlockedError("human annotation box is malformed")
    try:
        cls = int(box["c"])
        x0, y0, x1, y1 = map(float, box["b"])
    except (KeyError, TypeError, ValueError) as exc:
        raise WorkerBlockedError("human annotation box is malformed") from exc
    if cls not in (0, 1) or not (0 <= x0 < x1 <= 1 and 0 <= y0 < y1 <= 1):
        raise WorkerBlockedError("human annotation box is outside the fixed label schema")
    return f"{cls} {(x0+x1)/2:.6f} {(y0+y1)/2:.6f} {x1-x0:.6f} {y1-y0:.6f}"


def _canonical_yolo_text(value: str) -> tuple[str, ...]:
    result: list[str] = []
    for line in value.splitlines():
        fields = line.split()
        if not fields:
            continue
        if len(fields) != 5 or fields[0] not in {"0", "1"}:
            raise WorkerBlockedError("dataset label content is malformed")
        try:
            values = [float(item) for item in fields[1:]]
        except ValueError as exc:
            raise WorkerBlockedError("dataset label content is malformed") from exc
        result.append(" ".join([fields[0], *(f"{item:.6f}" for item in values)]))
    return tuple(result)


def _safe_local_dir(value: Any, root: Path, label: str) -> Path:
    path = _safe_local_path(value, root, label, must_exist=False)
    if not path.is_dir():
        raise WorkerBlockedError(f"{label} is not a local directory")
    return path


def _read_data_yaml(path: Path) -> dict[str, str]:
    import yaml

    class UniqueKeyLoader(yaml.SafeLoader):
        pass

    def mapping(loader: Any, node: Any, deep: bool = False) -> dict[Any, Any]:
        loader.flatten_mapping(node)
        result: dict[Any, Any] = {}
        for key_node, value_node in node.value:
            key = loader.construct_object(key_node, deep=deep)
            if key in result:
                raise WorkerBlockedError("dataset data_yaml contains duplicate mapping keys")
            result[key] = loader.construct_object(value_node, deep=deep)
        return result

    UniqueKeyLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)
    try:
        payload = yaml.load(path.read_text(encoding="utf-8"), Loader=UniqueKeyLoader)
    except (yaml.YAMLError, TypeError, ValueError) as error:
        raise WorkerBlockedError("dataset data_yaml is not a valid safe YAML mapping") from error
    if not isinstance(payload, dict) or any(not isinstance(payload.get(key), str) or not payload[key].strip()
                                            for key in ("path", "train", "val")):
        raise WorkerBlockedError("dataset data_yaml must bind fixed path, train, val, and label names")
    names = payload.get("names")
    expected = {0: "white_label", 1: "color_label"}
    if names != expected and names != ["white_label", "color_label"]:
        raise WorkerBlockedError("dataset data_yaml label names do not match the registered adapter")
    return {key: payload[key] for key in ("path", "train", "val")}


def _expected_hash(value: Any, *, label: str) -> str:
    if not isinstance(value, str) or not SHA256_RE.fullmatch(value):
        raise WorkerBlockedError(f"{label} is missing a registered sha256")
    return value.lower()


def _validate_file_hash(path: Path, expected: Any, label: str) -> str:
    digest = _expected_hash(expected, label=label)
    actual = _sha256(path).lower()
    if actual != digest:
        raise WorkerBlockedError(f"{label} hash changed")
    return actual


def _evaluation_manifest_hashes(evaluation: dict[str, Any], manifests: list[Any]) -> list[str]:
    configured = evaluation.get("manifest_sha256", evaluation.get("manifest_hashes"))
    if isinstance(configured, dict):
        result = []
        for item in manifests:
            result.append(str(configured.get(str(item)) or configured.get(Path(str(item)).name) or ""))
        return result
    if isinstance(configured, list):
        return [str(item) for item in configured]
    return []


def _walk_evaluation_evidence(value: Any, registration: WorkerRegistration) -> tuple[set[str], set[str]]:
    """Return content hashes and provenance IDs from a locked evaluation manifest."""

    hashes: set[str] = set()
    identifiers: set[str] = set()
    path_keys = {"image", "path", "image_path", "image_file", "file_path", "local_path"}
    id_keys = {"photo_id", "crop_id", "source_photo_id", "source_task_id", "task_id"}

    def visit(node: Any) -> None:
        if isinstance(node, dict):
            for key, child in node.items():
                normalized = str(key).lower()
                if normalized in id_keys and isinstance(child, (str, int)):
                    identifiers.add(str(child))
                if (
                    "sha256" in normalized
                    and isinstance(child, str)
                    and SHA256_RE.fullmatch(child)
                ):
                    hashes.add(child.lower())
                if normalized in path_keys and isinstance(child, str):
                    if not Path(child).is_absolute():
                        raise WorkerBlockedError("evaluation image must use an absolute project-local path")
                    path = _safe_local_path(child, registration.project_root, "evaluation image")
                    expected = node.get("image_sha256", node.get("sha256", node.get("source_sha256", node.get("file_sha256"))))
                    hashes.add(_validate_file_hash(path, expected, "evaluation image"))
                visit(child)
        elif isinstance(node, list):
            for child in node:
                visit(child)

    visit(value)
    return hashes, identifiers


def _validate_evaluation_inputs(config: dict[str, Any], registration: WorkerRegistration, *, stage: str) -> tuple[set[str], set[str]]:
    evaluation = config.get("evaluation") or {}
    manifests = evaluation.get("manifests") or []
    if not isinstance(manifests, list):
        raise WorkerBlockedError("evaluation manifests must be a fixed list")
    expected_manifests = _evaluation_manifest_hashes(evaluation, manifests)
    if manifests and len(expected_manifests) != len(manifests):
        raise WorkerBlockedError("evaluation manifests are missing registered sha256 values")
    holdout_hashes: set[str] = set()
    holdout_ids: set[str] = set()
    for index, raw_path in enumerate(manifests):
        path = _safe_local_path(str(raw_path), registration.project_root, "evaluation manifest")
        _validate_file_hash(path, expected_manifests[index], "evaluation manifest")
        payload = json.loads(path.read_text(encoding="utf-8"))
        hashes, identifiers = _walk_evaluation_evidence(payload, registration)
        holdout_hashes.update(hashes)
        holdout_ids.update(identifiers)

    if stage == "train":
        training = config.get("training") or {}
        base_path = _safe_local_path(training.get("base_model"), registration.project_root, "base model")
        _validate_file_hash(base_path, training.get("base_model_sha256"), "base model")
    elif stage == "evaluate":
        for key, hash_key, label in (
            ("tray_model", "tray_model_sha256", "evaluation tray model"),
            ("production_label_model", "production_label_model_sha256", "evaluation production label model"),
            ("truth_adjudication", "truth_adjudication_sha256", "evaluation truth adjudication"),
        ):
            raw_path = evaluation.get(key)
            if raw_path:
                path = _safe_local_path(raw_path, registration.project_root, label)
                _validate_file_hash(path, evaluation.get(hash_key), label)
    return holdout_hashes, holdout_ids


def _validate_dataset_result(config: dict[str, Any], result: dict[str, Any], registration: WorkerRegistration) -> dict[str, Any]:
    holdout_hashes, holdout_ids = _validate_evaluation_inputs(config, registration, stage="dataset")
    data_yaml = _safe_local_path(result.get("data_yaml"), registration.project_root, "dataset data_yaml")
    yaml_values = _read_data_yaml(data_yaml)
    yaml_root_value = Path(yaml_values["path"])
    if not yaml_root_value.is_absolute():
        yaml_root_value = data_yaml.parent / yaml_root_value
    dataset_root = _safe_local_dir(str(yaml_root_value), registration.project_root, "dataset YAML root")
    split_roots = {
        split: _safe_local_dir(str(dataset_root / yaml_values[split]), registration.project_root, f"dataset {split} path")
        for split in ("train", "val")
    }
    if any(not _within(path, dataset_root) for path in split_roots.values()):
        raise WorkerBlockedError("dataset YAML split escapes its dataset root")
    manifest_path = data_yaml.parent / "manifest.json"
    manifest_path = _safe_local_path(str(manifest_path), registration.project_root, "dataset manifest")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    rows = manifest.get("rows")
    if not isinstance(rows, list) or not rows or manifest.get("protected_holdout_included") is not False or manifest.get("train_only") is not True:
        raise WorkerBlockedError("dataset manifest lacks train-only and protected-holdout exclusion evidence")
    queue_roots = config.get("queue_roots") or []
    evidence_by_crop: dict[str, tuple[Path, dict[str, Any]]] = {}
    discovered_manifest_hashes: set[str] = set()
    for raw_root in queue_roots:
        queue_root = _safe_local_path(raw_root, registration.project_root, "queue root", must_exist=False)
        queue_manifest = _safe_local_path(str(queue_root / "manifest.json"), registration.project_root, "queue manifest")
        queue_value = json.loads(queue_manifest.read_text(encoding="utf-8"))
        queue_manifest_hash = _sha256(queue_manifest).lower()
        discovered_manifest_hashes.add(queue_manifest_hash)
        if queue_manifest_hash not in {str(item).lower() for item in manifest.get("source_manifest_sha256") or []}:
            raise WorkerBlockedError("dataset manifest is not bound to the registered queue manifest")
        for row in queue_value.get("rows") or []:
            crop_id = str(row.get("crop_id") or "")
            if crop_id:
                annotation = _safe_local_path(
                    str(queue_root / "annotations-human" / f"{crop_id}.json"),
                    registration.project_root,
                    "human annotation",
                )
                evidence_by_crop[crop_id] = (annotation, json.loads(annotation.read_text(encoding="utf-8")))
    if not evidence_by_crop:
        raise WorkerBlockedError("dataset has no local human-review evidence")
    declared_manifest_hashes = {str(item).lower() for item in manifest.get("source_manifest_sha256") or []}
    if declared_manifest_hashes != discovered_manifest_hashes:
        raise WorkerBlockedError("dataset manifest queue inputs do not match the configured discovery set")
    for row in rows:
        image = _safe_local_path(row.get("image"), registration.project_root, "dataset image")
        label = _safe_local_path(row.get("label"), registration.project_root, "dataset label")
        source_sha = str(row.get("source_sha256") or "")
        if not SHA256_RE.fullmatch(source_sha) or _sha256(image).lower() != source_sha.lower():
            raise WorkerBlockedError("dataset image hash does not match its manifest")
        row_ids = {
            str(row.get(key) or "")
            for key in ("crop_id", "task_id", "source_photo_id", "source_task_id")
            if row.get(key)
        }
        if source_sha.lower() in holdout_hashes or row_ids.intersection(holdout_ids):
            raise WorkerBlockedError("dataset overlaps the protected evaluation holdout")
        split = str(row.get("split") or "")
        if split not in split_roots or not _within(image, split_roots[split]):
            raise WorkerBlockedError("dataset image is outside the bound YOLO split")
        if not _within(label, dataset_root):
            raise WorkerBlockedError("dataset label escapes the bound dataset root")
        label_root = dataset_root / "labels" / split
        if not _within(label, label_root):
            raise WorkerBlockedError("dataset label is outside the bound YOLO label split")
        evidence = evidence_by_crop.get(str(row.get("crop_id") or ""))
        if evidence is None:
            raise WorkerBlockedError("dataset contains a sample without human evidence")
        annotation_path, verdict = evidence
        if verdict.get("reviewed") is not True or verdict.get("unjudgeable") is True:
            raise WorkerBlockedError("dataset contains a sample without reviewed human evidence")
        annotation_sha = str(row.get("annotation_sha256") or "")
        if not SHA256_RE.fullmatch(annotation_sha) or annotation_sha.lower() != _sha256(annotation_path).lower():
            raise WorkerBlockedError("dataset annotation hash does not match human evidence")
        boxes = verdict.get("boxes") or []
        if not isinstance(boxes, list):
            raise WorkerBlockedError("human annotation boxes are malformed")
        expected_label = tuple(_yolo_line(box) for box in boxes)
        if _canonical_yolo_text(label.read_text(encoding="utf-8")) != expected_label:
            raise WorkerBlockedError("dataset label content does not match human evidence")
    evaluation_manifests = (config.get("evaluation") or {}).get("manifests") or []
    evaluation_paths = {str(_resolve_under_root(str(item), registration.project_root)) for item in evaluation_manifests}
    if str(manifest_path) in evaluation_paths:
        raise WorkerBlockedError("training dataset cannot reuse an evaluation manifest")
    return {
        "dataset_sha256": _sha256(manifest_path),
        "reviewed_only": True,
        "unreviewed_count": 0,
        "protected_holdout_excluded": True,
        "manifest": str(manifest_path),
    }


def _validate_model_result(config: dict[str, Any], model: dict[str, Any], registration: WorkerRegistration) -> dict[str, Any]:
    artifact = _safe_local_path(model.get("artifact"), registration.project_root, "model artifact")
    artifact_sha = str(model.get("artifact_sha256") or "")
    if not SHA256_RE.fullmatch(artifact_sha) or _sha256(artifact).lower() != artifact_sha.lower():
        raise WorkerBlockedError("model artifact hash does not match the local model")
    if model.get("best_pt"):
        _safe_local_path(model["best_pt"], registration.project_root, "model checkpoint")
    training = config.get("training") or {}
    base_model = _safe_local_path(training.get("base_model"), registration.project_root, "base model")
    configured_base_sha = str(training.get("base_model_sha256") or "")
    if configured_base_sha and (_sha256(base_model).lower() != configured_base_sha.lower()):
        raise WorkerBlockedError("base model hash does not match the registered config")
    return {"artifact": str(artifact), "artifact_sha256": artifact_sha}


def _validate_evaluation_result(
    raw: dict[str, Any], model: dict[str, Any], registration: WorkerRegistration, config: dict[str, Any]
) -> None:
    metrics = raw.get("metrics")
    if not isinstance(metrics, dict) or metrics.get("artifact_sha256") != model.get("artifact_sha256"):
        raise WorkerBlockedError("evaluation metrics are not bound to the candidate model hash")
    if not metrics.get("production_pipeline_replay"):
        raise WorkerBlockedError("evaluation lacks production-pipeline replay evidence")
    truth = metrics.get("truth_adjudication")
    if isinstance(truth, dict) and truth.get("path"):
        truth_path = _safe_local_path(truth["path"], registration.project_root, "truth adjudication")
        expected = (config.get("evaluation") or {}).get("truth_adjudication_sha256")
        if not expected:
            raise WorkerBlockedError("truth adjudication is missing a registered sha256")
        _validate_file_hash(truth_path, expected, "evaluation truth adjudication")
        if truth.get("sha256") != _sha256(truth_path):
            raise WorkerBlockedError("truth adjudication hash does not match the local evidence")


def _public_receipt_value(value: Any, *, depth: int = 0) -> Any:
    """Keep local paths and command details out of the server/browser receipt."""

    if depth > 3:
        return "[omitted]"
    if isinstance(value, dict):
        return {
            str(key): _public_receipt_value(item, depth=depth + 1)
            for key, item in value.items()
            if not any(token in str(key).lower() for token in (
                "path", "command", "manifest", "receipt", "validation_dir",
                "data_yaml", "local_path", "artifact", "best_pt", "base_model",
            ))
        }
    if isinstance(value, list):
        return [_public_receipt_value(item, depth=depth + 1) for item in value[:100]]
    if isinstance(value, str) and re.search(r"(?:^[A-Za-z]:[\\/]|\\\\|(?:^|[\\/])(?:var|tmp|home|Users|ProgramData)(?:[\\/]|$))", value):
        return "[local-path-omitted]"
    return value


class VisionLabSOPWorker:
    """Execute only the five named existing functions for one binding."""

    def __init__(self, registration: WorkerRegistration) -> None:
        self.registration = registration
        registration.validate()

    def execute(self, stage: str) -> dict[str, Any]:
        if self.registration.adapter_id != "liushanmen-visionlab-v1":
            raise WorkerBlockedError("no real generic or OCR runner is registered")
        if stage not in {"collect", "dataset", "train", "evaluate"}:
            raise WorkerBlockedError("independent acceptance and enablement require separate authorized stages")
        config = _load_config(self.registration)
        vision_lab = _load_vision_lab()
        # assert_local_execution is called immediately before any existing
        # stage function; it rejects remote/Mac/MPS policy drift.
        vision_lab.assert_local_execution(config)
        if stage == "collect":
            state = vision_lab.State(self.registration.project_root)
            try:
                fixed_rows = _validate_collect_rows(_load_fixed_input(self.registration, stage), self.registration)
                result = vision_lab.collect(config, state, fixed_rows)
                _validate_collected_hashes(state, fixed_rows)
            finally:
                state.close()
            return result
        if stage == "dataset":
            # The dataset builder discovers only config-validated queue roots;
            # its output is accepted only after the emitted manifest is read
            # back and bound to reviewed local evidence.
            registered_manifest = _load_fixed_input(self.registration, stage)
            _validate_dataset_input_snapshot(registered_manifest, config, vision_lab, self.registration)
            result = vision_lab.build_dataset(config)
            result["_worker_validation"] = _validate_dataset_result(config, result, self.registration)
            result["dataset_sha256"] = result["_worker_validation"]["dataset_sha256"]
            return result
        if stage == "train":
            _validate_evaluation_inputs(config, self.registration, stage="train")
            dataset = _load_fixed_input(self.registration, stage)
            if not isinstance(dataset, dict):
                raise WorkerBlockedError("registered dataset input is not an object")
            dataset_validation = _validate_dataset_result(config, dataset, self.registration)
            result = vision_lab.train_candidate(config, dataset)
            model_validation = _validate_model_result(config, result, self.registration)
            result["_worker_validation"] = {
                **model_validation,
                "source_asset_sha256": dataset_validation["dataset_sha256"],
            }
            return result
        model = _load_fixed_input(self.registration, "evaluate")
        _validate_evaluation_inputs(config, self.registration, stage="evaluate")
        if not isinstance(model, dict):
            raise WorkerBlockedError("registered model input is not an object")
        model_validation = _validate_model_result(config, model, self.registration)
        metrics_path, metrics = vision_lab.evaluate_candidate(config, model)
        gate = vision_lab.evaluate_gate(config, model, metrics_path)
        result = {"metrics": metrics, "gate": gate}
        _validate_evaluation_result(result, model, self.registration, config)
        result["_worker_validation"] = {
            "independent": True,
            "artifact_sha256": metrics.get("artifact_sha256"),
            "source_asset_sha256": model_validation["artifact_sha256"],
        }
        return result

    @staticmethod
    def _contracts() -> tuple[Any, Any, Any, Any, Any, Any]:
        """Load shared contracts only when a claimed job is being completed."""

        backend_root = Path(__file__).resolve().parents[2] / "backend" / "python"
        if str(backend_root) not in sys.path:
            sys.path.insert(0, str(backend_root))
        from vision_training.models import AssetReceipt, JobReceipt, ReceiptStatus, StageName, StageReceipt
        from vision_training.queue import receipt_hash

        return AssetReceipt, JobReceipt, ReceiptStatus, StageName, StageReceipt, receipt_hash

    def execute_job(self, job: Any) -> Any:
        """Execute a claimed job and return an immutable, scope-bound receipt.

        The queue identity is supplied by the API.  The worker may only execute
        the explicitly registered adapter and local stage, and the returned
        receipt repeats every identity field so the API can reject forged or
        cross-factory output.
        """

        AssetReceipt, JobReceipt, ReceiptStatus, StageName, StageReceipt, make_hash = self._contracts()
        identity = job.identity
        stage = getattr(job.stage, "value", job.stage)
        if self.registration.adapter_id != getattr(job, "adapter_id", None):
            raise WorkerBlockedError("claimed job adapter does not match the local worker binding")
        if identity.factory_id != self.registration.factory_id or identity.project_id != self.registration.project_id:
            raise WorkerBlockedError("claimed job identity does not match the local worker binding")
        if identity.config_hash.lower() != self.registration.config_sha256.lower():
            raise WorkerBlockedError("claimed job config hash does not match the registered config")
        expected_input_hash = (self.registration.input_hashes or {}).get(stage)
        if not expected_input_hash or identity.input_hash.lower() != expected_input_hash.lower():
            raise WorkerBlockedError("claimed job input hash is not bound to a registered local input")
        if stage in {"train", "evaluate"} and not identity.source_asset_sha256:
            raise WorkerBlockedError("claimed job lacks a version-bound predecessor asset hash")
        if stage not in {"collect", "dataset", "train", "evaluate"}:
            raise WorkerBlockedError("only collect/dataset/train/evaluate jobs can run on the local worker")

        raw = self.execute(stage)
        if not isinstance(raw, dict):
            raise WorkerBlockedError("named VisionLab function returned a non-object receipt")
        validation = raw.get("_worker_validation") or {}
        if stage == "train" and str(validation.get("source_asset_sha256") or "").lower() != identity.source_asset_sha256.lower():
            raise WorkerBlockedError("registered dataset asset hash does not match the queued train input")
        if stage == "evaluate":
            metrics = raw.get("metrics")
            evaluated_model_hash = metrics.get("artifact_sha256") if isinstance(metrics, dict) else None
            if str(validation.get("source_asset_sha256") or evaluated_model_hash or "").lower() != identity.source_asset_sha256.lower():
                raise WorkerBlockedError("registered model asset hash does not match the queued evaluation input")
        status = ReceiptStatus.SUCCEEDED
        message = "named VisionLab stage completed locally"
        if stage == "evaluate":
            gate = raw.get("gate")
            if not isinstance(gate, dict) or gate.get("passed") is not True:
                status = ReceiptStatus.FAILED
                message = "candidate evaluation gate did not pass; independent acceptance remains pending"

        stage_name = StageName(stage)
        stage_receipt = StageReceipt(
            receipt_id=f"{job.job_id}:{stage}",
            stage=stage_name,
            status=status,
            project_id=identity.project_id,
            factory_id=identity.factory_id,
            task_family=identity.task_family,
            training_executed=stage == "train" and status == ReceiptStatus.SUCCEEDED,
            production_writes=0,
            message=message,
            details={"worker_version": VERSION, "config_hash": identity.config_hash, "input_hash": identity.input_hash, "summary": _public_receipt_value(raw)},
        )
        assets: list[Any] = []
        if status == ReceiptStatus.SUCCEEDED and stage in {"dataset", "train", "evaluate"}:
            if stage == "dataset":
                digest = str(raw.get("dataset_sha256") or "")
                kind = "dataset"
            elif stage == "train":
                digest = str(raw.get("artifact_sha256") or "")
                kind = "model"
            else:
                metrics = raw.get("metrics") or {}
                digest = str(metrics.get("artifact_sha256") or "") if isinstance(metrics, dict) else ""
                kind = "evaluation"
            if not SHA256_RE.fullmatch(digest):
                raise WorkerBlockedError(f"{stage} did not return a verifiable artifact hash")
            validation = raw.get("_worker_validation") or {}
            assets.append(AssetReceipt(
                receipt_id=f"{job.job_id}:{kind}",
                kind=kind,
                status=ReceiptStatus.SUCCEEDED,
                factory_id=identity.factory_id,
                task_id=identity.project_id,
                task_family=identity.task_family,
                data_version=identity.data_version,
                recipe_version=identity.recipe_version,
                human_standard_version=identity.human_standard_version,
                sha256=digest,
                reviewed_only=bool(validation.get("reviewed_only", False)),
                unreviewed_count=int(validation.get("unreviewed_count", 0)),
                protected_holdout_excluded=bool(validation.get("protected_holdout_excluded", False)),
                independent=bool(validation.get("independent", False)),
                training_success=stage == "train",
                acceptance_status="pending",
                enabled=False,
                production_writes=0,
                details={"source_stage": stage, "sop_version": identity.sop_version},
            ))
        receipt = JobReceipt(
            job_id=job.job_id,
            identity=identity,
            stage=stage_name,
            status=status,
            receipt_hash="0" * 64,
            stage_receipt=stage_receipt,
            asset_receipts=tuple(assets),
        )
        return receipt.model_copy(update={"receipt_hash": make_hash(receipt)})


def _worker_contracts() -> tuple[Any, Any, Any, Any, Any]:
    backend_root = Path(__file__).resolve().parents[2] / "backend" / "python"
    if str(backend_root) not in sys.path:
        sys.path.insert(0, str(backend_root))
    from vision_training.models import JobReceipt, ReceiptStatus, StageReceipt, VisionJob
    from vision_training.queue import receipt_hash

    return JobReceipt, ReceiptStatus, StageReceipt, VisionJob, receipt_hash


def _server_request(registration: WorkerRegistration, path: str, token: str, payload: dict[str, Any]) -> dict[str, Any]:
    allowed_paths = {
        "/api/vision-training/worker/jobs/claim",
        "/api/vision-training/worker/jobs/heartbeat",
    }
    if path not in allowed_paths and not re.fullmatch(r"/api/vision-training/worker/jobs/[A-Za-z0-9_.:-]+/(?:complete|heartbeat)", path):
        raise WorkerConfigurationError("worker transport path is not an allowed SOP endpoint")
    internal_secret = os.environ.get(registration.internal_secret_env, "")
    if not internal_secret:
        raise WorkerConfigurationError("worker internal secret environment variable is empty")
    url = registration.server_url.rstrip("/") + path
    request = urllib.request.Request(
        url,
        data=json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "X-Worker-Id": registration.worker_id,
            "X-Worker-Token": token,
            "X-Internal-Secret": internal_secret,
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            value = json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise WorkerBlockedError("worker transport request failed") from exc
    if not isinstance(value, dict) or value.get("success") is not True:
        raise WorkerBlockedError("worker transport returned an invalid response")
    return value


def _failed_job_receipt(job: Any, error: Exception) -> Any:
    JobReceipt, ReceiptStatus, StageReceipt, _, make_hash = _worker_contracts()
    stage = getattr(job.stage, "value", job.stage)
    stage_receipt = StageReceipt(
        receipt_id=f"{job.job_id}:{stage}:failed",
        stage=stage,
        status=ReceiptStatus.FAILED,
        project_id=job.identity.project_id,
        factory_id=job.identity.factory_id,
        task_family=job.identity.task_family,
        training_executed=False,
        production_writes=0,
        message="named worker execution failed; inspect the local worker log",
        details={"worker_version": VERSION, "error_code": type(error).__name__},
    )
    receipt = JobReceipt(
        job_id=job.job_id,
        identity=job.identity,
        stage=stage,
        status=ReceiptStatus.FAILED,
        receipt_hash="0" * 64,
        stage_receipt=stage_receipt,
    )
    return receipt.model_copy(update={"receipt_hash": make_hash(receipt)})


def run_once(registration: WorkerRegistration) -> dict[str, Any]:
    """Claim one bound job, execute it, and submit one immutable receipt."""

    registration.validate(require_transport=True)
    token = os.environ.get(registration.worker_token_env, "")
    if not token:
        raise WorkerConfigurationError("worker token environment variable is empty")
    claim = _server_request(
        registration,
        "/api/vision-training/worker/jobs/claim",
        token,
        {"project_id": registration.project_id, "lease_seconds": 86400},
    )
    data = claim.get("data")
    if data is None:
        return {"status": "no_job", "worker_id": registration.worker_id, "project_id": registration.project_id}
    _, _, _, VisionJob, _ = _worker_contracts()
    job = VisionJob.model_validate(data)
    if job.identity.factory_id != registration.factory_id or job.identity.project_id != registration.project_id:
        raise WorkerBlockedError("claimed job is outside the registration binding")
    worker = VisionLabSOPWorker(registration)
    stop = threading.Event()

    def heartbeat_loop() -> None:
        while not stop.wait(300):
            try:
                _server_request(
                    registration,
                    f"/api/vision-training/worker/jobs/{job.job_id}/heartbeat",
                    token,
                    {"lease_seconds": 86400},
                )
            except WorkerBlockedError:
                return

    heartbeat_thread = threading.Thread(target=heartbeat_loop, name="vision-sop-heartbeat", daemon=True)
    heartbeat_thread.start()
    try:
        try:
            receipt = worker.execute_job(job)
        except Exception as exc:  # local failures are reported as failed, scoped receipts
            receipt = _failed_job_receipt(job, exc)
        completed = _server_request(
            registration,
            f"/api/vision-training/worker/jobs/{job.job_id}/complete",
            token,
            {"job_id": job.job_id, "receipt": receipt.model_dump(mode="json")},
        )
    finally:
        stop.set()
        heartbeat_thread.join(timeout=2)
    completed_data = completed.get("data") or {}
    return {
        "status": "completed",
        "job_id": job.job_id,
        "job_status": completed_data.get("status"),
        "data": completed_data,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registration", required=True, type=Path)
    parser.add_argument("--stage", choices=("collect", "dataset", "train", "evaluate"))
    parser.add_argument("--once", action="store_true", help="claim one bound job and submit its receipt")
    args = parser.parse_args(argv)
    registration = WorkerRegistration.from_json(args.registration)
    if args.once:
        result = run_once(registration)
    elif args.stage:
        result = VisionLabSOPWorker(registration).execute(args.stage)
    else:
        parser.error("one of --once or --stage is required")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if args.once and result.get("job_status") == "failed":
        return 2
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
