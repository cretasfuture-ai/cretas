#!/usr/bin/env python3
"""Student-v2 data (INTENT-DISTILL-20261111): targeted generation on Claude + three-teacher labelling.

Teachers (provisional, from the teacher bake-off; recorded per row so agreement and weights can
be recomputed once Steve's review is in):
  hard, main     gpt-6.1-terra @ low   (OpenAI Responses, same prompt as the bake-off / F-pre Terra)
  hard, second   claude-opus-5-5 @ low (different vendor, same prompt)
  soft           gpt-6-luna            (Decisions probabilities; its argmax is recorded, not voted)
Silver = Terra and Opus give the same valid (act, intent). Everything else goes to the review queue.

Generation targets v1's bottlenecks (``plan_boundary``): paraphrased follow-ups, write->read task
switches, PAGE phrasings, and NEED_CLARIFY / NEW_TASK minimal pairs. The generator is a Claude
model (default claude-haiku-5-5), so OpenAI spend stays on labelling.

Stages (outputs in --work, outside git):
  generate   --calls N --start K [--gen-model M]       -> raw.jsonl
  label      --input rows.jsonl                         -> labeled-raw.jsonl (resumable)
  assemble                                              -> silver.jsonl / review-queue.jsonl
Dedupe and the anti-leak gate use the existing tools (distill_pipeline.py dedupe, distill_leak_check.py).
Nothing here trains, touches product code or prod data, or opens the sealed acceptance set.
"""
import argparse
import json
import random
import sys
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from distill_common import (ACTS, NONE_KEY, PARAM_SOURCES, ApiStop, DollarClient, SchemaError, build_intent_table,  # noqa: E402
                            derive_read_only, read_jsonl, validate_sample, write_jsonl)
import distill_pipeline as dp  # noqa: E402
import teacher_bakeoff as tb  # noqa: E402

SOURCE = 'INTENT-DISTILL-20261111-v4'
# Teacher pair after Steve's 46-item review (2026-11-12): Opus main, gpt-6-sol second, Luna soft.
# The first v4 batch (v4/topup-rest) used Terra main + Opus second under rubric v1; rows say which.
MAIN, SECOND = ('claude-opus-5-5', 'low'), ('gpt-6-sol', 'medium')
OLD_PAIR = (('gpt-6.1-terra', 'low'), ('claude-opus-5-5', 'low'))
GEN_PRICES = {'claude-haiku-5-5': (0.10, 0.50), 'claude-sonnet-5-5': (2.0, 10.0)}
BSLOTS = ('page_a', 'page_b', 'cont_paraphrase', 'write_to_read', 'boundary_clear', 'boundary_vague')


# ---------------------------------------------------------------- plan / prompt
def plan_boundary(k, table, seed):
    """One family per call; six slots aimed at v1's weak spots. Families without a READ intent
    cannot host PAGE / write->read slots and are skipped by the caller."""
    rng = random.Random('%s:b:%d' % (seed, k))
    fams = defaultdict(list)
    for c, v in table.items():
        if v['read_only'] is not None:
            fams[v['family']].append(c)
    names = sorted(f for f, cs in fams.items() if any(table[c]['read_only'] for c in cs))
    random.Random(seed).shuffle(names)
    fam = names[k % len(names)]
    cs = sorted(fams[fam])
    reads = [c for c in cs if table[c]['read_only']]
    writes = [c for c in cs if not table[c]['read_only']]
    r1, r2 = rng.choice(reads), rng.choice(reads)
    clear = rng.choice(reads)
    slots = [dict(slot='page_a', act='PAGE', prev=r1, intent=r1),
             dict(slot='page_b', act='PAGE', prev=r2, intent=r2),
             dict(slot='cont_paraphrase', act='CHANGE_PARAM', prev=rng.choice(cs), intent=None),
             dict(slot='write_to_read', act='NEW_TASK', prev=rng.choice(writes) if writes else None, intent=rng.choice(reads)),
             dict(slot='boundary_clear', act='NEW_TASK', prev=None, intent=clear),
             dict(slot='boundary_vague', act='NEED_CLARIFY', prev=None, intent=None)]
    slots[2]['intent'] = slots[2]['prev']
    others = sorted(c for f, v in fams.items() if f != fam for c in v)
    pool = cs if len(cs) <= 8 else sorted({s['intent'] for s in slots if s['intent']} | {s['prev'] for s in slots if s['prev']}
                                          | set(rng.sample(cs, 8)))
    return dict(call=k, family=fam, family_intents=cs, pool=pool, slots=slots,
                distractors=rng.sample(others, max(3, 7 - min(len(pool), 8))))


def gen_prompt(plan, table):
    intents = '\n'.join('- %s | %s | %s | %s' % (c, table[c]['name'], '只读查询' if table[c]['read_only'] else '会改数据',
                                                table[c]['description'][:80]) for c in plan['family_intents'])
    slot_lines = ['slot=%s dialogue_act=%s planned_intent=%s state.prev_intent=%s' % (
        s['slot'], s['act'], s['intent'] or 'null', s['prev'] or 'null(全新会话，state 全空)') for s in plan['slots']]
    return (
        '你在为食品工厂 ERP 的对话意图模型编写多轮训练样本（只需要“上一轮任务状态”和“用户当前这一句”）。\n'
        '本组业务领域及其意图：\n' + intents + '\n\n'
        '请严格按下面每个 slot 各写 1 条样本，slot/dialogue_act/planned_intent 原样回填：\n' + '\n'.join(slot_lines) + '\n\n'
        '要求：\n'
        '1. utterance 是工厂一线人员的口语，2~40 字，不要出现意图 ID、英文代码或“意图”字样。\n'
        '2. state 写上一轮：prev_intent 必须是给定值（全新会话则 null，且 confirmed_params、pending_fields 为空数组、last_action 为 null）；'
        'confirmed_params 写已确认的业务参数（中文 name/value，source 取 explicit_turn/confirmed/default）；pending_fields 是系统还在等的字段；'
        'last_action 一句话写系统上一步做了什么（翻页类要写成“展示了第一页/前 N 条”之类）。\n'
        '3. page_a / page_b：上一轮已列出结果，用户要翻页或看更多；两句说法要不同，尽量不用“下一页”原词（如“后面还有吗”“再往下看看”“上一批是哪些”）。\n'
        'cont_paraphrase：沿用上一轮任务，只改一个已确认参数或口径，换一种说法，不说任务名、不重复 confirmed_params 里的原词。\n'
        'write_to_read：上一轮是一个会改数据的操作（prev_intent），这一句转去做 planned_intent 那个只读查询，说清楚要查什么；prev_intent 为 null 时按全新会话写。\n'
        'boundary_clear 与 boundary_vague 是一对：两句话谈同一类业务对象。boundary_clear 说得足够清楚，只能对应 planned_intent；'
        'boundary_vague 少了决定性的那一点（查什么指标 / 看记录还是统计 / 哪个对象），在本领域至少两个意图都说得通，必须追问才能做。\n'
        '4. 不要写解释。样本之间不要互相复述。')


def gen_schema():
    s = json.loads(json.dumps(dp.GEN_SCHEMA))
    s['properties']['samples']['items']['properties']['slot']['enum'] = list(BSLOTS)
    return s


def build_rows(items, plan, table, seed):
    """Validate generated items against the plan and turn them into raw rows (same checks as F-pre)."""
    by_slot = {i['slot']: i for i in items}
    rng = random.Random('%s:cand:%d' % (seed, plan['call']))
    out, rejected = [], Counter()
    for n, s in enumerate(plan['slots']):
        item = by_slot.get(s['slot'])
        if item is None:
            rejected['missing_slot'] += 1
            continue
        err = valid_item(item, s, table)
        if err:
            rejected[err] += 1
            continue
        state = item['state'] if item['state']['prev_intent'] else None
        cands = list(plan['pool']) + [c for c in plan['distractors'] if c not in plan['pool']]
        for must in (s['intent'], s['prev']):
            if must and must not in cands:
                cands.append(must)
        rng.shuffle(cands)
        row = dict(id='v4-%04d-%d' % (plan['call'], n), utterance=item['utterance'].strip(), state=state, candidates=cands,
                   read_only=derive_read_only(s['intent'], state, table), family=plan['family'],
                   generator_plan=dict(dialogue_act=s['act'], intent=s['intent'], slot=s['slot'], model=plan.get('gen_model')),
                   source=SOURCE)
        try:
            validate_sample(row, table, 'raw')
        except SchemaError as e:
            rejected['schema:' + str(e)[:40]] += 1
            continue
        out.append(row)
    return out, rejected


def valid_item(item, plan_slot, table):
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
    if any(p['source'] not in PARAM_SOURCES for p in st['confirmed_params']):
        return 'bad_param_source'
    return None


# ---------------------------------------------------------------- claude generation
def claude_generate(plans, table, model, work, cap, concurrency=4):
    import anthropic
    from distill_claude_teacher import load_key
    cl = anthropic.Anthropic(api_key=load_key(), max_retries=2, timeout=300, default_headers={'Accept-Encoding': 'gzip'})
    pi, po = GEN_PRICES[model]
    path = Path(work) / 'gen-results.jsonl'
    done = {r['call']: r for r in read_jsonl(path) if r.get('ok')}
    spent = sum(r.get('cost', 0) for r in read_jsonl(path))
    sch = gen_schema()

    def one(p):
        nonlocal spent
        if p['call'] in done:
            return done[p['call']]
        if spent + (12000 * pi + 4000 * po) / 1e6 > cap:
            return dict(call=p['call'], ok=False, error='cap')
        rec = dict(call=p['call'], family=p['family'], model=model, ok=False)
        try:
            m = cl.messages.create(model=model, max_tokens=4000, system='你是严谨的数据编写员，只输出符合 schema 的 JSON。',
                                   messages=[dict(role='user', content=gen_prompt(p, table))],
                                   output_config=dict(effort='low', format=dict(type='json_schema', schema=sch)))
            u = m.usage
            rec.update(ok=m.stop_reason == 'end_turn', stop_reason=m.stop_reason, served_model=m.model,
                       input_tokens=u.input_tokens, output_tokens=u.output_tokens,
                       cost=(u.input_tokens * pi + u.output_tokens * po) / 1e6,
                       text=''.join(b.text for b in m.content if b.type == 'text'))
        except anthropic.APIStatusError as e:
            rec.update(status=e.status_code, error=str(e.message)[:200])
        except anthropic.APIConnectionError as e:
            rec.update(error='connection: ' + str(e)[:200])
        spent += rec.get('cost', 0)
        with open(path, 'a', encoding='utf-8', newline='') as f:
            f.write(json.dumps(rec, ensure_ascii=False) + '\n')
        return rec
    with ThreadPoolExecutor(concurrency) as ex:
        recs = list(ex.map(one, plans))
    return recs, spent


# ---------------------------------------------------------------- labelling
def label_rows(rows, table, work, cap_openai, cap_anthropic, luna_share=.25, pair=None, use_luna=True):
    """Luna (DollarClient, own ledger) + Terra and Opus (bake-off Runner, own receipts).
    The OpenAI cap is split: luna_share for Luna's ledger, the rest for Terra in the Runner."""
    import threading
    if not use_luna:
        luna_share = 0.0          # the whole OpenAI cap goes to the hard teacher
    work = Path(work)
    (work / 'hard').mkdir(parents=True, exist_ok=True)
    luna = dict(client=DollarClient(work / 'luna', max(cap_openai * luna_share, 0.01), concurrency=4))
    lock = threading.Lock()
    runner = tb.Runner(work / 'hard', dict(openai=cap_openai * (1 - luna_share), anthropic=cap_anthropic),
                       dict(openai=6, anthropic=4))

    def luna_call(row):
        # DollarClient refuses everything after one failed call; a failed receipt is never replayed, but
        # the other rows may go on with a fresh client over the same ledger (no reservation is pending
        # after an HTTP error). Spend cap and ledger are shared through the files.
        with lock:
            if luna['client'].stopped:
                luna['client'].close()
                luna['client'] = DollarClient(work / 'luna', cap_openai * luna_share, concurrency=4)
            c = luna['client']
        return c.call('luna:' + row['id'], 'decisions', dp.luna_payload(row, table))
    out_path = work / 'labeled-raw.jsonl'
    done = {r['id'] for r in read_jsonl(out_path)}
    stop = []

    def one(row):
        if row['id'] in done or stop:
            return None
        try:
            if not use_luna:
                raise _NoLuna()
            lu = dp.parse_luna(luna_call(row))
        except _NoLuna:
            lu = dict(model=None, skipped=True)
        except ApiStop as e:
            if 'cap' in str(e).lower():
                stop.append(str(e))
                return None
            lu = dict(model=dp.LUNA, error=str(e)[:200])
        except Exception as e:                                   # noqa: BLE001 - recorded, Luna is soft only
            lu = dict(model=dp.LUNA, error='%s: %s' % (type(e).__name__, str(e)[:150]))
        hard = {}
        for model, effort in (pair or (MAIN, SECOND)):
            try:
                rec = runner.call(row, table, model, effort)
            except tb.CapReached as e:
                stop.append(str(e))
                return None
            a, i, bad = tb.parse(rec, row)
            hard[model] = dict(model=model, effort=effort, dialogue_act=a, intent=i, issue=bad)
        return dict(row, luna=lu, hard=hard, pair=[list(x) for x in (pair or (MAIN, SECOND))],
                    rubric_version=(dp.RUBRIC or {}).get('version'))
    with ThreadPoolExecutor(8) as ex:
        for r in ex.map(one, rows):
            if r:
                with open(out_path, 'a', encoding='utf-8', newline='') as f:
                    f.write(json.dumps(r, ensure_ascii=False) + '\n')
    luna['client'].close()
    return dict(luna=luna['client'].summary(), hard_spent=dict(runner.spent), stopped=stop[:1])


class _NoLuna(Exception):
    pass


def pair_of(r):
    """(main, second) model names a labelled row was produced with (old rows carry no 'pair')."""
    p = r.get('pair')
    if not p:
        return (OLD_PAIR[0][0], OLD_PAIR[1][0])
    return (p[0][0], p[1][0]) if len(p) > 1 else (p[0][0], None)


def assemble(work, table):
    rows = read_jsonl(Path(work) / 'labeled-raw.jsonl')
    silver, queue, why = [], [], Counter()
    for r in rows:
        m1, m2 = pair_of(r)
        t, o = r['hard'][m1], r['hard'][m2]
        base = {k: r[k] for k in ('id', 'utterance', 'state', 'candidates', 'read_only', 'family', 'generator_plan', 'source')}
        luna = r['luna']
        probs = dp.luna_probs(dict(r, luna=luna)) if 'intent_probs' in luna else None
        teachers = dict(main=t, second=o, soft=dict(model=luna.get('model'), dialogue_act=luna.get('dialogue_act'),
                                                    intent=luna.get('intent'), error=luna.get('error')))
        if not t['issue'] and not o['issue'] and (t['dialogue_act'], t['intent']) == (o['dialogue_act'], o['intent']) and probs:
            row = dict(base, read_only=derive_read_only(t['intent'], r['state'], table), dialogue_act=t['dialogue_act'],
                       intent=t['intent'], teacher_probs=probs, teachers=teachers, teacher_set=teacher_set(r),
                       agreement=True)
            silver.append(row)
            why['agreed'] += 1
        else:
            reason = ('no_luna' if not probs else 'invalid_main' if t['issue'] else 'invalid_second' if o['issue']
                      else 'disagree_act' if t['dialogue_act'] != o['dialogue_act'] else 'disagree_intent')
            queue.append(dict(base, teacher_probs=probs, teachers=teachers, teacher_set=teacher_set(r),
                              agreement=False, review_reason=reason))
            why[reason] += 1
    write_jsonl(Path(work) / 'silver.jsonl', silver)
    write_jsonl(Path(work) / 'review-queue.jsonl', queue)
    return why


def build_dataset(sources, relabel_paths, exclude_ids, table):
    """Merge every labelled batch into one pool with BOTH label sets side by side.

    sources: [(name, silver_path, queue_path_or_None, default_teacher_set)] labelled under rubric v1.
    relabel_paths: labeled-raw files produced under rubric v3 (relabel of NEED_CLARIFY + control,
      new rows). A v3 row agrees when its two hard teachers agree.
    Rows whose hard teachers disagree keep no hard label: hard_weight=0, trained on Luna's soft
      label only. ``soft_stale`` marks soft labels produced under a different rubric than the label
      they now sit next to. ``exclude_ids`` (e.g. reviewed tuning items) never enter.
    """
    pool = {}
    for name, silver, queue, tset in sources:
        for r in read_jsonl(silver):
            pool[r['id']] = dict(r, batch=name, v1=dict(dialogue_act=r['dialogue_act'], intent=r['intent'], agreed=True,
                                                        teacher_set=r.get('teacher_set') or tset))
        for r in read_jsonl(queue) if queue else []:
            if r['id'] in pool or not r.get('teacher_probs'):
                continue
            pool[r['id']] = dict(r, batch=name, dialogue_act=None, intent=None,
                                 v1=dict(dialogue_act=None, intent=None, agreed=False, teacher_set=r.get('teacher_set') or tset,
                                         reason=r.get('review_reason')))
    merged = {}
    for path in relabel_paths:                        # one row may be labelled by several runs (main now, second later)
        for r in read_jsonl(path):
            m = merged.setdefault(r['id'], dict(r, hard={}, pair=[]))
            m['hard'].update(r['hard'])
            m['pair'] += [p for p in r.get('pair') or [] if p not in m['pair']]
            if (r.get('luna') or {}).get('intent_probs'):
                m['luna'] = r['luna']
    pending = 0
    for r in merged.values():
        order = {MAIN[0]: 0, SECOND[0]: 1}
        r['pair'] = sorted(r['pair'], key=lambda p: order.get(p[0], 9))
        if len(r['pair']) < 2:
            pending += 1                              # second teacher still to come: no v3 label yet
            continue
        if True:
            m1, m2 = pair_of(r)
            t, o = r['hard'][m1], r['hard'][m2]
            agreed = not t['issue'] and not o['issue'] and (t['dialogue_act'], t['intent']) == (o['dialogue_act'], o['intent'])
            v3 = dict(dialogue_act=t['dialogue_act'] if agreed else None, intent=t['intent'] if agreed else None, agreed=agreed,
                      teacher_set=teacher_set(r), main=dict(dialogue_act=t['dialogue_act'], intent=t['intent'], issue=t['issue']),
                      second=dict(dialogue_act=o['dialogue_act'], intent=o['intent'], issue=o['issue']))
            base = pool.get(r['id'])
            if base is None:                                  # new row: v3 only, soft label from this run's Luna
                lu = r.get('luna') or {}
                probs = dp.luna_probs(dict(r, luna=lu)) if 'intent_probs' in lu else None
                base = {k: r[k] for k in ('id', 'utterance', 'state', 'candidates', 'read_only', 'family', 'generator_plan', 'source')}
                base.update(batch='v4-new', teacher_probs=probs, v1=None)
            pool[r['id']] = dict(base, v3=v3)
    build_dataset.pending_second_teacher = pending
    out = []
    for r in pool.values():
        if r['id'] in exclude_ids:
            continue
        v1, v3 = r.get('v1'), r.get('v3')
        r['soft_stale'] = bool(v3 and v1 and r.get('teacher_probs') is not None and (v3['dialogue_act'], v3['intent']) != (v1['dialogue_act'], v1['intent']))
        if not r.get('teacher_probs') and not ((v1 or {}).get('agreed') or (v3 or {}).get('agreed')):
            continue                                          # neither a hard nor a soft label: nothing to learn
        out.append(r)
    return out


def label_view(r, version):
    """Flatten one label set onto the row for training: hard label + hard_weight (0 = soft only).
    For version 3, rows never relabelled under v3 fall back to their v1 label (v3 only changed the
    NEED_CLARIFY ones and the control sample showed the rest; see the report)."""
    lab = r.get('v3') if version == 3 and r.get('v3') else r.get('v1')
    if lab is None:
        return None
    return dict(r, dialogue_act=lab['dialogue_act'], intent=lab['intent'], hard_weight=1.0 if lab['agreed'] else 0.0,
                label_version='v3' if lab is r.get('v3') else 'v1', read_only=derive_read_only(lab['intent'], r['state'], TABLE_REF[0])
                if lab['agreed'] else r.get('read_only'))


TABLE_REF = [None]


def dataset(work, table):
    """Build the student-v2 pool from the fixed batch layout under Documents/Cretas意图蒸馏-20261111."""
    import ctx_student as cs
    d = DOCS
    exclude = set(json.loads((d / 'teacher-bakeoff' / 'steve-answers.json').read_text(encoding='utf-8')))
    sources = [('v2', d / 'v2/silver.jsonl', d / 'v2/review-queue.jsonl', 'gpt-6-luna+gpt-6.1-terra@low|rubric-v1'),
               ('v2-adjudicated', d / 'v2/silver-adjudicated.jsonl', None, 'gpt-6-luna+gpt-6.1-terra@low+claude-fable-5-2@high(2of3)|rubric-v1'),
               ('v3-topup', d / 'v3-topup/silver.jsonl', d / 'v3-topup/review-queue.jsonl', 'gpt-6-luna+gpt-6.1-terra@low|rubric-v1'),
               ('v4-topup-rest', d / 'v4/topup-rest/silver.jsonl', d / 'v4/topup-rest/review-queue.jsonl', None)]
    relabel = [p for p in (d / 'v4/relabel-v3/labeled-raw.jsonl', d / 'v4/new-label-main/labeled-raw.jsonl',
                           d / 'v4/new-label-second/labeled-raw.jsonl') if p.exists()]
    pool = build_dataset(sources, relabel, exclude, table)
    for r in pool:                                   # split key: the label we would train on (v3 view, else v1)
        v = label_view(r, 3)
        r['_key_act'] = (v or {}).get('dialogue_act') or (r.get('generator_plan') or {}).get('dialogue_act')
        r['_key_intent'] = (v or {}).get('intent')
    keyed = [dict(id=r['id'], utterance=r['utterance'], state=r['state'], dialogue_act=r['_key_act'], intent=r['_key_intent'])
             for r in pool]
    known = cs.known_intent_split(keyed)
    fam = dp.split_families(pool)
    for r in pool:
        r['split_known'] = known[r['id']]
        r['split_family'] = fam[r['family']]
        r.pop('_key_act'), r.pop('_key_intent')
    write_jsonl(Path(work) / 'pool-v4.jsonl', pool)
    rep = dataset_report(pool)
    (Path(work) / 'dataset-report.json').write_text(json.dumps(rep, ensure_ascii=False, indent=2), encoding='utf-8')
    print('DATASET', json.dumps(rep, ensure_ascii=False)[:3000])


def dataset_report(pool):
    def share(rows, act):
        rows = [r for r in rows if r and r['hard_weight']]
        return dict(n=len(rows), clarify=sum(r['dialogue_act'] == act for r in rows),
                    rate=(sum(r['dialogue_act'] == act for r in rows) / len(rows)) if rows else None)
    v1 = [label_view(r, 1) for r in pool if r.get('v1')]
    v3 = [label_view(r, 3) for r in pool]
    rel = [r for r in pool if r.get('v3') and r.get('v1')]
    changed = Counter((r['v1']['dialogue_act'], r['v3']['dialogue_act'] if r['v3']['agreed'] else 'NO_AGREEMENT') for r in rel)
    rep = dict(rows=len(pool), by_batch=dict(Counter(r['batch'] for r in pool)),
               v1_clarify_share=share(v1, 'NEED_CLARIFY'), v3_view_clarify_share=share([x for x in v3 if x], 'NEED_CLARIFY'),
               relabelled=len(rel), relabel_transitions={'%s->%s' % k: v for k, v in sorted(changed.items())},
               v3_teacher_agreement=dict(n=sum(1 for r in pool if r.get('v3')), agreed=sum(1 for r in pool if r.get('v3') and r['v3']['agreed'])),
               soft_only_v1=sum(1 for x in v1 if x and not x['hard_weight']), soft_only_v3_view=sum(1 for x in v3 if x and not x['hard_weight']),
               soft_stale=sum(r['soft_stale'] for r in pool),
               splits_known={s: dict(Counter(r['split_known'] for r in pool))[s] for s in ('train', 'validation', 'calibration')},
               splits_family=dict(Counter(r['split_family'] for r in pool)),
               acts_v3_view=dict(Counter(x['dialogue_act'] for x in v3 if x and x['hard_weight'])),
               read_v3_view=dict(Counter({True: 'read', False: 'write', None: 'none'}[x['read_only']] for x in v3 if x and x['hard_weight'])))
    return rep


DOCS = Path.home() / 'Documents' / 'Cretas意图蒸馏-20261111'


def teacher_set(r):
    p = r.get('pair') or [list(x) for x in OLD_PAIR]
    return '+'.join('%s@%s' % (m, e) for m, e in p) + '+luna|rubric-v%s' % (r.get('rubric_version') or 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('stage', choices=['generate', 'label', 'assemble', 'dataset'])
    ap.add_argument('--work', type=Path, required=True)
    ap.add_argument('--calls', type=int, default=6)
    ap.add_argument('--start', type=int, default=0)
    ap.add_argument('--seed', default='distill-v4-20261112')
    ap.add_argument('--gen-model', default='claude-haiku-5-5', choices=sorted(GEN_PRICES))
    ap.add_argument('--gen-cap', type=float, default=5.0)
    ap.add_argument('--input', type=Path)
    ap.add_argument('--limit', type=int, default=100000)
    ap.add_argument('--cap-openai', type=float, default=10.0)
    ap.add_argument('--cap-anthropic', type=float, default=55.0)
    ap.add_argument('--rubric', default=str(Path(__file__).parent / 'clarify_rubric_v3.json'))
    ap.add_argument('--teachers', choices=['both', 'main', 'second'], default='both',
                    help='label: run only one hard teacher (the other is added by a later run; rows merge by id)')
    ap.add_argument('--no-luna', action='store_true', help='label: hard teachers only (relabel keeps the earlier soft label)')
    a = ap.parse_args()
    table = build_intent_table()
    tb.TABLE = table
    TABLE_REF[0] = table
    dp.RUBRIC = dp.load_rubric(a.rubric)
    a.work.mkdir(parents=True, exist_ok=True)
    if a.stage == 'generate':
        plans = [dict(plan_boundary(k, table, a.seed), gen_model=a.gen_model) for k in range(a.start, a.start + a.calls)]
        recs, spent = claude_generate(plans, table, a.gen_model, a.work, a.gen_cap)
        rows, rej = [], Counter()
        for p, rec in zip(plans, recs):
            if not rec.get('ok'):
                rej['call:' + str(rec.get('error') or rec.get('stop_reason'))[:30]] += 1
                continue
            try:
                items = json.loads(rec['text'])['samples']
            except (ValueError, KeyError):
                rej['bad_json'] += 1
                continue
            got, bad = build_rows(items, p, table, a.seed)
            rows += got
            rej.update(bad)
        seen, uniq = set(), []
        from flywheel_reviewed import normalized
        for r in rows:
            k = (normalized(r['utterance']), (r['state'] or {}).get('prev_intent'), r['family'])
            if k in seen:
                rej['duplicate_utterance'] += 1
                continue
            seen.add(k)
            uniq.append(r)
        write_jsonl(a.work / 'raw.jsonl', uniq)
        rep = dict(calls=len(plans), samples=len(uniq), rejected=dict(rej), gen_model=a.gen_model, spent_usd=spent,
                   by_slot=dict(Counter(r['generator_plan']['slot'] for r in uniq)))
        (a.work / 'generate-report.json').write_text(json.dumps(rep, ensure_ascii=False, indent=2), encoding='utf-8')
        print('GENERATED', json.dumps(rep, ensure_ascii=False))
    elif a.stage == 'label':
        rows = read_jsonl(a.input)[:a.limit]
        pair = {'both': (MAIN, SECOND), 'main': (MAIN,), 'second': (SECOND,)}[a.teachers]
        res = label_rows(rows, table, a.work, a.cap_openai, a.cap_anthropic, use_luna=not a.no_luna, pair=pair)
        print('LABELED', json.dumps(res, ensure_ascii=False))
    elif a.stage == 'assemble':
        print('ASSEMBLED', dict(assemble(a.work, table)))
    else:
        dataset(a.work, table)


if __name__ == '__main__':
    main()
