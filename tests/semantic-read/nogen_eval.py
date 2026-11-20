"""Run the no-generative pipeline (``nogen_pipeline.py``) over development dialogues (line E).

  --mode record  one pass collecting signals (no thresholds) for offline calibration;
                 M uses the gold path as history (open loop), C uses its task state.
  --mode live    closed loop with chosen thresholds; writes ``turns.jsonl`` in the same shape as
                 ``decision_layer_eval.py`` so ``decision_eval_score.py`` scores every arm alike.

Candidates come from the prod retrieval snapshot plus the previous executed intent. Parameters
are bound afterwards by ``ReadParamProbe.java`` (main's deterministic binders) for every arm.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import pathlib
import sys
import time

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / 'backend' / 'python'))
sys.path.insert(0, str(HERE))

from decision_eval_transport import (TURN, BudgetStop, Ledger, _usage_tokens,  # noqa: E402
                                     load_llm_env, qwen_decision_type)
from decision_layer_eval import load_c, load_catalog, render_state, source_hashes, git_head  # noqa: E402
from nogen_pipeline import (LunaBackend, QwenBackend, candidates, clarify_question, decide, decide_v3,  # noqa: E402
                            load_rubric, rubric_notes)


def load_v4(path, split, per_act, seed):
    """pool-v4 rows in the C format, gold = rubric-v3 view (v3 label when its teachers agreed, else v1;
    rows whose v3 teachers disagreed are excluded). ``split`` is ``split_known``."""
    import collections
    import random
    rows = []
    for line in pathlib.Path(path).read_text('utf-8').splitlines():
        if not line.strip():
            continue
        r = json.loads(line)
        if r.get('split_known') != split:
            continue
        v3 = r.get('v3')
        if isinstance(v3, dict):
            if not v3.get('agreed'):
                continue
            act, intent, gold_src = v3.get('dialogue_act'), v3.get('intent'), 'v3'
        else:
            act, intent, gold_src = r.get('dialogue_act'), r.get('intent'), 'v1'
        if act is None:
            continue
        rows.append(dict(id=r['id'], utterance=r['utterance'], state=r.get('state'), dialogue_act=act, intent=intent,
                         read_only=r.get('read_only'), gold_src=gold_src))
    by = collections.defaultdict(list)
    for r in rows:
        by[r['dialogue_act']].append(r)
    out = []
    for act in sorted(by):
        pool = sorted(by[act], key=lambda r: r['id'])
        random.Random(seed + act).shuffle(pool)
        out += pool[: per_act.get(act, per_act.get('*', 0))]
    return sorted(out, key=lambda r: r['id'])


def call_rows(calls):
    out = []
    for c in calls:
        tin, tout, cached = _usage_tokens(c.get('usage') or {})
        out.append(dict(label=c['label'], model=c['model'], in_tokens=tin, out_tokens=tout, cached_tokens=cached,
                        latency_ms=c.get('latency_ms'), error=c.get('error')))
    return out


async def one_turn(backend, ctx, query, history, prev_intent, thresholds):
    calls = []
    token = TURN.set(calls)
    t = time.perf_counter()
    rec = dict(prev_intent_used=prev_intent)
    try:
        codes = candidates(query, prev_intent, ctx['snap'], ctx['by_code'], ctx['max_options'])
        rec['candidates'] = codes
        if not codes:
            rec['signals'] = None
            rec['error'] = 'no_candidates'
        else:
            notes = rubric_notes(codes, ctx['rubric'], query)
            sig = await backend.signals(query, history, [ctx['by_code'][c] for c in codes], notes)
            rec['signals'] = sig
    except BudgetStop:
        raise
    except Exception as ex:  # noqa: BLE001
        rec['signals'] = None
        rec['error'] = type(ex).__name__ + ': ' + str(ex)[:200]
    finally:
        TURN.reset(token)
    rec['wall_ms'] = round((time.perf_counter() - t) * 1000, 1)
    rec['calls'] = call_rows(calls)
    rec['n_calls'] = len(calls)
    if thresholds is not None:
        if rec['signals'] is None:
            rec.update(outcome='ERROR', intent=None, top=[], act=None)
        else:
            if ctx['policy'] == 'v3':
                d = decide_v3(rec['signals'], thresholds[0], thresholds[1], prev_intent=prev_intent,
                              modes=ctx['modes'], margin_t=ctx['margin_t'])
            else:
                d = decide(rec['signals'], *thresholds, prev_intent=prev_intent)
            rec.update(outcome=d['outcome'], intent=d['intent'] if d['outcome'] == 'EXECUTE' else None,
                       top=d['top'], act=d['act'], reason=d['reason'])
            if d['outcome'] == 'CLARIFY':
                rec['clarify_question'] = clarify_question(d['top'], ctx['rubric'], ctx['names'], query)
        rec['understand'] = None if rec.get('act') is None else dict(act=rec['act'], parameters=[])
    return rec


def assistant_line(rec, names):
    if rec.get('outcome') == 'EXECUTE':
        return '已为您查询「%s」' % names.get(rec['intent'], rec['intent'])
    if rec.get('outcome') == 'CLARIFY':
        return (rec.get('clarify_question') or {}).get('text') or '请确认您要办理的是哪一项'
    return '好的，这件事先不办了。'


async def main_async(a):
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    target = out / ('signals.jsonl' if a.mode == 'record' else 'turns.jsonl')
    if target.exists():
        raise SystemExit('refusing to overwrite ' + str(target))
    rows = load_catalog(a.catalog)
    by_code = {r['intent_code']: r for r in rows}
    names = {c: r.get('intent_name') or c for c, r in by_code.items()}
    ctx = dict(snap=json.loads(pathlib.Path(a.retrieval_snapshot).read_text('utf-8'))['results'], by_code=by_code,
               names=names, rubric=load_rubric(pathlib.Path(a.rubric)), max_options=a.max_options)
    ledger = None
    if a.backend == 'luna':
        ledger = Ledger(out, a.cap, pathlib.Path(a.key_file), concurrency=a.concurrency, quota_roots=a.quota_from, paid=a.paid)
        backend = LunaBackend(ledger, predicate=a.predicate)
    else:
        load_llm_env(a.llm_env)
        backend = QwenBackend(qwen_decision_type()(timeout_s=float(a.timeout)))
    thresholds = None if a.mode == 'record' else (a.clarify_t, a.exec_t)
    ctx['policy'], ctx['margin_t'] = a.policy, a.margin_t
    if a.policy == 'v3':
        sys.path.insert(0, str(ROOT / 'scripts' / 'finetune'))
        import tool_access_modes
        scan = tool_access_modes.scan()
        ctx['modes'] = {c: ('NO_TOOL' if not r.get('tool_name') else scan.get(r['tool_name'], {}).get('mode', 'UNDECLARED'))
                        for c, r in by_code.items()}
    corpus_m = json.loads(pathlib.Path(a.corpus_m).read_text('utf-8'))['dialogues'] if a.corpus_m else []
    if a.corpus_v4:
        corpus_c = load_v4(a.corpus_v4, a.v4_split, json.loads(a.c_per_act), a.seed)
    else:
        corpus_c = load_c(a.corpus_c, json.loads(a.c_per_act), a.seed) if a.corpus_c else []
    manifest = dict(head=git_head(), source_hashes=source_hashes(), backend=a.backend, mode=a.mode,
                    thresholds=thresholds, corpus_m=a.corpus_m, corpus_c=a.corpus_c, c_ids=[r['id'] for r in corpus_c],
                    rubric=a.rubric, retrieval_snapshot=a.retrieval_snapshot, max_options=a.max_options, predicate=a.predicate,
                    policy=a.policy, margin_t=a.margin_t, corpus_v4=a.corpus_v4, v4_split=a.v4_split, runs=a.runs, label=a.label, started=time.time())
    (out / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), 'utf-8')
    fh = target.open('a', encoding='utf-8')

    def sink(r):
        fh.write(json.dumps(r, ensure_ascii=False, default=str) + '\n')
        fh.flush()
    gate = asyncio.Semaphore(a.parallel)

    async def dialogue(d, run_id):
        async with gate:
            history, prev = [], None
            for i, t in enumerate(d['turns']):
                if a.mode == 'record':
                    prev = d['turns'][i - 1]['intent'] if i else None
                rec = await one_turn(backend, ctx, t['input'], list(history), prev, thresholds)
                sink(dict(rec, run=run_id, part='M', case_id=d['id'], turn=i + 1, input=t['input'], query=t['input'],
                          gold_intent=t['intent'], gold_params=t['parameters'], gold_basis=t.get('basis'),
                          gold_act=t.get('action'), history_len=len(history)))
                history.append({'role': 'user', 'content': t['input']})
                if a.mode == 'record':
                    history.append({'role': 'assistant', 'content': '已为您查询「%s」' % names.get(t['intent'], t['intent'])})
                else:
                    history.append({'role': 'assistant', 'content': assistant_line(rec, names)})
                    prev = rec['intent'] if rec.get('outcome') == 'EXECUTE' else (prev if rec.get('outcome') == 'CLARIFY' else None)

    async def c_row(r, run_id):
        async with gate:
            state = r.get('state') or {}
            rec = await one_turn(backend, ctx, r['utterance'], render_state(state, names), state.get('prev_intent'),
                                 thresholds)
            sink(dict(rec, run=run_id, part='C', case_id=r['id'], turn=1, input=r['utterance'], query=r['utterance'],
                      gold_intent=r.get('intent'), gold_act=r['dialogue_act'], prev_intent=state.get('prev_intent'),
                      read_only=r.get('read_only')))
    try:
        for run in range(1, a.runs + 1):
            run_id = '%s-r%d' % (a.label, run)
            await asyncio.gather(*([dialogue(d, run_id) for d in corpus_m] + [c_row(r, run_id) for r in corpus_c]))
            print('run %s done' % run_id, flush=True)
    finally:
        fh.close()
        if ledger is not None:
            await ledger.close()
        manifest['finished'] = time.time()
        (out / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), 'utf-8')


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--backend', choices=('luna', 'qwen'), required=True)
    p.add_argument('--mode', choices=('record', 'live'), required=True)
    p.add_argument('--catalog', required=True)
    p.add_argument('--retrieval-snapshot', required=True)
    p.add_argument('--rubric', default=str(ROOT / 'scripts' / 'finetune' / 'clarify_rubric.json'))
    p.add_argument('--corpus-m')
    p.add_argument('--corpus-c')
    p.add_argument('--c-per-act', default='{}')
    p.add_argument('--seed', default='e-line')
    p.add_argument('--runs', type=int, default=1)
    p.add_argument('--label', required=True)
    p.add_argument('--out', required=True)
    p.add_argument('--clarify-t', type=float, default=0.5)
    p.add_argument('--exec-t', type=float, default=0.5)
    p.add_argument('--cap', type=float, default=30.0)
    p.add_argument('--key-file', default=str(pathlib.Path.home() / '.codex/secrets/cretas-openai-eval.env'))
    p.add_argument('--quota-from', nargs='*', default=[])
    p.add_argument('--paid', action='store_true', help='use paid usage (USD cap still applies); rows tagged billing=paid')
    p.add_argument('--llm-env', default=r'C:/Users/Steve/my-prototype-logistics/backend/python/.env')
    p.add_argument('--timeout', default='10')
    p.add_argument('--concurrency', type=int, default=8)
    p.add_argument('--parallel', type=int, default=6)
    p.add_argument('--max-options', type=int, default=18)
    p.add_argument('--predicate', choices=('v1', 'v3', 'both'), default='v1', help='Luna clarify Predicate wording')
    p.add_argument('--policy', choices=('v1', 'v3'), default='v1', help='decision rule (v3 = Steve 2026-11-12 policy)')
    p.add_argument('--margin-t', type=float, default=0.3)
    p.add_argument('--corpus-v4', help='pool-v4.jsonl (rubric-v3 gold view) instead of --corpus-c')
    p.add_argument('--v4-split', default='validation')
    asyncio.run(main_async(p.parse_args()))


if __name__ == '__main__':
    main()
