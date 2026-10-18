"""Factory-owned photo preparation; frozen human evidence feeds the existing SOP.

No model, runner, command, local path or remote URL is accepted from the UI.
Original bytes are retained separately from the orientation-normalised preview.
"""
from __future__ import annotations

import base64
from contextlib import contextmanager
import hashlib
from io import BytesIO
import json
from pathlib import Path
import shutil
import sqlite3
from typing import Literal

from PIL import Image, ImageOps, UnidentifiedImageError
from pydantic import Field, StrictBool, StrictFloat

from .models import FactoryContext, StrictModel, TrainingProjectDraft, utc_now
from .store import CapacityLimitError

MAX_BYTES = 5 * 1024 * 1024
MAX_PHOTOS = 200
MAX_STORED_BYTES = 512 * 1024 * 1024
MAX_RENDERED_BYTES = 32 * 1024 * 1024
MAX_FACTORY_STORED_BYTES = 2 * 1024 * 1024 * 1024
MAX_GLOBAL_STORED_BYTES = 10 * 1024 * 1024 * 1024
MAX_FACTORY_PHOTO_PROJECTS = 50
MIN_FREE_BYTES = 2 * 1024 * 1024 * 1024
JOURNAL_BUFFER_BYTES = 64 * 1024 * 1024


def canonical(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")


def digest(value: object) -> str:
    return hashlib.sha256(canonical(value)).hexdigest()


class PreparationConflict(ValueError):
    pass


class RevisionRequest(StrictModel):
    expectedRevision: int = Field(ge=0)


class UploadPhoto(RevisionRequest):
    fileName: str = Field(min_length=1, max_length=200)
    contentBase64: str = Field(min_length=1, max_length=MAX_BYTES * 4 // 3 + 8)
    purpose: Literal["training", "acceptance"]
    captureGroup: str = Field(min_length=1, max_length=100)


class PhotoReference(StrictModel):
    photoId: str = Field(pattern=r"^photo-[0-9a-f]{24}$")


class Region(StrictModel):
    label: str = Field(min_length=1, max_length=80)
    x0: StrictFloat = Field(ge=0, le=1)
    y0: StrictFloat = Field(ge=0, le=1)
    x1: StrictFloat = Field(ge=0, le=1)
    y1: StrictFloat = Field(ge=0, le=1)


class ReviewPhoto(RevisionRequest, PhotoReference):
    verdict: Literal["good", "defect", "uncertain"]
    boxes: list[Region] = Field(max_length=100)
    transcription: str = Field(default="", max_length=1000)


class ConfirmStandard(RevisionRequest):
    passCriteria: str = Field(min_length=2, max_length=2000)
    failCriteria: str = Field(min_length=2, max_length=2000)
    maxMissRate: StrictFloat = Field(ge=0, le=100)
    maxFalseAlarmRate: StrictFloat = Field(ge=0, le=100)
    confirmed: StrictBool


class SubmitPreparation(RevisionRequest):
    confirmed: StrictBool


def decode_photo(content: str) -> tuple[bytes, bytes, dict]:
    try:
        original = base64.b64decode(content, validate=True)
        if len(original) > MAX_BYTES or not original:
            raise ValueError("每张照片最多五兆，请选择较小的照片。")
        with Image.open(BytesIO(original)) as source:
            if source.format not in {"JPEG", "PNG"} or source.width * source.height > 24_000_000:
                raise ValueError("只支持有效的普通 JPEG、PNG 照片，最多二千四百万像素。")
            if getattr(source, "n_frames", 1) != 1:
                raise ValueError("请选择单张照片，不支持动态图。")
            image = ImageOps.exif_transpose(source).convert("RGB")
            image.load()
        pixel_hash = hashlib.sha256(f"{image.width}x{image.height}:".encode() + image.tobytes()).hexdigest()
        tiny = list(image.convert("L").resize((9, 8)).getdata())
        perceptual = sum((tiny[y * 9 + x] > tiny[y * 9 + x + 1]) << (y * 8 + x) for y in range(8) for x in range(8))
        output = BytesIO()
        image.save(output, format="PNG")
        rendered = output.getvalue()
        if len(rendered) > MAX_RENDERED_BYTES:
            raise ValueError("照片解码后过大，请选择分辨率较低的现场照片，原图不会被自动压缩替换。")
        return original, rendered, {"width": image.width, "height": image.height,
            "imageSha256": hashlib.sha256(original).hexdigest(), "pixelSha256": pixel_hash,
            "renderSha256": hashlib.sha256(rendered).hexdigest(), "perceptualHash": f"{perceptual:016x}"}
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError, Image.DecompressionBombWarning) as exc:
        raise ValueError("照片无法读取，请重新选择有效的 JPEG 或 PNG 照片。") from exc
    except (ValueError, TypeError) as exc:
        if isinstance(exc, ValueError) and "照片" in str(exc):
            raise
        raise ValueError("照片内容无效，请重新选择 JPEG 或 PNG 照片。") from exc


def labels_for(draft: TrainingProjectDraft) -> list[dict]:
    if draft.template.scope == "specialized":
        return [{"id": "white_label", "name": "白色标签"}, {"id": "color_label", "name": "彩色标签"}]
    return [{"id": "object", "name": "需要检查的物品或文字"}]


class PreparationStore:
    """SQLite handles per-project revision checks across API processes atomically."""

    def __init__(self, path: str | Path):
        self.path = Path(path)

    def probe(self):
        with self.connection() as db:
            db.execute("INSERT INTO preparation_schema VALUES('startup-probe')")
            if not db.execute("SELECT 1 FROM preparation_schema WHERE key='startup-probe'").fetchone():
                raise RuntimeError("preparation storage readback failed")
            db.execute("DELETE FROM preparation_schema WHERE key='startup-probe'")

    def frozen_content(self, draft, photo_id, kind, expected_snapshot):
        if kind not in {"original", "rendered"}:
            raise ValueError("不支持的照片内容类型。")
        with self.connection() as db:
            state = self._read(db, draft)
            if not state["submission"] or state["submission"]["snapshotSha256"] != expected_snapshot:
                raise PreparationConflict("冻结照片版本不一致，不能传输执行资料。")
            photo = next((photo for photo in state["photos"] if photo["photoId"] == photo_id), None)
            if not photo:
                raise ValueError("没有找到本厂这张冻结照片。")
            row = db.execute("SELECT original,rendered FROM preparation_photos WHERE factory_id=? AND project_id=? AND photo_id=?",
                (draft.spec.factory_id,draft.project_id,photo_id)).fetchone()
            content = row[0 if kind == "original" else 1] if row else b""
            expected_hash = photo["imageSha256" if kind == "original" else "renderSha256"]
            if not content or hashlib.sha256(content).hexdigest() != expected_hash:
                raise PreparationConflict("冻结照片内容哈希不一致，停止传输。")
            return content, expected_hash

    @contextmanager
    def connection(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        db = sqlite3.connect(self.path, timeout=15)
        db.row_factory = sqlite3.Row
        try:
            db.executescript("""
              CREATE TABLE IF NOT EXISTS preparations (
                factory_id TEXT NOT NULL, project_id TEXT NOT NULL, revision INTEGER NOT NULL,
                state_json TEXT NOT NULL, PRIMARY KEY(factory_id,project_id));
              CREATE TABLE IF NOT EXISTS preparation_photos (
                factory_id TEXT NOT NULL, project_id TEXT NOT NULL, photo_id TEXT NOT NULL,
                original BLOB NOT NULL, rendered BLOB NOT NULL,
                PRIMARY KEY(factory_id,project_id,photo_id));
              CREATE TABLE IF NOT EXISTS preparation_photo_reservations (
                factory_id TEXT NOT NULL, project_id TEXT NOT NULL, photo_id TEXT NOT NULL,
                image_sha256 TEXT NOT NULL, pixel_sha256 TEXT NOT NULL, perceptual_hash TEXT NOT NULL,
                capture_group TEXT NOT NULL, purpose TEXT NOT NULL,
                PRIMARY KEY(factory_id,project_id,photo_id));
              CREATE INDEX IF NOT EXISTS ix_preparation_factory_purpose
                ON preparation_photo_reservations(factory_id,purpose);
              CREATE TABLE IF NOT EXISTS preparation_schema (key TEXT PRIMARY KEY);
            """)
            db.execute("BEGIN IMMEDIATE")
            # Upgrade earlier preparation files without changing their photos,
            # reviews or frozen snapshot hashes.
            if not db.execute("SELECT 1 FROM preparation_schema WHERE key='photo_reservations_v1'").fetchone():
                db.execute("""INSERT OR IGNORE INTO preparation_photo_reservations
                SELECT p.factory_id,p.project_id,json_extract(photo.value,'$.photoId'),
                  json_extract(photo.value,'$.imageSha256'),json_extract(photo.value,'$.pixelSha256'),
                  json_extract(photo.value,'$.perceptualHash'),lower(json_extract(photo.value,'$.captureGroup')),
                  json_extract(photo.value,'$.purpose')
                FROM preparations p,json_each(p.state_json,'$.photos') photo""")
                db.execute("INSERT INTO preparation_schema VALUES('photo_reservations_v1')")
            yield db
            db.commit()
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    @staticmethod
    def _read(db, draft):
        row = db.execute("SELECT state_json FROM preparations WHERE factory_id=? AND project_id=?",
                         (draft.spec.factory_id, draft.project_id)).fetchone()
        return json.loads(row[0]) if row else {"revision": 0, "photos": [], "standard": None, "submission": None}

    @staticmethod
    def _save(db, draft, state):
        db.execute("INSERT INTO preparations VALUES(?,?,?,?) ON CONFLICT(factory_id,project_id) DO UPDATE SET revision=excluded.revision,state_json=excluded.state_json",
                   (draft.spec.factory_id, draft.project_id, state["revision"], canonical(state).decode()))

    @staticmethod
    def _editable(state, revision):
        if state["submission"] is not None:
            raise PreparationConflict("这批资料已经提交并冻结，请新增检查准备下一批资料。")
        if state["revision"] != revision:
            raise PreparationConflict("资料已被更新，已保留本次填写内容；请刷新核对后再保存。")

    @staticmethod
    def _invalidate_standard(state):
        if state["standard"]:
            state["standard"].update(confirmed=False, confirmedBy=None, confirmedAt=None)

    @staticmethod
    def blockers(state, draft):
        reasons = []
        for purpose, name in (("training", "用来学习"), ("acceptance", "另留试用")):
            photos = [photo for photo in state["photos"] if photo["purpose"] == purpose]
            admitted = [photo for photo in photos if photo["reviewed"] and photo["verdict"] != "uncertain"]
            if len(admitted) < 2:
                reasons.append(f"{name}至少要有两张已审核、可判断的照片；这只是资料完整性检查，实际学习数量仍按本厂执行设置要求。")
            if {photo["verdict"] for photo in admitted} != {"good", "defect"}:
                reasons.append(f"{name}需要分别准备已审核的合格和不合格例子。")
        if any(not photo["reviewed"] for photo in state["photos"]):
            reasons.append("还有照片未逐张审核，请核对后保存；看不清的照片请标记为看不清。")
        if not state["standard"] or not state["standard"].get("confirmed"):
            reasons.append("请质量负责人确认本批照片的合格、不合格要求和允许的漏检、误报上限。")
        return reasons

    def view(self, draft):
        with self.connection() as db:
            return self.present(self._read(db, draft), draft, db)

    @staticmethod
    def _capacity_readings(db, draft):
        factory = draft.spec.factory_id
        factory_bytes = db.execute("SELECT COALESCE(SUM(length(original)+length(rendered)),0) FROM preparation_photos WHERE factory_id=?", (factory,)).fetchone()[0]
        global_bytes = db.execute("SELECT COALESCE(SUM(length(original)+length(rendered)),0) FROM preparation_photos").fetchone()[0]
        factory_projects = db.execute("SELECT COUNT(DISTINCT project_id) FROM preparation_photos WHERE factory_id=?", (factory,)).fetchone()[0]
        return factory_bytes, global_bytes, factory_projects

    def present(self, state, draft, db=None):
        if db is None:
            with self.connection() as capacity_db:
                return self.present(state, draft, capacity_db)
        factory_bytes, _global_bytes, factory_projects = self._capacity_readings(db, draft)
        reasons = self.blockers(state, draft)
        # Keep integrity/duplicate checks private; default UI receives only business fields.
        public = json.loads(canonical(state))
        for photo in public["photos"]:
            photo.pop("pixelSha256", None)
            photo.pop("perceptualHash", None)
        public.update(taskFamily=draft.spec.task_family, blockers=reasons, readyToSubmit=not reasons,
                      labels=labels_for(draft), limits={"maxBytes": MAX_BYTES, "maxPhotos": MAX_PHOTOS,
                      "minTrainingPhotos": 2, "minAcceptancePhotos": 2})
        public["limits"].update(maxStoredBytes=MAX_STORED_BYTES, storedBytes=state.get("storedBytes", 0),
                                 factoryStoredBytes=factory_bytes, maxFactoryStoredBytes=MAX_FACTORY_STORED_BYTES,
                                 factoryPhotoProjects=factory_projects, maxFactoryPhotoProjects=MAX_FACTORY_PHOTO_PROJECTS)
        return public

    def _check_capacity(self, db, draft, new_bytes):
        """Check factory/global/photo-project and physical-disk budgets in the write transaction."""
        factory, project = draft.spec.factory_id, draft.project_id
        factory_bytes = db.execute(
            "SELECT COALESCE(SUM(length(original)+length(rendered)),0) "
            "FROM preparation_photos WHERE factory_id=?", (factory,)
        ).fetchone()[0]
        global_bytes = db.execute(
            "SELECT COALESCE(SUM(length(original)+length(rendered)),0) "
            "FROM preparation_photos"
        ).fetchone()[0]
        has_project = db.execute(
            "SELECT 1 FROM preparation_photos WHERE factory_id=? AND project_id=? LIMIT 1",
            (factory, project),
        ).fetchone()
        if not has_project:
            project_count = db.execute(
                "SELECT COUNT(DISTINCT project_id) FROM preparation_photos WHERE factory_id=?",
                (factory,),
            ).fetchone()[0]
            if project_count >= MAX_FACTORY_PHOTO_PROJECTS:
                raise CapacityLimitError("本厂带照片的检查项目已达五十个上限，请联系管理员归档或核验容量后再新增照片。")
        if factory_bytes + new_bytes > MAX_FACTORY_STORED_BYTES:
            raise CapacityLimitError("本厂照片资料累计大小已达2GB上限，请联系管理员归档或核验容量后再新增照片。")
        if global_bytes + new_bytes > MAX_GLOBAL_STORED_BYTES:
            raise CapacityLimitError("系统照片资料累计大小已达10GB上限，请联系管理员归档或核验容量后再新增照片。")
        try:
            usage = shutil.disk_usage(str(self.path.parent))
            free_bytes = usage.free if hasattr(usage, "free") else usage[2]
        except OSError as exc:
            raise ValueError("无法确认本机磁盘余量，照片未保存，请联系维护人员。") from exc
        required = MIN_FREE_BYTES + (new_bytes * 2) + JOURNAL_BUFFER_BYTES
        if free_bytes < required:
            raise ValueError("本机磁盘余量不足，照片未保存，请释放至少2GB加本次写入缓冲后重试。")

    def upload(self, draft, context, request: UploadPhoto):
        if "/" in request.fileName or "\\" in request.fileName or any(ord(c) < 32 for c in request.fileName):
            raise ValueError("照片文件名无效，请从本机重新选择照片。")
        original, rendered, info = decode_photo(request.contentBase64)
        with self.connection() as db:
            state = self._read(db, draft)
            self._editable(state, request.expectedRevision)
            for existing in state["photos"]:
                same = existing["imageSha256"] == info["imageSha256"] or existing["pixelSha256"] == info["pixelSha256"]
                if same:
                    if existing["purpose"] == request.purpose and existing["captureGroup"] == request.captureGroup:
                        return self.present(state, draft, db)
                    raise PreparationConflict("这张照片已经存在，不能重复使用或同时用于学习和独立试用。")
                if existing["purpose"] != request.purpose:
                    near = (int(existing["perceptualHash"], 16) ^ int(info["perceptualHash"], 16)).bit_count() <= 2
                    if existing["captureGroup"].casefold() == request.captureGroup.casefold() or near:
                        raise PreparationConflict("学习照片和试用照片来自相同拍摄批次或过于相似，请另拍一批试用照片。")
            reservations = db.execute("SELECT * FROM preparation_photo_reservations WHERE factory_id=? AND purpose<>?",
                                      (draft.spec.factory_id, request.purpose)).fetchall()
            for other in reservations:
                if (other["image_sha256"] == info["imageSha256"] or other["pixel_sha256"] == info["pixelSha256"]
                        or other["capture_group"] == request.captureGroup.casefold()
                        or (int(other["perceptual_hash"],16)^int(info["perceptualHash"],16)).bit_count() <= 2):
                    raise PreparationConflict("这张照片或拍摄批次已在本厂其他检查用于相反用途，不能在学习照片和预留试用照片之间混用，请另拍新照片。")
            if len(state["photos"]) >= MAX_PHOTOS:
                raise ValueError("本次最多准备二百张照片，请另建检查保存下一批。")
            stored = db.execute("SELECT COALESCE(SUM(length(original)+length(rendered)),0) FROM preparation_photos WHERE factory_id=? AND project_id=?",
                                (draft.spec.factory_id, draft.project_id)).fetchone()[0]
            new_bytes = len(original) + len(rendered)
            if stored + new_bytes > MAX_STORED_BYTES:
                raise CapacityLimitError("本项目照片资料累计大小已达单项目上限，请联系管理员归档或核验厂级/系统容量后再继续，已保存照片会保留。")
            self._check_capacity(db, draft, new_bytes)
            photo_id = "photo-" + info["imageSha256"][:24]
            photo = {"photoId": photo_id, "fileName": request.fileName, "purpose": request.purpose,
                     "captureGroup": request.captureGroup, **info, "verdict": None, "reviewed": False,
                     "boxes": [], "transcription": "", "uploadedBy": context.actor_id,
                     "uploadedAt": utc_now().isoformat(), "reviewer": None, "reviewedAt": None}
            db.execute("INSERT INTO preparation_photos VALUES(?,?,?,?,?)", (draft.spec.factory_id, draft.project_id, photo_id, original, rendered))
            db.execute("INSERT INTO preparation_photo_reservations VALUES(?,?,?,?,?,?,?,?)",
                (draft.spec.factory_id,draft.project_id,photo_id,info["imageSha256"],info["pixelSha256"],info["perceptualHash"],request.captureGroup.casefold(),request.purpose))
            state["photos"].append(photo)
            state["storedBytes"] = stored + new_bytes
            state["revision"] += 1
            self._invalidate_standard(state)
            self._save(db, draft, state)
            return self.present(state, draft, db)

    def image(self, draft, photo_id):
        with self.connection() as db:
            row = db.execute("SELECT rendered FROM preparation_photos WHERE factory_id=? AND project_id=? AND photo_id=?",
                             (draft.spec.factory_id, draft.project_id, photo_id)).fetchone()
            if not row:
                raise ValueError("没有找到本厂这张照片，请刷新照片列表。")
            return {"contentBase64": base64.b64encode(row[0]).decode(), "mimeType": "image/png"}

    def review(self, draft, context, request: ReviewPhoto):
        with self.connection() as db:
            state = self._read(db, draft)
            self._editable(state, request.expectedRevision)
            photo = next((photo for photo in state["photos"] if photo["photoId"] == request.photoId), None)
            if photo is None:
                raise ValueError("没有找到本厂这张照片，请刷新照片列表。")
            allowed = {item["id"] for item in labels_for(draft)}
            for box in request.boxes:
                if box.label not in allowed or box.x1 - box.x0 < 0.002 or box.y1 - box.y0 < 0.002:
                    raise ValueError("标记区域无效，请在照片范围内框选本次要检查的物品。")
            if request.verdict != "uncertain" and not request.boxes:
                raise ValueError("请先框选要检查的物品，再确认这张照片。")
            if draft.template.ocr_rules_required and request.verdict != "uncertain" and not request.transcription:
                raise ValueError("请填写照片上的正确文字，再确认审核。")
            photo.update(verdict=request.verdict, reviewed=True, boxes=[box.model_dump() for box in request.boxes],
                         transcription=request.transcription, reviewer=context.actor_id, reviewedAt=utc_now().isoformat(),
                         source="human", training_eligible=request.verdict != "uncertain" and photo["purpose"] == "training")
            state["revision"] += 1
            self._invalidate_standard(state)
            self._save(db, draft, state)
            return self.present(state, draft, db)

    def standard(self, draft, context, request: ConfirmStandard):
        if not request.confirmed:
            raise ValueError("请明确确认本批照片的合格要求，再保存。")
        with self.connection() as db:
            state = self._read(db, draft)
            self._editable(state, request.expectedRevision)
            state["standard"] = {**request.model_dump(exclude={"expectedRevision"}),
                                 "confirmedBy": context.actor_id, "confirmedAt": utc_now().isoformat()}
            state["revision"] += 1
            self._save(db, draft, state)
            return self.present(state, draft, db)

    @staticmethod
    def snapshot(state, draft):
        return {"schema": "factory-vision-preparation-v1", "factory_id": draft.spec.factory_id,
                "project_id": draft.project_id, "preview_hash": draft.preview_hash,
                "task_family": draft.spec.task_family, "photos": state["photos"], "standard": state["standard"]}

    def submit(self, draft, context, request: SubmitPreparation, dispatch):
        if not request.confirmed:
            raise ValueError("请确认本厂资料和要求后，再提交训练。")
        with self.connection() as db:
            state = self._read(db, draft)
            if state["submission"]:
                # Explicit resubmission may connect a newly registered local binding;
                # it never changes the frozen preparation or creates duplicate jobs.
                if state["revision"] != request.expectedRevision:
                    raise PreparationConflict("资料状态已更新，请刷新后核对提交记录。")
            else:
                self._editable(state, request.expectedRevision)
                reasons = self.blockers(state, draft)
                if reasons:
                    raise ValueError("；".join(reasons))
                snapshot_hash = digest(self.snapshot(state, draft))
                state["submission"] = {"submissionId": "vprep-" + snapshot_hash[:24],
                    "snapshotSha256": snapshot_hash, "status": "waiting_configuration",
                    "message": "本厂资料已提交并冻结，等待与这批照片和标准匹配的本厂执行设置；尚未开始训练。",
                    "submittedBy": context.actor_id, "submittedAt": utc_now().isoformat()}
            snapshot = self.snapshot(state, draft)
            sha256 = state["submission"]["snapshotSha256"]
            state["revision"] += 1
            self._save(db, draft, state)
        # Commit the immutable preparation BEFORE another persistence layer can
        # publish a claimable job. A crash leaves a frozen pending dispatch,
        # and explicit retries use the same snapshot and queue identity.
        execution = dispatch(snapshot, sha256)
        if not execution:
            return self.present(state, draft)
        with self.connection() as db:
            current = self._read(db, draft)
            if not current["submission"] or current["submission"]["snapshotSha256"] != sha256:
                raise PreparationConflict("冻结资料状态发生冲突，请维护人员核对提交记录。")
            current["submission"].update(execution)
            current["revision"] += 1
            self._save(db, draft, current)
            return self.present(current, draft, db)

    def export(self, draft, *, include_content=True):
        with self.connection() as db:
            state = self._read(db, draft)
            if not state["submission"]:
                raise ValueError("请先明确提交并冻结本厂资料，再交给本地执行人员。")
            snapshot = self.snapshot(state, draft)
            if not include_content:
                return {"snapshot": snapshot, "snapshotSha256": state["submission"]["snapshotSha256"],
                        "photoIds": [photo["photoId"] for photo in snapshot["photos"]],
                        "contentTransfer": "trusted-local-directory-export"}
            if state.get("storedBytes", 0) > 32 * 1024 * 1024:
                raise ValueError("整批资料较大，请使用可信本地目录导出，不能一次性读取全部照片内容。")
            photos = {}
            for photo in snapshot["photos"]:
                row = db.execute("SELECT original,rendered FROM preparation_photos WHERE factory_id=? AND project_id=? AND photo_id=?",
                    (draft.spec.factory_id, draft.project_id, photo["photoId"])).fetchone()
                if not row or hashlib.sha256(row[0]).hexdigest() != photo["imageSha256"] or hashlib.sha256(row[1]).hexdigest() != photo["renderSha256"]:
                    raise ValueError("照片来源记录与内容不一致，请联系维护人员核对。")
                photos[photo["photoId"]] = {"originalBase64": base64.b64encode(row[0]).decode(), "renderedBase64": base64.b64encode(row[1]).decode()}
            return {"snapshot": snapshot, "snapshotSha256": state["submission"]["snapshotSha256"], "photos": photos}

    def export_directory(self, draft, destination):
        """Operator-only local export, never a caller-supplied API destination.

        New exclusive directory, bounded per-photo reads, no giant JSON/base64
        response. A failed export leaves no valid bundle.json completion marker.
        """
        root = Path(destination)
        source_root = Path(__file__).resolve().parents[3]
        if not root.is_absolute() or root.resolve() == source_root or source_root in root.resolve().parents:
            raise ValueError("导出目录须为代码树以外的新绝对目录。")
        if any(parent.is_symlink() or getattr(parent, "is_junction", lambda: False)() for parent in (root, *root.parents)):
            raise ValueError("导出目录不能经过符号链接或目录联接。")
        root.mkdir(parents=True, exist_ok=False)
        (root / "originals").mkdir()
        (root / "rendered").mkdir()
        with self.connection() as db:
            state = self._read(db, draft)
            if not state["submission"]:
                raise ValueError("只能导出明确提交并冻结的本厂资料。")
            snapshot = self.snapshot(state, draft)
            photos = {}
            for photo in snapshot["photos"]:
                row = db.execute("SELECT original,rendered FROM preparation_photos WHERE factory_id=? AND project_id=? AND photo_id=?",
                    (draft.spec.factory_id, draft.project_id, photo["photoId"])).fetchone()
                if not row or hashlib.sha256(row[0]).hexdigest() != photo["imageSha256"] or hashlib.sha256(row[1]).hexdigest() != photo["renderSha256"]:
                    raise ValueError("照片来源记录与内容不一致，不能生成执行资料。")
                original = f"originals/{photo['photoId']}.bin"
                rendered = f"rendered/{photo['photoId']}.png"
                (root / original).write_bytes(row[0])
                (root / rendered).write_bytes(row[1])
                photos[photo["photoId"]] = {"originalFile": original, "renderedFile": rendered}
            bundle = {"snapshot": snapshot, "snapshotSha256": state["submission"]["snapshotSha256"], "photos": photos}
            if digest(snapshot) != bundle["snapshotSha256"]:
                raise ValueError("冻结资料哈希不一致，不能导出。")
            (root / "bundle.json").write_bytes(canonical(bundle))
        return root / "bundle.json"
