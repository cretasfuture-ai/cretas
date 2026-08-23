# -*- coding: utf-8 -*-
"""单菜回答的形状 —— 任务 2（混着未标范围的全店数）+ 任务 4（空壳小节）。

## 两件事在**同一个答案**里，所以一支探针

    任务 2  抬头「营收 ¥5,403,264.00」是**罗氏虾**的，
            毛利段「实收营收 ¥21,104,706.93」是**全店**的 —— 而它没标范围。
            ⚠️ 订正: 外部审计原来还说「自称 100.0% 是错的」——**那条已撤回**。
               100% 是对的。⇒ 只剩「未标范围的全店数」这一件。
    任务 4  毛利段全是空的却把格式撑满:
            「计算过程：毛利 ¥0.00 = 净营收 ¥0.00 − 成本 ¥0.00」
            「毛利前 0 名菜品:  - 暂无…」「需要关注的低毛利菜品:  - 暂无…」
            ⇒ 违反交付定义 ⑤（答不了时说清缺什么）。

## ⛔ 本探针只量事实

    ① 全文 + 逐行贴出来（⛔ 不截断 —— 今天已经因为截断读数栽过两次）
    ② 抬头那个营收数 与 毛利段那个实收营收数，是不是**两个不同的数**
    ③ 「¥0.00」出现几次、「暂无」出现几次（空壳小节的量）
    ④ kpis 有没有、几条（任务 3 的输入）

## 阳性/阴性对照（⛔ 不许省）

- **阳性**：抬头必须读得到一个金额 —— 读不到说明这条路没跑起来（`rc=2`）
- **阴性**：`planner_authority == 'llm_unavailable'` 必须为 0，非 0 ⇒ `rc=2`
"""
from __future__ import annotations

import asyncio
import os
import re
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_SD_FID = "MOCK_REST"
_SD_ROUNDS = 2
_SD_RUN = "%d-%d" % (os.getpid(), int(time.time()))
ctx = bootstrap_probe(_SD_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

_SD_QUERY = "罗氏虾卖得怎么样"
#: 抬头那句：「「罗氏虾」最近30天…营收 **¥5,403,264.00**、」
_SD_HEAD_REV_RE = re.compile(r"营收\s*\**\s*¥([\d,]+\.\d{2})")
# 🔴 2026-08-19 探针订正（留痕，⛔ 不删）——
#    第一版是 `实收营收\s*\**\s*¥([\d,]+\.\d{2})`。修好之后那一行变成
#        - 实收营收（全部门店、全部菜品）**¥21,309,192.74**
#    括号把正则挡在外面 ⇒ 读出 `[]` ⇒ `mixed` 为空 ⇒ **rc=0**。
#    ▎那个 0 长得和「修好了」一模一样，而它其实是**仪器坏了**。
#    （本仓已经因为「修好之后没重想一遍期望」栽过两次。）
#
# ⇒ 判据也跟着换：任务 2 的修法是 **(a) 加范围标签**，
#   ⛔ 不是让两个数变成一个数 —— 它们本来就该不同（单菜 vs 全店全菜）。
#   ▎所以「同屏两个不同的数」**不再是**缺陷本身，
#   ▎缺陷是「第二个数**不自带**范围标签」。
_SD_NET_REV_LINE_RE = re.compile(r"^.*实收营收.*$", re.M)
_SD_NET_REV_RE = re.compile(r"实收营收[^¥\n]*¥\s*\**\s*([\d,]+\.\d{2})")
#: 范围标签的两半 —— 两半都要在**同一行**上（老板读到的是「抬头的下一行」）
_SD_SCOPE_HALVES = ("全部门店", "全部菜品")


def _clear() -> list:
    done = []
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        fn = getattr(ri, n, None)
        done.append(n if fn else "%s(⛔不存在)" % n)
        if fn:
            fn()
    return done


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s 轮数=%d RUN=%s"
          % (ctx.db_name, _SD_FID, _SD_ROUNDS, _SD_RUN))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2

    rows = []
    for rnd in range(1, _SD_ROUNDS + 1):
        cleared = _clear()
        set_factory_id(_SD_FID)
        skey = "sd-%s-r%d" % (_SD_RUN, rnd)
        async with rr.dish_catalogue_scope(pool, _SD_FID):
            spec = await ri.parse_restaurant_query(
                _SD_QUERY, pool, factory_id=_SD_FID, session_key=skey,
                semantic_first=True)
            res = None
            if spec is not None and svc.should_delegate(spec, None, query=_SD_QUERY):
                res = await svc.tiered_answer(
                    _SD_QUERY, pool, _SD_FID, ctx.role,
                    precomputed_spec=spec, session_key=skey, history=[])
        res = res or {}
        authority = getattr(spec, "planner_authority", None) if spec else None
        text = res.get("answer_text") or ""
        kpis = res.get("kpis")
        heads = _SD_HEAD_REV_RE.findall(text)
        nets = _SD_NET_REV_RE.findall(text)
        # 那一行的**原文** —— 判据落在同一行上，⛔ 不是「正文某处提到过」
        net_lines = _SD_NET_REV_LINE_RE.findall(text)
        # 🔴 判据：读到了那个全店数，而它所在的**那一行**没有自带范围标签
        unlabelled = bool(nets) and not any(
            all(h in ln for h in _SD_SCOPE_HALVES) for ln in net_lines)
        zeros = text.count("¥0.00")
        nones = text.count("暂无")
        rows.append((rnd, authority, res.get("kind"), len(text),
                     heads[:1], nets[:1], zeros, nones,
                     len(kpis) if isinstance(kpis, list) else None,
                     sorted(res.keys()), unlabelled, net_lines[:1]))

        print("\n" + "=" * 78)
        print("r%d 【%s】" % (rnd, _SD_QUERY))
        print("   清了: %s" % "、".join(cleared))
        print("   kind=%s  authority=%r  正文 %d 字  kpis=%s"
              % (res.get("kind"), authority, len(text),
                 len(kpis) if isinstance(kpis, list) else None))
        print("   res 顶层键: %s" % sorted(res.keys()))
        print("   抬头营收(首个) = %s   毛利段实收营收 = %s"
              % (heads[:1], nets[:1]))
        print("   实收营收那一行的原文: %s" % (net_lines[:1] or "(读不到 —— 仪器可能坏了)"))
        print("   任务2 判定: %s" % ("🔴 全店数不自带范围标签" if unlabelled else "—"))
        print("   「¥0.00」出现 %d 次   「暂无」出现 %d 次" % (zeros, nones))
        print("   ── 老板看到的**全文**（⛔ 不截断）──")
        for ln in text.split("\n"):
            print("   | " + ln)

    print("\n" + "=" * 78)
    n_unavail = sum(1 for r in rows if r[1] == "llm_unavailable")
    print("阴性对照·llm_unavailable = %d 次" % n_unavail)
    if n_unavail:
        print("⛔ rc=2 有 fail-closed 拒答混在里面，读数作废")
        return 2
    with_head = [r for r in rows if r[4]]
    print("阳性对照·抬头读到金额 = %d/%d" % (len(with_head), len(rows)))
    if not with_head:
        print("⛔ rc=2 抬头一个金额都没读到 —— 这条路没跑起来，读数作废")
        return 2

    # ⚠️ 阳性对照（第二格，⛔ 不许省）：那个全店数**必须**读得到。
    #    读不到时 `unlabelled` 恒为假 —— 也就是「没有缺陷」的长相。
    #    ▎「没发生」是最容易伪造的读数（硬约束 9）。
    with_net = [r for r in rows if r[5]]
    print("阳性对照·读到全店实收营收 = %d/%d" % (len(with_net), len(rows)))
    if not with_net:
        print("⛔ rc=2 一轮都没读到那个全店数 —— 正则被产品措辞变化挡在外面，")
        print("   而它的读数长得和「修好了」一模一样。读数作废。")
        return 2

    mixed = [r for r in rows if r[4] and r[5] and r[4][0] != r[5][0]]
    unlabelled = [r for r in rows if r[10]]
    print("\n🔑 任务 2 · 全店数**不自带范围标签** = %d/%d" % (len(unlabelled), len(rows)))
    print("   （同屏两个不同口径的营收 %d/%d —— ⚠️ 这**不再是**缺陷本身：" % (len(mixed), len(rows)))
    print("    单菜抬头与全店合计本来就该是两个数，缺陷是第二个不说自己的范围。）")
    for r in rows:
        print("     r%d 抬头=%s 实收营收=%s %s"
              % (r[0], r[4], r[5], "🔴" if r[10] else ""))
        print("        那一行: %s" % (r[11] or "(读不到)"))
    print("\n🔑 任务 4 · 空壳小节的量")
    for r in rows:
        print("     r%d 「¥0.00」%d 次  「暂无」%d 次  正文 %d 字"
              % (r[0], r[6], r[7], r[3]))
    print("\n🔑 任务 3 · 机器可读侧")
    for r in rows:
        print("     r%d kpis=%s  res 顶层键=%s" % (r[0], r[8], r[9]))
    print("\n⚠️ 口径：MOCK_REST / %d 轮 / 评测题库，⛔ 不是真实流量。" % _SD_ROUNDS)
    print("⛔ 本探针不下修法结论 —— 上面是事实。")
    shells = [r for r in rows if r[6] or r[7]]
    return 1 if (unlabelled or shells) else 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
