import copy
import json

import httpx
import pytest

from blacklake import analyst_supplement as module


def test_single_decision_has_no_other_case_context():
    payload = module.decision_payload('asserted')
    assert [case['id'] for case in json.loads(payload['input'])] == ['asserted']
    assert [question['name'] for question in payload['questions']] == ['asserted']
    with pytest.raises(module.TeacherError, match='UNKNOWN'):
        module.decision_payload('private-record')


def test_lineage_teacher_egress_is_fixed_synthetic_cases_only(client):
    payload=module.teacher_payload('gpt-6.1-sol','high','lineage')
    assert all(row['synthetic'] is True for row in json.loads(payload['input'])['cases'])
    payload['input']='actual factory batch or work order'
    with pytest.raises(module.TeacherError,match='SYNTHETIC_EGRESS_ONLY'):
        client.call('lineage','gpt-6.1-sol',payload,'responses')


def test_budget_shares_prior_cost_and_stops_before_new_dollar_limit(tmp_path):
    ledger=module.SupplementBudget(tmp_path/'budget.db')
    payload=module.teacher_payload('gpt-6-astra','medium')
    first=ledger.reserve_openai('one','gpt-6-astra',payload)
    with pytest.raises(module.TeacherError,match='BUDGET_STOP'):
        ledger.reserve_openai('too_big','gpt-6-astra',{**payload,'input':'x'*100000})
    ledger.settle_openai(first,{'input_tokens':1000,'output_tokens':100})
    assert ledger.total()==30000
    assert module.SupplementBudget(tmp_path/'budget.db').total()==30000


def test_unknown_usage_keeps_reserved_budget(tmp_path):
    ledger=module.SupplementBudget(tmp_path/'budget.db')
    call=ledger.reserve_openai('one','gpt-6-astra',module.teacher_payload('gpt-6-astra','high'))
    initial=ledger.total()
    with pytest.raises(module.TeacherError,match='USAGE_UNKNOWN'):
        ledger.settle_openai(call,{})
    assert ledger.total()==initial and not ledger.can_resume()


def test_decisions_are_input_only_and_rounded_up(tmp_path):
    ledger=module.SupplementBudget(tmp_path/'budget.db')
    call=ledger.reserve_openai('decision','decisions/gpt-6-luna',module.decision_payload())
    ledger.settle_openai(call,{'input_tokens':1001,'output_tokens':9999})
    assert ledger.total()==251


@pytest.fixture
def client(monkeypatch,tmp_path):
    monkeypatch.setattr(module,'config',lambda:{'OPENAI_API_KEY':'synthetic-key-not-real'})
    obj=module.Supplement(tmp_path/'responses',module.SupplementBudget(tmp_path/'budget.db'))
    obj.client.close()
    yield obj
    obj.client.close()


def test_real_data_or_arbitrary_payload_cannot_leave_machine(client):
    payload=module.teacher_payload('gpt-6-astra','medium')
    payload['input']='private factory data'
    with pytest.raises(module.TeacherError,match='SYNTHETIC_EGRESS_ONLY'):
        client.call('one','gpt-6-astra',payload,'responses')
    with pytest.raises(module.TeacherError,match='BILLING_MISMATCH'):
        client.call('one','gpt-6.1-sol',module.teacher_payload('gpt-6-astra','high'),'responses')
    assert client.ledger.total()==0


def test_supplement_cache_binds_effort_and_does_not_double_charge(client):
    seen=[]
    def reply(req):
        seen.append(json.loads(req.content))
        return httpx.Response(200,json={'status':'completed','usage':{'input_tokens':1000,'output_tokens':100},'output':[]})
    client.client=httpx.Client(transport=httpx.MockTransport(reply))
    p=module.teacher_payload('gpt-6-astra','medium')
    client.call('one','gpt-6-astra',p,'responses')
    client.call('one','gpt-6-astra',p,'responses')
    assert len(seen)==1 and client.ledger.total()==30000
    assert seen[0]['store'] is False and 'temperature' not in seen[0]
    assert seen[0]['text']['format']['strict'] is True


def test_network_unknown_has_no_automatic_retry(client):
    def fail(req):raise httpx.ReadTimeout('synthetic')
    client.client=httpx.Client(transport=httpx.MockTransport(fail))
    with pytest.raises(module.TeacherError,match='NETWORK_UNKNOWN'):
        client.call('one','gpt-6-astra',module.teacher_payload('gpt-6-astra','medium'),'responses')
    with pytest.raises(module.TeacherError,match='UNCERTAIN_PRIOR'):
        client.call('two','gpt-6-astra',module.teacher_payload('gpt-6-astra','medium'),'responses')


def test_unknown_usage_preserves_response_and_cannot_silently_reuse(client):
    client.client=httpx.Client(transport=httpx.MockTransport(lambda req:
        httpx.Response(200,json={'status':'incomplete','output':[{'content':'preserved long answer'}]})))
    payload=module.teacher_payload('gpt-6-astra','medium')
    with pytest.raises(module.TeacherError,match='USAGE_UNKNOWN'):
        client.call('one','gpt-6-astra',payload,'responses')
    artifact=json.loads(next(client.directory.glob('*.json')).read_text(encoding='utf8'))
    assert artifact['body']['output'][0]['content']=='preserved long answer'
    assert artifact['conservativeDebitedMicroUsd'] is None
    with pytest.raises(module.TeacherError,match='UNCERTAIN_PRIOR'):
        client.call('one','gpt-6-astra',payload,'responses')
