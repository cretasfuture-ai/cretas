# -*- coding: utf-8 -*-
"""缺口 #1 的 ② 那一支：**LLM 把非菜名填进 dish 槽位**时，老板看到什么。

⚠️ 为什么要单独一个探针：这一支**在 prod 上按需复现不了** ——
   owner 在 24 问句上量到 5 次 dish 非空、1 次不在菜单（「最差」），
   而我这几轮 `dish_slot` 一直是 None（LLM 正确编译成了 `ranking_direction`）。
   ⇒ 用 `dataclasses.replace` **把那个槽位钉上去**，其余全部走真实生产路径
     （真库、真 resolver、真 RBAC、真答案渲染）。

⛔ 这不是「构造样本让读数好看」：钉的是 owner **已经实测到过**的那个形状，
   而且钉完两侧都真跑一次执行链，答案文本是真的。
"""
from __future__ import annotations

import asyncio
import os
import sys
from dataclasses import replace

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))

from smartbi.scripts._probe_bootstrap import bootstrap_probe  # noqa: E402

ctx = bootstrap_probe("MOCK_REST")

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.gold.restaurant.restaurant_intent_service import (  # noqa: E402
    tiered_answer,
)
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

FID = "MOCK_REST"

#: (标签, 问句, 钉上去的 dish_slot)
_DISHCAT_FORCED_CASES = [
    ("最差-钉槽位", "那最差的呢", "最差"),
    ("最好-钉槽位", "最好的那道菜毛利多少", "最好"),
    # 阴性对照: 点名一道菜单里没有的菜, 槽位**不许**被摘
    ("新菜-钉槽位", "香辣蟹煲卖得怎么样", "香辣蟹煲"),
    # 阳性对照: 真菜, 什么都不该变
    ("真菜-钉槽位", "米饭最近30天卖得怎么样", "米饭"),
]

_HELPERS = ("clear_semantic_plan_cache", "clear_route_cache",
            "clear_tenant_gate_cache", "clear_promoted_routes_cache")


def clear_caches():
    for name in _HELPERS:
        getattr(ri, name)()


async def main() -> int:
    pool = await ctx.pool()
    if getattr(ctx, "llm_dead_slots", None):
        print("rc=2 LLM 死槽: %s" % (ctx.llm_dead_slots,))
        return 2
    set_factory_id(FID)
    async with rr.dish_catalogue_scope(pool, FID):
        cat = sorted(rr.current_dish_catalogue() or ())
    if not cat:
        print("rc=2 目录为空 —— 判据恒 False，读数无意义")
        return 2
    print("菜单 %d 条: %s\n" % (len(cat), cat))

    for tag, q, forced in _DISHCAT_FORCED_CASES:
        clear_caches()
        set_factory_id(FID)
        skey = "fc-" + tag
        async with rr.dish_catalogue_scope(pool, FID):
            spec = await ri.parse_restaurant_query(
                q, pool, factory_id=FID, session_key=skey, semantic_first=True)
        if spec is None:
            print("[%s] spec=None" % tag)
            continue
        pinned = replace(spec, dish_slot=forced)
        res = await tiered_answer(q, pool, FID, ctx.role,
                                  precomputed_spec=pinned, session_key=skey)
        text = (res or {}).get("answer_text") or ""
        print("=" * 88)
        print("[%s] %r" % (tag, q))
        print("  原 dish_slot=%r → 钉成 %r   ranking_direction=%r"
              % (spec.dish_slot, forced, spec.ranking_direction))
        print("  kind=%s  code=%s  披露出现=%s"
              % ((res or {}).get("kind"), (res or {}).get("code"),
                 ("菜单里没查到「%s」" % forced) in text))
        print("  老板看到 %d 字:" % len(text))
        for line in text.splitlines()[:16]:
            print("  | " + line)
        if len(text.splitlines()) > 16:
            print("  | …（省略 %d 行）" % (len(text.splitlines()) - 16))
            # 披露在末尾, 单独再打一次, ⛔ 不能被截断吃掉
            print("  | 【末尾三行】")
            for line in text.splitlines()[-3:]:
                print("  | " + line)
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
