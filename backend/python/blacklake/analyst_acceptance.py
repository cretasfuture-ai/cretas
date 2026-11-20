"""Explicit local-27B profile evaluation against one authorised real period."""
import asyncio
import json
import os
from pathlib import Path
import time


async def main():
    from .smartbi_app import create_app
    create_app()
    from . import period_runtime as runtime
    from smartbi.services.period_windows import parse_window
    from smartbi.services.analysis_playbook import VERSION
    config=runtime.settings(os.environ['BLACKLAKE_SMARTBI_CONFIG'])
    if await runtime.authorized_actor(config) is None:raise RuntimeError('COST_AUTHORITY_REQUIRED')
    db=runtime.store();scope=runtime.scope(config)
    retained={r['id']:r for r in db.list(scope,500) if r['status']=='ready'}
    period=parse_window('2026-10-29','2026-11-03')
    facts=await asyncio.to_thread(runtime.read,config,period)
    out=Path(os.environ['LOCALAPPDATA'])/'Cretas/Blacklake/outputs/local-analyst-20261104'
    out.mkdir(exist_ok=True,parents=True)
    prior=None
    if (out/'job.json').exists():
        prior=db.get(scope,json.loads((out/'job.json').read_text(encoding='utf-8'))['id'])
    if (prior and prior['status']=='queued' and prior['fingerprint']==facts['periodMetadata']['fingerprint']
            and prior['trigger']=='analyst_profile_evaluation'):
        job=prior
    else:
        job=db.submit(scope,period.wire(),facts['periodMetadata']['fingerprint'],facts,'analyst_profile_evaluation',force=True)
    db.resume_ai_queue(scope,VERSION)
    (out/'job.json').write_text(json.dumps({'id':job['id']}),encoding='utf-8')
    deadline=time.monotonic()+1400
    while time.monotonic()<deadline:
        job=db.get(scope,job['id'])
        if job['status'] in ('ready','failed'):break
        print(json.dumps({'status':job['status'],'validationFeedback':(job.get('result') or {}).get('ai',{}).get('validationFeedback')}),flush=True)
        if not await runtime.run_one(config):await asyncio.sleep(5)
    result=job.get('result') or {};ai=result.get('ai',{})
    preserved=all(db.get(scope,key)==old for key,old in retained.items())
    receipt={'success':job['status']=='ready' and ai.get('status')=='complete' and ai.get('analysisProfileVersion')==VERSION,
             'reportId':job['id'],'profile':ai.get('analysisProfileVersion'),'status':job['status'],'aiStatus':ai.get('status'),
             'sections':len(ai.get('analysisSections',[])),'validation':ai.get('validation'),'usage':ai.get('usage'),
             'currentRequestUsage':ai.get('currentRequestUsage'),'modelCalls':len(ai.get('usageCalls',[])),
             'repairAttempts':ai.get('repairAttempts'),'validationFeedback':ai.get('validationFeedback'),
             'preservedReady':len(retained) if preserved else False,'sourceBusinessWrites':0,'sourceRefreshed':False}
    (out/'report-private.json').write_text(json.dumps(job,ensure_ascii=False,indent=2),encoding='utf-8')
    (out/'acceptance.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(receipt,ensure_ascii=False),flush=True)
    return 0 if receipt['success'] and preserved else 1


if __name__=='__main__':raise SystemExit(asyncio.run(main()))
