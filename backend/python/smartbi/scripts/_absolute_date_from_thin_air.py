# -*- coding: utf-8 -*-
"""模型吐的**绝对日期**，是老板原话里的，还是它自己算的？

## 为什么量它

实体侧有反幻觉核对，日期侧**没有**：

    dish / store  →  `_verbatim_entity`（必须是问句里连续出现的子串）  ✅
    绝对日期      →  只校验 ISO 格式 + 区间合法性（颠倒/非法 fail-closed）🔴

`_time_phrase_from_llm` 的 absolute 分支注释自己写着「用户原话给了显式区间时，
LLM **只负责转录**」—— ▎**那是一个假设，代码没有核对它。**

⚠️ 危害长相：老板问「上周营收多少」，模型若吐
`{"type":"absolute","start":"2026-08-04","end":"2026-08-10"}`，
代码照单全收 ⇒ 窗口和披露都由模型的心算决定。
**算错一天，老板拿到的数就是错的，而它看起来完全正常。**
（prompt 规则 1 明写「你绝对不能计算或输出具体日期」——
 但那是**指令**，⛔ 不是**闸**。）

## ⛔ 先量，不下结论

`_gap22` 第 2 项报 🔴，而它的判据找的是两个**仓里不存在的函数名** ——
那种红我今天已经踩过一次（`_REALWORLD_EVENT`）。
⇒ 本探针回答两件**事实**：

    ① 模型什么时候吐 absolute
    ② 吐出来的日期，**原话里有没有**

## 判据（⛔ 结构，不看措辞）

对每一轮，取 `time_range`，若 `type == "absolute"`，把 `start` / `end` 拆成
数字片段，逐个问「这几个数字在问句里出现过吗」。
▎⛔ 不整串比对 —— 老板写「6月3号」而模型转录成 `2026-06-03`，
▎那是**合法转录**，不是编造。判据要能分开这两件事。

## 阳性/阴性对照（⛔ 不许省）

- **阳性**：显式日期那组必须**至少一次** `absolute` ——
  一次都没有说明这一格根本没被触到，②的读数没有意义（`rc=2`）
- **阴性**：纯相对说法那组（「上周」「本月」）`absolute` 应为 **0**；
  非 0 本身就是缺陷的直接证据
- ⚠️ 每轮清缓存 + `session_key` 唯一
"""
from __future__ import annotations

import asyncio
import os
import re
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_AD_FID = "MOCK_REST"
_AD_ROUNDS = 2
_AD_RUN = "%d-%d" % (os.getpid(), int(time.time()))
ctx = bootstrap_probe(_AD_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: (标签, 问句) —— 两组，⛔ 不混着判。
_AD_EXPLICIT = [
    "6月3号到18号的营收是多少",
    "2026-07-01 到 2026-07-15 卖了多少钱",
    "8月1日至8月10日的订单数",
]
_AD_RELATIVE = [
    "上周营收多少",
    "本月生意怎么样",
    "最近30天卖了多少钱",
    "上个季度的毛利",
]

_AD_NUM = re.compile(r"\d+")
_AD_SEEN = []
_AD_REAL_PARSE = ri._t3_llm_parse


async def _spy(*a, **kw):
    parsed = await _AD_REAL_PARSE(*a, **kw)
    _AD_SEEN.append(parsed if isinstance(parsed, dict) else None)
    return parsed


ri._t3_llm_parse = _spy


def _clear():
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, n)()


def _digits_backed_by_query(value: str, query: str):
    """`value` 里的数字片段，逐个问「问句里出现过吗」。

    ⛔ 不整串比对：老板写「6月3号」而模型转录成 `2026-06-03` 是**合法转录**。
    ⚠️ 年份单独看待 —— 老板极少说年份，模型补当年是正常的。
    """
    parts = _AD_NUM.findall(value or "")
    out = []
    for i, p in enumerate(parts):
        is_year = len(p) == 4 and p.startswith("20")
        # 去前导零后再找一次：「03」在原话里是「3号」
        found = (p in query) or (p.lstrip("0") and p.lstrip("0") in query)
        out.append({"part": p, "year": is_year, "in_query": bool(found)})
    return out


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s  轮数=%d  RUN=%s" % (ctx.db_name, _AD_ROUNDS, _AD_RUN))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2

    rows = []
    for rnd in range(1, _AD_ROUNDS + 1):
        for tag, group in (("①原话有日期", _AD_EXPLICIT), ("②只有相对说法", _AD_RELATIVE)):
            for q in group:
                _clear()
                set_factory_id(_AD_FID)
                n0 = len(_AD_SEEN)
                try:
                    await ri.parse_restaurant_query(
                        q, pool, factory_id=_AD_FID,
                        session_key="ad-%s-r%d-%s" % (_AD_RUN, rnd, q),
                        semantic_first=True)
                except Exception as exc:      # ⛔ 不 continue：失败要计数并贴出来
                    rows.append((rnd, tag, q, "EXC", repr(exc)[:120], []))
                    continue
                got = [p for p in _AD_SEEN[n0:] if isinstance(p, dict)]
                if not got:
                    rows.append((rnd, tag, q, "无编译", None, []))
                    continue
                tr = got[-1].get("time_range")
                kind = (tr or {}).get("type") if isinstance(tr, dict) else None
                backing = []
                if kind == "absolute" and isinstance(tr, dict):
                    for key in ("start", "end"):
                        backing.extend(_digits_backed_by_query(
                            str(tr.get(key) or ""), q))
                rows.append((rnd, tag, q, kind or "-", tr, backing))

    print("\n" + "=" * 78)
    print("逐条（⛔ 不折叠）")
    for rnd, tag, q, kind, tr, backing in rows:
        bad = [b for b in backing if not b["in_query"] and not b["year"]]
        mark = "🔴 原话里没有" if bad else ("✅" if kind == "absolute" else "")
        print("\nr%d 【%s】%s" % (rnd, tag, q))
        print("   time_range = %s" % (tr,))
        if backing:
            print("   数字片段: %s" % [
                "%s%s%s" % (b["part"], "(年)" if b["year"] else "",
                            "✓" if b["in_query"] else "✗") for b in backing])
        print("   判定: %s%s" % (kind, ("  " + mark) if mark else ""))

    exp = [r for r in rows if r[1] == "①原话有日期"]
    rel = [r for r in rows if r[1] == "②只有相对说法"]
    n_exp_abs = sum(1 for r in exp if r[3] == "absolute")
    n_rel_abs = sum(1 for r in rel if r[3] == "absolute")
    fabricated = [r for r in rows
                  if any(not b["in_query"] and not b["year"] for b in r[5])]

    print("\n" + "=" * 78)
    print("🔑 判定")
    print("  阳性对照·①原话有日期 ⇒ absolute = %d/%d" % (n_exp_abs, len(exp)))
    if not n_exp_abs:
        print("  ⛔ rc=2 一次都没吐 absolute —— 这一格没被触到，②的读数没有意义")
        return 2
    print("  阴性对照·②只有相对说法 ⇒ absolute = %d/%d（应为 0）"
          % (n_rel_abs, len(rel)))
    print("  🔴 日期数字**原话里没有**的轮次 = %d/%d" % (len(fabricated), len(rows)))
    for r in fabricated:
        print("     r%d 【%s】%s -> %s" % (r[0], r[1], r[2], r[4]))

    if fabricated or n_rel_abs:
        print("\n  ⇒ 🔴 模型给出了**不出自老板原话**的绝对日期，而代码只校验格式。")
        print("     ▎实体侧有 `_verbatim_entity`，日期侧没有对等的核对。")
        return 1
    print("\n  ⇒ ✅ 本轮没有观察到编造的绝对日期。")
    print("     ⛔ 别据此说「不可能发生」—— 这是 %d 条问句 × %d 轮的读数，"
          "口径是评测题库。" % (len(_AD_EXPLICIT) + len(_AD_RELATIVE), _AD_ROUNDS))
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
