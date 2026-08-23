# -*- coding: utf-8 -*-
"""能力表该不该给 `INVENTORY_WARNING` 补 `store` —— 依据必须是「**它真能出**」。

## 背景

全景重测 Q09「哪家店缺货最严重」老板拿到（prod / MOCK_REST / 2/2 轮逐字相同）：

    我不确定你要看的是哪一层的数，所以这次我没敢算。按食材我能算，但你这句还要求
    再按门店拆一层，这次选中的算法拆不出这一层。

而 `resolve_stock_shortage` 里**早就有**按门店的那一段（2026-08-17 加的，
`kpi_kind = 'stocktaking_shortage_cost_by_store'` JOIN `dim_store`，
渲染成「各门店盘亏金额」）—— 只是 `_RESOLVER_DIMENSIONS` 里没登记 `store`。

⇒ 与 2026-08-17 给 `CHANNEL_MIX` 补 store、2026-08-18 给 `DAYPART_PERFORMANCE`
  补 store 是**同一天同一个人同一个形状**的第三例：补了两个，漏了第三个。
  ▎硬约束 8：改共享结构前先数，改完再数，两个数必须相等。

## 但补之前必须先证「它真能出」

本仓给能力表补维度只有一条依据：**prod 上真的出得来**。
⛔ 不是「代码里有那段 SQL」——那段 SQL 完全可能读一个空的 kpi_kind。
（`_RESOLVER_DIMENSIONS` 的注释自己写着：「声明的是真能出的粒度，
  不是希望它能出的」「写宽了下游会拿它当承诺」。）

## 阳性/阴性对照（⛔ 不许省）

- 阳性：同一张表上一个**已知有数**的 kpi_kind 必须读出非 0
        （否则「按门店的盘亏没有数」和「我连不上/被 RLS 挡了」是同一个读数）
- 阴性：一个**必然不存在**的 kpi_kind 必须读出 0
"""
from __future__ import annotations

import asyncio

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_INVEV_FID = "MOCK_REST"
ctx = bootstrap_probe(_INVEV_FID)

from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: resolver 真正读的那个键（`restaurant_ops_router.resolve_stock_shortage` 里）。
_INVEV_STORE_KPI = "stocktaking_shortage_cost_by_store"
#: 同一张表上的食材粒度键 —— 阳性对照。
_INVEV_ING_KPI = "stocktaking_shortage_cost"
#: 必然不存在 —— 阴性对照。
_INVEV_FAKE_KPI = "__zzz_not_a_real_kpi_kind__"


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s" % (ctx.db_name, _INVEV_FID))
    set_factory_id(_INVEV_FID)

    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", _INVEV_FID)
            got = await conn.fetchval("SELECT current_setting('app.factory_id', true)")
            print("RLS 上下文 = %s  %s" % (got, "✅" if got == _INVEV_FID else "🔴 没生效"))
            if got != _INVEV_FID:
                print("⛔ rc=2 会话上下文没生效，读数作废")
                return 2

            counts = {}
            for kpi in (_INVEV_STORE_KPI, _INVEV_ING_KPI, _INVEV_FAKE_KPI):
                counts[kpi] = await conn.fetchval(
                    "SELECT count(*) FROM agg_restaurant_daily_ops "
                    "WHERE factory_id = $1 AND kpi_kind = $2", _INVEV_FID, kpi)

            # 真跑一次 resolver 那条按门店的 SQL（⛔ 不另写一份口径）
            store_rows = await conn.fetch(
                """
                SELECT s.name AS store_name, SUM(a.value_num)::float AS cost,
                       count(*) AS n_days
                  FROM agg_restaurant_daily_ops a
                  JOIN dim_store s ON s.store_id = a.dim_value_id
                                  AND s.factory_id = a.factory_id
                 WHERE a.factory_id = $1
                   AND a.kpi_kind = $2
                   AND a.date >= CURRENT_DATE - 30
                 GROUP BY s.name
                 ORDER BY cost DESC NULLS LAST
                 LIMIT 20
                """, _INVEV_FID, _INVEV_STORE_KPI)

    print("\nagg_restaurant_daily_ops 行数（本租户，全窗口）")
    for kpi, c in counts.items():
        tag = ("← 按门店（被测）" if kpi == _INVEV_STORE_KPI
               else "← 阳性对照" if kpi == _INVEV_ING_KPI else "← 阴性对照")
        print("  %-42s %-8s %s" % (kpi, c, tag))

    if counts[_INVEV_FAKE_KPI] != 0:
        print("⛔ rc=2 阴性对照读出非 0 —— 我的查询在乱数，读数作废")
        return 2
    if not counts[_INVEV_ING_KPI]:
        print("⛔ rc=2 阳性对照（食材粒度）也是 0 —— "
              "分不清「按门店没数」和「整张表被挡住/是空的」，读数作废")
        return 2

    print("\n真跑 resolver 那条按门店 SQL（最近 30 天）: %d 家店" % len(store_rows))
    for r in store_rows:
        print("  %-24s ¥%.2f  （%d 天有记录）"
              % (r["store_name"], r["cost"] or 0, r["n_days"]))

    print("\n判定")
    if store_rows:
        print("  ✅ **它真能出** —— %d 家店有按门店的盘亏金额 ⇒ "
              "能力表该给 INVENTORY_WARNING 补 `store`。" % len(store_rows))
        print("  ⛔ 依据是这份读数，不是「代码里有那段 SQL」。")
        return 0
    print("  🔴 按门店那一路**出不来**（%d 行 kpi / 0 家店）⇒ ⛔ 不许补能力表。"
          % counts[_INVEV_STORE_KPI])
    print("     该走「部分能做」：按食材的先给，再说清另一半为什么不能。")
    return 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
