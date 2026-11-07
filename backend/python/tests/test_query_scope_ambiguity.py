"""Scope evidence/control-flow tests;fake probabilities do not prove LLM quality."""
import asyncio
import math
from dataclasses import replace

import pytest
from ai import registry

from ai.matcher.decision import DecisionMatcher, build_scope_messages
from ai.orchestrator import _scope_peer_rows, _trusted_read_capabilities
from tests.test_ai_decision_layer import FakeDecision, _orch, _empty_registry, CFG


ROWS = [dict(id='stock', intent_code='MATERIAL_BATCH_QUERY', intent_name='批次查询', tool_name='stock_list',
             intent_category='MATERIAL', description='现存批次', is_active=True),
        dict(id='due', intent_code='DUE_LIST', intent_name='待处理批次', tool_name='due_list',
             intent_category='MATERIAL', description='按固定日期条件', is_active=True),
        dict(id='order', intent_code='ORDER_LIST', intent_name='订单列表', tool_name='order_list',
             intent_category='SALES', description='销售订单', is_active=True)]
CTX = dict(contextStatus='NEW', provenance='SERVER_CONVERSATION_MEMORY', readCapabilities=[
    dict(tool='stock_list', domains=['material'], clarification='按保存状态筛选', parameters=['status']),
    dict(tool='due_list', domains=['material'], clarification='按到期日条件筛选', parameters=[]),
    dict(tool='order_list', domains=['sales'], parameters=['status'])])


class ScopeDecision(FakeDecision):
    def __init__(self, probability, chosen='MATERIAL_BATCH_QUERY'):
        super().__init__([r['intent_code'] for r in ROWS], {chosen:.97, 'DUE_LIST':.02} if chosen!='DUE_LIST' else {chosen:.97,'MATERIAL_BATCH_QUERY':.02})
        self.probability=probability;self.scope_calls=[]

    async def scope_ambiguity(self, query, role, options, history=None):
        self.scope_calls.append((query, options, history))
        if isinstance(self.probability, Exception):raise self.probability
        return self.probability


def run(decision, context=CTX, rows=ROWS):
    return asyncio.run(_orch(decision,cfg=replace(CFG,decision_special_options=True,decision_auto_threshold=.8)).match(
        query='当前查询',factoryId='F006',businessType='FACTORY',role='operator',userId='7',visible_intents=rows,
        history=[],task_context=context,enable_llm=True,min_confidence=.7))


def test_high_intent_probability_cannot_replace_an_unconfirmed_scope_basis():
    decision=ScopeDecision(.98);result=run(decision)
    assert result.requiresConfirmation and result.confidence==.97
    assert result.timingMs['queryScopeClarify']==1
    assert [c.intentCode for c in result.topCandidates]==['MATERIAL_BATCH_QUERY','DUE_LIST']
    assert [c.confidence for c in result.topCandidates]==[.97,.02]
    assert len(decision.scope_calls)==1


def test_negative_scope_evidence_does_not_raise_or_replace_the_original_choice_score():
    decision=ScopeDecision(.01);result=run(decision)
    assert not result.requiresConfirmation and result.confidence==.97
    assert result.bestMatch.intentCode=='MATERIAL_BATCH_QUERY'
    assert result.timingMs['queryScopeAmbiguousP']==10


@pytest.mark.parametrize('probability',[.21,.5,.79,.8])
def test_uncertain_scope_evidence_does_not_silently_inherit_a_high_intent_score(probability):
    result=run(ScopeDecision(probability))
    assert result.requiresConfirmation and result.confidence==.97
    assert result.timingMs['queryScopeClarify']==1


@pytest.mark.parametrize('probability',[None, True, float('nan'),float('inf'),-0.1,1.1,RuntimeError('unavailable')])
def test_missing_invalid_and_failed_scope_evidence_clarifies_instead_of_executing(probability):
    result=run(ScopeDecision(probability))
    assert result.requiresConfirmation and result.timingMs['queryScopeUnavailable']==1


def test_an_unrelated_simple_query_does_not_add_a_scope_model_call():
    decision=ScopeDecision(.99,'ORDER_LIST');result=run(decision)
    assert result.bestMatch.intentCode=='ORDER_LIST' and not result.requiresConfirmation
    assert not decision.scope_calls


def test_uncertain_goal_keeps_other_domain_choices_instead_of_narrowing_to_scope_peers():
    decision=ScopeDecision(.99)
    decision.probs={'MATERIAL_BATCH_QUERY':.55,'ORDER_LIST':.4,'DUE_LIST':.05}
    result=run(decision)
    assert result.requiresConfirmation and result.confidence==.55
    assert not decision.scope_calls
    assert [c.intentCode for c in result.topCandidates]==['MATERIAL_BATCH_QUERY','ORDER_LIST','DUE_LIST']


def test_uncertain_route_offers_missing_actual_scope_peer_without_boosting_or_a_second_model_call():
    decision=ScopeDecision(.99)
    decision.probs={'MATERIAL_BATCH_QUERY':.65,'ORDER_LIST':.3}
    result=run(decision)
    assert result.requiresConfirmation and result.confidence==.65
    assert [c.intentCode for c in result.topCandidates]==['MATERIAL_BATCH_QUERY','ORDER_LIST','DUE_LIST']
    assert [c.confidence for c in result.topCandidates]==[.65,.3,None]
    assert not decision.scope_calls


def test_client_metadata_invisible_tools_and_same_tool_aliases_cannot_create_scope_peers():
    by={r['intent_code']:r for r in ROWS}
    assert len(_scope_peer_rows(CTX,by,'MATERIAL_BATCH_QUERY'))==2
    assert not _scope_peer_rows(dict(CTX,provenance='CLIENT'),by,'MATERIAL_BATCH_QUERY')
    assert not _scope_peer_rows(CTX,{k:v for k,v in by.items() if k!='DUE_LIST'},'MATERIAL_BATCH_QUERY')
    by['DUE_LIST']=dict(by['DUE_LIST'],tool_name='stock_list')
    assert not _scope_peer_rows(CTX,by,'MATERIAL_BATCH_QUERY')


def test_scope_choices_preserve_the_selected_alias_and_unknown_peer_scores():
    alias=dict(ROWS[0],id='alias',intent_code='STOCK_ALIAS')
    rows=ROWS+[alias]
    decision=ScopeDecision(.98);decision.probs={'MATERIAL_BATCH_QUERY':.97}
    result=run(decision,rows=rows)
    assert result.requiresConfirmation and result.confidence==.97
    assert [c.intentCode for c in result.topCandidates]==['MATERIAL_BATCH_QUERY','DUE_LIST']
    assert [c.confidence for c in result.topCandidates]==[.97,None]


def test_reviewed_exact_record_stays_a_candidate_when_actual_query_scopes_compete():
    registry.set_registry(registry.build_snapshot([{'factory_id':'F006','business_type':'FACTORY',
        'normalized_text':'当前查询','intent_code':'MATERIAL_BATCH_QUERY'}],[]))
    registry.get_registry().standalone_routes=frozenset({('F006','FACTORY','当前查询','MATERIAL_BATCH_QUERY')})
    decision=ScopeDecision(.99);result=run(decision)
    assert decision.choose_calls==1 and len(decision.scope_calls)==1
    assert result.requiresConfirmation and result.timingMs['registryCandidateOnly']==1
    assert result.timingMs.get('registryHit',0)==0


def test_scope_prompt_uses_actual_declarations_and_existing_context_without_a_selected_default():
    caps={c['tool']:c for c in _trusted_read_capabilities(CTX)}
    options=[dict(r,_read_capability=caps[r['tool_name']]) for r in ROWS[:2]]
    messages=build_scope_messages('当前问题','operator',options,[dict(role='user',content='已确认的历史')])
    assert '按保存状态筛选' in messages[1]['content'] and '按到期日条件筛选' in messages[1]['content']
    assert '已确认的历史' in messages[1]['content']
    assert '业务请求' not in messages[0]['content'] or '普通缺参' in messages[0]['content']


def test_actual_status_contract_is_visible_to_shortlist_choice_and_scope_without_defaults():
    from ai.matcher.decision import build_shortlist_messages,build_choice_messages
    cap=dict(tool='stock_list',domains=['material'],description='查询保存的状态',clarification='按保存状态筛选',
             parameters=['status'],statuses={'DEPLETED':['已耗尽'],'INSPECTING':['检验中','待检验','待质检']})
    projected=_trusted_read_capabilities(dict(CTX,readCapabilities=[cap]))[0]
    row=dict(ROWS[0],_read_capability=projected)
    groups=[build_shortlist_messages('当前问题','operator',[row]),
            build_choice_messages('当前问题','operator',[row]),
            build_scope_messages('当前问题','operator',[row])]
    for messages in groups:
        text=str(messages)
        assert 'DEPLETED(已耗尽)' in text and 'INSPECTING(检验中/待检验)' in text
        assert '待质检' not in text


def test_default_provider_scope_read_reuses_existing_slot_and_requires_model_probability():
    class Matcher(DecisionMatcher):
        async def _call(self,payload,body_validator=None,content_validator=None):
            assert payload['logprobs'] and payload['messages'][-1]['partial']
            body={'choices':[{'logprobs':{'content':[{'top_logprobs':[{'token':'A','logprob':math.log(.9)},
                                                                   {'token':'B','logprob':math.log(.1)}]}]}}]}
            assert body_validator(body) is None
            return body
    assert asyncio.run(Matcher().scope_ambiguity('当前问题','operator',[]))==pytest.approx(.9)
