#!/usr/bin/env python3
"""Local, receipt-driven YOLO training loop for LIUSHANMEN label QC.

The production side is read-only until an evaluated candidate passes every
promotion gate or an operator explicitly accepts only an incomplete-recall
gate.  Runtime data lives outside git under D:\\CretasVisionLab.
"""
from __future__ import annotations

import argparse
import contextlib
import datetime as dt
import hashlib
import json
import os
import re
import shlex
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Sequence


VERSION = "vision-lab-v1"
EXIT_ATTENTION_REQUIRED = 20
SAFE_FACTORY = re.compile(r"^[A-Z0-9_-]+$")
SAFE_REMOTE_PATH = re.compile(r"^/[A-Za-z0-9_./-]+$")
SAFE_SERVICE = re.compile(r"^[A-Za-z0-9_.@-]+$")
OPERATOR_RECALL_OVERRIDE_TOKEN = "ACCEPT-INCOMPLETE-RECALL"
WAIVABLE_RECALL_ERROR_PREFIX = "required defect group did not reach full recall: "
LABEL_CLASS_IDS = {"white_label": 0, "color_label": 1}
ISOLATED_MISSING_REPEAT_STRATEGY = "human-confirmed-isolated-missing-v1"
ISOLATED_MISSING_CLASS_REPEAT_STRATEGY = "human-confirmed-isolated-missing-by-class-v2"
DEFAULT_TRAY_PAD_RATIO = 0.14


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def stable_json(payload: Any) -> bytes:
    return json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    raw = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as handle:
        handle.write(raw)
        temp = Path(handle.name)
    temp.replace(path)


def write_bytes(path: Path, payload: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("wb", dir=path.parent, delete=False) as handle:
        handle.write(payload)
        temp = Path(handle.name)
    temp.replace(path)


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def load_config(path: Path) -> dict[str, Any]:
    config = load_json(path)
    if config.get("version") != VERSION:
        raise ValueError(f"unsupported config version: {config.get('version')!r}")
    root = Path(os.path.expandvars(config["runtime_root"])).resolve()
    config["runtime_root"] = str(root)
    factory = str(config.get("source", {}).get("factory_id", ""))
    if not SAFE_FACTORY.fullmatch(factory):
        raise ValueError(f"unsafe factory id: {factory!r}")
    cloud = config.get("cloud_vl", {})
    if cloud.get("enabled") and (
        int(cloud.get("max_calls_per_run", 0)) <= 0
        or float(cloud.get("monthly_budget_cny", 0)) <= 0
    ):
        raise ValueError("cloud VL requires positive call and monthly budget caps")
    return config


class State:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.path = root / "state" / "vision.db"
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(self.path)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.executescript(
            """
            CREATE TABLE IF NOT EXISTS meta (
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS photos (
              photo_id TEXT PRIMARY KEY,
              task_id TEXT NOT NULL,
              reviewed_at TEXT NOT NULL,
              sku_code TEXT,
              object_ref TEXT NOT NULL,
              sha256 TEXT NOT NULL,
              local_path TEXT NOT NULL,
              annotations_json TEXT NOT NULL,
              collected_at TEXT NOT NULL
            );
            DROP INDEX IF EXISTS idx_photos_sha;
            CREATE INDEX IF NOT EXISTS idx_photos_sha_lookup ON photos(sha256);
            CREATE TABLE IF NOT EXISTS queue_snapshots (
              queue_id TEXT PRIMARY KEY,
              root TEXT NOT NULL,
              manifest_sha256 TEXT NOT NULL,
              total INTEGER NOT NULL,
              reviewed INTEGER NOT NULL,
              unjudgeable INTEGER NOT NULL,
              updated_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS models (
              model_id TEXT PRIMARY KEY,
              status TEXT NOT NULL,
              artifact_path TEXT NOT NULL,
              artifact_sha256 TEXT NOT NULL,
              metrics_json TEXT,
              created_at TEXT NOT NULL,
              deployed_at TEXT
            );
            """
        )
        self.db.commit()

    def close(self) -> None:
        self.db.close()

    def get_meta(self, key: str, default: str | None = None) -> str | None:
        row = self.db.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
        return row["value"] if row else default

    def set_meta(self, key: str, value: str) -> None:
        self.db.execute(
            "INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            (key, value),
        )
        self.db.commit()


def runtime_paths(root: Path) -> dict[str, Path]:
    names = ("raw", "queues", "annotations", "datasets", "runs", "models", "receipts", "logs", "attention", "state")
    return {name: root / name for name in names}


def init_layout(config: dict[str, Any], state: State) -> dict[str, Any]:
    root = Path(config["runtime_root"])
    for path in runtime_paths(root).values():
        path.mkdir(parents=True, exist_ok=True)
    source = config["source"]
    if state.get_meta("watermark") is None:
        state.set_meta("watermark", str(source["initial_watermark"]))
    if state.get_meta("watermark_photo_id") is None:
        state.set_meta("watermark_photo_id", "")
    state.set_meta("config_sha256", hashlib.sha256(stable_json(config)).hexdigest())
    return {
        "runtime_root": str(root),
        "database": str(state.path),
        "watermark": state.get_meta("watermark"),
        "cloud_vl_enabled": bool(config.get("cloud_vl", {}).get("enabled", False)),
    }


def pid_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


@contextlib.contextmanager
def pipeline_lock(root: Path):
    lock = root / "state" / "cycle.lock"
    lock.parent.mkdir(parents=True, exist_ok=True)
    if lock.exists():
        try:
            owner = load_json(lock)
            if pid_alive(int(owner.get("pid", -1))):
                raise RuntimeError(f"another cycle is running: pid={owner.get('pid')}")
        except (ValueError, json.JSONDecodeError, OSError):
            raise RuntimeError(f"cannot validate existing lock: {lock}")
        lock.unlink()
    write_json(lock, {"pid": os.getpid(), "started_at": utc_now()})
    try:
        yield
    finally:
        if lock.exists():
            lock.unlink()


def parse_watermark(value: str) -> dt.datetime:
    parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    return parsed.replace(tzinfo=None)


def query_production(config: dict[str, Any], watermark: str, watermark_photo_id: str) -> list[dict[str, Any]]:
    source = config["source"]
    factory = source["factory_id"]
    stamp = parse_watermark(watermark).strftime("%Y-%m-%d %H:%M:%S.%f")
    limit = max(1, min(int(source.get("max_records_per_run", 500)), 5000))
    if watermark_photo_id and not re.fullmatch(r"[0-9a-fA-F-]{36}", watermark_photo_id):
        raise ValueError("invalid photo-id watermark")
    photo_floor = watermark_photo_id or "00000000-0000-0000-0000-000000000000"
    sql = f"""
SELECT COALESCE(jsonb_agg(row_data ORDER BY row_data->>'reviewed_at',
                          (row_data->>'order_index')::int), '[]'::jsonb)
FROM (
  SELECT jsonb_build_object(
    'photo_id', p.id, 'task_id', t.id, 'sku_code', t.sku_code,
    'reviewed_at', t.reviewed_at, 'order_index', p.order_index,
    'file_url', a.file_url,
    'annotations', COALESCE((
      SELECT jsonb_agg(jsonb_build_object(
        'source', q.source, 'ai_label', q.ai_label,
        'human_label', q.human_label, 'ai_confidence', q.ai_confidence,
        'bbox', CASE WHEN q.x_min IS NULL THEN NULL
          ELSE jsonb_build_array(q.x_min, q.y_min, q.x_max, q.y_max) END
      ) ORDER BY q.created_at, q.id)
      FROM label_qc_annotations q
      WHERE q.factory_id = '{factory}' AND q.photo_id = p.id AND q.deleted_at IS NULL
    ), '[]'::jsonb)
  ) AS row_data
  FROM label_qc_photos p
  JOIN label_qc_tasks t ON t.id=p.task_id AND t.factory_id=p.factory_id AND t.deleted_at IS NULL
  JOIN attachments a ON a.id=p.attachment_id AND a.factory_id=p.factory_id AND a.deleted_at IS NULL
  WHERE p.factory_id='{factory}' AND p.deleted_at IS NULL
    AND t.status='REVIEWED'
    AND (t.reviewed_at > TIMESTAMP '{stamp}' OR
         (t.reviewed_at = TIMESTAMP '{stamp}' AND p.id::text > '{photo_floor}'))
  ORDER BY t.reviewed_at, p.id::text
  LIMIT {limit}
) s;
"""
    host = str(source["ssh_host"])
    db_name = str(source.get("database", "cretas_prod_db"))
    command = [
        "ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15", host,
        f"sudo -u postgres psql -X -qAt {shlex.quote(db_name)}",
    ]
    result = subprocess.run(
        command, input=sql, text=True, encoding="utf-8", errors="strict",
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=90,
    )
    if result.returncode != 0:
        raise RuntimeError(f"production read failed: {result.stderr.strip()}")
    rows = json.loads(result.stdout or "[]")
    if not isinstance(rows, list):
        raise RuntimeError("production query did not return a JSON list")
    ids = [str(row.get("photo_id")) for row in rows]
    if len(ids) != len(set(ids)):
        raise RuntimeError("production query returned duplicate photo ids")
    return rows


def object_ref(url: str) -> str:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ValueError("only http(s) object downloads are allowed")
    return f"{parsed.netloc}{urllib.parse.unquote(parsed.path)}"


def download_bytes(url: str, user_agent: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": user_agent})
    with urllib.request.urlopen(request, timeout=90) as response:
        data = response.read()
    if not data:
        raise RuntimeError("empty object download")
    return data


def prepare_object(root: Path, url: str, ref: str, user_agent: str) -> tuple[str, Path, bool]:
    """Return a content-addressed local object, reusing completed downloads by OSS object key."""
    suffix = Path(urllib.parse.urlparse(url).path).suffix.lower()
    suffix = suffix if suffix in {".jpg", ".jpeg", ".png", ".webp"} else ".img"
    ref_digest = hashlib.sha256(ref.encode("utf-8")).hexdigest()
    index_path = root / "raw" / "object-index" / ref_digest[:2] / f"{ref_digest}.json"
    if index_path.is_file():
        try:
            cached = load_json(index_path)
        except (OSError, json.JSONDecodeError):
            cached = None
        if isinstance(cached, dict) and cached.get("object_ref") == ref:
            cached_digest = str(cached.get("sha256") or "")
            cached_suffix = str(cached.get("suffix") or "")
            if re.fullmatch(r"[0-9a-f]{64}", cached_digest) and cached_suffix in {".jpg", ".jpeg", ".png", ".webp", ".img"}:
                destination = root / "raw" / "sha256" / cached_digest[:2] / f"{cached_digest}{cached_suffix}"
                if destination.is_file() and sha256_file(destination) == cached_digest:
                    return cached_digest, destination, True

    data = download_bytes(url, user_agent)
    digest = hashlib.sha256(data).hexdigest()
    destination = root / "raw" / "sha256" / digest[:2] / f"{digest}{suffix}"
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and sha256_file(destination) != digest:
        raise RuntimeError(f"content-address collision: {destination}")
    if not destination.exists():
        write_bytes(destination, data)
    write_json(index_path, {
        "version": 1, "object_ref": ref, "sha256": digest, "suffix": suffix,
    })
    return digest, destination, False


def collect(config: dict[str, Any], state: State, rows: list[dict[str, Any]] | None = None) -> dict[str, Any]:
    root = Path(config["runtime_root"])
    watermark = str(state.get_meta("watermark", config["source"]["initial_watermark"]))
    watermark_photo_id = str(state.get_meta("watermark_photo_id", ""))
    queried_production = rows is None
    rows = query_production(config, watermark, watermark_photo_id) if rows is None else rows
    prepared: list[dict[str, Any]] = []
    object_cache_hits = 0
    object_downloads = 0
    for row in rows:
        required = ("photo_id", "task_id", "reviewed_at", "file_url")
        if any(not row.get(key) for key in required):
            raise ValueError(f"source record missing required fields: {row}")
        ref = object_ref(str(row["file_url"]))
        digest, destination, cache_hit = prepare_object(
            root, str(row["file_url"]), ref, "Cretas-VisionLab-readonly/1.0",
        )
        object_cache_hits += int(cache_hit)
        object_downloads += int(not cache_hit)
        prepared.append({
            "photo_id": str(row["photo_id"]), "task_id": str(row["task_id"]),
            "reviewed_at": str(row["reviewed_at"]), "sku_code": str(row.get("sku_code") or ""),
            "object_ref": ref, "sha256": digest, "local_path": str(destination),
            "annotations_json": json.dumps(row.get("annotations") or [], ensure_ascii=False),
        })

    receipt = {
        "version": VERSION, "stage": "collect", "created_at": utc_now(),
        "watermark_before": {"reviewed_at": watermark, "photo_id": watermark_photo_id}, "records_received": len(rows),
        "records_prepared": len(prepared), "production_reads": 1 if queried_production else 0,
        "object_cache_hits": object_cache_hits, "object_downloads": object_downloads,
        "production_writes": 0, "originals_modified": 0,
    }
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    receipt_path = root / "receipts" / f"collect-{stamp}.json"
    write_json(receipt_path, receipt)
    with state.db:
        for row in prepared:
            state.db.execute(
                """INSERT INTO photos(photo_id,task_id,reviewed_at,sku_code,object_ref,sha256,local_path,annotations_json,collected_at)
                   VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(photo_id) DO UPDATE SET
                   reviewed_at=excluded.reviewed_at,sku_code=excluded.sku_code,object_ref=excluded.object_ref,
                   sha256=excluded.sha256,local_path=excluded.local_path,annotations_json=excluded.annotations_json""",
                (*row.values(), utc_now()),
            )
    if prepared:
        next_watermark, next_photo_id = max((row["reviewed_at"], row["photo_id"]) for row in prepared)
        parse_watermark(next_watermark)
        receipt["watermark_after"] = {"reviewed_at": next_watermark, "photo_id": next_photo_id}
        write_json(receipt_path, receipt)
        state.set_meta("watermark", next_watermark)
        state.set_meta("watermark_photo_id", next_photo_id)
    else:
        receipt["watermark_after"] = {"reviewed_at": watermark, "photo_id": watermark_photo_id}
        write_json(receipt_path, receipt)
    receipt["receipt"] = str(receipt_path)
    return receipt


def queue_snapshot(queue_root: Path) -> dict[str, Any]:
    manifest_path = queue_root / "manifest.json"
    if not manifest_path.is_file():
        raise FileNotFoundError(manifest_path)
    manifest = load_json(manifest_path)
    rows = manifest.get("rows") or []
    if not isinstance(rows, list) or not rows:
        raise ValueError(f"queue has no rows: {queue_root}")
    ids = [str(row["crop_id"]) for row in rows]
    if len(ids) != len(set(ids)):
        raise ValueError(f"duplicate crop ids: {queue_root}")
    reviewed = unjudgeable = 0
    annotations = queue_root / "annotations-human"
    for crop_id in ids:
        path = annotations / f"{crop_id}.json"
        if not path.is_file():
            continue
        payload = load_json(path)
        if payload.get("reviewed"):
            reviewed += 1
            unjudgeable += int(bool(payload.get("unjudgeable")))
    return {
        "queue_id": str(manifest.get("version") or queue_root.name),
        "root": str(queue_root.resolve()), "manifest": str(manifest_path.resolve()),
        "manifest_sha256": sha256_file(manifest_path), "total": len(ids),
        "reviewed": reviewed, "remaining": len(ids) - reviewed,
        "unjudgeable": unjudgeable,
    }


def scan_queues(
    config: dict[str, Any], state: State, *, manage_attention_mark: bool = True,
) -> dict[str, Any]:
    root = Path(config["runtime_root"])
    snapshots = [queue_snapshot(path) for path in discover_queue_roots(config)]
    with state.db:
        for item in snapshots:
            state.db.execute(
                """INSERT INTO queue_snapshots(queue_id,root,manifest_sha256,total,reviewed,unjudgeable,updated_at)
                   VALUES(?,?,?,?,?,?,?) ON CONFLICT(queue_id) DO UPDATE SET root=excluded.root,
                   manifest_sha256=excluded.manifest_sha256,total=excluded.total,reviewed=excluded.reviewed,
                   unjudgeable=excluded.unjudgeable,updated_at=excluded.updated_at""",
                (item["queue_id"], item["root"], item["manifest_sha256"], item["total"], item["reviewed"], item["unjudgeable"], utc_now()),
            )
    pending = [item for item in snapshots if item["remaining"] > 0]
    mark = root / "attention" / "MARK-NEEDS-ANNOTATION.json"
    mark_text = root / "attention" / "MARK-NEEDS-ANNOTATION.txt"
    if pending and manage_attention_mark:
        payload = {
            "version": VERSION, "created_at": utc_now(), "status": "NEEDS_ANNOTATION",
            "message": "有图片需要人工确认；完成后流水线会自动继续训练。",
            "annotator_url": config.get("annotator_url"), "queues": pending,
        }
        write_json(mark, payload)
        remaining = sum(item["remaining"] for item in pending)
        mark_text.write_text(
            f"需要人工确认：{remaining} 张\n标注地址：{config.get('annotator_url') or '见 JSON 中的队列路径'}\n"
            f"完成后无需手工训练，下一轮会自动继续。\n",
            encoding="utf-8",
        )
    elif manage_attention_mark:
        for path in (mark, mark_text):
            if path.exists():
                path.unlink()
    return {
        "queues": snapshots,
        "pending_queues": len(pending),
        "mark": str(mark) if mark.exists() else None,
        "attention_mark_managed": manage_attention_mark,
    }


def training_quarantines(config: dict[str, Any]) -> list[dict[str, Any]]:
    configured = config.get("training_quarantine_receipts", [])
    if not isinstance(configured, list):
        raise ValueError("training_quarantine_receipts must be a list")

    quarantines: list[dict[str, Any]] = []
    seen: set[str] = set()
    for value in configured:
        if not isinstance(value, str) or not value.strip():
            raise ValueError("training quarantine receipt paths must be non-empty strings")
        receipt_path = Path(os.path.expandvars(value)).resolve()
        if not receipt_path.is_file():
            raise RuntimeError(f"training quarantine receipt is missing: {receipt_path}")
        receipt = load_json(receipt_path)
        if not isinstance(receipt, dict) or receipt.get("production_writes") != 0:
            raise RuntimeError(f"training quarantine receipt failed its safety contract: {receipt_path}")
        queue_value = receipt.get("excluded_blurry_queue")
        if not isinstance(queue_value, str) or not queue_value.strip():
            raise RuntimeError(f"training quarantine receipt has no excluded queue: {receipt_path}")
        queue_root = Path(os.path.expandvars(queue_value)).resolve()
        queue_key = str(queue_root).lower()
        if queue_key in seen:
            raise RuntimeError(f"training quarantine queue is duplicated: {queue_root}")
        manifest_path = queue_root / "manifest.json"
        if not manifest_path.is_file():
            raise RuntimeError(f"training quarantine queue manifest is missing: {manifest_path}")
        manifest = load_json(manifest_path)
        queue_rows = manifest.get("rows") if isinstance(manifest, dict) else None
        if not isinstance(queue_rows, list) or not queue_rows:
            raise RuntimeError(f"training quarantine queue has no annotation rows: {queue_root}")
        tasks: set[str] = set()
        for row in queue_rows:
            if not isinstance(row, dict) or not row.get("source_task_id"):
                raise RuntimeError(f"training quarantine row has no source task: {queue_root}")
            tasks.add(str(row["source_task_id"]))
        if (
            receipt.get("excluded_blurry_queue_crop_count") != len(queue_rows)
            or receipt.get("excluded_blurry_queue_task_count") != len(tasks)
        ):
            raise RuntimeError(f"training quarantine receipt no longer matches its queue: {receipt_path}")
        reasons = receipt.get("quarantined_rows_by_reason")
        reason = next(iter(reasons)) if isinstance(reasons, dict) and len(reasons) == 1 else "quarantined_source_task"
        quarantines.append({
            "receipt": receipt_path,
            "root": queue_root,
            "manifest": manifest_path,
            "rows": queue_rows,
            "tasks": tasks,
            "reason": str(reason),
        })
        seen.add(queue_key)
    return quarantines


def discover_queue_roots(config: dict[str, Any]) -> list[Path]:
    def is_annotation_queue(path: Path) -> bool:
        manifest_path = path / "manifest.json"
        if not manifest_path.is_file():
            return False
        manifest = load_json(manifest_path)
        if not isinstance(manifest, dict) or "rows" not in manifest:
            return False
        rows = manifest["rows"]
        if not isinstance(rows, list):
            raise ValueError(f"queue rows must be a list: {path}")
        return bool(rows)

    quarantined = {
        str(item["root"]).lower(): item
        for item in training_quarantines(config)
    }
    found: dict[str, Path] = {}
    for value in config.get("queue_roots", []):
        path = Path(os.path.expandvars(value)).resolve()
        if str(path).lower() in quarantined:
            raise RuntimeError(f"explicit queue root is quarantined from training: {path}")
        if not is_annotation_queue(path):
            raise RuntimeError(f"explicit queue root has no annotation rows: {path}")
        found[str(path).lower()] = path
    for value in config.get("queue_globs", []):
        expanded = os.path.expandvars(value)
        parent = Path(expanded).parent
        pattern = Path(expanded).name
        if parent.is_dir():
            for path in parent.glob(pattern):
                resolved = path.resolve()
                if str(resolved).lower() not in quarantined and is_annotation_queue(resolved):
                    found[str(resolved).lower()] = resolved
    return [found[key] for key in sorted(found)]


def config_with_queue_roots(config: dict[str, Any], values: Sequence[Path] | None) -> dict[str, Any]:
    if not values:
        return config
    roots = [Path(value).resolve() for value in values]
    if len({str(path).lower() for path in roots}) != len(roots):
        raise RuntimeError("explicit queue roots contain duplicates")
    overridden = dict(config)
    overridden["queue_roots"] = [str(path) for path in roots]
    overridden["queue_globs"] = []
    discover_queue_roots(overridden)
    return overridden


def deterministic_split(task_id: str, validation_percent: int) -> str:
    bucket = int(hashlib.sha256(task_id.encode("utf-8")).hexdigest()[:8], 16) % 100
    return "val" if bucket < validation_percent else "train"


def yolo_line(box: dict[str, Any]) -> str:
    cls = int(box["c"])
    x0, y0, x1, y1 = map(float, box["b"])
    if cls not in (0, 1) or not (0 <= x0 < x1 <= 1 and 0 <= y0 < y1 <= 1):
        raise ValueError(f"invalid annotation box: {box}")
    return f"{cls} {(x0+x1)/2:.6f} {(y0+y1)/2:.6f} {x1-x0:.6f} {y1-y0:.6f}"


def isolated_missing_class(annotation: dict[str, Any]) -> str | None:
    """Return the missing class only for unambiguous single-tray supervision.

    A confirmed missing-label crop is safe to upweight only when the human
    annotation declares exactly one missing class and contains exactly one box
    for the complementary class.  Multi-box crops may include neighbouring
    trays, so they remain ordinary detector samples instead of receiving extra
    hard-negative weight.
    """
    if annotation.get("missing_confirmed_by_human") is not True:
        return None
    missing = annotation.get("declared_missing_classes")
    if not isinstance(missing, list) or not missing:
        raise RuntimeError("confirmed missing-label annotation has unsupported declared classes")
    normalized_missing: list[str] = []
    for value in missing:
        if value in LABEL_CLASS_IDS:
            normalized_missing.append(str(value))
        elif isinstance(value, int) and not isinstance(value, bool) and value in LABEL_CLASS_IDS.values():
            normalized_missing.append(next(name for name, class_id in LABEL_CLASS_IDS.items() if class_id == value))
        else:
            raise RuntimeError("confirmed missing-label annotation has unsupported declared classes")
    if len(set(normalized_missing)) != len(normalized_missing):
        raise RuntimeError("confirmed missing-label annotation repeats a declared class")
    if len(normalized_missing) != 1:
        return None
    boxes = annotation.get("boxes") or []
    present_classes = [int(box["c"]) for box in boxes]
    missing_class = normalized_missing[0]
    missing_id = LABEL_CLASS_IDS[missing_class]
    complementary_id = 1 - missing_id
    if missing_id in present_classes:
        raise RuntimeError(f"confirmed missing-label annotation contains {missing_class} box")
    if len(present_classes) != 1 or present_classes[0] != complementary_id:
        return None
    return missing_class


def isolated_missing_repeat(training: dict[str, Any]) -> int:
    value = training.get("isolated_missing_repeat", 1)
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError("training option isolated_missing_repeat must be an integer")
    if not 1 <= value <= 10:
        raise ValueError("training option isolated_missing_repeat must be between 1 and 10")
    return value


def isolated_missing_repeats(training: dict[str, Any]) -> dict[str, int]:
    base_repeat = isolated_missing_repeat(training)
    repeats = {class_name: base_repeat for class_name in LABEL_CLASS_IDS}
    overrides = training.get("isolated_missing_repeat_by_class")
    if overrides is None:
        return repeats
    if not isinstance(overrides, dict):
        raise ValueError(
            "training option isolated_missing_repeat_by_class must be an object"
        )
    if not overrides:
        raise ValueError(
            "training option isolated_missing_repeat_by_class must not be empty"
        )
    unknown = sorted(set(overrides) - set(LABEL_CLASS_IDS))
    if unknown:
        raise ValueError(
            "training option isolated_missing_repeat_by_class has unknown classes: "
            + ", ".join(unknown)
        )
    for class_name, value in overrides.items():
        if isinstance(value, bool) or not isinstance(value, int):
            raise ValueError(
                f"training option isolated_missing_repeat_by_class.{class_name} "
                "must be an integer"
            )
        if not 1 <= value <= 10:
            raise ValueError(
                f"training option isolated_missing_repeat_by_class.{class_name} "
                "must be between 1 and 10"
            )
        repeats[class_name] = value
    return repeats


def build_dataset(config: dict[str, Any]) -> dict[str, Any]:
    root = Path(config["runtime_root"])
    training = config.get("training", {})
    validation_percent = int(training.get("validation_percent", 20))
    missing_repeat = isolated_missing_repeat(training)
    missing_repeats = isolated_missing_repeats(training)
    class_repeat_overrides = training.get("isolated_missing_repeat_by_class") is not None
    rows: list[dict[str, Any]] = []
    manifest_hashes: list[str] = []
    identity_rows: list[dict[str, str]] = []
    training_exclusions: dict[str, int] = {}
    quarantined_tasks: dict[str, str] = {}
    for quarantine in training_quarantines(config):
        reason = str(quarantine["reason"])
        training_exclusions[reason] = training_exclusions.get(reason, 0) + len(quarantine["rows"])
        for task_id in quarantine["tasks"]:
            quarantined_tasks[str(task_id)] = reason
    for queue_root in discover_queue_roots(config):
        manifest_path = queue_root / "manifest.json"
        manifest = load_json(manifest_path)
        manifest_hashes.append(sha256_file(manifest_path))
        authoritative_photo_verdict = manifest.get(
            "photo_level_human_verdict_authoritative",
            str(manifest.get("version", "")).startswith("liushanmen-clear-active-learning-"),
        )
        for source in manifest["rows"]:
            annotation_path = queue_root / "annotations-human" / f"{source['crop_id']}.json"
            if not annotation_path.is_file():
                continue
            annotation = load_json(annotation_path)
            if not annotation.get("reviewed") or annotation.get("unjudgeable"):
                continue
            source_task_id = str(source.get("source_task_id") or source.get("task_id") or "")
            if source_task_id in quarantined_tasks:
                reason = quarantined_tasks[source_task_id]
                training_exclusions[reason] = training_exclusions.get(reason, 0) + 1
                continue
            if (
                annotation.get("training_eligible") is False
                or source.get("training_eligible") is False
            ):
                reason = str(
                    annotation.get("training_exclusion_reason")
                    or source.get("training_exclusion_reason")
                    or "explicitly_ineligible"
                )
                training_exclusions[reason] = training_exclusions.get(reason, 0) + 1
                continue
            if manifest.get("version") == "liushanmen-pending-target-tray-review-v1":
                expected_policy = "shallow-aspect-width-context-v2"
                geometry = source.get("review_crop_geometry") or {}
                if (
                    manifest.get("review_crop_policy_version") != expected_policy
                    or geometry.get("policy_version") != expected_policy
                ):
                    raise RuntimeError(
                        "pending review crop cannot enter training without the resolution-independent "
                        "physical-context policy"
                    )
                if (
                    geometry.get("shallow_by_aspect_ratio") is True
                    and float(geometry.get("crop_height_to_tray_width") or 0.0) < 0.90
                ):
                    raise RuntimeError(
                        "pending shallow review crop cannot enter training with incomplete physical context"
                    )
            if (
                authoritative_photo_verdict
                and annotation.get("missing_confirmed_by_human") is True
                and source.get("human_qc_verdict") == "NO_DEFECT"
            ):
                reason = "crop_missing_conflicts_with_human_verified_full_photo_normal"
                training_exclusions[reason] = training_exclusions.get(reason, 0) + 1
                continue
            image = Path(source["image"])
            if not image.is_file() or sha256_file(image) != source["image_sha256"]:
                raise RuntimeError(f"queue image drift: {image}")
            task_id = str(source.get("source_task_id") or source.get("task_id") or source.get("source_photo_id") or source["crop_id"])
            annotation_sha = sha256_file(annotation_path)
            missing_class = isolated_missing_class(annotation)
            rows.append({"source": source, "annotation": annotation, "image": image, "task_id": task_id,
                         "annotation_sha256": annotation_sha, "isolated_missing_class": missing_class})
            identity_rows.append({
                "crop": str(source["crop_id"]),
                "image": source["image_sha256"],
                "annotation": annotation_sha,
                "task": task_id,
            })
    minimum = int(config.get("training", {}).get("min_reviewed_images", 100))
    if len(rows) < minimum:
        raise RuntimeError(f"training gate: {len(rows)} reviewed images < {minimum}")
    identity: dict[str, Any] = {
        "schema": "effective-training-rows-v2",
        "rows": sorted(identity_rows, key=lambda row: (row["task"], row["crop"])),
    }
    # Preserve the established dataset ID when weighting is disabled.  This
    # prevents a code-only rollout from triggering an accidental retrain.
    if class_repeat_overrides:
        identity |= {
            "schema": "effective-training-rows-v4",
            "isolated_missing_repeat_by_class": missing_repeats,
            "isolated_missing_strategy": ISOLATED_MISSING_CLASS_REPEAT_STRATEGY,
        }
    elif missing_repeat > 1:
        identity |= {
            "schema": "effective-training-rows-v3",
            "isolated_missing_repeat": missing_repeat,
            "isolated_missing_strategy": ISOLATED_MISSING_REPEAT_STRATEGY,
        }
    dataset_id = "label-" + hashlib.sha256(stable_json(identity)).hexdigest()[:12]
    out = root / "datasets" / dataset_id
    exclusion_receipt = {
        "total": sum(training_exclusions.values()),
        "reasons": training_exclusions,
    }
    if out.exists():
        existing = load_json(out / "manifest.json")
        # Excluded review metadata is deliberately outside dataset identity.
        # Return current audit fields without mutating or retraining the
        # existing immutable effective dataset.
        existing["source_manifest_sha256"] = manifest_hashes
        existing["training_exclusions"] = exclusion_receipt
        return existing
    for split in ("train", "val"):
        (out / "images" / split).mkdir(parents=True)
        (out / "labels" / split).mkdir(parents=True)
    counts = {
        "train": 0, "val": 0, "white_boxes": 0, "color_boxes": 0,
        "unique_train": 0, "unique_val": 0,
        "isolated_missing_unique": 0,
        "isolated_missing_train_unique": 0,
        "isolated_missing_val_unique": 0,
        "isolated_missing_train_copies": 0,
        "isolated_missing_train_copies_by_class": {
            class_name: 0 for class_name in LABEL_CLASS_IDS
        },
    }
    manifest_rows = []
    for item in rows:
        split = deterministic_split(item["task_id"], validation_percent)
        crop_id = str(item["source"]["crop_id"])
        lines = [yolo_line(box) for box in item["annotation"].get("boxes") or []]
        missing_class = item["isolated_missing_class"]
        repeat = missing_repeats[missing_class] if split == "train" and missing_class else 1
        counts[f"unique_{split}"] += 1
        if missing_class:
            counts["isolated_missing_unique"] += 1
            counts[f"isolated_missing_{split}_unique"] += 1
        for repeat_index in range(repeat):
            sample_id = crop_id if repeat_index == 0 else f"{crop_id}__missing_r{repeat_index + 1:02d}"
            image_out = out / "images" / split / f"{sample_id}{item['image'].suffix.lower()}"
            label_out = out / "labels" / split / f"{sample_id}.txt"
            shutil.copy2(item["image"], image_out)
            label_out.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
            counts[split] += 1
            counts["white_boxes"] += sum(1 for box in item["annotation"].get("boxes") or [] if int(box["c"]) == 0)
            counts["color_boxes"] += sum(1 for box in item["annotation"].get("boxes") or [] if int(box["c"]) == 1)
            if repeat_index > 0:
                counts["isolated_missing_train_copies"] += 1
                counts["isolated_missing_train_copies_by_class"][missing_class] += 1
            manifest_rows.append({
                "crop_id": crop_id, "sample_id": sample_id,
                "task_id": item["task_id"], "split": split,
                "source_sha256": item["source"]["image_sha256"],
                "image": str(image_out), "label": str(label_out),
                "annotation_sha256": item["annotation_sha256"],
                "isolated_missing_class": missing_class,
                "sample_role": "original" if repeat_index == 0 else "isolated_missing_repeat",
            })
    if min(counts["train"], counts["val"], counts["white_boxes"], counts["color_boxes"]) <= 0:
        raise RuntimeError(f"dataset coverage gate failed: {counts}")
    yaml_path = out / "data.yaml"
    yaml_path.write_text(
        f"path: {out.as_posix()}\ntrain: images/train\nval: images/val\nnames:\n  0: white_label\n  1: color_label\n",
        encoding="utf-8",
    )
    manifest = {
        "version": VERSION, "dataset_id": dataset_id, "created_at": utc_now(),
        "train_only": True, "protected_holdout_included": False,
        "task_level_split": True, "counts": counts, "rows": manifest_rows,
        "isolated_missing_supervision": {
            "strategy": (
                ISOLATED_MISSING_CLASS_REPEAT_STRATEGY
                if class_repeat_overrides else ISOLATED_MISSING_REPEAT_STRATEGY
            ),
            "train_repeat": missing_repeat,
            "train_repeat_by_class": missing_repeats,
            "validation_repeated": False,
            "requires_human_confirmation": True,
            "requires_single_complementary_box": True,
        },
        "source_manifest_sha256": manifest_hashes,
        "training_exclusions": exclusion_receipt,
        "data_yaml": str(yaml_path),
    }
    write_json(out / "manifest.json", manifest)
    return manifest


def train_candidate(config: dict[str, Any], dataset: dict[str, Any]) -> dict[str, Any]:
    training = config["training"]
    base_model = Path(os.path.expandvars(training["base_model"]))
    if not base_model.is_file():
        raise FileNotFoundError(base_model)
    run_id = f"{dataset['dataset_id']}-{dt.datetime.now().strftime('%Y%m%d-%H%M%S')}"
    root = Path(config["runtime_root"])
    os.environ["YOLO_OFFLINE"] = "true"
    try:
        from ultralytics import YOLO
    except ImportError as exc:
        raise RuntimeError("ultralytics is required for training") from exc
    imgsz = int(training.get("imgsz", 640))
    train_options: dict[str, Any] = {
        "data": dataset["data_yaml"],
        "epochs": int(training.get("epochs", 80)),
        "imgsz": imgsz,
        "batch": int(training.get("batch", 8)),
        "device": training.get("device", 0),
        "workers": int(training.get("workers", 0)),
        "project": str(root / "runs"),
        "name": run_id,
        "exist_ok": False,
        "seed": int(training.get("seed", 20260810)),
        "deterministic": True,
        "patience": int(training.get("patience", 20)),
        "pretrained": False,
        "amp": False,
        "verbose": True,
    }
    optional_training_options = {
        "optimizer": str,
        "lr0": float,
        "lrf": float,
        "weight_decay": float,
        "warmup_epochs": float,
        "warmup_bias_lr": float,
        "freeze": int,
        "close_mosaic": int,
        "mosaic": float,
        "mixup": float,
        "copy_paste": float,
        "degrees": float,
        "translate": float,
        "scale": float,
        "shear": float,
        "perspective": float,
        "fliplr": float,
        "flipud": float,
        "hsv_h": float,
        "hsv_s": float,
        "hsv_v": float,
        "cache": bool,
        "save_period": int,
        "plots": bool,
    }
    for key, converter in optional_training_options.items():
        if key in training and training[key] is not None:
            value = training[key]
            if converter is bool and not isinstance(value, bool):
                raise ValueError(f"training option {key} must be a boolean")
            train_options[key] = converter(value)
    if "lr0" in train_options:
        bias_lr = train_options.get("warmup_bias_lr", train_options["lr0"])
        if bias_lr < 0:
            raise ValueError("training option warmup_bias_lr must be non-negative")
        if bias_lr > train_options["lr0"]:
            raise ValueError(
                "training option warmup_bias_lr must not exceed lr0 during incremental fine-tuning"
            )
        train_options["warmup_bias_lr"] = bias_lr
    elif "warmup_bias_lr" in train_options:
        raise ValueError("training option warmup_bias_lr requires an explicit lr0")
    model = YOLO(str(base_model))
    model.train(**train_options)
    best = root / "runs" / run_id / "weights" / "best.pt"
    if not best.is_file():
        raise RuntimeError("training completed without best.pt")
    exported = YOLO(str(best)).export(
        format="onnx", imgsz=imgsz, opset=12,
        simplify=True, nms=True, dynamic=False, conf=float(training.get("export_floor", 0.05)),
    )
    exported_path = Path(exported)
    artifact_dir = root / "models" / "registry" / run_id
    artifact_dir.mkdir(parents=True)
    onnx = artifact_dir / "label.onnx"
    shutil.copy2(exported_path, onnx)
    parity_mismatches = verify_export_parity(
        best, onnx, Path(dataset["data_yaml"]).parent / "images" / "val",
        float(config.get("evaluation", {}).get("threshold", 0.20)),
        imgsz,
    )
    validation_dir = Path(dataset["data_yaml"]).parent / "images" / "val"
    baseline_presence_threshold = float(
        training.get(
            "baseline_presence_threshold",
            config.get("evaluation", {}).get("threshold", 0.20),
        )
    )
    candidate_presence_threshold = float(
        training.get("candidate_presence_threshold", baseline_presence_threshold)
    )
    presence_ownership_pad_ratio = float(
        config.get("evaluation", {}).get(
            "presence_ownership_pad_ratio", DEFAULT_TRAY_PAD_RATIO,
        )
    )
    for threshold_name, threshold in (
        ("baseline_presence_threshold", baseline_presence_threshold),
        ("candidate_presence_threshold", candidate_presence_threshold),
    ):
        if not 0.0 < threshold <= 1.0:
            raise ValueError(f"{threshold_name} must be in (0, 1]")
    label_presence_validation = {
        "validation_dir": str(validation_dir),
        "protected_holdout_included": False,
        "baseline": evaluate_label_presence(
            base_model, validation_dir, baseline_presence_threshold, imgsz,
            presence_ownership_pad_ratio,
        ),
        "candidate": evaluate_label_presence(
            onnx, validation_dir, candidate_presence_threshold, imgsz,
            presence_ownership_pad_ratio,
        ),
    }
    result = {
        "version": VERSION, "model_id": run_id, "created_at": utc_now(),
        "dataset_id": dataset["dataset_id"], "base_model": str(base_model),
        "base_model_sha256": sha256_file(base_model), "best_pt": str(best),
        "artifact": str(onnx), "artifact_sha256": sha256_file(onnx),
        "onnx_parity_mismatches": parity_mismatches, "status": "candidate",
        "offline_only": True, "amp_enabled": False,
        "label_presence_validation": label_presence_validation,
        "training": {
            key: train_options[key]
            for key in (
                "epochs", "imgsz", "batch", "device", "workers", "seed", "patience",
                "optimizer", "lr0", "lrf", "weight_decay", "warmup_epochs", "warmup_bias_lr", "freeze",
                "close_mosaic", "mosaic", "mixup", "copy_paste", "degrees", "translate",
                "scale", "shear", "perspective", "fliplr", "flipud", "hsv_h", "hsv_s",
                "hsv_v", "cache", "save_period", "plots",
            )
            if key in train_options
        },
    }
    write_json(artifact_dir / "training-receipt.json", result)
    return result


def verify_export_parity(
    best_pt: Path, onnx: Path, validation_dir: Path, threshold: float, imgsz: int = 640,
) -> int:
    from ultralytics import YOLO

    images = sorted(
        path for path in validation_dir.iterdir()
        if path.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"}
    )[:50]
    if not images:
        raise RuntimeError("no validation images for PT/ONNX parity")

    def signatures(model_path: Path) -> list[tuple[bool, bool]]:
        model = YOLO(str(model_path), task="detect")
        output: list[tuple[bool, bool]] = []
        for image in images:
            result = model.predict(
                str(image), imgsz=imgsz, conf=0.001, iou=0.45,
                verbose=False, device="cpu", rect=False,
            )[0]
            white = color = 0.0
            if result.boxes is not None and len(result.boxes):
                for cls, conf in zip(result.boxes.cls.cpu().tolist(), result.boxes.conf.cpu().tolist()):
                    if int(cls) == 0:
                        white = max(white, float(conf))
                    elif int(cls) == 1:
                        color = max(color, float(conf))
            output.append((white >= threshold, color >= threshold))
        return output

    return sum(left != right for left, right in zip(signatures(best_pt), signatures(onnx)))


def evaluate_label_presence(
    model_path: Path, validation_dir: Path, threshold: float, imgsz: int = 640,
    ownership_pad_ratio: float = DEFAULT_TRAY_PAD_RATIO,
) -> dict[str, Any]:
    from ultralytics import YOLO

    if not 0.0 <= ownership_pad_ratio <= 0.5:
        raise ValueError("label-presence ownership pad ratio must be between 0 and 0.5")
    ownership_margin = ownership_pad_ratio / (1.0 + 2.0 * ownership_pad_ratio)

    def owned_center(x: float, y: float) -> bool:
        return (
            ownership_margin <= x <= 1.0 - ownership_margin
            and ownership_margin <= y <= 1.0 - ownership_margin
        )

    images = sorted(
        path for path in validation_dir.iterdir()
        if path.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"}
    )
    if not images:
        raise RuntimeError("no validation images for label-presence gate")
    labels_dir = validation_dir.parent.parent / "labels" / validation_dir.name
    if not labels_dir.is_dir():
        raise RuntimeError(f"validation labels directory is missing: {labels_dir}")
    model = YOLO(str(model_path), task="detect")
    classes = {
        0: {"name": "white_label", "tp": 0, "fp": 0, "fn": 0, "tn": 0},
        1: {"name": "color_label", "tp": 0, "fp": 0, "fn": 0, "tn": 0},
    }
    exact_matches = 0
    mismatches: list[dict[str, Any]] = []
    dropped_predictions = {0: 0, 1: 0}
    dropped_prediction_details: list[dict[str, Any]] = []
    for image in images:
        expected = {0: False, 1: False}
        label_path = labels_dir / f"{image.stem}.txt"
        if label_path.is_file():
            for line_number, raw_line in enumerate(
                label_path.read_text(encoding="utf-8").splitlines(), start=1,
            ):
                line = raw_line.strip()
                if not line:
                    continue
                try:
                    parts = line.split()
                    class_id = int(parts[0])
                    float(parts[1])
                    float(parts[2])
                except (ValueError, IndexError) as exc:
                    raise RuntimeError(
                        f"invalid validation label at {label_path}:{line_number}"
                    ) from exc
                if class_id in expected:
                    # Human boxes already describe labels belonging to the
                    # reviewed target tray.  Old crops do not retain exact
                    # source crop/tray geometry and can be clipped at image
                    # boundaries, so a fixed padding margin must never erase
                    # human truth.
                    expected[class_id] = True
        result = model.predict(
            str(image), imgsz=imgsz, conf=0.001, iou=0.45,
            verbose=False, device="cpu", rect=False,
        )[0]
        confidence = {0: 0.0, 1: 0.0}
        if result.boxes is not None and len(result.boxes):
            for class_id, score, box in zip(
                result.boxes.cls.cpu().tolist(), result.boxes.conf.cpu().tolist(),
                result.boxes.xyxyn.cpu().tolist(),
            ):
                normalized_class = int(class_id)
                if normalized_class not in confidence:
                    continue
                if not isinstance(box, list) or len(box) != 4:
                    raise RuntimeError("label-presence prediction box is not normalized xyxy")
                center_x = (float(box[0]) + float(box[2])) / 2.0
                center_y = (float(box[1]) + float(box[3])) / 2.0
                # A prediction for a human-present class is allowed anywhere
                # in the reviewed crop.  For a human-absent class, border
                # predictions are audited as neighbouring-tray context and
                # excluded from false-positive presence scoring.  The full
                # production replay remains authoritative for source-image
                # tray ownership.
                if not expected[normalized_class] and not owned_center(center_x, center_y):
                    if float(score) >= threshold:
                        dropped_predictions[normalized_class] += 1
                        dropped_prediction_details.append({
                            "image": image.name,
                            "class": classes[normalized_class]["name"],
                            "confidence": float(score),
                            "center": [center_x, center_y],
                        })
                    continue
                confidence[normalized_class] = max(
                    confidence[normalized_class], float(score),
                )
        predicted = {
            class_id: confidence[class_id] >= threshold for class_id in confidence
        }
        if predicted == expected:
            exact_matches += 1
        else:
            mismatches.append({
                "image": image.name,
                "expected": {classes[key]["name"]: value for key, value in expected.items()},
                "predicted": {classes[key]["name"]: value for key, value in predicted.items()},
                "confidence": {classes[key]["name"]: confidence[key] for key in confidence},
            })
        for class_id, counters in classes.items():
            if expected[class_id] and predicted[class_id]:
                counters["tp"] += 1
            elif expected[class_id]:
                counters["fn"] += 1
            elif predicted[class_id]:
                counters["fp"] += 1
            else:
                counters["tn"] += 1
    return {
        "model": str(model_path),
        "model_sha256": sha256_file(model_path),
        "threshold": threshold,
        "imgsz": imgsz,
        "ownership": {
            "rule": "human_truth_authoritative_absent_class_prediction_center_inside_target",
            "pad_ratio": ownership_pad_ratio,
            "normalized_margin": ownership_margin,
            "dropped_predictions_at_or_above_threshold": {
                classes[class_id]["name"]: count
                for class_id, count in dropped_predictions.items()
            },
            "dropped_prediction_details": dropped_prediction_details,
        },
        "total_images": len(images),
        "exact_matches": exact_matches,
        "classes": {counters["name"]: {
            key: value for key, value in counters.items() if key != "name"
        } for counters in classes.values()},
        "mismatches": mismatches,
    }


def evaluate_label_presence_gate(model: dict[str, Any]) -> dict[str, Any]:
    validation = model.get("label_presence_validation") or {}
    baseline = validation.get("baseline") or {}
    candidate = validation.get("candidate") or {}
    errors: list[str] = []
    baseline_total = int(baseline.get("total_images", 0))
    candidate_total = int(candidate.get("total_images", 0))
    if baseline_total <= 0 or candidate_total != baseline_total:
        errors.append("label-presence validation set is missing or inconsistent")
    baseline_ownership = baseline.get("ownership") or {}
    candidate_ownership = candidate.get("ownership") or {}
    ownership_contract = ("rule", "pad_ratio", "normalized_margin")
    if any(field not in baseline_ownership for field in ownership_contract) or any(
        baseline_ownership.get(field) != candidate_ownership.get(field)
        for field in ownership_contract
    ):
        errors.append("label-presence ownership contract is missing or inconsistent")
    if int(candidate.get("exact_matches", -1)) < int(baseline.get("exact_matches", 0)):
        errors.append("label-presence exact accuracy regressed")
    baseline_classes = baseline.get("classes") or {}
    candidate_classes = candidate.get("classes") or {}
    for class_name in ("white_label", "color_label"):
        baseline_class = baseline_classes.get(class_name) or {}
        candidate_class = candidate_classes.get(class_name) or {}
        for metric in ("fp", "fn"):
            if int(candidate_class.get(metric, baseline_total + 1)) > int(
                baseline_class.get(metric, baseline_total)
            ):
                errors.append(f"label-presence {class_name} {metric} regressed")
    return {
        "version": VERSION,
        "model_id": model["model_id"],
        "evaluated_at": utc_now(),
        "passed": not errors,
        "errors": errors,
        "validation": validation,
    }


def evaluate_candidate(config: dict[str, Any], model: dict[str, Any]) -> tuple[Path, dict[str, Any]]:
    evaluation = config["evaluation"]
    script = Path(__file__).resolve().parent / "evaluate_candidate.py"
    output = Path(model["artifact"]).parent / "evaluation-metrics.json"
    command = [
        sys.executable, str(script), "--repo-root", str(Path(os.path.expandvars(evaluation["repo_root"])).resolve()),
        "--tray", str(Path(os.path.expandvars(evaluation["tray_model"]))),
        "--production-label", str(Path(os.path.expandvars(evaluation["production_label_model"]))),
        "--candidate-label", str(Path(model["artifact"])),
        "--threshold", str(float(evaluation.get("threshold", 0.20))),
        "--onnx-parity-mismatches", str(int(model["onnx_parity_mismatches"])),
        "--output", str(output),
    ]
    for manifest in evaluation.get("manifests", []):
        command.extend(["--manifest", str(Path(os.path.expandvars(manifest)))])
    truth_adjudication = evaluation.get("truth_adjudication")
    if truth_adjudication:
        command.extend([
            "--truth-adjudication",
            str(Path(os.path.expandvars(truth_adjudication)).resolve()),
        ])
    result = subprocess.run(command, text=True, encoding="utf-8", errors="replace", timeout=7200)
    if result.returncode != 0 or not output.is_file():
        raise RuntimeError(f"candidate evaluation failed with exit code {result.returncode}")
    return output, load_json(output)


def persist_candidate(state: State, model: dict[str, Any], status_value: str, metrics: dict[str, Any] | None = None) -> None:
    with state.db:
        state.db.execute(
            """INSERT INTO models(model_id,status,artifact_path,artifact_sha256,metrics_json,created_at,deployed_at)
               VALUES(?,?,?,?,?,?,NULL) ON CONFLICT(model_id) DO UPDATE SET status=excluded.status,
               artifact_path=excluded.artifact_path,artifact_sha256=excluded.artifact_sha256,
               metrics_json=excluded.metrics_json""",
            (model["model_id"], status_value, model["artifact"], model["artifact_sha256"],
             json.dumps(metrics, ensure_ascii=False) if metrics is not None else None, model["created_at"]),
        )


def mine_next_queue(config: dict[str, Any], state: State) -> dict[str, Any]:
    mining = config.get("mining", {})
    if not mining.get("enabled", True):
        return {"created": False, "reason": "mining disabled"}
    root = Path(config["runtime_root"])
    evaluation = config["evaluation"]
    script = Path(__file__).resolve().parent / "mine_queue.py"
    command = [
        sys.executable, str(script),
        "--repo-root", str(Path(os.path.expandvars(evaluation["repo_root"])).resolve()),
        "--database", str(state.path),
        "--tray", str(Path(os.path.expandvars(evaluation["tray_model"]))),
        "--label", str(Path(os.path.expandvars(evaluation["production_label_model"]))),
        "--protected-holdout", str(Path(os.path.expandvars(evaluation["manifests"][0]))),
        "--output-root", str(root / "queues"),
        "--max-queue", str(int(mining.get("max_queue", 120))),
        "--max-photos-scanned", str(int(mining.get("max_photos_scanned", 300))),
        "--max-per-sku", str(int(mining.get("max_per_sku", 30))),
        "--max-per-photo", str(int(mining.get("max_per_photo", 2))),
    ]
    manifests = [queue_root / "manifest.json" for queue_root in discover_queue_roots(config)]
    manifests.extend(quarantine["manifest"] for quarantine in training_quarantines(config))
    for manifest in manifests:
        command.extend(["--existing-manifest", str(manifest)])
    result = subprocess.run(
        command, text=True, encoding="utf-8", errors="replace",
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=28800,
    )
    if result.returncode != 0:
        raise RuntimeError(f"active-learning mining failed: {result.stderr.strip() or result.stdout.strip()}")
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"miner returned invalid receipt: {result.stdout[-1000:]}") from exc


def evaluate_gate(
    config: dict[str, Any], model: dict[str, Any], metrics_path: Path,
    output_path: Path | None = None,
) -> dict[str, Any]:
    metrics = load_json(metrics_path)
    artifact = Path(model["artifact"])
    errors: list[str] = []
    if sha256_file(artifact) != model["artifact_sha256"] or metrics.get("artifact_sha256") != model["artifact_sha256"]:
        errors.append("evaluated artifact hash does not match candidate")
    gate = config["promotion_gates"]
    baseline = metrics.get("baseline") or {}
    candidate = metrics.get("candidate") or {}
    configured_truth = (config.get("evaluation") or {}).get("truth_adjudication")
    if configured_truth:
        truth_path = Path(os.path.expandvars(str(configured_truth))).resolve()
        truth_metrics = metrics.get("truth_adjudication")
        if not truth_path.is_file():
            errors.append("configured truth adjudication is missing")
        elif not isinstance(truth_metrics, dict):
            errors.append("evaluation did not bind the configured truth adjudication")
        elif (
            truth_metrics.get("path") != str(truth_path)
            or truth_metrics.get("sha256") != sha256_file(truth_path)
        ):
            errors.append("evaluated truth adjudication does not match configuration")
        elif truth_metrics.get("promotion_ready") is not True:
            errors.append("truth adjudication is evaluation-only and not promotion-ready")
    defect_total = int(candidate.get("defect_total", 0))
    if defect_total < int(gate.get("min_independent_defects", 7)):
        errors.append("insufficient independent real defects")
    if int(candidate.get("defect_hits", -1)) < int(baseline.get("defect_hits", 0)):
        errors.append("real defect recall regressed")
    if int(candidate.get("defect_hits", 0)) < int(gate.get("min_defect_hits", 0)):
        errors.append("minimum protected-defect hit gate not met")
    for group_name in gate.get("required_full_recall_groups", []):
        group = (candidate.get("groups") or {}).get(group_name) or {}
        if int(group.get("defect_total", 0)) <= 0 or int(group.get("defect_hits", -1)) != int(group.get("defect_total", 0)):
            errors.append(f"required defect group did not reach full recall: {group_name}")
    baseline_fp = int(baseline.get("false_flags", 0))
    candidate_fp = int(candidate.get("false_flags", baseline_fp + 1))
    required_improvement = float(gate.get("min_false_flag_improvement", 0.05))
    if baseline_fp == 0 and candidate_fp > 0:
        errors.append("false flags regressed from a zero baseline")
    elif baseline_fp > 0 and candidate_fp > baseline_fp * (1.0 - required_improvement):
        errors.append("false-flag improvement gate not met")
    if int(metrics.get("onnx_parity_mismatches", -1)) != 0:
        errors.append("PT/ONNX parity gate failed")
    if not metrics.get("production_pipeline_replay"):
        errors.append("production pipeline replay missing")
    baseline_latency = float(baseline.get("p95_latency_ms", 0))
    candidate_latency = float(candidate.get("p95_latency_ms", 1e12))
    max_regression = float(gate.get("max_latency_regression", 0.15))
    if candidate_latency > float(gate.get("max_p95_latency_ms", 8000)):
        errors.append("latency gate failed")
    if baseline_latency > 0 and candidate_latency > baseline_latency * (1.0 + max_regression):
        errors.append("latency regressed against production")
    result = {
        "version": VERSION, "model_id": model["model_id"], "evaluated_at": utc_now(),
        "passed": not errors, "errors": errors, "metrics": metrics,
    }
    write_json(output_path or artifact.parent / "promotion-gate.json", result)
    return result


def ssh_run(host: str, command: str, timeout: int = 180, check: bool = True) -> str:
    result = subprocess.run(
        ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15", host, command],
        text=True, encoding="utf-8", errors="replace", stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        timeout=timeout,
    )
    if check and result.returncode != 0:
        raise RuntimeError(f"ssh failed ({result.returncode}): {result.stdout.strip()}")
    return result.stdout.strip()


def validate_operator_recall_override(
    gate: dict[str, Any], token: str | None, reason: str | None,
) -> dict[str, Any] | None:
    if gate.get("passed"):
        if token or reason:
            raise RuntimeError("operator recall override is only valid for a failed promotion gate")
        return None
    if token != OPERATOR_RECALL_OVERRIDE_TOKEN:
        raise RuntimeError("refusing deployment: promotion gate did not pass")
    normalized_reason = (reason or "").strip()
    if len(normalized_reason) < 20:
        raise RuntimeError("operator recall override requires a specific reason of at least 20 characters")
    errors = gate.get("errors")
    if not isinstance(errors, list) or not errors:
        raise RuntimeError("operator recall override requires recorded promotion-gate errors")
    if any(not isinstance(error, str) or not error.startswith(WAIVABLE_RECALL_ERROR_PREFIX) for error in errors):
        raise RuntimeError("operator recall override cannot waive non-recall promotion-gate errors")
    return {
        "accepted": True,
        "scope": "incomplete-required-group-recall-only",
        "reason": normalized_reason,
        "waived_errors": list(errors),
    }


def deploy_candidate(
    config: dict[str, Any], state: State, model: dict[str, Any], gate: dict[str, Any],
    *, operator_override_token: str | None = None, operator_override_reason: str | None = None,
) -> dict[str, Any]:
    deployment = config["deployment"]
    operator_override = validate_operator_recall_override(
        gate, operator_override_token, operator_override_reason,
    )
    gate_metrics = gate.get("metrics")
    if gate.get("model_id") != model.get("model_id"):
        raise RuntimeError("promotion-gate model id does not match candidate receipt")
    if not isinstance(gate_metrics, dict) or gate_metrics.get("artifact_sha256") != model.get("artifact_sha256"):
        raise RuntimeError("promotion-gate artifact hash does not match candidate receipt")
    if not deployment.get("auto_deploy"):
        return {"status": "ready-not-deployed", "reason": "auto_deploy is disabled"}
    if deployment.get("confirm_token") != "YES-PROD":
        raise RuntimeError("auto deployment requires confirm_token=YES-PROD")
    host = str(deployment["ssh_host"])
    remote = str(deployment["remote_model_path"])
    service = str(deployment["service"])
    if not SAFE_REMOTE_PATH.fullmatch(remote) or not SAFE_SERVICE.fullmatch(service):
        raise ValueError("unsafe deployment target")
    artifact = Path(model["artifact"])
    local_sha = sha256_file(artifact)
    if local_sha != model["artifact_sha256"]:
        raise RuntimeError("candidate artifact drift")
    expected_current = state.get_meta("production_model_sha256")
    current = ssh_run(host, f"sha256sum {shlex.quote(remote)} | cut -d' ' -f1")
    if not expected_current:
        raise RuntimeError("production model is not registered locally")
    if current != expected_current:
        raise RuntimeError(f"production model drift: live={current} registered={expected_current}")
    if current == local_sha:
        return {"status": "no-op", "live_sha256": current}
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d_%H%M%S")
    backup = f"{remote}.bak.{stamp}"
    staged = f"{remote}.staged.{stamp}"
    ssh_run(host, f"cp -p {shlex.quote(remote)} {shlex.quote(backup)}")
    result = subprocess.run(
        ["scp", "-o", "BatchMode=yes", str(artifact), f"{host}:{staged}"],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=900,
    )
    if result.returncode != 0:
        raise RuntimeError(f"upload failed: {result.stdout.strip()}")
    staged_sha = ssh_run(host, f"sha256sum {shlex.quote(staged)} | cut -d' ' -f1")
    if staged_sha != local_sha:
        ssh_run(host, f"rm -f {shlex.quote(staged)}", check=False)
        raise RuntimeError("staged model hash mismatch")
    ssh_run(host, f"mv {shlex.quote(staged)} {shlex.quote(remote)} && systemctl restart {shlex.quote(service)}")
    health_url = str(deployment["health_url"])
    status = health = live = ""
    succeeded = False
    for _ in range(12):
        status = ssh_run(host, f"systemctl is-active {shlex.quote(service)}", check=False)
        health = ssh_run(host, f"curl -s -m 10 -o /dev/null -w '%{{http_code}}' {shlex.quote(health_url)} || true", check=False)
        live = ssh_run(host, f"sha256sum {shlex.quote(remote)} | cut -d' ' -f1", check=False)
        succeeded = status == "active" and health.endswith("200") and live == local_sha
        if succeeded:
            break
        time.sleep(3)
    rolled_back = False
    rollback_live = None
    rollback_healthy = None
    if not succeeded:
        ssh_run(host, f"cp {shlex.quote(backup)} {shlex.quote(remote)} && systemctl restart {shlex.quote(service)}", check=False)
        rolled_back = True
        time.sleep(8)
        rollback_live = ssh_run(host, f"sha256sum {shlex.quote(remote)} | cut -d' ' -f1", check=False)
        rollback_status = ssh_run(host, f"systemctl is-active {shlex.quote(service)}", check=False)
        rollback_health = ssh_run(host, f"curl -s -m 10 -o /dev/null -w '%{{http_code}}' {shlex.quote(health_url)} || true", check=False)
        rollback_healthy = rollback_live == current and rollback_status == "active" and rollback_health.endswith("200")
    receipt = {
        "version": VERSION, "stage": "deploy", "deployed_at": utc_now(),
        "model_id": model["model_id"], "artifact_sha256": local_sha,
        "previous_sha256": current, "backup": backup, "service_status": status,
        "health_http": health, "live_sha256": live, "succeeded": succeeded,
        "promotion_gate_passed": bool(gate.get("passed")),
        "promotion_gate_errors": list(gate.get("errors") or []),
        "operator_override": operator_override,
        "rolled_back": rolled_back, "production_business_writes": 0,
        "rollback_live_sha256": rollback_live, "rollback_healthy": rollback_healthy,
    }
    path = Path(config["runtime_root"]) / "receipts" / f"deploy-{stamp}.json"
    write_json(path, receipt)
    if not succeeded:
        raise RuntimeError(f"deployment failed and rollback was attempted; receipt={path}")
    state.set_meta("production_model_sha256", local_sha)
    return receipt | {"receipt": str(path)}


def register_production(config: dict[str, Any], state: State, artifact: Path, model_id: str) -> dict[str, Any]:
    if not artifact.is_file():
        raise FileNotFoundError(artifact)
    digest = sha256_file(artifact)
    deployment = config.get("deployment") or {}
    if deployment:
        remote = str(deployment["remote_model_path"])
        if not SAFE_REMOTE_PATH.fullmatch(remote):
            raise ValueError("unsafe production model path")
        live = ssh_run(str(deployment["ssh_host"]), f"sha256sum {shlex.quote(remote)} | cut -d' ' -f1")
        if live != digest:
            raise RuntimeError(f"local production artifact does not match live server: local={digest} live={live}")
    state.set_meta("production_model_sha256", digest)
    with state.db:
        state.db.execute(
            """INSERT INTO models(model_id,status,artifact_path,artifact_sha256,metrics_json,created_at,deployed_at)
               VALUES(?,?,?,?,?,?,?) ON CONFLICT(model_id) DO UPDATE SET artifact_path=excluded.artifact_path,
               artifact_sha256=excluded.artifact_sha256,status='production'""",
            (model_id, "production", str(artifact.resolve()), digest, None, utc_now(), utc_now()),
        )
    return {"model_id": model_id, "status": "production", "artifact": str(artifact.resolve()), "sha256": digest}


def status(config: dict[str, Any], state: State) -> dict[str, Any]:
    root = Path(config["runtime_root"])
    mark = root / "attention" / "MARK-NEEDS-ANNOTATION.json"
    queues = [dict(row) for row in state.db.execute("SELECT * FROM queue_snapshots ORDER BY queue_id")]
    return {
        "version": VERSION, "runtime_root": str(root),
        "watermark": {"reviewed_at": state.get_meta("watermark"), "photo_id": state.get_meta("watermark_photo_id")},
        "production_model_sha256": state.get_meta("production_model_sha256"),
        "attention_required": mark.is_file(), "mark": str(mark) if mark.is_file() else None,
        "queues": queues, "cloud_vl": config.get("cloud_vl", {}),
    }


def load_rows_manifest(path: Path) -> list[dict[str, Any]]:
    payload = load_json(path)
    rows = payload.get("records", payload) if isinstance(payload, dict) else payload
    if not isinstance(rows, list):
        raise ValueError("source manifest must be a list or contain records")
    return rows


def command_main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("init")
    collect_parser = sub.add_parser("collect")
    collect_parser.add_argument("--source-manifest", type=Path)
    sub.add_parser("scan-queues")
    sub.add_parser("build-dataset")
    register = sub.add_parser("register-production")
    register.add_argument("--artifact", required=True, type=Path)
    register.add_argument("--model-id", default="production-current")
    gate = sub.add_parser("evaluate-gate")
    gate.add_argument("--model-receipt", required=True, type=Path)
    gate.add_argument("--metrics", required=True, type=Path)
    deploy = sub.add_parser("deploy")
    deploy.add_argument("--model-receipt", required=True, type=Path)
    deploy.add_argument("--gate-receipt", required=True, type=Path)
    deploy.add_argument("--operator-override", choices=[OPERATOR_RECALL_OVERRIDE_TOKEN])
    deploy.add_argument("--operator-reason")
    sub.add_parser("status")
    cycle = sub.add_parser("cycle")
    cycle.add_argument("--skip-collect", action="store_true")
    cycle.add_argument("--queue-root", action="append", type=Path)
    cycle.add_argument("--preserve-attention-mark", action="store_true")
    cycle.add_argument("--skip-mining", action="store_true")
    args = parser.parse_args(argv)

    config = load_config(args.config.resolve())
    if args.command == "cycle":
        config = config_with_queue_roots(config, args.queue_root)
    root = Path(config["runtime_root"])
    state = State(root)
    try:
        init_layout(config, state)
        if args.command == "init":
            result = init_layout(config, state)
        elif args.command == "collect":
            rows = load_rows_manifest(args.source_manifest) if args.source_manifest else None
            result = collect(config, state, rows)
        elif args.command == "scan-queues":
            result = scan_queues(config, state)
        elif args.command == "build-dataset":
            result = build_dataset(config)
        elif args.command == "register-production":
            result = register_production(config, state, args.artifact.resolve(), args.model_id)
        elif args.command == "evaluate-gate":
            result = evaluate_gate(config, load_json(args.model_receipt), args.metrics.resolve())
        elif args.command == "deploy":
            result = deploy_candidate(
                config, state, load_json(args.model_receipt), load_json(args.gate_receipt),
                operator_override_token=args.operator_override,
                operator_override_reason=args.operator_reason,
            )
        elif args.command == "status":
            result = status(config, state)
        elif args.command == "cycle":
            with pipeline_lock(root):
                stages: dict[str, Any] = {"started_at": utc_now()}
                if not args.skip_collect:
                    stages["collect"] = collect(config, state)
                stages["queues"] = scan_queues(
                    config, state, manage_attention_mark=not args.preserve_attention_mark,
                )
                if stages["queues"]["pending_queues"]:
                    stages["status"] = "attention-required"
                    stages["finished_at"] = utc_now()
                    write_json(root / "receipts" / "latest-cycle.json", stages)
                    print(json.dumps(stages, ensure_ascii=False, indent=2))
                    return EXIT_ATTENTION_REQUIRED
                stages["dataset"] = build_dataset(config)
                if state.get_meta("last_trained_dataset_id") == stages["dataset"]["dataset_id"]:
                    if args.skip_mining:
                        stages["mining"] = {"status": "skipped-by-operator"}
                        stages["queues_after_mining"] = None
                        stages["status"] = "no-new-reviewed-data"
                    else:
                        stages["mining"] = mine_next_queue(config, state)
                        stages["queues_after_mining"] = scan_queues(
                            config, state, manage_attention_mark=not args.preserve_attention_mark,
                        )
                        stages["status"] = "attention-required" if stages["queues_after_mining"]["pending_queues"] else "no-new-reviewed-data"
                    stages["finished_at"] = utc_now()
                    write_json(root / "receipts" / "latest-cycle.json", stages)
                    print(json.dumps(stages, ensure_ascii=False, indent=2))
                    return 0
                stages["model"] = train_candidate(config, stages["dataset"])
                persist_candidate(state, stages["model"], "candidate")
                stages["presence_gate"] = evaluate_label_presence_gate(stages["model"])
                if not stages["presence_gate"]["passed"]:
                    stages["evaluation"] = {
                        "skipped": True,
                        "reason": "label-presence preflight regressed against baseline",
                    }
                    stages["gate"] = {
                        "version": VERSION,
                        "model_id": stages["model"]["model_id"],
                        "evaluated_at": utc_now(),
                        "passed": False,
                        "errors": stages["presence_gate"]["errors"],
                        "metrics": {
                            "artifact_sha256": stages["model"]["artifact_sha256"],
                            "label_presence_validation": stages["model"]["label_presence_validation"],
                            "production_pipeline_replay": False,
                        },
                    }
                    write_json(
                        Path(stages["model"]["artifact"]).parent / "promotion-gate.json",
                        stages["gate"],
                    )
                    persist_candidate(
                        state, stages["model"], "rejected", stages["gate"]["metrics"],
                    )
                    stages["status"] = "candidate-rejected-preflight"
                else:
                    metrics_path, stages["evaluation"] = evaluate_candidate(
                        config, stages["model"],
                    )
                    stages["gate"] = evaluate_gate(config, stages["model"], metrics_path)
                    if stages["gate"]["passed"]:
                        persist_candidate(state, stages["model"], "approved", stages["evaluation"])
                        stages["deployment"] = deploy_candidate(
                            config, state, stages["model"], stages["gate"],
                        )
                        stages["status"] = (
                            "deployed" if stages["deployment"].get("succeeded")
                            else stages["deployment"]["status"]
                        )
                    else:
                        persist_candidate(state, stages["model"], "rejected", stages["evaluation"])
                        stages["status"] = "candidate-rejected"
                state.set_meta("last_trained_dataset_id", stages["dataset"]["dataset_id"])
                if args.skip_mining:
                    stages["mining"] = {"status": "skipped-by-operator"}
                    stages["queues_after_mining"] = None
                else:
                    stages["mining"] = mine_next_queue(config, state)
                    stages["queues_after_mining"] = scan_queues(
                        config, state, manage_attention_mark=not args.preserve_attention_mark,
                    )
                stages["finished_at"] = utc_now()
                write_json(root / "receipts" / "latest-cycle.json", stages)
                result = stages
        else:  # pragma: no cover
            raise AssertionError(args.command)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    finally:
        state.close()


if __name__ == "__main__":
    raise SystemExit(command_main())
