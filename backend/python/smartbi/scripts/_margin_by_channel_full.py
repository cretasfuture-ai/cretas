# -*- coding: utf-8 -*-
"""问「外卖和堂食的毛利」，老板到底拿到什么 —— **贴全文**，⛔ 不截断。

## 为什么重跑

我把这一格登记成「只给营收单量」，依据是 `_dimension_not_served` 打印的
**前 300 字**。守望第一次跑就翻面（`has_margin_word=True`）——
▎登记是基于**截断读数**做的，而「毛利」在后面。
▎（⛔「我看到的是全部吗」—— 今天第 N 次。）

而 `"毛利" in text` 又是**子串判据**，分不开两件事：

    (a) 真给了按渠道的毛利  ⇒ 这一格**不是缺陷**，登记该撤
    (b) 只在免责/口径句里提到「毛利」 ⇒ 仍然答非所问

⇒ 本探针只做一件事：**把全文贴出来给人读**，并顺带打几个结构量。
⛔ 不在这里下结论。
"""
from __future__ import annotations

import asyncio
import os
import re
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_MC_FID = "MOCK_REST"
_MC_RUN = "%d-%d" % (os.getpid(), int(time.time()))
ctx = bootstrap_probe(_MC_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

_MC_Q = "最近30天外卖和堂食的毛利分别是多少"


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s RUN=%s" % (ctx.db_name, _MC_RUN))
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, n)()
    set_factory_id(_MC_FID)
    async with rr.dish_catalogue_scope(pool, _MC_FID):
        spec = await ri.parse_restaurant_query(
            _MC_Q, pool, factory_id=_MC_FID, session_key="mc-%s" % _MC_RUN,
            semantic_first=True)
        res = None
        if spec is not None and svc.should_delegate(spec, None, query=_MC_Q):
            res = await svc.tiered_answer(
                _MC_Q, pool, _MC_FID, ctx.role,
                precomputed_spec=spec, session_key="mc-%s" % _MC_RUN)
    res = res or {}
    text = res.get("answer_text") or ""
    print("intent=%s  kind=%s  dims=%s  全文长度=%d 字"
          % (getattr(spec, "intent", None) if spec else None,
             res.get("kind"),
             list(getattr(spec, "dimensions", ()) or ()) if spec else [],
             len(text)))

    # 结构量（⛔ 只用来定位，不用来下结论）
    print("\n结构量:")
    print("  「毛利」出现 %d 次，首次在第 %d 字"
          % (text.count("毛利"), text.find("毛利")))
    heads = [ln for ln in text.split("\n") if ln.strip().startswith("|")
             and "---" not in ln]
    print("  表头行 %d 条:" % len(heads))
    for h in heads[:6]:
        print("     " + h.strip()[:110])
    # 「毛利」那几行的上下文
    print("\n  含「毛利」的行（逐行，⛔ 不截断）:")
    for ln in text.split("\n"):
        if "毛利" in ln:
            print("     " + ln.strip()[:160])

    print("\n" + "=" * 78)
    print("老板看到的**全文**（⛔ 不截断）")
    print("=" * 78)
    print(text)
    print("=" * 78)
    print("⛔ 本探针不下结论 —— 上面这段是给人读的。")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
