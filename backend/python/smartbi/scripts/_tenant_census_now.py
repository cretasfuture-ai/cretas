# -*- coding: utf-8 -*-
"""prod 上**此刻**有哪些餐饮租户 —— ⛔ 不引用任何写死的清单。

## 为什么需要它

`tests/test_no_drifted_duplicate_constants.py` 抓到 `TENANTS` 在三个探针里
漂成两种值：

    _attrib_step1.py / _nearest_alt_probe.py
      ('DEMO_REST', 'MOCK_REST', 'RES_3101_009', 'R_GML_DEMO', 'R_XMX_CHAIN')
    _gap15_channel.py
      ('DEMO_REST', 'F006', 'LIUSHANMEN', 'MOCK_REST')

闸说得对：这**不是**两个概念恰好同名，是**同一个概念漂了**（租户清单），
成因是租户被硬删除而写死的清单没跟着改。

▎**「某某租户有 N 行」这类结论有保质期。** 本仓刚踩过两次：
▎「采购 OA 零实例」三天后被真实使用推翻；
▎「21 家真实餐饮租户 / RES_3101_009 有 616,887 笔」已不描述今天的 prod。
⇒ 对齐之前先**当场量**，⛔ 不从任何一份写死的清单里挑一个当权威。

## 阳性对照（⛔ 不许省）

`MOCK_REST` 必须出现且行数 > 0 —— 它是全仓公认还活着的那个。
读不到它 ⇒ 是 RLS / 连接 / 权限问题，本次读数作废（rc=2）。

## RLS

`fact_pos_transaction` 等事实表带 RLS。**不 set_config 直接查会返回 0 行，
而「0 行」和「表是空的」长得一模一样**。所以：
- 逐租户**独立设置**会话变量，并把 `current_setting` 读回来贴出去
- 同时钉一句 `relrowsecurity`，让读数自己说清「被 RLS 挡了」还是「真的没有」
"""
from __future__ import annotations

import asyncio

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_CENSUS_FID = "MOCK_REST"
ctx = bootstrap_probe(_CENSUS_FID)

from smartbi.tenant_ctx import set_factory_id  # noqa: E402


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s" % ctx.db_name)

    async with pool.acquire() as conn:
        rls = await conn.fetchval(
            "SELECT relrowsecurity FROM pg_class WHERE relname = 'fact_pos_transaction'")
        print("fact_pos_transaction.relrowsecurity = %s "
              "（True ⇒ 不设会话变量必然读到 0 行）" % rls)

        # 租户目录本身：dim_* / 工厂表通常不带 RLS，先看全集
        rows = await conn.fetch("""
            SELECT table_schema, table_name
            FROM information_schema.tables
            WHERE table_name IN ('dim_store', 'fact_pos_transaction')
            ORDER BY table_schema, table_name
        """)
        print("\n相关表所在 schema（⛔ 不带 schema 限定的行数读数不可用）:")
        for r in rows:
            print("  %s.%s" % (r["table_schema"], r["table_name"]))

        # 谁还有门店 —— dim_store 带 RLS 与否都先试一次全表
        try:
            tenants = await conn.fetch("""
                SELECT factory_id, count(*) AS stores
                FROM dim_store GROUP BY factory_id ORDER BY factory_id
            """)
            print("\ndim_store 全表按 factory_id（不设会话变量）:")
            for t in tenants:
                print("  %-16s %d 家店" % (t["factory_id"], t["stores"]))
            if not tenants:
                print("  (0 行 —— 可能是 RLS 挡的, 见下方逐租户读数)")
        except Exception as exc:  # noqa: BLE001
            print("\ndim_store 全表查询失败: %s" % str(exc)[:120])
            tenants = []

    # 逐租户独立会话 —— ⛔ 不共用一个连接的会话变量
    candidates = sorted({
        "MOCK_REST", "F006", "LIUSHANMEN", "DEMO_REST",
        "RES_3101_009", "R_GML_DEMO", "R_XMX_CHAIN",
    } | {t["factory_id"] for t in tenants})

    print("\n逐租户（独立会话 + 断言上下文真的生效）:")
    alive = []
    for fid in candidates:
        set_factory_id(fid)
        async with pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, false)", fid)
                got = await conn.fetchval("SELECT current_setting('app.factory_id', true)")
                stores = await conn.fetchval(
                    "SELECT count(*) FROM dim_store WHERE factory_id = $1", fid)
                pos = await conn.fetchval(
                    "SELECT count(*) FROM fact_pos_transaction WHERE factory_id = $1", fid)
        ok = "✅" if got == fid else "🔴上下文没生效"
        print("  %-16s ctx=%-16s %s  门店 %-5s POS %s"
              % (fid, got, ok, stores, pos))
        if (stores or 0) > 0 or (pos or 0) > 0:
            alive.append(fid)

    print("\n活着的租户（门店或 POS 任一非 0）: %s" % (alive or "无"))

    if "MOCK_REST" not in alive:
        print("⛔ rc=2 阳性对照失败：MOCK_REST 都读不到 —— "
              "是 RLS/连接/权限问题，本次读数作废")
        return 2
    print("阳性对照 ✅ MOCK_REST 在活着的清单里")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
