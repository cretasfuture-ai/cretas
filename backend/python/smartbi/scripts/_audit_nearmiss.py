# -*- coding: utf-8 -*-
"""近似菜名（打错字 / 加后缀）+ 时间给全 ⇒ 老板到底拿到什么。

## 起点（审计任务 ① 的读数）

    麻婆豆腐 / 宫爆鸡丁   3/3 ✅ 拿到目录反馈
    **水煮牛肉丝**        0/3 🔴 拿到的是 56 字时间反问

成因（读码 + 下面的判据自检双证）：`_catalogue_says_not_a_dish` 的匹配语义里有
「**菜名是候选的子串** ⇒ 不否决」，而「水煮牛肉」是「水煮牛肉丝」的子串
⇒ 它被判成**在**菜单里。

## 本探针要回答的

老板打「水煮牛肉丝」（多打一个字），**时间给全之后**拿到什么？
两种可能，产品含义完全不同：

    ✅ 目录反馈      「菜单里没查到「水煮牛肉丝」」—— 对的
    🔴 **一句假话**  「「水煮牛肉丝」**在菜单里**，但这个时间范围内一份都没卖出去」
                     —— 它断言了一件不成立的事

⛔ 不许从代码推 —— 跑一次让它自己说。

## 对照

    麻婆豆腐（完全不像任何真菜）—— 已知 ✅，用来证明这条路是通的
    水煮牛肉（真菜）           —— 阳性对照，必须正常出数
"""
from __future__ import annotations

import asyncio
import os
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_NM_FID = "MOCK_REST"
_NM_ROUNDS = 2
_NM_RUN = "%d-%d" % (os.getpid(), int(time.time()))
ctx = bootstrap_probe(_NM_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

_NM_CASES = [
    ("🔴近似", "最近30天水煮牛肉丝卖得怎么样"),
    ("✅对照·假菜", "最近30天麻婆豆腐卖得怎么样"),
    ("✅对照·真菜", "最近30天水煮牛肉卖得怎么样"),
]
#: 「在菜单里但没卖出去」那一支的结构标记 —— ⛔ 只用来分档，判定看它是否成立。
_NM_IN_MENU_CLAIM = "在菜单里"


def _clear():
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, n)()


async def main() -> int:
    pool = await ctx.pool()
    set_factory_id(_NM_FID)
    async with rr.dish_catalogue_scope(pool, _NM_FID):
        cat = tuple(rr.current_dish_catalogue() or ())
    print("PROBE_DB=%s  目录 = %d 条  RUN=%s" % (ctx.db_name, len(cat), _NM_RUN))
    if len(cat) < 4:
        print("⛔ rc=2 目录不足，读数作废")
        return 2

    # ⚠️ 2026-08-19 订正：第一版只打 `_catalogue_says_not_a_dish` 一条。
    #    那一刀修好之后它对「水煮牛肉丝」**仍然**是 False（新判据是另起的一个），
    #    于是这行自检会打出「被判『不是菜』=False」而产品**已经正确拒了** ——
    #    ▎一个陈旧的仪器读数，会让下一个人以为没修好。
    # ⇒ 两条都打，并打出产品实际用的那个**组合**。
    print("\n判据自检（⛔ 这是读码那一半的证据，行为那一半在下面）:")
    async with rr.dish_catalogue_scope(pool, _NM_FID):
        for d in ("水煮牛肉丝", "麻婆豆腐", "水煮牛肉"):
            a = rr._catalogue_says_not_a_dish(d)
            b = rr._catalogue_says_a_longer_name(d)
            print("  %-12s 在目录里=%-6s 完全不像=%-6s 比菜单长=%-6s 组合(产品用的)=%s"
                  % (d, d in cat, a, b, a or b))

    rows = []
    for rnd in range(1, _NM_ROUNDS + 1):
        for tag, q in _NM_CASES:
            _clear()
            set_factory_id(_NM_FID)
            skey = "nm-%s-r%d-%s" % (_NM_RUN, rnd, q)
            async with rr.dish_catalogue_scope(pool, _NM_FID):
                spec = await ri.parse_restaurant_query(
                    q, pool, factory_id=_NM_FID, session_key=skey,
                    semantic_first=True)
                res = None
                if spec is not None and svc.should_delegate(spec, None, query=q):
                    res = await svc.tiered_answer(
                        q, pool, _NM_FID, ctx.role,
                        precomputed_spec=spec, session_key=skey)
            t = (res or {}).get("answer_text") or ""
            kind = (res or {}).get("kind") or "-"
            slot = getattr(spec, "dish_slot", None) if spec else None
            auth = getattr(spec, "planner_authority", None) if spec else None
            # 结构判定：产品有没有**断言这道菜在菜单里**，而它其实不在
            claims_in_menu = _NM_IN_MENU_CLAIM in t and (slot or "") in t
            really_in = (slot or "") in cat
            rows.append((rnd, tag, q, kind, slot, auth, claims_in_menu, really_in))

            print("\n" + "=" * 76)
            print("r%d 【%s】%s" % (rnd, tag, q))
            print("  kind=%s  dish_slot=%s  authority=%s" % (kind, slot, auth))
            print("  产品断言「%s 在菜单里」= %s   而它真的在目录里 = %s"
                  % (slot, claims_in_menu, really_in))
            if claims_in_menu and not really_in:
                print("  🔴🔴 **产品说了一句不成立的话**")
            print("  ── 老板看到的原文（前 260 字）──")
            print("    " + t[:260].replace(chr(10), "\n    "))

    print("\n" + "=" * 76)
    print("汇总（⛔ 逐行）")
    print("%-3s %-14s %-14s %-12s %-10s %s"
          % ("r", "组", "kind", "dish_slot", "真在目录", "断言在菜单里"))
    for (rnd, tag, q, kind, slot, auth, claim, really) in rows:
        print("%-3s %-14s %-14s %-12s %-10s %s"
              % (rnd, tag, kind, slot, really, claim))

    real = [r for r in rows if r[1] == "✅对照·真菜"]
    real_ans = sum(1 for r in real if r[3] == "answer")
    print("\n阳性对照·真菜出数 = %d/%d" % (real_ans, len(real)))
    if real_ans == 0:
        print("⛔ rc=2 连真菜都答不上 —— 整条链没跑起来，读数作废")
        return 2

    lie = [r for r in rows if r[6] and not r[7]]
    print("\n🔑 判定")
    print("  产品断言「X 在菜单里」而 X 其实不在 = %d/%d 次" % (len(lie), len(rows)))
    if lie:
        print("  ⇒ 🔴🔴 **产品说了一句不成立的话** —— 比「先问时间」严重：")
        print("     那边是白问一轮，这边是**断言了一件不存在的事实**。")
        print("     ▎撞反目标最重那条：一条误发的提示，烧掉的是「这东西说的话能信」。")
        return 1
    print("  ⇒ ✅ 没有出现这种断言")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
