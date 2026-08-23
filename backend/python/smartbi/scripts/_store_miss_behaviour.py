# -*- coding: utf-8 -*-
"""门店侧的同形缺口：点名一个租户没有的门店 + 缺时间 ⇒ 先拿到哪一句？

## 为什么量它

2026-08-19 已修 **dish 侧**：菜名不在菜单里时先说菜单，⛔ 不先问时间。
判据是「问之前先看这个问题问了有没有用」——菜名不存在时，
老板把时间答出来产品**照样给不出**。

`restaurant_intent.py` 里有一支**逐字同构**的分支：

    # ⛔ 用户**点名了**一家门店但租户名单里没有(打错字/改名/已停用) —— 仍然问。
    if spec.store_slot and spec.store_slot not in names:
        ... clarification_question=STORE_SCOPE_CLARIFICATION_QUESTION ...

▎它排在 `store+time` 时间反问**之后** —— 与 dish 侧修之前**同一个顺序问题**。

⇒ 这份探针就是那条解冻条件的判据本身。

## 三组样本

    ①真店+缺时间   租户名单里有的店 —— **阴性对照**，它必须仍走时间反问（⛔ 不误伤）
    ②假店+缺时间   🔴 被测的那一格
    ③假店+给全时间  证明门店反馈**在**（只是够不着）—— 与 dish 侧 ④ 同一个作用

## 判定（结构判据，⛔ 不匹配文案措辞）

    ✅ 说门店     答案里报出了**问句里没有的**租户门店名（≥2 个）
                 —— 只有真的拿到门店名单才说得出来
    🔴 先问时间   拿到的是 `COMBINED_SCOPE_TIME_CLARIFICATION_QUESTION`
                 ⇒ 与 dish 侧修之前同形，是同一个缺口
    ⚠️ 别的      人读原文

## 阳性/阴性对照（⛔ 不许省）

- 阳性：门店名单必须 ≥ 2 家（读不到就分不清「没说门店」和「没名单」）
- 阴性：真店那一组必须**仍然**走时间反问 —— 否则说明判据把真店也拦了
"""
from __future__ import annotations

import asyncio
import os
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_SM_FID = "MOCK_REST"
_SM_ROUNDS = 3
_SM_RUN_ID = "%d-%d" % (os.getpid(), int(time.time()))
ctx = bootstrap_probe(_SM_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: 租户名单里必然没有的门店名。⛔ 挑一个**像门店名**的，不是乱码 ——
#: 乱码会被别的启发式挡住，量不到这一层。
_SM_FAKE_STORE = "北京三里屯店"


def _clear() -> list[str]:
    done = []
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, n)()
        done.append(n)
    return done


def _quoted_stores(text, names, query):
    """答案里报出的、**问句里没有的**租户门店名 —— 结构判据。"""
    return [n for n in (names or ())
            if n and n in (text or "") and n not in (query or "")]


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s 轮数=%d RUN_ID=%s"
          % (ctx.db_name, _SM_FID, _SM_ROUNDS, _SM_RUN_ID))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2

    set_factory_id(_SM_FID)
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", _SM_FID)
            rows = await conn.fetch(
                "SELECT name FROM dim_store WHERE factory_id = $1 ORDER BY name",
                _SM_FID)
    names = tuple(r["name"] for r in rows)
    print("阳性对照·门店名单 = %d 家: %s" % (len(names), list(names)[:4]))
    if len(names) < 2:
        print("⛔ rc=2 门店名单不足 2 家 —— 分不清「没说门店」和「没名单」，读数作废")
        return 2
    if _SM_FAKE_STORE in names:
        print("⛔ rc=2 我挑的假店 %s 居然在名单里 —— 换一个" % _SM_FAKE_STORE)
        return 2
    real_store = sorted(names)[0]
    print("阳性对照·真店取名单第 1 家 = %s" % real_store)

    samples = [
        ("①真店+缺时间", "%s卖得怎么样" % real_store),
        ("②假店+缺时间", "%s卖得怎么样" % _SM_FAKE_STORE),
        ("③假店+给时间", "最近30天%s卖得怎么样" % _SM_FAKE_STORE),
    ]

    n_real_time_q = 0
    rows_out = []
    for rnd in range(1, _SM_ROUNDS + 1):
        for tag, q in samples:
            cleared = _clear()
            set_factory_id(_SM_FID)
            skey = "sm-%s-r%d-%s" % (_SM_RUN_ID, rnd, tag)
            async with rr.dish_catalogue_scope(pool, _SM_FID):
                spec = await ri.parse_restaurant_query(
                    q, pool, factory_id=_SM_FID, session_key=skey,
                    semantic_first=True)
                res = None
                if spec is not None and svc.should_delegate(spec, None, query=q):
                    res = await svc.tiered_answer(
                        q, pool, _SM_FID, ctx.role,
                        precomputed_spec=spec, session_key=skey)
            kind = (res or {}).get("kind") or "-"
            text = (res or {}).get("answer_text") or ""
            cq = getattr(spec, "clarification_question", None) if spec else None
            is_time_q = cq == ri.COMBINED_SCOPE_TIME_CLARIFICATION_QUESTION
            quoted = _quoted_stores(text, names, q)

            if tag == "①真店+缺时间" and is_time_q:
                n_real_time_q += 1

            # 🔴 判据订正（第一版是错的代理判据 —— 本轮第五次栽在这）：
            #    第一版写「答案里报出 ≥2 家名单里的门店名 ⇒ ✅ 说门店」。
            #    实测 r1/r2 被判成 ✅，而原文是**全店汇总答案**：
            #      「最近30天…覆盖 30 天、10 家门店，共 58,472 单。
            #        总营收 ¥21,042,226.51…表现最强的是模拟·徐汇美罗城店…」
            #    ——**一个字都没提「北京三里屯店不存在」**。
            #    全店汇总答案里自然会提到门店名 ⇒ 那个代理判据把
            #    **静默顶替**读成了「说门店」。
            # ⇒ 换**结构判据**：看 spec 的槽位与 kind，⛔ 不看答案里出现了什么词。
            _mslot = getattr(spec, "missing_slot", None) if spec else None
            if tag == "①真店+缺时间":
                verdict = "-（阴性对照）"
            elif kind == "clarification" and _mslot == "store":
                verdict = "✅ 正确反问（missing_slot=store）"
            elif kind == "answer":
                verdict = ("🔴🔴 静默顶替 —— 给了全店的数，一个字没提这家店不存在"
                           if _SM_FAKE_STORE not in (text or "")
                           else "⚠️ 给了答案但提到了那家店（人读原文）")
            elif is_time_q:
                verdict = "🔴 先问时间 —— 与 dish 侧修之前同形"
            else:
                verdict = "⚠️ 别的（人读原文）"

            rows_out.append((rnd, tag, kind, is_time_q, len(quoted), verdict))
            print("\n" + "=" * 76)
            print("r%d 【%s】%s" % (rnd, tag, q))
            print("  清了: %s" % "、".join(cleared))
            # 🔴 加 store_scope / store_slots / options —— `_apply_store_scope_guard`
            #    开头有 `or spec.store_scope` 早退, 它一旦有值, 5614 那个
            #    「点名门店不在名单 => 问门店」的分支**根本走不到**。
            #    ⛔ 这一格不打就分不清「分支没开火」和「分支被早退跳过」。
            print("  kind=%s  store_slot=%s  missing_slot=%s" % (
                kind,
                getattr(spec, "store_slot", None) if spec else None,
                getattr(spec, "missing_slot", None) if spec else None))
            print("  store_scope=%s  store_slots=%s  store_options=%d 家  intent=%s" % (
                getattr(spec, "store_scope", None) if spec else None,
                tuple(getattr(spec, "store_slots", ()) or ()) if spec else (),
                len(tuple(getattr(spec, "store_options", ()) or ()) if spec else ()),
                getattr(spec, "intent", None) if spec else None))
            print("  是不是那句 store+time 时间反问 = %s" % is_time_q)
            print("  答案里报出的名单门店（问句里没有的）= %d 家 %s"
                  % (len(quoted), quoted[:3]))
            print("  判定: %s" % verdict)
            print("  ── 老板看到的原文（前 300 字）──")
            print("    " + (text[:300].replace("\n", "\n    ") or "(空)"))

    print("\n" + "=" * 76)
    print("汇总（⛔ 逐行）")
    print("%-3s %-16s %-14s %-8s %-6s %s"
          % ("r", "组", "kind", "时间反问", "报店数", "判定"))
    for (rnd, tag, kind, tq, nq, v) in rows_out:
        print("%-3s %-16s %-14s %-8s %-6s %s" % (rnd, tag, kind, tq, nq, v[:36]))

    print("\n阴性对照·真店仍走时间反问 = %d/%d 轮" % (n_real_time_q, _SM_ROUNDS))
    if n_real_time_q == 0:
        print("⚠️ 真店没走时间反问 —— 那这条路上没有「时间反问在前」这个前提，")
        print("   ②的读数就不能拿来证明顺序问题。逐行看上表。")

    fake = [r for r in rows_out if r[1] == "②假店+缺时间"]
    n_silent = sum(1 for r in fake if r[5].startswith("🔴🔴"))
    n_ok = sum(1 for r in fake if r[5].startswith("✅"))
    n_timeq = sum(1 for r in fake if r[3])
    print("\n🔑 判定：②假店+缺时间（%d 轮）" % len(fake))
    print("  🔴🔴 静默顶替（给全店的数、零说明） = %d" % n_silent)
    print("  ✅ 正确反问（missing_slot=store）    = %d" % n_ok)
    print("  🔴 先问时间（dish 侧同形）           = %d" % n_timeq)
    if n_silent:
        print("\n  ⇒ 🔴🔴 **静默顶替成立** —— 老板点名一家不存在的门店，")
        print("     产品换成全店合计给了他一个数，**一个字没说那家店不存在**。")
        print("     ▎`restaurant_intent.py:5609` 的注释预言过这件事：")
        print("       「说了「东城店」而租户没这家店时补「全部」是**换了个问题回答**，")
        print("         他会拿到一个看着像答案、实际答的是别的事的数字。」")
        print("     ⇒ 与 dish 侧那一刀**不同形**：那边是白问一轮，这边是**给错答案**。")
        return 1
    if n_ok == len(fake):
        print("  ⇒ ✅ 每一轮都正确反问 —— 这条解冻条件不成立")
        return 0
    print("  ⇒ ⚠️ 混合 —— 逐行看上表，⛔ 别下单一结论")
    return 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
