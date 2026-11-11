"""Offline tests for the distillation pipeline (no network, no credentials).

Run: python -m pytest scripts/finetune/test_distill_pipeline.py
"""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))
import distill_common as dc
import distill_leak_check as lk
import distill_pipeline as dp


@pytest.fixture(scope='module')
def table():
    return dc.build_intent_table()


def raw_row(table, **kw):
    fam = dp.eligible_families(table)
    name = sorted(fam)[0]
    cs = fam[name][:3]
    extra = next(c for c in table if c not in cs)
    base = dict(id='t-1', utterance='换成上个月的',
                state=dict(prev_intent=cs[0], confirmed_params=[], pending_fields=[], last_action=None),
                candidates=cs + [extra], read_only=table[cs[0]]['read_only'], family=name,
                generator_plan=dict(dialogue_act='CHANGE_PARAM', intent=cs[0]), source='t')
    base.update(kw)
    return base


def test_table_read_write_from_tool_declarations(table):
    assert len(table) == 266
    modes = {v['access_mode'] for v in table.values()}
    assert 'READ' in modes and 'WRITE' in modes
    # unresolved is never guessed into a bool
    assert all(v['read_only'] is None for v in table.values() if v['access_mode'] == 'UNRESOLVED')
    assert dc.derive_read_only(None, None, table) is None


def test_every_label_has_a_resolved_access_mode(table):
    # F-pre: the two former scanner misses (comment in method / inherited) now read as their Java READ
    assert not [c for c, v in table.items() if v['access_mode'] == 'UNRESOLVED']
    assert table['GATE_DIAGNOSE_BATCH']['access_mode'] == 'READ'
    assert table['PAGINATION_PREVIOUS']['access_mode'] == 'READ'


def test_unresolved_label_refuses_to_build(monkeypatch):
    import tool_access_modes as tam
    real = tam.scan
    monkeypatch.setattr(tam, 'scan', lambda: {k: (dict(v, mode='DYNAMIC') if k == 'gate_diagnose_batch' else v)
                                              for k, v in real().items()})
    with pytest.raises(dc.UnresolvedAccessMode):
        dc.build_intent_table()
    assert dc.build_intent_table(allow_unresolved=True)['GATE_DIAGNOSE_BATCH']['read_only'] is None


def test_report_separates_no_target_from_unresolved():
    plan = dict(dialogue_act='NEED_CLARIFY', intent=None)
    assert dp.ro_bucket(dict(read_only=None, state=None, generator_plan=plan)) == 'no_target'
    assert dp.ro_bucket(dict(read_only=None, state=dict(prev_intent='X'), generator_plan=plan)) == 'unresolved'
    assert dp.ro_bucket(dict(read_only=True, state=None, generator_plan=plan)) == 'read_only'


def test_rubric_is_fresh_and_both_teachers_get_it(table, monkeypatch):
    import build_clarify_rubric as br
    assert br.main.__module__ == 'build_clarify_rubric'
    monkeypatch.setattr(sys, 'argv', ['x', '--check'])
    assert br.main() == 0, 'clarify_rubric.json is stale: rerun build_clarify_rubric.py'
    rub = dp.load_rubric(Path(__file__).parent / 'clarify_rubric.json')
    assert rub['intents']['BATCH_FULL_TRACE']['must_ask'][0]['param'] == 'batchNumber'
    assert all(r['source'] for r in rub['rules'])
    row = raw_row(table)
    monkeypatch.setattr(dp, 'RUBRIC', None)
    plain_t, plain_l = dp.terra_payload(row, table), dp.luna_payload(row, table)
    monkeypatch.setattr(dp, 'RUBRIC', rub)
    t, l = dp.terra_payload(row, table), dp.luna_payload(row, table)
    assert 'G1_ACTION_UNCLEAR' in t['instructions'] and 'G1_ACTION_UNCLEAR' not in plain_t['instructions']
    assert 'G1_ACTION_UNCLEAR' in l['questions'][0]['instructions']
    assert t['input'] == l['input'] and '"access"' in t['input'] and '"access"' not in plain_t['input']
    # independence: neither teacher's payload carries the other's answer or the generator plan
    for p in (t, l):
        assert 'generator_plan' not in str(p) and 'luna' not in p.get('input', '') and 'terra' not in p.get('input', '')


def test_raw_schema_accepts_and_rejects(table):
    ok = raw_row(table)
    dc.validate_sample(ok, table, 'raw')
    for bad in (dict(utterance=' '), dict(candidates=['NOPE', 'ALSO_NOPE']), dict(read_only='yes'),
                dict(state=dict(prev_intent=None)), dict(extra=1)):
        with pytest.raises(dc.SchemaError):
            dc.validate_sample(dict(ok, **bad), table, 'raw')


def test_label_rules():
    st = dict(prev_intent='A', confirmed_params=[], pending_fields=[], last_action=None)
    dc.check_label('CHANGE_PARAM', 'A', st, ['A', 'B'])
    dc.check_label('CANCEL', None, st, ['A', 'B'])
    for args in (('CHANGE_PARAM', 'B', st, ['A', 'B']),      # continues previous intent only
                 ('CHANGE_PARAM', 'A', None, ['A', 'B']),    # no previous task
                 ('CANCEL', 'A', st, ['A', 'B']),
                 ('NEW_TASK', None, st, ['A', 'B']),
                 ('SHOUT', None, st, ['A', 'B'])):
        with pytest.raises(dc.SchemaError):
            dc.check_label(*args)


def labeled(row, act, intent):
    return dict(row,
                luna=dict(model=dc.LUNA, dialogue_act=act, intent=intent or dc.NONE_KEY,
                          intent_probs={c: 1 / len(row['candidates']) for c in row['candidates']}, act_probs={}),
                terra=dict(model=dc.TERRA, dialogue_act=act, intent=intent or dc.NONE_KEY))


def test_agreement_and_review_queue(table, tmp_path):
    r = raw_row(table)
    prev = r['state']['prev_intent']
    same = labeled(dict(r, id='a'), 'CHANGE_PARAM', prev)
    diff = labeled(dict(r, id='b'), 'CHANGE_PARAM', prev)
    diff['terra'] = dict(diff['terra'], dialogue_act='NEW_TASK', intent=r['candidates'][1])
    bad_both = labeled(dict(r, id='c', state=None), 'CHANGE_PARAM', prev)   # rule violation, no state
    dc.write_jsonl(tmp_path / 'labeled-raw.jsonl', [same, diff, bad_both])

    class A:
        work = str(tmp_path)

    dp.cmd_assemble(A, table)
    silver = dc.read_jsonl(tmp_path / 'silver.jsonl')
    queue = dc.read_jsonl(tmp_path / 'review-queue.jsonl')
    assert [x['id'] for x in silver] == ['a'] and {x['id'] for x in queue} == {'b', 'c'}
    assert all(x['agreement'] is False and x['dialogue_act'] is None for x in queue)   # never labeled truth
    assert {x['review_reason'] for x in queue} == {'disagree_act', 'invalid_luna'}
    dc.validate_sample(silver[0], table, 'labeled')
    assert abs(sum(silver[0]['teacher_probs'].values()) - 1) < 1e-3


def test_dedupe_drops_only_same_words_same_task(table, tmp_path):
    import argparse
    a_row = raw_row(table, id='a', utterance='换成上个月的')
    same = raw_row(table, id='b', utterance='换成上个月的 ')          # same normalized words, same prev task
    other_task = raw_row(table, id='c', utterance='换成上个月的',
                         state=dict(a_row['state'], prev_intent=a_row['candidates'][1]))
    dc.write_jsonl(tmp_path / 'old.jsonl', [a_row])
    dc.write_jsonl(tmp_path / 'new.jsonl', [same, other_task])
    dp.cmd_dedupe(argparse.Namespace(input=tmp_path / 'new.jsonl', against=[tmp_path / 'old.jsonl'],
                                     output=tmp_path / 'out.jsonl'), table)
    assert [r['id'] for r in dc.read_jsonl(tmp_path / 'out.jsonl')] == ['c']


def test_split_never_crosses_families():
    rows = [dict(family='F%d' % (i % 9), id=str(i)) for i in range(300)]
    assign = dp.split_families(rows)
    assert set(assign.values()) == set(dc.SPLITS)
    counts = {s: sum(assign[r['family']] == s for r in rows) for s in dc.SPLITS}
    assert counts['train'] > counts['validation'] and counts['train'] > counts['calibration']


def test_plan_is_deterministic_and_grounded(table):
    fams = dp.eligible_families(table)
    a, b = dp.plan_call(7, fams, table, 's'), dp.plan_call(7, fams, table, 's')
    assert a == b and len(a['slots']) == 6
    assert all(table[c]['read_only'] is not None for f in fams.values() for c in f)
    sw = {s['slot']: s for s in a['slots']}['switch_task']
    assert sw['intent'] != sw['prev']


def test_leak_gate_bites_and_passes_clean():
    index = lk.ProtectedIndex(['查一下这周的销售数据', '把今天的出库单全部确认发货'])
    seen = lk.self_test(index)             # exact hit and near hit blocked; unrelated passes
    assert seen['exact'] == 'exact' and seen['near'] and seen['clean'] is None
    rows = [dict(utterance='查一下这周的销售数据'), dict(utterance='冷库三号间温度记录发我'),
            dict(utterance='把今天的出库单全部确认发货了')]
    clean, dropped, reasons = lk.filter_samples(rows, index)
    assert [r['utterance'] for r in clean] == ['冷库三号间温度记录发我']
    assert len(dropped) == 2 and sum(reasons.values()) == 2


def test_missing_protected_source_is_an_error(tmp_path):
    with pytest.raises(FileNotFoundError):
        lk.ProtectedIndex.from_sources([tmp_path / 'nope.jsonl'])


class FakeResp:
    def __init__(self, code, body):
        self.status_code, self._b, self.headers = code, body, {'x-request-id': 'r'}

    def json(self):
        return self._b


def make_client(tmp_path, cap, code=200):
    key = tmp_path / 'k.env'
    key.write_text('OPENAI_API_KEY=sk-test-not-real\n', encoding='utf-8')
    c = dc.DollarClient(tmp_path / 'w', cap, key_file=key)
    calls = []

    def post(url, json):
        calls.append(url)
        return FakeResp(code, dict(usage=dict(input_tokens=1000, output_tokens=0), answers=[]))

    c.http.post = post
    return c, calls


def test_budget_cap_stops_before_http_and_receipts_hold_no_key(tmp_path):
    c, calls = make_client(tmp_path, cap=0.0012)
    payload = dict(model=dc.LUNA, input='x' * 100, questions=[])
    c.call('one', 'decisions', payload)
    assert c.call('one', 'decisions', payload) is c.done['one']['response']       # cache, no second POST
    assert len(calls) == 1
    with pytest.raises(dc.ApiStop):
        c.call('two', 'decisions', dict(payload, input='y' * 6000))              # reserve blows the cap
    assert len(calls) == 1
    text = (tmp_path / 'w' / 'api-results.jsonl').read_text(encoding='utf-8')
    assert 'sk-test-not-real' not in text and c.spent <= 0.0012


def test_rejected_call_stops_run(tmp_path):
    c, _ = make_client(tmp_path, cap=1, code=403)
    with pytest.raises(dc.ApiStop):
        c.call('a', 'decisions', dict(model=dc.LUNA, input='x', questions=[]))
    with pytest.raises(dc.ApiStop):
        c.call('b', 'decisions', dict(model=dc.LUNA, input='y', questions=[]))   # run stays stopped


def test_sealed_fingerprint_hit_and_absent_file(tmp_path):
    text = '查询 库存，A-1！'
    fp = tmp_path / 'fingerprints.jsonl'
    fp.write_text('{"hash": "%s", "family": "x"}' % lk.fingerprint('查询库存a1'), encoding='utf-8')
    index = lk.ProtectedIndex(['完全无关的一句话']).attach_sealed(lk.load_sealed_hashes(fp))
    assert index.check(text) == 'sealed_holdout'                 # punctuation/space/case-insensitive
    assert index.check('今天冷库温度如何呢') is None
    assert lk.load_sealed_hashes(tmp_path / 'missing.jsonl') is None   # reported as not provided, never as empty


def test_claude_teacher_sees_exactly_what_terra_sees(table, monkeypatch):
    import distill_claude_teacher as ct
    monkeypatch.setattr(dp, 'RUBRIC', dp.load_rubric(Path(__file__).parent / 'clarify_rubric.json'))
    row = raw_row(table)
    t, c = dp.terra_payload(row, table), ct.claude_request(row, table)
    assert c['system'] == t['instructions'] and c['messages'] == [dict(role='user', content=t['input'])]
    assert c['model'] == 'claude-fable-5-2'
    assert 'luna' not in str(c) and 'terra' not in str(c) and 'generator_plan' not in str(c)
    ok = dict(ok=True, fallback=False, stop_reason='end_turn', model='claude-fable-5-2',
              text='{"dialogue_act": "NEED_CLARIFY", "intent": "%s"}' % dc.NONE_KEY)
    assert ct.parse(ok, row)[2] is None
    assert ct.parse(dict(ok, fallback=True, model='claude-opus-4-8'), row)[2].startswith('served_by_fallback')
    assert ct.parse(dict(ok, stop_reason='refusal'), row)[2] == 'stop:refusal'


def test_adjudication_is_two_of_three_and_kept_apart(table, tmp_path, monkeypatch):
    import argparse
    import distill_claude_teacher as ct
    base = raw_row(table)
    prev = base['state']['prev_intent']
    def q(id, luna, terra):
        tt = lambda a, i: dict(dialogue_act=a, intent=i, issue=None)
        return dict(base, id=id, dialogue_act=None, intent=None, agreement=False, review_reason='disagree_act',
                    teachers=dict(luna=tt(*luna), terra=tt(*terra)), teacher_probs={})
    queue = [q('a', ('CHANGE_PARAM', prev), ('NEED_CLARIFY', None)),      # claude sides with terra
             q('b', ('CHANGE_PARAM', prev), ('NEED_CLARIFY', None)),      # claude says a third thing
             q('c', ('CHANGE_PARAM', prev), ('NEED_CLARIFY', None))]      # claude call failed
    dc.write_jsonl(tmp_path / 'queue.jsonl', queue)
    dc.write_jsonl(tmp_path / 'silver.jsonl', [])
    cl = lambda a, i, issue=None: dict(model='claude-fable-5-2', dialogue_act=a, intent=i, issue=issue)
    dc.write_jsonl(tmp_path / 'claude-labels.jsonl', [dict(id='a', claude=cl('NEED_CLARIFY', None)),
                                                     dict(id='b', claude=cl('CANCEL', None)),
                                                     dict(id='c', claude=cl(None, None, 'call_failed'))])
    ct.cmd_adjudicate(argparse.Namespace(work=tmp_path, queue=tmp_path / 'queue.jsonl', silver=tmp_path / 'silver.jsonl',
                                         silver_split_from=[]), table)
    adj = dc.read_jsonl(tmp_path / 'silver-adjudicated.jsonl')
    assert [r['id'] for r in adj] == ['a'] and adj[0]['dialogue_act'] == 'NEED_CLARIFY'
    assert adj[0]['adjudication']['sided_with'] == 'terra' and adj[0]['agreement'] is False
    assert {r['id'] for r in dc.read_jsonl(tmp_path / 'review-queue-after-claude.jsonl')} == {'b', 'c'}
    assert not (tmp_path / 'silver.jsonl').read_text(encoding='utf-8')   # unanimous silver untouched


def test_targeted_plan_is_about_the_target(table):
    reads = sorted(c for c, v in table.items() if v['read_only'] is True)
    writes = sorted(c for c, v in table.items() if v['read_only'] is False)
    for target in (reads[0], writes[0]):
        p = dp.plan_targeted(2000, target, table, 'seed')
        assert p['target'] == target and all(s['slot'] in dp.SLOTS for s in p['slots'])
        assert all(s['intent'] in (target, None) for s in p['slots'])
        last = p['slots'][-1]
        assert (last['act'], last['intent']) == (('PAGE', target) if table[target]['read_only'] else ('CANCEL', None))
        assert len(set(p['family_intents'][:8]) | set(p['distractors'])) >= 7
        assert 'fresh_task_c' in dp.gen_prompt(p, table)
    assert 'fresh_task_c' not in dp.gen_prompt(dp.plan_call(0, dp.eligible_families(table), table, 's'), table)
