"""Milestone run of the no-generative Luna configuration on the sealed holdout (line E).

Follows tests/semantic-read/HOLDOUT.md: verifies the MANIFEST hash, freezes the configuration
hash before the first run (a later run with a different hash is refused), writes per-item model
outputs only to the private output directory, and never prints item content. Scoring is in
``holdout_score.py`` and reports aggregates only.

Configuration = the development ``nogen_luna`` arm (``nogen_pipeline.py``: candidates = previous
task + prod rerank@10 + blend@20 capped at 10, one Luna Decisions request with intent Choice +
dialogue-act Choice + clarify Predicate, continuation program rule, rubric clarify templates),
thresholds clarify >= 0.2 and execute >= 0.4, plus the standing write rule: an executed target whose
Tool is not declared READ (scanned from Java by scripts/finetune/tool_access_modes.py; undeclared /
dynamic fail closed) is routed to strong-model confirmation instead of direct execution.
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import pathlib
import sys
import time

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / 'backend' / 'python'))
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(ROOT / 'scripts' / 'finetune'))

from decision_eval_transport import TURN, BudgetStop, Ledger, _usage_tokens  # noqa: E402
from decision_layer_eval import load_catalog  # noqa: E402
from nogen_pipeline import LunaBackend, candidates, clarify_question, decide, load_rubric, rubric_notes  # noqa: E402

MANIFEST_SHA256 = 'f8ef128425de187d38e2fd767850eea3f2b65085df2d094d5075b5110caa641c'
CLARIFY_T, EXEC_T, MAX_OPTIONS = 0.2, 0.4, 10
FROZEN_FILES = ('tests/semantic-read/holdout_eval.py', 'tests/semantic-read/nogen_pipeline.py',
                'tests/semantic-read/decision_eval_transport.py', 'tests/semantic-read/ReadParamProbe.java',
                'scripts/finetune/clarify_rubric.json', 'scripts/finetune/tool_access_modes.py',
                'backend/python/ai/matcher/decision.py')


def sha(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def access_modes(rows):
    import tool_access_modes
    scan = tool_access_modes.scan()
    out = {}
    for r in rows:
        tool = r.get('tool_name')
        out[r['intent_code']] = 'NO_TOOL' if not tool else scan.get(tool, {}).get('mode', 'UNDECLARED')
    return out


def freeze(a, rows, modes):
    cfg = dict(arm='nogen_luna', clarify_t=CLARIFY_T, exec_t=EXEC_T, max_options=MAX_OPTIONS, model='gpt-6-luna',
               write_gate='executed target with mode != READ (NO_TOOL passes) -> CONFIRM_WRITE',
               files={f: sha(ROOT / f) for f in FROZEN_FILES}, catalog=sha(a.catalog), snapshot=sha(a.snapshot),
               access_modes=hashlib.sha256(json.dumps(modes, sort_keys=True).encode()).hexdigest(),
               java_classes_base='b803da72f9 (ReadParamProbe compiled against cretas-release-20261111b target/classes)')
    cfg['config_sha256'] = hashlib.sha256(json.dumps(cfg, sort_keys=True).encode()).hexdigest()
    path = pathlib.Path(a.out) / 'config-freeze.json'
    if path.exists():
        old = json.loads(path.read_text('utf-8'))
        if old['config_sha256'] != cfg['config_sha256']:
            raise SystemExit('configuration changed after freeze; refusing (old %s new %s)'
                             % (old['config_sha256'][:12], cfg['config_sha256'][:12]))
    else:
        path.write_text(json.dumps(cfg, ensure_ascii=False, indent=1), 'utf-8')
    return cfg


def render_b_state(state, names):
    if not state or not state.get('last_intent'):
        return []
    parts = ['上一轮在处理「%s」' % names.get(state['last_intent'], state['last_intent'])]
    conf = state.get('confirmed_params') or {}
    if isinstance(conf, dict) and conf:
        parts.append('已确认：' + '，'.join('%s=%s' % (k, v) for k, v in conf.items()))
    if state.get('last_action'):
        parts.append(state['last_action'])
    if state.get('pending_field'):
        parts.append('还缺：' + str(state['pending_field']))
    return [{'role': 'assistant', 'content': '；'.join(parts)}]


async def one(backend, ctx, query, history, prev):
    calls = []
    tok = TURN.set(calls)
    t = time.perf_counter()
    rec = {}
    try:
        codes = candidates(query, prev, ctx['snap'], ctx['by_code'], MAX_OPTIONS)
        rec['candidates'] = codes
        sig = await backend.signals(query, history, [ctx['by_code'][c] for c in codes],
                                    rubric_notes(codes, ctx['rubric'], query)) if codes else None
        rec['signals'] = sig
        if sig is None:
            rec.update(outcome='ERROR', error='no_candidates', intent=None, top=[], act=None)
        else:
            d = decide(sig, CLARIFY_T, EXEC_T, prev_intent=prev)
            rec.update(outcome_raw=d['outcome'], outcome=d['outcome'], intent=d['intent'] if d['outcome'] == 'EXECUTE' else None,
                       top=d['top'], act=d['act'], reason=d['reason'], clarify_p=sig['clarify_p'])
            if d['outcome'] == 'EXECUTE':
                rec['target_mode'] = ctx['modes'].get(d['intent'], 'UNDECLARED')
                if rec['target_mode'] not in ('READ', 'NO_TOOL'):
                    rec['outcome'] = 'CONFIRM_WRITE'
            if rec['outcome'] == 'CLARIFY':
                rec['clarify_kind'] = clarify_question(d['top'], ctx['rubric'], ctx['names'], query)['kind']
    except BudgetStop:
        raise
    except Exception as ex:  # noqa: BLE001
        rec.update(outcome='ERROR', error=type(ex).__name__ + ': ' + str(ex)[:160], intent=None, top=[], act=None)
    finally:
        TURN.reset(tok)
    rec['wall_ms'] = round((time.perf_counter() - t) * 1000, 1)
    rec['calls'] = [dict(label=c['label'], in_tokens=_usage_tokens(c.get('usage') or {})[0],
                         out_tokens=_usage_tokens(c.get('usage') or {})[1], latency_ms=c.get('latency_ms'),
                         billing=c.get('billing'), error=c.get('error')) for c in calls]
    rec['n_calls'] = len(calls)
    return rec


async def main_async(a):
    sealed = pathlib.Path(a.sealed)
    if not a.smoke and sha(sealed / 'MANIFEST.json') != MANIFEST_SHA256:
        raise SystemExit('MANIFEST hash mismatch: the holdout was changed; void')
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    rows = load_catalog(a.catalog)
    by_code = {r['intent_code']: r for r in rows}
    names = {c: r.get('intent_name') or c for c, r in by_code.items()}
    modes = access_modes(rows)
    cfg = freeze(a, rows, modes)
    target = out / ('run-%d.jsonl' % a.run_index)
    if target.exists():
        raise SystemExit('run %d already exists; runs are never overwritten' % a.run_index)
    ctx = dict(snap=json.loads(pathlib.Path(a.snapshot).read_text('utf-8'))['results'], by_code=by_code, names=names,
               rubric=load_rubric(ROOT / 'scripts' / 'finetune' / 'clarify_rubric.json'), modes=modes)
    ledger = Ledger(out / ('ledger-%d' % a.run_index), a.cap, pathlib.Path(a.key_file))
    backend = LunaBackend(ledger)
    A = json.loads((sealed / 'A_multiturn.json').read_text('utf-8'))['dialogues']
    B = [json.loads(x) for x in (sealed / 'B_decision.jsonl').read_text('utf-8').splitlines() if x.strip()]
    fh = target.open('a', encoding='utf-8')
    gate = asyncio.Semaphore(a.parallel)

    def sink(r):
        fh.write(json.dumps(r, ensure_ascii=False, default=str) + '\n')
        fh.flush()

    async def dialogue(d):
        async with gate:
            history, prev = [], None
            for t in d['turns']:
                rec = await one(backend, ctx, t['input'], list(history), prev)
                sink(dict(rec, set='A', id=d['id'], turn=t['turn'], prev_intent_used=prev))
                history.append({'role': 'user', 'content': t['input']})
                o = rec['outcome']
                history.append({'role': 'assistant', 'content':
                                '已为您查询「%s」' % names.get(rec['intent'], rec['intent']) if o == 'EXECUTE' else
                                '这是会改数据的操作，需要您确认后再办。' if o == 'CONFIRM_WRITE' else
                                '请确认您要办理的是哪一项' if o == 'CLARIFY' else '好的，这件事先不办了。'})
                prev = rec['intent'] if o == 'EXECUTE' else (prev if o == 'CLARIFY' else None)

    async def b_row(r):
        async with gate:
            st = r.get('state') or {}
            rec = await one(backend, ctx, r['utterance'], render_b_state(st, names), st.get('last_intent'))
            sink(dict(rec, set='B', id=r['id'], turn=1, prev_intent_used=st.get('last_intent')))
    started = time.time()
    try:
        await asyncio.gather(*([dialogue(d) for d in A] + [b_row(r) for r in B]))
    finally:
        fh.close()
        await ledger.close()
    (out / ('run-%d.meta.json' % a.run_index)).write_text(json.dumps(dict(
        config_sha256=cfg['config_sha256'], started=started, finished=time.time(), spent_upper_usd=ledger.spent,
        a_dialogues=len(A), b_rows=len(B)), indent=1), 'utf-8')
    print('run %d done; spent<=%.3f USD; config %s' % (a.run_index, ledger.spent, cfg['config_sha256'][:16]))


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--sealed', required=True)
    p.add_argument('--snapshot', required=True)
    p.add_argument('--catalog', required=True)
    p.add_argument('--out', required=True)
    p.add_argument('--run-index', type=int, required=True, help='1 = official; 2, 3 = stability only')
    p.add_argument('--cap', type=float, default=2.5)
    p.add_argument('--key-file', default=str(pathlib.Path.home() / '.codex/secrets/cretas-openai-eval.env'))
    p.add_argument('--parallel', type=int, default=8)
    p.add_argument('--smoke', action='store_true', help='dev items in holdout format; skips the MANIFEST check')
    asyncio.run(main_async(p.parse_args()))


if __name__ == '__main__':
    main()
