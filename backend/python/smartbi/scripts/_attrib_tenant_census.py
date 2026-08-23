"""重新核实：除 MOCK_REST 外，还有没有别的餐饮租户有 agg_daily 数据、且客流真的掉过。

⛔ 不照抄上一棒的「只有 MOCK_REST 有数据」——「零行」有保质期（本仓记过一次：
一条「某功能零实例」的登记三天后被真实使用推翻，它当时驱动了一整串推论）。

🔴 RLS：每个租户**独立设一次会话变量并贴出 current_setting**。
   本仓踩过：`set_config(..., true)` 是事务级，asyncpg 自动提交 ⇒ 下一条语句就没了，
   于是除某一家外每一家都恒为 0 行 —— 而那个读数被写成了「21 家一行数据都没有」。

阳性对照：MOCK_REST 必须读出非零。它为 0 ⇒ 仪器坏了（rc=2），⛔ 不是「大家都没数据」。
"""
from __future__ import annotations

import asyncio
import json
import sys
from datetime import timedelta

from smartbi.scripts._probe_bootstrap import bootstrap_probe

ctx = bootstrap_probe("MOCK_REST")

from smartbi.config import get_pg_pool  # noqa: E402

SPAN = 14


async def probe_one(pool, fid):
    """每个租户一条**独立连接**，会话级设 RLS 变量。"""
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", fid)
        seen = await conn.fetchval("SELECT current_setting('app.factory_id', true)")
        row = await conn.fetchrow(
            "SELECT MIN(date) d0, MAX(date) d1, COUNT(*) n, "
            "COUNT(DISTINCT store_id) stores, COALESCE(SUM(bill_count),0)::bigint bills "
            "FROM agg_daily WHERE factory_id=$1", fid)
        out = {
            "rls_says": seen,
            "d0": str(row["d0"]), "d1": str(row["d1"]),
            "rows": int(row["n"] or 0), "stores": int(row["stores"] or 0),
            "bills": int(row["bills"] or 0),
        }
        if not row["d1"] or not row["n"]:
            return out
        end = row["d1"]
        cur_s, base_e = end - timedelta(days=SPAN - 1), end - timedelta(days=SPAN)
        base_s = base_e - timedelta(days=SPAN - 1)
        drops = []
        srows = await conn.fetch(
            "SELECT st.name nm, "
            " SUM(a.bill_count) FILTER (WHERE a.date BETWEEN $2 AND $3) cur_b, "
            " COUNT(DISTINCT a.date) FILTER (WHERE a.date BETWEEN $2 AND $3) cur_d, "
            " SUM(a.bill_count) FILTER (WHERE a.date BETWEEN $4 AND $5) base_b, "
            " COUNT(DISTINCT a.date) FILTER (WHERE a.date BETWEEN $4 AND $5) base_d "
            "FROM agg_daily a JOIN dim_store st "
            "  ON st.factory_id=a.factory_id AND st.store_id=a.store_id "
            "WHERE a.factory_id=$1 GROUP BY st.name",
            fid, cur_s, end, base_s, base_e)
        for r in srows:
            cb, bb = r["cur_b"], r["base_b"]
            if not cb or not bb or r["cur_d"] != SPAN or r["base_d"] != SPAN:
                continue
            pct = 100.0 * (int(cb) - int(bb)) / int(bb)
            drops.append({"store": r["nm"], "pct": round(pct, 1)})
        drops.sort(key=lambda d: d["pct"])
        out["equal_window_14d_traffic"] = drops[:5]
        out["worst_drop_pct"] = drops[0]["pct"] if drops else None
        return out


async def main() -> int:
    pool = await get_pg_pool()
    async with pool.acquire() as conn:
        fids = [r["factory_id"] for r in await conn.fetch(
            "SELECT DISTINCT factory_id FROM agg_daily ORDER BY 1")]
    out = {"tenants_in_agg_daily": fids, "readings": {}}
    for fid in fids:
        try:
            out["readings"][fid] = await probe_one(pool, fid)
        except Exception as exc:  # noqa: BLE001 - 逐条贴出来, ⛔ 不 continue
            out["readings"][fid] = {"error": str(exc)[:200]}
    print(json.dumps(out, ensure_ascii=False, indent=2, default=str))

    mock = out["readings"].get("MOCK_REST") or {}
    if not mock.get("rows"):
        print("rc=2 阳性对照失败：MOCK_REST 读出 0 行 ⇒ 仪器坏了，本次读数作废",
              file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
