# -*- coding: utf-8 -*-
"""通名不再被当成老板点名的菜 —— 单臂直测（部署后验收）。

## 为什么不用 A/B 探针

`_lex_ab_probe` 要一个「旁路基线副本」，那份副本随 agent 的临时树被删了
⇒ 它正确地报 `rc=2`（没量到），⛔ 不是产品坏了。
▎**仪器缺件和产品缺陷长得不一样，⛔ 别把前者读成后者。**

⇒ 这支只跑**当前部署树**一臂，拿 agent 记录的 base 读数当对照（写在下面）。

## 四条承重（agent 的 base 读数，3/3 稳定）

    ①「最近30天各门店菜品毛利分别是多少」  base: 拒答 73 字「没有找到「菜品」的门店销售记录」
    ②「最近30天各门店单品毛利分别是多少」  base: 同上
    ③「香辣蟹煲卖得怎么样」               base: 151 字定向拒答 —— **必须逐字不变**
    ④「最近30天各门店水煮牛肉毛利分别是多少」base: 787 字 —— **必须逐字不变**

▎③④ 是这一刀的**承重阴性对照**：少了它们，这一刀就退化成
▎「拿全菜榜顶替他点名的菜」，那正是原设计要防的。

## 阴性对照

`spec.planner_authority == 'llm_unavailable'` 必须为 0（⛔ 顶层 res 没有这个键），
非 0 ⇒ rc=2 作废。
"""
from __future__ import annotations

import asyncio
import hashlib
import os
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_GV_FID = "MOCK_REST"
_GV_ROUNDS = 3
_GV_RUN = "%d-%d" % (os.getpid(), int(time.time()))
ctx = bootstrap_probe(_GV_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

_GV_CASES = [
    ("承重①通名菜品", "最近30天各门店菜品毛利分别是多少", "应当不再拒答"),
    ("承重②通名单品", "最近30天各门店单品毛利分别是多少", "应当不再拒答"),
    ("承重③点名假菜", "香辣蟹煲卖得怎么样", "必须仍是定向拒答"),
    ("承重④点名真菜", "最近30天各门店水煮牛肉毛利分别是多少", "必须逐字不变"),
]


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
          % (ctx.db_name, _GV_FID, _GV_ROUNDS, _GV_RUN))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2

    rows = []
    n_unavail = 0
    for rnd in range(1, _GV_ROUNDS + 1):
        for tag, q, expect in _GV_CASES:
            cleared = _clear()
            set_factory_id(_GV_FID)
            skey = "gv-%s-r%d-%s" % (_GV_RUN, rnd, q)
            async with rr.dish_catalogue_scope(pool, _GV_FID):
                spec = await ri.parse_restaurant_query(
                    q, pool, factory_id=_GV_FID, session_key=skey,
                    semantic_first=True)
                res = None
                if spec is not None and svc.should_delegate(spec, None, query=q):
                    res = await svc.tiered_answer(
                        q, pool, _GV_FID, ctx.role,
                        precomputed_spec=spec, session_key=skey, history=[])
            res = res or {}
            auth = getattr(spec, "planner_authority", None) if spec else None
            if auth == "llm_unavailable":
                n_unavail += 1
            text = res.get("answer_text") or ""
            md5 = hashlib.md5(text.encode("utf-8")).hexdigest()[:12]
            rows.append((rnd, tag, q, res.get("kind"), len(text), md5, expect))
            print("\n" + "=" * 74)
            print("r%d 【%s】%s" % (rnd, tag, q))
            print("   清了: %s" % "、".join(cleared))
            print("   kind=%s  正文 %d 字  md5=%s   期望: %s"
                  % (res.get("kind"), len(text), md5, expect))
            print("   ── 前 3 行 ──")
            for ln in text.split("\n")[:3]:
                print("   | " + ln[:100])

    print("\n" + "=" * 74)
    print("阴性对照·llm_unavailable = %d 次" % n_unavail)
    if n_unavail:
        print("⛔ rc=2 有 fail-closed 拒答混在里面，读数作废")
        return 2

    print("\n🔑 逐轮（⛔ 不折叠）")
    for r in rows:
        print("   r%d %-14s kind=%-14s %5d 字  md5=%s" % (r[0], r[1], r[3], r[4], r[5]))

    # 承重①② 不再拒答；③④ 三轮内部逐字一致（跨部署的逐字比对靠上面的 md5 人读）
    bad = []
    for tag in ("承重①通名菜品", "承重②通名单品"):
        grp = [r for r in rows if r[1] == tag]
        if any(r[3] != "answer" for r in grp):
            bad.append("%s 仍在拒答" % tag)
    for tag in ("承重③点名假菜", "承重④点名真菜"):
        grp = [r for r in rows if r[1] == tag]
        if len({r[5] for r in grp}) != 1:
            bad.append("%s 三轮 md5 不一致（抖动，无法判逐字不变）" % tag)
    print("\n🔑 判定")
    if bad:
        for b in bad:
            print("   🔴 %s" % b)
    else:
        print("   ✅ ①② 不再拒答；③④ 三轮内部逐字稳定")
    print("\n⚠️ 口径：MOCK_REST / %d 轮 / **评测题库**，⛔ 不是真实流量。" % _GV_ROUNDS)
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
