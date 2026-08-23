# -*- coding: utf-8 -*-
"""照产品自己给的出路走一遍 —— 老板到底会拿到什么。

## 为什么必须走这一步

Q09「哪家店缺货最严重」产品说：

    按食材我能算，…想看的话分开问：**先只按食材问同一件事**。

而 `_inventory_claim_check` 已经实测（prod / MOCK_REST）：
`resolve_inventory_warning` 读的 `raw_material_types` / `material_batches`
**在 smartbi 库里根本不存在**，餐饮侧的 `fact_inventory_snapshot` /
`dim_ingredient_threshold` 全表 **0 行**。

⇒ 但「源表没有数据」不等于「老板会一无所获」——
   产品完全可能在下游诚实地说「还没有库存数据」。
   ▎**这两件事从代码推不出来，必须照那条出路真的走一遍。**
   （测量纪律 5：⛔ 不从注释/文档推行为。）

## 判据（对每一句）

    ✅ 诚实       明说「还没有库存数据 / 接上之后才能回答」并给出路
    🔴 空答案     报了一堆 0 / 说「没有需要补货的食材」—— 把「没接数据」
                 说成了「库存很健康」，⛔ 这是最坏的一档
    🔴 崩         报错 / 「执行链暂时不可用」
    ⚠️ 说不清    人读一下原文

## 阳性对照（⛔ 不许省）

同一进程里问一句**已知能答**的（「最近30天总营收是多少」）必须 `kind == answer`，
否则「库存答不了」和「我整条链没跑起来」是同一个读数。
"""
from __future__ import annotations

import asyncio

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_INVFT_FID = "MOCK_REST"
_INVFT_ROUNDS = 2
ctx = bootstrap_probe(_INVFT_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

_INVFT_QS = [
    ("🔴原句", "哪家店缺货最严重"),
    ("➡️出路", "哪些食材快没了"),        # ← 产品自己建议的那条路
    ("➡️出路2", "库存够不够"),
    ("✅对照", "最近30天总营收是多少"),   # ← 阳性对照
]

#: 「没有数据/还没接上」类 —— 诚实。
_INVFT_HONEST = ("还没有库存数据", "没有库存数据", "还没有接", "接上之后",
                 "系统里还没有", "暂时没有库存", "没有录入库存")
#: 「一切正常」类 —— 在零数据上说这个是最坏的一档。
_INVFT_ALL_GOOD = ("没有需要补货", "库存充足", "都还够", "没有食材告警",
                   "暂无预警", "没有缺货")


def _clear() -> list[str]:
    done = []
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, n)()
        done.append(n)
    return done


def _verdict(tag, text):
    """判定。

    🔴 2026-08-19 换过一次：第一版是**纯词表**，于是产品修好之后
       （「**按门店拆不出来**，这次只能按食材看。下面是按食材的情况：」+ 完整表格）
       它照旧判「⚠️ 说不清」——词表里没有那个措辞。
       ▎与本轮 `_dish_catalogue_behaviour` 那次逐字同形（产品说「菜单里没**查到**」
       ▎而词表写的是「菜单里没**有**」，差一个字就把正确答案判成了说不清）。
       ⇒ 「部分能做」这一档改用**结构判据**：有表格 + 表格**之前**有一句
         「…拆不出来/只能按…」的说明。这两件事都不随文案措辞改动而失效。
    """
    if tag == "✅对照":
        return "-"
    if not text:
        return "🔴 空 —— 什么都没给"
    if "执行链暂时不可用" in text:
        return "🔴 崩（⚠️ 先怀疑探针自己，见 _probe_bootstrap）"
    # ── 「部分能做」：结构判据，⛔ 不匹配具体措辞 ──────────────────────
    table_at = text.find("\n| ")
    gap_at = min(
        [i for i in (text.find("拆不出来"), text.find("只能按")) if i >= 0]
        or [-1]
    )
    if table_at >= 0 and gap_at >= 0:
        if gap_at < table_at:
            return "✅ 部分能做（说明在前 + 表格在后）"
        return "🔴 说明排在表格后面 —— 老板第一眼读到的是数字"
    if any(m in text for m in _INVFT_HONEST):
        return "✅ 诚实（说了还没有库存数据）"
    if any(m in text for m in _INVFT_ALL_GOOD):
        return "🔴 把「没接数据」说成了「库存很健康」—— 最坏的一档"
    return "⚠️ 说不清（人读原文）"


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s 轮数=%d" % (ctx.db_name, _INVFT_FID, _INVFT_ROUNDS))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2

    n_ctrl = 0
    rows = []
    for rnd in range(1, _INVFT_ROUNDS + 1):
        for tag, q in _INVFT_QS:
            cleared = _clear()
            set_factory_id(_INVFT_FID)
            async with rr.dish_catalogue_scope(pool, _INVFT_FID):
                spec = await ri.parse_restaurant_query(
                    q, pool, factory_id=_INVFT_FID,
                    session_key="invft-r%d-%s" % (rnd, q), semantic_first=True)
                res = None
                if spec is not None and svc.should_delegate(spec, None, query=q):
                    res = await svc.tiered_answer(
                        q, pool, _INVFT_FID, ctx.role, precomputed_spec=spec,
                        session_key="invft-r%d-%s" % (rnd, q))
            kind = (res or {}).get("kind") or "-"
            text = (res or {}).get("answer_text") or ""
            if tag == "✅对照" and kind == "answer":
                n_ctrl += 1
            v = _verdict(tag, text)
            rows.append((rnd, tag, q, kind, len(text), v))

            print("\n" + "=" * 76)
            print("r%d 【%s】%s" % (rnd, tag, q))
            print("  清了: %s" % "、".join(cleared))
            print("  intent=%s  dims=%s  clarify=%s" % (
                getattr(spec, "intent", None) if spec else None,
                tuple(getattr(spec, "dimensions", ()) or ()) if spec else (),
                getattr(spec, "clarification_needed", None) if spec else None))
            print("  kind=%s  n=%d" % (kind, len(text)))
            print("  判定: %s" % v)
            print("  ── 老板看到的原文（前 400 字）──")
            print("    " + (text[:400].replace("\n", "\n    ") or "(空)"))

    print("\n" + "=" * 76)
    print("汇总（⛔ 逐行）")
    for (rnd, tag, q, kind, n, v) in rows:
        print("  r%d %-8s %-16s kind=%-14s n=%-6d %s" % (rnd, tag, q[:16], kind, n, v))

    print("\n阳性对照·「最近30天总营收是多少」kind==answer = %d/%d 轮"
          % (n_ctrl, _INVFT_ROUNDS))
    if n_ctrl == 0:
        print("⛔ rc=2 阳性对照都答不上 —— 分不清「库存答不了」和「整条链没跑起来」，读数作废")
        return 2
    bad = [r for r in rows if r[5].startswith("🔴")]
    print("🔴 = %d 条 / ⚠️ 说不清 = %d 条"
          % (len(bad), len([r for r in rows if r[5].startswith("⚠️")])))
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
