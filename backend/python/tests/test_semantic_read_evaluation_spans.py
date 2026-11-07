"""Evidence collector tests,not model or business acceptance."""
import contextvars
import asyncio
from dataclasses import replace
import importlib.util
from pathlib import Path

import pytest


def module(tmp_path):
    path=Path(__file__).resolve().parents[3]/'tests/semantic-read/evaluation_spans.py'
    spec=importlib.util.spec_from_file_location('evaluation_spans_test',path)
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result)
    result.configure(tmp_path/'spans.json');return result


def test_late_call_keeps_its_original_span_instead_of_the_next_http_turn(tmp_path):
    m=module(tmp_path);m.begin('a');token=m.bind_span();old=contextvars.copy_context();m.reset_span(token)
    m.end('a');m.begin('b')
    ask=m.wrap_ask(lambda *args:{'answers':[{'name':'scope','type':'predicate','probability':.5}]})
    with pytest.raises(RuntimeError,match='span closed'):
        old.run(ask,{'model':'fake','questions':[{'name':'scope','type':'predicate'}]},'decisions','scope')
    assert len(m._spans['a']['calls'])==1 and not m.end('b')['calls']
    assert m._spans['a']['calls'][0]['state']=='blocked_late' and m._spans['a']['errors']


@pytest.mark.parametrize('answer',[{'name':'scope','type':'refusal'},
    {'name':'scope','type':'predicate','probability':True},
    {'name':'scope','type':'predicate','probability':float('nan')}])
def test_invalid_or_refused_predicate_is_an_error_even_if_product_falls_back_to_clarification(tmp_path,answer):
    m=module(tmp_path);m.begin('a')
    result=m.wrap_ask(lambda *args:{'answers':[answer]})(
        {'model':'fake','questions':[{'name':'scope','type':'predicate'}]},'decisions','scope')
    assert result=={'answers':[answer]} # Never rewrites product output or probability.
    assert m.end('a')['errors']


def test_failed_model_call_and_replayed_spans_remain_visible(tmp_path):
    m=module(tmp_path);m.begin('a')
    def fail(*args):raise TimeoutError('test failure')
    with pytest.raises(TimeoutError):m.wrap_ask(fail)({'model':'fake'},'responses','read-turn')
    evidence=m.end('a');assert evidence['errors'] and evidence['calls'][0]['state']=='failed'
    with pytest.raises(RuntimeError):m.begin('a')
    with pytest.raises(RuntimeError):m.wrap_ask(fail)({'model':'fake'},'responses','read-turn')


def test_contextual_fail_closed_is_unknown_evidence_even_without_a_legacy_fallback(tmp_path):
    from tests.test_ai_decision_layer import FakeDecision,_orch,ROWS,CFG
    class Broken(FakeDecision):
        async def choose(self,*args,**kwargs):raise RuntimeError('invalid model protocol')
    decision=Broken(['MATERIAL_BATCH_QUERY'],{})
    result=asyncio.run(_orch(decision,cfg=replace(CFG,decision_special_options=True)).match(query='继续',
        factoryId='F006',businessType='FACTORY',role='operator',userId='7',visible_intents=ROWS,history=[],
        task_context={'contextStatus':'PRESENT'},enable_llm=True,min_confidence=.7))
    m=module(tmp_path);m.begin('a')
    failures=m.model_evidence_failures(result.timingMs)
    assert result.requiresConfirmation and 'decisionUnavailable' in failures and 'contextFallbackBlocked' in failures
    for failure in failures:m.mark_error(failure)
    assert m.end('a')['errors']
