"""Run the Python decision layer over development dialogues with real models (line E).

Development regression only; never independent acceptance. Two private inputs (kept
outside Git): the locked 2026-11-07 multi-turn corpus (``--corpus-m``, 24 dialogues /
74 turns, every turn should execute) and single-turn rows with a structured task state
from the v2 distillation splits (``--corpus-c``, includes NEED_CLARIFY / CANCEL rows so
both clarification directions have a denominator). The catalog is the F006
factory_super_admin visible intent list captured by the 11-07 probe.

What runs: ``Orchestrator.match`` from the checked-out source with production config
(FACTORY decision layer, special options on). Candidate retrieval is empty (no local
vector index / rerank service), so every turn uses the LLM shortlist; the exact phrase
registry is not loaded. Both versions run under the same conditions.

Output: ``<out>/turns.jsonl`` (one row per turn: outcome, selected intent, calls with
label/tokens/latency, wall time) and ``<out>/calls.jsonl`` (ledger), plus ``manifest.json``.
"""
from __future__ import annotations

import argparse
import asyncio
import collections
import hashlib
import json
import pathlib
import random
import re
import subprocess
import sys
import time

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / 'backend' / 'python'))
sys.path.insert(0, str(HERE))

from ai.config import AIConfig  # noqa: E402
from ai.orchestrator import Orchestrator  # noqa: E402
from decision_eval_transport import (TURN, UNDERSTOOD, BudgetStop, Ledger, SnapshotRerank, SnapshotRetriever,  # noqa: E402
                                     _usage_tokens, decision_type, load_llm_env, qwen_decision_type)

ROLE = 'factory_super_admin'


class Empty:
    async def match(self, *args, **kwargs):
        return []


def snake(k):
    return re.sub(r'(?<!^)(?=[A-Z])', '_', k).lower()


def load_catalog(path):
    raw = json.loads(pathlib.Path(path).read_text('utf-8'))
    return [{snake(k): v for k, v in r.items()} for r in raw if r.get('isActive') and not r.get('deletedAt')]


def render_state(state, names):
    """v2 structured task state -> one assistant line (same text for every version)."""
    if not state or not state.get('prev_intent'):
        return []
    parts = ['上一轮在处理「%s」' % names.get(state['prev_intent'], state['prev_intent'])]
    conf = [f"{p['name']}={p['value']}" for p in state.get('confirmed_params') or []]
    if conf:
        parts.append('已确认：' + '，'.join(conf))
    if state.get('last_action'):
        parts.append(state['last_action'])
    if state.get('pending_fields'):
        parts.append('还缺：' + '、'.join(state['pending_fields']))
    return [{'role': 'assistant', 'content': '；'.join(parts)}]


def load_c(path, per_act, seed):
    rows = [json.loads(line) for line in pathlib.Path(path).read_text('utf-8').splitlines() if line.strip()]
    by = collections.defaultdict(list)
    for r in rows:
        by[r['dialogue_act']].append(r)
    out = []
    for act in sorted(by):
        pool = sorted(by[act], key=lambda r: r['id'])
        random.Random(seed + act).shuffle(pool)
        out += pool[: per_act.get(act, per_act.get('*', 0))]
    return sorted(out, key=lambda r: r['id'])


def outcome_of(result):
    timing = result.timingMs or {}
    top = [c.intentCode for c in (result.topCandidates or [])]
    if timing.get('decisionFallback'):
        return 'FALLBACK', None, top
    if result.requiresConfirmation:
        return 'CLARIFY', (top[0] if top else None), top
    if result.bestMatch is not None:
        return 'EXECUTE', result.bestMatch.intentCode, top
    return 'NONE', None, top


def assistant_line(kind, intent, names, result):
    if kind == 'EXECUTE':
        return '已为您查询「%s」' % names.get(intent, intent)
    if kind == 'CLARIFY':
        return result.clarificationQuestion or '请确认您要办理的是哪一项'
    return result.conversationMessage or '这句话我没对上系统里的功能，所以先不做任何操作。'


async def run_turn(engine, rows, query, history):
    calls, understood = [], []
    token, utoken = TURN.set(calls), UNDERSTOOD.set(understood)
    t = time.perf_counter()
    error = None
    try:
        result = await engine.match(query=query, factoryId='F006', businessType='FACTORY', userId='eval',
                                    role=ROLE, visible_intents=rows, history=history, min_confidence=0.7)
    except BudgetStop:
        raise
    except Exception as ex:  # noqa: BLE001
        result, error = None, type(ex).__name__ + ': ' + str(ex)[:200]
    finally:
        TURN.reset(token)
        UNDERSTOOD.reset(utoken)
    wall = round((time.perf_counter() - t) * 1000, 1)
    call_rows = []
    for c in calls:
        tin, tout, cached = _usage_tokens(c.get('usage') or {})
        call_rows.append(dict(label=c['label'], model=c['model'], in_tokens=tin, out_tokens=tout,
                              cached_tokens=cached, latency_ms=c.get('latency_ms'), error=c.get('error')))
    return result, error, wall, call_rows, (understood[-1] if understood else None)


def turn_row(base, result, error, wall, calls, und=None):
    if result is None:
        kind, intent, top = 'ERROR', None, []
        timing = {}
    else:
        kind, intent, top = outcome_of(result)
        timing = result.timingMs or {}
    return dict(base, outcome=kind, intent=intent, top=top[:5], error=error, wall_ms=wall, calls=calls,
                n_calls=len(calls), timing=timing, understand=und,
                confidence=None if result is None else result.confidence)


async def run_dialogue(engine, rows, names, plan, run_id, sink):
    history = []
    for i, turn in enumerate(plan['turns']):
        result, error, wall, calls, und = await run_turn(engine, rows, turn['input'], list(history))
        row = turn_row(dict(run=run_id, part='M', case_id=plan['id'], turn=i + 1, input=turn['input'],
                            gold_intent=turn['intent'], gold_params=turn['parameters'], gold_basis=turn.get('basis'),
                            gold_act=turn.get('action'), history_len=len(history)), result, error, wall, calls, und)
        sink(row)
        history.append({'role': 'user', 'content': turn['input']})
        if result is not None:
            history.append({'role': 'assistant', 'content': assistant_line(row['outcome'], row['intent'], names, result)})


async def run_c_row(engine, rows, names, item, run_id, sink):
    history = render_state(item.get('state'), names)
    result, error, wall, calls, und = await run_turn(engine, rows, item['utterance'], history)
    sink(turn_row(dict(run=run_id, part='C', case_id=item['id'], turn=1, input=item['utterance'],
                       gold_intent=item.get('intent'), gold_act=item['dialogue_act'],
                       prev_intent=(item.get('state') or {}).get('prev_intent'), read_only=item.get('read_only'),
                       history_len=len(history)), result, error, wall, calls, und))


def git_head():
    try:
        return subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    except Exception:  # noqa: BLE001
        return None


def source_hashes():
    out = {}
    for rel in ('backend/python/ai/orchestrator.py', 'backend/python/ai/matcher/decision.py',
                'backend/python/ai/config.py', 'tests/semantic-read/decision_eval_transport.py',
                'tests/semantic-read/decision_layer_eval.py'):
        p = ROOT / rel
        out[rel] = hashlib.sha256(p.read_bytes()).hexdigest() if p.exists() else None
    return out


def make_config(a):
    return AIConfig(decision_business_types='FACTORY', decision_special_options=True,
                    decision_candidates='rerank' if a.retrieval_snapshot else 'shortlist',
                    decision_timeout_s=float(a.timeout), **json.loads(a.config_json or '{}'))


async def main_async(a):
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    if (out / 'turns.jsonl').exists() and not a.resume:
        raise SystemExit('refusing to overwrite a completed run: ' + str(out))
    rows = load_catalog(a.catalog)
    names = {r['intent_code']: r.get('intent_name') or r['intent_code'] for r in rows}
    config = make_config(a)
    if a.arm == 'qwen':
        load_llm_env(a.llm_env)
        ledger = None
        matcher = qwen_decision_type()(timeout_s=float(a.timeout), k=config.decision_k)
    else:
        ledger = Ledger(out, a.cap, pathlib.Path(a.key_file), concurrency=a.concurrency,
                        quota_roots=a.quota_from, paid=a.paid)
        ledger.extra_spent = sum(
            sum(json.loads(line).get('price_upper_usd', 0.0) for line in pathlib.Path(p).read_text('utf-8').splitlines())
            for p in a.spent_from if pathlib.Path(p).exists())
        matcher = decision_type(ledger)(timeout_s=90)
    if a.retrieval_snapshot:
        snap = json.loads(pathlib.Path(a.retrieval_snapshot).read_text('utf-8'))['results']
        retriever = SnapshotRetriever(snap)
        rerank = SnapshotRerank(retriever)
    else:
        retriever, rerank = Empty(), None
    engine = Orchestrator(Empty(), Empty(), Empty(), decision_matcher=matcher, candidate_retriever=retriever,
                          config=config, rerank_client=rerank)
    corpus_m = json.loads(pathlib.Path(a.corpus_m).read_text('utf-8'))['dialogues'] if a.corpus_m else []
    if a.m_limit:
        corpus_m = corpus_m[: a.m_limit]
    per_act = json.loads(a.c_per_act) if a.c_per_act else {}
    corpus_c = load_c(a.corpus_c, per_act, a.seed) if a.corpus_c else []
    manifest = dict(head=git_head(), source_hashes=source_hashes(), config=config.__dict__ if hasattr(config, '__dict__') else str(config),
                    corpus_m=a.corpus_m, corpus_c=a.corpus_c, c_per_act=per_act, c_ids=[r['id'] for r in corpus_c],
                    m_ids=[d['id'] for d in corpus_m], catalog=a.catalog, catalog_rows=len(rows), role=ROLE,
                    runs=a.runs, label=a.label, started=time.time(),
                    arm=a.arm, retrieval=('PROD SNAPSHOT ' + a.retrieval_snapshot) if a.retrieval_snapshot else
                    'EMPTY (LLM shortlist only; no vector index, no rerank, exact registry not loaded)')
    (out / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2, default=str), 'utf-8')
    fh = (out / 'turns.jsonl').open('a', encoding='utf-8')

    def sink(row):
        fh.write(json.dumps(row, ensure_ascii=False, default=str) + '\n')
        fh.flush()

    gate = asyncio.Semaphore(a.parallel)

    async def guarded(coro):
        async with gate:
            await coro

    try:
        for run in range(1, a.runs + 1):
            run_id = '%s-r%d' % (a.label, run)
            jobs = [guarded(run_dialogue(engine, rows, names, d, run_id, sink)) for d in corpus_m]
            jobs += [guarded(run_c_row(engine, rows, names, r, run_id, sink)) for r in corpus_c]
            await asyncio.gather(*jobs)
            print('run %s done, spent<=%s USD' % (run_id, 'n/a (qwen)' if ledger is None else '%.3f' % ledger.spent), flush=True)
    finally:
        fh.close()
        if ledger is not None:
            await ledger.close()
        manifest['finished'] = time.time()
        manifest['spent_upper_usd'] = None if ledger is None else ledger.spent
        (out / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2, default=str), 'utf-8')


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--catalog', required=True)
    p.add_argument('--corpus-m')
    p.add_argument('--corpus-c')
    p.add_argument('--c-per-act', help='JSON {act: n}, e.g. {"NEED_CLARIFY":40,"*":20}')
    p.add_argument('--m-limit', type=int, default=0)
    p.add_argument('--seed', default='e-line')
    p.add_argument('--runs', type=int, default=3)
    p.add_argument('--label', required=True)
    p.add_argument('--out', required=True)
    p.add_argument('--cap', type=float, default=30.0)
    p.add_argument('--spent-from', nargs='*', default=[], help='other calls.jsonl that count against the same cap')
    p.add_argument('--key-file', default=str(pathlib.Path.home() / '.codex/secrets/cretas-openai-eval.env'))
    p.add_argument('--concurrency', type=int, default=8)
    p.add_argument('--parallel', type=int, default=4, help='dialogues / rows in flight')
    p.add_argument('--config-json', help='extra AIConfig fields as JSON')
    p.add_argument('--resume', action='store_true')
    p.add_argument('--arm', choices=('mini', 'qwen'), default='mini',
                   help='mini = OpenAI eval transport; qwen = production llm_router DECISION chain')
    p.add_argument('--llm-env', default=r'C:/Users/Steve/my-prototype-logistics/backend/python/.env')
    p.add_argument('--retrieval-snapshot', help='retrieval_snapshot_remote.py output; enables the prod rerank path')
    p.add_argument('--timeout', default='90')
    p.add_argument('--quota-from', nargs='*', default=[], help='other ledgers counted against the daily free groups')
    p.add_argument('--paid', action='store_true', help='use paid usage (USD cap still applies); rows tagged billing=paid')
    a = p.parse_args()
    asyncio.run(main_async(a))


if __name__ == '__main__':
    main()
