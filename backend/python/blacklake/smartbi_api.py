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
        from .alert_followups import load
        data['alertFollowups']=await asyncio.to_thread(load,configured,data['runId'])
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
    alert_id: str | None = Field(default=None,min_length=1,max_length=200)


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
    context_alert=None
    if payload.alert_id is not None:
        context_alert=next((a for a in section_alerts(data,payload.section_id) if a['id']==payload.alert_id),None)
        if context_alert is None:raise HTTPException(404,'该异常不属于当前板块或归档，请刷新后重试')
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
    if context_alert:
        text='当前跟进：'+context_alert['title']+'\n'+context_alert['message']+'\n责任角色：'+context_alert['owner']+'\n下一步：'+context_alert['action']+'\n\n'+text
    from .section_review import for_section
    ai=for_section(report,[context_alert] if context_alert else section_alerts(data,payload.section_id))
    if ai.get('reviews') and ai.get('status') in ('complete','partial'):
        actions=list(dict.fromkeys(r['suggestion'] for r in ai['reviews']))[:3]
        text+='\n\n27B 补充核对建议（待人工确认）：'+ai['message']+'\n'+'\n'.join('• '+a for a in actions)
    else:text+='\n\n本地核对状态：'+ai.get('message','尚未完成')+'；以上回答来自已验证规则与当前证据。'
    return {'success':True,'data':{'answer':text,'sectionId':payload.section_id,'runId':data['runId'],
            'alertId':payload.alert_id,
            'engine':'确定性证据解释；27B仅选择核对动作','ai':ai},'message':'仅解释当前板块，不执行业务修改'}


class AlertProgress(BaseModel):
    model_config=ConfigDict(extra='forbid')
    run_id: str=Field(min_length=1,max_length=200)
    status: Literal['open','awaiting_data','verified']
    note: str=Field(min_length=1,max_length=500)
    expected_revision: int=Field(ge=0)


@router.post('/alerts/{alert_id}/followup')
async def save_alert_progress(alert_id:str,payload:AlertProgress,request:Request):
    path=os.environ.get('BLACKLAKE_SMARTBI_CONFIG')
    if not path:raise HTTPException(404,'黑湖测试数据集尚未启用')
    configured=settings(path);authorize(request,configured)
    await cost_authority(request,configured)
    import asyncio
    try:data=await asyncio.to_thread(read,configured)
    except Exception:raise HTTPException(503,'来源证据暂不可读，未保存跟进状态') from None
    if payload.run_id!=data['runId']:raise HTTPException(409,'归档已变化，请刷新后再保存')
    if not any(a['id']==alert_id for a in data['costAlerts']['items']):raise HTTPException(404,'当前归档中没有该异常')
    if not payload.note.strip():raise HTTPException(422,'请填写本次跟进说明')
    from .alert_followups import save,FollowupConflict
    try:
        result=await asyncio.to_thread(save,configured,payload.run_id,alert_id,payload.status,payload.note,
                                      payload.expected_revision,getattr(request.state,'user_id',None))
    except FollowupConflict:raise HTTPException(409,'跟进记录已被更新，请刷新后重试') from None
    except Exception:raise HTTPException(503,'跟进状态未能保存，请稍后重试') from None
    return {'success':True,'data':result,'message':'已保存Cretas跟进记录；原始数据、成本规则和核算缺口未改变'}
