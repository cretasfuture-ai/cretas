"""Aggregate-only scoring of ``holdout_eval.py`` runs (HOLDOUT.md rule 2: no item content out).

Per item the expected outcome is derived from the sealed gold:
  write (read_only false or must_not_direct_execute)  -> anything but EXECUTE (direct execution fails)
  should_clarify                                      -> CLARIFY
  dialogue_act CANCEL                                 -> NONE
  out-of-domain / unsupported (flags or that intent)  -> NONE, or EXECUTE of that intent
  otherwise (read)                                    -> EXECUTE of the gold intent (null intent: any EXECUTE)
Dimensions (ambiguous items reported separately, never mixed in):
  goal, coarse dialogue action, false/missed clarify, cancel, params (A only: page/size/status from
  main's Java binders vs gold; time_range/material/orderId are not bound by the probe and are counted
  as unmeasured), real result (not measured). Plus direct-execution coverage and accuracy on reads,
  write direct-execution counts (with and without the write gate), calls per turn and p50/p95.
"""
import argparse
import collections
import json
import math
import pathlib
import subprocess

EQUIV = {'ORDER_LIST': {'ORDER_STATUS'}}
REFUSE = {'OUT_OF_DOMAIN', 'UNSUPPORTED_CAPABILITY'}
PAGINATION = {'PAGINATION_NEXT', 'PAGINATION_PREVIOUS'}


def pct(v, q):
    v = sorted(v)
    if not v:
        return None
    k = (len(v) - 1) * q
    f, c = math.floor(k), math.ceil(k)
    return v[f] if f == c else v[f] + (v[c] - v[f]) * (k - f)


def gold_items(sealed):
    A = json.loads((sealed / 'A_multiturn.json').read_text('utf-8'))['dialogues']
    B = [json.loads(x) for x in (sealed / 'B_decision.jsonl').read_text('utf-8').splitlines() if x.strip()]
    out = {}
    for d in A:
        for t in d['turns']:
            out[('A', d['id'], t['turn'])] = dict(t, family=d.get('family'), domain=d.get('domain'))
    for r in B:
        out[('B', r['id'], 1)] = dict(r, input=r['utterance'])
    return out


def expected(g):
    if g.get('read_only') is False or g.get('must_not_direct_execute'):
        return 'WRITE'
    if g.get('should_clarify'):
        return 'CLARIFY'
    if g.get('dialogue_act') == 'CANCEL':
        return 'NONE'
    if g.get('expect_out_of_domain') or g.get('expect_unsupported_refusal') or g.get('intent') in REFUSE:
        return 'REFUSE'
    return 'EXECUTE'


def coarse(act):
    return {'CHANGE_PARAM': 'CONTINUE', 'PAGE': 'CONTINUE'}.get(act, act)


def bind_params(rows, classpath):
    feed, keys = [], []
    for r in sorted((r for r in rows if r['set'] == 'A'), key=lambda r: (r['id'], r['turn'])):
        feed.append(json.dumps(dict(case=r['id'], intent=r.get('intent'), sentence=r['_input'], act=r.get('act'),
                                    outcome=r['outcome']), ensure_ascii=False))
        keys.append((r['id'], r['turn']))
    proc = subprocess.run(['java', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', classpath,
                           'com.cretas.aims.service.execution.ReadParamProbe'], input='\n'.join(feed) + '\n',
                          capture_output=True, text=True, encoding='utf-8')
    if proc.returncode != 0:
        raise SystemExit('probe failed (rc %d)' % proc.returncode)
    outs = [json.loads(x) for x in proc.stdout.splitlines() if x.strip().startswith('{')]
    if len(outs) != len(keys):
        raise SystemExit('probe row count mismatch')
    return dict(zip(keys, outs))


def score(rows, gold, classpath):
    for r in rows:
        r['_input'] = gold[(r['set'], r['id'], r['turn'])]['input']
    bound = bind_params(rows, classpath)
    prev_exec = {}
    items = []
    for r in sorted(rows, key=lambda r: (r['set'], r['id'], r['turn'])):
        g = gold[(r['set'], r['id'], r['turn'])]
        exp = expected(g)
        o = r['outcome']
        sel = r.get('intent') if o == 'EXECUTE' else (r['top'][0] if r.get('top') else None)
        allowed = {g['intent']} | EQUIV.get(g['intent'] or '', set()) if g.get('intent') else None
        if exp == 'WRITE':
            ok_kind = o != 'EXECUTE'
        elif exp == 'REFUSE':
            ok_kind = o == 'NONE' or (o == 'EXECUTE' and r.get('intent') in REFUSE)
        elif exp == 'EXECUTE':
            ok_kind = o == 'EXECUTE'
        else:
            ok_kind = o == exp
        goal = None
        if exp == 'EXECUTE' and allowed:
            ok = sel in allowed
            if not ok and sel in PAGINATION and g.get('dialogue_act') == 'PAGE':
                ok = prev_exec.get(r['id']) in allowed
            goal = ok and o in ('EXECUTE', 'CLARIFY')
        params = None
        if r['set'] == 'A' and exp == 'EXECUTE' and o == 'EXECUTE' and isinstance(g.get('parameters'), dict):
            want = {k: v for k, v in g['parameters'].items() if k in ('page', 'size', 'status')}
            got = (bound.get((r['id'], r['turn'])) or {}).get('params') or {}
            params = all(got.get(k) == v for k, v in want.items())
        unmeasured_params = sorted(k for k, v in (g.get('parameters') or {}).items()
                                   if k in ('time_range', 'material', 'orderId', 'basis') and v is not None) \
            if r['set'] == 'A' and isinstance(g.get('parameters'), dict) else []
        if o == 'CLARIFY':
            obs = 'NEED_CLARIFY'
        elif o == 'NONE':
            obs = 'CANCEL'
        elif o in ('EXECUTE', 'CONFIRM_WRITE'):
            base = prev_exec.get(r['id']) if r['set'] == 'A' else r.get('prev_intent_used')
            it = r.get('intent') or (r['top'][0] if r.get('top') else None)
            obs = 'CONTINUE' if (it in PAGINATION or (base and it == base)) else 'NEW_TASK'
        else:
            obs = None
        if r['set'] == 'A':
            prev_exec[r['id']] = r['intent'] if o == 'EXECUTE' else (prev_exec.get(r['id']) if o == 'CLARIFY' else None)
        passed = ok_kind and goal is not False and params is not False
        items.append(dict(
            set=r['set'], ambiguous=bool(g.get('ambiguous')), expected=exp, outcome=o, ok_kind=ok_kind, goal=goal,
            params=params, unmeasured_params=unmeasured_params, passed=passed,
            action=None if obs is None or not g.get('dialogue_act') else obs == coarse(g['dialogue_act']),
            model_act=None if not r.get('act') or not g.get('dialogue_act') else r['act'] == g['dialogue_act'],
            false_clarify=(o == 'CLARIFY') if exp != 'CLARIFY' else None,
            missed_clarify=(o != 'CLARIFY') if exp == 'CLARIFY' else None,
            cancel=(o == 'NONE') if exp == 'NONE' else None,
            write_direct=(o == 'EXECUTE') if exp == 'WRITE' else None,
            write_direct_without_gate=(r.get('outcome_raw') == 'EXECUTE') if exp == 'WRITE' else None,
            read_exec=(o == 'EXECUTE') if (exp == 'EXECUTE' and g.get('read_only') is True) else None,
            read_exec_correct=(o == 'EXECUTE' and goal) if (exp == 'EXECUTE' and g.get('read_only') is True and allowed) else None,
            dialogue_act=g.get('dialogue_act'), read_only=g.get('read_only'), family=g.get('family'),
            difficulty=g.get('difficulty'), n_calls=r['n_calls'], wall_ms=r['wall_ms'],
            in_tokens=sum(c['in_tokens'] for c in r['calls']), error=bool(r.get('error')),
            error_kind=(r.get('error') or '').split(':')[0] or None))
    return items


def rate(items, key):
    v = [i[key] for i in items if i[key] is not None]
    return dict(ok=sum(1 for x in v if x), n=len(v))


def summarize(items):
    out = {}
    for s in ('A', 'B'):
        for amb in (False, True):
            sub = [i for i in items if i['set'] == s and i['ambiguous'] == amb]
            if not sub:
                continue
            key = s + ('-ambiguous' if amb else '')
            reads = [i for i in sub if i['read_exec'] is not None]
            out[key] = dict(
                items=len(sub), passed=rate(sub, 'passed'), goal=rate(sub, 'goal'), action=rate(sub, 'action'),
                model_act=rate(sub, 'model_act'), false_clarify=rate(sub, 'false_clarify'),
                missed_clarify=rate(sub, 'missed_clarify'), cancel=rate(sub, 'cancel'), params=rate(sub, 'params'),
                params_unmeasured_keys=dict(collections.Counter(k for i in sub for k in i['unmeasured_params'])),
                real_result='not measured',
                write_direct_exec=rate(sub, 'write_direct'),
                write_direct_exec_without_gate=rate(sub, 'write_direct_without_gate'),
                read_direct_coverage=rate(reads, 'read_exec'), read_direct_accuracy_in_coverage=dict(
                    ok=sum(1 for i in reads if i['read_exec'] and i['read_exec_correct']),
                    n=sum(1 for i in reads if i['read_exec'] and i['read_exec_correct'] is not None)),
                by_expected={k: rate([i for i in sub if i['expected'] == k], 'passed') for k in sorted({i['expected'] for i in sub})},
                by_dialogue_act={k: rate([i for i in sub if i['dialogue_act'] == k], 'passed')
                                 for k in sorted({str(i['dialogue_act']) for i in sub}) if k != 'None'},
                by_read_only={str(k): rate([i for i in sub if i['read_only'] == k], 'passed') for k in (True, False)},
                by_difficulty={k: rate([i for i in sub if i['difficulty'] == k], 'passed')
                               for k in sorted({str(i['difficulty']) for i in sub}) if k != 'None'},
                by_family={k: rate([i for i in sub if i['family'] == k], 'passed')
                           for k in sorted({str(i['family']) for i in sub}) if k != 'None'},
                calls_per_turn=round(sum(i['n_calls'] for i in sub) / len(sub), 3),
                calls_max=max(i['n_calls'] for i in sub),
                p50_ms=round(pct([i['wall_ms'] for i in sub], .5)), p95_ms=round(pct([i['wall_ms'] for i in sub], .95)),
                decision_input_tokens_p50=pct([i['in_tokens'] for i in sub if i['n_calls']], .5),
                errors=dict(collections.Counter(i['error_kind'] for i in sub if i['error'])))
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--sealed', required=True, type=pathlib.Path)
    p.add_argument('--run', required=True, type=pathlib.Path)
    p.add_argument('--classpath', required=True)
    p.add_argument('--output', required=True, type=pathlib.Path)
    a = p.parse_args()
    rows = [json.loads(x) for x in a.run.read_text('utf-8').splitlines() if x.strip()]
    summary = summarize(score(rows, gold_items(a.sealed), a.classpath))
    a.output.write_text(json.dumps(summary, ensure_ascii=False, indent=1), 'utf-8')
    print('scored %d items -> %s' % (len(rows), a.output))


if __name__ == '__main__':
    main()
