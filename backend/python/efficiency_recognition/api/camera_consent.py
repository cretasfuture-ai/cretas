"""摄像头画面 / 照片交给视觉大模型之前: 本厂 AI 设置里开了「摄像头抓拍 AI 识别」才发, 默认关。

与 Java ``CameraSnapshotAiConsent`` 同一规则: 读 ``factory_settings.ai_settings`` 里的
``cameraSnapshotAnalysis``, 必须是 JSON 布尔 true; 没有设置行 / 没写这一项 / 解析失败 / 查不了库,
一律当关 (不发)。工厂取登录身份 (``request.state.factory_id``), 不认请求里写的工厂。
"""
from __future__ import annotations

import json
import logging
from typing import Any

from fastapi import HTTPException, Request

from common.tenant_identity import caller_factory

logger = logging.getLogger(__name__)

DISABLED_MESSAGE = "本厂未开启摄像头抓拍 AI 识别"

_QUERY = "SELECT ai_settings FROM factory_settings WHERE factory_id = $1 AND deleted_at IS NULL"


def switch_on(ai_settings: Any) -> bool:
    """``ai_settings`` 列的原值 (TEXT) 里 ``cameraSnapshotAnalysis`` 是否为布尔 true。"""
    if ai_settings is None:
        return False
    if isinstance(ai_settings, (bytes, bytearray)):
        ai_settings = ai_settings.decode("utf-8", errors="replace")
    if isinstance(ai_settings, str):
        if not ai_settings.strip():
            return False
        try:
            ai_settings = json.loads(ai_settings)
        except ValueError:
            return False
    return isinstance(ai_settings, dict) and ai_settings.get("cameraSnapshotAnalysis") is True


async def camera_snapshot_ai_enabled(factory_id: str) -> bool:
    try:
        from smartbi.config import get_cretas_pool

        pool = await get_cretas_pool()
        if pool is None:
            return False
        async with pool.acquire() as conn:
            raw = await conn.fetchval(_QUERY, factory_id)
    except Exception as exc:  # 查不了就当没开
        logger.warning("读取摄像头抓拍 AI 识别开关失败, 按关闭处理: factory_id=%s, error=%s",
                       factory_id, type(exc).__name__)
        return False
    return switch_on(raw)


async def require_camera_snapshot_ai(request: Request) -> str:
    """路由依赖: 没有登录工厂 401; 本厂没开 403 (不读图、不发模型)。"""
    factory_id = caller_factory(request)
    if not await camera_snapshot_ai_enabled(factory_id):
        raise HTTPException(status_code=403, detail=DISABLED_MESSAGE)
    return factory_id
