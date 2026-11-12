import asyncio
from copy import deepcopy
from types import SimpleNamespace
import json
import pytest
from fastapi import HTTPException

from smartbi.services.analysis_batches import Queue,payload,validate

def item(identity='F001'):
    return {'id':identity,'cohort':'eligible26','sectionId':'material','kind':'material_link_coverage',
        'statistics':{'linkedLines':1},'considerations':[],
        'display':{'id':identity,'text':'PRIVATE 工单对应材料来源已核，批次仍待核。','entityRefs':[{'projectId':1}]}}

def answer(items):
    return {'analyses':[{'factId':r['id'],'whatItMeans':'材料单据已有记录，批次仍需核对。','whyItMatters':'账面归属需要核实，不能直接判断浪费。',
        'hypotheses':[],'nextStep':'请仓库核对实际批号和分配记录。','owner':'仓库','cannotConclude':'尚不能证明原料到成品批次贯通。','followupQuestion':'还缺哪些批次凭证？'} for r in items]}

def test_queue_resumes_exact_facts_and_rejects_other_factory(tmp_path):
    q=Queue(tmp_path/'queue.sqlite3');scope={'tenant':'A'};items=[item(f'F{i:03d}') for i in range(8)]
    run=q.submit(scope,'source',items,batch_size=6)
    assert q.submit(scope,'source',items,batch_size=6)==run
    with pytest.raises(ValueError,match='SCOPE'):q.read(run,{'tenant':'OTHER'})
    first=q.claim(run);assert len(first['items'])==6 and q.claim(run) is None
    q.finish(first,validate(answer(first['items']),first['items']))
    resumed=Queue(tmp_path/'queue.sqlite3');assert resumed.read(run,scope)['remainingFacts']==2
    second=resumed.claim(run);resumed.finish(second,validate(answer(second['items']),second['items']))
    result=resumed.read(run,scope)
    assert result['status']=='complete' and result['analyzedFacts']==8 and result['remainingFacts']==0
    with pytest.raises(ValueError,match='LEASE_LOST'):q.finish(first,[])


def test_prioritizing_waiting_evidence_does_not_touch_the_running_job(tmp_path):
    q=Queue(tmp_path/'q.sqlite3');scope={'tenant':'A'}
    run=q.submit(scope,'source',[item(),{**item('F002'),'cohort':'original44'}],batch_size=1)
    first=q.claim(run);assert first['items'][0]['cohort']=='eligible26'
    assert q.prioritize(run,'original44')==1 and q.claim(run) is None
    q.finish(first,validate(answer(first['items']),first['items']))
    assert q.claim(run)['items'][0]['cohort']=='original44'


def test_splitting_a_failed_batch_preserves_every_fact_and_original_calls(tmp_path):
    q=Queue(tmp_path/'q.sqlite3');scope={'tenant':'A'}
    items=[{**item(f'F{i:03d}'),'kind':'work_order_detail'} for i in range(7)]
    run=q.submit(scope,'source',items);job=q.claim(run)
    with pytest.raises(ValueError,match='WAIT_FOR_ACTIVE'):q.split_pending(run,'work_order_detail')
    q.call(job['id'],{},error='SYNTHETIC_FAILURE');q.finish(job,error='SYNTHETIC_FAILURE')
    assert q.split_pending(run,'work_order_detail')==1
    while (job:=q.claim(run)) is not None:q.finish(job,validate(answer(job['items']),job['items']))
    result=q.read(run,scope)
    assert result['status']=='complete' and result['analyzedFacts']==7 and result['calls']==1
    assert q.submit(scope,'source',items)==run and q.read(run,scope)['status']=='complete'

def test_failed_or_uninterpreted_facts_never_count_as_complete(tmp_path):
    q=Queue(tmp_path/'q.sqlite3');run=q.submit({'tenant':'A'},'source',[item()])
    job=q.claim(run);q.finish(job,error='BAD_OUTPUT')
    result=q.read(run,{'tenant':'A'})
    assert result['status']=='failed' and result['remainingFacts']==1 and not result['results']


def test_a_successful_job_cannot_hide_unanswered_facts(tmp_path):
    q=Queue(tmp_path/'q.sqlite3');run=q.submit({'tenant':'A'},'source',[item(),item('F002')]);job=q.claim(run)
    with pytest.raises(ValueError,match='INCOMPLETE_RESULTS'):q.finish(job,validate(answer([item()]),[item()]))
    assert q.read(run,{'tenant':'A'})['analyzedFacts']==0

@pytest.mark.parametrize('mutation',[lambda v:v['analyses'].clear(),lambda v:v['analyses'][0].update(factId='OTHER'),
    lambda v:v['analyses'][0].update(whatItMeans='材料成本123元'),lambda v:v['analyses'][0].update(owner='外部供应商'),
    lambda v:v['analyses'][0].update(whatItMeans='原因就是生产浪费，已经确定。')])
def test_every_fact_and_financial_claim_is_validated(mutation):
    data=[item()];raw=answer(data);mutation(raw)
    with pytest.raises(ValueError):validate(raw,data)

def test_model_payload_never_includes_real_display_names_or_record_ids():
    wire=json.dumps(payload([item()],'qwen3.8:27b'),ensure_ascii=False)
    assert 'PRIVATE' not in wire and 'entityRefs' not in wire and 'projectId' not in wire
    assert 'linkedLines' in wire and 'allowedOwners' in wire

def test_unresolved_batch_boundary_is_required_for_each_work_order():
    data=[{**item(),'kind':'work_order_detail'}];raw=answer(data)
    raw['analyses'][0].update(whatItMeans='材料和人工已有账面记录。',cannotConclude='尚不能证明实际生产没有浪费。')
    with pytest.raises(ValueError,match='ORDER_BATCH_BOUNDARY'):validate(raw,data)


def test_merely_mentioning_batches_does_not_allow_claiming_the_chain_is_proven():
    data=[{**item(),'kind':'work_order_detail','breakdown':{'actualBatchChainVerified':False}}];raw=answer(data)
    raw['analyses'][0]['whatItMeans']='原料批次已经完全贯通，成本链已核实。'
    with pytest.raises(ValueError,match='UNPROVEN_CHAIN'):validate(raw,data)
    raw['analyses'][0]['whatItMeans']='当前不能证明原料批次已经贯通。'
    assert validate(raw,data)


def test_no_fully_eligible_order_does_not_mean_all_its_fields_are_missing():
    data=[{**item(),'kind':'product_coverage','statistics':{'sampleSize':1,'eligibleCount':0}}];raw=answer(data)
    raw['analyses'][0]['whatItMeans']='这张工单不具备任何核算字段，无法分析。'
    with pytest.raises(ValueError,match='PARTIAL_FIELDS'):validate(raw,data)


def test_only_verified_full_coverage_phrase_can_be_rendered_without_a_number():
    data=[{**item(),'kind':'product_coverage','statistics':{'eligibleRatio':1}}];raw=answer(data)
    raw['analyses'][0]['whatItMeans']='该子集工单覆盖率为百分之百，但不能证明全厂完整。'
    before=deepcopy(raw);result=validate(raw,data)
    assert '所选样本均通过字段门槛' in result[0]['whatItMeans'] and raw==before
    data[0]['statistics']['eligibleRatio']=0.5
    with pytest.raises(ValueError,match='UNBOUND_NUMBER'):validate(raw,data)


def test_truncated_model_original_is_retained_privately_with_usage(monkeypatch,tmp_path):
    from smartbi.services import alert_local_ai as provider
    text='{"analyses":[{"factId":"unfinished'
    response={'model':'qwen3.8:27b','message':{'content':text},'prompt_eval_count':10,'eval_count':7,'total_duration':1000000000}
    monkeypatch.setattr(provider.subprocess,'run',lambda *a,**k:SimpleNamespace(returncode=0,stdout=json.dumps(response)))
    with pytest.raises(provider.LocalAIOutputError) as caught:provider.infer({'model':'qwen3.8:27b'},{'transport':'ssh','host':'canada-mini'})
    assert caught.value.raw_content==text and str(caught.value)=='LOCAL_AI_OUTPUT_INVALID'
    q=Queue(tmp_path/'q.sqlite3');run=q.submit({'tenant':'A'},'source',[item()]);job=q.claim(run)
    q.call(job['id'],{},error=str(caught.value),usage=caught.value.usage,raw_content=caught.value.raw_content)
    with q.connect() as db:stored=json.loads(db.execute('SELECT response_json FROM calls').fetchone()[0])
    assert stored['unparsedModelText']==text and q.read(run,{'tenant':'A'})['usage']['totalTokens']==17

def test_exhaustive_mode_preserves_the_facts_omitted_by_the_summary():
    from smartbi.services.analysis_evidence import build
    report={'scope':'A','summary':{'projects':60,'costEligible':0},'items':[]}
    context={'cost':{'projects':[{'productCode':str(i),'unit':'kg','finishedGood':1,'unitCostAvailable':False,'product':'PRIVATE'} for i in range(60)]},'production':{}}
    bounded=build(report,context);full=build(report,context,include_all=True)
    assert full['omittedFacts']==0 and bounded['omittedFacts']>0
    assert {r['id'] for r in bounded['facts']} < {r['id'] for r in full['facts']}

def test_complete_analysis_authority_is_checked_before_saved_cost_read(monkeypatch):
    from blacklake import smartbi_api as api,complete_cost_store
    monkeypatch.setenv('BLACKLAKE_SMARTBI_CONFIG','synthetic')
    monkeypatch.setattr(api,'settings',lambda path:{'tenant_id':'T','source_factory_id':'F','run_id':'S'})
    reads=[];monkeypatch.setattr(complete_cost_store,'read',lambda config:reads.append(config) or {})
    async def denied(*args):raise HTTPException(403,'denied')
    monkeypatch.setattr(api,'cost_authority',denied)
    request=SimpleNamespace(state=SimpleNamespace(auth_method='jwt',factory_id='T',role='factory_super_admin'))
    with pytest.raises(HTTPException):asyncio.run(api.complete_cost_analysis(request))
    assert not reads

def test_followup_does_not_mix_unready_or_other_evidence():
    from blacklake.complete_cost_store import followup
    results=validate(answer([item()]),[item()])
    data={'sourceRunId':'S','sourceCheckedAt':'synthetic-checked-at','batchAnalysis':{'results':results}}
    value=followup(data,'F001','找谁补？')
    assert 'PRIVATE' in value['answer'] and value['currentRequestUsage']['totalTokens']==0
    with pytest.raises(ValueError):followup(data,'OTHER','为什么？')


def test_unknown_or_failed_usage_is_retained_without_inventing_zero(tmp_path):
    q=Queue(tmp_path/'q.sqlite3');run=q.submit({'tenant':'A'},'source',[item()]);job=q.claim(run)
    identity=q.call(job['id'],{},error='IN_FLIGHT')
    assert q.read(run,{'tenant':'A'})['usageUnknownCalls']==1
    q.call(job['id'],{},error='INVALID_JSON',call_id=identity,usage={'inputTokens':10,'outputTokens':3,'totalTokens':13,'durationSeconds':1,'requestId':'known'})
    result=q.read(run,{'tenant':'A'})
    assert result['usage']['totalTokens']==13 and result['failedValidationCalls']==1


def test_saved_report_rejects_scope_or_cohort_tampering(tmp_path,monkeypatch):
    from blacklake.complete_cost_store import read
    scope={'tenantId':'T','sourceFactoryId':'F','baselineAnchor':'BASE'}
    value={'schema':'blacklake-complete-cost/1','scope':scope,'sourceRunId':'S',
        'analysis':{'runId':'S','costAlerts':{'runId':'S'},'cost':{'projects':[{'projectId':i,'unitCostAvailable':True} for i in range(26)]}},
        'batchAnalysis':{'scope':scope,'status':'running','analyzedFacts':0,'expectedFacts':1,'remainingFacts':1,'results':[]}}
    path=tmp_path/'report.json';path.write_text(json.dumps(value),encoding='utf8');monkeypatch.setenv('BLACKLAKE_COMPLETE_COST_FILE',str(path))
    config={'tenant_id':'T','source_factory_id':'F','run_id':'BASE'}
    assert len(read(config)['analysis']['cost']['projects'])==26
    with pytest.raises(ValueError,match='SCOPE'):read({**config,'tenant_id':'OTHER'})
    value['analysis']['cost']['projects'][0]['unitCostAvailable']=False;path.write_text(json.dumps(value),encoding='utf8')
    with pytest.raises(ValueError,match='COHORT'):read(config)
