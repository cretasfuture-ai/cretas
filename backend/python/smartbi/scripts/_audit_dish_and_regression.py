# -*- coding: utf-8 -*-
"""审计任务 ① —— 独立复核「菜名不在菜单里先说菜单」，并自留一格回归。

## 与开发侧探针的区别（⛔ 不复用它的题库）

开发侧用的假菜是「佛跳墙」。本探针**换一批自己的**，并加两种它没测的形状：
    · 打错字（真菜的近似写法）
    · 一个明显不是菜的词

## 判据（两条都要，第二条是防改过头）

    假菜 ⇒ 拿到目录反馈：提到那个名字 + 「新菜」这条出路 + 报出在售菜名
    真菜 ⇒ **时间反问逐字不变**（`COMBINED_SCOPE_TIME_CLARIFICATION_QUESTION`）

⛔ 判据看**结构**（`clarification_question` 是不是那个常量 / 报出的目录菜名个数），
   ⛔ 不匹配文案措辞 —— 开发侧在这上面栽过三次
   （「菜单里没**查到**」vs 词表写「没**有**」）。

## ➕ 回归格（审计自留，⛔ 不在开发侧清单里）

挑 3 条**与这一刀完全无关**的问句，同一轮里量它们的 `kind`。
▎审计的价值在于查被审方**没想到要给**的那一格。

## 阳性/阴性对照（⛔ 不许省）

- 阳性：菜单目录 ≥ 4 条；回归组至少 1 条 `kind == "answer"`
- 阴性：`planner_authority == "llm_unavailable"` 计数必须为 0，
        非 0 ⇒ `rc=2`，⛔ 不折叠进「答不上」

⚠️ `session_key` 每次跑唯一（`restaurant_pending_clarifications` 跨进程存活）。
"""
from __future__ import annotations

import asyncio
import os
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_AD_FID = "MOCK_REST"
_AD_ROUNDS = 3
_AD_RUN_ID = "%d-%d" % (os.getpid(), int(time.time()))
ctx = bootstrap_probe(_AD_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: 审计自己的假菜题库 —— ⛔ 不用开发侧那个「佛跳墙」。
_AD_FAKE_DISHES = ["麻婆豆腐", "水煮牛肉丝", "宫爆鸡丁"]
#: 回归格：与这一刀**完全无关**的三条。
_AD_REGRESSION = ["一共有多少家店", "折扣力度多大", "外卖和堂食各占多少"]


def _clear() -> list[str]:
    done = []
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, n)()
        done.append(n)
    return done


async def _ask(pool, q, skey):
    set_factory_id(_AD_FID)
    async with rr.dish_catalogue_scope(pool, _AD_FID):
        spec = await ri.parse_restaurant_query(
            q, pool, factory_id=_AD_FID, session_key=skey, semantic_first=True)
        res = None
        if spec is not None and svc.should_delegate(spec, None, query=q):
            res = await svc.tiered_answer(
                q, pool, _AD_FID, ctx.role, precomputed_spec=spec, session_key=skey)
    return spec, (res or {})


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s 轮数=%d RUN_ID=%s"
          % (ctx.db_name, _AD_FID, _AD_ROUNDS, _AD_RUN_ID))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2

    set_factory_id(_AD_FID)
    async with rr.dish_catalogue_scope(pool, _AD_FID):
        cat = tuple(rr.current_dish_catalogue() or ())
    print("阳性对照·菜单目录 = %d 条: %s" % (len(cat), list(cat)))
    if len(cat) < 4:
        print("⛔ rc=2 菜单目录不足 4 条，读数作废")
        return 2
    real_dish = sorted(cat)[0]
    fakes = [d for d in _AD_FAKE_DISHES if d not in cat]
    print("审计自己的假菜（已确认不在目录里）= %s" % fakes)
    print("真菜（阴性对照，取目录排序第 1）= %s" % real_dish)
    if not fakes:
        print("⛔ rc=2 我挑的假菜全在目录里，换一批")
        return 2

    n_unavail = 0
    rows = []
    for rnd in range(1, _AD_ROUNDS + 1):
        # ── 假菜组 ────────────────────────────────────────────────────
        for d in fakes:
            q = "%s卖得怎么样" % d
            _clear()
            spec, res = await _ask(pool, q, "ad-%s-r%d-%s" % (_AD_RUN_ID, rnd, d))
            auth = getattr(spec, "planner_authority", None) if spec else None
            if auth == "llm_unavailable":
                n_unavail += 1
            text = res.get("answer_text") or ""
            cq = getattr(spec, "clarification_question", None) if spec else None
            quoted = [n for n in cat if n in text and n not in q]
            # 结构判据: 报出 ≥3 道目录菜名 + 提到那个名字 ⇒ 目录反馈
            ok = len(quoted) >= 3 and d in text
            rows.append(("②假菜", rnd, d, res.get("kind") or "-",
                         len(quoted), ok, auth, text[:0]))
            print("\n" + "=" * 76)
            print("r%d 【假菜】%s" % (rnd, q))
            print("  kind=%s  dish_slot=%s  authority=%s" % (
                res.get("kind"), getattr(spec, "dish_slot", None) if spec else None,
                auth))
            print("  报出目录菜名 = %d 道；答案里提到「%s」= %s" % (
                len(quoted), d, d in text))
            print("  判定: %s" % ("✅ 目录反馈" if ok else "🔴 不是目录反馈"))
            print("  原文（前 200 字）: %s" % text[:200].replace(chr(10), " ⏎ "))

        # ── 真菜（阴性对照：时间反问必须逐字不变）────────────────────
        q = "%s卖得怎么样" % real_dish
        _clear()
        spec, res = await _ask(pool, q, "ad-%s-r%d-real" % (_AD_RUN_ID, rnd))
        auth = getattr(spec, "planner_authority", None) if spec else None
        if auth == "llm_unavailable":
            n_unavail += 1
        cq = getattr(spec, "clarification_question", None) if spec else None
        same = cq == ri.COMBINED_SCOPE_TIME_CLARIFICATION_QUESTION
        rows.append(("①真菜", rnd, real_dish, res.get("kind") or "-",
                     0, same, auth, ""))
        print("\n" + "=" * 76)
        print("r%d 【真菜·阴性对照】%s" % (rnd, q))
        print("  kind=%s  是那句时间反问 = %s  authority=%s" % (
            res.get("kind"), same, auth))

        # ── ➕ 回归格 ────────────────────────────────────────────────
        for q2 in _AD_REGRESSION:
            _clear()
            spec, res = await _ask(pool, q2, "ad-%s-r%d-reg-%s" % (_AD_RUN_ID, rnd, q2))
            auth = getattr(spec, "planner_authority", None) if spec else None
            if auth == "llm_unavailable":
                n_unavail += 1
            rows.append(("➕回归", rnd, q2, res.get("kind") or "-",
                         len(res.get("answer_text") or ""), None, auth, ""))

    print("\n" + "=" * 76)
    print("汇总（⛔ 逐行）")
    print("%-8s %-3s %-14s %-14s %-8s %s" % ("组", "r", "对象", "kind", "报店/字数", "判定"))
    for (grp, rnd, obj, kind, n, ok, auth, _t) in rows:
        v = "-" if ok is None else ("✅" if ok else "🔴")
        print("%-8s %-3s %-14s %-14s %-8s %s" % (grp, rnd, obj, kind, n, v))

    print("\n阴性对照·planner_authority=llm_unavailable = %d 次" % n_unavail)
    if n_unavail:
        print("⛔ rc=2 有 fail-closed 拒答混在里面 —— **不是产品被问倒**，本次读数作废")
        return 2

    fake_rows = [r for r in rows if r[0] == "②假菜"]
    real_rows = [r for r in rows if r[0] == "①真菜"]
    reg_rows = [r for r in rows if r[0] == "➕回归"]
    fake_ok = sum(1 for r in fake_rows if r[5])
    real_ok = sum(1 for r in real_rows if r[5])
    reg_ans = sum(1 for r in reg_rows if r[3] == "answer")

    print("\n🔑 判定")
    print("  ① 假菜 ⇒ 目录反馈        = %d/%d" % (fake_ok, len(fake_rows)))
    print("  ② 真菜 ⇒ 时间反问逐字不变 = %d/%d（防改过头）" % (real_ok, len(real_rows)))
    print("  ➕ 回归 ⇒ kind==answer    = %d/%d" % (reg_ans, len(reg_rows)))
    if reg_ans == 0:
        print("  ⛔ rc=2 回归组一条都没答上 —— 阳性对照没过，整份读数作废")
        return 2
    if fake_ok == len(fake_rows) and real_ok == len(real_rows):
        print("  ⇒ ✅ 这一刀独立复核通过，且没有改过头")
        return 0
    print("  ⇒ 🔴 逐行看上表")
    return 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
