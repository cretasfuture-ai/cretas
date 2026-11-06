"""Protocol/candidate recall tests with fake probabilities; not real LLM acceptance."""
import asyncio,json
import pytest
from ai import registry
from ai.orchestrator import _context_read_candidate,_with_task_context,_standalone_context
from tests.test_ai_decision_layer import FakeDecision,_orch,ROWS,_empty_registry

VISIBLE=[dict(r,**({'tool_name':'material_batch_query'} if r['intent_code']=='MATERIAL_BATCH_QUERY' else {})) for r in ROWS]+[dict(id='order',intent_code='ORDER_LIST',tool_name='order_list',intent_name='订单列表',is_active=True,
    intent_category='DATA_OPERATION',description='销售订单分页'),dict(id='continue',intent_code='CONTEXT_CONTINUE',
    tool_name='context_continue',intent_name='上下文继续',is_active=True,intent_category='SYSTEM')]
def context():
    return dict(contextStatus='PRESENT',provenance='SERVER_CONVERSATION_MEMORY',references=[
        dict(type='READ_QUERY',id='ORDER_LIST',toolName='order_list',taskStatus='COMPLETED')])
def run(dec,ctx,query='接着查询'):
    return asyncio.run(_orch(dec).match(query=query,factoryId='F006',businessType='FACTORY',role='operator',
        userId='u',visible_intents=VISIBLE,history=[],task_context=ctx,enable_llm=True,min_confidence=.7))

def test_missing_current_goal_is_added_only_as_a_candidate_and_still_requires_real_selection():
    dec=FakeDecision(['CONTEXT_CONTINUE'],{'ORDER_LIST':.97})
    result=run(dec,context())
    assert set(dec.seen_options)=={'ORDER_LIST'}
    assert dec.choose_calls==1 and result.bestMatch.intentCode=='ORDER_LIST'
    assert result.timingMs['contextReadCandidateAdded']==1
    assert result.timingMs['readActionCandidatesCanonicalized']==1

def test_new_goal_remains_available_and_previous_goal_cannot_force_selection():
    dec=FakeDecision(['MATERIAL_BATCH_QUERY'],{'MATERIAL_BATCH_QUERY':.97,'ORDER_LIST':.02})
    result=run(dec,context(),'查原料库存')
    assert result.bestMatch.intentCode=='MATERIAL_BATCH_QUERY' and dec.choose_calls==1
    assert 'ORDER_LIST' in dec.seen_options

@pytest.mark.parametrize('state',['UNKNOWN','EXPIRED','NEW'])
def test_uncertain_context_never_promotes_previous_goal(state):
    ctx=context();ctx['contextStatus']=state
    assert _context_read_candidate(ctx,{r['intent_code']:r for r in VISIBLE}) is None

@pytest.mark.parametrize('change',['untrusted','changed_binding','invisible','blocked','ambiguous'])
def test_only_one_visible_completed_matching_binding_can_seed_recall(change):
    ctx=context();by={r['intent_code']:r for r in VISIBLE}
    if change=='untrusted':ctx['provenance']='CLIENT'
    elif change=='changed_binding':ctx['references'][0]['toolName']='different_tool'
    elif change=='invisible':by.pop('ORDER_LIST')
    elif change=='blocked':ctx['references'][0]['taskStatus']='BLOCKED'
    else:
        by['MATERIAL_BATCH_QUERY']=dict(tool_name='material_batch_query')
        ctx['references'].append(dict(type='READ_QUERY',id='MATERIAL_BATCH_QUERY',toolName='material_batch_query',taskStatus='COMPLETED'))
    assert _context_read_candidate(ctx,by) is None

def test_capability_contract_survives_both_new_and_existing_task_projection_without_grants():
    for ctx in [context(),dict(contextStatus='NEW')]:
        ctx['readCapabilities']=[dict(tool='material_batch_query',description='真实批次列表',parameters=['page','size','status'],
            statuses={'EXPIRED':['已过期'],'DEPLETED':['已耗尽']},authorization='ADMIN',business_rows=[{'price':'private'}])]
        turns=_with_task_context([],ctx);payload=json.loads(turns[-1]['content'].split('：',1)[1])
        assert payload['readCapabilities'][0]['statuses']['DEPLETED']==['已耗尽']
        assert payload['readCapabilities'][0]['parameters']==['page','size','status']
        assert 'ADMIN' not in turns[-1]['content'] and 'private' not in turns[-1]['content']
    assert _standalone_context(ctx,[]) # Capability-only metadata is not dialogue state or a routing grant.

def test_low_probability_is_not_raised_by_context_candidate_injection():
    dec=FakeDecision(['CONTEXT_CONTINUE'],{'ORDER_LIST':.55})
    result=run(dec,context())
    assert result.requiresConfirmation and result.confidence==.55

@pytest.mark.parametrize('extra',[{'draftTasks':[{'status':'PAUSED'}]},{'lastActionCommitted':{'status':'COMMITTED'}},
    {'hasPageReference':True},{'slotTask':{'status':'ACTIVE'}}])
def test_multiple_possible_tasks_keep_action_candidates(extra):
    ctx=context();ctx.update(extra)
    dec=FakeDecision(['CONTEXT_CONTINUE'],{'ORDER_LIST':.55,'CONTEXT_CONTINUE':.4})
    run(dec,ctx)
    assert 'CONTEXT_CONTINUE' in dec.seen_options and 'ORDER_LIST' in dec.seen_options

def test_unpaged_specialized_read_recall_gets_visible_paged_alternative():
    from ai.orchestrator import _read_capability_candidates
    by={r['intent_code']:r for r in VISIBLE}
    by['MATERIAL_EXPIRED_QUERY']=dict(tool_name='material_expired_query')
    ctx={'readCapabilities':[dict(tool='material_expired_query',parameters=[]),
        dict(tool='material_batch_query',parameters=['page','size','status'])]}
    assert _read_capability_candidates(ctx,['MATERIAL_EXPIRED_QUERY'],by,20)==['MATERIAL_EXPIRED_QUERY','MATERIAL_BATCH_QUERY']
    assert _read_capability_candidates(ctx,['CLOCK_OUT'],by,20)==['CLOCK_OUT']
    assert _read_capability_candidates(ctx,['MATERIAL_EXPIRED_QUERY'],by,1)==['MATERIAL_EXPIRED_QUERY']
    by.pop('MATERIAL_BATCH_QUERY')
    assert _read_capability_candidates(ctx,['MATERIAL_EXPIRED_QUERY'],by,20)==['MATERIAL_EXPIRED_QUERY']
