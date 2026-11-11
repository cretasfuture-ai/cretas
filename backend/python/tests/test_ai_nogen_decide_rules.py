"""No-generative evaluation pipeline (INTENT-DISTILL E): decision rules, candidates, rubric questions.

The pipeline lives in <repo>/tests/semantic-read (evaluation prototype for line H); this file sits
under backend/python/tests so the Python Gate runs it. Each rule has a control that makes it red.
"""
import pathlib
import sys

SEMANTIC_READ = pathlib.Path(__file__).resolve().parents[3] / 'tests' / 'semantic-read'
sys.path.insert(0, str(SEMANTIC_READ))

from nogen_pipeline import candidates, clarify_question, decide  # noqa: E402

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
