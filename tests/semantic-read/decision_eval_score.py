"""Score ``decision_layer_eval.py`` runs: calls, latency and per-dimension accuracy (line E).

Pure functions over ``turns.jsonl``; no model, network or database.

Per turn:
  expected  CLARIFY for gold NEED_CLARIFY, NONE for gold CANCEL, else EXECUTE (the gold intent).
  goal      only when EXECUTE is expected: the selected intent (executed, or the top button on a
            clarification, same convention as ``scoring_dimensions``) is the gold intent or an
            equivalent. Server-side pagination intents count for a gold page turn when the
            previous executed intent was right (PAGINATION_* re-run the previous read).
  clarify   two directions kept apart: false_clarify (asked, should not) and missed_clarify
            (should ask, did not).
  action    coarse dialogue action derived from outcomes the same way for every version:
            NEW_TASK / CONTINUE (change parameter or page) / CANCEL / NEED_CLARIFY.
  model_act fine-grained act declared by the merged understanding (merged runs only).
  params    part M, executed turns: page/size/status bound by main's Java binders
            (``apply_param_probe.py`` -> ``params_ok``) equal the gold. None when not executed.
  model_params  status/size/page read by the merged understanding vs gold (part M only).
            On main these three are bound by Java (ReadTurnParameters / StatusFilter) from the
            utterance; model values are candidates only, so this is a reading metric, not the
            executed parameters. Executed parameters and the real result are NOT measured here.
"""
from __future__ import annotations

import argparse
import collections
import json
import math
import pathlib
import re
import statistics

EQUIVALENT = {'ORDER_LIST': {'ORDER_STATUS'}}
PAGINATION = {'PAGINATION_NEXT', 'PAGINATION_PREVIOUS'}
CN = {'零': 0, '一': 1, '二': 2, '两': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8, '九': 9, '十': 10}


def allowed(gold):
    return {gold} | EQUIVALENT.get(gold, set())


def m_gold_act(turns, i):
    """Fine act of a part-M gold turn (first / intent change = NEW_TASK, page+1 only = PAGE)."""
    t = turns[i]
    if i == 0 or (turns[i - 1]['gold_intent'], turns[i - 1].get('gold_basis')) != (t['gold_intent'], t.get('gold_basis')):
        return 'NEW_TASK'
    p, q = turns[i - 1]['gold_params'], t['gold_params']
    if q.get('page') == p.get('page', 0) + 1 and q.get('status') == p.get('status') and q.get('size') == p.get('size'):
        return 'PAGE'
    return 'CHANGE_PARAM'


def coarse(act):
    return {'CHANGE_PARAM': 'CONTINUE', 'PAGE': 'CONTINUE'}.get(act, act)


def expected(act):
    return {'NEED_CLARIFY': 'CLARIFY', 'CANCEL': 'NONE'}.get(act, 'EXECUTE')


def number(text):
    text = str(text or '')
    m = re.search(r'\d+', text)
    if m:
        return int(m.group())
    m = re.search('[零一二两三四五六七八九十]+', text)
    if not m:
        return None
    s = m.group()
    if s.startswith('十'):
        return 10 + (CN.get(s[1:2], 0) if len(s) > 1 else 0)
    if '十' in s:
        a, _, b = s.partition('十')
        return CN.get(a, 0) * 10 + (CN.get(b, 0) if b else 0)
    return CN.get(s[0])


def read_params(params, vocab):
    out = {}
    for p in params or []:
        name, value = str(p.get('name', '')), str(p.get('value', ''))
        low = name.lower()
        if '每页' in name or '条数' in name or low in ('size', 'pagesize'):
            out.setdefault('size', number(value))
        elif '页' in name or low in ('page', 'pagenum', 'pageno'):
            out.setdefault('page', number(value))
        elif '状态' in name or 'status' in low or '口径' in name:
            code = None
            for c, words in (vocab or {}).items():
                if value.strip().upper() == c or any(w and w in value for w in words):
                    code = c
                    break
            if code:
                out.setdefault('status', code)
    return out


def score_rows(rows, vocab):
    """rows of one run -> per-turn dicts."""
    by_case = collections.defaultdict(list)
    for r in rows:
        by_case[(r['part'], r['case_id'])].append(r)
    out = []
    for (part, _), turns in by_case.items():
        turns.sort(key=lambda r: r['turn'])
        prev_exec = None
        for i, r in enumerate(turns):
            gold_act = m_gold_act(turns, i) if part == 'M' else r['gold_act']
            exp = expected(gold_act)
            kind, intent = r['outcome'], r['intent']
            selected = intent if kind == 'EXECUTE' else (r['top'][0] if kind == 'CLARIFY' and r['top'] else None)
            goal = None
            if exp == 'EXECUTE':
                ok = selected in allowed(r['gold_intent'])
                if not ok and selected in PAGINATION and gold_act == 'PAGE' and prev_exec in allowed(r['gold_intent']):
                    ok = True
                goal = ok and kind in ('EXECUTE', 'CLARIFY')
            # observed coarse act, derived from outcomes only
            if kind == 'CLARIFY':
                obs = 'NEED_CLARIFY'
            elif kind in ('NONE',):
                obs = 'CANCEL'
            elif kind == 'EXECUTE':
                base = prev_exec if part == 'M' else r.get('prev_intent')
                obs = 'CONTINUE' if (intent in PAGINATION or (base is not None and intent == base)) else 'NEW_TASK'
            else:
                obs = None
            u = r.get('understand')
            mp = None
            if part == 'M' and u is not None:
                got = read_params(u.get('parameters'), (vocab or {}).get(r['gold_intent']))
                want = {k: v for k, v in r['gold_params'].items() if k in ('page', 'size', 'status')}
                mp = {k: got.get(k) == v for k, v in want.items()}
            out.append(dict(run=r['run'], part=part, case_id=r['case_id'], turn=r['turn'], gold_act=gold_act,
                            expected=exp, outcome=kind, selected=selected, gold_intent=r['gold_intent'],
                            goal=goal, exec_ok=(kind == 'EXECUTE' and goal) if exp == 'EXECUTE' else None,
                            false_clarify=(kind == 'CLARIFY') if exp != 'CLARIFY' else None,
                            missed_clarify=(kind != 'CLARIFY') if exp == 'CLARIFY' else None,
                            action=None if obs is None else obs == coarse(gold_act),
                            cancel_ok=(kind == 'NONE') if exp == 'NONE' else None,
                            model_act=None if u is None else u.get('act') == gold_act,
                            model_params=mp, params=r.get('params_ok') if part == 'M' else None,
                            n_calls=r['n_calls'], labels=[c['label'] for c in r['calls']],
                            wall_ms=r['wall_ms'], tokens_in=sum(c['in_tokens'] for c in r['calls']),
                            tokens_cached=sum(c['cached_tokens'] for c in r['calls']),
                            tokens_out=sum(c['out_tokens'] for c in r['calls']), error=r.get('error'),
                            fallback=kind in ('FALLBACK', 'ERROR')))
            if kind == 'EXECUTE':
                prev_exec = intent if intent not in PAGINATION else prev_exec
            elif part == 'M':
                prev_exec = prev_exec if kind == 'CLARIFY' else None
    return out


def pct(values, q):
    v = sorted(values)
    if not v:
        return None
    k = (len(v) - 1) * q
    f, c = math.floor(k), math.ceil(k)
    return v[f] if f == c else v[f] + (v[c] - v[f]) * (k - f)


def rate(rows, key):
    vals = [r[key] for r in rows if r[key] is not None]
    return dict(ok=sum(1 for v in vals if v), n=len(vals), pct=round(100 * sum(1 for v in vals if v) / len(vals), 1) if vals else None)


def summarize(scored):
    out = {}
    for part in ('ALL', 'M', 'C'):
        rows = [r for r in scored if part == 'ALL' or r['part'] == part]
        if not rows:
            continue
        mp = [v for r in rows if r['model_params'] for v in r['model_params'].values()]
        mp_by = collections.defaultdict(list)
        for r in rows:
            for k, v in (r['model_params'] or {}).items():
                mp_by[k].append(v)
        label_counts = collections.Counter(lbl for r in rows for lbl in r['labels'])
        out[part] = dict(
            turns=len(rows),
            calls_per_turn=round(sum(r['n_calls'] for r in rows) / len(rows), 3),
            calls_max=max(r['n_calls'] for r in rows),
            turns_over_2_calls=sum(1 for r in rows if r['n_calls'] > 2),
            calls_by_label={k: round(v / len(rows), 3) for k, v in sorted(label_counts.items())},
            p50_ms=round(pct([r['wall_ms'] for r in rows], 0.5)), p95_ms=round(pct([r['wall_ms'] for r in rows], 0.95)),
            tokens_in_per_turn=round(sum(r['tokens_in'] for r in rows) / len(rows)),
            tokens_cached_per_turn=round(sum(r['tokens_cached'] for r in rows) / len(rows)),
            tokens_out_per_turn=round(sum(r['tokens_out'] for r in rows) / len(rows)),
            goal=rate(rows, 'goal'), exec_ok=rate(rows, 'exec_ok'), params=rate(rows, 'params'), action=rate(rows, 'action'),
            false_clarify=rate(rows, 'false_clarify'), missed_clarify=rate(rows, 'missed_clarify'),
            cancel_ok=rate(rows, 'cancel_ok'), model_act=rate(rows, 'model_act'),
            model_params=dict(ok=sum(mp), n=len(mp), by_key={k: f'{sum(v)}/{len(v)}' for k, v in mp_by.items()}),
            fallback_or_error=sum(1 for r in rows if r['fallback']),
            outcomes=dict(collections.Counter(r['outcome'] for r in rows)),
        )
    return out


def load(path):
    return [json.loads(x) for x in pathlib.Path(path).read_text('utf-8').splitlines() if x.strip()]


def by_run(rows):
    runs = collections.defaultdict(list)
    for r in rows:
        runs[r['run']].append(r)
    return dict(sorted(runs.items()))


def spread(values):
    vals = [v for v in values if v is not None]
    if not vals:
        return None
    return dict(mean=round(statistics.mean(vals), 2), min=min(vals), max=max(vals))


def compare(per_run):
    """{version: {run: summary}} -> {version: {part: {metric: mean/min/max}}}."""
    metrics = ('calls_per_turn', 'calls_max', 'turns_over_2_calls', 'p50_ms', 'p95_ms', 'tokens_in_per_turn',
               'tokens_out_per_turn', 'fallback_or_error')
    rates = ('goal', 'exec_ok', 'params', 'action', 'false_clarify', 'missed_clarify', 'cancel_ok', 'model_act')
    out = {}
    for version, runs in per_run.items():
        out[version] = {}
        for part in ('ALL', 'M', 'C'):
            sums = [s[part] for s in runs.values() if part in s]
            if not sums:
                continue
            d = {m: spread([s[m] for s in sums]) for m in metrics}
            d.update({r: spread([s[r]['pct'] for s in sums]) for r in rates})
            d['n'] = {r: sums[0][r]['n'] for r in rates}
            d['model_params'] = spread([round(100 * s['model_params']['ok'] / s['model_params']['n'], 1)
                                        if s['model_params']['n'] else None for s in sums])
            out[version][part] = d
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--run', action='append', required=True, help='version=path/to/turns.jsonl')
    p.add_argument('--vocab', type=pathlib.Path)
    p.add_argument('--output', type=pathlib.Path)
    a = p.parse_args()
    vocab = json.loads(a.vocab.read_text('utf-8')) if a.vocab else {}
    per_run, turns = {}, {}
    for spec in a.run:
        version, path = spec.split('=', 1)
        per_run[version] = {}
        for run, rows in by_run(load(path)).items():
            scored = score_rows(rows, vocab)
            turns.setdefault(version, []).extend(scored)
            per_run[version][run] = summarize(scored)
    result = dict(per_run=per_run, compare=compare(per_run))
    text = json.dumps(result, ensure_ascii=False, indent=1)
    if a.output:
        a.output.write_text(text, 'utf-8')
        a.output.with_suffix('.turns.json').write_text(json.dumps(turns, ensure_ascii=False), 'utf-8')
    print(json.dumps(result['compare'], ensure_ascii=False, indent=1))


if __name__ == '__main__':
    main()
