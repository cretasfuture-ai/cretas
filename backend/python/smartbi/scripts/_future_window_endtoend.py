# -*- coding: utf-8 -*-
"""整段落在未来的时间窗 —— 老板到底看到哪句话？

## 我想知道的 X / 我实际在量的 Y（形态 A，动手前并排读一遍）

    X: 老板问一个**整段都在未来**的时间窗时，产品说的是不是实话
    Y: 端到端 `tiered_answer` 返回的 `kind` + 正文原文

⛔ 只量 resolver 层不算 —— 上一条线实测「端到端 0/4 而 resolver 层 6/6」，
   **两层读数不一致**。所以本探针在 `tiered_answer` 上读，⛔ 不在
   `_resolve_sales_date_range` 上读（那一层单独由 `_local_readout()` 打印，
   目的是**让两层的差异自己显形**，不是拿它当结论）。

## 四组（⛔ 不混着判）

    A 绝对区间·整段在未来   —— 缺陷组。期望：诚实拒答，⛔ 不是「系统这会儿有点忙」
    B 相对未来词           —— 诚实通道**已经存在**的那条路。期望：诚实拒答
    C 正常过去窗口         —— 阳性对照。**修完必须逐字不变**
    D 跨界区间(半过去半未来) —— T6(e) 截断的**正当用途**。期望：截到今天并正常作答

## 判据（⛔ 结构，不看措辞）

  · `kind`                      answer / clarification / unavailable
  · `spec.window_label`          指定区间 / 未来时间 / …
  · `spec.date_range`            **颠倒**(start > end) = 直接证据
  · `spec.planner_authority`     `llm_unavailable` ⇒ rc=2 作废，⛔ 不折叠进「答不上」
  · `money`                      正文里有没有金额（`¥`）
  · `past_dates`                 正文里有没有**不早于**问句、却**已经发生**的日期

🔴 **第一版判据是错的，这里记一笔。** 它写成「kind 不是 unavailable 且区间没颠倒
   就算诚实」，于是把
       「2026年9月1号到9月10号的毛利率是多少」
         -> kind=answer「**菜品毛利分析（2026-07-20 至 2026-08-19）**…¥9,576,887.96」
   读成了 ✅。▎**答了一个完全不同的时间段，比「系统忙」更糟，而尺子说没问题。**
   ⇒ A/B 组的「诚实」必须**同时**满足三条：不是 `unavailable`、正文里没有金额、
     正文里没有已经发生的日期。少哪一条都能让静默替换蒙混过去。

## 阳性对照（⛔ 不许省，且要求**每一轮**都成立）

  C 组必须 `kind == "answer"` —— C 组挂掉说明这台机器上整条链就不通，
  A/B/D 的读数一律无意义（rc=2）。
  ⚠️ 「任何一轮成立就算过」在仪器只坏一半时照样绿，所以这里要求逐轮。

⚠️ 每轮清缓存并**贴出清了哪几个**（硬约束 3）；`session_key` 每次唯一。
"""
from __future__ import annotations

import asyncio
import json
import os
import re
import sys
import time
from datetime import date

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_FID = os.environ.get("PROBE_FID", "MOCK_REST")
_ROUNDS = int(os.environ.get("PROBE_ROUNDS", "2"))
_RUN = "%d-%d" % (os.getpid(), int(time.time()))

ctx = bootstrap_probe(_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as R  # noqa: E402
from smartbi.gold.restaurant.restaurant_intent_service import tiered_answer  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: (组, 问句)。⛔ A 组的年份是**显式**的 —— 无年份时 `parse_absolute_date_range`
#: 有一条「落未来 -> 指去年」的既有规则会把它接走(实测)，那是**另一件事**。
CASES = [
    ("A·绝对区间整段在未来", "2026年8月25号到8月31号的营收是多少"),
    ("A·绝对区间整段在未来", "2026年9月1号到9月10号的毛利率是多少"),
    ("A·绝对区间整段在未来", "2026年8月25日至2026年8月31日各门店营收排名"),
    ("A·绝对区间整段在未来", "2026年8月25号到8月31号的折扣力度是多少"),
    ("B·相对未来词", "下周营收多少"),
    ("B·相对未来词", "下下周营收多少"),
    ("B·相对未来词", "下个月毛利率多少"),
    ("C·阳性·正常过去窗口", "2026年8月1号到8月15号的营收是多少"),
    ("C·阳性·正常过去窗口", "最近30天营收多少"),
    ("C·阳性·正常过去窗口", "上周营收多少"),
    ("D·跨界·半过去半未来", "2026年8月10号到8月31号的营收是多少"),
    # E 组 = 回归格。与时间窗**无关**的三条，两棵树上 kind 必须一致。
    ("E·回归·无关问句", "有哪些门店"),
    ("E·回归·无关问句", "最近30天卖得最好的菜是哪些"),
    ("E·回归·无关问句", "有没有菜是卖一份亏一份的"),
]


_ISO = re.compile(r"20\d{2}-\d{2}-\d{2}")


def _substitution_marks(text: str, today: date):
    """静默替换的两个可观测量。⛔ 不匹配任何一句具体措辞。

    · `money`      正文里出现金额 —— 一个「这段时间还没到」的答案不该有钱数
    · `past_dates` 正文里出现**已经发生**的日期 —— 那只能来自别的窗口
    """
    money = "¥" in (text or "")
    past = sorted({d for d in _ISO.findall(text or "")
                   if date.fromisoformat(d) <= today})
    return money, past


def _clear():
    """⛔ 用模块自己的 helper，⛔ 不拼属性名。清了哪几个**贴出来**。"""
    names = ("clear_semantic_plan_cache", "clear_route_cache",
             "clear_tenant_gate_cache", "clear_promoted_routes_cache")
    for n in names:
        getattr(ri, n)()
    return " / ".join(names)


def _local_readout(anchor: date):
    """确定性层自己怎么解这句话 —— **单独打印**，让两层的差异显形。"""
    print("=" * 88)
    print("确定性层 `_resolve_sales_date_range`（anchor=%s）——⛔ 这不是结论，"
          "是给端到端读数当对照" % anchor)
    for group, q in CASES:
        (s, e), lbl = R._resolve_sales_date_range(q, today=anchor)
        inv = s is not None and e is not None and s > e
        print("  [%s] %-34s -> (%s, %s) label=%-6s %s"
              % (group[0], q, s, e, lbl, "🔴颠倒" if inv else ""))


async def main() -> int:
    print("PROBE_DB=%s  租户=%s  轮数=%d  RUN=%s  今天=%s"
          % (ctx.db_name, _FID, _ROUNDS, _RUN, date.today()))
    print("代码来源: router=%s" % getattr(R, "__file__", "?"))
    if ctx.llm_dead_slots:
        print("⛔ rc=2 LLM 槽没有活账号: %s —— 每条答案都会是 fail-closed 拒答"
              % "、".join(ctx.llm_dead_slots))
        return 2

    pool = await ctx.pool()
    _local_readout(date.today())

    rows = []
    for rnd in range(1, _ROUNDS + 1):
        for group, q in CASES:
            cleared = _clear()
            set_factory_id(_FID)
            print("-" * 88)
            print("r%d [%s] %s" % (rnd, group, q))
            print("  [清缓存] %s" % cleared)
            try:
                spec = await ri.parse_restaurant_query(
                    q, pool, factory_id=_FID,
                    session_key="fw-%s-r%d-%s" % (_RUN, rnd, q),
                    history=[], semantic_first=True)
            except Exception as exc:  # noqa: BLE001 ⛔ 不 continue: 失败要计数并贴出来
                print("  🔴 规划抛异常: %r" % (exc,))
                rows.append((rnd, group, q, "PLAN_EXC", None, None, None, repr(exc)[:160]))
                continue
            authority = getattr(spec, "planner_authority", "")
            dr = getattr(spec, "date_range", None)
            wl = getattr(spec, "window_label", None)
            print("  intent=%s  source_tier=%s  authority=%s"
                  % (spec.intent, getattr(spec, "source_tier", "?"), authority))
            print("  date_range=%s  window_label=%r  颠倒=%s"
                  % (dr, wl,
                     bool(dr and dr[0] and dr[1] and dr[0] > dr[1])))
            try:
                result = await tiered_answer(
                    q, pool, factory_id=_FID, role=ctx.role,
                    precomputed_spec=spec)
            except Exception as exc:  # noqa: BLE001
                print("  🔴 执行抛异常: %r" % (exc,))
                rows.append((rnd, group, q, "EXEC_EXC", authority, dr, wl, repr(exc)[:160]))
                continue
            kind = (result or {}).get("kind")
            text = ((result or {}).get("answer_text") or "").replace("\n", " ")
            money, past = _substitution_marks(text, date.today())
            print("  kind=%s  正文 %d 字  金额=%s  已发生的日期=%s"
                  % (kind, len(text), money, past or "无"))
            print("  ▎老板看到的原文: %s" % text[:260])
            rows.append((rnd, group, q, kind, authority, dr, wl, text))

    # ── rc=2 闸：LLM 编译不出来时读数作废 ───────────────────────────────
    bad_auth = [r for r in rows if r[4] == "llm_unavailable"]
    if bad_auth:
        print("\n⛔ rc=2  %d/%d 条 spec.planner_authority == 'llm_unavailable'"
              " —— 这是 fail-closed 拒答，⛔ 不折叠进「答不上」" % (len(bad_auth), len(rows)))
        return 2

    # ── 阳性对照：C 组**每一轮**都必须 answer ───────────────────────────
    c_rows = [r for r in rows if r[1].startswith("C")]
    c_bad = [r for r in c_rows if r[3] != "answer"]
    print("\n" + "=" * 88)
    print("阳性对照 C 组（正常过去窗口）: answer %d/%d" % (len(c_rows) - len(c_bad), len(c_rows)))
    for r in c_bad:
        print("  🔴 r%d %s -> kind=%s" % (r[0], r[2], r[3]))
    if c_bad:
        print("⛔ rc=2  阳性对照没有**每一轮**成立 ⇒ 这台机器上整条链就不通，"
              "A/B/D 的读数一律无意义")
        return 2

    print("\n" + "=" * 88)
    print("逐条（⛔ 不折叠）")
    for r in rows:
        rnd, group, q, kind, _auth, dr, wl, text = r
        money, past = _substitution_marks(text, date.today())
        print("  r%d [%s] %-34s kind=%-14s label=%-6s 金额=%-5s 已发生日期=%-24s range=%s"
              % (rnd, group[0], q, kind, wl, money,
                 ",".join(past) if past else "无", dr))

    print("\n" + "=" * 88)
    print("🔑 按组汇总（口径：**评测题库**，⛔ 不是真实流量）")
    verdict = 0
    for prefix, expect in (("A", "诚实拒答"), ("B", "诚实拒答"),
                           ("C", "answer"), ("D", "answer")):
        grp = [r for r in rows if r[1].startswith(prefix)]
        if prefix in ("C", "D", "E"):
            ok = [r for r in grp if r[3] == "answer"]
        else:
            # 诚实 = 三条同时成立（⛔ 少一条静默替换就蒙混过去，见文件头那一笔）:
            #   ① 不是假故障  ② 正文里没有金额  ③ 正文里没有已经发生的日期
            ok = []
            for r in grp:
                money, past = _substitution_marks(r[7], date.today())
                if (r[3] not in ("unavailable", "EXEC_EXC", "PLAN_EXC")
                        and not money and not past):
                    ok.append(r)
        print("  %s 组: %d/%d 达到「%s」" % (prefix, len(ok), len(grp), expect))
        if len(ok) != len(grp):
            verdict = 1
            for r in grp:
                if r not in ok:
                    money, past = _substitution_marks(r[7], date.today())
                    print("     🔴 r%d %s -> kind=%s 金额=%s 已发生日期=%s range=%s label=%s"
                          % (r[0], r[2], r[3], money, past or "无", r[5], r[6]))
                    print("        ▎老板看到的原文: %s" % (r[7] or "")[:200])
    # ── 回归格：E 组不判对错，只把 kind 逐条打出来供两棵树比对 ──────────
    print("\n回归格 E（⛔ 不判对错，两棵树之间比 kind）")
    for r in [x for x in rows if x[1].startswith("E")]:
        print("  REGRESSION r%d %s kind=%s 字数=%d"
              % (r[0], r[2], r[3], len(r[7] or "")))

    print("\n口径: %d 条问句 × %d 轮 = %d 次调用，租户 %s。"
          % (len(CASES), _ROUNDS, len(rows), _FID))
    return verdict


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
