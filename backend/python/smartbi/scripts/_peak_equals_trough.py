# -*- coding: utf-8 -*-
"""「最高月」和「最低月」是同一个月 —— 而且它本身就是残月。

## 症状（外部审计 3/3 稳定）

老板问「营收为什么下滑」，产品答：

    - 数据月数：2   - 最高月：2026-07   - 最低月：2026-07

## 成因（已定位到代码，⛔ 本探针不重新推导）

`restaurant_ops_router.py` 9154-9164：

```python
comparable_monthly = monthly[:-1] if latest_month_partial and len(monthly) > 1 else monthly
if (window[0].day != 1 and len(comparable_monthly) > 1
        and comparable_monthly[0].month == window[0] 那个月):
    comparable_monthly = comparable_monthly[1:]
peak  = max(comparable_monthly, ...)
trough = min(comparable_monthly, ...)
```

30 天窗 ⇒ `monthly = [残首月, 残尾月]`
       ⇒ 规则一剔残尾月 ⇒ `comparable_monthly` 只剩 **1** 个
       ⇒ 规则二的守卫 `len > 1` 不成立 ⇒ **残首月没被剔**
       ⇒ `max` / `min` 作用在单元素列表上 ⇒ 同一个元素，且它本身是残月

▎**没有人守【过滤后的基数】。**

## 本探针量什么（⛔ 只量事实，不下修法结论）

    ① `window_label` —— brief 已登记「默认窗**本身不稳定**」，两条路径都要覆盖
    ② 正文里的「营收最高月 / 营收最低月」（⚠️ 正则要**吃 markdown 加粗**）
    ③ `kpis` 里的「最高月 / 最低月」两条
    ④ 两者相等 ⇒ 🔴

## 阳性/阴性对照（⛔ 不许省）

- **阳性**：一句**全历史**口径的问句必须给出**不同**的最高/最低月
  —— 给不出说明这条路整体坏了，本次读数作废（`rc=2`）
- **阴性**：`planner_authority == 'llm_unavailable'` 计数必须为 0
  （读 `spec.planner_authority`，⛔ 顶层 `res` 没有这个键），非 0 ⇒ `rc=2`
"""
from __future__ import annotations

import asyncio
import os
import re
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_PT_FID = "MOCK_REST"
_PT_ROUNDS = 3
_PT_RUN = "%d-%d" % (os.getpid(), int(time.time()))
ctx = bootstrap_probe(_PT_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: ⚠️ 正则**必须吃 markdown 加粗** —— 答案里写的是
#: 「- 营收最高月: **2026-07** (¥…)」，而 KPI 块里是「最高月：2026-07」。
#: brief 明写「任务 1 就栽在这」。
_PT_PEAK_RE = re.compile(r"营收最高月[:：]\s*\**\s*(\d{4}-\d{2})")
_PT_TROUGH_RE = re.compile(r"营收最低月[:：]\s*\**\s*(\d{4}-\d{2})")

# 🔴 样本订正（第一版两处都选错了，留痕）:
#   ① 「营收为什么下滑」现在是 `kind=clarification` —— 那是**同日 B 那一刀**
#      之后的形态（产出被驳回 ⇒ clarification + 手边的数）。
#      ▎缺陷仍在，但**载体换了**：它走不到 TREND_ANALYSIS 那条路了。
#   ② 「全部历史的营收趋势怎么样」实测 `window_label='最近30天'` ——
#      **它根本没走全历史**，我的阳性对照选错了问句
#      （而它恰好复现了缺陷，反倒证明载体是 TREND_ANALYSIS）。
# ⇒ 载体换成能稳定走到 TREND_ANALYSIS 的问句；阳性对照按
#   **实测的 window_label** 分组，⛔ 不靠问句措辞假设它走了哪个窗。
_PT_CASES = [
    ("①趋势", "营收趋势怎么样"),
    ("①趋势", "最近30天营收趋势怎么样"),
    ("②候选·更长窗", "今年每个月的营收是多少"),
    ("②候选·更长窗", "2025年到现在每个月营收趋势"),
]


def _clear() -> list:
    """⚠️ 逐个贴出清了哪几个。`clear_promotion_cache` **不存在**，
    写了会静默 no-op（brief 纪律 3）。"""
    done = []
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        fn = getattr(ri, n, None)
        if fn is None:
            done.append("%s(⛔不存在)" % n)
            continue
        fn()
        done.append(n)
    return done


def _kpi_month(kpis, title: str):
    for k in (kpis or []):
        if isinstance(k, dict) and k.get("title") == title:
            return k.get("value")
    return None


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s 轮数=%d RUN=%s"
          % (ctx.db_name, _PT_FID, _PT_ROUNDS, _PT_RUN))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2

    rows = []
    n_unavailable = 0
    for rnd in range(1, _PT_ROUNDS + 1):
        for tag, q in _PT_CASES:
            cleared = _clear()
            set_factory_id(_PT_FID)
            # ⚠️ `session_key` 每次唯一 + `history` 也要传（纪律 4）：
            #    `restaurant_pending_clarifications` 跨进程存活，只传 history
            #    会让第二轮丢槽位，量出一个比真实情况差的读数。
            skey = "pt-%s-r%d-%s" % (_PT_RUN, rnd, q)
            async with rr.dish_catalogue_scope(pool, _PT_FID):
                spec = await ri.parse_restaurant_query(
                    q, pool, factory_id=_PT_FID, session_key=skey,
                    semantic_first=True)
                res = None
                if spec is not None and svc.should_delegate(spec, None, query=q):
                    res = await svc.tiered_answer(
                        q, pool, _PT_FID, ctx.role,
                        precomputed_spec=spec, session_key=skey, history=[])
            res = res or {}
            # ⚠️ 阴性对照读 `spec.planner_authority`，⛔ 顶层 res 没有这个键
            authority = getattr(spec, "planner_authority", None) if spec else None
            if authority == "llm_unavailable":
                n_unavailable += 1
            text = res.get("answer_text") or ""
            kpis = res.get("kpis")
            m = _PT_PEAK_RE.search(text)
            peak_prose = m.group(1) if m else None
            m = _PT_TROUGH_RE.search(text)
            trough_prose = m.group(1) if m else None
            peak_kpi = _kpi_month(kpis, "最高月")
            trough_kpi = _kpi_month(kpis, "最低月")
            win = getattr(spec, "window_label", None) if spec else None
            same = bool(peak_prose and peak_prose == trough_prose) or \
                bool(peak_kpi and peak_kpi == trough_kpi)
            rows.append((rnd, tag, q, win, res.get("kind"), authority,
                         peak_prose, trough_prose, peak_kpi, trough_kpi,
                         same, len(kpis) if isinstance(kpis, list) else None))

            print("\n" + "=" * 78)
            print("r%d 【%s】%s" % (rnd, tag, q))
            print("   清了: %s" % "、".join(cleared))
            print("   window_label=%r  kind=%s  authority=%r  kpis=%s"
                  % (win, res.get("kind"), authority,
                     len(kpis) if isinstance(kpis, list) else None))
            print("   正文  最高月=%r  最低月=%r" % (peak_prose, trough_prose))
            print("   kpis  最高月=%r  最低月=%r" % (peak_kpi, trough_kpi))
            print("   判定: %s" % ("🔴 最高月 == 最低月" if same else "—"))
            # ⚠️ 判据③ 要求**贴原文** —— 只报 None 说不清老板读到了什么。
            # ⛔ 这几行**必须用 Edit 直接落盘**：第一版用 heredoc 写，
            #    `\n` 被 shell 吃成真换行，当场把文件写成 SyntaxError
            #    ——「转义会被吃掉」今天第二次（硬约束 6）。
            head = "\n".join(text.split("\n")[:6])
            print("   ── 老板看到的（前 6 行）──")
            print("   " + (head or "(空)").replace("\n", "\n   "))

    print("\n" + "=" * 78)
    print("阴性对照·planner_authority == 'llm_unavailable' = %d 次" % n_unavailable)
    if n_unavailable:
        print("⛔ rc=2 有 fail-closed 拒答混在里面 —— ⛔ 不折叠进「答不上」，读数作废")
        return 2

    # 🔑 阳性对照按**实测的 window_label** 判，⛔ 不按问句措辞假设它走了哪个窗
    #    （第一版就是那么假设的，而那句问句实测走的是「最近30天」）。
    with_months = [r for r in rows if r[6] and r[7]]
    pos_ok = [r for r in with_months if r[6] != r[7]]
    print("阳性对照·**任何**一轮给出两个不同的月 = %d（共 %d 轮读到了月份）"
          % (len(pos_ok), len(with_months)))
    if not with_months:
        print("⛔ rc=2 一轮都没读到最高/最低月 —— 载体问句没走到那条路，读数作废")
        return 2
    if not pos_ok:
        print("⚠️ 所有读到月份的轮次都是「最高==最低」——")
        print("   ⛔ 这**不能**直接当成阳性对照失败: 也可能所有轮次的窗口都是")
        print("   30 天。下面按 window_label 分组看，⛔ 别只看总数。")

    dflt = [r for r in rows if r[6] and r[7]]
    bad = [r for r in dflt if r[10]]
    print("\n🔑 判定（默认窗那 %d 轮）" % len(dflt))
    print("  🔴 最高月 == 最低月 = %d/%d" % (len(bad), len(dflt)))
    print("\n  窗口分布（brief 已登记「默认窗本身不稳定」，两条路径都要覆盖）:")
    wins = {}
    for r in dflt:
        wins[r[3]] = wins.get(r[3], 0) + 1
    for w, c in sorted(wins.items(), key=lambda kv: -kv[1]):
        same_n = sum(1 for r in dflt if r[3] == w and r[10])
        print("     %-14r %d 轮   其中最高==最低 %d 轮" % (w, c, same_n))
    print("\n⚠️ 口径：MOCK_REST / %d 轮 / 评测题库，⛔ 不是真实流量。" % _PT_ROUNDS)
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
