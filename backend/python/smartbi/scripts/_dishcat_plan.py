# -*- coding: utf-8 -*-
"""目录否决之后，「摘掉菜名这个计划还成不成立」有没有结构信号可读？

⛔ 不判「用户是什么意思」。只读 **LLM 自己编译出来的** 计划字段：
   `analysis_action` / `ranking_direction` / `dimensions` / `intent`。

同一句问句跑 ROUNDS 轮 —— 真 LLM 对同一条问句吐过三种不同输出形状。
⚠️ 每轮前清 4 个缓存，否则第 2..N 轮命中第 1 轮写进去的计划缓存 = 假重复。
"""
from __future__ import annotations

import asyncio
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))

from smartbi.scripts._probe_bootstrap import bootstrap_probe  # noqa: E402

ctx = bootstrap_probe("MOCK_REST")

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

FID = "MOCK_REST"
ROUNDS = int(os.environ.get("ROUNDS", "3"))

_DISHCAT_PLAN_CASES = [
    # 期望「摘掉菜名后计划仍成立」—— 老板要的本来就是排行
    ("排行-最差追问", "那最差的呢", "哪道菜卖得最好"),
    ("排行-最差单轮", "最差的那道菜卖得怎么样", None),
    ("排行-最好单轮", "最好的那道菜毛利多少", None),
    # 期望「摘掉菜名后没题了」—— 问的就是这一道菜
    ("点名-新菜", "香辣蟹煲卖得怎么样", None),
    ("点名-新菜2", "佛跳墙这个月毛利多少", None),
    ("点名-真菜", "米饭最近30天卖得怎么样", None),
    ("点名-真菜2", "藤椒鸡这个月毛利多少", None),
]

_HELPERS = ("clear_semantic_plan_cache", "clear_route_cache",
            "clear_tenant_gate_cache", "clear_promoted_routes_cache")


def clear_caches():
    for name in _HELPERS:
        getattr(ri, name)()


async def main() -> int:
    pool = await ctx.pool()
    if getattr(ctx, "llm_dead_slots", None):
        print("rc=2 LLM 死槽: %s" % (ctx.llm_dead_slots,))
        return 2
    set_factory_id(FID)
    async with rr.dish_catalogue_scope(pool, FID):
        cat = sorted(rr.current_dish_catalogue() or ())
    if not cat:
        print("rc=2 目录为空")
        return 2
    print("菜单 %d 条: %s\n" % (len(cat), cat))

    shapes = {}
    for rnd in range(ROUNDS):
        for tag, q, prev in _DISHCAT_PLAN_CASES:
            clear_caches()
            history = None
            skey = "pl-%s-r%d" % (tag, rnd)
            set_factory_id(FID)
            async with rr.dish_catalogue_scope(pool, FID):
                if prev:
                    await ri.parse_restaurant_query(
                        prev, pool, factory_id=FID, session_key=skey,
                        semantic_first=True)
                    history = [{"role": "user", "content": prev},
                               {"role": "assistant", "content": "（上一轮答案）"}]
                spec = await ri.parse_restaurant_query(
                    q, pool, factory_id=FID, session_key=skey, history=history,
                    semantic_first=True)
            if spec is None:
                print("[%s r%d] spec=None" % (tag, rnd))
                continue
            slot = spec.dish_slot
            veto = None
            if slot:
                async with rr.dish_catalogue_scope(pool, FID):
                    veto = rr._catalogue_says_not_a_dish(slot)
            shape = (spec.analysis_action, spec.ranking_direction,
                     tuple(spec.dimensions),
                     (spec.intent or "").replace("RESTAURANT_OPS_", ""))
            shapes.setdefault(tag, Counter())[shape] += 1
            print("[%-14s r%d] dish_slot=%-12r 目录否决=%-5s action=%-10s "
                  "rank_dir=%-6s dims=%-16s intent=%-18s authority=%s"
                  % (tag, rnd, slot, veto, spec.analysis_action,
                     spec.ranking_direction, ",".join(spec.dimensions) or "∅",
                     shape[3], spec.planner_authority))

    print("\n" + "=" * 92)
    print("形状汇总（同一问句 %d 轮）" % ROUNDS)
    print("=" * 92)
    for tag, ctr in shapes.items():
        print("%-16s %s" % (tag, dict(ctr)))
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
