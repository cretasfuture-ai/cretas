import json
from concurrent.futures import ThreadPoolExecutor

import httpx
import pytest

from blacklake.teacher_eval import CASE_PLAN, Ledger, Teacher, TeacherError, cases_from, synthesis_input


def test_budget_refuses_before_send_and_cannot_reset_on_restart(tmp_path):
    ledger = Ledger(tmp_path / 'budget.db')
    for i in range(100):
        ledger.reserve(str(i), 'claude-opus-5-5', '', 1, system='')
    used = ledger.total()
    assert used < 10_000_000
    with pytest.raises(TeacherError, match='BUDGET_STOP'):
        ledger.reserve('too_large', 'claude-opus-5-5', 'x' * 3_000_000, 8000)
    assert Ledger(tmp_path / 'budget.db').total() == used
    with pytest.raises(TeacherError, match='ALREADY_SENT'):
        ledger.reserve('0', 'claude-opus-5-5', '', 1, system='')


def test_concurrent_reservations_never_admit_over_ten_dollars(tmp_path):
    ledger = Ledger(tmp_path / 'budget.db')
    def attempt(i):
        try:
            ledger.reserve(str(i), 'claude-opus-5-5', 'x' * 100_000, 8000)
            return True
        except TeacherError:
            return False
    with ThreadPoolExecutor(max_workers=4) as pool:
        admitted = list(pool.map(attempt, range(40)))
    assert 0 < sum(admitted) < 40
    assert ledger.total() <= 10_000_000


def test_unknown_call_keeps_reservation_and_blocks_automatic_retry(tmp_path):
    teacher = Teacher('synthetic-test-not-real-key', tmp_path)
    def fail(request):
        raise httpx.ReadTimeout('synthetic timeout')
    teacher.client.close()
    teacher.client = httpx.Client(transport=httpx.MockTransport(fail), base_url='https://api.anthropic.com')
    with pytest.raises(TeacherError, match='OUTCOME_UNKNOWN'):
        teacher.call('first', 'claude-sonnet-5-5', {'facts': []})
    assert teacher.ledger.total() > 0
    used = teacher.ledger.total()
    with pytest.raises(TeacherError, match='UNCERTAIN_PRIOR'):
        teacher.call('second', 'claude-sonnet-5-5', {'facts': []})
    assert teacher.ledger.total() == used
    teacher.client.close()


def test_actual_usage_settlement_and_cached_result_do_not_double_charge(tmp_path):
    sent = []
    def success(request):
        sent.append(request)
        return httpx.Response(200, json={'usage': {'input_tokens': 1000, 'output_tokens': 200},
                                        'stop_reason': 'end_turn',
                                        'content': [{'type': 'text', 'text': '{"findings":[]}'}]})
    teacher = Teacher('synthetic-test-not-real-key', tmp_path)
    teacher.client.close()
    teacher.client = httpx.Client(transport=httpx.MockTransport(success), base_url='https://api.anthropic.com')
    assert teacher.call('one', 'claude-sonnet-5-5', {}) == {'findings': []}
    assert teacher.ledger.total() == 4000
    assert teacher.call('one', 'claude-sonnet-5-5', {}) == {'findings': []}
    assert len(sent) == 1 and teacher.ledger.total() == 4000
    body = json.loads(sent[0].content)
    assert body['max_tokens'] == 6000
    assert 'tools' not in body and 'cache_control' not in body
    teacher.client.close()


@pytest.mark.parametrize('usage', [{}, {'input_tokens': -1, 'output_tokens': 2},
                                  {'input_tokens': 3, 'output_tokens': 2, 'cache_read_input_tokens': 4}])
def test_missing_or_unknown_billing_never_releases_reservation(tmp_path, usage):
    ledger = Ledger(tmp_path / 'budget.db')
    call = ledger.reserve('one', 'claude-sonnet-5-5', '', 6000)
    reserved = ledger.total()
    with pytest.raises(TeacherError):
        ledger.settle(call, usage)
    assert ledger.total() == reserved and not ledger.can_resume()


def test_unpriced_models_and_excess_output_are_rejected(tmp_path):
    ledger = Ledger(tmp_path / 'budget.db')
    for model, max_tokens in [('unknown', 6000), ('claude-opus-5-5', 8001)]:
        with pytest.raises(TeacherError, match='UNPRICED'):
            ledger.reserve('one', model, '', max_tokens)
    assert ledger.total() == 0


def test_prepared_cases_exclude_names_raw_records_and_teacher_answers():
    pack = {'displayFacts': {'F1': {'text': 'private factory name'}}, 'facts': [
        {'id': 'F1', 'sectionId': 'material', 'kind': 'sample', 'statistics': {'count': 2},
         'considerations': ['DO_NOT_SEND'], 'raw': 'private identity'}]}
    cases = cases_from(pack)
    assert len(cases) == 12 and sum(c['heldout'] for c in cases) == 3
    wire = json.dumps(cases)
    assert 'private' not in wire and 'DO_NOT_SEND' not in wire and 'displayFacts' not in wire
    assert len(CASE_PLAN) == 12
    assert all(c['origin'].startswith('synthetic') for c in cases if c['caseId'] in ('normal', 'units'))


def test_unknown_model_pair_does_not_send_inference(tmp_path):
    teacher = Teacher('synthetic-test-not-real-key', tmp_path)
    teacher.client.close()
    teacher.client = httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(200, json={'data': []})),
                                 base_url='https://api.anthropic.com')
    with pytest.raises(TeacherError, match='NO_AVAILABLE_PRICED'):
        teacher.models()
    assert teacher.ledger.total() == 0
    teacher.client.close()


def test_heldout_answers_cannot_enter_synthesis():
    analyses = {key: {'caseId': key, 'answer': 'sealed-answer' if heldout else 'training-answer'}
                for key, _, _, heldout in CASE_PLAN}
    reviews = {key: value for key, value in analyses.items()}
    result = synthesis_input(analyses, reviews)
    assert len(result['trainingCases']) == 9
    assert 'sealed-answer' not in json.dumps(result)


def test_truncated_cached_output_cannot_be_silently_reused(tmp_path):
    teacher = Teacher('synthetic-test-not-real-key', tmp_path)
    teacher.client.close()
    teacher.client = httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(200, json={
        'usage': {'input_tokens': 1000, 'output_tokens': 200}, 'stop_reason': 'max_tokens',
        'content': [{'type': 'text', 'text': '{"findings":[]}'}]})), base_url='https://api.anthropic.com')
    for _ in range(2):
        with pytest.raises(TeacherError, match='INCOMPLETE_TEACHER_OUTPUT'):
            teacher.call('one', 'claude-sonnet-5-5', {})
    partial = teacher.call('one', 'claude-sonnet-5-5', {}, allow_partial=True)
    assert partial['sourceComplete'] is False and partial['needsLocalSummary'] is True
    assert partial['partialText'] == '{"findings":[]}'
    assert teacher.ledger.total() == 4000
    teacher.client.close()
