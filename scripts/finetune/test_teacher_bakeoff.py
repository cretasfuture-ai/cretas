"""Offline tests for the teacher bake-off scoring (no network, no credentials).

Run: python -m pytest scripts/finetune/test_teacher_bakeoff.py
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import teacher_bakeoff as tb

ROW = dict(id='q1', state=None, candidates=['A', 'B'])


def test_majority_needs_more_than_half_and_leaves_self_out():
    votes = {'m1': ('NEW_TASK', 'A', None), 'm2': ('NEW_TASK', 'A', None), 'm3': ('NEED_CLARIFY', None, None)}
    assert tb.majority(votes) == (('NEW_TASK', 'A'), 3)
    # leaving m1 out: 1 vs 1 -> no majority, so m1 is not scored against a tie
    assert tb.majority(votes, exclude='m1') == (None, 2)
    # invalid answers do not vote
    votes['m4'] = (None, None, 'bad_json')
    assert tb.majority(votes) == (('NEW_TASK', 'A'), 3)


def _rec(**kw):
    base = dict(ok=True, model='m', served_model='m-2026', text=json.dumps(dict(dialogue_act='NEW_TASK', intent='A')))
    base.update(kw)
    return base


def test_parse_rejects_failed_truncated_and_substituted_answers():
    assert tb.parse(_rec(), ROW)[:2] == ('NEW_TASK', 'A')
    assert tb.parse(_rec(ok=False), ROW)[2] == 'call_failed'
    assert tb.parse(_rec(stop_reason='max_tokens'), ROW)[2] == 'stop:max_tokens'
    assert tb.parse(_rec(status_text='incomplete'), ROW)[2] == 'status:incomplete'
    assert tb.parse(_rec(served_model='other-model'), ROW)[2].startswith('served_by')
    assert tb.parse(_rec(text='not json'), ROW)[2] == 'bad_json'
    # label rule: an intent not among the candidates is invalid
    assert tb.parse(_rec(text=json.dumps(dict(dialogue_act='NEW_TASK', intent='Z'))), ROW)[2]


def test_every_contestant_has_price_and_a_low_effort():
    for m, c in tb.CONTESTANTS.items():
        assert c['provider'] in ('openai', 'anthropic') and len(c['price']) == 3
        assert 'low' in c['efforts'], m


def test_parse_review_reads_steve_answers():
    md = '\n'.join(['## 1. q1（三方分歧题）', '- x', 'Steve 意见： NEW_TASK ORDER_LIST', '',
                    '## 2. q2', 'Steve 意见：NEED_CLARIFY', '', '## 3. q3', 'Steve 意见：都不对：应该查库存', '',
                    '## 4. q4', 'Steve 意见：', ''])
    assert tb.parse_review(md) == {'q1': ('NEW_TASK', 'ORDER_LIST'), 'q2': ('NEED_CLARIFY', None), 'q3': None}
