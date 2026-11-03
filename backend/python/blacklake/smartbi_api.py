"""Explicitly enabled, fixed test-factory Blacklake SmartBI analysis route."""
import os
from pathlib import Path
from typing import Literal
from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field, ConfigDict

from blacklake.smartbi_store import read, settings

router=APIRouter()


async def cost_authority(request, configured):
    from smartbi.services.factory_charts.cost_scope import resolve_cost_scope
    scope = await resolve_cost_scope(request, configured['tenant_id'], [])
    if not scope.is_full:
        raise HTTPException(403, '成本范围授权未确认，暂不展示六膳门成本数据；请核对测试Java授权服务')


def authorize(request, configured):
    if (getattr(request.state,'auth_method',None)!='jwt'
            or getattr(request.state,'factory_id',None)!=configured['tenant_id']
            or getattr(request.state,'role',None)!='factory_super_admin'):
        raise HTTPException(403,'仅限六膳门隔离测试工厂的授权管理员')


@router.get('/analysis')
async def analysis(request: Request):
    path=os.environ.get('BLACKLAKE_SMARTBI_CONFIG')
    if not path: raise HTTPException(404,'黑湖测试数据集尚未启用')
    configured=settings(path)
    authorize(request,configured)
    await cost_authority(request, configured)
    try:
        import asyncio
        data=await asyncio.to_thread(read,configured)
    except Exception:
        raise HTTPException(503,'黑湖测试数据集读取或一致性验证失败') from None
    return {'success':True,'data':data,'message':'六膳门账号可见历史 · 只读分析'}


@router.post('/alerts/local-review')
async def local_review(request:Request):
    path=os.environ.get('BLACKLAKE_SMARTBI_CONFIG')
    if not path:raise HTTPException(404,'黑湖测试数据集尚未启用')
    configured=settings(path);authorize(request,configured)
    await cost_authority(request, configured)
    import asyncio
    from smartbi.services.alert_local_ai import enrich
    try:data=await asyncio.to_thread(read,configured)
    except Exception:raise HTTPException(503,'黑湖测试数据集读取或一致性验证失败') from None
    report=await enrich(data['dataAlerts'],getattr(request.state,'user_id',None))
    from .section_review import save
    if report.get('ai',{}).get('status')=='complete':
        await asyncio.to_thread(save, report, Path(path).resolve().parent/'cost-common-ai.dpapi')
    return {'success':True,'data':report,'message':'共用本地AI核对，未改写源数据和成本'}


class Turn(BaseModel):
    model_config=ConfigDict(extra='forbid')
    role: Literal['user','assistant']
    content: str = Field(min_length=1,max_length=12000)


class Followup(BaseModel):
    model_config=ConfigDict(extra='forbid')
    section_id: Literal['coverage','material','labor','finished','product_cost','production','inventory']
    question: str = Field(min_length=1,max_length=500)
    run_id: str = Field(min_length=1,max_length=200)
    history: list[Turn] = Field(default_factory=list,max_length=6)


@router.post('/sections/followup')
async def followup(payload: Followup, request: Request):
    path=os.environ.get('BLACKLAKE_SMARTBI_CONFIG')
    if not path:raise HTTPException(404,'黑湖测试数据集尚未启用')
    configured=settings(path);authorize(request,configured)
    await cost_authority(request, configured)
    import asyncio
    try:data=await asyncio.to_thread(read,configured)
    except Exception:raise HTTPException(503,'当前来源证据读取失败，未生成回答') from None
    if payload.run_id != data['runId']:
        raise HTTPException(409,'数据归档已变化，请刷新该分析后再继续追问')
    if not payload.question.strip():raise HTTPException(422,'请输入需要核对的问题')
    from .section_insights import answer, section_alerts
    section=next(s for s in data['sectionInsights'] if s['sectionId']==payload.section_id)
    report=data['dataAlerts']
    # Never send free-text questions, history, source rows or names to a model.
    # The rule answer uses current evidence; 27B only selects bounded checks.
    from smartbi.services.alert_local_ai import enrich
    if report.get('ai',{}).get('status')!='complete':
        await enrich(report,getattr(request.state,'user_id',None))
        if report.get('ai',{}).get('status')=='complete':
            from .section_review import save
            await asyncio.to_thread(save,report,Path(path).resolve().parent/'cost-common-ai.dpapi')
    text=answer(section,payload.question,[h.model_dump() for h in payload.history])
    from .section_review import for_section
    ai=for_section(report,section_alerts(data,payload.section_id))
    if ai.get('reviews') and ai.get('status') in ('complete','partial'):
        actions=list(dict.fromkeys(r['suggestion'] for r in ai['reviews']))[:3]
        text+='\n\n27B 补充核对建议（待人工确认）：'+ai['message']+'\n'+'\n'.join('• '+a for a in actions)
    else:text+='\n\n本地核对状态：'+ai.get('message','尚未完成')+'；以上回答来自已验证规则与当前证据。'
    return {'success':True,'data':{'answer':text,'sectionId':payload.section_id,'runId':data['runId'],
            'engine':'确定性证据解释；27B仅选择核对动作','ai':ai},'message':'仅解释当前板块，不执行业务修改'}
