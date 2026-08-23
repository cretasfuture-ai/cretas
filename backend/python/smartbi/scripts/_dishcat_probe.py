# -*- coding: utf-8 -*-
"""菜单核对 / 菜品归一 —— 老板真正看到的原文，前后对比用同一个仪器。

跑法（prod 部署树，只读）::

    python -m smartbi.scripts._dishcat_probe            # 单轮 -> 快
    ROUNDS=3 python -m smartbi.scripts._dishcat_probe   # 三轮 -> 收结论用

⛔ 每条读数带来源标记（`authority` / `tier` / `catalogue_n`），
   否则分不清「产品答不上」和「探针自己没 key / 目录没加载」。
"""
from __future__ import annotations

import asyncio
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))

from smartbi.scripts._probe_bootstrap import (  # noqa: E402
    assert_not_an_llm_artifact,
    bootstrap_probe,
)

ctx = bootstrap_probe("MOCK_REST")

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.gold.restaurant.restaurant_intent_service import (  # noqa: E402
    tiered_answer,
)
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

FID = "MOCK_REST"
ROUNDS = int(os.environ.get("ROUNDS", "1"))

#: 每条 = (标签, 问句, 上一轮问句或 None)
#: ⛔ 每一条都要说清「它想量什么」，否则读数出来不知道该怎么读。
_DISHCAT_ANSWER_CASES = [
    # ① 核对：计算式指代被 LLM 当成菜名（owner 2026-08-17 prod 实拍）
    ("A-最差-追问", "那最差的呢", "哪道菜卖得最好"),
    ("A-最差-单轮", "最差的那道菜卖得怎么样", None),
    ("A-最好-单轮", "最好的那道菜毛利多少", None),
    # ② 核对：菜单里真没有的菜名（新菜刚上架）—— ⛔ 绝不能静默丢掉
    ("B-新菜", "香辣蟹煲卖得怎么样", None),
    ("B-新菜2", "佛跳墙这个月毛利多少", None),
    # ③ 阳性对照：菜单里真有的菜 —— 不许被改动碰到
    ("C-真菜", "米饭卖得怎么样", None),
    # ④ 归一：菜名简称（③ 号任务）
    ("D-简称", "招牌藤椒味卖得怎么样", None),
    # ⑤ 无菜对照：改动不该碰它
    ("E-无菜", "这个月营业额多少", None),
]

_CACHE_HELPERS = (
    "clear_semantic_plan_cache",
    "clear_route_cache",
    "clear_tenant_gate_cache",
    "clear_promoted_routes_cache",
)


def clear_caches(verbose: bool = False) -> list:
    """⛔ 用模块自己的 helper —— 拼错的属性名不会报错，只会静默不清。"""
    cleared = []
    for name in _CACHE_HELPERS:
        getattr(ri, name)()          # AttributeError 当场炸，⛔ 不给默认值
        cleared.append(name)
    if verbose:
        print("清了缓存: %s" % "、".join(cleared))
    return cleared


async def run_turn(pool, query: str, session_key: str, history=None) -> dict:
    """复刻 `restaurant_intent_service` 的生产序列（含 dish_catalogue_scope）。"""
    set_factory_id(FID)
    async with rr.dish_catalogue_scope(pool, FID):
        catalogue = rr.current_dish_catalogue()
        spec = await ri.parse_restaurant_query(
            query, pool, factory_id=FID, session_key=session_key,
            history=history, semantic_first=True,
        )
        # 🔴 字段名是 `dish_slot`，⛔ 不是 `dish`。
        #    我第一版写的是 `getattr(spec, "dish", None)` —— 那个属性**不存在**，
        #    于是这一列**每一行都读成 None**，看起来像「LLM 从来不填菜名槽位」。
        #    ▎形态 A：我量的这个数，不是我真正想知道的那个数。
        #    ⛔ 不要在这里加 `or getattr(spec, "dish", None)` 兜底 ——
        #      读不到就该炸，兜底会把「我不知道」翻译成「是 None」。
        dish = spec.dish_slot if spec else None
        # 目录裁决必须在 scope **内**读 —— 出了作用域 contextvar 已复位
        verdict = bool(dish) and rr._catalogue_says_not_a_dish(dish)
    row = {
        "q": query,
        "catalogue_n": len(catalogue) if catalogue else 0,
        "dish": dish,
        "catalogue_says_not_a_dish": verdict,
    }
    if spec is None:
        return row | {"kind": "spec-None", "authority": None, "text": ""}
    row |= {
        "authority": spec.planner_authority,
        "tier": spec.source_tier,
        "intent": (spec.intent or "").replace("RESTAURANT_OPS_", "") or None,
        "dims": list(spec.dimensions),
        "clar": bool(spec.clarification_needed),
        "_spec": spec,
    }
    result = await tiered_answer(
        query, pool, FID, ctx.role, precomputed_spec=spec,
        session_key=session_key, history=history,
    )
    if not result:
        return row | {"kind": "None", "text": ""}
    return row | {
        "kind": result.get("kind") or "-",
        "code": (result.get("code") or "").replace("RESTAURANT_OPS_", ""),
        "contract": result.get("contract_pass"),
        "text": result.get("answer_text") or "",
        "n_charts": len(result.get("charts") or []),
        "n_kpis": len(result.get("kpis") or []),
    }


async def main() -> int:
    pool = await ctx.pool()
    if getattr(ctx, "llm_dead_slots", None):
        print("rc=2 LLM 死槽: %s —— 每条答案都会是 fail-closed 拒答" %
              (ctx.llm_dead_slots,))
        return 2

    set_factory_id(FID)
    async with rr.dish_catalogue_scope(pool, FID):
        cat = sorted(rr.current_dish_catalogue() or ())
        print("菜单目录 %d 条: %s" % (len(cat), cat))
        if not cat:
            print("rc=2 目录为空 —— 判据恒 False，读数无意义")
            return 2
        # 🔴 阳性对照：目录里真有的菜，判据必须说「是菜」
        bad_pos = [n for n in cat if rr._catalogue_says_not_a_dish(n)]
        if bad_pos:
            print("rc=2 阳性对照失败: 目录里的 %s 被判成「不是菜」" % bad_pos[:3])
            return 2
        # 阴性对照：一个必然不是菜的词
        if not rr._catalogue_says_not_a_dish("最差"):
            print("rc=2 阴性对照失败:「最差」被判成「是菜」")
            return 2
        print("对照 ✅  目录内 %d 条全判「是菜」/「最差」判「不是菜」" % len(cat))
    clear_caches(verbose=True)
    print()

    rows = []
    for rnd in range(ROUNDS):
        for tag, q, prev in _DISHCAT_ANSWER_CASES:
            clear_caches()
            history = None
            skey = "dcp-%s-r%d" % (tag, rnd)
            if prev:
                first = await run_turn(pool, prev, skey)
                history = [
                    {"role": "user", "content": prev},
                    {"role": "assistant", "content": first.get("text", "")},
                ]
            row = await run_turn(pool, q, skey, history=history)
            row["tag"] = tag
            row["round"] = rnd
            row["prev"] = prev
            rows.append(row)
            print("[%s r%d] dish=%r 非菜=%s kind=%s code=%s clar=%s "
                  "authority=%s dims=%s n=%d"
                  % (tag, rnd, row.get("dish"),
                     row.get("catalogue_says_not_a_dish"), row.get("kind"),
                     row.get("code"), row.get("clar"), row.get("authority"),
                     row.get("dims"), len(row.get("text", ""))))

    assert_not_an_llm_artifact(
        [r.get("_spec") for r in rows if r.get("_spec") is not None],
        llm_dead_slots=ctx.llm_dead_slots,
    )

    print("\n" + "=" * 92)
    print("老板看到的原文")
    print("=" * 92)
    for r in rows:
        print("\n### [%s r%d] %r  (上一轮: %r)" %
              (r["tag"], r["round"], r["q"], r["prev"]))
        print("    dish=%r  目录说不是菜=%s  kind=%s  code=%s  contract=%s "
              "charts=%s kpis=%s"
              % (r.get("dish"), r.get("catalogue_says_not_a_dish"),
                 r.get("kind"), r.get("code"), r.get("contract"),
                 r.get("n_charts"), r.get("n_kpis")))
        print("    ---8<---")
        for line in (r.get("text") or "(空)").splitlines()[:24]:
            print("    | " + line)
        print("    --->8---")

    out = os.environ.get("DUMP_JSON")
    if out:
        with open(out, "w", encoding="utf-8", newline="") as fh:
            json.dump([{k: v for k, v in r.items() if k != "_spec"}
                       for r in rows], fh, ensure_ascii=False, indent=1)
        print("\nJSON -> %s" % out)
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
