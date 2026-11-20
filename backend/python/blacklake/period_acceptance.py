"""Real isolated API acceptance. Never print credentials or source record payloads."""
import json
import os
from pathlib import Path
import time

import httpx


def main():
    from .preview_session import session
    verified=session()['data']
    out=Path(os.environ['LOCALAPPDATA'])/'Cretas/Blacklake/outputs/period-20261104'
    out.mkdir(parents=True,exist_ok=True)
    checks=[]
    evidence={'success':False,'sourceBusinessWrites':0,'sourceRefreshed':False,'checks':checks}
    headers={'Authorization':'Bearer '+verified['accessToken']}
    try:
        with httpx.Client(base_url='http://127.0.0.1:8093/api/smartbi/blacklake',headers=headers,timeout=30,trust_env=False) as client:
            full=client.get('/analysis');full.raise_for_status();full=full.json()['data']
            evidence['fullSnapshot']={'projects':full['production']['projectCount'],'eligible':full['cost']['unitCostAvailableProjects']}
            windows=[('day','2026-11-03','2026-11-03'),('week','2026-10-26','2026-11-01'),('month','2026-11-01','2026-11-30'),
                     ('custom','2026-10-29','2026-11-02'),('quarter','2026-10-01','2026-12-31'),('year','2026-01-01','2026-12-31')]
            for kind,start,end in windows:
                evidence['stage']='facts_'+kind
                query={'start_date':start,'end_date':end,'kind':kind,'basis':'completed'}
                before=time.monotonic();facts=client.get('/analysis',params=query);facts.raise_for_status();facts=facts.json()['data']
                duration=time.monotonic()-before
                assert facts['sectionInsights']==[] and facts['production']['completionRate'] is None
                assert facts['periodMetadata']['startDate']==start and facts['periodMetadata']['endDate']==end
                fp=facts['periodMetadata']['fingerprint']
                evidence['stage']='report_'+kind
                submitted=client.post('/period-reports',json={**query,'expected_fingerprint':fp});submitted.raise_for_status();job=submitted.json()['data']
                first_status=job['status'];deadline=time.monotonic()+420
                while job['status'] in ('queued','running') and not job.get('result') and time.monotonic()<deadline:
                    time.sleep(2)
                    state=client.get('/period-reports/'+job['id']);state.raise_for_status();job=state.json()['data']
                assert job.get('result') and job['status'] in ('ready','queued','running'), 'RULE_REPORT_NOT_SAVED'
                if job['status']!='ready':
                    assert job['result']['ai']['status'] in ('rate_limited','busy','unavailable') and job.get('nextAttemptAt')
                assert job['fingerprint']==fp and len(job['result']['sectionInsights'])==7
                same=client.post('/period-reports',json={**query,'expected_fingerprint':fp});same.raise_for_status();same=same.json()['data']
                assert same['id']==job['id'] and same['fromCache'] is True
                checks.append({'kind':kind,'startDate':start,'endDate':end,'factsSeconds':round(duration,2),
                               'projects':facts['production']['projectCount'],'eligible':facts['cost']['unitCostAvailableProjects'],
                               'firstStatus':first_status,'savedStatus':job['status'],'sameRequestReused':True,'rulesSaved':True,
                               'nextAiAttemptAt':job.get('nextAttemptAt'),
                               'usedHistory':len(job['result']['history']['usedReports']), 'aiStatus':job['result']['ai']['status'],
                               'aiModel':job['result']['ai'].get('model'),'revision':job['revision'],'reportId':job['id']})
                (out/'api-acceptance.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8')
            schedule=client.get('/period-schedule');schedule.raise_for_status();schedule=schedule.json()['data']
            assert schedule['enabled'] is True and '23:55' in schedule['message']
            evidence.update(success=True,nightlyEnabled=True,lastTick=schedule.get('lastTick'),timezone=schedule['timezone'],
                            allAiComplete=all(c['aiStatus'] in ('complete','not_applicable','not_configured') for c in checks))
    except Exception as error:
        evidence['error']='PERIOD_API_ACCEPTANCE_FAILED'
        evidence['failureType']=type(error).__name__
        if isinstance(error,httpx.HTTPStatusError):evidence['httpStatus']=error.response.status_code
        if 'job' in locals():
            evidence['lastJobState']={'status':job.get('status'),'aiStatus':(job.get('result') or {}).get('ai',{}).get('status'),
                                      'nextAttemptAt':job.get('nextAttemptAt'),'error':job.get('error')}
    (out/'api-acceptance.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(evidence,ensure_ascii=False))
    return 0 if evidence['success'] else 1


if __name__=='__main__':raise SystemExit(main())
