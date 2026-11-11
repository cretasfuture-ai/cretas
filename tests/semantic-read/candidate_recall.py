"""Candidate recall and out-of-candidate paths for no-generative runs (line E). Aggregates only.

Recall@k: is the gold intent among the first k candidates handed to the decision model? ``actual``
is the list the run really gave Choice (recorded per turn, capped at the run's max options);
k = 5 / 10 / 20 are recomputed from the same prod retrieval snapshot and the same previous-task rule
(``nogen_pipeline.candidates``), so no model is called. Only turns whose expected outcome is
executing a read with a known gold intent count.

Failure split on those turns: ``not_in_candidates`` (no decision can recover it) vs
``in_candidates_wrong`` (the decision layer chose another intent, asked, or declined).

Out-of-candidate paths in this configuration (``nogen_pipeline.decide`` reasons):
  choice_none     the "以上都不是" option won (p >= 0.5 and above every candidate) -> NONE, no execution
  low_choice      "需要先问清楚" option above the top candidate, or top below the execute threshold -> CLARIFY
  clarify_signal  Predicate / act clarify probability over threshold -> CLARIFY
  re-retrieval    does not exist in this configuration (counted as 0 by construction)
Each is reported with its trigger count and what the gold expected on those turns.
"""
import argparse
import collections
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / 'backend' / 'python'))
sys.path.insert(0, str(HERE))

from nogen_pipeline import candidates  # noqa: E402

EQUIV = {'ORDER_LIST': {'ORDER_STATUS'}}


def holdout_gold(sealed):
    from holdout_score import expected, gold_items
    g = gold_items(sealed)
    return {k: dict(expected=expected(v), intent=v.get('intent'), ambiguous=bool(v.get('ambiguous'))) for k, v in g.items()}


def dev_expected(r):
    return {'NEED_CLARIFY': 'CLARIFY', 'CANCEL': 'NONE'}.get(r.get('gold_act'), 'EXECUTE')


def analyse(rows, gold_of, snap, visible):
    rec = collections.Counter()
    fail = collections.Counter()
    paths = collections.defaultdict(collections.Counter)
    sizes = []
    for r in rows:
        g = gold_of(r)
        if g is None or g.get('ambiguous'):
            continue
        reason = r.get('reason') or ('error' if r.get('error') else 'unknown')
        paths[reason][g['expected']] += 1
        gi = g.get('intent')
        if g['expected'] != 'EXECUTE' or not gi:
            continue
        allowed = {gi} | EQUIV.get(gi, set())
        cand = r.get('candidates') or []
        sizes.append(len(cand))
        rec['n'] += 1
        rec['actual'] += bool(allowed & set(cand))
        q = r.get('query') or r.get('_input')
        for k in (5, 10, 20):
            rec['k%d' % k] += bool(allowed & set(candidates(q, r.get('prev_intent_used'), snap, visible, k)))
        executed_right = r.get('outcome') == 'EXECUTE' and r.get('intent') in allowed
        if not executed_right:
            fail['not_in_candidates' if not (allowed & set(cand)) else 'in_candidates_wrong'] += 1
    n = rec['n'] or 1
    return dict(
        recall={k: dict(ok=rec[k], n=rec['n'], pct=round(100 * rec[k] / n, 1)) for k in ('actual', 'k5', 'k10', 'k20')},
        actual_k=dict(mean=round(sum(sizes) / len(sizes), 2) if sizes else None, max=max(sizes) if sizes else None),
        read_execute_failures=dict(fail),
        out_of_candidate_paths={k: dict(triggered=sum(v.values()), gold_expected=dict(v)) for k, v in paths.items()},
        re_retrieval_path='absent in this configuration (0 triggers)')


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--run', required=True, type=pathlib.Path)
    p.add_argument('--snapshot', required=True)
    p.add_argument('--catalog', required=True)
    p.add_argument('--sealed', type=pathlib.Path, help='holdout mode: gold from the sealed files')
    p.add_argument('--output', required=True, type=pathlib.Path)
    a = p.parse_args()
    from decision_layer_eval import load_catalog
    visible = {r['intent_code']: r for r in load_catalog(a.catalog)}
    snap = json.loads(pathlib.Path(a.snapshot).read_text('utf-8'))['results']
    rows = [json.loads(x) for x in a.run.read_text('utf-8').splitlines() if x.strip()]
    if a.sealed:
        gold = holdout_gold(a.sealed)
        from holdout_score import gold_items
        texts = {k: v['input'] for k, v in gold_items(a.sealed).items()}
        out = {}
        for s in ('A', 'B'):
            sub = [dict(r, _input=texts[(r['set'], r['id'], r['turn'])]) for r in rows if r['set'] == s]
            out[s] = analyse(sub, lambda r: gold[(r['set'], r['id'], r['turn'])], snap, visible)
    else:
        out = {}
        for run in sorted({r['run'] for r in rows}):
            sub = [r for r in rows if r['run'] == run]
            out[run] = analyse(sub, lambda r: dict(expected=dev_expected(r), intent=r.get('gold_intent'), ambiguous=False),
                               snap, visible)
    a.output.write_text(json.dumps(out, ensure_ascii=False, indent=1), 'utf-8')
    print(json.dumps(out, ensure_ascii=False)[:3000])


if __name__ == '__main__':
    main()
