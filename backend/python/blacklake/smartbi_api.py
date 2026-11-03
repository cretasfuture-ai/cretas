"""Explicitly enabled, fixed test-factory Blacklake SmartBI analysis route."""
import os
from fastapi import APIRouter, HTTPException, Request

from blacklake.smartbi_store import read, settings

router=APIRouter()


def authorize(request, configured):
    if (getattr(request.state,'auth_method',None)!='jwt'
            or getattr(request.state,'factory_id',None)!=configured['tenant_id']
            or getattr(request.state,'role',None)!='factory_super_admin'):
        raise HTTPException(403,'仅限六膳门隔离测试工厂的授权管理员')


@router.get('/analysis')
def analysis(request: Request):
    path=os.environ.get('BLACKLAKE_SMARTBI_CONFIG')
    if not path: raise HTTPException(404,'黑湖测试数据集尚未启用')
    configured=settings(path)
    authorize(request,configured)
    try:
        data=read(configured)
    except Exception:
        raise HTTPException(503,'黑湖测试数据集读取或一致性验证失败') from None
    return {'success':True,'data':data,'message':'六膳门账号可见历史 · 只读分析'}


@router.post('/alerts/local-review')
async def local_review(request:Request):
    path=os.environ.get('BLACKLAKE_SMARTBI_CONFIG')
    if not path:raise HTTPException(404,'黑湖测试数据集尚未启用')
    configured=settings(path);authorize(request,configured)
    import asyncio
    from smartbi.services.alert_local_ai import enrich
    try:data=await asyncio.to_thread(read,configured)
    except Exception:raise HTTPException(503,'黑湖测试数据集读取或一致性验证失败') from None
    report=await enrich(data['dataAlerts'],getattr(request.state,'user_id',None))
    return {'success':True,'data':report,'message':'共用本地AI核对，未改写源数据和成本'}
