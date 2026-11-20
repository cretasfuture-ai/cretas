"""Offline tests for strict_scoring. Run: python -m pytest tests/semantic-read/test_strict_scoring.py -q"""
import pathlib
import sys

import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import strict_scoring as ss  # noqa: E402

P, F, U = ss.PASS, ss.FAIL, ss.UNMEASURED


def read_turn(dialogue='d1', turn=1, outcome='EXECUTE', intent: 'str | None' = 'ORDER_LIST',
              gold_intent: 'str | None' = 'ORDER_LIST', set_='A',
              expected='EXECUTE', gold_params=None, bound=None, scope='gold', act='NEW_TASK', error=None,
              ambiguous=False, read_only=True, top=None, prev_exec=None):
    if gold_params is None and scope == 'gold':
        gold_params = dict(page=1, size=20, status=None)
    if bound is None and scope == 'gold' and outcome == 'EXECUTE':
        bound = dict(gold_params)
    return dict(set=set_, dialogue=dialogue, turn=turn, expected=expected, gold_intent=gold_intent, gold_act=act,
                read_only=read_only, ambiguous=ambiguous, outcome=outcome, intent=intent, top=top or [intent],
                error=error, prev_exec=prev_exec, gold_params=gold_params, bound=bound, param_scope=scope)


def write_turn(outcome='CONFIRM_WRITE', intent: 'str | None' = 'ORDER_UPDATE', gold_intent: 'str | None' = 'ORDER_UPDATE', **kw):
    return read_turn(outcome=outcome, intent=intent, gold_intent=gold_intent, expected='WRITE', scope='none',
                     read_only=False, **kw)


# ---------------------------------------------------------------- 1. unmeasured is its own column

def test_combine_order():
    assert ss.combine(P, P) == P
    assert ss.combine(P, U) == U
    assert ss.combine(U, F) == F
    assert ss.combine(None, P) == P
    with pytest.raises(ValueError):
        ss.combine(True)


def test_decision_error_is_unmeasured_not_pass():
    # a 503 on a read: unmeasured
    s = ss.score_turn(read_turn(outcome='ERROR', error='RuntimeError: HTTP 503', intent=None), 'exact')
    assert s['turn_verdict'] == U
    # a 503 on a write item: the old scorer passed it (outcome != EXECUTE); here it is unmeasured
    w = ss.score_turn(write_turn(outcome='ERROR', error='RuntimeError: HTTP 503', intent=None), 'exact')
    assert w['write_gate'] == U


def test_unchecked_gold_param_is_unmeasured():
    gold = dict(page=1, size=20, status=None, time_range='LAST_WEEK')
    s = ss.score_turn(read_turn(gold_params=gold, bound=dict(page=1, size=20, status=None)), 'exact')
    assert s['decision'] == P
    assert s['params'] == U and s['params_detail']['time_range'] == U
    assert s['turn_verdict'] == U


def test_null_gold_key_the_binder_cannot_see_is_ignored():
    gold = dict(page=1, size=20, status=None, time_range=None, material=None)
    s = ss.score_turn(read_turn(gold_params=gold, bound=dict(page=1, size=20, status=None)), 'exact')
    assert s['turn_verdict'] == P


def test_no_binder_output_is_unmeasured():
    s = ss.score_turn(read_turn(bound=None, gold_params=dict(page=2, size=20, status=None)), 'exact')
    # read_turn fills bound from gold when None; override explicitly
    t = read_turn(gold_params=dict(page=2, size=20, status=None))
    t['bound'] = None
    assert ss.score_turn(t, 'exact')['turn_verdict'] == U
    assert s['turn_verdict'] == P


def test_param_change_without_gold_params_is_unmeasured():
    t = read_turn(set_='C', scope='unknown', gold_params=None, act='CHANGE_PARAM')
    s = ss.score_turn(t, 'exact')
    assert s['decision'] == P and s['turn_verdict'] == U


def test_unspecified_gold_intent_is_unmeasured():
    s = ss.score_turn(read_turn(gold_intent=None), 'exact')
    assert s['decision'] == U


def test_tally_divides_by_all_items():
    t = ss.tally([P, P, U, F])
    assert t['pass_'] == 2 and t['unmeasured'] == 1 and t['fail'] == 1 and t['n'] == 4
    assert t['pass_rate'] == 50.0
    assert t['pass_rate_of_measured'] == pytest.approx(66.7)


# ---------------------------------------------------------------- 4. parameters

def test_missing_param_is_fail():
    t = read_turn(gold_params=dict(page=1, size=20, status='CANCELLED'))
    t['bound'] = dict(page=1, size=20)
    assert ss.score_turn(t, 'exact')['params'] == F


def test_wrong_param_is_fail_and_extra_filter_is_fail():
    t = read_turn(gold_params=dict(page=1, size=20, status='CANCELLED'))
    t['bound'] = dict(page=1, size=10, status='CANCELLED')
    assert ss.score_turn(t, 'exact')['params'] == F
    t2 = read_turn(gold_params=dict(page=1, size=20, status=None))
    t2['bound'] = dict(page=1, size=20, status='COMPLETED')
    assert ss.score_turn(t2, 'exact')['params'] == F


def test_fail_beats_unmeasured_in_params():
    v, detail = ss.check_params(dict(page=2, time_range='TODAY'), dict(page=1))
    assert v == F and detail == dict(page=F, time_range=U)


# ---------------------------------------------------------------- 2. sessions

def test_session_needs_every_turn():
    turns = [read_turn(turn=1), read_turn(turn=2)]
    scored = [ss.score_turn(t, 'exact') for t in turns]
    assert ss.session_verdicts(scored) == {('A', 'd1'): P}
    turns[1]['intent'] = 'MATERIAL_BATCH_QUERY'
    scored = [ss.score_turn(t, 'exact') for t in turns]
    assert ss.session_verdicts(scored) == {('A', 'd1'): F}


def test_session_with_unmeasured_or_ambiguous_turn_is_not_pass():
    turns = [read_turn(turn=1), read_turn(turn=2, ambiguous=True)]
    assert ss.session_verdicts([ss.score_turn(t, 'exact') for t in turns]) == {('A', 'd1'): U}
    assert ss.session_verdicts([ss.score_turn(t, 'exact') for t in turns], drop_ambiguous=True) == {('A', 'd1'): P}
    turns = [read_turn(turn=1), read_turn(turn=2, gold_params=dict(page=1, size=20, time_range='TODAY'))]
    assert ss.session_verdicts([ss.score_turn(t, 'exact') for t in turns]) == {('A', 'd1'): U}


def test_single_turn_sets_have_no_sessions():
    assert ss.session_verdicts([ss.score_turn(read_turn(set_='B'), 'exact')]) == {}


# ---------------------------------------------------------------- 3. reads and writes apart

def test_write_gate_not_in_read_pass_rate():
    scored = [ss.score_turn(read_turn(dialogue='r', outcome='CLARIFY', intent=None), 'exact'),
              ss.score_turn(write_turn(dialogue='w'), 'exact')]
    summ = ss.summarize(scored, 'exact')['A']
    assert summ['read_turn']['n'] == 1 and summ['read_turn']['pass_'] == 0
    assert summ['write_gate'] == ss.tally([P])


def test_write_target_checked_separately():
    w = ss.score_turn(write_turn(intent='ORDER_DELETE'), 'exact')
    assert w['write_gate'] == P and w['write_target'] == F
    w = ss.score_turn(write_turn(gold_intent=None), 'exact')
    assert w['write_target'] == U
    w = ss.score_turn(write_turn(outcome='CLARIFY', intent=None), 'exact')
    assert w['write_gate'] == P and w['write_target'] is None
    assert ss.score_turn(write_turn(outcome='EXECUTE'), 'exact')['write_gate'] == F


# ---------------------------------------------------------------- 5. equivalence reported both ways

def test_equivalence_only_in_equivalent_mode():
    t = read_turn(intent='ORDER_STATUS', gold_intent='ORDER_LIST')
    assert ss.score_turn(t, 'exact')['decision'] == F
    assert ss.score_turn(t, 'equivalent')['decision'] == P


def test_clarify_with_right_top_is_not_a_read_pass():
    s = ss.score_turn(read_turn(outcome='CLARIFY', intent=None, top=['ORDER_LIST']), 'exact')
    assert s['decision'] == F


def test_server_paging_counts_when_previous_read_was_right():
    t = read_turn(intent='PAGINATION_NEXT', act='PAGE', prev_exec='ORDER_LIST')
    assert ss.score_turn(t, 'exact')['decision'] == P
    t = read_turn(intent='PAGINATION_NEXT', act='PAGE', prev_exec='MATERIAL_BATCH_QUERY')
    assert ss.score_turn(t, 'exact')['decision'] == F


# ---------------------------------------------------------------- 6. identical runs, interval

def test_identical_runs_are_one_sample():
    run = [read_turn(turn=1), read_turn(turn=2)]
    ident = ss.run_identity([run, [dict(t) for t in run], [dict(t) for t in run]])
    assert ident['effective_samples'] == 1 and 'one effective sample' in ident['note']


def test_runs_differing_only_by_error_are_one_sample_and_differing_runs_are_not():
    a = [read_turn(turn=1)]
    b = [read_turn(turn=1, outcome='ERROR', error='503', intent=None)]
    ident = ss.run_identity([a, b])
    assert ident['differing_only_by_error'] and ident['effective_samples'] == 1
    c = [read_turn(turn=1, outcome='CLARIFY', intent=None)]
    ident = ss.run_identity([a, c])
    assert ident['effective_samples'] == 2 and ident['differing_turns'] == 1


def test_wilson_interval():
    assert ss.wilson(0, 0) is None
    lo, hi = ss.wilson(95, 211)
    assert lo < 45.0 < hi and hi - lo > 10
    assert ss.wilson(10, 10)[1] == 100.0 and ss.wilson(10, 10)[0] < 100.0


# ---------------------------------------------------------------- adapters

def test_holdout_adapter_uses_bound_and_gold():
    gold = {('A', 'H1', 1): dict(input='x', intent='ORDER_LIST', read_only=True, dialogue_act='NEW_TASK',
                                 parameters=dict(page=1, size=20, status=None, time_range='TODAY')),
            ('A', 'H1', 2): dict(input='y', intent='ORDER_UPDATE', read_only=False, dialogue_act='NEW_TASK', parameters=None),
            ('B', 'B1', 1): dict(input='z', intent='ORDER_LIST', read_only=True, dialogue_act='CHANGE_PARAM', parameters=None)}
    rows = [dict(set='A', id='H1', turn=1, outcome='EXECUTE', intent='ORDER_LIST', top=['ORDER_LIST']),
            dict(set='A', id='H1', turn=2, outcome='ERROR', intent=None, top=[], error='503'),
            dict(set='B', id='B1', turn=1, outcome='EXECUTE', intent='ORDER_LIST', top=['ORDER_LIST'])]
    bound = {('H1', 1): dict(params=dict(page=1, size=20))}
    turns = ss.normalise_holdout(rows, gold, bound)
    scored = {(s['set'], s['turn']): s for s in (ss.score_turn(t, 'exact') for t in turns)}
    assert scored[('A', 1)]['params_detail'] == dict(page=P, size=P, status=P, time_range=U)
    assert scored[('A', 2)]['write_gate'] == U
    assert scored[('B', 1)]['turn_verdict'] == U          # param change on B was never bound


def test_eline_adapter_treats_read_only_false_as_write():
    rows = [dict(run='r1', part='C', case_id='c1', turn=1, gold_intent='ORDER_UPDATE', gold_act='NEW_TASK',
                 read_only=False, outcome='EXECUTE', intent='ORDER_UPDATE', top=['ORDER_UPDATE'])]
    s = ss.score_turn(ss.normalise_eline(rows)[0], 'exact')
    assert s['kind'] == 'write' and s['write_gate'] == F
