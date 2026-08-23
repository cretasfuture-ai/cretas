"""改文案之前，先问库要真相：**今天到底有什么、缺的到底是什么。**

⛔ 不从注释/文档推 —— 现行文案里那句「进货金额系统里已经有了，缺的是两次盘点」
   就是从设计意图推出来的，本探针要逐项核实它。

要回答的（每项都要有列名 + 行数）：
  ① `fact_restaurant_stocktaking` 有没有**库存金额**这一列（不是盘盈亏差额）
     —— 「期初库存 ＋ 进货 − 期末库存」这条公式的两端在不在
  ② 进货金额在哪、有多少
  ③ 今天**真的算得出来**的最接近替代是什么、是多少（领料成本 ÷ 营收）

退出码: 0=量到了 / 2=没量到（对照没过）
"""

from __future__ import annotations

import asyncio
import sys
from datetime import date, timedelta

from smartbi.scripts._probe_bootstrap import (
    RESTAURANT_TENANTS_WITH_DATA,
    bootstrap_probe,
)

CANNOT_COMPUTE_SCHEMA_TENANTS = RESTAURANT_TENANTS_WITH_DATA
CANNOT_COMPUTE_SCHEMA_DAYS = 30
CANNOT_COMPUTE_SCHEMA_BOGUS = "zzz_schema_probe_must_not_exist"

CANNOT_COMPUTE_SCHEMA_TABLES = (
    "fact_restaurant_stocktaking",
    "agg_restaurant_daily_totals",
)


def _p(*a):
    print(*a, flush=True)


async def main() -> int:
    ctx = bootstrap_probe(CANNOT_COMPUTE_SCHEMA_TENANTS[0])
    _p(f"PROBE_CTX={ctx!r}")
    pool = await ctx.pool()
    fid = CANNOT_COMPUTE_SCHEMA_TENANTS[0]
    end = date.today()
    start = end - timedelta(days=CANNOT_COMPUTE_SCHEMA_DAYS - 1)

    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", fid)

        pos = int(await conn.fetchval(
            "SELECT COUNT(*)::bigint FROM fact_pos_transaction WHERE factory_id=$1", fid) or 0)
        bogus = await conn.fetchval(
            "SELECT to_regclass($1) IS NOT NULL", CANNOT_COMPUTE_SCHEMA_BOGUS)
        _p(f"[对照] 阳性 POS={pos:,} | 阴性 bogus_exists={bogus}")
        if pos <= 0 or bogus:
            _p("PROBE_VERDICT=2 对照没过")
            return 2

        for t in CANNOT_COMPUTE_SCHEMA_TABLES:
            cols = await conn.fetch(
                """
                SELECT column_name, data_type
                  FROM information_schema.columns
                 WHERE table_schema='public' AND table_name=$1
                 ORDER BY ordinal_position
                """, t)
            _p(f"\n### {t} 全部列（{len(cols)} 列, ⛔ 不加 head）")
            for c in cols:
                _p(f"    {c['column_name']:38s} {c['data_type']}")

        _p(f"\n### ③ 今天真的算得出来的数（{fid}, {start}~{end}）")
        row = await conn.fetchrow(
            """
            WITH rev AS (
                SELECT COALESCE(SUM(net_amount),0)::float AS amount,
                       COUNT(*)::bigint AS n
                  FROM fact_pos_transaction
                 WHERE factory_id=$1 AND date >= $2 AND date <= $3
            ), req AS (
                SELECT COALESCE(SUM(requisition_cost_total),0)::float AS cost,
                       COUNT(*) FILTER (WHERE requisition_cost_total IS NOT NULL)::bigint AS days,
                       MIN(date) AS d0, MAX(date) AS d1
                  FROM agg_restaurant_daily_totals
                 WHERE factory_id=$1 AND date >= $2 AND date <= $3
            )
            SELECT rev.amount AS revenue, rev.n AS txn,
                   req.cost AS req_cost, req.days AS req_days,
                   req.d0, req.d1
              FROM rev CROSS JOIN req
            """, fid, start, end)
        rev = float(row["revenue"] or 0)
        rc = float(row["req_cost"] or 0)
        _p(f"  营收 ¥{rev:,.2f}（{row['txn']:,} 单）")
        _p(f"  领料成本 ¥{rc:,.2f}（{row['req_days']} 天有数, {row['d0']}~{row['d1']}）")
        _p(f"  ⇒ 领料成本 ÷ 营收 = {rc / rev * 100:.2f}%" if rev else "  ⇒ 营收为 0，比不了")

        # 「进货金额系统里已经有了」—— 核实这句
        _p("\n### 核实现行文案那句「进货金额系统里已经有了」")
        for kind in ("purchase_amount", "purchase_cost", "inbound_amount"):
            n = await conn.fetchval(
                "SELECT COUNT(*)::bigint FROM agg_restaurant_daily_ops "
                "WHERE factory_id=$1 AND kpi_kind=$2", fid, kind)
            _p(f"    agg_restaurant_daily_ops kpi_kind={kind!r}: {n} 行")
        kinds = await conn.fetch(
            "SELECT kpi_kind, COUNT(*)::bigint AS n FROM agg_restaurant_daily_ops "
            "WHERE factory_id=$1 GROUP BY kpi_kind ORDER BY kpi_kind", fid)
        _p(f"    该租户实际有的 kpi_kind（全部 {len(kinds)} 种, ⛔ 不加 head）:")
        for k in kinds:
            _p(f"      {k['kpi_kind']:40s} {k['n']:,}")

    _p("\nPROBE_VERDICT=0 量到了")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
