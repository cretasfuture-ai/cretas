# -*- coding: utf-8 -*-
"""⛔⛔ 本探针的结论**已作废**（2026-08-19，同日）。⛔ 不要引用它的读数。

## 它错在哪

它拿 `ctx.pool()`（**smartbi 库**）去查 `raw_material_types` / `material_batches`，
读出「表不存在」，据此判定产品那句「按食材我能算」是假话。

**那两张表在另一个库里。** `resolve_inventory_warning` 的函数体第一行就是：

    from smartbi.config import get_cretas_pool
    cretas_pool = await get_cretas_pool()      # ← Java 侧 cretas_db

⇒ ▎**我量错了库。** 这是本仓记过的老坑（「探针连错库」），而我在同一轮里
   已经因为「判据量错对象」栽了三次，这是第四次。

## 反证（同日实测，prod / MOCK_REST，`_inventory_followthrough`）

照产品自己给的出路走一遍 ——「哪些食材快没了」**答得很好**，2/2 轮：

    **库存预警（2026-08-19）:**
    - 需要立即补货 **4 项**, 关注 0 项, 正常 21 项
    | 状态 | 食材 | 分类 | 剩余 | 安全库存 | 缺口 |
    | 需立即补货 | 鲈鱼 | 水产 | 1689.4 kg | 4826.8 kg | 3137.4 kg |
    ...

⇒ 那句「按食材我能算」**是真的**，⛔ 不是假话。

## 留下它的理由（⛔ 不删）

▎**救回来的不是我的分析，是「照那条出路真的走一遍」这一步** ——
▎而那一步正是我自己写在 `_inventory_followthrough` docstring 里的那句
▎「⛔ 从代码推不出来，必须真的走一遍」。规则当场救了一次。

判据（写给下一棒）：**探针查表之前先确认「这张表在哪个库」** ——
`get_pg_pool()`（smartbi）和 `get_cretas_pool()`（Java 侧）是两个连接池，
而「表不存在」和「这张表不在这个库里」是同一个读数。

---

（以下是作废前的原始说明，保留以便对照。）

「按食材我能算」是不是真的 —— 产品对老板说的那句话，站得住吗？

## 那句话

全景重测 Q09「哪家店缺货最严重」，老板拿到（prod / MOCK_REST / 2/2 轮逐字相同）：

    我不确定你要看的是哪一层的数，所以这次我没敢算。**按食材我能算**，但你这句还要求
    再按门店拆一层，这次选中的算法拆不出这一层。想看的话分开问：先只按食材问同一件事。

它对老板做了一个**承诺**：「你换成按食材问，我就能算」。

## 为什么怀疑它

`RESTAURANT_OPS_INVENTORY_WARNING` → `resolve_inventory_warning`
（⚠️ **不是** `resolve_stock_shortage`，那是盘亏，另一个 code），
而它读的是 `raw_material_types` LEFT JOIN `material_batches`
—— **工厂**的物料表，⛔ 不是餐饮表。

而餐饮侧的库存快照 `fact_inventory_snapshot` 本租户实测 **0 行**。

⇒ 如果工厂物料表在这个餐饮租户上也是 0，那句「按食材我能算」就是**假话**，
   而老板照它换个问法会**一无所获** ——
   ▎反目标里最重的一条：一条误发的提示，烧掉的是「这东西说的话能信」。

## 判据

    工厂物料表本租户有行  ⇒ 那句话站得住（只是拆不出门店）
    工厂物料表本租户 0 行 ⇒ 那句话是**假话**，该换成 owner 的「没有数据」措辞：
      「当前系统里还没有库存数据，接上之后我才能回答缺货情况。」

## 阳性/阴性对照（⛔ 不许省）

- 阳性：同库一张**已知有数**的表（`fact_pos_transaction`）必须读出非 0，
        否则「库存 0 行」和「我被 RLS 挡了 / 连错库」是同一个读数。
- 每张表都钉 `relrowsecurity` 与 `current_setting`，让读数自己说清被没被挡。
"""
from __future__ import annotations

import asyncio

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_INVCLAIM_FID = "MOCK_REST"
ctx = bootstrap_probe(_INVCLAIM_FID)

from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: `resolve_inventory_warning` 真正读的两张表（AST 从函数体里读出来的，⛔ 不是猜的）。
_INVCLAIM_TABLES = ("raw_material_types", "material_batches")
#: 餐饮侧的库存快照 —— 顺带一起量，它是「餐饮真有库存数据」的那张表。
_INVCLAIM_REST_TABLES = ("fact_inventory_snapshot", "dim_ingredient_threshold")
#: 阳性对照。
_INVCLAIM_CONTROL = "fact_pos_transaction"


async def _count(conn, table, fid):
    cols = await conn.fetch("""
        SELECT column_name FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = $1
    """, table)
    names = {c["column_name"] for c in cols}
    if not names:
        return "(表不存在)", None, None
    rls = await conn.fetchval(
        "SELECT relrowsecurity FROM pg_class c JOIN pg_namespace n "
        "ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = $1", table)
    total = await conn.fetchval('SELECT count(*) FROM public."%s"' % table)
    mine = None
    if "factory_id" in names:
        mine = await conn.fetchval(
            'SELECT count(*) FROM public."%s" WHERE factory_id = $1' % table, fid)
    return total, mine, rls


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s" % (ctx.db_name, _INVCLAIM_FID))
    set_factory_id(_INVCLAIM_FID)

    # 🔴 2026-08-19 修正：`raw_material_types` / `material_batches` 在 **Java 侧
    #    cretas_db**，⛔ 不在 smartbi 库。第一版拿 smartbi 池去查，读出「表不存在」，
    #    据此把一句**真话**判成了假话。
    #    ⇒ 先在正确的池上量一次，⛔ 不让「表不存在」和「不在这个库里」共用一个读数。
    from smartbi.config import get_cretas_pool
    cretas_pool = await get_cretas_pool()
    print("\ncretas 池（resolve_inventory_warning 真用的那个）= %s"
          % ("拿到了" if cretas_pool else "🔴 拿不到"))
    if cretas_pool is not None:
        async with cretas_pool.acquire() as cconn:
            for t in _INVCLAIM_TABLES:
                try:
                    n = await cconn.fetchval('SELECT count(*) FROM "%s"' % t)
                except Exception as exc:  # noqa: BLE001
                    n = "ERR:%s" % str(exc)[:50]
                print("  cretas_db.%-24s %s" % (t, n))
    else:
        print("  ⚠️ 拿不到 cretas 池 —— 下面 smartbi 侧的读数**不能**用来判"
              "「按食材能不能算」")

    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", _INVCLAIM_FID)
            got = await conn.fetchval("SELECT current_setting('app.factory_id', true)")
            print("RLS 上下文 = %s  %s" % (got, "✅" if got == _INVCLAIM_FID else "🔴 没生效"))
            if got != _INVCLAIM_FID:
                print("⛔ rc=2 会话上下文没生效，读数作废")
                return 2

            print("\n%-34s %-12s %-12s %s" % ("表", "全表行数", "本租户行数", "RLS"))
            res = {}
            for t in (_INVCLAIM_TABLES + _INVCLAIM_REST_TABLES + (_INVCLAIM_CONTROL,)):
                total, mine, rls = await _count(conn, t, _INVCLAIM_FID)
                res[t] = (total, mine, rls)
                note = ""
                if t in _INVCLAIM_TABLES:
                    note = "  ← resolve_inventory_warning 真读它"
                elif t == _INVCLAIM_CONTROL:
                    note = "  ← 阳性对照"
                print("%-34s %-12s %-12s %-6s%s" % (
                    t, total, "—" if mine is None else mine, rls, note))

    ctrl_total, ctrl_mine, _ = res[_INVCLAIM_CONTROL]
    if not (isinstance(ctrl_mine, int) and ctrl_mine > 0):
        print("\n⛔ rc=2 阳性对照（%s）本租户也是 0 —— "
              "分不清「库存没数据」和「我读不到」，读数作废" % _INVCLAIM_CONTROL)
        return 2
    print("\n阳性对照 ✅ %s 本租户 %d 行" % (_INVCLAIM_CONTROL, ctrl_mine))

    warn_rows = 0
    for t in _INVCLAIM_TABLES:
        total, mine, _ = res[t]
        warn_rows += (mine if isinstance(mine, int)
                      else (total if isinstance(total, int) else 0))

    print("\n判定")
    print("  ⚠️ 上面 smartbi 侧那几张表的行数**不构成判据** —— "
          "resolve_inventory_warning 读的是 cretas_db（见本文件顶部的作废说明）。")
    print("  📏 已由 `_inventory_followthrough` 行为实测定论（prod / MOCK_REST / 2 轮）：")
    print("     「哪些食材快没了」答得很好（4 项需补货 / 21 项正常，带缺口表）")
    print("     ⇒ 那句「按食材我能算」**是真的**。")
    print("     Q09 剩下的问题只有一个：**拆不出门店** ⇒ 该走「部分能做」。")
    print("  （smartbi 侧 fact_inventory_snapshot=%s / dim_ingredient_threshold=%s 行 ——"
          " 它们是 2026-08-09 起**已弃用**的旧账，0 行是预期的，⛔ 不是缺陷。）"
          % (res["fact_inventory_snapshot"][0], res["dim_ingredient_threshold"][0]))
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
