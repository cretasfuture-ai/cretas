import asyncio
from types import SimpleNamespace


def test_saved_bounded_review_does_not_reinfer_every_followup(monkeypatch):
    from blacklake import smartbi_api as api,period_runtime
    from blacklake import section_insights
    from smartbi.services import alert_local_ai
    from smartbi.services.analysis_playbook import VERSION
    config={'tenant_id':'BLACKLAKE_POC_TEST','source_factory_id':'synthetic'}
    data={'runId':'synthetic-run','dataAlerts':{'items':[]},
          'sectionInsights':[{'sectionId':'product_cost'}]}
    saved={'status':'ready','result':{'runId':'synthetic-run',
        'ai':{'status':'partial','analysisProfileVersion':VERSION,'analysisSections':[],
              'omittedAnalysisFacts':14,'reviews':[],'usage':{'totalTokens':10}}}}
    monkeypatch.setenv('BLACKLAKE_SMARTBI_CONFIG','synthetic')
    monkeypatch.setenv('SMARTBI_LOCAL_ANALYST_ENABLED','1')
    monkeypatch.setattr(api,'settings',lambda path:config)
    monkeypatch.setattr(api,'read',lambda *args:data)
    async def authorized(*args):pass
    monkeypatch.setattr(api,'cost_authority',authorized)
    monkeypatch.setattr(period_runtime,'store',lambda:SimpleNamespace(list=lambda *args:[saved]))
    monkeypatch.setattr(section_insights,'section_alerts',lambda *args:[])
    monkeypatch.setattr(section_insights,'answer',lambda *args,**kwargs:'合成当前证据说明')
    async def unexpected(*args,**kwargs):raise AssertionError('saved partial result must be reused')
    monkeypatch.setattr(alert_local_ai,'enrich',unexpected)
    payload=api.Followup(section_id='product_cost',question='先核对什么？',run_id='synthetic-run',
        period={'start_date':'2026-10-29','end_date':'2026-11-03'})
    request=SimpleNamespace(state=SimpleNamespace(auth_method='jwt',factory_id='BLACKLAKE_POC_TEST',role='factory_super_admin'))
    result=asyncio.run(api.followup(payload,request))
    assert result['success']
    assert result['data']['ai']['omittedAnalysisFacts']==14
    assert result['data']['ai']['analysisProfileVersion']==VERSION
    assert result['data']['ai']['currentRequestUsage']['totalTokens']==0
    assert result['data']['ai']['usage']['totalTokens']==10
