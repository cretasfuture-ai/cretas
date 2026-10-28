"""
Dashboard Layout Persistence API

Save/load user-customized dashboard layouts (chart positions and sizes).
"""
from __future__ import annotations

import logging
from typing import Any, Dict, Optional

from fastapi import APIRouter, Request
from pydantic import BaseModel

from common.tenant_identity import caller_user_id, require_same_factory

logger = logging.getLogger(__name__)
router = APIRouter()


class LayoutSaveRequest(BaseModel):
    # 工厂与用户一律取登录身份; 这两个字段只为兼容旧前端, 与身份不一致即 403
    factory_id: Optional[str] = None
    upload_id: int
    sheet_index: int = 0
    user_id: Optional[int] = None
    layout_name: Optional[str] = None
    layout_data: Dict[str, Any]  # Full DashboardLayout JSON


class LayoutResponse(BaseModel):
    success: bool
    data: Optional[Dict[str, Any]] = None
    message: str = ""


@router.post("/layouts/save", response_model=LayoutResponse)
async def save_layout(req: LayoutSaveRequest, request: Request):
    """Save or update a dashboard layout for a specific upload/sheet."""
    factory_id = require_same_factory(request, req.factory_id)
    user_id = caller_user_id(request)
    try:
        from smartbi.database.connection import get_db_context, is_postgres_enabled
        if not is_postgres_enabled():
            return LayoutResponse(success=False, message="PostgreSQL not enabled")

        from smartbi.database.models import SmartBiDashboardLayout

        with get_db_context() as db:
            # Upsert: find existing layout for this upload+sheet+user
            existing = db.query(SmartBiDashboardLayout).filter(
                SmartBiDashboardLayout.factory_id == factory_id,
                SmartBiDashboardLayout.upload_id == req.upload_id,
                SmartBiDashboardLayout.sheet_index == req.sheet_index,
            )
            if user_id:
                existing = existing.filter(SmartBiDashboardLayout.user_id == user_id)

            record = existing.first()

            if record:
                record.layout_data = req.layout_data
                record.layout_name = req.layout_name or record.layout_name
            else:
                record = SmartBiDashboardLayout(
                    factory_id=factory_id,
                    upload_id=req.upload_id,
                    sheet_index=req.sheet_index,
                    user_id=user_id,
                    layout_name=req.layout_name,
                    layout_data=req.layout_data,
                )
                db.add(record)

            db.flush()
            return LayoutResponse(
                success=True,
                data=record.to_dict(),
                message="布局已保存",
            )

    except Exception as e:
        logger.error(f"Save layout failed: {e}")
        return LayoutResponse(success=False, message=f"保存失败: {e}")


@router.get("/layouts/{upload_id}")
async def get_layout(
    upload_id: int,
    request: Request,
    sheet_index: int = 0,
    factory_id: Optional[str] = None,
):
    """Load a saved dashboard layout for a specific upload/sheet."""
    factory_id = require_same_factory(request, factory_id)
    try:
        from smartbi.database.connection import get_db_context, is_postgres_enabled
        if not is_postgres_enabled():
            return {"success": False, "data": None, "message": "PostgreSQL not enabled"}

        from smartbi.database.models import SmartBiDashboardLayout

        with get_db_context() as db:
            record = db.query(SmartBiDashboardLayout).filter(
                SmartBiDashboardLayout.factory_id == factory_id,
                SmartBiDashboardLayout.upload_id == upload_id,
                SmartBiDashboardLayout.sheet_index == sheet_index,
            ).order_by(SmartBiDashboardLayout.updated_at.desc()).first()

            if record:
                return {"success": True, "data": record.to_dict()}
            return {"success": True, "data": None, "message": "无已保存的布局"}

    except Exception as e:
        logger.error(f"Load layout failed: {e}")
        return {"success": False, "data": None, "message": f"加载失败: {e}"}


@router.delete("/layouts/{upload_id}")
async def delete_layout(
    upload_id: int,
    request: Request,
    sheet_index: int = 0,
    factory_id: Optional[str] = None,
):
    """Delete a saved dashboard layout."""
    factory_id = require_same_factory(request, factory_id)
    try:
        from smartbi.database.connection import get_db_context, is_postgres_enabled
        if not is_postgres_enabled():
            return {"success": False, "message": "PostgreSQL not enabled"}

        from smartbi.database.models import SmartBiDashboardLayout

        with get_db_context() as db:
            deleted = db.query(SmartBiDashboardLayout).filter(
                SmartBiDashboardLayout.factory_id == factory_id,
                SmartBiDashboardLayout.upload_id == upload_id,
                SmartBiDashboardLayout.sheet_index == sheet_index,
            ).delete()

            return {"success": True, "message": f"已删除 {deleted} 条布局"}

    except Exception as e:
        logger.error(f"Delete layout failed: {e}")
        return {"success": False, "message": f"删除失败: {e}"}
