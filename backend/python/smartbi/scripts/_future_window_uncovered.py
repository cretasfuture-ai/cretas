# -*- coding: utf-8 -*-
"""解冻格用的读数: 这道闸**够不着**的那条路，现在是什么样。

`RESTAURANT_OPS_BUSINESS_OPTIMIZATION` 不经 `resolve_by_code` 派发
（`_SERVICE_DISPATCHED_WINDOW_AWARE`），所以未来窗口短路碰不到它。
⛔ 不猜它会怎样 —— 跑一次让它自己说（形态 B⁷）。

同时带一条**闸覆盖得到**的对照，证明这次读数里仪器是活的。
"""
from __future__ import annotations

import asyncio
import os
import sys
import time
from datetime import date

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_FID = "MOCK_REST"
_RUN = "%d-%d" % (os.getpid(), int(time.time()))
ctx = bootstrap_probe(_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant.restaurant_intent_service import tiered_answer  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

CASES = [
    ("闸够不着·经营优化", "2026年8月25号到8月31号全部门店营收怎么提高"),
    ("闸够不着·经营优化", "下周全部门店营收怎么提高"),
    ("对照·闸覆盖得到", "2026年8月25号到8月31号的营收是多少"),
]


async def main() -> int:
    if ctx.llm_dead_slots:
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2
    pool = await ctx.pool()
    print("PROBE_DB=%s 今天=%s" % (ctx.db_name, date.today()))
    specs = []
    for rnd in (1, 2):
        for tag, q in CASES:
            names = ("clear_semantic_plan_cache", "clear_route_cache",
                     "clear_tenant_gate_cache", "clear_promoted_routes_cache")
            for n in names:
                getattr(ri, n)()
            set_factory_id(_FID)
            print("-" * 80)
            print("r%d [%s] %s" % (rnd, tag, q))
            print("  [清缓存] %s" % " / ".join(names))
            spec = await ri.parse_restaurant_query(
                q, pool, factory_id=_FID, history=[],
                session_key="unc-%s-r%d-%s" % (_RUN, rnd, q), semantic_first=True)
            specs.append(spec)
            print("  intent=%s authority=%s date_range=%s window_label=%r"
                  % (spec.intent, getattr(spec, "planner_authority", "?"),
                     getattr(spec, "date_range", None),
                     getattr(spec, "window_label", None)))
            res = await tiered_answer(q, pool, factory_id=_FID, role=ctx.role,
                                      precomputed_spec=spec)
            text = ((res or {}).get("answer_text") or "").replace("\n", " ")
            print("  kind=%s 字数=%d 金额=%s"
                  % ((res or {}).get("kind"), len(text), "¥" in text))
            print("  ▎老板看到的原文: %s" % text[:240])
    if any(getattr(s, "planner_authority", "") == "llm_unavailable" for s in specs):
        print("⛔ rc=2 有 llm_unavailable，读数作废")
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
