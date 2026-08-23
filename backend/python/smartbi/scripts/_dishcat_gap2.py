# -*- coding: utf-8 -*-
"""两个待证事实，决定 ②③ 怎么落地：

  (a) 菜**在菜单里**、只是这个窗口一份没卖 ⇒ 老板今天看到的是不是
      「没有找到名为「X」的菜品」？（那是一条**经不起他去查**的提示 ——
       他去菜单一看明明有）
  (b) 简称唯一命中一道菜时，抬头用的是**他打的字**还是**库里的全名**？

⛔ 两条都不从代码推断，跑一次让它自己说。
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
_HELPERS = ("clear_semantic_plan_cache", "clear_route_cache",
            "clear_tenant_gate_cache", "clear_promoted_routes_cache")


def clear_caches():
    for name in _HELPERS:
        getattr(ri, name)()


async def facts(pool):
    """dim_product 全量 vs 最近30天真有销量的菜 —— 差集就是 (a) 的样本源。"""
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", FID)
        allp = await conn.fetch(
            "SELECT product_id, name, normalized_name FROM dim_product "
            "WHERE factory_id = $1 ORDER BY name", FID)
        sold = await conn.fetch(
            """
            WITH anchor AS (SELECT MAX(date) AS d FROM fact_pos_transaction
                             WHERE factory_id = $1)
            SELECT DISTINCT p.name
              FROM fact_pos_item i
              JOIN fact_pos_transaction t ON t.id = i.transaction_id
              JOIN dim_product p ON p.product_id = i.product_id
              CROSS JOIN anchor
             WHERE i.factory_id = $1 AND t.factory_id = $1 AND p.factory_id = $1
               AND t.date >= anchor.d - 30 AND t.date <= anchor.d
            """, FID)
    names = [r["name"] for r in allp]
    sold_names = {r["name"] for r in sold}
    print("dim_product 全量 %d 道: %s" % (len(names), names))
    print("最近30天有销量 %d 道: %s" % (len(sold_names), sorted(sold_names)))
    zero = [n for n in names if n not in sold_names]
    print("🔎 在菜单里、最近30天零销量: %s" % (zero or "（无）"))
    return names, zero


async def turn(pool, q, skey):
    set_factory_id(FID)
    clear_caches()
    async with rr.dish_catalogue_scope(pool, FID):
        spec = await ri.parse_restaurant_query(
            q, pool, factory_id=FID, session_key=skey, semantic_first=True)
    res = await tiered_answer(q, pool, FID, ctx.role, precomputed_spec=spec,
                              session_key=skey)
    return spec, res or {}


async def main() -> int:
    pool = await ctx.pool()
    if getattr(ctx, "llm_dead_slots", None):
        print("rc=2 LLM 死槽: %s" % (ctx.llm_dead_slots,))
        return 2
    names, zero = await facts(pool)
    if not names:
        print("rc=2 dim_product 空")
        return 2

    probes = []
    # (a) 零销量的菜（有就用真的；没有就用一个极窄窗口制造同样的形状）
    for n in zero[:2]:
        probes.append(("a-零销量", "%s最近30天卖得怎么样" % n))
    probes.append(("a-窄窗", "水煮牛肉昨天卖得怎么样"))
    # (b) 简称 —— 全名的真子串，唯一指向一道菜
    for full, short in (("凉拌木耳", "木耳"), ("干锅花菜", "花菜"),
                        ("水煮牛肉", "牛肉")):
        if full in names:
            probes.append(("b-简称->%s" % full, "%s这个月卖得怎么样" % short))
    # 对照：菜单里真没有
    probes.append(("对照-不存在", "香辣蟹煲这个月卖得怎么样"))

    for tag, q in probes:
        spec, res = await turn(pool, q, "g2-" + tag)
        text = res.get("answer_text") or ""
        print("\n" + "=" * 88)
        print("[%s] %r" % (tag, q))
        print("  dish_slot=%r  kind=%s  code=%s  title=%r"
              % (getattr(spec, "dish_slot", None), res.get("kind"),
                 res.get("code"), res.get("title")))
        for line in text.splitlines()[:10]:
            print("  | " + line)
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
