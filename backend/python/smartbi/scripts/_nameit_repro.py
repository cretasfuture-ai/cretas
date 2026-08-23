# -*- coding: utf-8 -*-
"""S2 复现 —— 老板问「哪一个」，产品给一张表（而且可能加粗了**相反**的那个）。

⛔ 本探针只读，不改产品行为。它要回答的只有一句：

    ▎问「拖后腿的那家店是哪家」时，产品加粗的是第 1 名还是最后 1 名？

## 为什么必须自己跑一遍

上一条并行线的裁定是转述来的。转述里最贵的一格是
「加粗 #1 陆家嘴正大店（最好的那家）」—— 如果它是对的，这是当前唯一一条
**答案指向相反对象**的缺陷；如果复现不出来，就该报 rc=2 而不是折叠进「没问题」。

## 读数带来源标记（形态 A¹³）

每条读数都记 `planner_authority` / `ranking_direction` / `intent` / `code`，
⛔ 不只记 kind —— 「答不上」和「探针没 key」在 kind 上长得一模一样。

## 缓存

每轮清 4 个（贴出清了哪几个）+ **绕过叙述缓存**。
⚠️ `clear_promotion_cache` 不存在，别写。
"""
from __future__ import annotations

import asyncio
import json
import re

from smartbi.scripts._probe_bootstrap import bootstrap_probe

FID = "MOCK_REST"
ROUNDS = 2
ctx = bootstrap_probe(FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: 🔴 S2 主问句 + 上一线列的另外三条 + 一条**不问「哪一个」**的阳性对照。
QUESTIONS = [
    ("S2", "拖后腿的那家店是哪家"),
    ("D2", "毛利最高的那道菜是哪道"),
    ("D3", "卖得最好的那道菜叫什么"),
    ("OK", "怎么让最差的那家店好起来"),
    #: 🔴 方向**已知**的两头 —— ⛔ 少了它们，「方向未知不加粗」那条读数是恒真式
    #:    （一个「永远不加粗」的实现同样让 S2 变绿）。
    ("WORST", "哪家店毛利最低"),
    ("BEST", "哪家店毛利最高"),
    #: 阳性对照：不问「哪一个」的普通问句，修完必须**逐字不变**。
    ("CTL", "这个月营业额多少"),
]

_CACHES = ("clear_semantic_plan_cache", "clear_route_cache",
           "clear_tenant_gate_cache", "clear_promoted_routes_cache")


def _clear():
    for n in _CACHES:
        getattr(ri, n)()
    return _CACHES


def _bypass_narrative_cache() -> list:
    hit = []
    try:
        import smartbi.agent.narrative_cache as nc

        async def _miss(self, *a, **kw):
            return None

        for name in ("get", "get_semantic"):
            if hasattr(nc.NarrativeCacheService, name):
                setattr(nc.NarrativeCacheService, name, _miss)
                hit.append("NarrativeCacheService." + name)
    except Exception as exc:  # noqa: BLE001
        hit.append("⚠️拿不到 narrative_cache: %s" % str(exc)[:60])
    return hit


_BOLD = re.compile(r"\*\*(.+?)\*\*")
#: markdown 表格行 —— 取第一格（通常是排名或名字）
_TROW = re.compile(r"^\s*\|(.+)\|\s*$")


def _table_rows(text: str):
    """把正文里的 markdown 表格切成行（第 1、2 格）。⛔ 不猜，逐行读。"""
    out = []
    for ln in (text or "").splitlines():
        m = _TROW.match(ln)
        if not m:
            continue
        cells = [c.strip() for c in m.group(1).split("|")]
        if not cells or set("".join(cells)) <= set("-: "):
            continue
        out.append(cells)
    return out


async def _ask(pool, q, key):
    _clear()
    set_factory_id(FID)
    async with rr.dish_catalogue_scope(pool, FID):
        spec = await ri.parse_restaurant_query(
            q, pool, factory_id=FID, session_key=key, semantic_first=True)
    res = {}
    if spec is not None and svc.should_delegate(spec, None, query=q):
        res = await svc.tiered_answer(q, pool, FID, ctx.role,
                                      precomputed_spec=spec,
                                      session_key=key) or {}
    return spec, res


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s ROUNDS=%d" % (ctx.db_name, FID, ROUNDS))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2
    print("每轮清: %s" % "、".join(_CACHES))
    print("绕过叙述缓存: %s" % (_bypass_narrative_cache() or "⚠️一个都没绕过"))

    set_factory_id(FID)
    async with rr.dish_catalogue_scope(pool, FID):
        cat = rr.current_dish_catalogue()
    print("菜单目录 = %d 条\n" % len(cat or ()))
    if not cat:
        print("⛔ rc=2 库没连上")
        return 2

    unavailable = 0
    for tag, q in QUESTIONS:
        for rnd in range(1, ROUNDS + 1):
            spec, res = await _ask(pool, q, "nameit-%s-r%d" % (tag, rnd))
            text = res.get("answer_text") or ""
            authority = getattr(spec, "planner_authority", None)
            if authority == "llm_unavailable":
                unavailable += 1
            print("=" * 96)
            print("[%s r%d] 《%s》" % (tag, rnd, q))
            print("  kind=%s  code=%s  n=%d" % (
                res.get("kind"), res.get("code"), len(text)))
            print("  authority=%s  intent=%s  ranking_direction=%r  metrics=%s"
                  % (authority, getattr(spec, "intent", None),
                     getattr(spec, "ranking_direction", None),
                     tuple(getattr(spec, "metrics", ()) or ())))
            bolds = _BOLD.findall(text)
            print("  🔴 加粗片段 %d 个: %s" % (len(bolds), json.dumps(
                bolds[:8], ensure_ascii=False)))
            rows = _table_rows(text)
            print("  表格行 %d 条:" % len(rows))
            for r in rows:
                print("     " + " | ".join(r[:4]))
            print("  --- 正文原文 ---")
            for ln in text.splitlines():
                print("  > " + ln)
            print()

    if unavailable:
        print("⛔ rc=2 有 %d 条 spec 是 llm_unavailable —— 读数作废" % unavailable)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
