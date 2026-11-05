import json
import sqlite3
from copy import deepcopy
from types import SimpleNamespace
import pytest
from smartbi.services import analysis_supplement as supplement
from tests.test_smartbi_local_analyst import sample_report,response_for
from smartbi.services.analysis_evidence import build


def fixture(monkeypatch):
    report=sample_report()
    context={'costAlerts':report,'periodMetadata':{'fingerprint':'a'*64},'runId':'r'}
    # Only the planner is stubbed: fact-schema, numeric and certainty guards stay real.
    pack=build(report)
    original=pack['facts'][0]
    more=[dict(original,id=f'F{i:03d}') for i in range(101,111)]
    full={**pack,'facts':pack['facts']+more,'displayFacts':{**pack['displayFacts'],**{f['id']:{'id':f['id'],'text':'真实绑定的待核对资料','entityRefs':[]} for f in more}},'omittedFacts':0}
    monkeypatch.setattr(supplement,'build',lambda report,context,include_all=False:full if include_all else {**pack,'omittedFacts':10})
    job={'id':'b'*32,'fingerprint':'a'*64,'result':{'runId':'r','history':{},'sectionInsights':[{'sectionId':'coverage'}],
         'ai':{'status':'partial','inputFingerprint':'base','validation':{'allSections':True},'totalRepresentativeGroups':1,'reviewedRepresentativeFacts':1,'usageCalls':[]}}}
    return job,context,full,more


def review(fact):
    return {'factId':fact['id'],'whatItMeans':'材料成本记录不全，先核对缺失的原始资料。','nextStep':'请生产和财务核对材料记录及计价依据。',
            'cannotConclude':'资料未齐，不能认定实际成本、浪费或利润。','owner':'生产/财务'}


def test_rejects_unbound_numbers_unknown_facts_and_certainty(monkeypatch):
    _,_,full,missing=fixture(monkeypatch)
    for change in ({'factId':'FOREIGN'},{'whatItMeans':'实际材料成本为123元。'},{'whatItMeans':'已证实发生浪费，确定是生产问题。'},
                   {'whatItMeans':'仅有两个样本，材料成本需要核对。'},
                   {'whatItMeans':'该产品的样本多于其他组，材料成本还需核对。'},
                   {'whatItMeans':'数据波动大，材料成本还需核对。'}):
        with pytest.raises(ValueError):supplement.validate_review({**review(missing[0]),**change},missing[0],full)


def test_publication_revalidates_scope_and_preserves_base_report(monkeypatch,tmp_path):
    job,context,full,missing=fixture(monkeypatch);before=deepcopy(job)
    _,_,binding=supplement.plan(job,context)
    path=tmp_path/'review.json';scope={'tenant_id':'T','source_factory_id':'F','connector':'blacklake'}
    state={**binding,'scope':scope,'accepted':{f['id']:review(f) for f in missing},'calls':[],'status':'ready'}
    supplement.save(path,state)
    updated=supplement.attach(job,context,scope,path)
    assert job==before
    assert updated['result']['ai']['status']=='complete'
    assert updated['result']['ai']['analysisFactCoverage']['remaining']==0
    assert len(updated['result']['sectionInsights'][0]['supplementalReviews'])==10
    with pytest.raises(ValueError):supplement.attach(job,context,{**scope,'source_factory_id':'OTHER'},path)
    state['accepted'][missing[0]['id']]['whatItMeans']='实际成本为999元。';supplement.save(path,state)
    with pytest.raises(ValueError):supplement.attach(job,context,scope,path)


def test_saved_overlay_cannot_load_another_factorys_facts(monkeypatch,tmp_path):
    job,context,_,missing=fixture(monkeypatch);scope={'tenant_id':'T','source_factory_id':'F','connector':'blacklake'}
    _,_,binding=supplement.plan(job,context)
    path=tmp_path/'analysis-supplements'/(job['id']+'.json')
    supplement.save(path,{**binding,'scope':scope,'accepted':{f['id']:review(f) for f in missing},'calls':[]})
    db_path=tmp_path/'reports.sqlite3'
    with sqlite3.connect(db_path) as db:
        db.execute('CREATE TABLE period_reports(id TEXT,scope_key TEXT,facts_json TEXT)')
        db.execute('INSERT INTO period_reports VALUES(?,?,?)',(job['id'],json.dumps(scope,sort_keys=True,separators=(',',':')),json.dumps(context)))
    assert supplement.attach_saved(job,scope,db_path)['result']['ai']['status']=='complete'
    with pytest.raises(ValueError,match='SUPPLEMENT_SOURCE_SCOPE'):
        supplement.attach_saved(job,{**scope,'source_factory_id':'OTHER'},db_path)


def test_incomplete_supplement_stays_partial_and_preserves_honest_coverage(monkeypatch,tmp_path):
    job,context,_,missing=fixture(monkeypatch);scope={'tenant_id':'T','source_factory_id':'F','connector':'blacklake'}
    _,_,binding=supplement.plan(job,context);path=tmp_path/'review.json'
    supplement.save(path,{**binding,'scope':scope,'accepted':{missing[0]['id']:review(missing[0])},'calls':[],'status':'running'})
    ai=supplement.attach(job,context,scope,path)['result']['ai']
    assert ai['status']=='partial'
    assert ai['analysisFactCoverage']['remaining']==9
    assert ai['analysisFactCoverage']['scope']=='program_generated_facts_not_all_source_records'


@pytest.mark.asyncio
async def test_batches_resume_and_account_for_actual_calls(monkeypatch,tmp_path):
    from smartbi.services import alert_local_ai,alert_ai_coordination
    job,context,full,missing=fixture(monkeypatch)
    monkeypatch.setattr(alert_local_ai,'provider_config',lambda:{'transport':'ssh','host':'canada-mini','model':'qwen3.8:27b'})
    calls=[]
    class Result(dict):pass
    def infer(_infer,payload,config,tenant,user):
        batch=json.loads(payload['messages'][1]['content'])['facts'];calls.append(len(batch))
        result=Result(reviews=[review(f) for f in batch]);result.usage={'requestId':str(len(calls)),'inputTokens':10,'outputTokens':5,'totalTokens':15,'durationSeconds':1}
        return result
    monkeypatch.setattr(alert_ai_coordination,'run',infer)
    path=tmp_path/'review.json';scope={'tenant_id':'T','source_factory_id':'F','connector':'blacklake'}
    first=await supplement.complete(job,context,scope,path,1,batch_size=4)
    assert calls==[4,4,2]
    assert first['result']['ai']['usage']['totalTokens']==45
    assert first['result']['ai']['analysisFactCoverage']['additionalReviewed']==10
    second=await supplement.complete(job,context,scope,path,1,batch_size=4)
    assert calls==[4,4,2]
    assert second['result']['ai']['usage']['totalTokens']==45


@pytest.mark.asyncio
async def test_history_summary_uses_the_same_validated_supplement(monkeypatch,tmp_path):
    from blacklake import period_api
    from unittest.mock import AsyncMock
    config={'tenant_id':'T','source_factory_id':'F'}
    monkeypatch.setattr(period_api,'context',AsyncMock(return_value=config))
    monkeypatch.setenv('BLACKLAKE_PERIOD_REPORT_DB',str(tmp_path/'reports.sqlite3'))
    base={'id':'a'*32,'result':{'ai':{'status':'partial'},'sectionInsights':[]}}
    monkeypatch.setattr(period_api.runtime,'store',lambda:SimpleNamespace(list=lambda *args:[base]))
    enriched=deepcopy(base);enriched['result']['ai']={'status':'complete','analysisFactCoverage':{'additionalReviewed':47,'remaining':0}}
    monkeypatch.setattr(supplement,'attach_saved',lambda *args:enriched)
    summary=await period_api.history(object(),summaries_only=True)
    assert summary['data'][0]['result']['ai']['status']=='complete'
    assert summary['data'][0]['result']['ai']['analysisFactCoverage']['remaining']==0


@pytest.mark.asyncio
async def test_full_mode_reviews_base_facts_too_and_resumes_after_repair_failure(monkeypatch,tmp_path):
    from smartbi.services import alert_local_ai,alert_ai_coordination
    job,context,original,more=fixture(monkeypatch)
    # Homogeneous bound facts isolate batching from the per-kind validators tested above.
    base=original['facts'][0]
    all_facts=[base,*more]
    pack={**original,'facts':all_facts,'omittedFacts':0}
    monkeypatch.setattr(supplement,'build',lambda report,context,include_all=False:pack if include_all else {**pack,'facts':[base]})
    monkeypatch.setattr(alert_local_ai,'provider_config',lambda:{'transport':'ssh','model':'local'})
    seen=[];bad=True
    def infer(_infer,payload,*args):
        prompt=json.loads(payload['messages'][1]['content']);seen.append(prompt)
        return {'reviews':[{**review(f),**({'owner':'不存在的部门'} if bad else {})} for f in prompt['facts']]}
    monkeypatch.setattr(alert_ai_coordination,'run',infer)
    path=tmp_path/'full.json';scope={'tenant_id':'T','source_factory_id':'F','connector':'blacklake'}
    with pytest.raises(supplement.SupplementRetry):
        await supplement.complete(job,context,scope,path,1,exhaustive=True,max_batches=1,batch_size=4)
    failed=supplement.attach(job,context,scope,path)['result']['ai']['analysisFactCoverage']
    assert failed['reviewed']==0 and failed['remaining']==11 and failed['phase']=='retrying'
    assert failed['blocker']['facts'] and failed['retryAt']
    bad=False
    first=await supplement.complete(job,context,scope,path,1,exhaustive=True,max_batches=1,batch_size=4)
    assert seen[-1]['validationFeedback']  # Previous rejection is fed back across durable claims.
    assert seen[-1]['previousRejectedReviews']
    assert all(r['factId'] in {f['id'] for f in seen[-1]['facts']} for r in seen[-1]['previousRejectedReviews'])
    progress=first['result']['ai']['analysisFactCoverage']
    assert progress['reviewed']==4 and progress['remaining']==7 and progress['additionalReviewed']==3
    accepted={f['factId'] for f in first['result']['ai']['supplementalReviews']}
    final=await supplement.complete(job,context,scope,path,1,exhaustive=True,batch_size=4)
    assert not accepted.intersection(f['id'] for call in seen[-2:] for f in call['facts'])
    assert final['result']['ai']['analysisFactCoverage']['reviewed']==11
    assert final['result']['ai']['analysisFactCoverage']['remaining']==0
    assert final['result']['ai']['status']=='complete'


@pytest.mark.asyncio
async def test_transport_failure_waits_and_expired_owner_cannot_checkpoint(monkeypatch,tmp_path):
    from smartbi.services import alert_local_ai,alert_ai_coordination
    from smartbi.services.period_reports import LeaseError
    job,context,_,_=fixture(monkeypatch);scope={'tenant_id':'T','source_factory_id':'F','connector':'blacklake'}
    monkeypatch.setattr(alert_local_ai,'provider_config',lambda:{'transport':'ssh','model':'local'})
    def offline(*args):raise OSError('private transport detail')
    monkeypatch.setattr(alert_ai_coordination,'run',offline)
    path=tmp_path/'offline.json'
    with pytest.raises(supplement.SupplementRetry):await supplement.complete(job,context,scope,path,1,exhaustive=True,max_batches=1)
    state=json.loads(path.read_text(encoding='utf8'))
    assert state['status']=='waiting_model' and 'private' not in state['lastError']
    before=path.read_bytes()
    def expired():raise LeaseError('lost')
    with pytest.raises(LeaseError):await supplement.complete(job,context,scope,path,1,exhaustive=True,guard=expired)
    assert path.read_bytes()==before


@pytest.mark.asyncio
async def test_one_invalid_fact_keeps_valid_siblings_and_repairs_only_the_invalid_one(monkeypatch,tmp_path):
    from smartbi.services import alert_local_ai,alert_ai_coordination
    job,context,original,missing=fixture(monkeypatch)
    base=original['facts'][0];pack={**original,'facts':[base,*missing],'omittedFacts':0}
    monkeypatch.setattr(supplement,'build',lambda report,context,include_all=False:pack if include_all else {**pack,'facts':[base]})
    monkeypatch.setattr(alert_local_ai,'provider_config',lambda:{'transport':'ssh','model':'local'})
    calls=[]
    def infer(_infer,payload,*args):
        facts=json.loads(payload['messages'][1]['content'])['facts'];calls.append([f['id'] for f in facts])
        return {'reviews':[{**review(f),**({'owner':'invalid'} if f['id']==missing[0]['id'] else {})} for f in facts]}
    monkeypatch.setattr(alert_ai_coordination,'run',infer)
    path=tmp_path/'partial-batch.json';scope={'tenant_id':'T','source_factory_id':'F','connector':'blacklake'}
    with pytest.raises(supplement.SupplementRetry):await supplement.complete(job,context,scope,path,1,exhaustive=True,max_batches=1)
    state=json.loads(path.read_text(encoding='utf8'))
    assert len(state['accepted'])==7
    assert calls[-1]==[missing[0]['id']]
