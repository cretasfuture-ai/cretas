"""③ 步可行性读数：客流(bill_count)的同比/环比在 MOCK_REST 上长什么样。

⛔ 这个探针不改任何产品代码，只读 `agg_daily`。

它要回答三个问题（上一棒把 ③ 整条写成「缺外部客流数据源」，本轮重新核实）：

  Q1 `agg_daily.bill_count` 在不在？   —— 在的话 ③ 的「同比/环比」半边不需要任何新数据源
  Q2 各窗口的**天数覆盖**够不够？     —— base 窗只覆盖一半会造出假的「客流暴跌」
  Q3 laggard 那家店的客流是**掉了**还是**一直就这么小**？ —— ⑤ 该不该问的判据

阳性对照：current 窗必须有行、有 bill_count > 0。为 0 ⇒ rc=2（没量到），⛔ 不是「没问题」。
"""
from __future__ import annotations

import asyncio
import json
import sys
from datetime import date, timedelta

from smartbi.scripts._probe_bootstrap import bootstrap_probe

FACTORY = "MOCK_REST"
ctx = bootstrap_probe(FACTORY)

from smartbi.gold.queries import tenant_conn  # noqa: E402


def _shift_year(d, years):
    try:
        return d.replace(year=d.year - years)
    except ValueError:
        return d - timedelta(days=365 * years)


async def main() -> int:
    pool = await ctx.pool()
    out = {}
    async with tenant_conn(pool, FACTORY) as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", FACTORY)
        who = await conn.fetchval("SELECT current_setting('app.factory_id', true)")
        out["rls_context"] = who

        span = await conn.fetchrow(
            "SELECT MIN(date) d0, MAX(date) d1, COUNT(*) n, "
            "COUNT(DISTINCT store_id) stores, SUM(bill_count) bills "
            "FROM agg_daily WHERE factory_id = $1", FACTORY)
        out["span"] = {
            "d0": str(span["d0"]), "d1": str(span["d1"]),
            "rows": int(span["n"] or 0), "stores": int(span["stores"] or 0),
            "total_bills": int(span["bills"] or 0),
        }
        if not span["d1"] or not span["n"]:
            print(json.dumps(out, ensure_ascii=False, indent=2))
            print("rc=2 没量到：agg_daily 无行", file=sys.stderr)
            return 2

        end = span["d1"]
        start = end - timedelta(days=29)
        windows = {
            "current": (start, end),
            "mom": (start - timedelta(days=1) - (end - start), start - timedelta(days=1)),
            "yoy": (_shift_year(start, 1), _shift_year(end, 1)),
        }
        out["windows"] = {k: [str(a), str(b)] for k, (a, b) in windows.items()}

        chain = {}
        for key, (s, e) in windows.items():
            row = await conn.fetchrow(
                "SELECT COALESCE(SUM(net_amount),0)::float rev, "
                "COALESCE(SUM(bill_count),0)::bigint bills, "
                "COUNT(DISTINCT date) days, COUNT(*) n_rows "
                "FROM agg_daily WHERE factory_id=$1 AND date BETWEEN $2 AND $3",
                FACTORY, s, e)
            wanted = (e - s).days + 1
            chain[key] = {
                "rev": round(row["rev"], 2), "bills": int(row["bills"]),
                "days_with_data": int(row["days"]), "days_in_window": wanted,
                "coverage_pct": round(100.0 * int(row["days"]) / wanted, 1),
                "avg_ticket": round(row["rev"] / int(row["bills"]), 2) if row["bills"] else None,
            }
        out["chain"] = chain

        # 每店：current vs mom 的客流
        per_store = {}
        for key in ("current", "mom"):
            s, e = windows[key]
            rows = await conn.fetch(
                "SELECT st.name nm, COALESCE(SUM(a.net_amount),0)::float rev, "
                "COALESCE(SUM(a.bill_count),0)::bigint bills, "
                "COUNT(DISTINCT a.date) days "
                "FROM agg_daily a JOIN dim_store st "
                "  ON st.factory_id=a.factory_id AND st.store_id=a.store_id "
                "WHERE a.factory_id=$1 AND a.date BETWEEN $2 AND $3 "
                "GROUP BY st.name ORDER BY 2",
                FACTORY, s, e)
            per_store[key] = {
                r["nm"]: {"rev": round(r["rev"], 2), "bills": int(r["bills"]),
                          "days": int(r["days"])}
                for r in rows
            }
        out["per_store"] = per_store

    # 阳性对照
    if chain["current"]["bills"] <= 0:
        print(json.dumps(out, ensure_ascii=False, indent=2))
        print("rc=2 没量到：current 窗 bills=0", file=sys.stderr)
        return 2

    print(json.dumps(out, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
