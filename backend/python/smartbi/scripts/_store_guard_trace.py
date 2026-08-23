# -*- coding: utf-8 -*-
"""点名一家不存在的门店 ⇒ `_apply_store_scope_guard` 里**哪一支**执行了？

## 第一轮读数（3 轮）已经把问题的形状改了

    r1 假店  5573 早退=0  5689 核对=1（判 True）⇒ kind=clarification  missing=store  ✅
    r2 假店  同上 ✅
    r3 假店  5573 早退=**1**  5689 核对=0      ⇒ store_scope='all'  kind=answer   🔴

▎**同一句问句间歇走两条路。** 那不是「保护没实现」，是**保护够不着**：
▎`L5565` 的 `or spec.store_scope` 在上游已经把 scope 定成真值的那一轮，
▎把「点名的门店在不在名单里」这道核对**整支跳过**。

⇒ 本版要回答的下一格：**谁在上游把 scope 定了**，以及**多大比例**。

## 与第一版的三处订正（⛔ 都是仪器的错，不是被测对象的）

1. **判定改成逐轮配对**。第一版按跨轮聚合判（`if n_took: 形态B投影丢失`），
   而「2 轮反问 + 1 轮顶替」在汇总里与「3 轮判 True 但被覆盖」**长得一模一样**
   —— 形态 A¹³。
2. **阳性对照挑错了**。第一版要求「真店命中 5689」，实测真店 0/3 ——
   因为真店的 scope 被 LLM 判成 `single`，**本来就该早退**。
   换成「至少有一轮命中过 5689」+「guard spy 被调用过」。
3. **钩住 guard 入参**。早退与否**完全由入参决定**，只记「早退了」
   回答不了「谁把它定成 all 的」。

## 阳性/阴性对照（⛔ 不许省）

- **行号自检**：每个行号的源码文本必须匹配预期片段（改一行就全错位，且不报错）
- **阳性 ①**：`guard spy` 调用次数 > 0 —— 为 0 说明 monkeypatch 打空了（形态 B⁶）
- **阳性 ②**：核对行 5689 至少被执行过一次 —— 一次没有说明 trace 没挂上，
  那么「没命中」这个读数没有意义
- **阴性**：函数体外的一行命中数必须为 0
"""
from __future__ import annotations

import asyncio
import os
import sys
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_SG_FID = "MOCK_REST"
#: 间歇现象 —— 3 轮分不出比例。
_SG_ROUNDS = 6
_SG_RUN = "%d-%d" % (os.getpid(), int(time.time()))
ctx = bootstrap_probe(_SG_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

_SG_FAKE = "北京三里屯店"

#: 行号 -> (标签, 该行源码里必然出现的片段)。⛔ 片段对不上就 rc=2。
_SG_LINES = {
    5573: ("A·早退return", "return spec"),
    5689: ("核对那一行", "spec.store_slot not in names"),
    5690: ("核对判True→问门店", "_seal_query_plan"),
    5704: ("延续轮分支", "is_clarification_continuation"),
    5734: ("C·兜底all", "_seal_query_plan"),
}
#: 阴性对照：函数体外的一行，永远不该被这条路径命中。
_SG_NEVER = 5743


def _clear():
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, n)()


class _Rec:
    def __init__(self):
        self.reset()

    def reset(self):
        self.hits = {}
        self.snaps = []
        self.guard_calls = 0
        self.guard_in = []


_REC = _Rec()
_SG_FILE = os.path.abspath(ri.__file__)

# ── 钩 guard 入参 ────────────────────────────────────────────────────
# 🔴 5573 早退与否**完全由入参决定**（`or spec.store_scope`）。
#    只记「早退了」回答不了「谁把 scope 定成 all 的」。
# ⚠️ 形态 B⁶：这个 patch 能生效，是因为 guard 在**同模块内**按 global 查找被调用。
#    ⛔ 不假设它生效 —— 用 `_REC.guard_calls` 做阳性对照，为 0 就 rc=2。
_SG_REAL_GUARD = ri._apply_store_scope_guard


async def _guard_spy(pool, factory_id, spec, history=None):
    _REC.guard_calls += 1
    _REC.guard_in.append({
        "store_scope": getattr(spec, "store_scope", None) if spec is not None else None,
        "store_slot": getattr(spec, "store_slot", None) if spec is not None else None,
        "store_slots": tuple(getattr(spec, "store_slots", ()) or ()) if spec is not None else (),
        "authority": getattr(spec, "planner_authority", None) if spec is not None else None,
        "clarify": getattr(spec, "clarification_needed", None) if spec is not None else None,
        "intent": getattr(spec, "intent", None) if spec is not None else None,
        "defaulted": getattr(spec, "store_scope_defaulted", None) if spec is not None else None,
    })
    return await _SG_REAL_GUARD(pool, factory_id, spec, history)


ri._apply_store_scope_guard = _guard_spy


def _local_trace(frame, event, arg):
    if event != "line":
        return _local_trace
    ln = frame.f_lineno
    if ln in _SG_LINES or ln == _SG_NEVER:
        _REC.hits[ln] = _REC.hits.get(ln, 0) + 1
        if ln == 5689:
            lv = frame.f_locals
            spec = lv.get("spec")
            names = lv.get("names")
            _REC.snaps.append({
                "store_slot": getattr(spec, "store_slot", None) if spec is not None else None,
                "store_slots": tuple(getattr(spec, "store_slots", ()) or ()) if spec is not None else (),
                "store_scope": getattr(spec, "store_scope", None) if spec is not None else None,
                "n_names": len(tuple(names or ())),
                "slot_in_names": (getattr(spec, "store_slot", None) in tuple(names or ()))
                                 if spec is not None else None,
            })
    return _local_trace


def _global_trace(frame, event, arg):
    if event == "call" and os.path.abspath(frame.f_code.co_filename) == _SG_FILE:
        return _local_trace
    return None


async def _ask(pool, q, skey):
    set_factory_id(_SG_FID)
    async with rr.dish_catalogue_scope(pool, _SG_FID):
        spec = await ri.parse_restaurant_query(
            q, pool, factory_id=_SG_FID, session_key=skey, semantic_first=True)
        res = None
        if spec is not None and svc.should_delegate(spec, None, query=q):
            res = await svc.tiered_answer(
                q, pool, _SG_FID, ctx.role, precomputed_spec=spec, session_key=skey)
    return spec, (res or {})


async def main() -> int:
    print("PROBE_DB=%s  FILE=%s  RUN=%s" % (ctx.db_name, _SG_FILE, _SG_RUN))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2

    # ── 行号自检（⛔ 不许省：文件改一行，所有行号静默错位）────────────
    src_lines = open(_SG_FILE, "r", encoding="utf-8", newline="").read().split("\n")
    print("\n行号自检:")
    bad = 0
    for ln, (tag, frag) in sorted(_SG_LINES.items()):
        text = src_lines[ln - 1].strip()
        ok = frag in text
        if not ok:
            bad += 1
        print("  L%-6d %-22s %s  %s" % (ln, tag, "OK" if ok else "BAD", text[:62]))
    if bad:
        print("⛔ rc=2 有 %d 个行号对不上源码 —— 行号错位，整份读数作废" % bad)
        return 2

    pool = await ctx.pool()
    set_factory_id(_SG_FID)
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute("SELECT set_config('app.factory_id', $1, false)", _SG_FID)
            rows = await conn.fetch(
                "SELECT name FROM dim_store WHERE factory_id = $1 ORDER BY name", _SG_FID)
    names = tuple(r["name"] for r in rows)
    if len(names) < 2 or _SG_FAKE in names:
        print("⛔ rc=2 门店名单不合用: %d 家 / 假店在名单里=%s"
              % (len(names), _SG_FAKE in names))
        return 2
    real = sorted(names)[0]
    print("\n门店名单 = %d 家；真店取 %s；假店 %s" % (len(names), real, _SG_FAKE))

    cases = [("①真店", "%s卖得怎么样" % real), ("②假店", "%s卖得怎么样" % _SG_FAKE)]
    agg = {}
    for rnd in range(1, _SG_ROUNDS + 1):
        for tag, q in cases:
            _clear()
            _REC.reset()
            sys.settrace(_global_trace)
            try:
                spec, res = await _ask(pool, q, "sg-%s-r%d-%s" % (_SG_RUN, rnd, tag))
            finally:
                sys.settrace(None)
            hits = dict(_REC.hits)
            snaps = list(_REC.snaps)
            gin = list(_REC.guard_in)
            gcalls = _REC.guard_calls
            agg.setdefault(tag, []).append(
                (hits, snaps, (res or {}).get("kind"), gin, gcalls))

            print("\n" + "=" * 76)
            print("r%d 【%s】%s   kind=%s" % (rnd, tag, q, (res or {}).get("kind")))
            for ln, (lt, _f) in sorted(_SG_LINES.items()):
                print("   L%-6d %-22s 命中 %d 次" % (ln, lt, hits.get(ln, 0)))
            print("   L%-6d %-22s 命中 %d 次（阴性对照，必须 0）"
                  % (_SG_NEVER, "阴性·函数外", hits.get(_SG_NEVER, 0)))
            print("   guard 被调用 %d 次；每次的**入参**（早退与否由它决定）:" % gcalls)
            for g in gin:
                print("     > 进 guard 时 store_scope=%r  store_slot=%r  slots=%s"
                      % (g["store_scope"], g["store_slot"], g["store_slots"]))
                print("       authority=%r  clarify=%s  intent=%s  defaulted=%s"
                      % (g["authority"], g["clarify"], g["intent"], g["defaulted"]))
            for s in snaps:
                print("   > 5689 当时: store_slot=%r  slots=%s  scope=%r  names=%d  在名单里=%s"
                      % (s["store_slot"], s["store_slots"], s["store_scope"],
                         s["n_names"], s["slot_in_names"]))
            print("   最终 spec: store_scope=%r slots=%s store_slot=%r missing=%r authority=%r"
                  % (getattr(spec, "store_scope", None),
                     tuple(getattr(spec, "store_slots", ()) or ()),
                     getattr(spec, "store_slot", None),
                     getattr(spec, "missing_slot", None),
                     getattr(spec, "planner_authority", None)))

    print("\n" + "=" * 76)
    print("🔑 判定")
    all_rows = [(t, r) for t, rs in agg.items() for r in rs]
    never = sum(h.get(_SG_NEVER, 0) for _t, (h, _s, _k, _g, _c) in all_rows)
    gcalls = sum(c for _t, (_h, _s, _k, _g, c) in all_rows)
    hit5689 = any(h.get(5689) for _t, (h, _s, _k, _g, _c) in all_rows)
    print("  阴性对照·函数外那一行命中 = %d（必须 0）" % never)
    print("  阳性对照①·guard spy 被调用 = %d 次（0 ⇒ monkeypatch 打空，读数作废）" % gcalls)
    print("  阳性对照②·核对行 5689 至少执行过 = %s" % hit5689)
    if never:
        print("  ⛔ rc=2 阴性对照命中 —— trace 挂错地方，读数作废")
        return 2
    if not gcalls:
        print("  ⛔ rc=2 guard spy 一次没被调用 —— monkeypatch 打空了（形态 B⁶）")
        return 2
    if not hit5689:
        print("  ⛔ rc=2 核对行一次都没执行 —— 「没命中」这个读数没有意义")
        return 2

    # ── 逐轮配对（⛔ 不跨轮聚合：形态 A¹³）─────────────────────────
    print("\n  ②假店逐轮（⛔ 不折叠）:")
    print("  %-3s %-14s %-7s %-7s %-22s %s"
          % ("r", "kind", "早退", "核对", "进guard时scope", "判定"))
    n_silent = n_ok = n_other = 0
    for i, (h, _sn, k, g, _c) in enumerate(agg.get("②假店", []), 1):
        early = bool(h.get(5573))
        checked = bool(h.get(5689))
        took = bool(h.get(5690))
        in_scope = g[0]["store_scope"] if g else "<没调到>"
        if early and not checked and k == "answer":
            v = "🔴🔴 早退 ⇒ 静默顶替"
            n_silent += 1
        elif checked and took and k == "clarification":
            v = "✅ 走到核对 ⇒ 正确反问"
            n_ok += 1
        else:
            v = "⚠️ 别的（人读上面）"
            n_other += 1
        print("  %-3d %-14s %-7s %-7s %-22r %s" % (i, k, early, checked, in_scope, v))

    total = n_silent + n_ok + n_other
    print("\n  🔴🔴 早退⇒静默顶替 = %d/%d" % (n_silent, total))
    print("  ✅ 走到核对⇒反问   = %d/%d" % (n_ok, total))
    print("  ⚠️ 别的            = %d/%d" % (n_other, total))

    # 进 guard 时 scope 非空的那些轮，authority 是什么 —— 指向上游是谁
    print("\n  进 guard 时 scope 已非空的轮次（⇒ 早退的成因）:")
    for i, (_h, _sn, k, g, _c) in enumerate(agg.get("②假店", []), 1):
        if g and g[0]["store_scope"]:
            print("    r%d  scope=%r  authority=%r  intent=%s"
                  % (i, g[0]["store_scope"], g[0]["authority"], g[0]["intent"]))

    if n_silent and n_ok:
        print("\n  ⇒ ▎**同一句问句间歇走两条路** —— 不是「没实现」，是**保护够不着**。")
        print("     早退条件 `or spec.store_scope`（L5565）在上游已把 scope 定成真值的")
        print("     那一轮，把「点名的门店在不在名单里」这道核对整支跳过。")
        print("     ⇒ 修的是**早退条件**，⛔ 不是 5689 那一支（它是对的）。")
        return 1
    if n_silent:
        print("\n  ⇒ 🔴🔴 每一轮都早退 —— 同上，修早退条件。")
        return 1
    if n_ok == total:
        print("\n  ⇒ ✅ 本轮每次都正确反问。⛔ 别据此说它不存在 —— 上一轮 1/3 复现过。")
        return 0
    print("\n  ⇒ ⚠️ 逐行看上表")
    return 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
