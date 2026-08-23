"""prod 数据探针: 那两句「算不出来 / 比不了」到底是真是假.

⛔ 不读码下结论 —— 去查 resolver **真正读的表**在哪个库、有没有数据。

🔴 本轮开工前刚踩过的坑（写在这里，不写在记忆里）：
   拿 smartbi 池去查 `raw_material_types` 读出「表不存在」，
   据此把产品一句**真话**判成假话 —— 那两张表在 **Java 侧 `cretas_db`**。
   ▎「表不存在」和「这张表不在这个库里」是同一个读数。
   ⇒ 本探针对每张表都**先报它在哪个库里存不存在**，再报行数。

阳性 / 阴性对照（⛔ 缺一个这轮读数作废）：
  阳性: `fact_pos_transaction` 必须 > 0（MOCK_REST 已知 101,768 笔）
        —— 它读出 0 ⇒ 仪器坏（RLS / 租户上下文 / 连错库），本轮全部读数作废
  阴性: 一个**故意不存在**的表名必须报 undefined_table
        —— 它读出 0 行而不是报错 ⇒ 我分不清「空」和「不在这个库」
  ⚠️ 阳性对照要问一句「仪器坏掉时它会不会也成立」：
     连错库 / RLS 挡住 / 租户没设 —— 三种坏法都会让 POS 读出 0，所以它能红。

退出码: 0=量到了 / 1=量到了且指向缺陷 / 2=**没量到**（对照没过）
"""

from __future__ import annotations

import asyncio
import sys
from datetime import date, timedelta

from smartbi.scripts._probe_bootstrap import (
    RESTAURANT_TENANTS_WITH_DATA,
    bootstrap_probe,
)

#: 本探针关心的租户。⛔ 不在这里另写一份清单（形态 D）。
CANNOT_COMPUTE_TENANTS = RESTAURANT_TENANTS_WITH_DATA

#: resolver 默认窗口（`resolve_recipe_cost` / `resolve_supplier_price` 的 days 默认值）
CANNOT_COMPUTE_RECIPE_DAYS = 30
CANNOT_COMPUTE_SUPPLIER_DAYS = 90

#: 故意不存在的表 —— 阴性对照
CANNOT_COMPUTE_BOGUS_TABLE = "zzz_table_that_must_not_exist"

#: 在 smartbi_prod_db 侧要体检的表
CANNOT_COMPUTE_SMARTBI_TABLES = (
    "fact_pos_transaction",
    "fact_cost_line",
    "dim_cost_category",
    "agg_supplier_price",
    "agg_restaurant_product_cost",
    CANNOT_COMPUTE_BOGUS_TABLE,
)

#: 在 cretas_db（Java 侧）要体检的表 —— 供应商这一栏的**上游**
CANNOT_COMPUTE_CRETAS_TABLES = (
    "product_types",
    "material_batches",
    "suppliers",
    "purchase_orders",
    CANNOT_COMPUTE_BOGUS_TABLE,
)


def _p(*args):
    print(*args, flush=True)


async def _table_health(conn, table: str) -> dict:
    """先答「这张表在这个库里存不存在」，再答行数。⛔ 不把两者折叠成一个 0。"""
    reg = await conn.fetchrow("SELECT to_regclass($1) AS oid", table)
    if reg is None or reg["oid"] is None:
        return {"table": table, "exists": False, "rls": None, "rows": None}
    rls = await conn.fetchval(
        "SELECT relrowsecurity FROM pg_class WHERE oid = to_regclass($1)", table
    )
    schema = await conn.fetchval(
        "SELECT n.nspname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
        " WHERE c.oid = to_regclass($1)",
        table,
    )
    try:
        rows = await conn.fetchval(f'SELECT COUNT(*)::bigint FROM "{table}"')
    except Exception as exc:  # noqa: BLE001
        return {"table": table, "exists": True, "rls": rls, "schema": schema,
                "rows": None, "error": str(exc)[:120]}
    return {"table": table, "exists": True, "rls": rls, "schema": schema, "rows": int(rows)}


async def probe_smartbi(pool, fid: str) -> dict:
    out = {}
    end = date.today()
    recipe_start = end - timedelta(days=CANNOT_COMPUTE_RECIPE_DAYS - 1)
    sup_start = end - timedelta(days=CANNOT_COMPUTE_SUPPLIER_DAYS - 1)

    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", fid)
        readback = await conn.fetchval("SELECT current_setting('app.factory_id', true)")
        out["tenant_ctx_readback"] = readback

        _p(f"\n### smartbi_prod_db 表体检 (租户上下文回读={readback!r})")
        health = []
        for t in CANNOT_COMPUTE_SMARTBI_TABLES:
            h = await _table_health(conn, t)
            health.append(h)
            _p(f"  {t:34s} exists={h['exists']!s:5s} schema={h.get('schema')} "
               f"rls={h['rls']} rows_all_tenants={h['rows']}")
        out["health"] = health

        # ── 阳性对照 ────────────────────────────────────────────────────
        pos = await conn.fetchval(
            "SELECT COUNT(*)::bigint FROM fact_pos_transaction WHERE factory_id = $1", fid)
        out["positive_control_pos_rows"] = int(pos or 0)
        _p(f"\n### 阳性对照 fact_pos_transaction({fid}) 全量 = {pos:,}")

        pos_win = await conn.fetchrow(
            """
            SELECT COUNT(*)::bigint AS n, COALESCE(SUM(net_amount),0)::float AS amt,
                   MIN(date) AS d0, MAX(date) AS d1
              FROM fact_pos_transaction
             WHERE factory_id = $1 AND date >= $2 AND date <= $3
            """, fid, recipe_start, end)
        out["pos_window"] = dict(pos_win)
        _p(f"  近{CANNOT_COMPUTE_RECIPE_DAYS}天({recipe_start}~{end}): "
           f"{pos_win['n']:,} 笔 / ¥{pos_win['amt']:,.2f} / {pos_win['d0']}~{pos_win['d1']}")

        # ── ① 食材成本率：fact_cost_line ────────────────────────────────
        _p(f"\n### ① RECIPE_COST 读的 fact_cost_line({fid})")
        fcl_all = await conn.fetchrow(
            """
            SELECT COUNT(*)::bigint AS n, MIN(date) AS d0, MAX(date) AS d1
              FROM fact_cost_line WHERE factory_id = $1
            """, fid)
        out["fact_cost_line_all"] = dict(fcl_all)
        _p(f"  全量(不限窗口): {fcl_all['n']:,} 行  日期 {fcl_all['d0']} ~ {fcl_all['d1']}")

        fcl_win = await conn.fetchval(
            """
            SELECT COUNT(*)::bigint FROM fact_cost_line
             WHERE factory_id = $1 AND date >= $2 AND date <= $3
            """, fid, recipe_start, end)
        out["fact_cost_line_window"] = int(fcl_win or 0)
        _p(f"  近{CANNOT_COMPUTE_RECIPE_DAYS}天窗口: {fcl_win:,} 行")

        # 分类分布 —— resolver 的 LIKE 过滤是否把有数据的分类漏掉了?
        cats = await conn.fetch(
            """
            SELECT c.cost_type, c.name, COUNT(*)::bigint AS n,
                   SUM(f.amount)::float AS amt,
                   MIN(f.date) AS d0, MAX(f.date) AS d1
              FROM fact_cost_line f
              JOIN dim_cost_category c
                ON c.category_id = f.category_id AND c.factory_id = f.factory_id
             WHERE f.factory_id = $1
             GROUP BY c.cost_type, c.name
             ORDER BY n DESC
            """, fid)
        out["cost_categories"] = [dict(r) for r in cats]
        _p(f"  按分类分布 (全量, {len(cats)} 组):")
        for r in cats:
            _p(f"    cost_type={r['cost_type']!r:14s} name={r['name']!r:20s} "
               f"n={r['n']:,} amt=¥{(r['amt'] or 0):,.2f} {r['d0']}~{r['d1']}")

        # resolver 的**原样**过滤条件
        exact = await conn.fetchrow(
            """
            SELECT COUNT(*)::bigint AS n, SUM(f.amount)::float AS amt
              FROM fact_cost_line f
              JOIN dim_cost_category c
                ON c.category_id = f.category_id AND c.factory_id = f.factory_id
             WHERE f.factory_id = $1
               AND f.date >= $2 AND f.date <= $3
               AND c.cost_type = 'material'
               AND (c.name LIKE '%食材%' OR c.name LIKE '%原料%')
            """, fid, recipe_start, end)
        out["resolver_exact_filter"] = dict(exact)
        _p(f"  ⇒ resolver 原样过滤(material + 名字含食材/原料 + 窗口): "
           f"n={exact['n']} amt={exact['amt']}")

        # 放宽: 只要 cost_type='material'
        loose = await conn.fetchrow(
            """
            SELECT COUNT(*)::bigint AS n, SUM(f.amount)::float AS amt
              FROM fact_cost_line f
              JOIN dim_cost_category c
                ON c.category_id = f.category_id AND c.factory_id = f.factory_id
             WHERE f.factory_id = $1
               AND f.date >= $2 AND f.date <= $3
               AND c.cost_type = 'material'
            """, fid, recipe_start, end)
        out["material_only_filter"] = dict(loose)
        _p(f"  ⇒ 只要 cost_type='material'(不看名字) + 窗口: "
           f"n={loose['n']} amt={loose['amt']}")

        # 孤儿: fact_cost_line 有行但 JOIN 不上 dim_cost_category
        orphan = await conn.fetchval(
            """
            SELECT COUNT(*)::bigint FROM fact_cost_line f
             WHERE f.factory_id = $1
               AND NOT EXISTS (SELECT 1 FROM dim_cost_category c
                                WHERE c.category_id = f.category_id
                                  AND c.factory_id = f.factory_id)
            """, fid)
        out["cost_line_orphans"] = int(orphan or 0)
        _p(f"  JOIN 不上分类维表的孤儿行: {orphan}")

        # ── ② 供应商比价：agg_supplier_price ────────────────────────────
        _p(f"\n### ② SUPPLIER_PRICE 读的 agg_supplier_price({fid})")
        sp_all = await conn.fetchrow(
            """
            SELECT COUNT(*)::bigint AS n,
                   COUNT(supplier_id)::bigint AS with_sid,
                   COUNT(supplier_name)::bigint AS with_sname,
                   COUNT(DISTINCT normalized_name)::int AS ingredients,
                   COUNT(DISTINCT COALESCE(supplier_id, supplier_name))::int AS suppliers,
                   COUNT(unit_price)::bigint AS with_price,
                   MIN(delivery_date) AS d0, MAX(delivery_date) AS d1
              FROM agg_supplier_price WHERE factory_id = $1
            """, fid)
        out["agg_supplier_price_all"] = dict(sp_all)
        _p(f"  全量(不限窗口): {sp_all['n']} 行 / 有supplier_id {sp_all['with_sid']} / "
           f"有supplier_name {sp_all['with_sname']} / 食材 {sp_all['ingredients']} / "
           f"供应商 {sp_all['suppliers']} / 有单价 {sp_all['with_price']} / "
           f"{sp_all['d0']}~{sp_all['d1']}")

        sp_win = await conn.fetchrow(
            """
            SELECT COUNT(*)::bigint AS n,
                   COUNT(DISTINCT normalized_name)::int AS ingredients,
                   COUNT(DISTINCT COALESCE(supplier_id, supplier_name))::int AS suppliers
              FROM agg_supplier_price
             WHERE factory_id = $1 AND delivery_date >= $2 AND delivery_date <= $3
            """, fid, sup_start, end)
        out["agg_supplier_price_window"] = dict(sp_win)
        _p(f"  近{CANNOT_COMPUTE_SUPPLIER_DAYS}天({sup_start}~{end}): "
           f"{sp_win['n']} 行 / 食材 {sp_win['ingredients']} / 供应商 {sp_win['suppliers']}")

        sample = await conn.fetch(
            """
            SELECT ingredient_name, normalized_name, unit, supplier_id, supplier_name,
                   unit_price, delivery_date
              FROM agg_supplier_price WHERE factory_id = $1
             ORDER BY delivery_date DESC
            """, fid)
        out["agg_supplier_price_rows"] = len(sample)
        _p(f"  逐行(全部 {len(sample)} 行, ⛔ 不加 head):")
        for r in sample:
            _p(f"    {r['delivery_date']} {r['ingredient_name']!r} unit={r['unit']!r} "
               f"sid={r['supplier_id']!r} sname={r['supplier_name']!r} "
               f"price={r['unit_price']}")

    return out


async def probe_cretas(fid: str) -> dict:
    """Java 侧 cretas_db —— 供应商这一栏的**上游**在不在那里。"""
    import asyncpg
    from config import get_settings

    out = {}
    url = get_settings().food_kb_db_url
    # ⛔ 不打印整串 URL（含密码）
    _p(f"\n### cretas_db 侧 (food_kb_db_url host/db = {url.rsplit('@', 1)[-1]})")
    conn = await asyncpg.connect(url)
    try:
        health = []
        for t in CANNOT_COMPUTE_CRETAS_TABLES:
            h = await _table_health(conn, t)
            health.append(h)
            _p(f"  {t:34s} exists={h['exists']!s:5s} schema={h.get('schema')} "
               f"rows_all_tenants={h['rows']}")
        out["health"] = health

        for t, col in (("suppliers", "factory_id"), ("material_batches", "factory_id")):
            reg = await conn.fetchval("SELECT to_regclass($1)", t)
            if reg is None:
                _p(f"  {t}: 这个库里没有这张表 —— ⛔ 与「0 行」不是一回事")
                continue
            n = await conn.fetchval(
                f'SELECT COUNT(*)::bigint FROM "{t}" WHERE {col} = $1', fid)
            out[f"{t}_rows_{fid}"] = int(n or 0)
            _p(f"  {t}({fid}) = {n}")
    finally:
        await conn.close()
    return out


async def main() -> int:
    ctx = bootstrap_probe(CANNOT_COMPUTE_TENANTS[0])
    _p(f"PROBE_CTX={ctx!r}")
    _p(f"TENANTS={CANNOT_COMPUTE_TENANTS}")
    pool = await ctx.pool()

    bad_instrument = False
    results = {}
    for fid in CANNOT_COMPUTE_TENANTS:
        from smartbi.tenant_ctx import set_factory_id
        set_factory_id(fid)
        _p("=" * 72)
        _p(f"# 租户 {fid}")
        res = await probe_smartbi(pool, fid)
        results[fid] = res

        # 阳性对照
        if res["positive_control_pos_rows"] <= 0:
            _p("🔴 POSITIVE_CONTROL_FAILED: fact_pos_transaction 读出 0 "
               "—— 连错库 / RLS / 租户上下文，本轮读数作废")
            bad_instrument = True
        # 阴性对照
        bogus = [h for h in res["health"] if h["table"] == CANNOT_COMPUTE_BOGUS_TABLE]
        if not bogus or bogus[0]["exists"]:
            _p("🔴 NEGATIVE_CONTROL_FAILED: 故意不存在的表居然存在 —— 仪器坏")
            bad_instrument = True
        else:
            _p(f"\n✅ 阴性对照: {CANNOT_COMPUTE_BOGUS_TABLE} exists=False "
               "⇒ 「不存在」与「0 行」在本探针里分得开")

    try:
        results["cretas_db"] = await probe_cretas(CANNOT_COMPUTE_TENANTS[0])
    except Exception as exc:  # noqa: BLE001
        _p(f"⚠️ cretas_db 侧探测失败: {exc}")
        results["cretas_db"] = {"error": str(exc)[:200]}

    _p("=" * 72)
    if bad_instrument:
        _p("PROBE_VERDICT=2 没量到（对照没过）")
        return 2
    _p("PROBE_VERDICT=0 量到了")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
