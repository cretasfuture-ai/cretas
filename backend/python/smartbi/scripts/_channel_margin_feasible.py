# -*- coding: utf-8 -*-
"""按渠道算毛利，数据上做不做得到 —— ⛔ 先问数据，再谈实现。

## 起点

`CHANNEL_MIX` 能给按渠道的**营收 / 单量**，给不了**毛利**。
老板问毛利拿到营收表（已修成会说一句），但**能力本身**还在挂着。

⇒ 补这个能力要两样东西同时在：
    ① 渠道这一维（现在营收表按渠道拆得出来 ⇒ 它一定在某处）
    ② 成本这一列，且**能连到渠道上**

⛔ 本探针只回答「数据在不在、连不连得上」，⛔ 不谈实现。

## ⚠️ 查表一律带 schema 限定

本仓记过一次：`pg_stat_user_tables` 不带 schema 过滤，把归档 schema 的行数
读成了主表的（`production_reports` 读成 3 而 `public` 里是 0）。
⇒ 这里逐 schema 列出来，⛔ 不假设只有一个。
"""
from __future__ import annotations

import asyncio

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_CM_FID = "MOCK_REST"
ctx = bootstrap_probe(_CM_FID)

from smartbi.tenant_ctx import set_factory_id  # noqa: E402

_CM_TABLES = ("fact_pos_transaction", "fact_pos_item",
              "agg_restaurant_product_cost", "dim_product", "product_types")


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s" % ctx.db_name)
    set_factory_id(_CM_FID)

    async with pool.acquire() as conn:
        print("\n=== ① 这几张表各在哪个 schema（⛔ 不假设只有一个）===")
        rows = await conn.fetch(
            """
            SELECT table_schema, table_name, count(*) AS cols
              FROM information_schema.columns
             WHERE table_name = ANY($1::text[])
             GROUP BY 1, 2 ORDER BY 2, 1
            """, list(_CM_TABLES))
        for r in rows:
            print("   %-34s %-30s %d 列"
                  % (r["table_schema"] + "." + r["table_name"],
                     "", r["cols"]))
        if not rows:
            print("   ⛔ rc=2 一张都没找到 —— 连库或 schema 不对，读数作废")
            return 2

        print("\n=== ② 渠道这一维在哪一列（全库扫列名）===")
        chan = await conn.fetch(
            """
            SELECT table_schema, table_name, column_name, data_type
              FROM information_schema.columns
             WHERE column_name ~* '(channel|order_type|渠道|source_type|platform)'
               AND table_schema NOT IN ('pg_catalog', 'information_schema')
             ORDER BY table_name, column_name
            """)
        for r in chan:
            print("   %-46s %-22s %s"
                  % (r["table_schema"] + "." + r["table_name"],
                     r["column_name"], r["data_type"]))
        if not chan:
            print("   ⛔ 一列都没有 —— 而营收表**确实**按渠道拆出来了，"
                  "说明渠道是**算出来的**不是存的。逐条看下面的 ③")

        print("\n=== ③ 成本这一维在哪一列 ===")
        cost = await conn.fetch(
            """
            SELECT table_schema, table_name, column_name
              FROM information_schema.columns
             WHERE column_name ~* '(cost|成本)'
               AND table_schema NOT IN ('pg_catalog', 'information_schema')
             ORDER BY table_name, column_name
            """)
        for r in cost[:24]:
            print("   %-46s %s"
                  % (r["table_schema"] + "." + r["table_name"], r["column_name"]))
        print("   （共 %d 列）" % len(cost))

        print("\n=== ④ `fact_pos_transaction` 的全部列（渠道到底怎么来的）===")
        cols = await conn.fetch(
            """
            SELECT table_schema, string_agg(column_name, ', '
                                            ORDER BY ordinal_position) AS cols
              FROM information_schema.columns
             WHERE table_name = 'fact_pos_transaction'
             GROUP BY 1
            """)
        for r in cols:
            print("   [%s] %s" % (r["table_schema"], r["cols"]))

    print("\n🔑 判定要人读上面四格 —— ⛔ 本探针不下结论。")
    print("   关键问题：**渠道是存的还是算出来的**；成本能不能连到订单上。")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
