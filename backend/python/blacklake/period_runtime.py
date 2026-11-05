"""Durable period-report adapter. Source data stays read-only; jobs store snapshots."""
import asyncio
from datetime import date, datetime, timedelta, timezone
import os
import time
from pathlib import Path
from zoneinfo import ZoneInfo

from smartbi.services.period_reports import ReportStore, LeaseError, DEFINITION_VERSION

from .smartbi_store import read, settings

_workers = {}
_last_tick = None
_schedule_error = None
_source_run = None
_authority_backoff = {}


def store():
    path = os.environ.get('BLACKLAKE_PERIOD_REPORT_DB')
    if not path or not Path(path).is_absolute():
        raise RuntimeError('PERIOD_REPORT_STORAGE_NOT_CONFIGURED')
    return ReportStore(Path(path))


def scope(config):
    return {'tenant_id': config['tenant_id'], 'source_factory_id': str(config['source_factory_id']),
            'connector': 'blacklake'}


def live_config(anchor):
    path = os.environ.get('BLACKLAKE_SMARTBI_CONFIG')
    if not path:
        raise RuntimeError('PERIOD_REPORT_SOURCE_NOT_CONFIGURED')
    current = settings(path)
    if scope(current) != scope(anchor) or current.get('dsn') != anchor.get('dsn'):
        raise RuntimeError('PERIOD_REPORT_SOURCE_IDENTITY_CHANGED_RESTART_REQUIRED')
    return current


async def worker_authority(config):
    # Revalidate the real, fixed test principal before computing/storing cost reports.
    # Login tokens never enter the queue, report, logs or model.
    from .preview_session import session
    verified = await asyncio.to_thread(session)
    user = verified['data']
    if user.get('factoryId') != config['tenant_id'] or user.get('role') != 'factory_super_admin':
        raise RuntimeError('PERIOD_COST_AUTHORITY_REQUIRED')
    return user['userId']


async def authorized_actor(config):
    global _schedule_error
    key = (os.environ.get('BLACKLAKE_PERIOD_REPORT_DB'), tuple(sorted(scope(config).items())))
    if time.monotonic() < _authority_backoff.get(key, 0):
        return None
    try:
        actor = await worker_authority(config)
    except Exception:
        _authority_backoff[key] = time.monotonic() + 60
        _schedule_error = '成本授权复核暂不可用，尚未读取任务成本事实；60秒后自动重试。'
        return None
    _authority_backoff.pop(key, None)
    return actor


def before_submission(report, current):
    at = current.get('createdAt')
    if not at or report['createdAt'] < at:
        return True
    return (report['createdAt'] == at and report['period']['startDate'] == current['period']['startDate']
            and report['period']['endDate'] == current['period']['endDate']
            and report.get('revision', 0) < current.get('revision', 1))


def _compatible(report, current):
    period = report.get('period', {})
    return (report.get('status') == 'ready' and isinstance(report.get('result'), dict)
            and period.get('basis') == current.get('basis')
            and period.get('timezone') == current.get('timezone'))


def previous_context(jobs, current_job, analysis):
    """Past narratives are context only. All financial metrics use current source rows."""
    current = current_job['period']
    start, end = date.fromisoformat(current['startDate']), date.fromisoformat(current['endDate'])
    candidates = [j for j in jobs if j['id'] != current_job['id'] and _compatible(j, current)
                  and j.get('definitionVersion') == current_job.get('definitionVersion')
                  and before_submission(j,current_job)
                  and (j['period']['startDate'] != current['startDate'] or j['period']['endDate'] != current['endDate']
                       or j.get('revision', 0) < current_job.get('revision', 1))
                  and j['result'].get('rulesVersion') == analysis['costAlerts']['ruleVersion']]
    same = next((j for j in candidates if j['period']['startDate'] == current['startDate']
                 and j['period']['endDate'] == current['endDate']), None)
    used, intervals = [], []
    for old in sorted(candidates, key=lambda j: (j['period']['endDate'], j['createdAt']), reverse=True):
        old_start, old_end = date.fromisoformat(old['period']['startDate']), date.fromisoformat(old['period']['endDate'])
        if old is same:
            relation = 'same_range_previous_version'
        elif old_start == start and old_end == end:
            continue
        elif start <= old_start <= old_end <= end:
            if any(not (old_end < lo or old_start > hi) for lo, hi in intervals):
                continue
            intervals.append((old_start, old_end)); relation = 'contained_period'
        elif old_end == start - timedelta(days=1) and (
                (end-start).days == (old_end-old_start).days or
                (old['period'].get('kind') == current.get('kind') and current.get('kind') in ('month','quarter','year'))):
            if any(r['relation'] == 'previous_period' for r in used):
                continue
            relation = 'previous_period'
        else:
            continue
        used.append({'id': old['id'], 'startDate': old['period']['startDate'], 'endDate': old['period']['endDate'],
                     'relation': relation, 'dataAsOf': old['result'].get('dataAsOf'),
                     'sameSourceSnapshot': old['result'].get('sourceRunId') == analysis['periodMetadata']['baseRunId']})
        if len(used) >= 12:
            break
    now_types = {a['type']: a['title'] for a in analysis['costAlerts']['items']}
    previous_period = next((j for j in candidates if any(r['id']==j['id'] and r['relation']=='previous_period' for r in used)),None)
    baseline = same or previous_period
    previous_types = baseline['result'].get('issueTypes', {}) if baseline else {}
    known_types = dict(previous_types)
    for old in candidates:
        if any(r['id'] == old['id'] for r in used):
            known_types.update(old['result'].get('issueTypes', {}))
    return {'usedReports': used,
            'recurringIssues': [now_types[t] for t in sorted(now_types.keys() & known_types.keys())],
            'newIssues': [now_types[t] for t in sorted(now_types.keys() - previous_types.keys())] if baseline else [],
            'resolvedIssues': [previous_types[t] for t in sorted(previous_types.keys() - now_types.keys())] if baseline else [],
            'comparisonAvailable': baseline is not None,
            'message': '历史分析仅用于发现重复提醒与安排核对；本期金额重新按原始工单计算，不相加日报单价或重复汇总重叠日期。未再命中不代表业务问题已经解决。'}


async def compile_report(job, history, actor):
    analysis = job['facts']
    period = analysis['periodMetadata']
    fingerprint = period['fingerprint']
    if (job['fingerprint'] != fingerprint
            or any(job['period'].get(k) != period.get(k) for k in ('startDate','endDate','basis','timezone'))
            or analysis['runId'] != period['baseRunId'] + ':period:' + fingerprint[:20]
            or analysis['costAlerts']['runId'] != analysis['runId']):
        raise ValueError('PERIOD_REPORT_FACT_BINDING_MISMATCH')
    projects = analysis['production']['projectCount']
    eligible = analysis['cost']['unitCostAvailableProjects']
    report = analysis['costAlerts']
    context = previous_context(history, job, analysis)
    analysis['analysisHistory']=context
    if projects:
        from smartbi.services.alert_local_ai import enrich
        await enrich(report, actor,analysis_context=analysis)
    else:
        report['ai'] = {'status': 'not_applicable', 'message': '本期没有可归属工单，未调用AI猜测成本或生产原因。'}
    configured_model=os.environ.get('SMARTBI_ALERT_AI_MODEL')
    if configured_model:
        report['ai'].setdefault('model',configured_model)
    if os.environ.get('SMARTBI_LOCAL_ANALYST_ENABLED')=='1':
        from smartbi.services.analysis_playbook import VERSION
        report['ai'].setdefault('analysisProfileVersion',VERSION)
    summary = (f"{period['startDate']} 至 {period['endDate']}归入 {projects} 张工单；"
               f'{eligible} 张资料具备，{projects-eligible} 张仍需核对材料、人工或成品记录。'
               if projects else '所选日期没有已同步且能按当前日期口径归属的工单；这不表示工厂没有生产，也不表示成本为零。')
    observations = [period['scopeDescription'],
                    '分析使用的数据截至 ' + datetime.fromisoformat(period['dataAsOf'].replace('Z','+00:00')).astimezone(
                        ZoneInfo('Asia/Shanghai')).strftime('%Y-%m-%d %H:%M:%S') +
                    '（北京时间）；生成报告不会自动补齐未同步或未记录的数据。']
    if period['unassignedProjects']:
        observations.append(f"全快照另有 {period['unassignedProjects']} 张工单尚未完工或未提供所选归属日期，未猜测分配到本期。")
    if period['coverageStatus'] == 'source_snapshot_before_period_end':
        observations.append('来源采集时间早于所选周期结束，当前属于截至采集时点的分析，不能称为完整周期成本。')
    observations += analysis['findings'] if projects else []
    for ranking in analysis['eligibleCostReview']['rankings']:
        observations.append(f"{ranking['product']}：按产量计算的平均材料加人工成本 {ranking['weightedUnitCost']:.2f}/{ranking['unit']}，"
                            f"对应 {ranking['projects']} 张资料具备工单；不同产品、规格和单位不直接判高低。")
    from .section_review import for_section
    from .section_insights import section_alerts
    sections = analysis['sectionInsights']
    for section in sections:
        section['ai'] = for_section(report, section_alerts(analysis, section['sectionId']))
        repeated = [a for a in section_alerts(analysis,section['sectionId']) if a['title'] in context['recurringIssues']]
        if repeated:
            first=repeated[0]
            section['nextSteps'].insert(0, '历史报告也出现过“'+first['title']+'”这类提醒，本期仍命中；建议优先处理：'+first['action']+'。旧结论不能代替本期核对。')
    from smartbi.services.local_analyst import apply_sections
    apply_sections(sections,report['ai'])
    return {'summary': summary, 'observations': observations, 'sectionInsights': sections,
            'history': context, 'ai': report['ai'], 'dataAsOf': period['dataAsOf'],
            'sourceRunId': period['baseRunId'], 'runId': analysis['runId'],
            'issueTypes': {a['type']: a['title'] for a in report['items']},
            'coverageStatus': period['coverageStatus'], 'generatedAt': datetime.now(timezone.utc).isoformat(),
            'rulesVersion': report['ruleVersion'], 'fullManufacturingCost': None, 'grossProfit': None}


async def run_one(config):
    global _schedule_error
    try:
        config = live_config(config)
    except Exception:
        _schedule_error = '来源配置已变化或不可读，旧任务暂停，尚未读取任务成本事实。'
        return False
    actor = await authorized_actor(config)
    if actor is None:
        return False
    db = store(); key = scope(config)
    job = await asyncio.to_thread(db.claim, key, None, 1400)
    if job is None:
        return False
    try:
        from .full_archive import digest
        if job['facts']['costAlerts']['scope'] != digest((config['tenant_id'],config['source_factory_id'])):
            raise ValueError('PERIOD_REPORT_SOURCE_SCOPE_MISMATCH')
        history = await asyncio.to_thread(db.history, key, job, 100)
        result = await compile_report(job, history, actor)
        from smartbi.services.local_analyst import retain_usage
        retain_usage(result['ai'],(job.get('result') or {}).get('ai',{}))
        ai_status=result['ai'].get('status')
        if ai_status in ('rate_limited','busy'):
            # Legacy rate-limited drafts now resume through the same serial queue.
            await asyncio.to_thread(db.defer,key,job['id'],job['leaseToken'],result,
                                    15,True)
        elif ai_status=='unavailable':
            if job.get('attempts',1)>=3:
                await asyncio.to_thread(db.fail,key,job['id'],job['leaseToken'],
                                        {'code':'LOCAL_AI_UNAVAILABLE','message':'规则分析已保存，本地AI暂不可用；系统退避重试，仍失败时可手动重试。'})
            else:
                await asyncio.to_thread(db.defer,key,job['id'],job['leaseToken'],result,120*job.get('attempts',1),False)
        else:
            await asyncio.to_thread(db.complete, key, job['id'], job['leaseToken'], result)
    except asyncio.CancelledError:
        # A durable lease expires and can be reclaimed after a process restart.
        raise
    except LeaseError:
        pass
    except Exception:
        try:
            await asyncio.to_thread(db.fail, key, job['id'], job['leaseToken'],
                                    {'code': 'PERIOD_REPORT_FAILED', 'message': '分析或成本授权复核未能完成，请检查服务后重试；原始数据未改变。'})
        except LeaseError:
            pass
    return True


def ensure_worker(config):
    key = (os.environ.get('BLACKLAKE_PERIOD_REPORT_DB'), tuple(sorted(scope(config).items())))
    previous = _workers.get(key)
    if previous is not None and not previous.done():
        return previous

    async def drain():
        # Bound each wake-up. Remaining durable jobs resume on the next tick.
        for _ in range(40):
            if not await run_one(config):
                break
    task = asyncio.create_task(drain())
    _workers[key] = task
    return task


async def reconcile_windows(config, db, key, limit=10):
    """Requeue stored windows once per source revision, without losing older windows."""
    from smartbi.services.period_windows import parse_window
    from smartbi.services.period_reports import DEFINITION_VERSION
    from .full_archive import digest
    revision=config['run_id']
    if os.environ.get('BLACKLAKE_OPERATIONAL_SOURCE_FILE'):
        if await authorized_actor(config) is None:raise RuntimeError('PERIOD_COST_AUTHORITY_UNAVAILABLE')
        from .operational_source import source_revision
        revision=source_revision(config,os.environ['BLACKLAKE_OPERATIONAL_SOURCE_FILE'],os.environ.get('BLACKLAKE_OPERATIONAL_BOM_FILE'))
    done_key = 'reconcile:' + revision + ':' + DEFINITION_VERSION
    if await asyncio.to_thread(db.schedule_seen, key, done_key):
        return 0
    if await authorized_actor(config) is None:
        raise RuntimeError('PERIOD_COST_AUTHORITY_UNAVAILABLE')
    offset, processed, last_id = 0, 0, None
    while True:
        windows = await asyncio.to_thread(db.windows, key, offset, 50)
        if not windows:
            if last_id is not None:
                await asyncio.to_thread(db.mark_schedule, key, done_key, last_id)
            return processed
        for old in windows:
            period = old['period']
            identity = {k: period[k] for k in ('startDate','endDate','basis','timezone')}
            marker = done_key + ':' + digest(identity)
            last_id = old['id']
            if await asyncio.to_thread(db.schedule_seen, key, marker):
                continue
            if processed >= limit:
                return processed
            window = parse_window(period['startDate'],period['endDate'],kind=period['kind'],basis=period['basis'])
            facts = await asyncio.to_thread(read, config, window)
            job = await asyncio.to_thread(db.submit, key, window.wire(), facts['periodMetadata']['fingerprint'],
                                          facts, 'source_revision_recheck', None, False)
            await asyncio.to_thread(db.mark_schedule, key, marker, job['id'])
            last_id = job['id']; processed += 1
        offset += len(windows)


async def tick(anchor, now=None):
    global _last_tick, _schedule_error, _source_run
    from smartbi.services.period_schedule import due_periods
    from smartbi.services.period_windows import parse_window
    from .period_projection import instant
    import math
    config = live_config(anchor)
    revision=config['run_id']
    if os.environ.get('BLACKLAKE_OPERATIONAL_SOURCE_FILE'):
        if await authorized_actor(config) is None:raise RuntimeError('PERIOD_COST_AUTHORITY_UNAVAILABLE')
        from .operational_source import source_revision
        revision=source_revision(config,os.environ['BLACKLAKE_OPERATIONAL_SOURCE_FILE'],os.environ.get('BLACKLAKE_OPERATIONAL_BOM_FILE'))
    current = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    db = store(); key = scope(config)
    checkpoint = await asyncio.to_thread(db.schedule_checkpoint, key)
    lower = instant(checkpoint) if checkpoint else current - timedelta(days=2)
    end = min(current, lower + timedelta(days=31))
    days = math.ceil((end-lower).total_seconds()/86400) + 1
    events = [e for e in due_periods(end,lookback_days=days,preview=True) if instant(e['dueAt']) >= lower]
    for event in events:
        event['storageKey'] = event['key'] + ':source:' + revision + ':definition:' + DEFINITION_VERSION
    pending = [e for e in events if not await asyncio.to_thread(db.schedule_seen, key, e['storageKey'])]
    if pending:
        if await authorized_actor(config) is None:
            raise RuntimeError('PERIOD_COST_AUTHORITY_UNAVAILABLE')
    for event in pending:
        period = event['period']
        window = parse_window(period['startDate'], period['endDate'], kind=period['kind'], basis=period['basis'])
        data = await asyncio.to_thread(read, config, window)
        result = await asyncio.to_thread(db.submit, key, period, data['periodMetadata']['fingerprint'], data,
                                        'scheduled_' + event['phase'], None, False)
        await asyncio.to_thread(db.mark_schedule, key, event['storageKey'], result['id'])
    await reconcile_windows(config, db, key)
    await asyncio.to_thread(db.schedule_checkpoint, key, end)
    _last_tick = end.isoformat(); _schedule_error = None; _source_run = revision
    ensure_worker(config)
    return len(pending)


async def schedule_loop(config):
    global _schedule_error
    while True:
        try:
            await tick(config)
        except asyncio.CancelledError:
            raise
        except Exception:
            _schedule_error = '夜间任务暂未完成，已保留任务与原始数据，服务恢复后补跑。'
        await asyncio.sleep(60)


async def stop_workers():
    tasks = [t for t in _workers.values() if not t.done()]
    for task in tasks:
        task.cancel()
    await asyncio.gather(*tasks, return_exceptions=True)


def schedule_status():
    return {'enabled': os.environ.get('BLACKLAKE_PERIOD_SCHEDULER_ENABLED') == '1',
            'timezone': 'Asia/Shanghai', 'lastTick': _last_tick, 'message': _schedule_error or
            '每晚23:55按已同步数据分析；停机后从持久检查点补跑（首次启用回看两天），新快照会逐批复核已保存范围，旧版本保留。',
            'rules': [{'label': '日报', 'description': '每晚23:55分析当天，并补齐前一天的已结束周期。'},
                      {'label': '周报', 'description': '周日晚23:55分析本周；周一晚补齐上周。'},
                      {'label': '月报 / 季报 / 年报', 'description': '周期最后一天23:55总结本期，次期第一天晚间补齐；不安排早晨任务。'}]}
