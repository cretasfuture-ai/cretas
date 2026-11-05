"""Read-only view of the fixed factory's local collection campaign."""
import asyncio
import os
import json
from datetime import timedelta
from fastapi import APIRouter, Request, HTTPException, Query
from .period_api import context
from . import live_monitor as live

router=APIRouter()


@router.get('/live-monitor')
async def monitor(request:Request,history_offset:int=Query(default=0,ge=0,le=1000000)):
    configured=await context(request)
    path=os.environ.get('BLACKLAKE_LIVE_CONFIG')
    if not path:raise HTTPException(404,'尚未启动持续采集')
    try:
        v=live.config(path)
        if (v['tenant_id'],str(v['source_factory_id']),v['baselineRunId'])!=(configured['tenant_id'],str(configured['source_factory_id']),configured['run_id']):
            raise ValueError('LIVE_API_SCOPE_MISMATCH')
        result=await asyncio.to_thread(live.status,v,history_offset)
    except Exception:raise HTTPException(503,'持续采集记录暂不可读，未显示虚构状态') from None
    return {'success':True,'data':result,'message':'采集、分析和一周观察进度分开显示'}


async def analysis_tick(configured):
    from . import period_runtime as runtime
    from .smartbi_store import read
    from smartbi.services.period_windows import parse_window
    from zoneinfo import ZoneInfo
    v=live.config(os.environ['BLACKLAKE_LIVE_CONFIG'])
    if (v['tenant_id'],str(v['source_factory_id']),v['baselineRunId'])!=(configured['tenant_id'],str(configured['source_factory_id']),configured['run_id']):
        raise ValueError('LIVE_ANALYSIS_SOURCE_SCOPE_MISMATCH')
    p=await asyncio.to_thread(live.latest,v)
    if not p:return
    data_hash=live.digest(p['data'])
    db=live.connect(v['db'])
    try:
        state=db.execute('SELECT analysis,ends FROM monitor WHERE scope=?',(live.binding(v),)).fetchone()
        if not state:raise ValueError('LIVE_ANALYSIS_SCOPE_MISMATCH')
        info=json.loads(state[0]) if state[0] else {}
        ended=live.instant(live.now())>=live.instant(state['ends'])
        store=runtime.store();scope=runtime.scope(configured)
        if info.get('reportId'):
            job=await asyncio.to_thread(store.get,scope,info['reportId'])
            if job:
                ai=(job.get('result') or {}).get('ai') or {}
                info.update(status=job['status'],lastCompletedAt=job.get('finishedAt'),tokens=(ai.get('usage') or {}).get('totalTokens'),
                            message='分析已保存；未通过校验的AI段落不作为结论。' if job['status']=='ready' else
                            '本次分析失败，数据已保存，两分钟后自动重试。' if job['status']=='failed' else
                            '本地模型正在排队或分析；新数据继续采集，完成后复核最新数据。')
                if job['status']=='failed' and not info.get('retryAt'):info['retryAt']=(live.instant(live.now())+timedelta(minutes=2)).isoformat()
                if job['status']=='failed' and ended:info['message']='观察期已结束，本地AI分析失败；规则与数据已保存，检查模型连接后可手动重新分析。'
                if job.get('result'):
                    info.update(summary=job['result'].get('summary'),findings=job['result'].get('observations',[])[1:])
                if info.get('analyzedDataHash')!=data_hash:
                    info.update(status='pending_latest',summary=None,findings=[],message='上次报告仅供历史查看；已采集到更新数据，正在等候复核最新数据。')
                with db:db.execute('UPDATE monitor SET analysis=? WHERE scope=?',(json.dumps(info,ensure_ascii=False),live.binding(v)))
                if job['status'] in ('queued','running'):runtime.ensure_worker(configured);return
                if job['status']=='failed' and info.get('analyzedDataHash')==data_hash and live.instant(info['retryAt'])>live.instant(live.now()):return
        # Drain the final unseen snapshot even if collection finished at the deadline.
        # Once submitted, an ended campaign never creates retry revisions for it.
        if ended and info.get('analyzedDataHash')==data_hash:return
        if info.get('analyzedDataHash')==data_hash and info.get('status')=='ready':
            info['message']='数据未变化，复用已保存的分析；每轮规则核对与库存对账继续更新。'
            with db:
                db.execute('UPDATE monitor SET analysis=? WHERE scope=?',(json.dumps(info,ensure_ascii=False),live.binding(v)))
                db.execute('UPDATE cycle SET report_id=? WHERE id=?',(info['reportId'],p['cycleId']))
            return
        retries=info.get('retries',0) if info.get('analyzedDataHash')==data_hash else 0
        if info.get('status')=='failed' and retries>=2:
            info['message']='本地模型连续重试仍失败，规则与原始数据已保留；请检查模型连接后重新分析，采集继续运行。'
            with db:db.execute('UPDATE monitor SET analysis=? WHERE scope=?',(json.dumps(info,ensure_ascii=False),live.binding(v)))
            return
        if await runtime.authorized_actor(configured) is None:return
        from .period_projection import instant
        dates=[instant(r.get('createTime')) for r in p['data']['project']]
        start=min((d for d in dates if d is not None),default=instant(p['checkedAt'])).astimezone(ZoneInfo('Asia/Shanghai')).date()
        end=instant(p['checkedAt']).astimezone(ZoneInfo('Asia/Shanghai')).date()
        window=parse_window(start.isoformat(),end.isoformat(),kind='custom',basis='created')
        facts=await asyncio.to_thread(read,configured,window,live_cycle_id=p['cycleId'])
        job=await asyncio.to_thread(store.submit,scope,window.wire(),facts['periodMetadata']['fingerprint'],facts,'continuous_changed_data',None,info.get('status')=='failed')
        info={'status':job['status'],'reportId':job['id'],'analyzedDataHash':data_hash,'retries':retries+1 if info.get('status')=='failed' else 0,
              'message':'数据有变化，已排入本地27B分析队列；相同数据复用结果。'}
        with db:
            db.execute('UPDATE monitor SET analysis=? WHERE scope=?',(json.dumps(info,ensure_ascii=False),live.binding(v)))
            db.execute('UPDATE cycle SET report_id=? WHERE id=?',(job['id'],p['cycleId']))
        runtime.ensure_worker(configured)
    finally:db.close()


async def nightly_tick(configured,current=None):
    from zoneinfo import ZoneInfo
    current=(current or live.instant(live.now())).astimezone(ZoneInfo('Asia/Shanghai'))
    if current.hour==23 and current.minute>=55:
        v=live.config(os.environ['BLACKLAKE_LIVE_CONFIG'])
        if (v['tenant_id'],str(v['source_factory_id']),v['baselineRunId'])!=(configured['tenant_id'],str(configured['source_factory_id']),configured['run_id']):raise ValueError('LIVE_NIGHTLY_SOURCE_SCOPE_MISMATCH')
        if not live.status(v)['enabled']:return
        from .period_runtime import tick
        live.record_nightly(v)
        try:await tick(configured)
        except Exception:
            live.record_nightly(v,False);raise
        else:live.record_nightly(v,True)


async def analysis_loop(configured):
    while True:
        await analysis_step(configured)
        await asyncio.sleep(30)


async def analysis_step(configured):
    # A change-analysis-only failure cannot suppress the independent nightly plan.
    for action in (analysis_tick,nightly_tick):
        try:await action(configured)
        except asyncio.CancelledError:raise
        except Exception:
            try:
                v=live.config(os.environ['BLACKLAKE_LIVE_CONFIG'])
                if (v['tenant_id'],str(v['source_factory_id']),v['baselineRunId'])!=(configured['tenant_id'],str(configured['source_factory_id']),configured['run_id']):
                    raise ValueError('LIVE_ANALYSIS_SOURCE_SCOPE_MISMATCH')
                db=live.connect(v['db'])
                with db:
                    db.execute('UPDATE monitor SET error=? WHERE scope=?',('分析任务暂未提交，保留数据并自动重试。',live.binding(v)))
                    if action is analysis_tick:
                        row=db.execute('SELECT analysis FROM monitor WHERE scope=?',(live.binding(v),)).fetchone()
                        try:info=json.loads(row[0]) if row and row[0] else {}
                        except ValueError:info={}
                        info.update(status='pending_latest',message='最新数据分析暂未提交，保留数据自动重试；上次报告仅作历史查看。',summary=None,findings=[])
                        db.execute('UPDATE monitor SET analysis=? WHERE scope=?',(json.dumps(info,ensure_ascii=False),live.binding(v)))
                db.close()
            except Exception:pass
