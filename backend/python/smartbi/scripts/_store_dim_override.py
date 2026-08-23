# -*- coding: utf-8 -*-
"""`restaurant_intent.py:2505-2510` 那段词表覆盖，到底在保护什么、又误伤了什么。

## 被测的那段

    if (store_scope == "all"
            and "store" in dimension_list
            and not _asks_store_breakdown(effective_query)):
        dimension_list.remove("store")

`_asks_store_breakdown` 是**一张词表**（`_STORE_BREAKDOWN_SCOPE_TOKENS`，19 条）。
它里面有「有没有店」，**没有**「有几家店」——「有几家店是亏钱的」因此被剥掉
store 维度，contract-repair 随后把 STORE_MARGIN 改写成 GROSS_MARGIN，
老板问「几家店亏钱」拿到的是**菜品毛利分析**。

## 这个探针要回答的唯一问题

▎**LLM 自己分得清「全店汇总」和「按店拆」吗？**

分得清 ⇒ 那段覆盖对 **LLM 明确给出的** store 维度就是纯误伤，
        可以按来源收窄（只覆盖代码自己派生的维度）。
分不清 ⇒ 那段覆盖在保护真东西，收窄会引入拒答回归，要另想办法。

⛔ 这个问题**从代码推不出来**，两种可能跑的是同一段代码。跑一次让它自己说。

## 两组样本

    组A「应当按店拆」—— 剥掉 store 就答非所问
    组B「应当全店汇总」—— 保留 store 会让计划撞能力闸(这段代码的立身之本)

## 读数（每格都打，⛔ 不只打汇总）

    llm_dimensions          LLM 原始输出（这是判据本身）
    store_scope / breakdown 那段的两个前提
    OVERRIDE_FIRED          那段有没有真的删掉 store
    最终 dims / intent      结果
    kind + 答案首行         老板会看到的东西

## 阳性/阴性对照（⛔ 不许省）

- 阳性：钩子调用数 > 0，且组A里「有没有店是亏钱的」必须保住 store 维度
        （它是**已知答对**的那句 —— 它要是也翻车，说明仪器坏了）
- 阴性：至少一句 `OVERRIDE_FIRED=True`（否则「零误伤」和「那段根本没跑」
        是同一个读数）
"""
from __future__ import annotations

import asyncio
import functools

from smartbi.scripts._probe_bootstrap import bootstrap_probe

FID = "MOCK_REST"
ROUNDS = 3
ctx = bootstrap_probe(FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: 组A = 应当按店拆；组B = 应当全店汇总（那段覆盖的立身之本）
_STORE_DIM_QS = [
    ("A", "有几家店是亏钱的"),
    ("A", "多少家店在亏钱"),
    ("A", "有没有店是亏钱的"),          # 已知答对 —— 阳性对照
    ("A", "哪家店毛利最低"),
    ("B", "全部门店最近30天卖了多少钱"),
    ("B", "所有门店最近30天的毛利是多少"),
    ("B", "最近30天总营收是多少"),
    ("B", "全店汇总的客单价是多少"),
]

_CALLS: list[dict] = []


def _install():
    orig = ri._build_spec

    @functools.wraps(orig)
    def _spy(code, query, **kw):
        _CALLS.append({
            "code": code,
            "llm_dimensions": kw.get("llm_dimensions"),
            "llm_store": kw.get("llm_store"),
            "llm_store_scope": kw.get("llm_store_scope"),
        })
        return orig(code, query, **kw)

    ri._build_spec = _spy


def _clear() -> list[str]:
    done = []
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, n)()
        done.append(n)
    return done


def _fmt(v):
    return "()" if v in (None, (), []) else repr(v)


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s 轮数=%d 句数=%d" % (ctx.db_name, FID, ROUNDS, len(_STORE_DIM_QS)))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2
    set_factory_id(FID)
    async with rr.dish_catalogue_scope(pool, FID):
        cat = rr.current_dish_catalogue()
    print("阳性对照·菜单目录 = %d 条" % len(cat or ()))
    if not cat:
        print("⛔ rc=2 菜单目录为 0")
        return 2

    _install()
    hook_calls = 0
    fired_any = 0
    rows = []

    for rnd in range(1, ROUNDS + 1):
        for grp, q in _STORE_DIM_QS:
            cleared = _clear()
            _CALLS.clear()
            set_factory_id(FID)
            async with rr.dish_catalogue_scope(pool, FID):
                spec = await ri.parse_restaurant_query(
                    q, pool, factory_id=FID,
                    session_key="sdo-r%d-%s" % (rnd, q), semantic_first=True)
            res = None
            if spec is not None and svc.should_delegate(spec, None, query=q):
                res = await svc.tiered_answer(
                    q, pool, FID, ctx.role, precomputed_spec=spec,
                    session_key="sdo-r%d-%s" % (rnd, q))
            kind = (res or {}).get("kind") or "-"
            text = (res or {}).get("answer_text") or ""
            hook_calls += len(_CALLS)

            llm_dims = None
            for c in _CALLS:
                if c["llm_dimensions"] is not None:
                    llm_dims = tuple(c["llm_dimensions"])
                    break
            final_dims = tuple(getattr(spec, "dimensions", ()) or ()) if spec else ()
            scope = getattr(spec, "store_scope", None) if spec else None
            breakdown = ri._asks_store_breakdown(q)
            # 🔴 那段有没有真的开火：LLM 给了 store、最终没有、且两个前提都成立
            fired = bool(
                llm_dims and "store" in llm_dims
                and "store" not in final_dims
                and scope == "all" and not breakdown)
            if fired:
                fired_any += 1

            rows.append((rnd, grp, q, llm_dims, scope, breakdown, fired,
                         final_dims, getattr(spec, "intent", None), kind))

            print("\n" + "=" * 76)
            print("r%d [%s] %s" % (rnd, grp, q))
            print("  清了: %s   钩子 %d 次" % ("、".join(cleared), len(_CALLS)))
            print("  llm_dimensions      = %s" % _fmt(llm_dims))
            print("  llm 给的 resolver    = %s" % (_CALLS[0]["code"] if _CALLS else "-"))
            print("  store_scope         = %s" % _fmt(scope))
            print("  _asks_store_breakdown = %s" % breakdown)
            print("  🔴 OVERRIDE_FIRED   = %s" % fired)
            print("  最终 dimensions      = %s" % _fmt(final_dims))
            print("  最终 intent          = %s" % getattr(spec, "intent", None))
            print("  kind=%s n=%d" % (kind, len(text)))
            print("  老板看到（首 160 字）: %s" % text[:160].replace("\n", " ⏎ "))

    print("\n" + "=" * 76)
    print("汇总（⛔ 逐行，不折叠）")
    print("%-3s %-3s %-22s %-14s %-6s %-6s %-16s %s" % (
        "r", "组", "问句", "llm_dims", "覆盖", "最终", "intent", "kind"))
    for (rnd, grp, q, ld, _sc, _bd, fired, fd, it, kind) in rows:
        print("%-3s %-3s %-22s %-14s %-6s %-6s %-16s %s" % (
            rnd, grp, q[:22],
            ("store" if (ld and "store" in ld) else _fmt(ld))[:14],
            "🔴删" if fired else "-",
            "store" if "store" in fd else "()",
            (it or "").replace("RESTAURANT_OPS_", "")[:16], kind))

    print("\n阳性对照·钩子总调用 = %d" % hook_calls)
    if hook_calls == 0:
        print("⛔ rc=2 钩子没挂上，读数作废")
        return 2
    print("阴性对照·OVERRIDE_FIRED 出现 = %d 次（0 次 ⇒ 那段没跑，读数作废）" % fired_any)
    if fired_any == 0:
        print("⛔ rc=2 那段一次都没开火，分不清「零误伤」和「没跑到」")
        return 2
    ctrl = [r for r in rows if r[2] == "有没有店是亏钱的"]
    ok = sum(1 for r in ctrl if "store" in r[7])
    print("阳性对照·「有没有店是亏钱的」保住 store 维度 = %d/%d 轮" % (ok, len(ctrl)))
    if ok == 0:
        print("⛔ rc=2 已知答对的那句也翻车了 —— 仪器有问题")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
