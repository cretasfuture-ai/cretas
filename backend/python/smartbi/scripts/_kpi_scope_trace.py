# -*- coding: utf-8 -*-
"""KPI 那一份到底按几道菜算的 —— 钩在 `resolve_gross_margin` 现场量。

## 为什么要这支（⛔ 别照转述下修法）

`_kpi_says_cannot_table_says_can` 实测 3/3 拿到一个**对不上**的读数：

    罗氏虾（无成本卡）  KPI 总毛利 = 暂不可计算(None)
    鲈鱼  （有成本卡）  KPI 总毛利 = **¥19,766,090**

而鲈鱼自己在表里的毛利是 **¥2,894,006.16**，表里 6 行之和是 **¥6,520,195.72**。
▎19.77M **两个都不是**。

⇒ 「KPI 按点名那一道菜算」这个成因**解释不了鲈鱼那一格**。
   读码给出的候选：`restaurant_ops_router.py:5047`

       dish_candidate = dish_candidates[0] if len(dish_candidates) == 1 else None

   候选**不止一个**时 `dish_candidate` 为 None ⇒ `pos_rows = matched_rows`
   那一步**不执行** ⇒ KPI 按全菜品算，而抬头仍然只说那一道。

## 本探针量什么（⛔ 只量事实）

钩住 `_build_margin_entries`，在**现场**取调用方的局部变量：

    dish_candidates    LLM/规则给了几个候选
    dish_candidate     过滤条件成不成立
    dish_scope_row     有没有落到单菜
    len(pos_rows)      喂给 KPI 计算的那份数据有几行

再并排贴出最终 KPI 的 `总毛利` —— **两个读数并排**，才判得了口径。

## 阳性/阴性对照

- **阳性**：钩子必须至少抓到 1 次现场（`len(pos_rows)` 非空）——
  抓不到说明 monkeypatch 打空了，`rc=2`，⛔ 不当成「没问题」
- **阴性**：`spec.planner_authority == 'llm_unavailable'` 必须为 0，非 0 ⇒ rc=2
"""
from __future__ import annotations

import asyncio
import os
import sys
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_KS_FID = "MOCK_REST"
_KS_ROUNDS = 2
_KS_RUN = "%d-%d" % (os.getpid(), int(time.time()))
ctx = bootstrap_probe(_KS_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

_KS_CASES = [
    ("无成本卡", "罗氏虾卖得怎么样"),
    ("有成本卡", "鲈鱼卖得怎么样"),
]


def _clear() -> list:
    done = []
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        fn = getattr(ri, n, None)
        done.append(n if fn else "%s(⛔不存在)" % n)
        if fn:
            fn()
    return done


def _kpi(kpis, title):
    for k in (kpis or []):
        if isinstance(k, dict) and k.get("title") == title:
            return k
    return None


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s 轮数=%d RUN=%s"
          % (ctx.db_name, _KS_FID, _KS_ROUNDS, _KS_RUN))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2

    real = rr._build_margin_entries
    seen = []

    def spy(pos_rows, cretas_map, cost_map):
        caller = sys._getframe(1).f_locals
        seen.append({
            "dish_candidates": list(caller.get("dish_candidates") or []),
            "dish_candidate": caller.get("dish_candidate"),
            "dish_scope_row": (caller.get("dish_scope_row") or {}).get("dish_name")
            if caller.get("dish_scope_row") else None,
            "n_pos_rows": len(pos_rows or []),
            "n_cost_map": len(cost_map or {}),
        })
        return real(pos_rows, cretas_map, cost_map)

    rr._build_margin_entries = spy
    n_unavailable = 0
    rows = []
    try:
        for rnd in range(1, _KS_ROUNDS + 1):
            for tag, q in _KS_CASES:
                cleared = _clear()
                set_factory_id(_KS_FID)
                seen.clear()
                skey = "ks-%s-r%d-%s" % (_KS_RUN, rnd, q)
                async with rr.dish_catalogue_scope(pool, _KS_FID):
                    spec = await ri.parse_restaurant_query(
                        q, pool, factory_id=_KS_FID, session_key=skey,
                        semantic_first=True)
                    res = None
                    if spec is not None and svc.should_delegate(spec, None, query=q):
                        res = await svc.tiered_answer(
                            q, pool, _KS_FID, ctx.role,
                            precomputed_spec=spec, session_key=skey, history=[])
                res = res or {}
                authority = getattr(spec, "planner_authority", None) if spec else None
                if authority == "llm_unavailable":
                    n_unavailable += 1
                gp = _kpi(res.get("kpis"), "总毛利")
                snap = list(seen)
                rows.append((rnd, tag, q, authority, snap,
                             (gp or {}).get("value"), (gp or {}).get("rawValue")))
                print("\n" + "=" * 78)
                print("r%d 【%s】%s" % (rnd, tag, q))
                print("   清了: %s" % "、".join(cleared))
                print("   authority=%r" % authority)
                print("   钩到 %d 次 `_build_margin_entries` 现场:" % len(snap))
                for i, s in enumerate(snap, 1):
                    print("     #%d dish_candidates=%s  dish_candidate=%r"
                          % (i, s["dish_candidates"], s["dish_candidate"]))
                    print("        dish_scope_row=%r  len(pos_rows)=%d  len(cost_map)=%d"
                          % (s["dish_scope_row"], s["n_pos_rows"], s["n_cost_map"]))
                print("   最终 KPI 总毛利: value=%r rawValue=%r"
                      % ((gp or {}).get("value"), (gp or {}).get("rawValue")))
    finally:
        rr._build_margin_entries = real

    print("\n" + "=" * 78)
    print("阴性对照·llm_unavailable = %d 次" % n_unavailable)
    if n_unavailable:
        print("⛔ rc=2 有 fail-closed 拒答混在里面，读数作废")
        return 2
    hit = [r for r in rows if r[4]]
    print("阳性对照·钩子抓到现场 = %d/%d 轮" % (len(hit), len(rows)))
    if not hit:
        print("⛔ rc=2 钩子一次都没抓到 —— monkeypatch 打空了，读数作废")
        return 2

    print("\n🔑 逐轮并排（⛔ 不折叠）")
    # ⚠️ `_build_margin_entries` 在本模块有**两个**调用点:
    #      L5459  enriched = _build_margin_entries(pos_rows, …)   ← 我要的那个
    #      L5823  entry    = _build_margin_entries([row], …)      ← 逐行, n=1
    #    ⇒ 汇总取**第一次**，⛔ 不是最后一次（取错就永远读出 pos_rows=1，
    #      而那个 1 恰好长得像「已经过滤成单菜了」—— 一个会自我印证的错读数）。
    for r in rows:
        first = r[4][0] if r[4] else {}
        print("   r%d %-8s cand=%-24s scope=%-8r pos_rows=%-3d → KPI总毛利=%r"
              % (r[0], r[1], first.get("dish_candidates"),
                 first.get("dish_scope_row"), first.get("n_pos_rows", -1), r[6]))
    print("\n⚠️ 口径：MOCK_REST / %d 轮 / **评测题库**，⛔ 不是真实流量。" % _KS_ROUNDS)
    print("⛔ 本探针不下修法结论 —— 上面是事实。")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
