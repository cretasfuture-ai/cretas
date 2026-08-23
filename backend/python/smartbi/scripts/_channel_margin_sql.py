# -*- coding: utf-8 -*-
"""真跑一次：按渠道的毛利算不算得出来，**以及那个数站不站得住**。

## 为什么不能只看「连得上」

`_channel_margin_feasible` 已经证明连接成立：
`fact_pos_item` → `fact_pos_transaction`（`order_type` / `channel_origin`）
→ `agg_restaurant_product_cost`（单位成本）。

⛔ **连得上 ≠ 算出来的数站得住。** 本仓在毛利口径上踩过多次：
成本卡覆盖率在真实租户是 **22%~61%**，而 MOCK_REST 是 100% ——
▎「干净的样本让每一道闸都绿，而你分不清是产品对了还是闸不可能红」。

⇒ 本探针把**覆盖率**和**毛利**一起算出来，⛔ 不只报毛利。

## 三格（缺一格都不能下结论）

    ① 各渠道的营收 / 单量        —— 与现有 CHANNEL_MIX 对得上吗
    ② 各渠道**有成本卡**的那部分营收占比（覆盖率）
    ③ 各渠道毛利 —— **只在覆盖额上算**，⛔ 不拿全额营收当分母

⚠️ 第 ③ 格的分母必须是**覆盖额**：本仓记过一次「分子用实收、分母用全额」
   导致毛利率报到 88.3%。
"""
from __future__ import annotations

import asyncio

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_CS_FID = "MOCK_REST"
ctx = bootstrap_probe(_CS_FID)

from smartbi.tenant_ctx import set_factory_id  # noqa: E402

_CS_SQL = """
WITH win AS (
    SELECT (MAX(date) - INTERVAL '29 day')::date AS d0, MAX(date) AS d1
      FROM fact_pos_transaction WHERE factory_id = $1
), it AS (
    SELECT COALESCE(NULLIF(t.order_type, ''), '(未标)') AS channel,
           i.product_id,
           i.qty,
           i.amount,
           c.food_cost AS unit_cost
      FROM fact_pos_item i
      -- ⚠️ `fact_pos_transaction` 的主键是 `id`，⛔ 不是 `transaction_id`。
      --    第一版我猜了列名，SQL 当场 UndefinedColumn —— 而那**也是读数**：
      --    ▎连接不成立时，先查列名，⛔ 不改判据去迁就猜错的名字。
      JOIN fact_pos_transaction t ON t.id = i.transaction_id
                                 AND t.factory_id = i.factory_id
      JOIN win ON t.date BETWEEN win.d0 AND win.d1
      -- 🔴 权威键是 `product_id`（两侧都是 bigint）。
      --    我先后猜错三次: `t.transaction_id`(不存在) /
      --    `c.product_source_pk = i.product_id`(varchar = bigint) /
      --    `c.unit_cost`(这一列根本没有, 真名是 `food_cost`)。
      -- ⚠️ 把 memory 里「连接两侧要用权威键 product_source_pk = product_types.id」
      --    那条**跨到 Java 侧**的纪律, 误用到了库内 —— 库内的权威键是 product_id。
      LEFT JOIN agg_restaurant_product_cost c
             ON c.factory_id = i.factory_id
            AND c.product_id = i.product_id
     WHERE i.factory_id = $1
)
SELECT channel,
       count(*)                                              AS lines,
       sum(amount)                                           AS revenue,
       sum(amount) FILTER (WHERE unit_cost IS NOT NULL)      AS covered_revenue,
       sum(qty * unit_cost) FILTER (WHERE unit_cost IS NOT NULL) AS covered_cost
  FROM it
 GROUP BY channel
 ORDER BY revenue DESC NULLS LAST
"""


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s" % (ctx.db_name, _CS_FID))
    set_factory_id(_CS_FID)
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", _CS_FID)
            got = await conn.fetchval("SELECT current_setting('app.factory_id')")
            print("会话上下文 app.factory_id=%s（⛔ 不设的话 RLS 恒 0 行）" % got)
            try:
                rows = await conn.fetch(_CS_SQL, _CS_FID)
            except Exception as exc:
                failure = exc
                rows = None
            else:
                failure = None

    if failure is not None:
        print("⛔ rc=2 SQL 跑不动: %r" % failure)
        print("   ⇒ 那本身是读数：这条连接**在这个库上不成立**。")
        # 🔴 让仪器自己回答「为什么不成立」——⛔ 不要人来回猜列名/类型。
        #    我已经猜错两次（`transaction_id` 不存在 / 类型不匹配），每次一个来回。
        # ⚠️ **诊断查询必须在失败的那个事务之外**：第一版把它写在 `except` 里、
        #    还在同一个 `conn.transaction()` 内，当场 `InFailedSQLTransactionError`
        #    —— ▎自愈的仪器自己坏在同一个事务里。
        async with pool.acquire() as conn2:
            cols = await conn2.fetch(
                """
                SELECT table_name, column_name, data_type
                  FROM information_schema.columns
                 WHERE (table_name, column_name) IN (
                        ('fact_pos_transaction', 'id'),
                        ('fact_pos_item', 'transaction_id'),
                        ('fact_pos_item', 'product_id'),
                        ('agg_restaurant_product_cost', 'product_source_pk'),
                        ('agg_restaurant_product_cost', 'unit_cost'))
                 ORDER BY table_name, column_name
                """)
        print("\n   连接列的实际类型:")
        for c in cols:
            print("     %-32s %-22s %s"
                  % (c["table_name"], c["column_name"], c["data_type"]))
        # ⚠️ 上面那张表只回答「我点名的那几列」。列名猜错时它是**沉默**的
        #    （`unit_cost` 就这么消失了一轮）——⇒ 把整张表的列一次打全。
        #    ▎**问「这几列对不对」之前，先问「这张表有哪些列」。**
        async with pool.acquire() as conn3:
            full = await conn3.fetch(
                """
                SELECT table_name,
                       string_agg(column_name || ':' || data_type, ', '
                                  ORDER BY ordinal_position) AS cols
                  FROM information_schema.columns
                 WHERE table_name IN ('agg_restaurant_product_cost', 'dim_product')
                 GROUP BY 1 ORDER BY 1
                """)
        print("\n   相关表的**全部**列:")
        for f in full:
            print("     [%s]\n       %s" % (f["table_name"], f["cols"]))
        return 2

    if not rows:
        print("⛔ rc=2 一行都没有 —— 窗口内没数据或连接不成立，读数作废")
        return 2

    # 🔴 阳性对照（第一版**没配**，当场读出「覆盖率 0.0%」而我差点信了）:
    #    MOCK_REST 被本仓登记为**成本卡覆盖率 100%**，产品自己也算得出毛利。
    #    ⇒ 0% 只可能是**我的连接不对**，⛔ 不是「数据没有」。
    # ▎会读出「全 0」的仪器必须带阳性对照 —— 否则分不清「真的没有」和「仪器坏了」。
    async with pool.acquire() as c4:
        async with c4.transaction():
            await c4.execute(
                "SELECT set_config('app.factory_id', $1, false)", _CS_FID)
            cost_rows = await c4.fetchval(
                "SELECT count(*) FROM agg_restaurant_product_cost WHERE factory_id = $1",
                _CS_FID)
            cost_pids = await c4.fetchval(
                "SELECT count(DISTINCT product_id) FROM agg_restaurant_product_cost"
                " WHERE factory_id = $1", _CS_FID)
            item_pids = await c4.fetchval(
                "SELECT count(DISTINCT product_id) FROM fact_pos_item WHERE factory_id = $1",
                _CS_FID)
            both = await c4.fetchval(
                """
                SELECT count(*) FROM (
                    SELECT DISTINCT product_id FROM fact_pos_item WHERE factory_id = $1
                    INTERSECT
                    SELECT DISTINCT product_id FROM agg_restaurant_product_cost
                     WHERE factory_id = $1
                ) x
                """, _CS_FID)
            sample = await c4.fetch(
                "SELECT product_id, food_cost, product_source_pk"
                "  FROM agg_restaurant_product_cost WHERE factory_id = $1 LIMIT 3",
                _CS_FID)
    print("\n阳性对照·成本卡这一侧:")
    print("   成本卡行数 = %s   其中不同 product_id = %s" % (cost_rows, cost_pids))
    print("   POS 明细里的不同 product_id = %s" % item_pids)
    print("   两侧 product_id **交集** = %s   ← 0 就说明连接键选错了" % both)
    for s in sample:
        print("   样本: product_id=%r food_cost=%r product_source_pk=%r"
              % (s["product_id"], s["food_cost"], s["product_source_pk"]))
    if cost_rows and not both:
        print("\n⛔ rc=2 成本卡**有行**但两侧 product_id 零交集 ——")
        print("   ⇒ 连接键选错了（⛔ 不是「这个租户没成本卡」）。本次覆盖率读数作废。")
        return 2

    print("\n%-10s %8s %14s %14s %10s %14s %8s"
          % ("渠道", "行数", "营收", "覆盖额营收", "覆盖率", "覆盖额毛利", "毛利率"))
    total_cov = total_rev = 0.0
    for r in rows:
        rev = float(r["revenue"] or 0)
        cov = float(r["covered_revenue"] or 0)
        cost = float(r["covered_cost"] or 0)
        rate = (cov / rev * 100) if rev else 0.0
        margin = cov - cost
        # ⚠️ 分母是**覆盖额**，⛔ 不是全额营收（本仓记过 88.3% 那次事故）
        mrate = (margin / cov * 100) if cov else 0.0
        total_rev += rev
        total_cov += cov
        print("%-10s %8d %14.2f %14.2f %9.1f%% %14.2f %7.1f%%"
              % (r["channel"], r["lines"], rev, cov, rate, margin, mrate))

    print("\n合计营收 %.2f / 覆盖额 %.2f ⇒ 总覆盖率 %.1f%%"
          % (total_rev, total_cov, (total_cov / total_rev * 100) if total_rev else 0))
    print("\n⚠️ 口径：MOCK_REST。本仓已登记它是**干净样本**（成本卡覆盖率 100%），")
    print("   ⛔ 「算得对不对」要在脏样本上验 —— 而脏样本今天的 `dim_store` 里没有。")
    print("⛔ 本探针不下「该不该做」的结论 —— 上面是事实。")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
