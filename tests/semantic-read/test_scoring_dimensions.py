"""Offline tests for the five-dimension scoring. Run: python -m pytest tests/semantic-read -q"""
import pathlib
import sys

import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import scoring_dimensions as sd  # noqa: E402


def gold_turn(intent='ORDER_LIST', page=1, size=3, status='COMPLETED', **extra):
    return dict(intent=intent, parameters=dict(page=page, size=size, status=status), input='x', **extra)


DIALOGUE = [gold_turn(page=1), gold_turn(page=2), gold_turn(page=1, status='CANCELLED')]


def row(turn=1, channel='normal', status='SUCCESS', intent='ORDER_LIST', selected=None, page=1, size=3, ostatus='COMPLETED',
        params_ok=True, real_ok=True, success=True, case_id='d1', category='PASS'):
    return dict(case_id=case_id, channel=channel, turn=turn, status=status, intent=intent,
                recognition=dict(selectedIntent=selected if selected is not None else intent),
                observed=dict(status=ostatus, page=page, size=size), parameters_correct=params_ok,
                real_result_correct=real_ok, task_success=success, category=category)


def clarified(turn, selected='ORDER_LIST', status='NEED_CLARIFICATION', intent='ORDER_LIST'):
    return row(turn=turn, status=status, intent=intent, selected=selected, page=None, size=None, ostatus=None,
               params_ok=False, real_ok=False, success=False, category='CLARIFICATION')


def test_gold_action_rules():
    assert sd.gold_action(DIALOGUE, 0) == sd.NEW_TASK
    assert sd.gold_action(DIALOGUE, 1) == sd.PAGINATE
    assert sd.gold_action(DIALOGUE, 2) == sd.CHANGE_PARAM
    switch = [gold_turn(), gold_turn(intent='MATERIAL_BATCH_QUERY', status='AVAILABLE')]
    assert sd.gold_action(switch, 1) == sd.NEW_TASK
    expiry = [gold_turn(intent='MATERIAL_BATCH_QUERY'), gold_turn(intent='MATERIAL_EXPIRED_QUERY', basis='EXPIRY_DATE')]
    assert sd.gold_action(expiry, 1) == sd.NEW_TASK          # basis/intent change is a new task
    assert sd.gold_action([gold_turn(), gold_turn(action='CANCEL')], 1) == 'CANCEL'   # explicit field wins


def test_all_pass_turn():
    r = sd.score_turn(DIALOGUE, 0, row(), None)
    assert (r['goal'], r['action'], r['clarify'], r['params'], r['real_result'], r['success']) == (True,) * 6
    assert r['false_clarify'] is False and r['missed_clarify'] is False


def test_false_clarify_keeps_goal_but_fails_clarify_and_action():
    prev = row(turn=1)
    r = sd.score_turn(DIALOGUE, 1, clarified(2), prev)
    assert r['goal'] is True                 # right target, then asked a question
    assert r['clarify'] is False and r['false_clarify'] is True and r['missed_clarify'] is False
    assert r['params'] is None and r['real_result'] is None     # nothing recorded to judge
    assert r['observed_action'] == sd.CLARIFY and r['action'] is False
    assert r['success'] is False


def test_need_more_info_uses_recognised_intent_for_goal():
    r = sd.score_turn(DIALOGUE, 0, clarified(1, selected='ORDER_LIST', status='NEED_MORE_INFO', intent=None), None)
    assert r['goal'] is True and r['false_clarify'] is True
    wrong = sd.score_turn(DIALOGUE, 0, clarified(1, selected='CONTEXT_CONTINUE', intent='CONTEXT_CONTINUE'), None)
    assert wrong['goal'] is False


def test_missed_clarify_when_gold_expects_clarification():
    gold = [gold_turn(expect_clarify=True)]
    r = sd.score_turn(gold, 0, row(), None)
    assert r['missed_clarify'] is True and r['false_clarify'] is False and r['clarify'] is False
    assert r['gold_action'] == sd.CLARIFY and r['action'] is False


def test_expected_clarification_that_happened_is_correct():
    gold = [gold_turn(expect_clarify=True)]
    r = sd.score_turn(gold, 0, clarified(1), None)
    assert r['clarify'] is True and r['action'] is True and r['goal'] is None
    assert r['missed_clarify'] is False and r['false_clarify'] is False


def test_wrong_intent_executed_fails_goal_only():
    r = sd.score_turn(DIALOGUE, 0, row(intent='MATERIAL_BATCH_QUERY', params_ok=True, real_ok=True, success=False), None)
    assert r['goal'] is False and r['clarify'] is True and r['params'] is True


def test_order_status_is_an_accepted_target_for_order_list():
    assert sd.score_turn(DIALOGUE, 0, row(intent='ORDER_STATUS'), None)['goal'] is True


def test_wrong_page_is_a_params_and_action_failure():
    prev = row(turn=1)
    r = sd.score_turn(DIALOGUE, 1, row(turn=2, page=1, params_ok=False, real_ok=False, success=False), prev)
    assert r['params'] is False and r['observed_action'] == sd.OTHER and r['action'] is False


def test_action_unknown_when_previous_turn_did_not_execute():
    r = sd.score_turn(DIALOGUE, 1, row(turn=2, page=2), clarified(1))
    assert r['observed_action'] is None and r['action'] is None


def test_carrier_failure_is_not_a_clarification_decision():
    failed = row(status='FAILED', success=False, params_ok=False, real_ok=False)
    r = sd.score_turn(DIALOGUE, 0, failed, None)
    assert r['clarify'] is None and r['false_clarify'] is None and r['missed_clarify'] is None
    assert r['goal'] is False and r['params'] is None


def _corpus_and_score(rows_for):
    corpus = dict(dialogues=[dict(id='d1', turns=DIALOGUE)])
    turns = []
    for ch in sd.CHANNELS:
        turns += [dict(r, channel=ch) for r in rows_for(ch)]
    return corpus, dict(turns=turns)


def test_summary_denominators_and_directions():
    def rows_for(ch):
        if ch == 'normal':
            return [row(1), row(2, page=2), row(3, page=1, ostatus='CANCELLED')]
        return [row(1), clarified(2), row(3, ostatus='CANCELLED', page=1)]
    corpus, score = _corpus_and_score(rows_for)
    summary = sd.summarize(sd.score_run(corpus, score), corpus)
    normal, sse = summary['normal'], summary['sse']
    assert normal['sessions_success'] == 1 and sse['sessions_success'] == 0
    assert sse['clarify_directions']['false_clarify'] == dict(count=1, denominator=3)
    assert sse['clarify_directions']['missed_clarify'] == dict(count=0, denominator=0)   # corpus has no gold clarification
    assert sse['dimensions']['params'] == dict(passed=2, evaluable=2, total=3, unevaluable=1)
    assert normal['dimensions']['params']['unevaluable'] == 0
    assert sse['clarify_directions']['false_clarify_by_status'] == {'NEED_CLARIFICATION': 1}


def test_score_run_fails_closed_on_missing_row():
    corpus, score = _corpus_and_score(lambda ch: [row(1), row(2, page=2)])    # turn 3 absent
    with pytest.raises(KeyError):
        sd.score_run(corpus, score)


def test_mutation_guard_clarify_direction_flipped_is_detected():
    """A scorer that swaps the two clarification directions must fail an existing assertion."""
    gold_no_clarify = [gold_turn()]
    r = sd.score_turn(gold_no_clarify, 0, clarified(1), None)
    assert r['false_clarify'] is True and r['missed_clarify'] is False
    gold_clarify = [gold_turn(expect_clarify=True)]
    r2 = sd.score_turn(gold_clarify, 0, row(), None)
    assert r2['missed_clarify'] is True and r2['false_clarify'] is False
