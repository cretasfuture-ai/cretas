"""Authenticated asynchronous reports using the fixed isolated source scope."""
import asyncio
import os
from datetime import datetime, timezone

from fastapi import APIRouter, HTTPException, Request
from pydantic import Field

from .period_contract import PeriodSelection
from . import period_runtime as runtime

router = APIRouter()


async def context(request):
    from . import smartbi_api as api
    path = os.environ.get('BLACKLAKE_SMARTBI_CONFIG')
    if not path:
        raise HTTPException(404, '黑湖测试数据集尚未启用')
    config = api.settings(path)
    api.authorize(request, config)
    await api.cost_authority(request, config)
    return config


class ReportRequest(PeriodSelection):
    force: bool = False
    expected_fingerprint: str | None = Field(default=None, pattern=r'^[a-f0-9]{64}$')


@router.post('/period-reports')
async def submit(payload: ReportRequest, request: Request):
    from . import smartbi_api as api
    config = await context(request)
    window = payload.window()
    try:
        data = await asyncio.to_thread(api.read, config, window)
        if payload.expected_fingerprint is not None and payload.expected_fingerprint != data['periodMetadata']['fingerprint']:
            raise HTTPException(409, '数据在生成分析前已更新，请刷新日期范围后再试，避免分析与数值不一致')
        job = await asyncio.to_thread(runtime.store().submit, runtime.scope(config), window.wire(),
                                      data['periodMetadata']['fingerprint'], data, 'manual',
                                      getattr(request.state, 'user_id', None), payload.force)
        # Explicitly requested ranges upgrade old explanation profiles by a new
        # immutable revision; background reconciliation does not rewrite history.
        from smartbi.services.analysis_playbook import VERSION as ANALYSIS_PROFILE
        if (os.getenv('SMARTBI_LOCAL_ANALYST_ENABLED')=='1' and job['status']=='ready'
                and job.get('result',{}).get('ai',{}).get('analysisProfileVersion')!=ANALYSIS_PROFILE):
            job=await asyncio.to_thread(runtime.store().submit,runtime.scope(config),window.wire(),
                                       data['periodMetadata']['fingerprint'],data,'analysis_profile_upgrade',
                                       getattr(request.state,'user_id',None),True)
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(503, '日期范围数据或分析任务暂不可用，未生成虚构结果') from None
    retry=job.get('nextAttemptAt')
    if job['status'] in ('queued', 'running') and (not retry or datetime.fromisoformat(retry)<=datetime.now(timezone.utc)):
        runtime.ensure_worker(config)
    return {'success': True, 'data': job, 'message': '数值按当前快照展示；分析独立生成、持久保存，完成后可复用'}


@router.get('/period-reports')
async def history(request: Request, summaries_only: bool=False):
    config = await context(request)
    try:
        reports = await asyncio.to_thread(runtime.store().list, runtime.scope(config), 50)
    except Exception:
        raise HTTPException(503, '历史分析暂不可读，请稍后重试') from None
    from smartbi.services.analysis_supplement import attach_saved
    try:
        reports=[await asyncio.to_thread(attach_saved,job,runtime.scope(config),os.environ['BLACKLAKE_PERIOD_REPORT_DB'])
                 if job.get('result') else job for job in reports]
    except Exception:
        raise HTTPException(503,'历史补充分析证据暂未通过读取校验，原报告仍保留') from None
    if summaries_only:
        summaries=[]
        for job in reports:
            row=dict(job)
            if row.get('result'):
                result=row['result'];ai=result.get('ai') or {}
                row['result']={k:v for k,v in result.items() if k in ('generatedAt','summary','observations','history')}
                row['result']['sectionInsights']=[]
                row['result']['ai']={k:v for k,v in ai.items() if k in ('status','message','model','usage','cacheHit','analysisProfileVersion','omittedAnalysisFacts','analysisFactCoverage','analysisStage')}
                row['summaryOnly']=True
            summaries.append(row)
        reports=summaries
    return {'success': True, 'data': reports, 'message': '仅当前工厂及来源的历史分析，旧版本不混入当前数值'}


@router.get('/period-reports/{report_id}')
async def report(report_id: str, request: Request):
    config = await context(request)
    try:
        job = await asyncio.to_thread(runtime.store().get, runtime.scope(config), report_id)
    except Exception:
        raise HTTPException(503, '分析任务暂不可读，请稍后重试') from None
    if job is None:
        raise HTTPException(404, '当前工厂和来源中没有这份分析')
    if job.get('result'):
        from smartbi.services.analysis_supplement import attach_saved
        try:
            job=await asyncio.to_thread(attach_saved,job,runtime.scope(config),os.environ['BLACKLAKE_PERIOD_REPORT_DB'])
        except Exception:
            raise HTTPException(503,'补充分析证据暂未通过读取校验，原报告和原始数据仍保留') from None
    retry=job.get('nextAttemptAt')
    if job['status'] in ('queued', 'running') and (not retry or datetime.fromisoformat(retry)<=datetime.now(timezone.utc)):
        runtime.ensure_worker(config)
    return {'success': True, 'data': job, 'message': '分析完成状态与数据完整性分开说明'}


@router.get('/period-schedule')
async def schedule(request: Request):
    await context(request)
    return {'success': True, 'data': runtime.schedule_status(), 'message': '北京时间夜间分析计划'}
