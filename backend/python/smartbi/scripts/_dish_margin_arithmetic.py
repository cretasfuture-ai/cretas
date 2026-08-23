# -*- coding: utf-8 -*-
"""点名单菜时那条毛利算式的每一格 —— 逐格贴出来。

## 为什么要这支

`_kpi_scope_trace` 实测**推翻**了我自己的候选成因（4/4 两侧 `pos_rows=1`、
`dish_scope_row` 都落上了 ⇒ 单菜过滤**生效了**），而鲈鱼**单行**却算出
`总毛利 = ¥19,766,090`，是它自己表内毛利 `¥2,894,006.16` 的 **6.8 倍**。

⇒ 读码给出的新候选（`restaurant_ops_router.py:5490~5514`）：

```python
total_paid_rev = await _paid_revenue_in_window(pool, factory_id, w0, w1)  # ← 全店
if total_paid_rev is not None and total_rev_items > 0:
    _discount      = total_rev_items - total_paid_rev      # ← 单菜 − 全店 = **负数**
    _share         = total_rev_with_cost / total_rev_items
    covered_net_rev = total_rev_with_cost - _discount * _share   # ← 减掉负数 = 膨胀
total_profit = covered_net_rev - total_cost
```

▎`_paid_revenue_in_window` **按窗口取全店实收，⛔ 不按菜品过滤**。
▎点名单菜时 `total_rev_items` 只剩那一道 ⇒ 「折扣」被算成一个巨大的负数。

⚠️ `margin_invariant_pass`（`total_profit <= covered_net_rev`）**拦不住** ——
分子分母一起膨胀，闸的两边同向移动。形态 B⁴：闸没坏，是这种输入让它恒真。

## 本探针量什么（⛔ 只量事实，逐格）

钩在算式现场取：`total_rev_items` / `total_rev_with_cost` / `total_paid_rev`
/ `_discount` / `_share` / `covered_net_rev` / `total_cost` / `total_profit`，
并与**表内那道菜自己的毛利**并排。

## 阳性/阴性对照

- **阳性**：一句**不点名单菜**的问句（「各个菜的毛利怎么样」）——
  它的 `total_rev_items` 应该是全菜品，`_discount` 应该 **>= 0**。
  读不到这个对照 ⇒ 说明钩子或口径判断本身有问题。
  ▎⛔ 没有它就分不清「负折扣是单菜特有的」还是「这条路一直是负的」。
- **阴性**：`spec.planner_authority == 'llm_unavailable'` 必须为 0，非 0 ⇒ rc=2
"""
from __future__ import annotations

import asyncio
import os
import sys
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_DA_FID = "MOCK_REST"
_DA_ROUNDS = 2
_DA_RUN = "%d-%d" % (os.getpid(), int(time.time()))
ctx = bootstrap_probe(_DA_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

_DA_CASES = [
    ("被测·点名单菜(有卡)", "鲈鱼卖得怎么样"),
    ("被测·点名单菜(无卡)", "罗氏虾卖得怎么样"),
    ("阳性对照·不点名", "各个菜的毛利怎么样"),
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


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s 轮数=%d RUN=%s"
          % (ctx.db_name, _DA_FID, _DA_ROUNDS, _DA_RUN))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2

    # 钩 `math.isfinite` 不行（到处都在用）。钩 `_paid_revenue_in_window`：
    # 它就在算式**正上方**，返回之后调用方的局部变量已经全部就位。
    real = rr._paid_revenue_in_window
    grabs = []

    async def spy(*a, **kw):
        got = await real(*a, **kw)
        f = sys._getframe(1).f_locals
        # 🔴 2026-08-19 **仪器订正**（留痕，⛔ 不删）——
        #    第一版只抓 `total_rev_items` 并**按旧公式自己推导** `_discount`。
        #    修复上线后，产品已经改用「过滤之前那份拷贝」算分母，而探针
        #    照旧推导 ⇒ 它继续报「`_discount` 为负 4/6」和 `rc=1`，
        #    ▎**读数长得和「没修好」一模一样，而 KPI 那一格已经是对的了。**
        #    （纪律 12：改完产品立刻回头问「我的尺子还量得到吗」。）
        # ⇒ 两个分母**都抓**，并排推导，让「用哪个分母」自己在读数里现形。
        bench = f.get("benchmark_pos_rows") or []
        grabs.append({
            "total_paid_rev": got,
            "total_rev_items": f.get("total_rev_items"),
            "all_items_rev": sum(float(r["total_revenue"] or 0) for r in bench),
            "n_benchmark": len(bench),
            "total_rev_with_cost": f.get("total_rev_with_cost"),
            "total_cost": f.get("total_cost"),
            "n_enriched": len(f.get("enriched") or []),
            "n_with_cost": len(f.get("with_cost") or []),
            "scope": (f.get("dish_scope_row") or {}).get("dish_name")
            if f.get("dish_scope_row") else None,
        })
        return got

    rr._paid_revenue_in_window = spy
    n_unavailable = 0
    rows = []
    try:
        for rnd in range(1, _DA_ROUNDS + 1):
            for tag, q in _DA_CASES:
                cleared = _clear()
                set_factory_id(_DA_FID)
                grabs.clear()
                skey = "da-%s-r%d-%s" % (_DA_RUN, rnd, q)
                async with rr.dish_catalogue_scope(pool, _DA_FID):
                    spec = await ri.parse_restaurant_query(
                        q, pool, factory_id=_DA_FID, session_key=skey,
                        semantic_first=True)
                    res = None
                    if spec is not None and svc.should_delegate(spec, None, query=q):
                        res = await svc.tiered_answer(
                            q, pool, _DA_FID, ctx.role,
                            precomputed_spec=spec, session_key=skey, history=[])
                res = res or {}
                authority = getattr(spec, "planner_authority", None) if spec else None
                if authority == "llm_unavailable":
                    n_unavailable += 1
                g = grabs[0] if grabs else None

                def _derive(den):
                    """按给定分母把算式跑一遍。⛔ 两个分母都跑，并排看。"""
                    if not g or g["total_paid_rev"] is None or (den or 0) <= 0:
                        return None
                    disc = den - g["total_paid_rev"]
                    share = g["total_rev_with_cost"] / den
                    cnr = g["total_rev_with_cost"] - disc * share
                    return {"_discount": disc, "_share": share,
                            "covered_net_rev": cnr,
                            "total_profit": cnr - (g["total_cost"] or 0)}

                # 🔑 `derived` = **现行公式**（分母 = 过滤之前那份拷贝）
                #    `derived_old` = 旧公式（分母 = 被削成单菜的那份），只作对照
                derived = _derive(g["all_items_rev"]) if g else None
                derived_old = _derive(g["total_rev_items"]) if g else None
                gp = next((k for k in (res.get("kpis") or [])
                           if isinstance(k, dict) and k.get("title") == "总毛利"), None)
                rows.append((rnd, tag, q, authority, g, derived,
                             (gp or {}).get("rawValue"), derived_old))

                print("\n" + "=" * 78)
                print("r%d 【%s】%s" % (rnd, tag, q))
                print("   清了: %s" % "、".join(cleared))
                print("   authority=%r  钩到 %d 次" % (authority, len(grabs)))
                if g is None:
                    print("   ⚠️ 钩子没抓到 —— 这一轮没走到那条算式")
                else:
                    print("   dish_scope=%r  enriched=%d  with_cost=%d"
                          % (g["scope"], g["n_enriched"], g["n_with_cost"]))
                    print("   total_rev_items(被削成单菜) = %s" % g["total_rev_items"])
                    print("   all_items_rev  (过滤前拷贝) = %s  (%d 行)"
                          % (g["all_items_rev"], g["n_benchmark"]))
                    print("   total_rev_with_cost = %s" % g["total_rev_with_cost"])
                    print("   total_paid_rev(全店) = %s" % g["total_paid_rev"])
                    print("   total_cost          = %s" % g["total_cost"])
                    if derived:
                        print("   ── 算式逐格（**现行公式**：分母=过滤前那份） ──")
                        print("   _discount = all_items − paid = %s   %s"
                              % (round(derived["_discount"], 2),
                                 "🔴 **负数**" if derived["_discount"] < 0 else ""))
                        print("   _share            = %.4f" % derived["_share"])
                        print("   covered_net_rev   = %s" % round(derived["covered_net_rev"], 2))
                        print("   total_profit      = %s" % round(derived["total_profit"], 2))
                    if derived_old:
                        print("   ── 对照：旧公式（分母=被削成单菜的那份） ──")
                        print("   _discount = %s   total_profit = %s"
                              % (round(derived_old["_discount"], 2),
                                 round(derived_old["total_profit"], 2)))
                print("   最终 KPI 总毛利 rawValue = %r" % ((gp or {}).get("rawValue")))
    finally:
        rr._paid_revenue_in_window = real

    print("\n" + "=" * 78)
    print("阴性对照·llm_unavailable = %d 次" % n_unavailable)
    if n_unavailable:
        print("⛔ rc=2 有 fail-closed 拒答混在里面，读数作废")
        return 2

    ctrl = [r for r in rows if r[1].startswith("阳性") and r[5]]
    print("阳性对照·不点名那一句抓到算式 = %d 轮" % len(ctrl))
    if not ctrl:
        print("⛔ rc=2 阳性对照一次都没抓到 —— 分不清「负折扣是单菜特有的」")
        print("   还是「这条路一直是负的」。读数作废。")
        return 2
    ctrl_neg = [r for r in ctrl if r[5]["_discount"] < 0]
    print("   其中 _discount < 0 的 = %d/%d（对照组应为 0）"
          % (len(ctrl_neg), len(ctrl)))

    print("\n🔑 逐轮并排（⛔ 不折叠）")
    for r in rows:
        d, old = r[5] or {}, r[7] or {}
        print("   r%d %-22s scope=%-6r all_items=%-14s paid(全店)=%-14s "
              "_discount=%-14s KPI总毛利=%s"
              % (r[0], r[1], (r[4] or {}).get("scope"),
                 (r[4] or {}).get("all_items_rev"),
                 (r[4] or {}).get("total_paid_rev"),
                 (round(d["_discount"], 2) if d else None), r[6]))
        if old:
            print("        对照·旧公式 _discount=%-14s total_profit=%s"
                  % (round(old["_discount"], 2), round(old["total_profit"], 2)))

    bad = [r for r in rows if r[5] and r[5]["_discount"] < 0]
    old_bad = [r for r in rows if r[7] and r[7]["_discount"] < 0]
    print("\n🔑 **现行公式** `_discount` 为负 = %d/%d 轮（应为 0）" % (len(bad), len(rows)))
    print("🔑 对照·旧公式 `_discount` 为负 = %d/%d 轮" % (len(old_bad), len(rows)))
    print("   ▎旧公式那一列**仍然会是负的** —— 它是被修掉的那个东西，")
    print("   ▎留着它是为了让「修好了」有一个可见的对照，⛔ 不是缺陷读数。")
    # 🔑 KPI 是不是真的用了现行公式 —— 两个读数并排才判得了（⛔ 单看一个不行）
    mismatched = [
        r for r in rows
        if r[5] and r[6] is not None
        and abs(float(r[6]) - r[5]["total_profit"]) > 0.01
    ]
    print("\n🔑 KPI 总毛利与**现行公式**对不上 = %d/%d 轮（应为 0）"
          % (len(mismatched), len(rows)))
    for r in mismatched:
        print("     r%d %s: KPI=%s 现行公式=%s"
              % (r[0], r[1], r[6], round(r[5]["total_profit"], 2)))
    print("\n⚠️ 口径：MOCK_REST / %d 轮 / **评测题库**，⛔ 不是真实流量。" % _DA_ROUNDS)
    print("⛔ 本探针不下修法结论 —— 上面是事实。")
    return 1 if (bad or mismatched) else 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
