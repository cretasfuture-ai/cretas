"""Explicitly enabled, fixed test-factory Blacklake SmartBI analysis route."""
import os
from pathlib import Path
from typing import Literal
from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field, ConfigDict

from blacklake.smartbi_store import read, settings
from .period_contract import PeriodSelection, window

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
async def analysis(request: Request, start_date: str | None=None, end_date: str | None=None,
                   basis: Literal['completed','created']='completed',
                   kind: Literal['day','week','month','quarter','year','custom']='custom'):
    path=os.environ.get('BLACKLAKE_SMARTBI_CONFIG')
    if not path: raise HTTPException(404,'黑湖测试数据集尚未启用')
    configured=settings(path)
    authorize(request,configured)
    await cost_authority(request, configured)
    period=window(start_date,end_date,basis,kind)
    try:
        import asyncio
        data=await asyncio.to_thread(read,configured,period) if period is not None else await asyncio.to_thread(read,configured)
        from .alert_followups import load
        scoped_config={**configured,'run_id':data['runId']}
        data['alertFollowups']=await asyncio.to_thread(load,scoped_config,data['runId'])
        if period is not None:
            # Numeric facts and deterministic alerts are immediate; saved diagnosis is separate.
            data['sectionInsights']=[]
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
    report=await enrich(data['dataAlerts'],getattr(request.state,'user_id',None),analysis_context=data)
    from .section_review import save
    if report.get('ai',{}).get('status')=='complete':
        await asyncio.to_thread(save, report, Path(path).resolve().parent/'cost-common-ai.dpapi')
    return {'success':True,'data':report,'message':'共用本地AI核对，未改写源数据和成本'}


class Turn(BaseModel):
    model_config=ConfigDict(extra='forbid')
    role: Literal['user','assistant']
    content: str = Field(min_length=1,max_length=12000)


@router.get('/complete-cost-analysis')
async def complete_cost_analysis(request:Request):
    path=os.environ.get('BLACKLAKE_SMARTBI_CONFIG')
    if not path:raise HTTPException(404,'黑湖测试数据集尚未启用')
    configured=settings(path);authorize(request,configured);await cost_authority(request,configured)
    from .complete_cost_store import read as read_complete
    import asyncio
    try:
        result=await asyncio.to_thread(read_complete,configured)
        if result is None:raise ValueError('NOT_CONFIGURED')
    except Exception:raise HTTPException(503,'逐条成本分析尚未就绪或与当前工厂不一致') from None
    return {'success':True,'data':result,'message':'当前可核算工单；数值与规则取同一份已保存数据，历史补查另列范围'}


class CompleteCostFollowup(BaseModel):
    model_config=ConfigDict(extra='forbid')
    fact_id:str=Field(min_length=1,max_length=100)
    run_id:str=Field(min_length=1,max_length=200)
    question:str=Field(min_length=1,max_length=2000)


@router.post('/complete-cost-analysis/followup')
async def complete_cost_followup(body:CompleteCostFollowup,request:Request):
    response=await complete_cost_analysis(request);value=response['data']
    if body.run_id!=value['sourceRunId']:raise HTTPException(409,'分析版本已变化，请刷新后追问')
    from .complete_cost_store import followup
    try:result=followup(value,body.fact_id,body.question)
    except ValueError:raise HTTPException(409,'这条证据仍在排队或不属于当前分析') from None
    return {'success':True,'data':result,'message':'基于本条已保存证据回答'}


@router.get('/cost-traceability')
async def cost_traceability(request:Request,mode:Literal['latest','snapshot']='latest'):
    path=os.environ.get('BLACKLAKE_SMARTBI_CONFIG')
    if not path:raise HTTPException(404,'黑湖测试数据集尚未启用')
    configured=settings(path);authorize(request,configured)
    await cost_authority(request,configured)
    import asyncio
    try:
        if mode=='snapshot' or os.environ.get('BLACKLAKE_LIVE_CONFIG'):
            data=await asyncio.to_thread(read,configured)
            result=data['costTraceability']
        else:
            from .traceability import configured_report
            result=await asyncio.to_thread(configured_report,configured)
            if result is None:raise ValueError('TRACE_NOT_CONFIGURED')
    except Exception:
        raise HTTPException(503,'成本关联补查报告未就绪或与当前来源不一致，未猜测工单归属') from None
    return {'success':True,'data':result,'message':'逐单列出缺什么、找谁补、补齐后能判断什么；不改写历史金额和黑湖业务'}


class Followup(BaseModel):
    model_config=ConfigDict(extra='forbid')
    section_id: Literal['coverage','material','labor','finished','product_cost','production','inventory']
    question: str = Field(min_length=1,max_length=500)
    run_id: str = Field(min_length=1,max_length=200)
    history: list[Turn] = Field(default_factory=list,max_length=6)
    alert_id: str | None = Field(default=None,min_length=1,max_length=200)
    period: PeriodSelection | None = None


@router.post('/sections/followup')
async def followup(payload: Followup, request: Request):
    path=os.environ.get('BLACKLAKE_SMARTBI_CONFIG')
    if not path:raise HTTPException(404,'黑湖测试数据集尚未启用')
    configured=settings(path);authorize(request,configured)
    await cost_authority(request, configured)
    import asyncio
    selected_period=payload.period.window() if payload.period else None
    try:data=await asyncio.to_thread(read,configured,selected_period) if selected_period else await asyncio.to_thread(read,configured)
    except Exception:raise HTTPException(503,'当前来源证据读取失败，未生成回答') from None
    if payload.run_id != data['runId']:
        raise HTTPException(409,'数据归档已变化，请刷新该分析后再继续追问')
    if not payload.question.strip():raise HTTPException(422,'请输入需要核对的问题')
    from .section_insights import answer, section_alerts, operational_answer, resolve_question
    section=next(s for s in data['sectionInsights'] if s['sectionId']==payload.section_id)
    context_alert=None
    if payload.alert_id is not None:
        context_alert=next((a for a in section_alerts(data,payload.section_id) if a['id']==payload.alert_id),None)
        if context_alert is None:raise HTTPException(404,'该异常不属于当前板块或归档，请刷新后重试')
    focused=operational_answer(data,payload.section_id,resolve_question(payload.question,[h.model_dump() for h in payload.history]))
    if focused and context_alert is None:
        # Current-row checks are complete without waiting for a model. Local AI
        # period reports keep their independent persistent queue and token log.
        return {'success':True,'data':{'answer':focused,'sectionId':payload.section_id,'runId':data['runId'],
                'alertId':None,'engine':'当前明细规则核对',
                'ai':{'status':'not_requested','message':'本项由当前明细计算；本地AI周期报告独立生成。'}},
                'message':'已按当前明细核对，不执行业务修改'}
    report=data['dataAlerts']
    if payload.period and os.environ.get('SMARTBI_LOCAL_ANALYST_ENABLED')=='1':
        from . import period_runtime
        from smartbi.services.analysis_playbook import VERSION
        saved=await asyncio.to_thread(period_runtime.store().list,period_runtime.scope(configured),50)
        compatible=next((j for j in saved if j['status']=='ready' and j.get('result',{}).get('runId')==data['runId']
                         and j.get('result',{}).get('ai',{}).get('analysisProfileVersion')==VERSION),None)
        if compatible:
            report['ai']={**compatible['result']['ai'],'cacheHit':True,
                          'currentRequestUsage':{'inputTokens':0,'outputTokens':0,'totalTokens':0,
                                                 'source':'saved_report','estimated':False}}
            from smartbi.services.local_analyst import apply_sections
            apply_sections([section],report['ai'])
    # Never send free-text questions, history, source rows or names to a model.
    # The rule answer uses current evidence; 27B only selects bounded checks.
    from smartbi.services.alert_local_ai import enrich
    if report.get('ai',{}).get('status') not in ('complete','partial'):
        await enrich(report,getattr(request.state,'user_id',None),analysis_context=data)
        if report.get('ai',{}).get('status')=='complete' and payload.period is None:
            from .section_review import save
            await asyncio.to_thread(save,report,Path(path).resolve().parent/'cost-common-ai.dpapi')
    text=answer(section,payload.question,[h.model_dump() for h in payload.history],analysis=data)
    if context_alert:
        from .presentation import readable_text
        text='当前跟进：'+readable_text(context_alert['title'])+'\n'+readable_text(context_alert['message'])+'\n责任角色：'+context_alert['owner']+'\n下一步：'+readable_text(context_alert['action'])+'\n\n'+text
    from .section_review import for_section
    ai=for_section(report,[context_alert] if context_alert else section_alerts(data,payload.section_id))
    if ai.get('reviews') and ai.get('status') in ('complete','partial'):
        actions=list(dict.fromkeys(r['suggestion'] for r in ai['reviews']))[:3]
        text+='\n\n27B 补充核对建议（待人工确认）：'+ai['message']+'\n'+'\n'.join('• '+a for a in actions)
    else:text+='\n\n本地核对状态：'+ai.get('message','尚未完成')+'；以上回答来自已验证规则与当前证据。'
    return {'success':True,'data':{'answer':text,'sectionId':payload.section_id,'runId':data['runId'],
            'alertId':payload.alert_id,
            'engine':'当前证据统计与本地模型已校验解读' if report.get('ai',{}).get('analysisSections') else '确定性证据解释；27B选择核对动作','ai':ai},'message':'仅解释当前板块，不执行业务修改'}


class AlertProgress(BaseModel):
    model_config=ConfigDict(extra='forbid')
    run_id: str=Field(min_length=1,max_length=200)
    status: Literal['open','awaiting_data','verified']
    note: str=Field(min_length=1,max_length=500)
    expected_revision: int=Field(ge=0)
    period: PeriodSelection | None = None


@router.post('/alerts/{alert_id}/followup')
async def save_alert_progress(alert_id:str,payload:AlertProgress,request:Request):
    path=os.environ.get('BLACKLAKE_SMARTBI_CONFIG')
    if not path:raise HTTPException(404,'黑湖测试数据集尚未启用')
    configured=settings(path);authorize(request,configured)
    await cost_authority(request,configured)
    import asyncio
    selected_period=payload.period.window() if payload.period else None
    try:data=await asyncio.to_thread(read,configured,selected_period) if selected_period else await asyncio.to_thread(read,configured)
    except Exception:raise HTTPException(503,'来源证据暂不可读，未保存跟进状态') from None
    if payload.run_id!=data['runId']:raise HTTPException(409,'归档已变化，请刷新后再保存')
    if not any(a['id']==alert_id for a in data['costAlerts']['items']):raise HTTPException(404,'当前归档中没有该异常')
    if not payload.note.strip():raise HTTPException(422,'请填写本次跟进说明')
    from .alert_followups import save,FollowupConflict
    try:
        result=await asyncio.to_thread(save,{**configured,'run_id':data['runId']},payload.run_id,alert_id,payload.status,payload.note,
                                      payload.expected_revision,getattr(request.state,'user_id',None))
    except FollowupConflict:raise HTTPException(409,'跟进记录已被更新，请刷新后重试') from None
    except Exception:raise HTTPException(503,'跟进状态未能保存，请稍后重试') from None
    return {'success':True,'data':result,'message':'已保存Cretas跟进记录；原始数据、成本规则和核算缺口未改变'}

from .period_api import router as period_router
router.include_router(period_router)
from .live_api import router as live_router
router.include_router(live_router)
