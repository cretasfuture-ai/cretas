# -*- coding: utf-8 -*-
"""③⑤ 的 prod A/B —— 真实入口 `tiered_answer`，绕过叙述缓存。

🔴 **为什么问句里要自带指标和时间范围**：
📏 实测（本轮 BEFORE，3/3 轮）「我要不要关掉最差的那家店」返回 `kind=clarification`，
   `synthesize 被调用 0 次` —— 那不是基线，那是**没量到**（rc=2 的形状）。
   澄清判定在 `restaurant_intent`（本轮禁止碰的文件），⇒ 绕开它的办法是
   **把它要问的两件事（判断指标 / 时间范围）写进问句**。
⚠️ 于是本探针量的是「问句已经说清楚之后，归因这一段长什么样」，
   ⛔ 不是「老板第一句就这么问会怎样」—— 口径不同，别混着比。

🔑 阳性对照（三格，任一为 0 ⇒ 本轮读数作废 rc=2）：
   ① `synthesize` 被调用次数        ② `_build_prompt` 被调用次数
   ③ 喂给模型的门店名单行数         —— 0 ⇒ 答案根本没重新生成
"""
from __future__ import annotations

import asyncio
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))

from smartbi.scripts._probe_bootstrap import bootstrap_probe  # noqa: E402

ctx = bootstrap_probe("MOCK_REST")

from smartbi.agent import synthesis_engine as se  # noqa: E402
from smartbi.agent.narrative_cache import NarrativeCacheService  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

FID = "MOCK_REST"
ROUNDS = int(os.environ.get("ATTRIB_ROUNDS", "3"))
LABEL = os.environ.get("ATTRIB_LABEL", "<未标注>")

#: ⚠️ 每句都自带「指标 + 时间范围」——见模块 docstring 的理由。
QUESTIONS = [
    q for q in (os.environ.get("ATTRIB_QUESTIONS", "").split("|")) if q.strip()
] or [
    "最近30天按营业额看，最差的那家店我要不要关掉",
]

_CLEARED = ("clear_semantic_plan_cache", "clear_route_cache",
            "clear_tenant_gate_cache", "clear_promoted_routes_cache")

_ROSTER_RE = re.compile(r"^\s+\d+\. .+：¥", re.M)

SEEN: list = []


def _instrument():
    orig_syn = se.ComprehensiveSynthesisEngine.synthesize
    orig_fb = se.ComprehensiveSynthesisEngine._build_factbook
    orig_prompt = se.ComprehensiveSynthesisEngine._build_prompt

    async def wrapped_fb(self, factory_id, date_range, plan, **kw):
        fb = await orig_fb(self, factory_id, date_range, plan, **kw)
        if SEEN:
            SEEN[-1]["attribution"] = fb.attribution
            SEEN[-1]["traffic_trend"] = getattr(fb, "traffic_trend", "<字段不存在>")
        return fb

    def wrapped_prompt(self, *a, **kw):
        out = orig_prompt(self, *a, **kw)
        if SEEN:
            SEEN[-1]["prompt_calls"] += 1
            SEEN[-1]["roster_lines"] = len(_ROSTER_RE.findall(out or ""))
        return out

    async def wrapped(self, factory_id, question, date_range, **kw):
        SEEN.append({"question": question, "date_range": str(date_range),
                     "attribution": None, "traffic_trend": None,
                     "prompt_calls": 0, "roster_lines": 0, "answer": None})
        res = await orig_syn(self, factory_id, question, date_range, **kw)
        SEEN[-1]["answer"] = getattr(res, "answer", None)
        return res

    se.ComprehensiveSynthesisEngine.synthesize = wrapped
    se.ComprehensiveSynthesisEngine._build_factbook = wrapped_fb
    se.ComprehensiveSynthesisEngine._build_prompt = wrapped_prompt


async def _ask(pool, q, key):
    for n in _CLEARED:
        getattr(ri, n)()
    set_factory_id(FID)
    async with rr.dish_catalogue_scope(pool, FID):
        spec = await ri.parse_restaurant_query(
            q, pool, factory_id=FID, session_key=key, semantic_first=True)
    return await svc.tiered_answer(q, pool, FID, ctx.role,
                                   precomputed_spec=spec, session_key=key) or {}


async def main() -> int:
    if getattr(ctx, "llm_dead_slots", None):
        print("rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2
    pool = await ctx.pool()
    _instrument()

    async def _miss(self, *a, **kw):
        return None

    NarrativeCacheService.get = _miss
    if hasattr(NarrativeCacheService, "get_semantic"):
        NarrativeCacheService.get_semantic = _miss

    tally = []
    for i, q in enumerate(QUESTIONS * ROUNDS):
        SEEN.clear()
        res = await _ask(pool, q, f"ab3-{LABEL}-{i}")
        text = res.get("answer_text") or ""
        print("=" * 78)
        print(f"[{LABEL}] 第 {i + 1}/{len(QUESTIONS) * ROUNDS} 轮   问: {q}")
        print(f"  kind={res.get('kind')!r}  synthesize 被调用 {len(SEEN)} 次")
        rec = SEEN[-1] if SEEN else None
        if rec:
            att = rec["attribution"]
            print(f"  🔑 阳性对照: _build_prompt {rec['prompt_calls']} 次 / "
                  f"喂给模型的门店行 {rec['roster_lines']}")
            if not att or att.get("no_data"):
                print(f"  factbook.attribution = {att!r} ⇒ ②③ 都没跑")
            else:
                lg = att.get("laggard") or {}
                print(f"  ② laggard={lg.get('store_name')!r} "
                      f"Δ=¥{lg.get('delta_revenue'):,.0f} 主因={att.get('primary_cause')}")
            print(f"  ③ factbook.traffic_trend = {rec['traffic_trend']!r}")
        print("---- 老板看到的原文 ----")
        print(text)
        tally.append({
            "kind": res.get("kind"), "synth": len(SEEN),
            "prompt_calls": rec["prompt_calls"] if rec else 0,
            "roster": rec["roster_lines"] if rec else 0,
            "n": len(text),
            "has_step3": "客流" in text and ("每天" in text or "环比" in text),
            "has_step5": "只有你" in text or "门口" in text,
        })

    print("=" * 78)
    print(f"[{LABEL}] 汇总 —— ⛔ 逐轮在上面，这里只是索引")
    print("  轮 | kind          | synth | prompt | roster | 长度 | ③迹象 | ⑤迹象")
    for i, t in enumerate(tally, 1):
        print(f"  {i:>2} | {t['kind']:<13} | {t['synth']:<5} | {t['prompt_calls']:<6} "
              f"| {t['roster']:<6} | {t['n']:<4} | {str(t['has_step3']):<5} "
              f"| {t['has_step5']}")

    live = [t for t in tally if t["synth"] and t["prompt_calls"] and t["roster"]]
    print(f"  真的重新生成的轮次: {len(live)}/{len(tally)}")
    if not live:
        print("rc=2 一轮都没真的重新生成 ⇒ 本次读数作废", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
