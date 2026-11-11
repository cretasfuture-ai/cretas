#!/usr/bin/env python3
"""INTENT-DISTILL-20261111 context-aware distillation data pipeline (P3).

Stages (each reads/writes JSONL under --work):
  generate   strong model writes multi-turn cases per semantic family  -> raw.jsonl
  (leak)     distill_leak_check.py drops protected hits                -> clean.jsonl
  label      Luna Choice and Terra label independently                 -> labeled-raw.jsonl
  assemble   agreement -> silver train; disagreement -> review queue   -> silver.jsonl, review-queue.jsonl
  split      by semantic family into train/validation/calibration      -> silver-{split}.jsonl
  report     agreement, Luna probability shape, spend, extrapolation   -> pilot-report.json

Never trains, never touches product code or prod data. Teachers never see each other's output.
"""
import argparse
import json
import random
import statistics
import sys
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from distill_common import (ACTS, GENERATOR, INTENT_ACTS, LUNA, NO_INTENT_ACTS, NONE_KEY, PARAM_SOURCES, SPLITS, TERRA,
                            ApiStop, DollarClient, SchemaError, build_intent_table, check_label, derive_read_only,
                            read_jsonl, validate_sample, write_jsonl)

SOURCE = 'INTENT-DISTILL-20261111'
SLOTS = ('change_param', 'page_or_cancel', 'switch_task', 'need_clarify', 'contrast_read', 'contrast_write')

ACT_HELP = ('对话动作定义：NEW_TASK=开始一个新任务或换了目标（哪怕和上一轮同领域）；'
            'CHANGE_PARAM=沿用上一轮任务，只改参数/口径/对象（如“换成上个月的”“只看二车间”）；'
            'PAGE=对上一轮结果翻页（下一页/上一页/再多看几条）；CANCEL=放弃当前任务（算了/不查了/别弄了）；'
            'NEED_CLARIFY=信息不足以判断要做什么、指代不明或口径含糊，必须先问用户。'
            '注意区分查询类与新建/修改/删除/确认类，动词决定动作，差一两个字意图可能完全不同。')
INTENT_HELP = ('意图规则：NEW_TASK 选最匹配的候选意图；CHANGE_PARAM 和 PAGE 选上一轮任务(state.prev_intent)；'
               'CANCEL 和 NEED_CLARIFY 不选意图（选 ' + NONE_KEY + '）。只做识别，不执行任何业务。')


# ------------------------------------------------------------------ generate
GEN_SCHEMA = dict(
    type='object', additionalProperties=False, required=['samples'],
    properties=dict(samples=dict(type='array', items=dict(
        type='object', additionalProperties=False,
        required=['slot', 'dialogue_act', 'planned_intent', 'state', 'utterance'],
        properties=dict(
            slot=dict(type='string', enum=list(SLOTS)),
            dialogue_act=dict(type='string', enum=list(ACTS)),
            planned_intent=dict(type=['string', 'null']),
            utterance=dict(type='string'),
            state=dict(type='object', additionalProperties=False,
                       required=['prev_intent', 'confirmed_params', 'pending_fields', 'last_action'],
                       properties=dict(
                           prev_intent=dict(type=['string', 'null']),
                           confirmed_params=dict(type='array', items=dict(
                               type='object', additionalProperties=False, required=['name', 'value', 'source'],
                               properties=dict(name=dict(type='string'), value=dict(type='string'),
                                               source=dict(type='string', enum=list(PARAM_SOURCES))))),
                           pending_fields=dict(type='array', items=dict(type='string')),
                           last_action=dict(type=['string', 'null']))))))))


def eligible_families(table, min_size=3):
    fams = defaultdict(list)
    for c, v in table.items():
        if v['read_only'] is not None:      # unresolved access mode is never a generation target
            fams[v['family']].append(c)
    return {f: sorted(cs) for f, cs in fams.items() if len(cs) >= min_size}


def plan_call(k, fams, table, seed):
    """Deterministic per-call plan: which family, which intents each slot is about."""
    rng = random.Random('%s:%d' % (seed, k))
    names = sorted(fams)
    random.Random(seed).shuffle(names)
    fam = names[k % len(names)]
    cs = fams[fam]
    reads = [c for c in cs if table[c]['read_only']]
    writes = [c for c in cs if not table[c]['read_only']]
    pick = lambda pool: rng.choice(pool or cs)
    x = pick(reads if rng.random() < .6 else writes)
    slots = [dict(slot='change_param', act='CHANGE_PARAM', prev=x, intent=x)]
    if k % 2 == 0 and reads:
        r = pick(reads)
        slots.append(dict(slot='page_or_cancel', act='PAGE', prev=r, intent=r))
    else:
        p = pick(cs)
        slots.append(dict(slot='page_or_cancel', act='CANCEL', prev=p, intent=None))
    x2 = pick(cs)
    y = rng.choice([c for c in cs if c != x2])
    slots.append(dict(slot='switch_task', act='NEW_TASK', prev=x2, intent=y))
    slots.append(dict(slot='need_clarify', act='NEED_CLARIFY', prev=pick(cs) if k % 2 else None, intent=None))
    if reads and writes:
        slots.append(dict(slot='contrast_read', act='NEW_TASK', prev=None, intent=rng.choice(reads)))
        slots.append(dict(slot='contrast_write', act='NEW_TASK', prev=None, intent=rng.choice(writes)))
    else:   # no read/write contrast available in this family: plain fresh-session tasks
        for s in ('contrast_read', 'contrast_write'):
            slots.append(dict(slot=s, act='NEW_TASK', prev=None, intent=pick(cs)))
    others = [c for f, v in fams.items() if f != fam for c in v]
    return dict(call=k, family=fam, family_intents=cs, slots=slots, distractors=rng.sample(others, 3))


def gen_prompt(plan, table):
    intents = '\n'.join('- %s | %s | %s' % (c, table[c]['name'], table[c]['description'][:80]) for c in plan['family_intents'])
    slot_lines = []
    for s in plan['slots']:
        prev = s['prev']
        slot_lines.append('slot=%s dialogue_act=%s planned_intent=%s state.prev_intent=%s' % (
            s['slot'], s['act'], s['intent'] or 'null', prev or 'null(全新会话，state 全空)'))
    return (
        '你在为食品工厂 ERP 的对话意图模型编写多轮训练样本（只需要“上一轮任务状态”和“用户当前这一句”）。\n'
        '本组业务领域及其意图：\n' + intents + '\n\n'
        '请严格按下面每个 slot 各写 1 条样本，slot/dialogue_act/planned_intent 原样回填：\n' + '\n'.join(slot_lines) + '\n\n'
        '要求：\n'
        '1. utterance 是工厂一线人员的口语，2~40 字，不要出现意图 ID、英文代码或“意图”字样，不要带称呼。\n'
        '2. state 描述上一轮：prev_intent 必须是上面给出的值（全新会话则为 null 且 confirmed_params、pending_fields 为空数组、last_action 为 null）；'
        'confirmed_params 写已确认的业务参数（name/value 用中文业务词，source 取 explicit_turn/confirmed/default）；'
        'pending_fields 是系统还在等用户回答的字段；last_action 一句话写系统上一步做了什么。\n'
        '3. change_param：只改参数/口径，不说任务名；page_or_cancel：PAGE 是翻页类说法，CANCEL 是放弃类说法；'
        'switch_task：换成 planned_intent 对应的新任务，说清楚新对象；need_clarify：口径含糊，在本领域多个意图都说得通，不能判断要做什么。\n'
        '4. contrast_read 与 contrast_write 是一对：两句话字面只差一两个字，但前者是只读查询，后者是新建/修改/删除/确认类动作（按各自 planned_intent 的真实含义写）；两句都是全新会话。\n'
        '5. 不要写标准答案以外的任何解释。样本之间不要互相复述。')


def valid_gen(item, plan_slot, table):
    """Reject items that drift from the plan. Returns an error string or None."""
    if item['slot'] != plan_slot['slot'] or item['dialogue_act'] != plan_slot['act']:
        return 'slot_or_act_drift'
    if item['planned_intent'] != plan_slot['intent']:
        return 'intent_drift'
    u = item['utterance'].strip()
    if not 2 <= len(u) <= 80:
        return 'utterance_length'
    if any(c in u for c in table):
        return 'intent_id_in_utterance'
    st = item['state']
    if plan_slot['prev'] is None:
        if st['prev_intent'] or st['confirmed_params'] or st['pending_fields'] or st['last_action']:
            return 'state_not_empty'
    elif st['prev_intent'] != plan_slot['prev']:
        return 'prev_intent_drift'
    return None


def gen_one(client, plan, table, seed):
    payload = dict(model=GENERATOR, store=False, reasoning=dict(effort='low'),
                   instructions='你是严谨的数据编写员，只输出符合 schema 的 JSON。', input=gen_prompt(plan, table),
                   max_output_tokens=3000,
                   text=dict(format=dict(type='json_schema', name='distill_cases', strict=True, schema=GEN_SCHEMA)))
    body = client.call('gen:%03d' % plan['call'], 'responses', payload, max_output_tokens=3000)
    text = ''.join(p.get('text', '') for it in body.get('output', []) for p in it.get('content', []) if p.get('type') == 'output_text')
    items = json.loads(text)['samples']
    by_slot = {i['slot']: i for i in items}
    rng = random.Random('%s:cand:%d' % (seed, plan['call']))
    out, rejected = [], defaultdict(int)
    for n, s in enumerate(plan['slots']):
        item = by_slot.get(s['slot'])
        if item is None:
            rejected['missing_slot'] += 1
            continue
        err = valid_gen(item, s, table)
        if err:
            rejected[err] += 1
            continue
        state = item['state']
        if not state['prev_intent']:
            state = None
        pool = list(plan['family_intents'])
        if len(pool) > 8:
            must = {c for c in (s['intent'], s['prev']) if c}
            pool = sorted(must) + rng.sample([c for c in pool if c not in must], 8 - len(must))
        cands = pool + [c for c in plan['distractors'] if c not in pool]
        rng.shuffle(cands)
        ro = derive_read_only(s['intent'], state, table)
        row = dict(id='d20261111-%03d-%d' % (plan['call'], n), utterance=item['utterance'].strip(), state=state,
                   candidates=cands, read_only=ro, family=plan['family'],
                   generator_plan=dict(dialogue_act=s['act'], intent=s['intent'], slot=s['slot'], model=GENERATOR),
                   source=SOURCE)
        try:
            validate_sample(row, table, 'raw')
        except SchemaError as e:
            rejected['schema:' + str(e)[:40]] += 1
            continue
        out.append(row)
    return out, dict(rejected)


def cmd_generate(a, table):
    fams = eligible_families(table)
    client = DollarClient(a.work, a.cap)
    plans = [plan_call(k, fams, table, a.seed) for k in range(a.start, a.start + a.calls)]
    rows, rejected = [], defaultdict(int)
    try:
        with ThreadPoolExecutor(a.concurrency) as ex:
            for out, rej in ex.map(lambda p: gen_one(client, p, table, a.seed), plans):
                rows.extend(out)
                for k, v in rej.items():
                    rejected[k] += v
    finally:
        client.close()
        print('SPEND', json.dumps(client.summary()))
    seen, uniq = set(), []
    from flywheel_reviewed import normalized
    for r in rows:
        # same words under a different task state are different samples (e.g. "下一页" after different lists)
        key = (normalized(r['utterance']), (r['state'] or {}).get('prev_intent'), r['family'])
        if key in seen:
            rejected['duplicate_utterance'] += 1
            continue
        seen.add(key)
        uniq.append(r)
    write_jsonl(Path(a.work) / 'raw.jsonl', uniq)
    Path(a.work, 'generate-report.json').write_text(json.dumps(dict(
        calls=a.calls, samples=len(uniq), rejected=dict(rejected), families=len(fams)), ensure_ascii=False, indent=2), encoding='utf-8')
    print('GENERATED', len(uniq), 'rejected', dict(rejected))


# ------------------------------------------------------------------ label
def label_context(row, table):
    return json.dumps(dict(
        state=row['state'], utterance=row['utterance'],
        candidates=[dict(id=c, name=table[c]['name'], description=table[c]['description'][:80]) for c in row['candidates']]),
        ensure_ascii=False)


def luna_payload(row, table):
    choices = [dict(value=c, description=table[c]['name'] + '：' + table[c]['description'][:60]) for c in row['candidates']]
    choices.append(dict(value=NONE_KEY, description='不选意图（取消，或需要澄清）'))
    return dict(model=LUNA, input=label_context(row, table), questions=[
        dict(type='choice', name='dialogue_act', instructions='判断当前这句话在对话中的动作。' + ACT_HELP,
             choices=[dict(value=x, description=x) for x in ACTS]),
        dict(type='choice', name='intent', instructions='只依据 state 与 utterance 选择意图。' + INTENT_HELP, choices=choices)])


def terra_payload(row, table):
    schema = dict(type='object', additionalProperties=False, required=['dialogue_act', 'intent'], properties=dict(
        dialogue_act=dict(type='string', enum=list(ACTS)), intent=dict(type='string', enum=row['candidates'] + [NONE_KEY])))
    return dict(model=TERRA, service_tier='default', store=False, reasoning=dict(effort='low'),
                instructions='你是食品工厂 ERP 对话意图的标注教师。依据 state（上一轮任务状态）、utterance（当前句）与候选意图判断对话动作和意图。'
                             + ACT_HELP + INTENT_HELP + '只输出 JSON。',
                input=label_context(row, table), max_output_tokens=300,
                text=dict(format=dict(type='json_schema', name='distill_label', strict=True, schema=schema)))


def parse_luna(body):
    ans = {a['name']: a for a in body['answers']}
    probs = {p['value']: float(p['probability']) for p in ans['intent']['probabilities']}
    acts = {p['value']: float(p['probability']) for p in ans['dialogue_act']['probabilities']}
    return dict(model=LUNA, dialogue_act=ans['dialogue_act']['choice'], intent=ans['intent']['choice'],
                intent_probs=probs, act_probs=acts)


def parse_terra(body):
    text = ''.join(p.get('text', '') for it in body.get('output', []) for p in it.get('content', []) if p.get('type') == 'output_text')
    r = json.loads(text)
    return dict(model=TERRA, dialogue_act=r['dialogue_act'], intent=r['intent'])


def label_one(client, row, table):
    luna = parse_luna(client.call('luna:' + row['id'], 'decisions', luna_payload(row, table)))
    terra = parse_terra(client.call('terra:' + row['id'], 'responses', terra_payload(row, table), max_output_tokens=300))
    return dict(row, luna=luna, terra=terra)


def conclude(t, state, cands):
    """Normalise one teacher's answer; returns (act, intent, issue|None)."""
    act = t['dialogue_act']
    intent = None if t['intent'] == NONE_KEY else t['intent']
    if act in NO_INTENT_ACTS:
        intent = None     # a stray intent on CANCEL/NEED_CLARIFY carries no meaning
    try:
        check_label(act, intent, state, cands)
    except SchemaError as e:
        return act, intent, str(e)
    return act, intent, None


def cmd_label(a, table):
    rows = read_jsonl(Path(a.work) / 'clean.jsonl')[:a.limit]
    out_path = Path(a.work) / 'labeled-raw.jsonl'
    client = DollarClient(a.work, a.cap)
    done, agree_n = [], 0
    try:
        with ThreadPoolExecutor(a.concurrency) as ex:
            for first in range(0, len(rows), a.check_after):
                batch = rows[first:first + a.check_after]
                got = list(ex.map(lambda r: label_one(client, r, table), batch))
                done.extend(got)
                n = len(done)
                agree_n = sum(agreed(r) for r in done)
                print('LABELED %d agreement %.3f spent %.4f' % (n, agree_n / n, client.spent), flush=True)
                if first == 0 and agree_n / n < .70:
                    write_jsonl(out_path, done)
                    print('STOP_AGREEMENT_BELOW_70', agree_n / n)
                    sys.exit(3)
    finally:
        client.close()
        write_jsonl(out_path, done)
        print('SPEND', json.dumps(client.summary()))


def agreed(r):
    la, li, lbad = conclude(r['luna'], r['state'], r['candidates'])
    ta, ti, tbad = conclude(r['terra'], r['state'], r['candidates'])
    return not lbad and not tbad and la == ta and li == ti


# ------------------------------------------------------------------ assemble / split
def luna_probs(r):
    p = {k: v for k, v in r['luna']['intent_probs'].items() if k in r['candidates'] or k == NONE_KEY}
    s = sum(p.values()) or 1.0
    return {k: round(v / s, 6) for k, v in p.items()}


def cmd_assemble(a, table):
    rows = read_jsonl(Path(a.work) / 'labeled-raw.jsonl')
    silver, queue, ro_fix = [], [], 0
    for r in rows:
        la, li, lbad = conclude(r['luna'], r['state'], r['candidates'])
        ta, ti, tbad = conclude(r['terra'], r['state'], r['candidates'])
        base = {k: r[k] for k in ('id', 'utterance', 'state', 'candidates', 'read_only', 'family', 'generator_plan', 'source')}
        teachers = dict(luna=dict(model=LUNA, dialogue_act=la, intent=li, issue=lbad, raw_intent=r['luna']['intent'], raw_dialogue_act=r['luna']['dialogue_act']),
                        terra=dict(model=TERRA, dialogue_act=ta, intent=ti, issue=tbad, raw_intent=r['terra']['intent'], raw_dialogue_act=r['terra']['dialogue_act']))
        if not lbad and not tbad and la == ta and li == ti:
            ro = derive_read_only(li, r['state'], table)
            if ro != base['read_only']:
                ro_fix += 1
            row = dict(base, read_only=ro, dialogue_act=la, intent=li, teacher_probs=luna_probs(r),
                       teachers=teachers, agreement=True)
            validate_sample(row, table, 'labeled')
            silver.append(row)
        else:
            reason = ('invalid_luna' if lbad else 'invalid_terra' if tbad else 'disagree_act' if la != ta else 'disagree_intent')
            row = dict(base, dialogue_act=None, intent=None, teacher_probs=luna_probs(r), teachers=teachers,
                       agreement=False, review_reason=reason)
            validate_sample(row, table, 'review')
            queue.append(row)
    write_jsonl(Path(a.work) / 'silver.jsonl', silver)
    write_jsonl(Path(a.work) / 'review-queue.jsonl', queue)    # human review only; never training data
    print('ASSEMBLED silver=%d review=%d read_only_recomputed=%d' % (len(silver), len(queue), ro_fix))


def split_families(rows, ratios=(.70, .15, .15)):
    """Greedy by family size so no family appears in two splits."""
    size = defaultdict(int)
    for r in rows:
        size[r['family']] += 1
    total, assign, load = len(rows), {}, dict.fromkeys(SPLITS, 0)
    for fam in sorted(size, key=lambda f: (-size[f], f)):
        best = max(range(3), key=lambda i: ratios[i] * total - load[SPLITS[i]])
        assign[fam] = SPLITS[best]
        load[SPLITS[best]] += size[fam]
    return assign


def cmd_split(a, table):
    rows = read_jsonl(Path(a.work) / 'silver.jsonl')
    assign = split_families(rows)
    parts = {s: [] for s in SPLITS}
    for r in rows:
        r = dict(r, split=assign[r['family']])
        parts[r['split']].append(r)
    for s, v in parts.items():
        write_jsonl(Path(a.work) / ('silver-%s.jsonl' % s), v)
    bad = set(assign.values())
    fam_split = defaultdict(set)
    for s, v in parts.items():
        for r in v:
            fam_split[r['family']].add(s)
    assert all(len(x) == 1 for x in fam_split.values()), 'family crosses splits'
    print('SPLIT', {s: len(v) for s, v in parts.items()}, 'families', len(fam_split), bad and '')


# ------------------------------------------------------------------ report
def rate(num, den):
    return None if not den else round(num / den, 4)


def cmd_report(a, table):
    work = Path(a.work)
    labeled = read_jsonl(work / 'labeled-raw.jsonl')
    silver = read_jsonl(work / 'silver.jsonl')
    queue = read_jsonl(work / 'review-queue.jsonl')
    ag = {r['id'] for r in silver}
    by_act, by_ro = defaultdict(lambda: [0, 0]), defaultdict(lambda: [0, 0])
    for r in labeled:
        for bucket, key in ((by_act, r['generator_plan']['dialogue_act']), (by_ro, {True: 'read_only', False: 'write', None: 'unresolved'}[r['read_only']])):
            bucket[key][1] += 1
            bucket[key][0] += r['id'] in ag
    maxp = [max(r['teacher_probs'].values()) for r in silver]
    plan_match = sum(r['dialogue_act'] == r['generator_plan']['dialogue_act'] and r['intent'] == r['generator_plan']['intent'] for r in silver)
    reasons = defaultdict(int)
    for r in queue:
        reasons[r['review_reason']] += 1
    leak = json.loads((work / 'leak-report.json').read_text(encoding='utf-8')) if (work / 'leak-report.json').exists() else {}
    gen = json.loads((work / 'generate-report.json').read_text(encoding='utf-8')) if (work / 'generate-report.json').exists() else {}
    results = read_jsonl(work / 'api-results.jsonl')
    by_model = defaultdict(lambda: dict(calls=0, price_upper_usd=0.0))
    for r in results:
        by_model[r['model']]['calls'] += 1
        by_model[r['model']]['price_upper_usd'] += r.get('price_upper_usd', 0)
    spend = sum(m['price_upper_usd'] for m in by_model.values())
    label_spend = sum(by_model[m]['price_upper_usd'] for m in (LUNA, TERRA) if m in by_model)
    report = dict(
        n_labeled=len(labeled), agreed=len(silver), review_queue=len(queue),
        agreement_overall=rate(len(silver), len(labeled)),
        agreement_by_generator_dialogue_act={k: dict(n=v[1], agreed=v[0], rate=rate(*v)) for k, v in sorted(by_act.items())},
        agreement_by_read_only={k: dict(n=v[1], agreed=v[0], rate=rate(*v)) for k, v in sorted(by_ro.items())},
        review_reasons=dict(reasons),
        luna_max_prob_on_agreed=dict(n=len(maxp), median=round(statistics.median(maxp), 4) if maxp else None,
                                     share_below_0_6=rate(sum(p < .6 for p in maxp), len(maxp))),
        agreed_label_equals_generator_plan=dict(n=plan_match, rate=rate(plan_match, len(silver)),
                                                note='diagnostic only: teacher agreement is silver, not human truth'),
        leak_check=dict(checked=leak.get('checked'), dropped=leak.get('dropped'), reasons=leak.get('reasons')),
        generation=gen,
        api=dict(calls=len(results), by_model={k: dict(v) for k, v in by_model.items()}, price_upper_usd=round(spend, 4)),
        cost_per_1000_samples_upper_usd=dict(
            labeling_only=round(label_spend / len(labeled) * 1000, 2) if labeled else None,
            generation_plus_labeling=round(spend / len(labeled) * 1000, 2) if labeled else None,
            note='upper bounds from list prices (Terra at cache-write input, generator at max output); not an invoice'))
    (work / 'pilot-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('stage', choices=['generate', 'label', 'assemble', 'split', 'report', 'validate'])
    ap.add_argument('--work', required=True, help='evidence directory outside git (ledger + receipts live here)')
    ap.add_argument('--cap', type=float, default=10.0, help='hard USD upper-bound cap for this lane')
    ap.add_argument('--seed', default='distill-20261111')
    ap.add_argument('--calls', type=int, default=84)
    ap.add_argument('--start', type=int, default=0)
    ap.add_argument('--limit', type=int, default=500)
    ap.add_argument('--check-after', type=int, default=100)
    ap.add_argument('--concurrency', type=int, default=4)
    ap.add_argument('--file', help='validate: jsonl to check')
    ap.add_argument('--schema-stage', default='labeled')
    a = ap.parse_args()
    table = build_intent_table()
    if a.stage == 'validate':
        rows = read_jsonl(a.file)
        for r in rows:
            validate_sample(r, table, a.schema_stage)
        print('VALID', len(rows))
        return
    try:
        globals()['cmd_' + a.stage](a, table)
    except ApiStop as e:
        print('API_STOP', e)
        sys.exit(2)


if __name__ == '__main__':
    main()
