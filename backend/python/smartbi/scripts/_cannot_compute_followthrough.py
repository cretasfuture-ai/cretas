"""照产品自己给的**出路**真的走一遍 —— 老板照做，会不会一无所获。

骨架照抄 `_inventory_followthrough.py`（同一个形状：不是量「答得对不对」，
是量「它让老板去做的那件事，做完之后系统收得到吗」）。

两句被测的话（prod 实测原文，`docs/dispatch/2026-08-18-全景重测-修复前基线.md`）：

  Q16 RECIPE_COST   「…所以这个数的前提是：这段时间的**头和尾各盘一次库**。
                      盘点数据进了系统，这个数就能算。」
  Q18 SUPPLIER_PRICE 「你要做的：让采购在录单据时把供应商填上。」

▎判据（形态 D′ / 反目标第一条）：**排在最前面的那个命中，他去查会不会一无所获。**

对每一句，问同一个问题：
  ① 它让老板做的那件事，系统里**已经有人做过了吗**？
  ② 做过之后，被测那条读路径**收得到吗**（那个数还是不是算不出来）？
  ①有 且 ②收不到  ⇒ 这句承诺**已经被现状证伪**，⛔ 不是「等他去做」。

阳性 / 阴性对照：
  阳性: `fact_pos_transaction` > 0（仪器活着）。⚠️ 问一句「仪器坏掉时它会不会
        也成立」—— 连错库/RLS/租户没设三种坏法都会让它读 0 ⇒ 它能红。
  阴性: 故意不存在的表必须报 exists=False，⛔ 不能读成 0 行。

退出码: 0=没发现证伪 / 1=**承诺已被现状证伪** / 2=没量到（对照没过）
"""

from __future__ import annotations

import asyncio
import sys
from datetime import date, timedelta

from smartbi.scripts._probe_bootstrap import (
    RESTAURANT_TENANTS_WITH_DATA,
    bootstrap_probe,
)

CANNOT_COMPUTE_FT_TENANTS = RESTAURANT_TENANTS_WITH_DATA
CANNOT_COMPUTE_FT_DAYS = 30
CANNOT_COMPUTE_FT_BOGUS = "zzz_followthrough_must_not_exist"

#: Q16 的出路指向的那两张表 —— 盘点做完之后数据落在哪
CANNOT_COMPUTE_FT_STOCKTAKING_TABLES = (
    "fact_restaurant_stocktaking",
    "agg_restaurant_daily_totals",
)

#: Q16 真正被读的那张表。⛔ 出路落到别处 = 这条出路走不通。
CANNOT_COMPUTE_FT_RATIO_SOURCE = "fact_cost_line"


def _p(*a):
    print(*a, flush=True)


async def _exists(conn, table: str) -> bool:
    return await conn.fetchval("SELECT to_regclass($1) IS NOT NULL", table)


async def run(pool, fid: str) -> dict:
    end = date.today()
    start = end - timedelta(days=CANNOT_COMPUTE_FT_DAYS - 1)
    out = {"factory_id": fid, "window": (str(start), str(end))}

    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", fid)
        out["tenant_readback"] = await conn.fetchval(
            "SELECT current_setting('app.factory_id', true)")

        # ── 对照 ────────────────────────────────────────────────────────
        out["positive_pos"] = int(await conn.fetchval(
            "SELECT COUNT(*)::bigint FROM fact_pos_transaction WHERE factory_id=$1", fid) or 0)
        out["negative_bogus_exists"] = bool(
            await _exists(conn, CANNOT_COMPUTE_FT_BOGUS))
        _p(f"[对照] 阳性 fact_pos_transaction({fid})={out['positive_pos']:,} | "
           f"阴性 {CANNOT_COMPUTE_FT_BOGUS} exists={out['negative_bogus_exists']}")

        # ── Q16 出路：「头和尾各盘一次库」 ────────────────────────────────
        _p(f"\n### Q16 出路核对: 盘点做过没有 / 做过之后 {CANNOT_COMPUTE_FT_RATIO_SOURCE} 收得到吗")
        for t in CANNOT_COMPUTE_FT_STOCKTAKING_TABLES:
            if not await _exists(conn, t):
                _p(f"  {t}: 这个库里**没有这张表** —— ⛔ 与「0 行」不是一回事")
                out[f"{t}_exists"] = False
                continue
            out[f"{t}_exists"] = True

        st_rows = await conn.fetch(
            """
            SELECT date, status, COUNT(*)::bigint AS n
              FROM fact_restaurant_stocktaking
             WHERE factory_id = $1
             GROUP BY date, status ORDER BY date
            """, fid)
        out["stocktaking_by_date"] = [dict(r) for r in st_rows]
        total_st = sum(int(r["n"]) for r in st_rows)
        out["stocktaking_rows_total"] = total_st
        _p(f"  fact_restaurant_stocktaking({fid}) 全量 = {total_st} 行, "
           f"{len(st_rows)} 个(日期,状态)组:")
        for r in st_rows:
            _p(f"    {r['date']} status={r['status']!r} n={r['n']}")

        st_win = await conn.fetchrow(
            """
            SELECT COUNT(*)::bigint AS n, COUNT(DISTINCT date)::int AS days,
                   MIN(date) AS d0, MAX(date) AS d1
              FROM fact_restaurant_stocktaking
             WHERE factory_id=$1 AND date >= $2 AND date <= $3
            """, fid, start, end)
        out["stocktaking_window"] = dict(st_win)
        _p(f"  近{CANNOT_COMPUTE_FT_DAYS}天窗口内: {st_win['n']} 行 / "
           f"{st_win['days']} 个盘点日 / {st_win['d0']}~{st_win['d1']}")

        tot = await conn.fetchrow(
            """
            SELECT COUNT(*)::bigint AS days,
                   SUM(COALESCE(stocktaking_count,0))::bigint AS st_cnt,
                   SUM(COALESCE(requisition_cost_total,0))::float AS req_cost,
                   SUM(COALESCE(stocktaking_shortage_cost,0))::float AS short_cost,
                   MIN(date) AS d0, MAX(date) AS d1
              FROM agg_restaurant_daily_totals
             WHERE factory_id=$1 AND date >= $2 AND date <= $3
            """, fid, start, end)
        out["daily_totals_window"] = dict(tot)
        _p(f"  agg_restaurant_daily_totals 窗口内: {tot['days']} 天 / "
           f"盘点次数合计 {tot['st_cnt']} / 领料成本合计 ¥{(tot['req_cost'] or 0):,.2f} / "
           f"盘亏成本合计 ¥{(tot['short_cost'] or 0):,.2f} / {tot['d0']}~{tot['d1']}")

        ratio_src = int(await conn.fetchval(
            f"SELECT COUNT(*)::bigint FROM {CANNOT_COMPUTE_FT_RATIO_SOURCE} "
            "WHERE factory_id=$1", fid) or 0)
        ratio_src_global = int(await conn.fetchval(
            f"SELECT COUNT(*)::bigint FROM {CANNOT_COMPUTE_FT_RATIO_SOURCE}") or 0)
        out["ratio_source_rows"] = ratio_src
        out["ratio_source_rows_global"] = ratio_src_global
        _p(f"  ⇒ 而 Q16 真正读的 {CANNOT_COMPUTE_FT_RATIO_SOURCE}: "
           f"{fid}={ratio_src} 行 / **全库所有租户**={ratio_src_global} 行")

        # ── Q18 出路：「让采购在录单据时把供应商填上」 ────────────────────
        _p("\n### Q18 出路核对: 供应商这一栏, 上游填了没有")
        sp = await conn.fetchrow(
            """
            SELECT COUNT(*)::bigint AS n,
                   COUNT(supplier_id)::bigint AS sid,
                   COUNT(supplier_name)::bigint AS sname,
                   COUNT(DISTINCT source_note_id)::int AS notes
              FROM agg_supplier_price WHERE factory_id=$1
            """, fid)
        out["agg_supplier_price"] = dict(sp)
        _p(f"  agg_supplier_price({fid}): {sp['n']} 行 / 有 supplier_id {sp['sid']} / "
           f"有 supplier_name {sp['sname']} / {sp['notes']} 个来源单据")
        notes = await conn.fetch(
            "SELECT DISTINCT source_note_id FROM agg_supplier_price WHERE factory_id=$1"
            " ORDER BY source_note_id", fid)
        out["source_note_ids"] = [r["source_note_id"] for r in notes]
        _p(f"  来源单据 id（全部 {len(notes)} 个, ⛔ 不加 head）: "
           f"{[r['source_note_id'] for r in notes]}")

        sp_global = await conn.fetchrow(
            """
            SELECT COUNT(*)::bigint AS n, COUNT(supplier_id)::bigint AS sid,
                   COUNT(supplier_name)::bigint AS sname
              FROM agg_supplier_price
            """)
        out["agg_supplier_price_global"] = dict(sp_global)
        _p(f"  agg_supplier_price **全库**: {sp_global['n']} 行 / "
           f"有 supplier_id {sp_global['sid']} / 有 supplier_name {sp_global['sname']}")

    # ── Q18 的真正上游在 Java 侧 cretas_db ──────────────────────────────
    import asyncpg
    from config import get_settings
    conn = await asyncpg.connect(get_settings().food_kb_db_url)
    try:
        _p("\n  —— 上游 cretas_prod_db.material_batches ——")
        mb = await conn.fetchrow(
            """
            SELECT COUNT(*)::bigint AS n,
                   COUNT(supplier_id)::bigint AS sid,
                   COUNT(unit_price)::bigint AS price,
                   MIN(purchase_date) AS d0, MAX(purchase_date) AS d1
              FROM material_batches
             WHERE factory_id = $1 AND deleted_at IS NULL
            """, fid)
        out["material_batches"] = dict(mb)
        _p(f"  material_batches({fid}, 未删): {mb['n']} 行 / "
           f"有 supplier_id {mb['sid']} / 有 unit_price {mb['price']} / "
           f"{mb['d0']}~{mb['d1']}")

        mb_global = await conn.fetchrow(
            """
            SELECT COUNT(*)::bigint AS n, COUNT(supplier_id)::bigint AS sid
              FROM material_batches WHERE deleted_at IS NULL
            """)
        out["material_batches_global"] = dict(mb_global)
        _p(f"  material_batches **全库**(未删): {mb_global['n']} 行 / "
           f"有 supplier_id {mb_global['sid']}")

        sup = await conn.fetchrow(
            "SELECT COUNT(*)::bigint AS n FROM suppliers WHERE factory_id = $1", fid)
        sup_g = await conn.fetchval("SELECT COUNT(*)::bigint FROM suppliers")
        out["suppliers"] = {"tenant": int(sup["n"]), "global": int(sup_g)}
        _p(f"  suppliers({fid}) = {sup['n']} / 全库 = {sup_g}")
    finally:
        await conn.close()

    return out


async def main() -> int:
    ctx = bootstrap_probe(CANNOT_COMPUTE_FT_TENANTS[0])
    _p(f"PROBE_CTX={ctx!r}  TENANTS={CANNOT_COMPUTE_FT_TENANTS}")
    pool = await ctx.pool()

    falsified = []
    for fid in CANNOT_COMPUTE_FT_TENANTS:
        _p("=" * 72)
        _p(f"# {fid}")
        r = await run(pool, fid)
        if r["positive_pos"] <= 0 or r["negative_bogus_exists"]:
            _p("🔴 对照没过 —— 本轮读数作废")
            return 2

        _p("\n" + "-" * 72)
        # Q16 判定
        did_stocktake = r["stocktaking_rows_total"] > 0
        source_empty = r["ratio_source_rows"] == 0
        _p(f"Q16 出路判定: 已做过盘点={did_stocktake} "
           f"({r['stocktaking_rows_total']} 行) / "
           f"被读的 {CANNOT_COMPUTE_FT_RATIO_SOURCE} 仍为空={source_empty} "
           f"({r['ratio_source_rows']} 行, 全库 {r['ratio_source_rows_global']} 行)")
        if did_stocktake and source_empty:
            falsified.append(
                f"{fid}/Q16: 盘点已经做过 {r['stocktaking_rows_total']} 行, "
                f"而 {CANNOT_COMPUTE_FT_RATIO_SOURCE} 仍是 0 行 "
                f"⇒ 「盘点数据进了系统，这个数就能算」**已被现状证伪**")

        # Q18 判定
        mb = r.get("material_batches") or {}
        mb_n, mb_sid = int(mb.get("n") or 0), int(mb.get("sid") or 0)
        _p(f"Q18 出路判定: 上游 material_batches {mb_n} 行, 其中填了供应商 {mb_sid} 行")
        if mb_n > 0 and mb_sid > 0 and int(r["agg_supplier_price"]["sid"]) == 0:
            falsified.append(
                f"{fid}/Q18: 上游 {mb_sid}/{mb_n} 行**已经填了供应商**, "
                f"而 agg_supplier_price 里 0 行有 ⇒ 是 ETL 丢了, "
                f"「让采购把供应商填上」是**错的出路**")

    _p("=" * 72)
    if falsified:
        for f in falsified:
            _p(f"🔴 {f}")
        _p("PROBE_VERDICT=1 有承诺被现状证伪")
        return 1
    _p("PROBE_VERDICT=0 没发现证伪")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
