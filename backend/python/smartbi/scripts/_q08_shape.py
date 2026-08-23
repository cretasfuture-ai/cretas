# -*- coding: utf-8 -*-
"""Q08「米饭卖得怎么样」是哪一种反问 —— ⛔ 分清之前不写任何代码。

## 已知（外部审计 2026-08-19 实测）

    米饭卖得怎么样   2/2 稳定拒答，**契约调用 0 次**
    ⇒ 被【规划器反问】挡在契约之前

## 三种形态，只有第三种能修

    ① 真歧义（信息不足）           ⇒ 反问是对的，⛔ 别动
    ② 有默认值却仍反问              ⇒ 撞「⛔ 不重开」那条裁定（2026-08-07
                                     撤回过，代价 5 条交互契约），⛔ 也别动
    ③ LLM 判对了但被**别的**挡      ⇒ 这才是能修的

## 分野判据（结构，⛔ 不用词表）

    LLM 原始 clarification_needed   ← 钩 `_build_spec` 拿模型自己说的
    最终 spec.clarification_needed  ← 产品最后的决定

    两者都 True   ⇒ ①或② —— 再看缺的那个槽位在产品里有没有默认值
    LLM False / 最终 True ⇒ **③**：模型判对了，是**代码**把它变成了反问

⚠️ 纪律 5：判定读产品自己的登记（`missing_slot` / `clarification_options`），
   ⛔ 不维护第二份词表。

## 对照

    ✅ 已知能答  「哪家店毛利最低」—— 阳性对照，它要是也拒答说明整条链没跑起来
    🔵 同族      「水煮牛肉卖得怎么样」「罗氏虾卖得怎么样」
                 —— 同一句式换菜名。若它们能答而米饭不能，
                    问题在**米饭这个词**上（`_RICE_STAPLE_ITEM_RE` 就在词表里）；
                    若它们也拒答，问题在**句式**上。
                 ▎这一格是本探针最值钱的一格：它把「词」和「句式」分开。
"""
from __future__ import annotations

import asyncio
import functools

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_Q08_FID = "MOCK_REST"
_Q08_ROUNDS = 3
ctx = bootstrap_probe(_Q08_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

_Q08_QS = [
    ("🔴目标", "米饭卖得怎么样"),
    ("🔵同族", "水煮牛肉卖得怎么样"),
    ("🔵同族", "罗氏虾卖得怎么样"),
    ("✅对照", "哪家店毛利最低"),
]

_Q08_CALLS: list[dict] = []


def _install():
    orig = ri._build_spec

    @functools.wraps(orig)
    def _spy(code, query, **kw):
        _Q08_CALLS.append({
            "code": code,
            "clarification_needed": kw.get("clarification_needed"),
            "clarification_question": kw.get("clarification_question"),
            "llm_dish": kw.get("llm_dish"),
            "llm_dimensions": kw.get("llm_dimensions"),
            "llm_requested_metrics": kw.get("llm_requested_metrics"),
            "llm_analysis_action": kw.get("llm_analysis_action"),
            "planner_authority": kw.get("planner_authority"),
            "tier": kw.get("tier"),
        })
        return orig(code, query, **kw)

    ri._build_spec = _spy


def _clear() -> list[str]:
    done = []
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, n)()
        done.append(n)
    return done


def _fmt(v):
    return "()" if v in (None, (), []) else repr(v)


def _shape(llm_clar, final_clar, kind):
    """三态判定。⛔ 结构判据，不看措辞。"""
    if kind == "answer":
        return "—（答上了）"
    if llm_clar is None:
        return "⚠️ 钩子没拿到 LLM 那一格（读数作废）"
    if llm_clar and final_clar:
        return "①或② 模型自己说信息不足 —— 再看缺的槽位有没有默认值"
    if (not llm_clar) and final_clar:
        return "🔴 ③ 模型判对了，是**代码**把它变成了反问 —— 这是能修的那一种"
    if llm_clar and not final_clar:
        return "⚠️ 模型说要反问而产品没反问（反向，另记）"
    return "⚠️ 两边都说不反问，却拿到了非 answer —— 拦它的是别的东西"


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s 轮数=%d" % (ctx.db_name, _Q08_FID, _Q08_ROUNDS))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2

    set_factory_id(_Q08_FID)
    async with rr.dish_catalogue_scope(pool, _Q08_FID):
        cat = tuple(rr.current_dish_catalogue() or ())
    print("阳性对照·菜单目录 = %d 条: %s" % (len(cat), list(cat)))
    if not cat:
        print("⛔ rc=2 菜单目录为 0 —— 库没连上，读数作废")
        return 2
    for name in ("米饭", "水煮牛肉", "罗氏虾"):
        print("  前提·「%s」在菜单里 = %s" % (name, name in cat))

    _install()
    n_ctrl = 0
    hook_total = 0
    rows = []

    for rnd in range(1, _Q08_ROUNDS + 1):
        for tag, q in _Q08_QS:
            cleared = _clear()
            _Q08_CALLS.clear()
            set_factory_id(_Q08_FID)
            async with rr.dish_catalogue_scope(pool, _Q08_FID):
                spec = await ri.parse_restaurant_query(
                    q, pool, factory_id=_Q08_FID,
                    session_key="q08-r%d-%s" % (rnd, q), semantic_first=True)
                res = None
                if spec is not None and svc.should_delegate(spec, None, query=q):
                    res = await svc.tiered_answer(
                        q, pool, _Q08_FID, ctx.role, precomputed_spec=spec,
                        session_key="q08-r%d-%s" % (rnd, q))
            kind = (res or {}).get("kind") or "-"
            text = (res or {}).get("answer_text") or ""
            hook_total += len(_Q08_CALLS)
            if tag == "✅对照" and kind == "answer":
                n_ctrl += 1

            llm_clar = _Q08_CALLS[0]["clarification_needed"] if _Q08_CALLS else None
            final_clar = getattr(spec, "clarification_needed", None) if spec else None
            shape = _shape(llm_clar, final_clar, kind)
            rows.append((rnd, tag, q, kind, len(text), llm_clar, final_clar, shape))

            print("\n" + "=" * 76)
            print("r%d 【%s】%s" % (rnd, tag, q))
            print("  清了: %s   钩子 %d 次" % ("、".join(cleared), len(_Q08_CALLS)))
            print("  ── LLM 原始（钩 _build_spec）──")
            for i, c in enumerate(_Q08_CALLS, 1):
                print("    第%d次 code=%s tier=%s authority=%s" % (
                    i, c["code"], c["tier"], c["planner_authority"]))
                print("           clarification_needed=%s  question=%s" % (
                    c["clarification_needed"],
                    _fmt((c["clarification_question"] or "")[:60])))
                print("           llm_dish=%s  dims=%s  metrics=%s  action=%s" % (
                    _fmt(c["llm_dish"]), _fmt(c["llm_dimensions"]),
                    _fmt(c["llm_requested_metrics"]), _fmt(c["llm_analysis_action"])))
            print("  ── 最终 spec ──")
            print("    intent=%s  planned=%s" % (
                getattr(spec, "intent", None) if spec else None,
                _fmt(tuple(getattr(spec, "planned_intents", ()) or ()) if spec else ())))
            print("    dimensions=%s  dish_slot=%s  store_slot=%s" % (
                _fmt(tuple(getattr(spec, "dimensions", ()) or ()) if spec else ()),
                _fmt(getattr(spec, "dish_slot", None) if spec else None),
                _fmt(getattr(spec, "store_slot", None) if spec else None)))
            print("    clarification_needed=%s  missing_slot=%s  options=%s" % (
                final_clar,
                _fmt(getattr(spec, "missing_slot", None) if spec else None),
                _fmt(tuple(getattr(spec, "clarification_options", ()) or ())
                     if spec else ())))
            print("  ── 形态判定 ──")
            print("    %s" % shape)
            print("  ── 老板看到的原文（前 300 字）──")
            print("    " + (text[:300].replace("\n", "\n    ") or "(空)"))

    print("\n" + "=" * 76)
    print("汇总（⛔ 逐行）")
    print("%-3s %-8s %-16s %-14s %-7s %-7s %s" % (
        "r", "组", "问句", "kind", "LLM反问", "最终反问", "形态"))
    for (rnd, tag, q, kind, n, lc, fc, shape) in rows:
        print("%-3s %-8s %-16s %-14s %-7s %-7s %s" % (
            rnd, tag, q[:16], "%s(%d)" % (kind, n), lc, fc, shape[:34]))

    print("\n阳性对照·钩子总调用 = %d" % hook_total)
    if hook_total == 0:
        print("⛔ rc=2 钩子没挂上，LLM 那两列全是假的，读数作废")
        return 2
    print("阳性对照·「哪家店毛利最低」kind==answer = %d/%d 轮" % (n_ctrl, _Q08_ROUNDS))
    if n_ctrl == 0:
        print("⛔ rc=2 已知能答的那句也拒了 —— 整条链没跑起来，读数作废")
        return 2

    tgt = [r for r in rows if r[1] == "🔴目标"]
    sib = [r for r in rows if r[1] == "🔵同族"]
    tgt_ans = sum(1 for r in tgt if r[3] == "answer")
    sib_ans = sum(1 for r in sib if r[3] == "answer")
    print("\n🔑 词 vs 句式")
    print("  目标「米饭」答上 %d/%d ；同族（换菜名）答上 %d/%d"
          % (tgt_ans, len(tgt), sib_ans, len(sib)))
    if tgt_ans == 0 and sib_ans == len(sib):
        print("  ⇒ 问题在**「米饭」这个词**上，⛔ 不是句式")
    elif tgt_ans == 0 and sib_ans == 0:
        print("  ⇒ 问题在**句式**上，⛔ 与菜名无关")
    else:
        print("  ⇒ 混合/抖动 —— 逐行看上面的表，⛔ 别下单一结论")
    return 1 if tgt_ans == 0 else 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
