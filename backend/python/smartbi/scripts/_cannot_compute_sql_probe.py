# -*- coding: utf-8 -*-
"""把**改后的那条 SQL**拿到 prod 真跑一次（只读），证明它跑得动、读数对得上。

🔴 为什么要单独有它：单测里那个 `_Pool` 是桩，喂什么形状都行 ——
   它证明不了「这条 SQL 在真库上能不能跑」。本仓记过这条：
   ▎找到标记 ≠ 它能跑。

⛔ **不部署**：候选源码传到 `/tmp/cc_router_candidate.py`，**不进部署树**。
⛔ **不抄 SQL**：SQL 用 AST 从候选源码里**抽**出来（形态 D：抄一份一定会漂）——
   抽不到就 rc=2，⛔ 不退回一份手写的。

对照：
  阳性: 读出的 revenue 必须 > 0，且与 `_cannot_compute_data_probe` 那轮同口径
        （同窗口同租户）—— 读出 0 说明连错库/RLS/租户没设，本轮作废
  阴性: 把 SQL 里的表名改成一个不存在的表，**必须抛 UndefinedTableError**
        —— 不抛说明我根本没在执行这条 SQL（形态 C″：变异没生效）

退出码: 0=跑得动且对得上 / 2=没量到
"""
from __future__ import annotations

import ast
import asyncio
import os
import sys
from datetime import date, timedelta

from smartbi.scripts._probe_bootstrap import (
    RESTAURANT_TENANTS_WITH_DATA,
    bootstrap_probe,
)

CANNOT_COMPUTE_SQL_CANDIDATE = os.environ.get(
    "CC_CANDIDATE", "/tmp/cc_router_candidate.py")
CANNOT_COMPUTE_SQL_FUNC = "_resolve_food_cost_ratio"
CANNOT_COMPUTE_SQL_DAYS = 30
CANNOT_COMPUTE_SQL_TENANT = RESTAURANT_TENANTS_WITH_DATA[0]
CANNOT_COMPUTE_SQL_ROUNDS = 2

#: 改后 SQL 必须新出现的三个出口 —— 抽错函数时这三个都不会在
CANNOT_COMPUTE_SQL_MARKS = ("req_cost", "req_days", "stocktaking_days")


def _p(*a):
    print(*a, flush=True)


def extract_sql() -> str:
    """从候选源码里把 `_resolve_food_cost_ratio` 的那条 SQL 抽出来。

    ⛔ 用 AST 找**最长的那个字符串常量**，不用正则切文本
       （形态 C⁸：字符串计数量的是文本，AST 量的是结构）。
    """
    with open(CANNOT_COMPUTE_SQL_CANDIDATE, "r", encoding="utf-8", newline="") as fh:
        src = fh.read()
    tree = ast.parse(src)
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) \
                and node.name == CANNOT_COMPUTE_SQL_FUNC:
            lits = [n.value for n in ast.walk(node)
                    if isinstance(n, ast.Constant) and isinstance(n.value, str)]
            if not lits:
                raise RuntimeError(f"{CANNOT_COMPUTE_SQL_FUNC} 里一个字符串常量都没有")
            return max(lits, key=len)
    raise RuntimeError(f"候选源码里找不到 {CANNOT_COMPUTE_SQL_FUNC}")


async def main() -> int:
    ctx = bootstrap_probe(CANNOT_COMPUTE_SQL_TENANT)
    _p(f"CANDIDATE={CANNOT_COMPUTE_SQL_CANDIDATE}")
    sql = extract_sql()
    _p(f"抽到 SQL {len(sql)} 字符")
    missing = [m for m in CANNOT_COMPUTE_SQL_MARKS if m not in sql]
    if missing:
        _p(f"🔴 抽到的 SQL 里没有改后的出口 {missing} —— 抽错了函数或候选是旧版")
        return 2
    _p(f"✅ 改后出口三个都在: {CANNOT_COMPUTE_SQL_MARKS}")

    pool = await ctx.pool()
    fid = CANNOT_COMPUTE_SQL_TENANT
    end = date.today()
    start = end - timedelta(days=CANNOT_COMPUTE_SQL_DAYS - 1)

    rows = []
    for rnd in range(1, CANNOT_COMPUTE_SQL_ROUNDS + 1):
        async with pool.acquire() as conn:
            await conn.execute("SELECT set_config('app.factory_id', $1, false)", fid)
            row = await conn.fetchrow(sql, fid, CANNOT_COMPUTE_SQL_DAYS, start, end)
        d = dict(row or {})
        rows.append(d)
        _p(f"\n# 第 {rnd} 轮 ({fid}, {start}~{end})")
        for k, v in d.items():
            _p(f"    {k:22s} = {v!r}")

    if not rows or not (float(rows[0].get("revenue") or 0) > 0):
        _p("🔴 阳性对照失败: revenue 读出 0 —— 连错库/RLS/租户没设，本轮作废")
        return 2
    if rows[0] != rows[-1]:
        _p("⚠️ 两轮读数不同 —— 实时数据在动，⛔ 别据此比较改动前后")

    # ── 阴性对照: 变异表名, 必须抛 UndefinedTable ─────────────────────────
    mutated = sql.replace("agg_restaurant_daily_totals",
                          "zzz_daily_totals_must_not_exist")
    assert mutated != sql, "变异没生效 —— 锚点串不在 SQL 里"
    try:
        async with pool.acquire() as conn:
            await conn.execute("SELECT set_config('app.factory_id', $1, false)", fid)
            await conn.fetchrow(mutated, fid, CANNOT_COMPUTE_SQL_DAYS, start, end)
    except Exception as exc:  # noqa: BLE001 - 这里就是要它炸
        _p(f"\n✅ 阴性对照: 变异表名后抛 {type(exc).__name__} "
           f"⇒ 这条 SQL 真的被执行了，⛔ 不是我在读一份没跑过的文本")
    else:
        _p("\n🔴 阴性对照失败: 表名换成不存在的表居然没抛 —— SQL 没被真正执行")
        return 2

    r = rows[0]
    rev = float(r.get("revenue") or 0)
    rc = r.get("req_cost")
    _p(f"\n📏 读数: 营收 ¥{rev:,.2f} / 领料成本 "
       f"{('¥%s' % format(float(rc), ',.2f')) if rc is not None else 'None'} / "
       f"领料天数 {r.get('req_days')} / 盘点日 {r.get('stocktaking_days')} / "
       f"food_cost {r.get('food_cost')!r} / line_count {r.get('line_count')}")
    if rc is not None and rev:
        _p(f"    ⇒ 领料成本占营收 {float(rc) / rev * 100:.2f}%")
    _p("\nPROBE_VERDICT=0 SQL 跑得动")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
