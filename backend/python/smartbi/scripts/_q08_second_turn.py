# -*- coding: utf-8 -*-
"""老板点了那个时间按钮之后，到底拿不拿得到答案。

## 为什么这一步不能省

`docs/decisions/2026-08-19-Q08-repair清了clarify却错过补默认窗口-设计卡.md`
的裁定（选项 C：不做）**全部理由**建立在一句话上：

    ▎老板答完那一问之后**就能拿到答案** —— 那不是白问，只是多一步。

而这一句我**没有实测过**。⇒ 判定完全取决于它：

    点了按钮能拿到  ⇒ 「多一步」  ⇒ 选项 C 成立，不做
    点了按钮拿不到  ⇒ **「白问」** ⇒ 与同日已修的那一刀同形，裁定作废，直接做选项 A

▎**一个「多一步」和一个「白问」，产品含义完全相反，而它们只差这一次实测。**

## 判法

    第一轮  「米饭卖得怎么样」          → 拿 clarification_options
    第二轮  把其中一个按钮原样当问句发   → 断言 kind == "answer"

⚠️ 两轮必须用**同一个 session_key** —— 澄清延续的前提就是它。
⚠️ 而 `session_key` 又必须**跨次唯一**（`restaurant_pending_clarifications`
   是跨进程存活的 Postgres 表），所以 key = 固定前缀 + RUN_ID + 轮次。

## 阳性/阴性对照（⛔ 不许省）

- 阳性：第一轮必须真的是 `clarification` 且 `clarification_options` 非空 ——
        否则「第二轮答不上」和「压根没进澄清流程」是同一个读数。
- 阴性：拿一句**与按钮无关**的话当第二轮（「今天天气怎么样」），
        它**不应该**被当成对上一轮的回答而答出销量 —— 用来证明延续不是恒真的。
"""
from __future__ import annotations

import asyncio
import os
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_Q08ST_FID = "MOCK_REST"
_Q08ST_ROUNDS = 3
_Q08ST_RUN_ID = "%d-%d" % (os.getpid(), int(time.time()))
ctx = bootstrap_probe(_Q08ST_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

_Q08ST_FIRST = "米饭卖得怎么样"
#: 阴性对照：与按钮无关的一句话。它**不该**被当成对上一轮的回答。
_Q08ST_UNRELATED = "今天天气怎么样"


def _clear() -> list[str]:
    done = []
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, n)()
        done.append(n)
    return done


async def _ask(pool, q, skey):
    set_factory_id(_Q08ST_FID)
    async with rr.dish_catalogue_scope(pool, _Q08ST_FID):
        spec = await ri.parse_restaurant_query(
            q, pool, factory_id=_Q08ST_FID, session_key=skey, semantic_first=True)
        res = None
        if spec is not None and svc.should_delegate(spec, None, query=q):
            res = await svc.tiered_answer(
                q, pool, _Q08ST_FID, ctx.role,
                precomputed_spec=spec, session_key=skey)
    return spec, (res or {})


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s 轮数=%d RUN_ID=%s"
          % (ctx.db_name, _Q08ST_FID, _Q08ST_ROUNDS, _Q08ST_RUN_ID))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2

    n_first_clar = 0
    n_second_answer = 0
    n_unrelated_answer = 0
    rows = []

    for rnd in range(1, _Q08ST_ROUNDS + 1):
        # ── 第一轮 ────────────────────────────────────────────────────
        _clear()
        skey = "q08st-%s-r%d" % (_Q08ST_RUN_ID, rnd)
        spec1, res1 = await _ask(pool, _Q08ST_FIRST, skey)
        kind1 = res1.get("kind") or "-"
        opts = tuple(getattr(spec1, "clarification_options", ()) or ())
        if kind1 == "clarification" and opts:
            n_first_clar += 1

        print("\n" + "=" * 78)
        print("r%d 第一轮【%s】" % (rnd, _Q08ST_FIRST))
        print("  kind=%s  missing_slot=%s" % (
            kind1, getattr(spec1, "missing_slot", None) if spec1 else None))
        print("  按钮 = %s" % list(opts))
        print("  老板看到: %s" % (res1.get("answer_text") or "")[:120])

        if not opts:
            print("  ⚠️ 没有按钮 —— 这一轮没法测第二轮")
            rows.append((rnd, kind1, None, "-", "-"))
            continue

        # ── 第二轮：把第一个按钮原样当问句发（老板点它就是这个效果）──
        btn = opts[0]
        _clear()
        spec2, res2 = await _ask(pool, btn, skey)
        kind2 = res2.get("kind") or "-"
        text2 = res2.get("answer_text") or ""
        if kind2 == "answer":
            n_second_answer += 1

        print("  ── 第二轮（点按钮）【%s】──" % btn)
        print("    kind=%s  n=%d  intent=%s  win=%s" % (
            kind2, len(text2),
            getattr(spec2, "intent", None) if spec2 else None,
            getattr(spec2, "window_label", None) if spec2 else None))
        print("    老板看到（前 240 字）: %s" % text2[:240].replace("\n", " ⏎ "))

        # ── 阴性对照：与按钮无关的一句 ───────────────────────────────
        _clear()
        skey_neg = "q08st-%s-r%d-neg" % (_Q08ST_RUN_ID, rnd)
        _spec1n, _res1n = await _ask(pool, _Q08ST_FIRST, skey_neg)
        _clear()
        _spec2n, res2n = await _ask(pool, _Q08ST_UNRELATED, skey_neg)
        kind_neg = res2n.get("kind") or "-"
        neg_text = res2n.get("answer_text") or ""
        # 🔴 2026-08-19 判据订正（第一版是错的代理判据）：
        #    第一版写 `"米饭" in neg_text` ⇒ 3/3 报「被误当成延续」。
        #    实测原文是**产品判对了**：
        #      intent=RESTAURANT_OPS_OUT_OF_DOMAIN
        #      「天气、新闻这类外部信息不在我的数据范围内，我不会编造答案。
        #        我可以帮您分析门店经营数据，例如：…「米饭的销量」…」
        #    正文里那个「米饭」是**举例清单**里的一项，⛔ 不是答案。
        #    ▎又一次「用子串当判据」—— 本轮第三次（另两次：dish 目录、按门店拆不出来）。
        # ⇒ 换**结构判据**：看 intent 有没有被拉回菜品那条路 + dish 槽位有没有被继承。
        _neg_intent = getattr(_spec2n, "intent", None) if _spec2n else None
        _neg_dish = getattr(_spec2n, "dish_slot", None) if _spec2n else None
        neg_leaked = bool(
            kind_neg == "answer"
            and _neg_intent
            and "OUT_OF_DOMAIN" not in _neg_intent
            and _neg_dish)
        if neg_leaked:
            n_unrelated_answer += 1
        print("  ── 阴性对照（第二轮换成无关问句「%s」）──" % _Q08ST_UNRELATED)
        print("    kind=%s  intent=%s  win=%s  seed=%r" % (
            kind_neg,
            getattr(_spec2n, "intent", None) if _spec2n else None,
            getattr(_spec2n, "window_label", None) if _spec2n else None,
            (getattr(_spec2n, "resolver_query_seed", None) or "")[:60]))
        print("    dish_slot=%s  %s" % (
            _neg_dish,
            "🔴 被误当成延续（intent 被拉回菜品那条路）" if neg_leaked
            else "✅ 没被误当成延续"))
        print("    老板看到（前 260 字）: %s"
              % neg_text[:260].replace(chr(10), " ⏎ "))

        rows.append((rnd, kind1, btn, kind2, kind_neg))

    print("\n" + "=" * 78)
    print("汇总（⛔ 逐行）")
    print("%-3s %-16s %-22s %-14s %s" % ("r", "第一轮kind", "点的按钮", "第二轮kind", "阴性对照kind"))
    for (rnd, k1, btn, k2, kn) in rows:
        print("%-3s %-16s %-22s %-14s %s" % (rnd, k1, btn or "-", k2, kn))

    print("\n阳性对照·第一轮是 clarification 且有按钮 = %d/%d"
          % (n_first_clar, _Q08ST_ROUNDS))
    if n_first_clar == 0:
        print("⛔ rc=2 第一轮压根没进澄清流程 —— "
              "「第二轮答不上」和「没测到东西」是同一个读数，作废")
        return 2
    print("阴性对照·无关问句被误当成延续 = %d/%d（>0 说明延续是恒真的，读数不可信）"
          % (n_unrelated_answer, _Q08ST_ROUNDS))

    print("\n🔑 裁定判据")
    print("  点按钮之后 kind==answer = %d/%d" % (n_second_answer, n_first_clar))
    if n_second_answer == n_first_clar and n_first_clar > 0:
        print("  ⇒ ✅ 「多一步」成立 —— 设计卡选项 C（不做）的理由站得住")
        return 0
    if n_second_answer == 0:
        print("  ⇒ 🔴 **「白问」** —— 老板点了按钮**照样拿不到**。")
        print("     与同日已修的那一刀同形 ⇒ 设计卡裁定**作废**，该做选项 A。")
        return 1
    print("  ⇒ ⚠️ 部分答上 —— 逐行看上表，⛔ 别下单一结论")
    return 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
