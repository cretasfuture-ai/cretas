"""Candidate contract only; mocked probabilities never establish real-model accuracy."""
import asyncio,copy
import pytest
from ai.matcher.decision import _catalog_line,build_choice_messages
from tests.test_ai_decision_layer import FakeDecision,_orch,_empty_registry
from tests.test_read_context_candidate import VISIBLE,context

class Capture(FakeDecision):
    async def shortlist(self,query,role,visible,history=None):
        self.catalog=visible
        return await super().shortlist(query,role,visible,history)
    async def choose(self,query,role,options,history=None,**kwargs):
        self.rows=options
        return await super().choose(query,role,options,history,**kwargs)

@pytest.mark.parametrize('state',['NEW','PRESENT'])
def test_actual_capability_is_bound_to_both_shortlist_and_choice_without_editing_catalog(state):
    rows=copy.deepcopy(VISIBLE);before=copy.deepcopy(rows);ctx=context();ctx['contextStatus']=state
    ctx['readCapabilities']=[dict(tool='material_batch_query',description='按保存的状态筛选，不按到期日',parameters=['page','size','status'])]
    dec=Capture(['MATERIAL_BATCH_QUERY'],{'MATERIAL_BATCH_QUERY':.55})
    result=asyncio.run(_orch(dec).match(query='查批次',factoryId='F006',businessType='FACTORY',role='operator',
        userId='u',visible_intents=rows,history=[],task_context=ctx,enable_llm=True,min_confidence=.7))
    coarse=next(r for r in dec.catalog if r['intent_code']=='MATERIAL_BATCH_QUERY')
    fine=next(r for r in dec.rows if r['intent_code']=='MATERIAL_BATCH_QUERY')
    assert '按保存的状态' in _catalog_line(coarse)
    text=build_choice_messages('查批次','operator',[fine])[1]['content']
    assert '按保存的状态' in text and 'page' in text
    assert rows==before
    assert result.requiresConfirmation and result.confidence==.55

@pytest.mark.parametrize('provenance',[None,'CLIENT'])
def test_untrusted_capability_cannot_replace_visible_option(provenance):
    ctx=context();ctx['provenance']=provenance
    ctx['readCapabilities']=[dict(tool='material_batch_query',description='CLIENT_ONLY_VALUE',parameters=['page'])]
    dec=Capture(['MATERIAL_BATCH_QUERY'],{'MATERIAL_BATCH_QUERY':.97})
    asyncio.run(_orch(dec).match(query='查批次',factoryId='F006',businessType='FACTORY',role='operator',userId='u',
        visible_intents=VISIBLE,history=[],task_context=ctx,enable_llm=True,min_confidence=.7))
    assert all('_read_capability' not in r for r in dec.catalog+dec.rows)
    assert 'CLIENT_ONLY_VALUE' not in str(dec.seen_history)

@pytest.mark.parametrize('provenance',[None,'CLIENT','SERVER_CONVERSATION_MEMORY'])
def test_all_capability_paths_share_the_same_trust_boundary(provenance):
    from ai.orchestrator import _with_task_context,_read_capability_candidates
    ctx=dict(contextStatus='PRESENT',provenance=provenance,readCapabilities=[
        dict(tool='unp',parameters=[],description='CLIENT_ONLY_DESCRIPTION',clarification='CLIENT_ONLY_CLARIFICATION'),
        dict(tool='pgd',parameters=['page','size'])])
    by={'A':dict(tool_name='unp'),'B':dict(tool_name='pgd')}
    text=str(_with_task_context([],ctx));codes=_read_capability_candidates(ctx,['A'],by,20)
    trusted=provenance=='SERVER_CONVERSATION_MEMORY'
    assert ('CLIENT_ONLY_DESCRIPTION' in text)==trusted
    assert ('CLIENT_ONLY_CLARIFICATION' in text)==trusted
    assert codes==(['A','B'] if trusted else ['A'])

def test_capability_annotations_never_create_a_catalog_option_or_render_business_rows():
    from ai.matcher.decision import intent_text
    row=dict(intent_code='A',tool_name='real',_read_capability=dict(tool='wrong',description='PRIVATE'))
    assert 'PRIVATE' not in intent_text(row)
    row['_read_capability']=dict(tool='real',description='状态列表',parameters=['page'],business_rows=[{'price':'PRIVATE'}],permission='ROOT')
    assert 'PRIVATE' not in intent_text(row) and 'ROOT' not in intent_text(row)

@pytest.mark.parametrize('provenance',['CLIENT','SERVER_CONVERSATION_MEMORY'])
def test_paged_scope_alternatives_are_recalled_without_picking_a_goal(provenance):
    from ai.orchestrator import _read_capability_candidates
    ctx=dict(provenance=provenance,readCapabilities=[
        dict(tool='status',parameters=['page','size','status'],domains=['material'],clarification='按状态'),
        dict(tool='expiry',parameters=['page','size'],domains=['material'],clarification='按到期日'),
        dict(tool='other',parameters=['page','size'],domains=['order'],clarification='按订单')])
    by={'S':dict(tool_name='status'),'E':dict(tool_name='expiry'),'O':dict(tool_name='other')}
    expected=['E','S'] if provenance=='SERVER_CONVERSATION_MEMORY' else ['E']
    assert _read_capability_candidates(ctx,['E'],by,20)==expected
    assert _read_capability_candidates(ctx,['E'],by,1)==['E']
    by.pop('S')
    assert _read_capability_candidates(ctx,['E'],by,20)==['E']

@pytest.mark.parametrize('provenance',['CLIENT','SERVER_CONVERSATION_MEMORY'])
def test_visible_category_can_seed_real_scope_alternatives_when_coarse_tool_has_no_declaration(provenance):
    from ai.orchestrator import _read_capability_candidates
    ctx=dict(provenance=provenance,readCapabilities=[
        dict(tool='status',clarification='按状态',domains=['material']),
        dict(tool='expiry',clarification='按日期',domains=['material']),
        dict(tool='order',clarification='按订单',domains=['order'])])
    by={code:dict(tool_name=tool,intent_category=category) for code,tool,category in
        [('A','alert','MATERIAL'),('S','status','MATERIAL'),('E','expiry','MATERIAL'),('O','order','ORDER')]}
    assert _read_capability_candidates(ctx,['A'],by,20)==(['A','S','E'] if provenance=='SERVER_CONVERSATION_MEMORY' else ['A'])
    assert _read_capability_candidates(ctx,['A'],by,2)==(['A','S'] if provenance=='SERVER_CONVERSATION_MEMORY' else ['A'])
    by.pop('E')
    assert 'E' not in _read_capability_candidates(ctx,['A'],by,20)

def test_previous_read_goal_seeds_peers_before_expansion_without_raising_probability():
    rows=copy.deepcopy(VISIBLE)+[dict(id='expiry',intent_code='MATERIAL_EXPIRED_QUERY',intent_name='日期清单',
        tool_name='material_expired_query',is_active=True,intent_category='MATERIAL',description='按日期')]
    ctx=context();ctx['references']=[dict(type='READ_QUERY',id='MATERIAL_BATCH_QUERY',toolName='material_batch_query',taskStatus='COMPLETED')]
    ctx['readCapabilities']=[dict(tool=tool,parameters=['page','size'],clarification='真实口径',domains=['material'])
        for tool in ['material_batch_query','material_expired_query']]
    dec=Capture(['CONTEXT_CONTINUE'],{'MATERIAL_EXPIRED_QUERY':.55})
    result=asyncio.run(_orch(dec).match(query='更换口径',factoryId='F006',businessType='FACTORY',role='operator',userId='u',
        visible_intents=rows,history=[],task_context=ctx,enable_llm=True,min_confidence=.7))
    assert {'MATERIAL_BATCH_QUERY','MATERIAL_EXPIRED_QUERY'}<=set(dec.seen_options)
    assert result.requiresConfirmation and result.confidence==.55
