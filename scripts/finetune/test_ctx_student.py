"""Offline tests for the context-aware student (no GPU, no weights, no network).

Run: python -m pytest scripts/finetune/test_ctx_student.py
"""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))
import ctx_student as cs

TABLE = {
    'R1': dict(name='库存查询', description='查库存', read_only=True),
    'R2': dict(name='销量排名', description='查排名', read_only=True),
    'W1': dict(name='创建订单', description='新建订单', read_only=False),
}


def row(**kw):
    base = dict(id='x1', utterance='看下库存', state=None, candidates=['R1', 'R2', 'W1'], dialogue_act='NEW_TASK',
                intent='R1', read_only=True, family='F', teacher_probs={'R1': .8, 'R2': .1, 'W1': .05, '__NONE__': .05})
    base.update(kw)
    return base


def logits(act, target_idx, n=3, strength=8.0):
    a = [0.0] * 5
    a[cs.ACT_ID[act]] = strength
    t = [0.0] * (n + 1)
    t[target_idx] = strength
    return dict(act_logits=a, target_logits=t)


POL = dict(t_act=1.0, t_target=1.0, act_min=.9, target_min=.9, write_max=.05)


def test_serialization_is_fixed_format_and_marks_previous_task():
    r = row(state=dict(prev_intent='R2', confirmed_params=[dict(name='时间', value='本周', source='confirmed')],
                       pending_fields=['产品'], last_action='系统问产品'))
    ctx = cs.serialize_context(r, TABLE)
    assert ctx.splitlines() == ['句：看下库存', '上轮目标：销量排名（只读）', '已确认：时间=本周', '待答：产品', '上次动作：系统问产品']
    assert cs.serialize_context(row(), TABLE).splitlines()[1:] == ['上轮目标：无', '已确认：无', '待答：无', '上次动作：无']
    cands = cs.serialize_candidates(r, TABLE)
    assert cands.count(cs.MARK) == 3 and cs.MARK + cs.PREV + '销量排名' in cands


def test_targets_and_soft_labels():
    assert cs.target_index(row()) == 0
    assert cs.target_index(row(intent=None, dialogue_act='NEED_CLARIFY')) == 3
    st = cs.soft_target(row())
    assert abs(sum(st) - 1) < 1e-9 and st[0] == pytest.approx(.8)
    assert cs.use_soft(row())
    assert not cs.use_soft(row(adjudication=dict(sided_with='terra')))      # Luna outvoted -> no KL
    assert cs.use_soft(row(adjudication=dict(sided_with='luna')))


def test_confident_read_target_goes_direct():
    d = cs.decide(logits('NEW_TASK', 0), row(), TABLE, POL)
    assert d['direct'] and d['intent'] == 'R1' and cs.is_correct(d, row())


@pytest.mark.parametrize('act', ['CANCEL', 'NEED_CLARIFY'])
def test_cancel_and_clarify_always_escalate(act):
    assert not cs.decide(logits(act, 0), row(), TABLE, POL)['direct']


def test_write_target_never_direct_even_when_certain():
    # write_max=1.0 disables the mass cap so this isolates the read-only-target rule
    d = cs.decide(logits('NEW_TASK', 2, strength=50), row(intent='W1', read_only=False), TABLE, dict(POL, write_max=1.0))
    assert d['intent'] == 'W1' and not d['direct']


def test_none_target_never_direct():
    assert not cs.decide(logits('NEW_TASK', 3, strength=50), row(), TABLE, POL)['direct']


def test_write_mass_cap_blocks_direct():
    p = dict(act_logits=logits('NEW_TASK', 0)['act_logits'], target_logits=[5.0, 0.0, 2.5, -9.0])
    d = cs.decide(p, row(), TABLE, dict(POL, target_min=.5))
    assert d['write_mass'] > .05 and not d['direct']
    assert cs.decide(p, row(), TABLE, dict(POL, target_min=.5, write_max=.2))['direct']


def test_low_confidence_escalates():
    p = dict(act_logits=[1.0, .9, 0, 0, 0], target_logits=[8.0, 0, -9, -9])
    assert not cs.decide(p, row(), TABLE, POL)['direct']


def test_score_counts_direct_on_write_gold_and_coverage_over_read_only():
    rows = [row(id='a'), row(id='b', intent='R2'), row(id='c', intent='W1', read_only=False),
            row(id='d', intent=None, dialogue_act='NEED_CLARIFY', read_only=None)]
    preds = [logits('NEW_TASK', 0), logits('NEW_TASK', 0), logits('NEW_TASK', 0), logits('NEW_TASK', 1)]
    s = cs.score_policy(preds, rows, TABLE, POL)
    assert s['read_only_n'] == 2 and s['read_only_direct'] == 2 and s['coverage'] == 1.0
    assert s['direct'] == 4 and s['correct'] == 1
    assert s['write_gold_direct'] == 1 and s['write_gold_direct_ids'] == ['c']
    assert s['nonread_gold_direct'] == 2 and s['predicted_write_direct'] == 0


def test_policy_selection_requires_zero_direct_on_non_read_gold():
    rows = [row(id='a'), row(id='c', intent='W1', read_only=False)]
    # the write-gold sample looks like a confident read request: only an unreachable gate avoids it
    preds = [logits('NEW_TASK', 0, strength=10), logits('NEW_TASK', 0, strength=10)]
    pol, _ = cs.select_policy(preds, rows, TABLE, 1.0, 1.0)
    assert pol is None
    preds = [logits('NEW_TASK', 0, strength=10), logits('NEW_TASK', 0, strength=3)]
    pol, _ = cs.select_policy(preds, rows, TABLE, 1.0, 1.0)
    s = cs.score_policy(preds, rows, TABLE, pol)
    assert s['nonread_gold_direct'] == 0 and s['coverage'] == 1.0


def test_no_target_clarify_counts_as_error_by_default_and_hard_zero_when_strict():
    rows = [row(id='a%d' % i) for i in range(60)] + [row(id='q', intent=None, dialogue_act='NEED_CLARIFY', read_only=None)]
    preds = [logits('NEW_TASK', 0, strength=10)] * 61
    pol, _ = cs.select_policy(preds, rows, TABLE, 1.0, 1.0)          # 60/61 = 98.4% accurate, no write gold
    assert pol is not None and cs.score_policy(preds, rows, TABLE, pol)['nonread_gold_direct'] == 1
    pol, _ = cs.select_policy(preds, rows, TABLE, 1.0, 1.0, hard='nonread_gold_direct')
    assert pol is None


def test_wilson_and_temperature():
    lo, hi = cs.wilson(98, 100)
    assert 0.92 < lo < .98 < hi < 1.0
    assert cs.wilson(0, 0) == (None, None)
    # overconfident logits: fitted temperature > 1 lowers NLL
    ls = [[6.0, 0.0]] * 7 + [[0.0, 6.0]] * 3
    gold = [0] * 10
    t, n1, nt = cs.fit_temperature(ls, gold)
    assert t > 1 and nt < n1


def _splits(**over):
    s = dict(train=[row(id='t1', family='A')], validation=[row(id='v1', family='B', utterance='另一句')],
             calibration=[row(id='c1', family='C', utterance='第三句')], adjudicated_validation=[], adjudicated_calibration=[])
    s.update(over)
    return s


def test_disjointness_checks():
    cs.check_disjoint(_splits(), [])
    with pytest.raises(ValueError, match='three-way'):
        cs.check_disjoint(_splits(), [dict(id='t1')])
    with pytest.raises(ValueError, match='family crosses'):
        cs.check_disjoint(_splits(validation=[row(id='v1', family='A', utterance='另一句')]), [])
    with pytest.raises(ValueError, match='identical sample'):
        cs.check_disjoint(_splits(calibration=[row(id='c1', family='C')]), [])


def test_frozen_inputs_fail_closed(tmp_path):
    (tmp_path / 'a.jsonl').write_text('{}\n', encoding='utf-8')
    cs.verify_frozen(tmp_path, {'a.jsonl': cs.sha256_file(tmp_path / 'a.jsonl')})
    with pytest.raises(ValueError, match='frozen input changed'):
        cs.verify_frozen(tmp_path, {'a.jsonl': '0' * 64})


def _k(i, utt, intent='R1', act='NEW_TASK', prev=None):
    return row(id='k%d' % i, utterance=utt, intent=intent, dialogue_act=act,
               state=None if prev is None else dict(prev_intent=prev, confirmed_params=[], pending_fields=[], last_action=None))


def test_paraphrase_clusters_merge_near_duplicates_only():
    rows = [_k(1, '查一下今天的库存'), _k(2, '查一下今天库存'), _k(3, '本周销量排名给我看看')]
    sizes = sorted(len(c) for c in cs.paraphrase_clusters(rows))
    assert sizes == [1, 2]


def test_known_split_keeps_every_target_in_train_and_paraphrases_together():
    rows = []
    phrases = ['查一下今天的库存', '查一下今天库存', '仓库里还剩多少货', '盘点结果出来没', '冷库温度正常吗', '原料还够几天用',
               '帮我看看成品数量', '库龄超过三十天的有哪些', '最近入库记录', '出库明细拉一下']
    for i, p in enumerate(phrases):
        rows.append(_k(i, p, intent='R1'))
    rows.append(_k(99, '只有一条的意图', intent='R2'))
    assign = cs.known_intent_split(rows)
    assert assign['k99'] == 'train'                       # single-cluster target stays in train
    assert assign['k0'] == assign['k1']                   # near-duplicates never split apart
    assert any(assign['k%d' % i] == 'train' for i in range(10))
    assert cs.known_intent_split(rows) == assign          # deterministic
    splits = {n: [] for n in cs.SPLIT_NAMES}
    for r in rows:
        splits[assign[r['id']]].append(r)
    cs.check_known_split(splits)


def test_known_split_check_rejects_leaks():
    base = {n: [] for n in cs.SPLIT_NAMES}
    with pytest.raises(ValueError, match='without train rows'):
        cs.check_known_split(dict(base, train=[_k(1, '查库存')], validation=[_k(2, '看排名', intent='R2')]))
    with pytest.raises(ValueError, match='paraphrase'):
        cs.check_known_split(dict(base, train=[_k(1, '查一下今天的库存')], validation=[_k(2, '查一下今天库存')]))
    # same words about a different task are a different sample (e.g. 算了 after different lists)
    cs.check_known_split(dict(base, train=[_k(1, '算了', intent=None, act='CANCEL', prev='R1')],
                              validation=[_k(2, '算了', intent=None, act='CANCEL', prev='R2')]))


def test_ensemble_is_geometric_mean_and_requires_alignment():
    a = [dict(id='x', act_logits=[2.0, 0, 0, 0, 0], target_logits=[1.0, 0.0])]
    b = [dict(id='x', act_logits=[0.0, 2.0, 0, 0, 0], target_logits=[1.0, 0.0])]
    e = cs.ensemble([a, b])[0]
    assert e['act_logits'][0] == pytest.approx(e['act_logits'][1])
    assert cs.softmax(e['target_logits'])[0] == pytest.approx(cs.softmax([1.0, 0.0])[0])
    with pytest.raises(ValueError, match='aligned'):
        cs.ensemble([a, [dict(b[0], id='y')]])
