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
