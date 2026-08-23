# -*- coding: utf-8 -*-
"""那句兜底建议发给了谁 —— 而他**是不是已经照它说的做了**。

## 起点

`restaurant_intent_service.py:2690` 那句：

    你可以把问题问得更小一点（限定到某家店、某道菜或某个时间段），
    那条路我能给你确定的数。

它**已经因为「假建议」被改过一次**（2026-08-17）：原版硬编码
「例如把**为什么**换成…」，实测发给了根本没问「为什么」的老板。
当时的修法是**换成不假设问句形状**的笼统说法。

▎**而那正是现在失败的原因。** 今天两条实测都撞上它：
▎「最近30天水煮牛肉丝卖得怎么样」（菜 + 时间段都限定了）
▎「最近30天哪个时段毛利最高」（时间段限定了）
▎—— 建议他做的事，**他已经做了**。同一个毛病换了个形状回来。

## 本探针只回答事实（⛔ 不下修法结论）

对每一句撞上这条兜底的问句，把**老板已经限定了什么**打出来：

    dish_slot / store_slots / date_range / dimensions

⇒ 「建议他限定的那几项」与「他已经限定的那几项」重叠多少。
▎重叠 = 那句建议对他是**废话**，甚至是**做不到的指引**。

## 阳性/阴性对照（⛔ 不许省）

- **阳性**：至少有一句要真的撞上这条兜底，否则这一格没被触到（`rc=2`）
- **阴性**：一组**正常出数**的问句必须 `kind == "answer"` 且不含这句 ——
  证明不是整条链坏了
"""
from __future__ import annotations

import asyncio
import os
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_GF_FID = "MOCK_REST"
_GF_ROUNDS = 1
_GF_RUN = "%d-%d" % (os.getpid(), int(time.time()))
ctx = bootstrap_probe(_GF_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

_GF_MARK = "不把推测说成结论"
#: 三样候选 —— ⛔ 只用于枚举，**不是**「这次建议了哪几样」的判据。
_GF_ALL = ("某家店", "某道菜", "某个时间段")


def _suggested(body: str):
    """从**实际发出去的那句话里**解析建议了哪几样。

    🔴 判据订正（形态 A，在我自己的仪器上，今天第 N 次）：
       第一版拿硬编码的三样当「这次建议了什么」，于是修好之后
       **产品已经只建议还没限定的那几样了**，探针照样报 🔴 3/3。
       ▎我量的是「那句话**原本**让他限定什么」，
       ▎我想知道的是「**这次**发出去的那句里列了什么」。
    """
    import re as _re
    m = _re.search(r"限定到([^）)]*)", body or "")
    if not m:
        return []
    seg = m.group(1)
    return [x for x in _GF_ALL if x in seg]

_GF_QUERIES = [
    # 今天实测撞上过的两条
    "最近30天水煮牛肉丝卖得怎么样",
    "最近30天哪个时段毛利最高",
    # 铺开一点：问原因 / 问建议 / 问对比
    "最近30天营收为什么下滑",
    "模拟·徐汇美罗城店最近30天为什么毛利低",
    "最近30天哪道菜卖一份亏一份",
    "本月生意不太好有什么办法提升",
    # 阴性对照：正常出数
    "最近30天各门店营收是多少",
    "最近30天水煮牛肉卖得怎么样",
]


def _clear():
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, n)()


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s  RUN=%s  轮数=%d" % (ctx.db_name, _GF_RUN, _GF_ROUNDS))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2

    rows = []
    for rnd in range(1, _GF_ROUNDS + 1):
        for q in _GF_QUERIES:
            _clear()
            set_factory_id(_GF_FID)
            skey = "gf-%s-r%d-%s" % (_GF_RUN, rnd, q)
            async with rr.dish_catalogue_scope(pool, _GF_FID):
                spec = await ri.parse_restaurant_query(
                    q, pool, factory_id=_GF_FID, session_key=skey,
                    semantic_first=True)
                res = None
                if spec is not None and svc.should_delegate(spec, None, query=q):
                    res = await svc.tiered_answer(
                        q, pool, _GF_FID, ctx.role,
                        precomputed_spec=spec, session_key=skey)
            res = res or {}
            body = (res.get("answer_text") or "")
            hit = _GF_MARK in body
            # 老板**已经限定了什么** —— 全部取自 spec，⛔ 不猜
            dish = getattr(spec, "dish_slot", None) if spec else None
            stores = tuple(getattr(spec, "store_slots", ()) or ()) if spec else ()
            dr = getattr(spec, "date_range", None) if spec else None
            win = getattr(spec, "window_label", None) if spec else None
            has_time = bool(dr and dr[0]) or (win not in (None, "", "全部历史"))
            already = []
            if stores:
                already.append("某家店")
            if dish:
                already.append("某道菜")
            if has_time:
                already.append("某个时间段")
            # ⚠️ 正文本身要存进去 —— 判据要看**实际发出去的那句话**，
            #    ⛔ 不是我在脚本顶部硬编码的那三样。
            rows.append((rnd, q, res.get("kind"), hit, dish, stores, win,
                         already, len(body), body))

            print("\n" + "=" * 78)
            print("r%d 【%s】" % (rnd, q))
            print("   kind=%s  撞上那句兜底=%s  正文 %d 字"
                  % (res.get("kind"), hit, len(body)))
            print("   老板已经限定: dish=%r  stores=%s  window=%r  ⇒ %s"
                  % (dish, list(stores), win, already or "（什么都没限定）"))
            if hit:
                sug = _suggested(body)
                overlap = [s for s in sug if s in already]
                print("   ▎这次**实际建议**他限定 %s；他**已经**限定了 %s"
                      % (sug or "（没给建议）", already))
                print("   ▎重叠 = %s  ⇒ %s"
                      % (overlap,
                         "🔴 建议里有他已经做过的" if overlap else "✅ 无重叠"))
            print("   ── 正文（前 200 字）──")
            print("   " + (body[:200] or "(空)").replace("\n", "\n   "))

    hits = [r for r in rows if r[3]]
    ok = [r for r in rows if r[2] == "answer" and not r[3]]
    print("\n" + "=" * 78)
    print("阴性对照·正常出数（answer 且不含那句）= %d 条" % len(ok))
    print("阳性对照·撞上那句兜底 = %d/%d 条" % (len(hits), len(rows)))
    if not hits:
        print("⛔ rc=2 一条都没撞上 —— 这一格没被触到，本次读数没有意义")
        return 2
    if not ok:
        print("⛔ rc=2 一条都没正常出数 —— 整条链可能坏了，读数作废")
        return 2

    # 🔑 判据：**这次实际建议的** ∩ **他已经限定的**。非空 = 建议他做已做过的事。
    bad = []
    for r in hits:
        sug = _suggested(r[9])
        ov = [s for s in sug if s in r[7]]
        if ov:
            bad.append((r, sug, ov))
    print("\n🔑 撞上那句兜底的 %d 条里" % len(hits))
    print("  🔴 建议里混着他已经做过的 = %d" % len(bad))
    print("  ✅ 建议与他已做的零重叠   = %d" % (len(hits) - len(bad)))
    for r in hits:
        sug = _suggested(r[9])
        ov = [s for s in sug if s in r[7]]
        print("     [%s] 实际建议=%s  已限定=%s  ← %s"
              % ("🔴" if ov else "✅", sug or "（没给建议）", r[7] or "-", r[1]))
    print("\n⚠️ 口径：评测题库、%d 条问句 × %d 轮，⛔ 不是真实流量。"
          % (len(_GF_QUERIES), _GF_ROUNDS))
    print("⛔ 本探针不下修法结论 —— 上面是事实。")
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
