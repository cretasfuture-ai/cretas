"""业务域缩小候选 (PR-4) 前后对比: 同一棵树、同一份考题、同一个模型链, 只差请求里带不带业务域表版本。

  before: catalog_version=None  ⇒ 决策层不按业务域收窄 (= 改动前行为, 老 Java 也是这样)
  after : catalog_version=表版本 ⇒ 粗选提示词只列这句话所属业务域的意图 (+ SYSTEM / ANALYTICS / 表外意图)

考题: 验收集 v3 开发半边 tests/intent-acceptance/dev/items.jsonl, 取 stance ∈ {DIRECT, WRITE} 且 ambiguous=false、
金标意图在目录里的题, 固定种子抽 --limit 条 (不碰封存半边)。
可见意图: 平台目录 backend/python/ai/data/factory-intent-catalog.json 的 canonical_intents (没有库里的 keywords,
两边条件相同)。决策链: 生产 llm_router 的 DECISION 链 (qwen, 读 backend/python/.env 的 LLM_* 键, 不打印),
粗选 + 读 logprobs 细选 (decision_fallback=legacy), 语义召回为空, 精确表不加载 —— 每题都走模型粗选, 正是收窄作用的那一步。

读数 (每个 arm): 首选意图命中金标 (含 intent_alternates) / 直接办且命中 / 金标进了细选候选 / 出错数。
退出码: 0 跑完 / 1 有题出错 (计入 unmeasured, 不当通过) / 2 一题都没量到。
用法: python -X utf8 domain_scope_eval.py --out <dir> [--limit 200] [--seed pr4]
"""
from __future__ import annotations

import argparse
import asyncio
import json
import pathlib
import random
import sys
import time

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / 'backend' / 'python'))
sys.path.insert(0, str(HERE))

from ai import intent_domain  # noqa: E402
from ai import registry  # noqa: E402
from ai.config import AIConfig  # noqa: E402
from ai.orchestrator import Orchestrator  # noqa: E402
from decision_eval_transport import TURN, load_llm_env, qwen_decision_type  # noqa: E402

ROLE_MAP = {'OPERATOR': 'operator', 'WAREHOUSE': 'warehouse_manager', 'QC': 'quality_inspector',
            'DISPATCHER': 'dispatcher', 'BOSS': 'factory_super_admin'}


class Empty:
    async def match(self, *args, **kwargs):
        return []


def load_items(limit, seed, codes):
    rows = []
    for line in (ROOT / 'tests/intent-acceptance/dev/items.jsonl').read_text(encoding='utf-8').splitlines():
        if not line.strip():
            continue
        it = json.loads(line)
        if it.get('ambiguous') or it.get('stance') not in ('DIRECT', 'WRITE') or it.get('intent') not in codes:
            continue
        rows.append(it)
    random.Random(seed).shuffle(rows)
    return rows[:limit] if limit else rows


async def one(engine, rows, it, version):
    calls = []
    token = TURN.set(calls)
    t = time.perf_counter()
    try:
        res = await engine.match(query=it['input'], factoryId='F006', businessType='FACTORY', userId='eval',
                                 role=ROLE_MAP.get(it.get('role'), 'factory_super_admin'), visible_intents=rows,
                                 history=[], min_confidence=0.7, catalog_version=version)
        err = None
    except Exception as ex:  # noqa: BLE001
        res, err = None, type(ex).__name__ + ': ' + str(ex)[:160]
    finally:
        TURN.reset(token)
    gold = {it['intent'], *(it.get('intent_alternates') or [])}
    top = [c.intentCode for c in (res.topCandidates or [])] if res is not None else []
    best = res.bestMatch.intentCode if res is not None and res.bestMatch is not None else (top[0] if top else None)
    executed = bool(res is not None and res.bestMatch is not None and not res.requiresConfirmation)
    timing = (res.timingMs or {}) if res is not None else {}
    return dict(id=it['id'], arm='after' if version else 'before', gold=sorted(gold), best=best, top=top[:5],
                executed=executed, hit=best in gold, exec_hit=executed and best in gold,
                gold_in_choice=bool(gold & set(top)), narrowed=timing.get('domainNarrowed') == 1,
                candidates=timing.get('domainCandidates'), error=err or ([c.get('error') for c in calls if c.get('error')] or [None])[0],
                ms=round((time.perf_counter() - t) * 1000), n_calls=len(calls))


def summary(rows, arm):
    rs = [r for r in rows if r['arm'] == arm]
    ok = [r for r in rs if r['error'] is None]
    n = len(ok)
    def pct(k):
        return '%d/%d (%.1f%%)' % (sum(1 for r in ok if r[k]), n, 100.0 * sum(1 for r in ok if r[k]) / max(1, n))
    return dict(arm=arm, measured=n, errors=len(rs) - n, top1_hit=pct('hit'), executed_and_hit=pct('exec_hit'),
                gold_in_choice=pct('gold_in_choice'), narrowed=sum(1 for r in ok if r['narrowed']))


async def main_async(a):
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    load_llm_env(a.llm_env)
    registry.set_registry(registry.RegistrySnapshot())
    cat = json.loads((ROOT / 'backend/python/ai/data/factory-intent-catalog.json').read_text(encoding='utf-8'))
    rows = [dict(r, id=str(i), is_active=True) for i, r in enumerate(cat['canonical_intents'])]
    codes = {r['intent_code'] for r in rows}
    items = load_items(a.limit, a.seed, codes)
    version = intent_domain.get_table().catalog_version
    config = AIConfig(decision_business_types='FACTORY', decision_special_options=True, decision_candidates='shortlist',
                      decision_fallback='legacy', decision_timeout_s=float(a.timeout))
    matcher = qwen_decision_type()(timeout_s=float(a.timeout), k=config.decision_k)
    engine = Orchestrator(Empty(), Empty(), Empty(), decision_matcher=matcher, candidate_retriever=Empty(), config=config)
    gate = asyncio.Semaphore(a.parallel)
    results = []

    async def guarded(it, v):
        async with gate:
            r = await one(engine, rows, it, v)
            results.append(r)

    # 两个 arm 交错跑, 同一时间段的模型状态对两边相同
    jobs = []
    for it in items:
        jobs += [guarded(it, None), guarded(it, version)]
    await asyncio.gather(*jobs)
    (out / 'turns.jsonl').write_text('\n'.join(json.dumps(r, ensure_ascii=False) for r in results) + '\n', 'utf-8')
    rep = dict(items=len(items), seed=a.seed, catalog_version=version, before=summary(results, 'before'),
               after=summary(results, 'after'))
    paired = {}
    for r in results:
        paired.setdefault(r['id'], {})[r['arm']] = r
    rep['after_lost'] = sorted(i for i, p in paired.items() if p.get('before', {}).get('hit') and not p.get('after', {}).get('hit'))
    rep['after_gained'] = sorted(i for i, p in paired.items() if p.get('after', {}).get('hit') and not p.get('before', {}).get('hit'))
    (out / 'report.json').write_text(json.dumps(rep, ensure_ascii=False, indent=2), 'utf-8')
    print(json.dumps(rep, ensure_ascii=False, indent=2))
    if not items or not any(r['error'] is None for r in results):
        return 2
    return 1 if any(r['error'] for r in results) else 0


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--out', required=True)
    p.add_argument('--limit', type=int, default=200)
    p.add_argument('--seed', default='pr4-domain')
    p.add_argument('--parallel', type=int, default=6)
    p.add_argument('--timeout', default='30')
    p.add_argument('--llm-env', default=r'C:/Users/Steve/my-prototype-logistics/backend/python/.env')
    a = p.parse_args()
    sys.exit(asyncio.run(main_async(a)))


if __name__ == '__main__':
    main()
