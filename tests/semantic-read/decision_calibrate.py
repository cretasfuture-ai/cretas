"""Collect every signal per turn once, so thresholds are chosen offline (line E calibration).

For each development item it records, from one pass: the merged understanding (one call),
the Luna Choice distribution over the merged candidates (production option construction,
special options on), and a Luna Predicate "needs clarification" probability. Policies
(which clarify signal, which threshold, when to call Choice) are then simulated by
``decision_policy.py`` without further model calls. Live comparison runs use the
production orchestrator with the chosen config, not this script.

History here is open-loop (gold path): previous user turns plus "已为您查询「gold」".
"""
from __future__ import annotations

import argparse
import asyncio
import dataclasses
import json
import pathlib
import sys
import time

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / 'backend' / 'python'))
sys.path.insert(0, str(HERE))

from ai.matcher.decision import UNDERSTAND_RULES, history_block, intent_text, role_line  # noqa: E402
from decision_eval_transport import TURN, Ledger, _usage_tokens, decision_type  # noqa: E402
from decision_layer_eval import ROLE, load_c, load_catalog, render_state  # noqa: E402

PREDICATE_INSTRUCTIONS = ('判断系统这一轮是否必须先问用户才能办理（true=必须先问；false=可以直接办、续接上一轮或明确取消）。'
                          + UNDERSTAND_RULES)


def predicate_text(query, history, options):
    opts = '\n'.join(f"{r['intent_code']}：{intent_text(r)[:60]}" for r in options)
    return f"{role_line(ROLE)}\n{history_block(history)}用户说：{query}\n\n可能相关的功能：\n{opts}"


async def one(matcher, rows, by_code, query, history, base, sink):
    calls = []
    token = TURN.set(calls)
    t = time.perf_counter()
    rec = dict(base, query=query, history=history)
    try:
        u = await matcher.understand(query, ROLE, rows, history)
        rec['understand_ms'] = round((time.perf_counter() - t) * 1000, 1)
        rec['understand'] = None if u is None else dict(dataclasses.asdict(u), purpose=u.purpose.value)
        if u is not None:
            codes = []
            for c in u.candidates + u.alternatives:
                if c in by_code and c not in codes:
                    codes.append(c)
            options = [by_code[c] for c in codes[:18]]
            choice_t = time.perf_counter()
            pred_t = time.perf_counter()

            async def timed(coro, start):
                v = await coro
                return v, round((time.perf_counter() - start) * 1000, 1)
            jobs = [timed(matcher.clarify_predicate(PREDICATE_INSTRUCTIONS, predicate_text(query, history, options)), pred_t)]
            if options:
                jobs.append(timed(matcher.choose(query, ROLE, options, history, special_options=True), choice_t))
            res = await asyncio.gather(*jobs, return_exceptions=True)
            p, pm = res[0] if not isinstance(res[0], BaseException) else (None, None)
            rec['predicate'], rec['predicate_ms'] = p, pm
            if options:
                ch, cm = res[1] if not isinstance(res[1], BaseException) else (None, None)
                rec['choice'], rec['choice_ms'] = ch, cm
            else:
                rec['choice'], rec['choice_ms'] = None, None
    except Exception as ex:  # noqa: BLE001
        rec['error'] = type(ex).__name__ + ': ' + str(ex)[:200]
    finally:
        TURN.reset(token)
    rec['calls'] = []
    for c in calls:
        tin, tout, cached = _usage_tokens(c.get('usage') or {})
        rec['calls'].append(dict(label=c['label'], in_tokens=tin, out_tokens=tout, cached_tokens=cached,
                                 latency_ms=c.get('latency_ms'), error=c.get('error')))
    sink(rec)


async def main_async(a):
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    if (out / 'signals.jsonl').exists():
        raise SystemExit('refusing to overwrite ' + str(out))
    rows = load_catalog(a.catalog)
    by_code = {r['intent_code']: r for r in rows}
    names = {c: r.get('intent_name') or c for c, r in by_code.items()}
    ledger = Ledger(out, a.cap, pathlib.Path(a.key_file), concurrency=a.concurrency)
    ledger.extra_spent = sum(sum(json.loads(x).get('price_upper_usd', 0.0) for x in pathlib.Path(p).read_text('utf-8').splitlines())
                             for p in a.spent_from if pathlib.Path(p).exists())
    matcher = decision_type(ledger)(timeout_s=90)
    fh = (out / 'signals.jsonl').open('a', encoding='utf-8')

    def sink(r):
        fh.write(json.dumps(r, ensure_ascii=False, default=str) + '\n')
        fh.flush()
    gate = asyncio.Semaphore(a.parallel)

    async def g(coro):
        async with gate:
            await coro
    jobs = []
    if a.corpus_m:
        for d in json.loads(pathlib.Path(a.corpus_m).read_text('utf-8'))['dialogues']:
            hist = []
            for i, t in enumerate(d['turns']):
                jobs.append(g(one(matcher, rows, by_code, t['input'], list(hist),
                                  dict(part='M', case_id=d['id'], turn=i + 1, gold_intent=t['intent'],
                                       gold_params=t['parameters'], gold_basis=t.get('basis')), sink)))
                hist += [{'role': 'user', 'content': t['input']},
                         {'role': 'assistant', 'content': '已为您查询「%s」' % names.get(t['intent'], t['intent'])}]
    if a.corpus_c:
        for r in load_c(a.corpus_c, json.loads(a.c_per_act), a.seed):
            jobs.append(g(one(matcher, rows, by_code, r['utterance'], render_state(r.get('state'), names),
                              dict(part='C', case_id=r['id'], turn=1, gold_intent=r.get('intent'), gold_act=r['dialogue_act'],
                                   prev_intent=(r.get('state') or {}).get('prev_intent'), read_only=r.get('read_only')), sink)))
    try:
        await asyncio.gather(*jobs)
    finally:
        fh.close()
        await ledger.close()
        print('spent<=%.3f' % ledger.spent)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--catalog', required=True)
    p.add_argument('--corpus-m')
    p.add_argument('--corpus-c')
    p.add_argument('--c-per-act', default='{}')
    p.add_argument('--seed', default='e-line')
    p.add_argument('--out', required=True)
    p.add_argument('--cap', type=float, default=30.0)
    p.add_argument('--spent-from', nargs='*', default=[])
    p.add_argument('--key-file', default=str(pathlib.Path.home() / '.codex/secrets/cretas-openai-eval.env'))
    p.add_argument('--concurrency', type=int, default=8)
    p.add_argument('--parallel', type=int, default=6)
    asyncio.run(main_async(p.parse_args()))


if __name__ == '__main__':
    main()
