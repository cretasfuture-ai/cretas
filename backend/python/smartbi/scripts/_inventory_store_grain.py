# -*- coding: utf-8 -*-
"""「哪家店缺货最严重」—— 库存到底能不能按门店出？

## 为什么先量这个

全景重测里 Q09 老板看到的是（prod，MOCK_REST，2/2 轮逐字相同）：

    我不确定你要看的是哪一层的数，所以这次我没敢算。按食材我能算，但你这句还要求
    再按门店拆一层，这次选中的算法拆不出这一层。想看的话分开问：先只按食材问同一件事。

三条都撞在 owner 写死的规范上：
  ⛔「所以这次**我没敢算**」—— 交付定义①原文就是「给答案，不是『我没敢算』」
  ⛔「这次**选中的算法**拆不出这一层」—— 技术话不给老板
  ⛔ **因为一半不行就整句作废** —— 规范是「能做的先给 → 为什么另一半不能 → 他要干什么」

但**修法取决于数据**，而这一点从代码推不出来：

    (甲) 库存事实表**有** store 粒度 ⇒ 那是**能力表漏登**，
         该像 2026-08-17 给 CHANNEL_MIX 补 store、2026-08-18 给 DAYPART 补 store
         那两次一样把它补上 —— ⛔ 依据是「它真能出」，不是「希望它能出」
    (乙) 库存事实表**没有** store 粒度 ⇒ 那是**真的做不到**，
         该走「部分能做」：按食材的先给，再说清另一半为什么不能

⛔ 猜错方向的代价：(甲) 猜成 (乙) 会把一个本来能答的问题永久降级；
   (乙) 猜成 (甲) 会往能力表里写一个**它出不来**的粒度 ——
   而能力表是承诺，写宽了下游会拿它当真。

## 硬纪律

- **查行数一律带 schema 限定** —— 本库多 schema，`pg_stat_user_tables`
  不带过滤会读到归档 schema 的数（本仓踩过，结论完全相反）。
- **RLS 表上 0 行 ≠ 表是空的** —— 钉一句 `relrowsecurity`，
  让读数自己说清是「被 RLS 挡了」还是「真的没有」。
- 阳性对照：同库里一张**已知带 store 粒度**的事实表必须读出非 0，
  否则「库存没有 store」和「我连不上/被挡住」是同一个读数。
"""
from __future__ import annotations

import asyncio

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_INVSTORE_FID = "MOCK_REST"
ctx = bootstrap_probe(_INVSTORE_FID)

from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: 库存/缺货相关的候选表。⛔ 逐个都查, 不猜哪个是「那个」。
_INVSTORE_TABLE_HINTS = ("inventory", "stock", "shortage", "requisition", "ingredient")

#: 阳性对照：这张表**已知**带 store 粒度（门店毛利/POS 都按它出）。
_INVSTORE_CONTROL_TABLE = "fact_pos_transaction"


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s" % (ctx.db_name, _INVSTORE_FID))
    set_factory_id(_INVSTORE_FID)

    async with pool.acquire() as conn:
        # ① 候选表清单 —— 带 schema，⛔ 不用 pg_stat_user_tables
        rows = await conn.fetch("""
            SELECT table_schema, table_name
            FROM information_schema.tables
            WHERE table_type = 'BASE TABLE'
              AND table_schema NOT IN ('pg_catalog', 'information_schema')
            ORDER BY table_schema, table_name
        """)
        cands = [(r["table_schema"], r["table_name"]) for r in rows
                 if any(h in r["table_name"].lower() for h in _INVSTORE_TABLE_HINTS)]
        print("\n候选表（按名字命中 %s）: %d 张" % (list(_INVSTORE_TABLE_HINTS), len(cands)))
        for sch, tbl in cands:
            print("  %s.%s" % (sch, tbl))
        if not cands:
            print("⛔ rc=2 一张候选表都没匹配到 —— 关键词可能不对，读数作废")
            return 2

        # ② 每张表有没有 store 列 + RLS 状态 + 本租户行数
        print("\n逐表：store 粒度 / RLS / 本租户行数")
        print("  %-42s %-16s %-6s %s" % ("表", "store 列", "RLS", "本租户行数"))
        verdicts = []
        for sch, tbl in cands + [("public", _INVSTORE_CONTROL_TABLE)]:
            cols = await conn.fetch("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = $1 AND table_name = $2
            """, sch, tbl)
            names = {c["column_name"] for c in cols}
            store_cols = sorted(n for n in names
                                if n in ("store_id", "store_code", "store_name", "store"))
            rls = await conn.fetchval(
                "SELECT relrowsecurity FROM pg_class c JOIN pg_namespace n "
                "ON n.oid = c.relnamespace WHERE n.nspname = $1 AND c.relname = $2",
                sch, tbl)
            cnt = None
            if "factory_id" in names:
                async with conn.transaction():
                    await conn.execute(
                        "SELECT set_config('app.factory_id', $1, false)", _INVSTORE_FID)
                    try:
                        cnt = await conn.fetchval(
                            'SELECT count(*) FROM "%s"."%s" WHERE factory_id = $1'
                            % (sch, tbl), _INVSTORE_FID)
                    except Exception as exc:  # noqa: BLE001
                        cnt = "ERR:%s" % str(exc)[:40]
            else:
                cnt = "(无 factory_id 列)"
            print("  %-42s %-16s %-6s %s" % (
                "%s.%s" % (sch, tbl), ",".join(store_cols) or "—", rls, cnt))
            verdicts.append((sch, tbl, store_cols, cnt))

    ctrl = [v for v in verdicts if v[1] == _INVSTORE_CONTROL_TABLE]
    ctrl_ok = bool(ctrl and ctrl[0][2] and isinstance(ctrl[0][3], int) and ctrl[0][3] > 0)
    print("\n阳性对照·%s 有 store 列且本租户非 0 行 = %s" % (_INVSTORE_CONTROL_TABLE, ctrl_ok))
    if not ctrl_ok:
        print("⛔ rc=2 阳性对照没过 —— 分不清「库存没有 store」和「我读不到」，读数作废")
        return 2

    inv = [v for v in verdicts if v[1] != _INVSTORE_CONTROL_TABLE]
    with_store = [v for v in inv if v[2] and isinstance(v[3], int) and v[3] > 0]
    print("\n判定")
    if with_store:
        print("  🔴 (甲) 库存类表**有** store 粒度且本租户有行 ⇒ 能力表漏登，该补：")
        for sch, tbl, sc, cnt in with_store:
            print("      %s.%s  store 列=%s  %s 行" % (sch, tbl, ",".join(sc), cnt))
        print("  ⇒ 补能力表的依据是**它真能出**，⛔ 不是为了让某条用例过。")
        return 1
    print("  ✅ (乙) 库存类表**没有**可用的 store 粒度 ⇒ 真的做不到，")
    print("     该走「部分能做」：按食材的先给，再说清另一半为什么不能。")
    print("     ⛔ 不许往能力表里写一个它出不来的粒度。")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
