"""Explicit scoped resume + completion receipt, without printing business facts."""
import asyncio
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sqlite3
import time


async def main():
    from .smartbi_app import create_app
    create_app()  # Fixed private test configuration; no HTTP/lifespan started.
    from . import period_runtime as runtime
    from smartbi.services.period_reports import DEFINITION_VERSION
    config=runtime.settings(os.environ['BLACKLAKE_SMARTBI_CONFIG'])
    if await runtime.authorized_actor(config) is None:raise RuntimeError('COST_AUTHORITY_REQUIRED')
    db=runtime.store();key=runtime.scope(config)
    before=db.list(key,500)
    current=[r for r in before if r['definitionVersion']==DEFINITION_VERSION]
    pending=[r['id'] for r in current if r['status'] in ('queued','running')]
    preserved={r['id']:r for r in before if r['status']=='ready'}
    resumed=db.resume_ai_queue(key)
    started=time.time();deadline=time.monotonic()+1800
    out=Path(os.environ['LOCALAPPDATA'])/'Cretas/Blacklake/outputs/ai-queue-20261104'
    out.mkdir(exist_ok=True,parents=True)
    while time.monotonic()<deadline:
        reports=[db.get(key,job_id) for job_id in pending]
        unfinished=[r for r in reports if r['status'] in ('queued','running')]
        if not unfinished:break
        if not await runtime.run_one(config):await asyncio.sleep(2)
    final=[db.get(key,job_id) for job_id in pending]
    for job_id,old in preserved.items():
        assert db.get(key,job_id)==old,'READY_REPORT_MUTATED'
    with sqlite3.connect(os.environ['SMARTBI_ALERT_AI_STATE_FILE']) as usage_db:
        usage_db.row_factory=sqlite3.Row
        rows=[dict(r) for r in usage_db.execute('SELECT request_id,model,started_at,finished_at,outcome,input_tokens,output_tokens,total_tokens,provider_duration_seconds FROM inference_usage WHERE started_at>=? ORDER BY started_at',(started,))]
    # Only public report state/usage, never source rows, questions, wages or tokens.
    checks=[{'reportId':r['id'],'startDate':r['period']['startDate'],'endDate':r['period']['endDate'],
             'status':r['status'],'aiStatus':(r.get('result') or {}).get('ai',{}).get('status'),
             'usage':(r.get('result') or {}).get('ai',{}).get('usage'),
             'sections':len((r.get('result') or {}).get('sectionInsights',[]))} for r in final]
    success=all(r['status']=='ready' and r['aiStatus'] in ('complete','not_applicable') and r['sections']==7 for r in checks)
    receipt={'success':success,'completedAt':datetime.now(timezone.utc).isoformat(),'initialCurrentReady':len(current)-len(pending),
             'resumed':len(resumed),'pendingAtStart':len(pending),'checks':checks,'preservedReady':len(preserved),
             'modelCalls':len(rows),'usageRows':rows,'inputTokens':sum(r['input_tokens'] or 0 for r in rows),
             'outputTokens':sum(r['output_tokens'] or 0 for r in rows),'totalTokens':sum(r['total_tokens'] or 0 for r in rows),
             'usageUnknownCalls':sum(r['total_tokens'] is None for r in rows),
             'sourceBusinessWrites':0,'sourceRefreshed':False}
    (out/'drain-acceptance.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(receipt,ensure_ascii=False))
    return 0 if success else 1


if __name__=='__main__':raise SystemExit(asyncio.run(main()))
