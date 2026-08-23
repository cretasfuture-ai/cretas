# -*- coding: utf-8 -*-
"""A 还是 B —— 用**执行证据**分开，⛔ 不靠读码。

## 要分开的两种（外部审计提的）

    A  那次 repair 根本没走到 `clarification_needed = False` 那一行（被 guard 挡了）
       ⇒ 与 #2799 覆盖洞同族，是确定性的活
    B  走到了，但清得**太晚**（在补默认窗口那一步之后）
       ⇒ 顺序问题

⛔ 在分清之前不许登记成「属 LLM 那一层」。

## 判法

`sys.settrace` 只盯 `restaurant_intent.py` 里这几行，记**执行顺序**：

    2373/2374  补默认窗口（`time_phrase = DEFAULT_TIME_PHRASE` / `time_range_defaulted = True`）
    2524       clarification_needed = True   （补默认之后的第一处设回）
    2942       clarification_needed = False  ← **repair 块里的那一行**
    2967       clarification_needed = True   （unsupported_requirements 那支）
    3025       clarification_needed = True   ← **时间反问闸**
    3046       clarification_needed = False  （_NO_SCOPE_INTENTS 那支）

一次跑两句就分得开：

    米饭（走 repair）      2942 有没有执行？在 2373 之前还是之后？之后有没有 3025？
    水煮牛肉（不走 repair） 2373 有没有执行？（阳性对照 —— 它必须执行，否则 trace 没工作）

## 阳性/阴性对照（⛔ 不许省）

- 阳性：水煮牛肉必须命中 **2373**（它是唯一 `time_defaulted=True` 的那句）；
        且 trace 命中总行数 > 0
- 阴性：一个**必然不会被执行**的行号（`_build_spec` 之外的某行）必须 0 命中 ——
        否则说明我的过滤器在乱数

⚠️ `sys.settrace` 是线程级的，asyncio 单线程跑得通；只在 parse 那一段装，跑完就卸。
"""
from __future__ import annotations

import asyncio
import sys

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_Q08TRC_FID = "MOCK_REST"
_Q08TRC_ROUNDS = 3
ctx = bootstrap_probe(_Q08TRC_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: 被盯的那几行 —— ⛔ 行号是从 AST 取的，不是手抄的（见模块 docstring）。
_Q08TRC_WATCH = {
    2373: "补默认窗口 time_phrase=DEFAULT",
    2374: "补默认窗口 time_range_defaulted=True",
    2524: "clarify=True (2524)",
    2942: "🔴 repair 清 clarify=False",
    2967: "clarify=True (2967 unsupported_requirements)",
    3025: "🔴 时间反问闸 clarify=True",
    3046: "clarify=False (3046 _NO_SCOPE_INTENTS)",
}
#: 阴性对照：`_build_spec` 之外、且必然不在本轮执行路径上的一行。
#: ⛔ 取一个模块级常量定义行 —— 它只在 import 时执行一次，parse 期间必然 0 命中。
_Q08TRC_NEGATIVE = 96

_Q08TRC_FILE = ri.__file__.replace("\\", "/")
_Q08TRC_SEQ: list = []


def _tracer(frame, event, arg):
    if event != "line":
        return _tracer
    fn = (frame.f_code.co_filename or "").replace("\\", "/")
    if not fn.endswith("restaurant_intent.py"):
        return _tracer
    ln = frame.f_lineno
    if ln in _Q08TRC_WATCH or ln == _Q08TRC_NEGATIVE:
        _Q08TRC_SEQ.append(ln)
    return _tracer


def _clear() -> list[str]:
    done = []
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, n)()
        done.append(n)
    return done


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s 轮数=%d" % (ctx.db_name, _Q08TRC_FID, _Q08TRC_ROUNDS))
    print("被盯的文件: %s" % _Q08TRC_FILE)
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2

    # ⚠️ 行号必须与当前源码对得上 —— 源码一改行号就漂。开跑前先核对。
    src = open(ri.__file__, "r", encoding="utf-8", newline="").read().splitlines()
    print("\n行号自检（⛔ 对不上就别信下面的读数）:")
    expect = {
        2373: "time_phrase = DEFAULT_TIME_PHRASE",
        2942: "clarification_needed = False",
        3025: "clarification_needed = True",
    }
    ok = True
    for ln, frag in expect.items():
        actual = src[ln - 1].strip()
        good = frag in actual
        ok = ok and good
        print("  %-6d %s  期望含 %r  实际 %r" % (ln, "✅" if good else "🔴", frag, actual[:60]))
    if not ok:
        print("⛔ rc=2 行号与源码对不上（源码改过），读数作废")
        return 2

    rows = []
    for rnd in range(1, _Q08TRC_ROUNDS + 1):
        for dish in ("米饭", "水煮牛肉"):
            q = "%s卖得怎么样" % dish
            _clear()
            _Q08TRC_SEQ.clear()
            set_factory_id(_Q08TRC_FID)
            async with rr.dish_catalogue_scope(pool, _Q08TRC_FID):
                sys.settrace(_tracer)
                try:
                    spec = await ri.parse_restaurant_query(
                        q, pool, factory_id=_Q08TRC_FID,
                        session_key="q08trc-r%d-%s" % (rnd, dish),
                        semantic_first=True)
                finally:
                    sys.settrace(None)
            seq = list(_Q08TRC_SEQ)
            rows.append((rnd, dish, seq, spec))

            print("\n" + "=" * 78)
            print("r%d 【%s】%s" % (rnd, dish, q))
            print("  最终 spec: authority=%s clarify=%s time_defaulted=%s win=%s" % (
                getattr(spec, "planner_authority", None) if spec else None,
                getattr(spec, "clarification_needed", None) if spec else None,
                getattr(spec, "time_range_defaulted", None) if spec else None,
                getattr(spec, "window_label", None) if spec else None))
            print("  执行序列（⛔ 按发生顺序，不去重）:")
            if not seq:
                print("    (一行都没命中 —— trace 可能没工作)")
            for i, ln in enumerate(seq, 1):
                tag = _Q08TRC_WATCH.get(ln, "⚠️ 阴性对照行(不该出现)")
                print("    %2d. line %-6d %s" % (i, ln, tag))

    print("\n" + "=" * 78)
    print("判定")
    neg_hits = sum(1 for (_r, _d, seq, _s) in rows if _Q08TRC_NEGATIVE in seq)
    print("  阴性对照（line %d，模块级常量行）命中 = %d 次（必须是 0）"
          % (_Q08TRC_NEGATIVE, neg_hits))
    if neg_hits:
        print("  ⛔ rc=2 阴性对照被命中 —— 我的过滤器在乱数，读数作废")
        return 2

    beef = [r for r in rows if r[1] == "水煮牛肉"]
    beef_2373 = sum(1 for r in beef if 2373 in r[2])
    print("  阳性对照·水煮牛肉命中 2373（补默认）= %d/%d" % (beef_2373, len(beef)))
    if beef_2373 == 0:
        print("  ⛔ rc=2 唯一 time_defaulted=True 的那句都没命中补默认 —— trace 没工作")
        return 2

    rice = [r for r in rows if r[1] == "米饭"]
    hit_2942 = sum(1 for r in rice if 2942 in r[2])
    hit_2373 = sum(1 for r in rice if 2373 in r[2])
    hit_3025_after = sum(
        1 for r in rice
        if 2942 in r[2] and 3025 in r[2] and r[2].index(3025) > r[2].index(2942))
    print("\n  米饭（%d 轮）:" % len(rice))
    print("    命中 2942（repair 清 clarify）      = %d/%d" % (hit_2942, len(rice)))
    print("    命中 2373（补默认窗口）             = %d/%d" % (hit_2373, len(rice)))
    print("    2942 之后又命中 3025（时间反问闸）  = %d/%d" % (hit_3025_after, len(rice)))

    print("\n  ⇒ ", end="")
    if hit_2942 == 0:
        print("🔴 **A**：repair 根本没走到清 clarify 那一行 —— 确定性的活（覆盖洞同族）")
        return 1
    if hit_2942 and hit_2373 == 0 and hit_3025_after:
        print("🔴 **B**：repair **走到了**（清了 clarify），但补默认窗口(2373)"
              "在它之前就已经错过；清完之后时间反问闸(3025)接着开火 ——")
        print("     ▎顺序问题，**确定性的活**，⛔ 不是 prompt 的活。")
        return 1
    if hit_2942 and hit_2373:
        print("⚠️ 两条都命中 —— 逐行看上面的序列，⛔ 别下单一结论")
        return 1
    print("⚠️ 不落在 A/B 任一形状 —— 逐行看上面的序列")
    return 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
