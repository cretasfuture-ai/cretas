# -*- coding: utf-8 -*-
"""老板要一个**这个 resolver 拆不出来**的维度时，他拿到什么？

## 起点

能力表 `_RESOLVER_DIMENSIONS`（19 个 resolver，维度全集 8 个）里有明显的空白：

    RESTAURANT_OPS_DISCOUNT_SUMMARY   []              一个维度都不支持
    RESTAURANT_OPS_GROSS_MARGIN       [dish, time]    **没有 store / channel / meal_period**
    RESTAURANT_OPS_SALES_SUMMARY      [store]         没有 dish / channel
    RESTAURANT_OPS_TREND_ANALYSIS     [time]          只有时间

上一轮量「追问接得住」时的结论是：真正接不住的那 5 条形态是
**「这些维度组合根本没有 resolver 服务」** ⇒ 主线是**能力表**不是接线。

## 本探针要回答的（⛔ 一件事一格，不折叠）

    ① 老板问一个拆不出来的维度组合时，`kind` 是什么
    ② 他拿到的话里**说没说**「这个拆法我这儿没有」
    ③ 有没有告诉他**我能按什么拆**（给出路，⛔ 不是只说不行）
    ④ ⛔ 有没有**静默换成别的维度**给了个数（那是最坏的一种）

## 判据（结构，⛔ 不匹配措辞）

- `spec.dimensions` 与该 resolver 能力表的**差集**非空 ⇒ 这一轮踩到了空白格
- 答案里出现该差集里那个维度的中文名 ⇒ 算「说了」
- `kind == "answer"` 且**没说** ⇒ 🔴 静默换题

## 阳性/阴性对照（⛔ 不许省）

- **阳性**：一组**能拆**的组合（「按门店看营收」）必须正常出数 ——
  出不来说明整条链没跑起来，本次读数作废（`rc=2`）
- **阴性**：⛔ 不拿「答案里没提那个词」当「静默换题」的唯一证据 ——
  逐条贴原文给人读
"""
from __future__ import annotations

import asyncio
import os
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_DN_FID = "MOCK_REST"
_DN_ROUNDS = 2
_DN_RUN = "%d-%d" % (os.getpid(), int(time.time()))
ctx = bootstrap_probe(_DN_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.gold.restaurant.restaurant_intent_service import (  # noqa: E402
    _RESOLVER_DIMENSIONS,
)
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: 维度 -> 老板会怎么说它（用来判「说没说」）。⛔ 只用于判定，不用于生成文案。
_DN_DIM_WORDS = {
    "store": ("门店", "店"),
    "dish": ("菜", "菜品"),
    "channel": ("渠道", "外卖", "堂食"),
    "meal_period": ("时段", "餐段", "午市", "晚市"),
    "ingredient": ("食材", "原料"),
    "time": ("时间", "走势", "趋势"),
    "customer": ("顾客", "客群"),
    "wastage_type": ("损耗",),
}

#: (标签, 问句) —— 前几条踩空白格，最后一条是阳性对照。
_DN_CASES = [
    ("①拆不出来·折扣按门店", "最近30天哪家店折扣给得最多"),
    ("①拆不出来·毛利按渠道", "最近30天外卖和堂食的毛利分别是多少"),
    ("①拆不出来·毛利按时段", "最近30天哪个时段毛利最高"),
    ("②阳性对照·能拆", "最近30天各门店营收是多少"),
]


def _clear():
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, n)()


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s  轮数=%d  RUN=%s" % (ctx.db_name, _DN_ROUNDS, _DN_RUN))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2
    print("能力表 = %d 个 resolver" % len(_RESOLVER_DIMENSIONS))

    rows = []
    for rnd in range(1, _DN_ROUNDS + 1):
        for tag, q in _DN_CASES:
            _clear()
            set_factory_id(_DN_FID)
            skey = "dn-%s-r%d-%s" % (_DN_RUN, rnd, q)
            async with rr.dish_catalogue_scope(pool, _DN_FID):
                spec = await ri.parse_restaurant_query(
                    q, pool, factory_id=_DN_FID, session_key=skey,
                    semantic_first=True)
                res = None
                if spec is not None and svc.should_delegate(spec, None, query=q):
                    res = await svc.tiered_answer(
                        q, pool, _DN_FID, ctx.role,
                        precomputed_spec=spec, session_key=skey)
            res = res or {}
            code = getattr(spec, "intent", None) if spec else None
            asked = tuple(getattr(spec, "dimensions", ()) or ()) if spec else ()
            served = set(_RESOLVER_DIMENSIONS.get(code or "", ()) or ())
            missing = [d for d in asked if d not in served]
            text = res.get("answer_text") or ""
            cq = getattr(spec, "clarification_question", None) if spec else None
            body = text or (cq or "")
            said = [d for d in missing
                    if any(w in body for w in _DN_DIM_WORDS.get(d, ()))]
            kind = res.get("kind") or "-"

            if tag.startswith("②"):
                verdict = "阳性对照: %s" % ("✅ 出数了" if kind == "answer" else "🔴 没出数")
            elif not missing:
                verdict = "⚠️ 这轮没踩到空白格（模型给的维度都在能力表里）"
            elif kind == "answer" and not said:
                verdict = "🔴🔴 静默换题 —— 给了数，一个字没提拆不出来的那个维度"
            elif said:
                verdict = "✅ 说了「%s 拆不出来」" % "、".join(said)
            else:
                verdict = "⚠️ 别的（人读原文）"

            rows.append((rnd, tag, q, code, asked, sorted(served), missing,
                         kind, said, verdict))
            print("\n" + "=" * 78)
            print("r%d 【%s】%s" % (rnd, tag, q))
            print("   intent=%s" % code)
            print("   模型要的维度=%s   能力表支持=%s   **拆不出来的**=%s"
                  % (list(asked), sorted(served), missing))
            print("   kind=%s   答案里提到了拆不出来的: %s" % (kind, said))
            print("   判定: %s" % verdict)
            print("   ── 老板看到的（前 300 字）──")
            print("   " + (body[:300] or "(空)").replace("\n", "\n   "))

    print("\n" + "=" * 78)
    pos = [r for r in rows if r[1].startswith("②")]
    n_pos = sum(1 for r in pos if r[7] == "answer")
    print("阳性对照·能拆的组合出数 = %d/%d" % (n_pos, len(pos)))
    if not n_pos:
        print("⛔ rc=2 连能拆的都出不了数 —— 整条链没跑起来，读数作废")
        return 2

    gap = [r for r in rows if not r[1].startswith("②") and r[6]]
    silent = [r for r in gap if r[9].startswith("🔴🔴")]
    told = [r for r in gap if r[9].startswith("✅")]
    print("\n🔑 判定（只看真的踩到空白格的 %d 轮）" % len(gap))
    print("  ✅ 说清了「按 X 拆不出来」 = %d" % len(told))
    print("  🔴🔴 静默换题（给了数、没说）= %d" % len(silent))
    print("  ⚠️ 别的 = %d" % (len(gap) - len(told) - len(silent)))
    print("\n⚠️ 口径：评测题库，⛔ 不是真实流量。踩不到空白格的轮次已排除在分母外。")
    return 1 if silent else 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
