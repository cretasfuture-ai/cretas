# -*- coding: utf-8 -*-
"""老板要一个这个 resolver **产不出来的指标**时，系统知不知道？

## 起点（`_margin_by_channel_full` 实测）

「最近30天外卖和堂食的**毛利**分别是多少」→ `RESTAURANT_OPS_CHANNEL_MIX`，
老板拿到**营收 / 单量**表，全文 1031 字里「毛利」只出现 1 次、在第 942 字：

    往下可以拆开看：堂食和外卖各自的每单平均消费、**毛利分别是多少**。

▎**产品把老板刚问的那个问题，当成「下一步可以问什么」推荐回给了他。**

## 结构性背景

维度有能力表 `_RESOLVER_DIMENSIONS`（19 个 resolver），
**指标没有对应的那一张** —— 所以「这个 resolver 产不出这个指标」这件事，
系统有没有一条路知道，本探针要量。

## ⛔ 先量三件事，不下修法结论

    ① `spec.requested_metrics` —— 系统**知不知道**他要的是毛利
    ② `spec.wants_margin`      —— 另一条可能的信号
    ③ 答案里到底给没给毛利的**数**（表头含毛利 / 「毛利」近旁有数字）
       ⛔ 不用「正文提没提毛利」当判据 —— 那正是今天栽过的子串判据

## 阳性/阴性对照（⛔ 不许省）

- **阳性**：一句**能给毛利**的问句（「最近30天各门店毛利」）必须真给出毛利的数
  —— 给不出说明整条链没跑起来，读数作废（`rc=2`）
- **阴性**：一句**只问营收**的问句不该被判成「要毛利没给毛利」
"""
from __future__ import annotations

import asyncio
import os
import re
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_MN_FID = "MOCK_REST"
_MN_ROUNDS = 2
_MN_RUN = "%d-%d" % (os.getpid(), int(time.time()))
ctx = bootstrap_probe(_MN_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

_MN_CASES = [
    ("①要毛利", "最近30天外卖和堂食的毛利分别是多少"),
    ("①要毛利", "最近30天各渠道的毛利率是多少"),
    ("②阳性·能给毛利", "最近30天各门店毛利是多少"),
    ("③阴性·只问营收", "最近30天外卖和堂食的营收分别是多少"),
]


def _clear():
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, n)()


def _gave_margin_numbers(text: str) -> bool:
    """给没给**毛利的数** —— 表头含毛利，或「毛利」近旁 24 字内真有数字。

    ⛔ 不用 `"毛利" in text`：今天实测它命中的是一句**建议**
    （「往下可以拆开看…毛利分别是多少」）。
    """
    header = any("毛利" in ln for ln in (text or "").split("\n")
                 if ln.strip().startswith("|") and "---" not in ln)
    near = bool(re.search(r"毛利[^\n]{0,24}?(¥|\d[\d,]*\.?\d*\s*%|\d{3,})", text or ""))
    return header or near


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s 轮数=%d RUN=%s" % (ctx.db_name, _MN_ROUNDS, _MN_RUN))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2

    rows = []
    for rnd in range(1, _MN_ROUNDS + 1):
        for tag, q in _MN_CASES:
            _clear()
            set_factory_id(_MN_FID)
            skey = "mn-%s-r%d-%s" % (_MN_RUN, rnd, q)
            async with rr.dish_catalogue_scope(pool, _MN_FID):
                spec = await ri.parse_restaurant_query(
                    q, pool, factory_id=_MN_FID, session_key=skey,
                    semantic_first=True)
                res = None
                if spec is not None and svc.should_delegate(spec, None, query=q):
                    res = await svc.tiered_answer(
                        q, pool, _MN_FID, ctx.role,
                        precomputed_spec=spec, session_key=skey)
            res = res or {}
            text = res.get("answer_text") or ""
            metrics = tuple(getattr(spec, "requested_metrics", ()) or ()) if spec else ()
            wants = getattr(spec, "wants_margin", None) if spec else None
            unsup = tuple(getattr(spec, "unsupported_requirements", ()) or ()) if spec else ()
            gave = _gave_margin_numbers(text)
            rows.append((rnd, tag, q, getattr(spec, "intent", None) if spec else None,
                         metrics, wants, unsup, res.get("kind"), gave, text))

            print("\n" + "=" * 78)
            print("r%d 【%s】%s" % (rnd, tag, q))
            print("   intent=%s  kind=%s" % (
                getattr(spec, "intent", None) if spec else None, res.get("kind")))
            print("   🔑 requested_metrics=%s   wants_margin=%s"
                  % (list(metrics), wants))
            print("   unsupported_requirements=%s" % list(unsup))
            print("   给了毛利的**数** = %s" % gave)
            print("   ── 正文（前 220 字）──")
            print("   " + (text[:220] or "(空)").replace("\n", "\n   "))

    pos = [r for r in rows if r[1].startswith("②")]
    n_pos = sum(1 for r in pos if r[8])
    print("\n" + "=" * 78)
    print("阳性对照·能给毛利的问句真给了毛利的数 = %d/%d" % (n_pos, len(pos)))
    if not n_pos:
        print("⛔ rc=2 连该给毛利的都没给 —— 整条链没跑起来，读数作废")
        return 2

    want = [r for r in rows if r[1].startswith("①")]
    knew = [r for r in want if ("gross_margin" in r[4]) or r[5]]
    silent = [r for r in want if not r[8]]
    print("\n🔑 ①要毛利的 %d 轮里" % len(want))
    print("  系统**知道**他要毛利（metrics 含 gross_margin 或 wants_margin）= %d"
          % len(knew))
    print("  最终**没给毛利的数** = %d" % len(silent))
    for r in want:
        print("     [%s] metrics=%s wants=%s 给了毛利数=%s ← %s"
              % ("🔴" if not r[8] else "✅", list(r[4]), r[5], r[8], r[2]))
    neg = [r for r in rows if r[1].startswith("③")]
    print("\n阴性对照·只问营收那条 = %s"
          % ["给了毛利数=%s" % r[8] for r in neg])
    print("\n⚠️ 口径：评测题库、%d 条 × %d 轮，⛔ 不是真实流量。"
          % (len(_MN_CASES), _MN_ROUNDS))
    print("⛔ 本探针不下修法结论 —— 上面是事实。")
    return 1 if (knew and silent) else 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
