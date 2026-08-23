# -*- coding: utf-8 -*-
"""任务 3 —— 数字只在文字里，机器可读侧是空的。

## brief 原话 + 我的订正

brief 说「拒答路径的 res 顶层**没有 kpis 键**」，并明确标注
「『前端拿不到』是**推断**，先读消费方」。

【读码】消费方读完了，订正一条：

    smartbi/api/gold_reads.py:1356    "kpis": result.get("kpis") or [],

▎**缺键到不了前端** —— 这一层把它兜成 `[]`。
⇒ 症状的准确说法不是「没有 kpis 键」，是**前端收到空数组**。
  两者对前端等价（都渲染不出 KPI 卡），但**对成因不等价**：
  前者像「漏了透传」，后者是「那条路根本没往里放数」。
  🔴 读数对 ≠ 成因对（纪律 10）。

## 本探针量什么（⛔ 只量事实，不下修法结论）

逐轮记：

    kind            —— 按**实测的 kind 分组**，⛔ 不按问句措辞假设它会拒答
    'kpis' in res   —— 键在不在（brief 的原命题）
    len(kpis)       —— 有几条（前端真正拿到的）
    正文里的金额个数 —— 用 `¥[\d,]+` 数，这是「数字只在文字里」的那个「数字」
    正文里的百分比个数

判据（结构，⛔ 不是子串）：

    🔴 手边有数却不给 = 正文里数得到金额/百分比，而 len(kpis) == 0

## 阳性/阴性对照（⛔ 不许省）

- **阳性**：至少一轮 `kind == 'answer'` **且** `len(kpis) > 0`
  —— 否则说明本探针根本读不到 kpis（仪器坏了），`rc=2`
  ⚠️ 这条对照必须问「仪器坏掉时它会不会也成立」：读不到 kpis 时它**不成立**，
     所以它不是恒真式。
- **阴性**：`spec.planner_authority == 'llm_unavailable'` 计数必须为 0
  （⛔ 顶层 `res` 没有这个键），非 0 ⇒ `rc=2`，⛔ 不折叠进「答不上」
"""
from __future__ import annotations

import asyncio
import os
import re
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_NP_FID = "MOCK_REST"
_NP_ROUNDS = 2
_NP_RUN = "%d-%d" % (os.getpid(), int(time.time()))
ctx = bootstrap_probe(_NP_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

_NP_MONEY_RE = re.compile(r"¥\s*[\d,]+(?:\.\d+)?")
_NP_PCT_RE = re.compile(r"\d+(?:\.\d+)?\s*%")

# ⚠️ 题目按**可能落到哪条路**挑，但分组一律按**实测 kind**，
#    ⛔ 不按措辞假设（本仓已经因为「假设它走了哪个窗」栽过）。
_NP_CASES = [
    ("会答上的", "罗氏虾卖得怎么样"),
    ("会答上的", "最近30天营收多少"),
    ("可能拒答/追问", "营收为什么下滑"),
    ("可能拒答/追问", "各个渠道的毛利分别是多少"),
    ("可能拒答/追问", "外卖和堂食哪个更赚钱"),
    ("可能拒答/追问", "徐汇美罗城店最近30天为什么毛利低"),
]


def _clear() -> list:
    """⚠️ 逐个贴出清了哪几个（纪律 3）。
    `clear_promotion_cache` **不存在**，写了会静默 no-op。"""
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


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s 轮数=%d RUN=%s"
          % (ctx.db_name, _NP_FID, _NP_ROUNDS, _NP_RUN))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2

    rows = []
    n_unavailable = 0
    for rnd in range(1, _NP_ROUNDS + 1):
        for tag, q in _NP_CASES:
            cleared = _clear()
            set_factory_id(_NP_FID)
            # 纪律 4：`session_key` 每次唯一 + `history` 也要传
            skey = "np-%s-r%d-%s" % (_NP_RUN, rnd, q)
            async with rr.dish_catalogue_scope(pool, _NP_FID):
                spec = await ri.parse_restaurant_query(
                    q, pool, factory_id=_NP_FID, session_key=skey,
                    semantic_first=True)
                res = None
                if spec is not None and svc.should_delegate(spec, None, query=q):
                    res = await svc.tiered_answer(
                        q, pool, _NP_FID, ctx.role,
                        precomputed_spec=spec, session_key=skey, history=[])
            res = res or {}
            authority = getattr(spec, "planner_authority", None) if spec else None
            if authority == "llm_unavailable":
                n_unavailable += 1
            text = res.get("answer_text") or ""
            kpis = res.get("kpis")
            n_kpi = len(kpis) if isinstance(kpis, list) else None
            monies = _NP_MONEY_RE.findall(text)
            pcts = _NP_PCT_RE.findall(text)
            has_key = "kpis" in res
            # 🔴 判据：正文里数得到数，而机器可读侧是空的
            stranded = bool(monies or pcts) and not n_kpi
            rows.append((rnd, tag, q, res.get("kind"), authority, has_key,
                         n_kpi, len(monies), len(pcts), stranded, len(text),
                         sorted(res.keys())))

            print("\n" + "=" * 78)
            print("r%d 【%s】%s" % (rnd, tag, q))
            print("   清了: %s" % "、".join(cleared))
            print("   kind=%s  authority=%r  正文 %d 字"
                  % (res.get("kind"), authority, len(text)))
            print("   'kpis' 键在不在 = %s   len(kpis) = %s" % (has_key, n_kpi))
            print("   正文里金额 %d 个  百分比 %d 个" % (len(monies), len(pcts)))
            print("   金额样本: %s" % monies[:6])
            print("   判定: %s" % ("🔴 手边有数却不给" if stranded else "—"))
            print("   res 顶层键: %s" % sorted(res.keys()))

    print("\n" + "=" * 78)
    print("阴性对照·planner_authority == 'llm_unavailable' = %d 次" % n_unavailable)
    if n_unavailable:
        print("⛔ rc=2 有 fail-closed 拒答混在里面 —— ⛔ 不折叠进「答不上」，读数作废")
        return 2

    pos = [r for r in rows if r[3] == "answer" and (r[6] or 0) > 0]
    print("阳性对照·kind=answer 且 len(kpis)>0 = %d/%d 轮" % (len(pos), len(rows)))
    if not pos:
        print("⛔ rc=2 一轮都没读到非空 kpis —— 本探针读不到机器可读侧，读数作废")
        return 2

    print("\n🔑 判定 · 按**实测 kind** 分组（⛔ 不按问句措辞）")
    kinds = {}
    for r in rows:
        kinds.setdefault(r[3], []).append(r)
    for k, rs in sorted(kinds.items(), key=lambda kv: -len(kv[1])):
        bad = [r for r in rs if r[9]]
        nokey = [r for r in rs if not r[5]]
        print("   kind=%-14r %2d 轮   🔴手边有数却不给 %d   ⚠️无 kpis 键 %d"
              % (k, len(rs), len(bad), len(nokey)))
        for r in rs:
            print("        %-28s kpis=%-4s 金额%2d 百分比%2d %s"
                  % (r[2][:26], r[6], r[7], r[8], "🔴" if r[9] else ""))

    stranded_all = [r for r in rows if r[9]]
    print("\n🔑 总计 · 手边有数却不给 = %d/%d 轮" % (len(stranded_all), len(rows)))
    print("🔑 总计 · res 顶层无 'kpis' 键 = %d/%d 轮"
          % (len([r for r in rows if not r[5]]), len(rows)))
    print("\n⚠️ 口径：MOCK_REST / %d 轮 / 评测题库，⛔ 不是真实流量。" % _NP_ROUNDS)
    print("⛔ 本探针不下修法结论 —— 上面是事实。")
    return 1 if stranded_all else 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
