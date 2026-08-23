# -*- coding: utf-8 -*-
"""LLM 到底给了什么 —— 「有几家店是亏钱的」的 `_build_spec` 入参逐格打出来。

## 为什么必须先量这个

`_lossmaking` 已经把**结果**量清楚了（3/3 轮一致）：

    目标句  intent=GROSS_MARGIN  dimensions=()   store_slot='有几家店'
    对照句  intent=STORE_MARGIN  dimensions=('store',)  store_slot=()

但**结果**分不清两种成因，而两种的修法相反：

    (甲) LLM 给了 dimensions=['store']，被 `restaurant_intent.py:2505-2510`
         那段 `store_scope == "all" and not _asks_store_breakdown(...)` 删掉了
         ⇒ 缺陷在那段删除逻辑
    (乙) LLM 没给 store 维度，"store" 是 2485 行从**假槽位** `store_slot='有几家店'`
         派生出来的，删掉它反而是对的
         ⇒ 缺陷在「假店名没被目录核对丢掉」（owner 的 A）

▎⛔ 从代码推不出来 —— 这两条都跑得通同一段代码。跑一次让它自己说。
   （测量纪律 5：不从注释/文档推行为。）

## 钩子

`_build_spec` 在 `restaurant_intent` 里被 20+ 处以**模块全局**方式调用，
全仓只有一个测试文件 from-import 它 ⇒ 钩 `ri._build_spec` 一处即可覆盖读路径。
⚠️ 测量纪律 3：`functools.wraps` 保签名。这里用 `*a, **kw` 透传 + wraps。

## 阳性/阴性对照（⛔ 不许省）

- 阳性：对照句「有没有店是亏钱的」必须至少一轮 `kind == "answer"`
        且钩子必须**至少被调用一次** —— 否则「llm_dimensions 全空」
        和「我的钩子没挂上」是同一个读数（memory：形态 A 的老坑）。
- 阴性：钩子调用计数逐句打出来，⛔ 不只打汇总。
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

_LLM_RAW_QS = [
    ("🔴目标", "有几家店是亏钱的"),
    ("✅对照", "有没有店是亏钱的"),
]

#: 只打这几个入参 —— 它们唯一决定 2485/2505 那两段的走向。
_WATCH_KW = (
    "llm_dimensions", "llm_store", "llm_stores", "llm_store_scope",
    "llm_requested_metrics", "llm_semantics_authoritative",
    "allow_explicit_slot_repair", "planner_authority", "tier",
)

_CALLS: list[tuple] = []


def _install():
    orig = ri._build_spec

    @functools.wraps(orig)
    def _spy(code, query, **kw):
        _CALLS.append((code, query, {k: kw.get(k) for k in _WATCH_KW}))
        return orig(code, query, **kw)

    ri._build_spec = _spy
    return orig


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
    print("PROBE_DB=%s FID=%s 轮数=%d" % (ctx.db_name, FID, ROUNDS))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2

    set_factory_id(FID)
    async with rr.dish_catalogue_scope(pool, FID):
        cat = rr.current_dish_catalogue()
    print("阳性对照·菜单目录 = %d 条" % len(cat or ()))
    if not cat:
        print("⛔ rc=2 菜单目录为 0 —— 库没连上，读数作废")
        return 2

    _install()
    total_hook_calls = 0
    n_answer_control = 0

    for rnd in range(1, ROUNDS + 1):
        for tag, q in _LLM_RAW_QS:
            cleared = _clear()
            _CALLS.clear()
            set_factory_id(FID)
            async with rr.dish_catalogue_scope(pool, FID):
                spec = await ri.parse_restaurant_query(
                    q, pool, factory_id=FID,
                    session_key="lmraw-r%d-%s" % (rnd, tag), semantic_first=True)
            res = None
            if spec is not None and svc.should_delegate(spec, None, query=q):
                res = await svc.tiered_answer(
                    q, pool, FID, ctx.role, precomputed_spec=spec,
                    session_key="lmraw-r%d-%s" % (rnd, tag))
            kind = (res or {}).get("kind") or "-"
            if tag == "✅对照" and kind == "answer":
                n_answer_control += 1
            total_hook_calls += len(_CALLS)

            print("\n" + "=" * 76)
            print("r%d 【%s】%s" % (rnd, tag, q))
            print("  清了: %s" % "、".join(cleared))
            print("  钩子被调用 %d 次   ← 0 次 ⇒ 下面的读数全部作废" % len(_CALLS))
            for i, (code, _query, kw) in enumerate(_CALLS, 1):
                print("  ── _build_spec 第 %d 次 ──" % i)
                print("    code(LLM 给的 resolver) = %s" % code)
                for k in _WATCH_KW:
                    print("    %-28s= %s" % (k, _fmt(kw.get(k))))
            print("  ── 最终 spec ──")
            print("    intent=%s  dimensions=%s  store_slot=%s  kind=%s" % (
                getattr(spec, "intent", None),
                _fmt(tuple(getattr(spec, "dimensions", ()) or ())),
                _fmt(getattr(spec, "store_slot", None)),
                kind))
            print("  ── 判据：这一句到底是甲还是乙 ──")
            llm_dims = None
            for _c, _q, kw in _CALLS:
                if kw.get("llm_dimensions") is not None:
                    llm_dims = kw["llm_dimensions"]
                    break
            if llm_dims is None:
                print("    LLM 没给 dimensions（None）⇒ 走 _detect_dimensions 兜底")
            elif "store" in tuple(llm_dims):
                print("    🔴 甲：LLM **给了** store 维度，是代码删的")
            else:
                print("    🔴 乙：LLM **没给** store 维度 ⇒ store 是假槽位派生的")

    print("\n阳性对照·钩子总调用 = %d 次" % total_hook_calls)
    if total_hook_calls == 0:
        print("⛔ rc=2 钩子一次都没被调用 —— 读数作废")
        return 2
    print("阳性对照·对照组 kind==answer = %d/%d 轮" % (n_answer_control, ROUNDS))
    if n_answer_control == 0:
        print("⛔ rc=2 对照组一轮都没答上 —— 读数作废")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
