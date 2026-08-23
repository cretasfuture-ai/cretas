# -*- coding: utf-8 -*-
"""问句重复率 —— 区分「自动化评测」与「真人在用」的最后一格。

## 为什么需要它

访问日志能给端点次数和源 IP，但**没有 body** ⇒ 看不到老板问了什么。
而 `tiered-answer` 实测 **18,397/18,398 来自 `127.0.0.1`**
（Java 与 Python 同机，真人请求也是本机转发）⇒ ▎**源 IP 对它没有区分力。**

⇒ 区分只能靠**问句本身**：
    自动化评测 ⇒ 固定题库、少数几句反复问、重复率高
    真人       ⇒ 长尾、措辞各异、重复率低

## 判据（⛔ 不看总数，看分布）

    唯一问句数 / 总问句数        —— 越低越像评测
    Top-N 问句各自占比           —— 一句占掉几成就是评测
    每个租户各自的问句数          —— 只有一个租户在问 ⇒ 更像评测

## 阳性/阴性对照（⛔ 不许省）

- 阳性：表必须读出 > 0 行，且 `MOCK_REST` 必须在里面
        （它是唯一活着的租户 —— 读不到说明连错库或被 RLS 挡了）
- 阴性：钉一句 `relrowsecurity`，让读数自己说清「被 RLS 挡了」还是「真的没有」
"""
from __future__ import annotations

import asyncio

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_TQ_FID = "MOCK_REST"
ctx = bootstrap_probe(_TQ_FID)

from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: 候选表 —— ⛔ 逐个都查，不猜哪个是「那个」。
_TQ_TABLES = (
    "restaurant_pending_clarifications",
    "restaurant_query_captures",
    "restaurant_refusal_queue",
    "restaurant_promotion_candidates",
)


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s" % ctx.db_name)
    set_factory_id(_TQ_FID)

    async with pool.acquire() as conn:
        # ① 先找出所有**带问句列**的表 —— ⛔ 不靠猜表名
        cols = await conn.fetch("""
            SELECT table_schema, table_name, column_name
            FROM information_schema.columns
            WHERE table_schema NOT IN ('pg_catalog', 'information_schema')
              AND column_name IN ('query', 'question', 'user_query', 'raw_query',
                                  'query_text', 'utterance')
            ORDER BY table_schema, table_name
        """)
        print("\n带问句列的表（⛔ 从 information_schema 找，不猜）:")
        found = []
        for r in cols:
            print("  %s.%s.%s" % (r["table_schema"], r["table_name"], r["column_name"]))
            found.append((r["table_schema"], r["table_name"], r["column_name"]))
        if not found:
            print("  (一张都没有 —— 问句没有落库，本探针量不到东西)")
            print("⛔ rc=2 没有可用的数据源")
            return 2

        for sch, tbl, col in found:
            print("\n" + "=" * 76)
            print("表 %s.%s  问句列=%s" % (sch, tbl, col))
            rls = await conn.fetchval(
                "SELECT relrowsecurity FROM pg_class c JOIN pg_namespace n "
                "ON n.oid = c.relnamespace WHERE n.nspname = $1 AND c.relname = $2",
                sch, tbl)
            print("  relrowsecurity = %s（True ⇒ 不设会话变量必然读到 0 行）" % rls)

            has_fid = await conn.fetchval("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = $1 AND table_name = $2 AND column_name = 'factory_id'
            """, sch, tbl)

            async with conn.transaction():
                if has_fid:
                    await conn.execute(
                        "SELECT set_config('app.factory_id', $1, false)", _TQ_FID)
                total = await conn.fetchval(
                    'SELECT count(*) FROM "%s"."%s"' % (sch, tbl))
                uniq = await conn.fetchval(
                    'SELECT count(DISTINCT "%s") FROM "%s"."%s"' % (col, sch, tbl))
                print("  总行数 = %s   唯一问句数 = %s" % (total, uniq))
                if total:
                    print("  唯一率 = %.1f%%（越低越像固定题库）" % (100.0 * uniq / total))

                if has_fid and total:
                    per = await conn.fetch(
                        'SELECT factory_id, count(*) AS n, count(DISTINCT "%s") AS u '
                        'FROM "%s"."%s" GROUP BY factory_id ORDER BY n DESC'
                        % (col, sch, tbl))
                    print("  按租户:")
                    for r in per:
                        print("    %-16s %6d 行 / %5d 唯一" % (
                            r["factory_id"], r["n"], r["u"]))

                if total:
                    top = await conn.fetch(
                        'SELECT "%s" AS q, count(*) AS n FROM "%s"."%s" '
                        'GROUP BY 1 ORDER BY n DESC LIMIT 12' % (col, sch, tbl))
                    print("  Top 12 问句（⛔ 逐行，不折叠）:")
                    for r in top:
                        share = 100.0 * r["n"] / total
                        print("    %5d 次 (%.1f%%)  %s" % (
                            r["n"], share, str(r["q"])[:56]))

    print("\n判定")
    print("  ⚠️ 这份读数只回答「问句像不像固定题库」，")
    print("     ⛔ 它**不能**证明「没有真实用户」—— 真人也可能反复问同一句。")
    print("     与访问日志那份（问答 18,398 : 餐饮反馈 5）并排读才有意义。")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
