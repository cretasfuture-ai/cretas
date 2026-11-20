"""Offline tests for student-v2 data tooling (no network, no credentials).

Run: python -m pytest scripts/finetune/test_distill_v4.py
"""
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))
import distill_common as dc
import distill_v4 as v4


@pytest.fixture(scope='module')
def table():
    return dc.build_intent_table()


def test_boundary_plan_targets_v1_weak_spots(table):
    for k in range(20):
        p = v4.plan_boundary(k, table, 's')
        acts = {s['slot']: s for s in p['slots']}
        assert set(acts) == set(v4.BSLOTS)
        for s in ('page_a', 'page_b'):
            assert acts[s]['act'] == 'PAGE' and table[acts[s]['prev']]['read_only'] is True and acts[s]['intent'] == acts[s]['prev']
        assert acts['cont_paraphrase']['intent'] == acts['cont_paraphrase']['prev']
        w = acts['write_to_read']
        assert table[w['intent']]['read_only'] is True and (w['prev'] is None or table[w['prev']]['read_only'] is False)
        assert acts['boundary_vague']['act'] == 'NEED_CLARIFY' and acts['boundary_vague']['intent'] is None
        assert len(set(p['pool']) | set(p['distractors'])) >= 7
    assert v4.plan_boundary(3, table, 's') == v4.plan_boundary(3, table, 's')


def _item(slot, plan, utt='后面还有吗'):
    s = next(x for x in plan['slots'] if x['slot'] == slot)
    state = dict(prev_intent=s['prev'], confirmed_params=[], pending_fields=[], last_action='系统展示了第一页' if s['prev'] else None)
    return dict(slot=slot, dialogue_act=s['act'], planned_intent=s['intent'], state=state, utterance=utt)


def test_build_rows_rejects_drift_and_keeps_targets_in_candidates(table):
    p = v4.plan_boundary(1, table, 's')
    good = [_item(s, p, '句子%d' % i) for i, s in enumerate(v4.BSLOTS)]
    rows, rej = v4.build_rows(good, p, table, 's')
    assert len(rows) == 6 and not rej
    for r, s in zip(rows, p['slots']):
        assert all(c in r['candidates'] for c in (s['intent'], s['prev']) if c)
        assert r['id'].startswith('v4-')
    bad = dict(good[0], planned_intent='OTHER')
    rows, rej = v4.build_rows([bad] + good[1:], p, table, 's')
    assert rej['intent_drift'] == 1 and len(rows) == 5
    leak = dict(good[1], utterance='看下 ' + p['slots'][1]['intent'])
    assert v4.valid_item(leak, p['slots'][1], table) == 'intent_id_in_utterance'


def test_assemble_requires_main_and_second_to_agree_and_luna_probs(tmp_path, table, monkeypatch):
    p = v4.plan_boundary(2, table, 's')
    rows, _ = v4.build_rows([_item(s, p, '句子%d' % i) for i, s in enumerate(v4.BSLOTS)], p, table, 's')
    r = rows[0]
    probs = {c: 0.0 for c in r['candidates']}
    probs[r['candidates'][0]] = 1.0
    luna = dict(model='gpt-6-luna', dialogue_act='PAGE', intent=r['state']['prev_intent'], intent_probs=dict(probs, __NONE__=0.0))
    ok = dict(dialogue_act='PAGE', intent=r['state']['prev_intent'], issue=None)
    old_m, old_s = v4.OLD_PAIR[0][0], v4.OLD_PAIR[1][0]          # first batch: rows carry no 'pair'
    new = dict(pair=[list(v4.MAIN), list(v4.SECOND)], rubric_version=3)
    lines = [dict(r, id='a', luna=luna, hard={old_m: ok, old_s: ok}),
             dict(r, id='b', luna=luna, hard={old_m: ok, old_s: dict(ok, dialogue_act='NEED_CLARIFY', intent=None)}),
             dict(r, id='c', luna=dict(model='gpt-6-luna', error='HTTP 503'), hard={old_m: ok, old_s: ok}),
             dict(r, id='d', luna=luna, hard={v4.MAIN[0]: ok, v4.SECOND[0]: ok}, **new)]
    (tmp_path / 'labeled-raw.jsonl').write_text(''.join(json.dumps(x, ensure_ascii=False) + '\n' for x in lines), encoding='utf-8')
    why = v4.assemble(tmp_path, table)
    assert why == {'agreed': 2, 'disagree_act': 1, 'no_luna': 1}
    silver = {x['id']: x for x in dc.read_jsonl(tmp_path / 'silver.jsonl')}
    assert silver['a']['teacher_set'] == 'gpt-6.1-terra@low+claude-opus-5-5@low+luna|rubric-v1'
    assert silver['d']['teacher_set'] == 'claude-opus-5-5@low+gpt-6-sol@medium+luna|rubric-v3'
    assert silver['a']['teachers']['second']['dialogue_act'] == 'PAGE'


def test_dataset_keeps_both_label_sets_soft_only_on_disagreement_and_excludes(tmp_path, table):
    v4.TABLE_REF[0] = table
    p = v4.plan_boundary(4, table, 's')
    rows, _ = v4.build_rows([_item(s, p, '句子%d' % i) for i, s in enumerate(v4.BSLOTS)], p, table, 's')
    vague = next(r for r in rows if r['generator_plan']['slot'] == 'boundary_vague')
    clear = next(r for r in rows if r['generator_plan']['slot'] == 'boundary_clear')
    probs = dict({c: 0.1 for c in vague['candidates']}, __NONE__=0.5)
    silver = [dict(vague, dialogue_act='NEED_CLARIFY', intent=None, teacher_probs=probs),
              dict(clear, dialogue_act='NEW_TASK', intent=clear['generator_plan']['intent'], teacher_probs=probs)]
    (tmp_path / 's.jsonl').write_text(''.join(json.dumps(x, ensure_ascii=False) + '\n' for x in silver), encoding='utf-8')
    target = clear['generator_plan']['intent']
    pair = dict(pair=[list(v4.MAIN), list(v4.SECOND)], rubric_version=3)
    agree = {v4.MAIN[0]: dict(dialogue_act='NEW_TASK', intent=target, issue=None),
             v4.SECOND[0]: dict(dialogue_act='NEW_TASK', intent=target, issue=None)}
    split = {v4.MAIN[0]: agree[v4.MAIN[0]], v4.SECOND[0]: dict(dialogue_act='NEED_CLARIFY', intent=None, issue=None)}
    rel = [dict(vague, hard=agree, **pair), dict(clear, hard=split, **pair)]
    (tmp_path / 'r.jsonl').write_text(''.join(json.dumps(x, ensure_ascii=False) + '\n' for x in rel), encoding='utf-8')
    pool = {r['id']: r for r in v4.build_dataset([('b', tmp_path / 's.jsonl', None, 'x')], [tmp_path / 'r.jsonl'], set(), table)}
    a, b = pool[vague['id']], pool[clear['id']]
    assert a['v1']['dialogue_act'] == 'NEED_CLARIFY' and a['v3']['dialogue_act'] == 'NEW_TASK' and a['soft_stale']
    assert v4.label_view(a, 1)['hard_weight'] == 1 and v4.label_view(a, 3)['dialogue_act'] == 'NEW_TASK'
    assert v4.label_view(b, 3)['hard_weight'] == 0 and v4.label_view(b, 1)['hard_weight'] == 1   # teachers split under v3
    assert vague['id'] not in {r['id'] for r in v4.build_dataset([('b', tmp_path / 's.jsonl', None, 'x')], [], {vague['id']}, table)}


def test_sensitive_list_covers_every_money_keyword_read_intent(table):
    import sensitive_intents as si
    missing, stale = si.check_complete(table)
    assert missing == [] and stale == []
    assert si.risk_class('WAGE_COST_SUMMARY', table) == 'sensitive'
    assert si.risk_class(next(c for c, v in table.items() if v['read_only'] is False), table) == 'write'
    assert si.risk_class(None, table) is None
