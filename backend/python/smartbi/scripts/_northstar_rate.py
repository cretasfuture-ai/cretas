# -*- coding: utf-8 -*-
"""北极星那一问，到底多少轮答得上 —— 两条线的读数冲突，先量。

## 冲突

同一天、同一句问句、两条线两个读数：

    S 线（早些时候）  kind=answer         n=1358 / 1460，还带了归因⑥步
    归因线（刚刚）    kind=clarification  3/3 轮，`synthesize` 调用 **0** 次

⇒ **必须知道是哪一个错了**，因为这是北极星那一问，
   交付定义①「答得上」直接挂在它身上。

## 三种可能，读数要能分开它们

    (a) LLM 非确定性 —— 交接件登记过「16 次里 7 次返回反问」(44%)
                        ⇒ 3/3 clarification 在 44% 下的概率是 8.5%，不离奇
    (b) 两条线跑的树不同 —— 归因线在它自己的 worktree 上跑，没有 #2872
    (c) 今天某个改动引入的回归

⇒ 本探针跑在 **prod 活代码**上（含 #2872），**5 轮**，
   逐轮打 kind + 那一屏的抬头，⛔ 不只报比例。

## ⛔ 每轮清缓存并贴出清了哪几个（硬约束 3）

⚠️ 只清 `restaurant_intent` 那 4 个**不够** —— 这一句走 synthesis，
   而 `narrative_cache` 表 TTL 24h、`window_key` 含当天日期。
   ⇒ 同一句连跑 5 轮，第 2 轮起会命中第 1 轮写进去的叙述缓存，
     那时「5 轮一致」是构造出来的（形态 A¹³ 的原形）。
   ⇒ 本探针**绕过叙述缓存**，并把绕没绕过打出来。

## 阳性对照（⛔ 不许省）

「有没有店是亏钱的」—— 📏 已知它稳定答上（3/3）。
它也答不上 ⇒ 是执行链坏了，不是这一句难 ⇒ rc=2，读数作废。
"""
from __future__ import annotations

import asyncio

from smartbi.scripts._probe_bootstrap import bootstrap_probe

FID = "MOCK_REST"
ROUNDS = 5
ctx = bootstrap_probe(FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

NORTHSTAR_Q = "我要不要关掉最差的那家店"
NORTHSTAR_CONTROL_Q = "有没有店是亏钱的"


def _clear():
    done = []
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, n)()
        done.append(n)
    return done


def _bypass_narrative_cache() -> list[str]:
    """⛔ 不绕过的话，第 2 轮起读的是第 1 轮写进去的答案。"""
    import smartbi.agent.synthesis_engine as se
    hit = []
    for name in ("_narrative_cache_get", "_load_narrative_cache",
                 "_narrative_cache_lookup", "get_cached_narrative",
                 "narrative_cache_get"):
        if hasattr(se, name):
            setattr(se, name, lambda *a, **k: None)
            hit.append(name)
    try:
        import smartbi.agent.narrative_cache as nc
        for name in ("get", "lookup", "fetch"):
            if hasattr(nc, name):
                setattr(nc, name, lambda *a, **k: None)
                hit.append("narrative_cache." + name)
    except Exception:  # noqa: BLE001
        pass
    return hit


async def _ask(pool, q, key):
    _clear()
    set_factory_id(FID)
    async with rr.dish_catalogue_scope(pool, FID):
        spec = await ri.parse_restaurant_query(
            q, pool, factory_id=FID, session_key=key, semantic_first=True)
    if spec is None or not svc.should_delegate(spec, None, query=q):
        return spec, "no-delegate", ""
    res = await svc.tiered_answer(q, pool, FID, ctx.role,
                                  precomputed_spec=spec, session_key=key)
    return spec, ((res or {}).get("kind") or "-"), \
        ((res or {}).get("answer_text") or "")


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s 轮数=%d" % (ctx.db_name, FID, ROUNDS))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2
    bypassed = _bypass_narrative_cache()
    print("绕过的叙述缓存入口: %s" % (bypassed or "⚠️ 一个都没找到 —— 读数可能是缓存"))

    set_factory_id(FID)
    async with rr.dish_catalogue_scope(pool, FID):
        cat = rr.current_dish_catalogue()
    print("菜单目录 = %d 条" % len(cat or ()))
    if not cat:
        print("⛔ rc=2 库没连上")
        return 2

    kinds = []
    for rnd in range(1, ROUNDS + 1):
        spec, kind, text = await _ask(pool, NORTHSTAR_Q, "ns-r%d" % rnd)
        kinds.append(kind)
        head = (text or "").strip().splitlines()
        print("\n" + "=" * 74)
        print("r%d  kind=%s  n=%d  dims=%s  intent=%s" % (
            rnd, kind, len(text),
            tuple(getattr(spec, "dimensions", ()) or ()) if spec else (),
            getattr(spec, "intent", None) if spec else None))
        print("    " + (head[0][:150] if head else "(空)"))
        if kind != "answer" and text:
            print("    ⚠️ 不是答案，全文: " + text[:260].replace("\n", " "))

    # 🔴 阳性对照：已知稳定答上的那一句
    _spec, ck, ctext = await _ask(pool, NORTHSTAR_CONTROL_Q, "ns-ctl")
    print("\n阳性对照【%s】kind=%s n=%d" % (NORTHSTAR_CONTROL_Q, ck, len(ctext)))

    n_answer = sum(1 for k in kinds if k == "answer")
    print("\n📏 北极星那一问: kind==answer %d/%d 轮  逐轮=%s"
          % (n_answer, ROUNDS, kinds))
    if ck != "answer":
        print("⛔ rc=2 阳性对照也没答上 —— 执行链坏了，本次读数作废")
        return 2
    return 0 if n_answer == ROUNDS else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
