# -*- coding: utf-8 -*-
"""执行期到底有没有加载菜单目录？—— ⛔ 不从注释推断，跑一次让它自己说。

三件事，一次量完：

  1. `_extract_dish_candidate_single` **在执行期**被调用时，`current_dish_catalogue()`
     是不是 None（= 那道「菜单说了算」在生产上是 no-op）。
  2. A/B：把 `tiered_answer` 放进 / 放出 `dish_catalogue_scope`，老板看到的原文差在哪。
  3. spec 的 `dish` 槽位上，目录否决的发生率（复现 owner 的 24 问句读数）。

⚠️ A/B 两侧都**真编译一次**（每次 turn 前清 4 个缓存），否则第二侧命中第一侧
   写进去的计划缓存，两侧是同一个东西。
"""
from __future__ import annotations

import asyncio
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))

from smartbi.scripts._probe_bootstrap import bootstrap_probe  # noqa: E402

ctx = bootstrap_probe("MOCK_REST")

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.gold.restaurant.restaurant_intent_service import (  # noqa: E402
    tiered_answer,
)
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

FID = "MOCK_REST"

_DISHCAT_SCOPE_CASES = [
    ("最差-追问", "那最差的呢", "哪道菜卖得最好"),
    ("最差-单轮", "最差的那道菜卖得怎么样", None),
    ("新菜", "香辣蟹煲卖得怎么样", None),
    ("真菜", "米饭最近30天卖得怎么样", None),
]

_HELPERS = ("clear_semantic_plan_cache", "clear_route_cache",
            "clear_tenant_gate_cache", "clear_promoted_routes_cache")


def clear_caches():
    for name in _HELPERS:
        getattr(ri, name)()


#: 执行期每次调用 `_extract_dish_candidate_single` 时记一条
#: (候选串, 当时目录条数或 None)。⛔ 记的是**目录在不在**，不是判据的返回值。
SPY: list = []
_ORIG = rr._extract_dish_candidate_single


def _spy(text):
    cat = rr.current_dish_catalogue()
    out = _ORIG(text)
    SPY.append((text[:28], None if cat is None else len(cat), out))
    return out


rr._extract_dish_candidate_single = _spy


async def one(pool, q, skey, history, *, wrap: bool):
    """wrap=True: 执行也放进 dish_catalogue_scope（候选方案）。"""
    set_factory_id(FID)
    async with rr.dish_catalogue_scope(pool, FID):
        spec = await ri.parse_restaurant_query(
            q, pool, factory_id=FID, session_key=skey, history=history,
            semantic_first=True)
        if wrap:
            res = await tiered_answer(q, pool, FID, ctx.role,
                                      precomputed_spec=spec, session_key=skey,
                                      history=history)
            return spec, res
    res = await tiered_answer(q, pool, FID, ctx.role, precomputed_spec=spec,
                              session_key=skey, history=history)
    return spec, res


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

    for tag, q, prev in _DISHCAT_SCOPE_CASES:
        for wrap in (False, True):
            clear_caches()
            SPY.clear()
            history = None
            skey = "sc-%s-%s" % (tag, wrap)
            if prev:
                _s, first = await one(pool, prev, skey, None, wrap=wrap)
                history = [{"role": "user", "content": prev},
                           {"role": "assistant",
                            "content": (first or {}).get("answer_text", "")}]
                SPY.clear()
            spec, res = await one(pool, q, skey, history, wrap=wrap)
            text = (res or {}).get("answer_text") or ""
            print("=" * 88)
            print("[%s] wrap=%s  spec.dish=%r  kind=%s  code=%s"
                  % (tag, wrap, getattr(spec, "dish", None),
                     (res or {}).get("kind"), (res or {}).get("code")))
            print("  抽取器调用 %d 次（候选, 当时目录条数, 抽出的菜名）:" % len(SPY))
            for row in SPY[:8]:
                print("     %r  目录=%s  ->  %r" % row)
            print("  老板看到 %d 字:" % len(text))
            for line in text.splitlines()[:14]:
                print("  | " + line)
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
