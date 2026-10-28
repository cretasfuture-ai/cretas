"""Authenticated chart choices, isolated by factory, user and source."""
import json
import logging

from fastapi import HTTPException
from smartbi import config
from common.tenant_identity import caller_user_id, require_same_factory
from smartbi_compat._rbac_role import ANALYTICS_READ_ROLES

logger = logging.getLogger(__name__)

READ_SQL = """
SELECT selected_ids, updated_at FROM smart_bi_factory_chart_selections
WHERE factory_id=$1 AND user_id=$2 AND source=$3
"""
SAVE_SQL = """
INSERT INTO smart_bi_factory_chart_selections (factory_id,user_id,source,selected_ids)
VALUES ($1,$2,$3,$4::jsonb)
ON CONFLICT (factory_id,user_id,source)
DO UPDATE SET selected_ids=EXCLUDED.selected_ids, updated_at=CURRENT_TIMESTAMP
RETURNING selected_ids, updated_at
"""


def identity(request, claimed):
    factory = require_same_factory(request, claimed)
    user = caller_user_id(request)
    if user is None or user <= 0:
        raise HTTPException(401, '保存选图需要有效的登录用户身份')
    if getattr(request.state, 'role', None) not in ANALYTICS_READ_ROLES:
        raise HTTPException(403, '当前身份没有经营分析查看权限')
    return factory, user


async def access(request, claimed, source, selected_ids=None):
    factory, user = identity(request, claimed)
    try:
        pool = await config.get_pg_pool()
        if pool is None:
            raise RuntimeError('pool unavailable')
        async with pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory)
                params = [factory, user, source]
                if selected_ids is not None:
                    params.append(json.dumps(selected_ids, ensure_ascii=False))
                row = await conn.fetchrow(SAVE_SQL if selected_ids is not None else READ_SQL, *params)
        ids = row['selected_ids'] if row else None
        if isinstance(ids, str):
            ids = json.loads(ids)
        if ids is not None and (not isinstance(ids, list) or not all(isinstance(x, str) for x in ids)):
            raise ValueError('invalid persisted selection')
        updated = row['updated_at'] if row else None
        return {'success': True, 'data': {'factoryId': factory, 'source': source,
            'selectedIds': ids, 'updatedAt': updated.isoformat() if updated else None}}
    except Exception as exc:
        logger.warning('Factory selection persistence unavailable (%s)', type(exc).__name__)
        raise HTTPException(503, '选图偏好同步失败，请稍后重试；当前页面仍可使用') from None
