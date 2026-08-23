"""繁体/词表三刀的 A/B 探针 —— prod 真数据 + 真 LLM，读**老板会看到的原文**。

⛔ 不部署、⛔ 不覆盖线上文件。改动过的 `restaurant_ops_router.py` 从
`LEX_OVERLAY` 指的旁路目录用 `importlib` 先行注册进 `sys.modules`，
所以本探针量的是「这份改动在 prod 上是什么行为」，而线上那个进程不受影响。

## 两臂必须对称（⛔ 不能一臂走旁路、一臂走部署树）

base 臂和 new 臂都经过同一段 `_preload`，只是 `LEX_OVERLAY` 指向不同目录。
否则两臂差的就不只是那个文件，还差一条 import 路径。

## 读数（每问一句都打全，⛔ 不只打结论）

  · kind              answer / clarification —— **拒答也是一种「不一样的答案」**
  · planner_authority `llm_unavailable` ⇒ 本轮读数作废(rc=2)，⛔ 不是「产品答不了」
  · source_tier       llm / plan_cache —— 来源标记，⛔ 不带它分不清
                      「两侧相同」是真相同还是同一条缓存条目
  · 正文全文 sha1 + 字数 + 前 240 字   —— 阳性对照要判「逐字不变」，只能靠 sha1

## 纪律

  · 每个 case × 每个 arm × 每一轮 `session_key` 独立（共用会串台）
  · 每次调用前清 4 个缓存并**贴出清了哪几个**
  · 承重结论 3 轮，其余 >=2 轮
"""
from __future__ import annotations

import asyncio
import hashlib
import importlib.util
import json
import os
import sys
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

ctx = bootstrap_probe(os.environ.get("PROBE_FID", "MOCK_REST"))

OVERLAY = os.environ.get("LEX_OVERLAY", "")
ARM = os.environ.get("LEX_ARM", "?")
ROUNDS = int(os.environ.get("LEX_ROUNDS", "3"))
ONLY = os.environ.get("LEX_ONLY", "")


def _preload(dotted: str, filename: str) -> bool:
    path = os.path.join(OVERLAY, filename)
    if not os.path.exists(path):
        print("🔴 旁路文件不存在: %s" % path)
        return False
    spec = importlib.util.spec_from_file_location(dotted, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[dotted] = module
    spec.loader.exec_module(module)
    return True


_OVERLAY_OK = _preload(
    "smartbi.gold.restaurant.restaurant_ops_router", "restaurant_ops_router.py")


#: (组, 标签, 问句, 期望)
#: 期望只是**读的时候拿来对照**，⛔ 探针不据此改行为。
CASES = [
    # ── 刀1 繁体：每一对的简体那条是它自己的基准 ────────────────────────
    ("刀1", "繁-基准-整体", "最近30天整体的毛利率是多少", "answer"),
    ("刀1", "繁-整體",      "最近30天整體的毛利率是多少", "= 上一条"),
    ("刀1", "繁-基准-门店", "最近30天门店的营收是多少", "answer"),
    ("刀1", "繁-門店",      "最近30天門店的營收是多少", "= 上一条"),
    ("刀1", "繁-基准-单品", "最近30天单品的销量排名", "answer"),
    ("刀1", "繁-單品",      "最近30天單品的銷量排名", "= 上一条"),
    # ── 阳性对照：词表在**救场**的三条，修完必须逐字不变 ────────────────
    ("阳性", "救场-罗氏虾", "罗氏虾的采购成本", "逐字不变"),
    ("阳性", "救场-客单",   "客单的毛利率", "逐字不变"),
    ("阳性", "救场-入库虾", "入库虾的营收", "逐字不变"),
    # ── 刀3 承重两条：① 必须不再拒答 ② 必须逐字不变 ────────────────────
    ("刀3", "承重①-通名", "最近30天各门店菜品毛利分别是多少", "不再拒答"),
    ("刀3", "承重②-点名", "香辣蟹煲卖得怎么样", "逐字不变(定向拒答)"),
    ("刀3", "承重①b-单品", "最近30天各门店单品毛利分别是多少", "不再拒答"),
    ("刀3", "阴性-真菜", "最近30天各门店水煮牛肉毛利分别是多少", "逐字不变(应答得出)"),
    # ── 刀2 靶子：名字里带裸单字「菜」的**真菜**，问一句带排名词的话。
    #    确定性层实测 `dish_ranking_direction` 对它们返回 best(2/10 真菜名)，
    #    ⚠️ 但那只是 spec 的一个字段 —— 要不要修，看**老板看到的**变没变。
    ("刀2", "裸菜字-干锅花菜", "干锅花菜卖得最好吗", "量现状"),
    ("刀2", "裸菜字-娃娃菜", "娃娃菜卖得最好吗", "量现状"),
    ("刀2", "对照-无菜字真菜", "水煮牛肉卖得最好吗", "量现状(对照)"),
]


def _clear(ri):
    """⛔ 用模块自己的 helper，⛔ 不拼属性名。清了哪几个**贴出来**。"""
    cleared = []
    for name in ("clear_semantic_plan_cache", "clear_route_cache",
                 "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        fn = getattr(ri, name)          # ⛔ 不给默认值 —— 名字错了就该当场炸
        fn()
        cleared.append(name)
    return cleared


async def main() -> int:
    if not _OVERLAY_OK:
        print("🔴 旁路加载失败 —— 读数作废 (rc=2)")
        return 2
    pool = await ctx.pool()
    fid = ctx.factory_id
    if ctx.llm_dead_slots:
        print("🔴 LLM 槽死了: %s —— 每条都会是 fail-closed 拒答，读数作废 (rc=2)"
              % (ctx.llm_dead_slots,))
        return 2

    from smartbi.gold.restaurant import restaurant_ops_router as R
    from smartbi.gold.restaurant import restaurant_intent as ri
    from smartbi.gold.restaurant import restaurant_intent_service as svc
    from smartbi.tenant_ctx import set_factory_id

    # ── 代码自检：证明跑的是这一臂的那份文件，⛔ 不假设 scp 送对了 ───────
    print("ARM=%s  OVERLAY=%s" % (ARM, OVERLAY))
    print("router 来自 %s" % getattr(R, "__file__", "?"))
    print("router md5 = %s" % hashlib.md5(
        open(os.path.join(OVERLAY, "restaurant_ops_router.py"),
             "rb").read()).hexdigest())
    print("标记: len(_DISH_GENERIC_TOKENS)=%d  '整體'在表=%s  '菜品'在表=%s  "
          "'香辣蟹煲'在表=%s" % (
              len(R._DISH_GENERIC_TOKENS),
              "整體" in R._DISH_GENERIC_TOKENS,
              "菜品" in R._DISH_GENERIC_TOKENS,
              "香辣蟹煲" in R._DISH_GENERIC_TOKENS))
    print("标记: restaurant_intent 拿到的 _KITCHEN_OPS_NOUNS 长度=%d"
          % len(ri._KITCHEN_OPS_NOUNS))
    print("标记: dish_slot_after_catalogue_check 有没有通名支路 = %s"
          % hasattr(R, "_GENERIC_DISH_SLOT_DISCLOSURE"))

    # ── 阳性对照：菜单目录**在这台机器上真的加载得出来** ────────────────
    #    ⛔ 目录为空时 `_catalogue_says_not_a_dish` 整个 fail-open，
    #       那时刀3 的两条承重断言测的是另一件事，读数必须作废。
    set_factory_id(fid)
    async with R.dish_catalogue_scope(pool, fid):
        cat = R.current_dish_catalogue()
    print("阳性对照·菜单目录 = %d 条" % len(cat or ()))
    if not cat:
        print("🔴 目录 0 条 —— 目录闸 fail-open，读数作废 (rc=2)")
        return 2

    rc = 0
    stamp = "%d" % int(time.time())
    for group, label, query, expect in CASES:
        if ONLY and ONLY not in group:
            continue
        for rnd in range(1, ROUNDS + 1):
            cleared = _clear(ri)
            # ⚠️ session_key: case × arm × round 三者都进 key，⛔ 不共用
            skey = "lexab-%s-%s-%s-r%d" % (stamp, ARM, label, rnd)
            t0 = time.perf_counter()
            try:
                # ⚠️ 必须在 `dish_catalogue_scope` 里规划 —— 作用域外规划会让
                #    「菜单说了算」那道闸 fail-open，刀3 的读数会全是假的。
                set_factory_id(fid)
                async with R.dish_catalogue_scope(pool, fid):
                    spec = await ri.parse_restaurant_query(
                        query, pool, factory_id=fid, session_key=skey,
                        semantic_first=True)
            except Exception as exc:  # noqa: BLE001
                print("RESULT\t%s" % json.dumps({
                    "arm": ARM, "group": group, "label": label, "round": rnd,
                    "query": query, "error": repr(exc)}, ensure_ascii=False))
                rc = max(rc, 1)
                continue
            authority = getattr(spec, "planner_authority", None)
            if authority == "llm_unavailable":
                print("🔴 planner_authority=llm_unavailable —— 本轮读数作废 (rc=2)")
                return 2
            try:
                result = await svc.tiered_answer(
                    query, pool, fid, ctx.role,
                    session_key=skey, history=[], precomputed_spec=spec)
            except Exception as exc:  # noqa: BLE001
                print("RESULT\t%s" % json.dumps({
                    "arm": ARM, "group": group, "label": label, "round": rnd,
                    "query": query, "error": repr(exc)}, ensure_ascii=False))
                rc = max(rc, 1)
                continue
            ms = (time.perf_counter() - t0) * 1000
            text = (result or {}).get("answer_text") or ""
            row = {
                "arm": ARM, "group": group, "label": label, "round": rnd,
                "query": query, "expect": expect,
                "kind": (result or {}).get("kind"),
                "intent": getattr(spec, "intent", None),
                "source_tier": getattr(spec, "source_tier", None),
                "authority": authority,
                "dish_slot": getattr(spec, "dish_slot", None),
                "store_slot": getattr(spec, "store_slot", None),
                "store_scope": getattr(spec, "store_scope", None),
                "clarification_needed": getattr(spec, "clarification_needed", None),
                "chars": len(text),
                "sha1": hashlib.sha1(text.encode("utf-8")).hexdigest()[:12],
                "ms": round(ms),
                "cleared": cleared,
                "head": text.replace("\n", " ")[:240],
                # ⚠️ 披露挂在**正文末尾** —— 只记 head 的话，「披露丢没丢」
                #    这件事在读数里根本不存在（形态 A⁗：我看到的是全部吗）。
                #    第一次就是这么栽的：grep 整个日志找披露句子，读出 0，
                #    而日志里压根只有前 240 字。
                "tail": text.replace("\n", " ")[-200:],
                "has_disclosure": "（说明：" in text,
            }
            print("RESULT\t%s" % json.dumps(row, ensure_ascii=False))
            sys.stdout.flush()
    return rc


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
