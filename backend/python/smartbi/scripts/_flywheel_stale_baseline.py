"""前后对比探针 —— 「40」和「0」这两个数, 当前 API 只给得出哪一个。

⛔ 一次性诊断探针, 不进 cron。cron 用的是
   `smartbi.scripts.flywheel_promotion_staleness`(三态跑批)。

跑法(prod):
    ssh root@47.100.235.168 \
      "/tmp/runprobe.sh smartbi.scripts._flywheel_stale_baseline /tmp/fwbase.log"

每条读数都带**来源标记**(形态 A13): 这个数是从哪个函数、哪张表、哪次调用来的。
"""
from __future__ import annotations

import asyncio
import json
import sys

from smartbi.scripts._probe_bootstrap import bootstrap_probe

ctx = bootstrap_probe("MOCK_REST")  # noqa: E402 - 必须在 import 生产模块之前


async def main() -> int:
    pool = await ctx.pool()

    from smartbi.gold.restaurant import restaurant_intent as ri
    from smartbi.gold.restaurant import restaurant_intent_promotion as promo
    from smartbi.api import flywheel as fw

    print("=== 0. 仪器自检 ===")
    print(f"POSTGRES_DB = {ctx.db_name!r}")

    # ⚠️ RLS 表上「0 行」和「表是空的」长得一模一样 —— 让读数自己说清是哪一种。
    async with pool.acquire() as conn:
        rls = await conn.fetchrow(
            "SELECT relrowsecurity, relforcerowsecurity FROM pg_class "
            "WHERE relname = 'ai_promoted_routes'"
        )
    print(f"ai_promoted_routes.relrowsecurity = {rls and rls['relrowsecurity']}, "
          f"relforcerowsecurity = {rls and rls['relforcerowsecurity']}")

    # 阳性对照: 刚算出来的指纹, 过一遍判定必须**等于**当前语义段。
    # ⛔ 不打桩 plan_semantics_segment —— 那是「关掉闸来证明闸能开」。
    live_sem = ri._plan_semantics_fingerprint()
    synthetic = ri.compose_routing_fingerprint()
    positive_control = ri.plan_semantics_segment(synthetic) == live_sem
    print(f"当前语义段 live_sem      = {live_sem!r}")
    print(f"合成新指纹 synthetic     = {synthetic!r}")
    print(f"阳性对照(新写入必生效)   = {positive_control}")
    # 阴性对照: 裸 8-hex 旧格式必须判失效, 且**即使它逐字等于 live_sem**。
    negative_control = ri.plan_semantics_segment(live_sem) == ""
    print(f"阴性对照(裸8hex 必失效)  = {negative_control} "
          f"(喂进去的正是 live_sem 本身 {live_sem!r})")
    if not positive_control or not negative_control:
        print("⛔ 对照没过 —— 本次读数作废, 不要据此下任何结论")
        return 2

    print()
    print("=== 1. 修前: /overview 里 promoted_routes 长什么样 ===")
    before = await fw._read_promoted_routes_summary("restaurant")
    print("_read_promoted_routes_summary('restaurant') ->")
    print(json.dumps(before, ensure_ascii=False, indent=2, default=str))
    print(f"⇒ 数出来的字段: {sorted(before.keys())}")

    print()
    print("=== 2. 真值: 逐行数指纹 (直接读表, 不经 API) ===")
    rows = await promo.list_route_promotions(pool, domain="restaurant")
    print(f"list_route_promotions 返回 {len(rows)} 行, "
          f"每行字段 = {sorted(rows[0].keys()) if rows else '(空)'}")
    print(f"⇒ 注意: 返回里有没有 routing_fingerprint = "
          f"{'routing_fingerprint' in (rows[0] if rows else {})}")

    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, true)", "__internal__")
            raw = await conn.fetch(
                "SELECT normalized_phrase, routing_fingerprint, source, scope, "
                "       plan_version, created_at "
                "  FROM ai_promoted_routes WHERE domain = 'restaurant' "
                " ORDER BY normalized_phrase")

    by_fp: dict = {}
    stale = 0
    for r in raw:
        fp = r["routing_fingerprint"]
        by_fp[fp] = by_fp.get(fp, 0) + 1
        if ri.plan_semantics_segment(fp) != live_sem:
            stale += 1
    print(f"表里 domain=restaurant 共 {len(raw)} 行")
    print(f"原始指纹分布: {by_fp}")
    print(f"⇒ 指纹判失效(回放必跳过)的行数 stale = {stale}")
    print(f"⇒ 指纹判有效的行数 effective = {len(raw) - stale}")

    print()
    print("=== 3. 端到端: 回放路径真的能拿到几条 ===")
    ri.clear_promoted_routes_cache()   # 硬约束 3: 跨样本读数前清缓存
    routes = await ri._load_promoted_routes(pool, "MOCK_REST", domain="restaurant")
    print(f"_load_promoted_routes(MOCK_REST) 生效条数 = {len(routes)}")

    print()
    print("=== 4. 净效果 ===")
    print(f"API 会显示 route_count = {before.get('route_count')}")
    print(f"实际生效           = {len(routes)}")
    print(f"两个数是否同时出现在同一个响应里 = "
          f"{'effective_count' in before and 'stale_count' in before}")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
