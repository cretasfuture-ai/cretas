"""刀3 接线诊断：`dish_slot_after_catalogue_check` 在**真实入口**上到底被调了没有，
调的时候手里有没有目录，返回了什么。

## 为什么要有这个探针

端到端 A/B 读出「两臂逐字相同」。那有两种完全不同的成因：
  (a) 改动没用 —— 闸调了，但那条问句本来就不该走通名支路
  (b) **闸没被调到** / 调到了但目录是空的 ⇒ fail-open ⇒ 改动够不着

▎两者的修法相反，而端到端读数**分不开它们**。

⛔ 不从代码推 —— 包一层记录器，跑一次真实入口，让它自己说。
"""
from __future__ import annotations

import asyncio
import json
import os
import sys

from smartbi.scripts._probe_bootstrap import bootstrap_probe

ctx = bootstrap_probe(os.environ.get("PROBE_FID", "MOCK_REST"))

import importlib.util  # noqa: E402

OVERLAY = os.environ.get("LEX_OVERLAY", "")
if OVERLAY:
    _spec = importlib.util.spec_from_file_location(
        "smartbi.gold.restaurant.restaurant_ops_router",
        os.path.join(OVERLAY, "restaurant_ops_router.py"))
    _m = importlib.util.module_from_spec(_spec)
    sys.modules["smartbi.gold.restaurant.restaurant_ops_router"] = _m
    _spec.loader.exec_module(_m)

QUERIES = [
    "最近30天各门店菜品毛利分别是多少",
    "最近30天各门店水煮牛肉毛利分别是多少",
    "香辣蟹煲卖得怎么样",
    # ⚠️ 下面两句**不走** `_STORE_DISH_SPLIT_RE` 那条正则（没有「各门店…毛利」的形状），
    #    所以通名只可能由 LLM 直接填进 `spec.dish_slot`。
    #    ▎它们回答的是：闸里那条通名支路**在生产上到底还够不够得着**（形态 B）。
    "菜品毛利怎么样",
    "单品的销量怎么样",
]


async def main() -> int:
    from smartbi.gold.restaurant import restaurant_ops_router as R
    from smartbi.gold.restaurant import restaurant_intent as ri
    from smartbi.gold.restaurant import restaurant_intent_service as svc
    from smartbi.tenant_ctx import set_factory_id

    pool = await ctx.pool()
    fid = ctx.factory_id
    if ctx.llm_dead_slots:
        print("🔴 LLM 槽死了 —— rc=2")
        return 2

    print("router 来自 %s" % R.__file__)
    # 🔴 真标记：这份 router 里有没有通名支路（AST 数结构，⛔ 不 grep 源码字符串）
    import ast
    import inspect
    src = inspect.getsource(R.dish_slot_after_catalogue_check)
    names = {n.id for n in ast.walk(ast.parse(src.lstrip()))
             if isinstance(n, ast.Name)}
    print("标记·闸里引用了 _DISH_GENERIC_TOKENS = %s" % ("_DISH_GENERIC_TOKENS" in names))

    calls = []
    original = R.dish_slot_after_catalogue_check

    def traced(dish_mention, *, ranking_direction):
        cat = R.current_dish_catalogue()
        out = original(dish_mention, ranking_direction=ranking_direction)
        calls.append({
            "dish_mention": dish_mention,
            "ranking_direction": ranking_direction,
            "catalogue_size": (len(cat) if cat else 0),
            "catalogue_bound": cat is not None,
            "is_generic": dish_mention in R._DISH_GENERIC_TOKENS if dish_mention else None,
            "catalogue_says_not_a_dish": (
                R._catalogue_says_not_a_dish(dish_mention) if dish_mention else None),
            "returned": list(out),
        })
        return out

    R.dish_slot_after_catalogue_check = traced

    # ── 第二层观测点：披露函数被调了没有（形态 B：每层单独确认，二分定位）──
    disclosures = []
    _orig_disc = R.dish_catalogue_miss_disclosure

    def traced_disc(mention):
        out = _orig_disc(mention)
        disclosures.append({"mention": mention, "len": len(out)})
        return out

    R.dish_catalogue_miss_disclosure = traced_disc
    # ⚠️ 调用点是 `from ... import` 拿的名字 —— 打在模块属性上够不够得着？
    #    实测由本探针的输出回答，⛔ 不靠推。见 `test_mutation_targets_are_reachable`。
    svc_ref = getattr(svc, "dish_slot_after_catalogue_check", None)
    print("标记·service 模块上有没有同名属性(from-import 的痕迹) = %s"
          % (svc_ref is not None))

    for q in QUERIES:
        for name in ("clear_semantic_plan_cache", "clear_route_cache",
                     "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
            getattr(ri, name)()
        calls.clear()
        set_factory_id(fid)
        async with R.dish_catalogue_scope(pool, fid):
            spec = await ri.parse_restaurant_query(
                q, pool, factory_id=fid, session_key="wire-%s" % abs(hash(q)),
                semantic_first=True)
        res = await svc.tiered_answer(
            q, pool, fid, ctx.role, precomputed_spec=spec,
            session_key="wire-%s" % abs(hash(q)))
        text = (res or {}).get("answer_text") or ""
        print("\n" + "=" * 74)
        print("「%s」" % q)
        print("  spec.dish_slot=%r  intent=%s  kind=%s  %d字"
              % (getattr(spec, "dish_slot", None), getattr(spec, "intent", None),
                 (res or {}).get("kind"), len(text)))
        print("  闸被调用 %d 次:" % len(calls))
        for c in calls:
            print("    %s" % json.dumps(c, ensure_ascii=False))
        if not calls:
            print("    🔴 一次都没调到 —— 改动够不着这条路（形态 B）")
        print("  披露函数被调 %d 次: %s" % (len(disclosures), disclosures))
        print("  正文里有没有披露标记「（说明：」= %s" % ("（说明：" in text))
        print("  正文前 200: %s" % text.replace("\n", " ")[:200])
        print("  正文末 200: %s" % text.replace("\n", " ")[-200:])
        disclosures.clear()
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
