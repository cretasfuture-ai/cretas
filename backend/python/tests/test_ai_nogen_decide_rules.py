"""No-generative evaluation pipeline (INTENT-DISTILL E): decision rules, candidates, rubric questions.

The pipeline lives in <repo>/tests/semantic-read (evaluation prototype for line H); this file sits
under backend/python/tests so the Python Gate runs it. Each rule has a control that makes it red.
"""
import pathlib
import sys

SEMANTIC_READ = pathlib.Path(__file__).resolve().parents[3] / 'tests' / 'semantic-read'
sys.path.insert(0, str(SEMANTIC_READ))

from nogen_pipeline import candidates, clarify_question, decide, decide_v3  # noqa: E402

NONE, CLAR = '__NONE__', '__CLARIFY__'


def sig(intent, act, clarify_p=0.0):
    return dict(intent=intent, act=act, clarify_p=clarify_p)


ACT_NEW = {'NEW_TASK': 0.9, 'CHANGE_PARAM': 0.05, 'PAGE': 0.0, 'CANCEL': 0.0, 'NEED_CLARIFY': 0.05}
ACT_PAGE = {'NEW_TASK': 0.05, 'CHANGE_PARAM': 0.05, 'PAGE': 0.9, 'CANCEL': 0.0, 'NEED_CLARIFY': 0.0}
ACT_CANCEL = {'NEW_TASK': 0.05, 'CHANGE_PARAM': 0.0, 'PAGE': 0.0, 'CANCEL': 0.9, 'NEED_CLARIFY': 0.05}


def test_clarify_signal_wins_over_a_confident_choice():
    s = sig({'A': 0.95, NONE: 0.0, CLAR: 0.05}, ACT_NEW, clarify_p=0.3)
    assert decide(s, 0.2, 0.4)['outcome'] == 'CLARIFY'
    assert decide(s, 0.5, 0.4) == dict(decide(s, 0.5, 0.4), outcome='EXECUTE', intent='A')  # control: below threshold


def test_cancel_act_is_not_executed_or_asked():
    s = sig({'A': 0.95, NONE: 0.0, CLAR: 0.05}, ACT_CANCEL)
    assert decide(s, 0.5, 0.4)['outcome'] == 'NONE'
    assert decide(sig(s['intent'], ACT_NEW), 0.5, 0.4)['outcome'] == 'EXECUTE'  # control


def test_page_act_continues_the_previous_task_not_a_meta_intent():
    s = sig({'PAGINATION_PREVIOUS': 0.8, 'ORDER_LIST': 0.1, NONE: 0.05, CLAR: 0.05}, ACT_PAGE)
    got = decide(s, 0.5, 0.4, prev_intent='ORDER_LIST')
    assert (got['outcome'], got['intent'], got['reason']) == ('EXECUTE', 'ORDER_LIST', 'continue_prev')
    # control: with no previous task the meta intent is not silently remapped to anything
    assert decide(s, 0.5, 0.4)['intent'] == 'PAGINATION_PREVIOUS'


def test_low_choice_asks_instead_of_executing():
    s = sig({'A': 0.35, 'B': 0.3, NONE: 0.2, CLAR: 0.15}, ACT_NEW)
    assert decide(s, 0.5, 0.4)['outcome'] == 'CLARIFY'
    assert decide(s, 0.5, 0.3)['outcome'] == 'EXECUTE'  # control: threshold below the top probability


def test_candidates_put_previous_task_first_and_respect_visibility_and_cap():
    snap = {'q': {'rerank': [['B', 9], ['HIDDEN', 8]], 'blend': [['C', 0.5], ['B', 0.4], ['D', 0.3]]}}
    visible = {'A': {}, 'B': {}, 'C': {}, 'D': {}}
    assert candidates('q', 'A', snap, visible) == ['A', 'B', 'C', 'D']
    assert candidates('q', None, snap, visible, max_options=2) == ['B', 'C']


def test_clarify_question_comes_from_rubric_templates():
    rubric = {'intents': {'X': {'must_ask': [{'param': 'orderId', 'description': '销售订单ID（必填）'}]}},
              'confusions': [{'id': 'c', 'term': '过期/到期（原料）', 'intents': ['Y'], 'question': '您要看已经过期的还是快过期的？',
                              'resolve_when': []}]}
    names = {'X': '开票', 'Y': '过期查询', 'Z': '临期预警'}
    assert clarify_question(['Y', 'Z'], rubric, names, '看下过期的')['kind'] == 'confusion'
    assert clarify_question(['X'], rubric, names, '开票弄一下') == dict(kind='must_ask', text='请告诉我销售订单ID（必填）。')
    assert clarify_question(['Z', 'Y'], rubric, names, '那个看下')['kind'] == 'choose_between'


# ---------------------------------------------------------------- v3 policy (Steve 2026-11-12)
MODES = {'R': 'READ', 'W': 'WRITE', 'R2': 'READ'}


def test_v3_read_executes_with_defaults_even_when_the_clarify_option_is_ahead():
    s = sig({'R': 0.5, 'R2': 0.1, NONE: 0.05, CLAR: 0.35}, ACT_NEW, clarify_p=0.0)
    s['clarify_p_v3'] = 0.05
    got = decide_v3(s, 0.2, 0.5, modes=MODES)
    assert (got['outcome'], got['intent'], got['reason']) == ('EXECUTE', 'R', 'read_with_defaults')
    # control: the v1 rule asks on the same signals (clarify option above the top candidate is not the case here,
    # but the top is below its execute threshold of 0.6)
    assert decide(s, 0.2, 0.6)['outcome'] == 'CLARIFY'


def test_v3_uses_the_v3_predicate_not_the_v1_one():
    s = sig({'R': 0.9, NONE: 0.05, CLAR: 0.05}, ACT_NEW, clarify_p=0.9)
    s['clarify_p_v3'] = 0.05
    assert decide_v3(s, 0.2, 0.5, modes=MODES)['outcome'] == 'EXECUTE'
    s['clarify_p_v3'] = 0.9
    assert decide_v3(s, 0.2, 0.5, modes=MODES)['outcome'] == 'CLARIFY'  # control


def test_v3_two_way_read_target_asks_and_clear_leader_executes():
    s = sig({'R': 0.3, 'R2': 0.28, NONE: 0.2, CLAR: 0.22}, ACT_NEW)
    assert decide_v3(s, 0.2, 0.5, modes=MODES, margin_t=0.4)['reason'] == 'two_way_target'
    s2 = sig({'R': 0.45, 'R2': 0.02, NONE: 0.3, CLAR: 0.23}, ACT_NEW)
    assert decide_v3(s2, 0.2, 0.5, modes=MODES, margin_t=0.4)['outcome'] == 'EXECUTE'  # control: leads by 0.43


def test_v3_write_target_goes_to_confirmation_or_asks_when_weak():
    s = sig({'W': 0.8, NONE: 0.1, CLAR: 0.1}, ACT_NEW)
    assert decide_v3(s, 0.2, 0.5, modes=MODES)['reason'] == 'write_to_confirm'
    weak = sig({'W': 0.3, 'R': 0.2, NONE: 0.1, CLAR: 0.4}, ACT_NEW)
    assert decide_v3(weak, 0.2, 0.5, modes=MODES)['outcome'] == 'CLARIFY'
