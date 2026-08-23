"""③ 可行性读数 v2：MOCK_REST 的客流在**更细的粒度**上动不动。

v1 读出「30 天 vs 21 天」是窗口覆盖造出来的 +42%，按天归一之后 -0.3%。
v2 问两件事：

  Q4 换成等长窗（近 7 天 vs 前 7 天、近 14 vs 前 14）之后，有没有哪家店真的掉了？
     —— 这决定 ⑤ 有没有可验收的样本。
  Q5 生产今天的 `period_comparison` 对 MOCK_REST 报的营收环比是多少？
     —— 如果它报 +42%，那是**窗口覆盖造出来的**，不是经营变化。

阳性对照：等长窗两侧的 days_with_data 必须都等于窗口天数，否则这一格作废（rc=2）。
"""
from __future__ import annotations

import asyncio
import json
import sys
from datetime import timedelta

from smartbi.scripts._probe_bootstrap import bootstrap_probe

FACTORY = "MOCK_REST"
ctx = bootstrap_probe(FACTORY)

from smartbi.gold.queries import tenant_conn, period_comparison  # noqa: E402


async def main() -> int:
    pool = await ctx.pool()
    out = {}
    async with tenant_conn(pool, FACTORY) as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", FACTORY)
        out["rls_context"] = await conn.fetchval(
            "SELECT current_setting('app.factory_id', true)")
        end = await conn.fetchval(
            "SELECT MAX(date) FROM agg_daily WHERE factory_id=$1", FACTORY)
        out["max_date"] = str(end)

        async def store_window(s, e):
            rows = await conn.fetch(
                "SELECT st.name nm, COALESCE(SUM(a.net_amount),0)::float rev, "
                "COALESCE(SUM(a.bill_count),0)::bigint bills, "
                "COUNT(DISTINCT a.date) days "
                "FROM agg_daily a JOIN dim_store st "
                "  ON st.factory_id=a.factory_id AND st.store_id=a.store_id "
                "WHERE a.factory_id=$1 AND a.date BETWEEN $2 AND $3 "
                "GROUP BY st.name", FACTORY, s, e)
            return {r["nm"]: {"rev": r["rev"], "bills": int(r["bills"]),
                              "days": int(r["days"])} for r in rows}

        for span_days in (7, 14):
            cur_s, cur_e = end - timedelta(days=span_days - 1), end
            base_e = cur_s - timedelta(days=1)
            base_s = base_e - timedelta(days=span_days - 1)
            cur = await store_window(cur_s, cur_e)
            base = await store_window(base_s, base_e)
            block = {
                "window_cur": [str(cur_s), str(cur_e)],
                "window_base": [str(base_s), str(base_e)],
                "stores": {},
            }
            bad_cover = []
            for name, c in sorted(cur.items()):
                b = base.get(name)
                if not b:
                    block["stores"][name] = {"base": "缺"}
                    continue
                if c["days"] != span_days or b["days"] != span_days:
                    bad_cover.append(f"{name}({c['days']}/{b['days']})")
                block["stores"][name] = {
                    "bills_cur": c["bills"], "bills_base": b["bills"],
                    "bills_pct": round(100.0 * (c["bills"] - b["bills"]) / b["bills"], 1)
                    if b["bills"] else None,
                    "rev_pct": round(100.0 * (c["rev"] - b["rev"]) / b["rev"], 1)
                    if b["rev"] else None,
                    "days_cur": c["days"], "days_base": b["days"],
                }
            block["coverage_incomplete"] = bad_cover
            out[f"equal_window_{span_days}d"] = block

    # Q5 生产今天真的报什么
    end_d = end
    start_d = end_d - timedelta(days=29)
    pc = await period_comparison(pool, FACTORY, start_d, end_d)
    out["prod_period_comparison"] = {
        "window": [str(start_d), str(end_d)],
        "revenue": pc.get("revenue"),
        "gross_margin_pct": pc.get("gross_margin_pct"),
        "has_bill_count_key": any("bill" in k for k in pc.keys()),
        "keys": sorted(pc.keys()),
    }

    print(json.dumps(out, ensure_ascii=False, indent=2, default=str))

    b7 = out["equal_window_7d"]
    if b7["coverage_incomplete"]:
        print("rc=2 等长窗覆盖不全: " + "、".join(b7["coverage_incomplete"]), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
