# -*- coding: utf-8 -*-
"""词表否决四刀 —— 老板真正看到的原文，A/B 用**同一个仪器**。

跑法（prod 部署树的副本，只读数据）::

    /tmp/runprobe.sh smartbi.scripts._veto_fix_probe /tmp/veto_A.log
    ROUNDS=3 CASES=D,T,I,X /tmp/runprobe.sh ...

## 判据纪律（每条都被本仓踩出来过）

- **每条读数带来源标记**（`authority` / `tier` / `dims` / `clar`）——
  否则分不清「产品答不上」和「探针自己没 key」。
- `llm_unavailable` 读 `spec.planner_authority`，非 0 ⇒ rc=2 **作废**，
  ⛔ 不是「产品答不上来」。
- **每个 case × 每个 arm × 每轮独立 `session_key`** —— 共用会串台：
  输入 A 的正文里印着 B 的实体名，而其它读数看起来完全正常。
- 每轮**清缓存并贴出清了哪几个**；`clear_promotion_cache` **不存在**，
  拼错的属性名不会报错，只会让清理静默失效 ⇒ 用 `getattr` 直取、⛔ 不给默认值。
- 三态 rc：0 正常 / 1 有读数且指向缺陷 / **2 这次没量到东西**（仪器死）。
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))

from smartbi.scripts._probe_bootstrap import (  # noqa: E402
    assert_not_an_llm_artifact,
    bootstrap_probe,
)

ctx = bootstrap_probe("MOCK_REST")

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.gold.restaurant.restaurant_intent_service import (  # noqa: E402
    tiered_answer,
)
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

FID = "MOCK_REST"
ROUNDS = int(os.environ.get("ROUNDS", "1"))

# ── 来源标记：LLM 给的维度 vs 编译出来的维度 ────────────────────────────────
#
# 🔴 少了这一格，「两侧没差异」有**两种**完全不同的读法：
#      ① 词表否决没开火（LLM 本来就没给那个维度）
#      ② 开火了但用户看不出来
#    汇总里它们长得一模一样。⇒ 包一层 `_build_spec`，把**入参**的
#    `llm_dimensions` 和**出参**的 `spec.dimensions` 一起记下来。
#    ⛔ 不改生产代码 —— 只在探针进程里包。
_TRACE: list = []
_ORIG_BUILD_SPEC = ri._build_spec


def _traced_build_spec(*args, **kwargs):
    spec = _ORIG_BUILD_SPEC(*args, **kwargs)
    _TRACE.append({
        "llm_dims": (list(kwargs.get("llm_dimensions"))
                     if kwargs.get("llm_dimensions") is not None else None),
        "out_dims": list(getattr(spec, "dimensions", ()) or ()),
        "llm_margin": bool(kwargs.get("llm_wants_margin")),
        "out_margin": bool(getattr(spec, "wants_margin", False)),
        "llm_metrics": list(kwargs.get("llm_requested_metrics") or ()),
        "out_metrics": list(getattr(spec, "requested_metrics", ()) or ()),
    })
    return spec


ri._build_spec = _traced_build_spec

#: 模型**原始**输出。⛔ `_build_spec` 那一层看不到它 —— 门店名录那一刀
#: 在调 `_build_spec` **之前**就把 `requested_metrics` / `wants_margin` /
#: `asks_profitability` 清掉了，所以在 `_build_spec` 里量它恒为「没变化」。
#: ▎形态 A：我量的这个数，不是我真正想知道的那个数。
_PARSED: list = []
_ORIG_T3 = ri._semantic_spec_from_t3


def _traced_t3(parsed, query, **kwargs):
    _PARSED.append({"query": query, "parsed": dict(parsed or {})})
    return _ORIG_T3(parsed, query, **kwargs)


ri._semantic_spec_from_t3 = _traced_t3

# ── `SHORT_BARE_CAI=1`: 短路 `_detect_dimensions` 里的**裸单字「菜」**─────────
#
# 只用来**回答一个问题**: 那个裸单字到底有没有改变老板看到的东西?
# ⛔ 它不是修复方案, 也⛔ 不是交付判据 —— 交付要看真改完之后的 A/B。
# 📏 SQL 实测: 单字「菜」命中 5/32 个真实菜名(娃娃菜/干锅花菜/菜籽油/香菜),
#    也命中「买菜」「点菜」「韭菜园店」这些**不是菜品**的说法。
if os.environ.get("SHORT_BARE_CAI") == "1":
    _ORIG_DETECT_DIMS = ri._detect_dimensions

    def _detect_dims_without_bare_cai(text: str):
        dims = list(_ORIG_DETECT_DIMS(text))
        if ("dish" in dims
                and not any(tok in (text or "") for tok in ri._DISH_TOKENS)):
            dims.remove("dish")          # 只有裸单字撑着的那一票 -> 拿掉
        return tuple(dims)

    ri._detect_dimensions = _detect_dims_without_bare_cai
    print("[短路] 裸单字「菜」已短路（⛔ 仅用于测量，不是修复）", file=sys.stderr)
#: 只跑某几组（首字母），例：`CASES=D,T`。空 = 全跑。
_WANT = tuple(c.strip() for c in (os.environ.get("CASES") or "").split(",") if c.strip())

#: 每条 = (标签, 问句, 上一轮问句或 None, 这条想量什么)
_CASES = [
    # ── ③ 强制门店名录（`_is_explicit_store_directory_query`）────────────
    ("D1-亏钱追问", "哪几家店", "有没有店是亏钱的",
     "头条伤害：上一轮问亏损，这一轮「哪几家店」被正则短路成花名册"),
    ("D2-亏钱追问b", "哪几家店", "有没有店在亏钱",
     "同族第二问法"),
    ("D3-名录-几家", "我们一共有几家门店", None,
     "立身之本①：纯名录问句，改完必须仍是名录"),
    ("D4-名录-哪些", "有哪些门店", None,
     "立身之本②"),
    ("D5-名录-名单", "门店名单", None,
     "立身之本③"),
    ("D6-名录-几家店", "现在有几家店", None,
     "立身之本④"),

    # ── ② 剥 time 维度（`_detect_comparison`）─────────────────────────────
    ("T1-救", "昨天和前天营收差多少", None,
     "唯一证明有用的那次（3/3 救）—— 改完必须仍被救"),
    ("T2-毁-每天", "本月和上个月每天的营收趋势怎么样", None,
     "用户自己说了粒度词「每天/趋势」"),
    ("T3-毁-走势", "这个月和上个月的营收走势对比", None,
     "用户自己说了粒度词「走势」"),
    ("T4-毁-按周", "本月和上个月按周看营收", None,
     "用户自己说了粒度词「按周」"),
    ("T5-阳性", "本月和上个月营收差多少", None,
     "阳性对照：有比较、无粒度词 ⇒ 改完必须逐字不变"),

    # ── ① 剥 ingredient（6 个中文词）─────────────────────────────────────
    # ⚠️ 菜名要被 `extract_dish_candidate` 认出来才有 `deterministic_dish`，
    #    而它是**句式**匹配（「X的成本是多少」中），⛔ 不是「句子里出现了菜名」。
    #    我第一版写的「水煮牛肉的成本都花在哪了」句式不匹配 ⇒ 这一刀根本不开火，
    #    22 条读数里一条都没量到它。
    ("I1-按食材", "按食材拆一下这个月的成本", None, "含「食材」⇒ 不开火"),
    ("I2-费钱", "哪些原料最费钱", None, "含「原料」⇒ 不开火"),
    ("I3-开火", "水煮牛肉的成本是多少", None,
     "有 deterministic_dish、无 6 词 ⇒ **该开火**"),
    ("I4-同计划不开火", "水煮牛肉的食材成本是多少", None,
     "🔴 与 I3 配对：只多了「食材」二字 ⇒ 逃生口 ⇒ 不开火。"
     "两条的 LLM 计划相同而结局不同 = 那个不一致本身"),
    ("I5-开火2", "米饭的成本为什么这么高", None, "有 deterministic_dish、无 6 词"),
    ("I6-开火3", "藤椒鸡的成本是多少", None, "有 deterministic_dish、无 6 词"),

    # ── ⑥ 裸单字「菜」当维度判据（`_detect_dimensions`）──────────────────
    ("X1-韭菜园店", "韭菜园店最近30天营收多少", None,
     "店名里含「菜」⇒ 被判成菜品维度"),
    ("X2-阴性王府井", "王府井店最近30天营收多少", None,
     "阴性对照：同样不存在的店名、只是不含「菜」"),
    ("X3-买菜", "这个月买菜花了多少钱", None,
     "「买菜」的菜不是菜品 ⇒ 裸单字给采购问句硬塞 dish 维度"),
    ("X4-点菜", "点菜高峰在几点", None,
     "「点菜」的菜不是菜品；⚠️「点」在 store 那半边是硬编码巧合"),
    ("X5-阴性采购", "这个月采购花了多少钱", None,
     "阴性对照：同一件事、只是不含「菜」"),
]

_CACHE_HELPERS = (
    "clear_semantic_plan_cache",
    "clear_route_cache",
    "clear_tenant_gate_cache",
    "clear_promoted_routes_cache",
)


def clear_caches(verbose: bool = False) -> list:
    cleared = []
    for name in _CACHE_HELPERS:
        getattr(ri, name)()          # AttributeError 当场炸，⛔ 不给默认值
        cleared.append(name)
    if verbose:
        print("清了缓存: %s" % "、".join(cleared))
    return cleared


async def run_turn(pool, query: str, session_key: str, history=None) -> dict:
    set_factory_id(FID)
    _TRACE.clear()
    _PARSED.clear()
    async with rr.dish_catalogue_scope(pool, FID):
        spec = await ri.parse_restaurant_query(
            query, pool, factory_id=FID, session_key=session_key,
            history=history, semantic_first=True,
        )
    # 🔴 最后一次 `_build_spec` 调用才是产出这个 spec 的那一次
    #    （contract-repair 会用修好的 code 再编译一遍）。
    last = _TRACE[-1] if _TRACE else {}
    row = {
        "q": query,
        "n_build_spec": len(_TRACE),
        "llm_dims": last.get("llm_dims"),
        "llm_margin": last.get("llm_margin"),
        "llm_metrics": last.get("llm_metrics"),
        # 词表把 LLM 给的东西删掉了没有 —— 这一格就是「否决开没开火」
        "veto_dims": (sorted(set(last.get("llm_dims") or ())
                             - set(last.get("out_dims") or ()))
                      if last.get("llm_dims") is not None else None),
        "veto_metrics": sorted(set(last.get("llm_metrics") or ())
                               - set(last.get("out_metrics") or ())),
        "veto_margin": bool(last.get("llm_margin")) and not bool(last.get("out_margin")),
        "dir_regex": bool(ri._is_explicit_store_directory_query(query)),
        # 模型原始输出（最后一次 T3 编译的入参）—— 用来判「模型给的对不对」
        "parsed": (_PARSED[-1]["parsed"] if _PARSED else None),
    }
    if spec is None:
        return row | {"kind": "spec-None", "authority": None, "text": ""}
    row |= {
        "authority": spec.planner_authority,
        "tier": spec.source_tier,
        "intent": (spec.intent or "").replace("RESTAURANT_OPS_", "") or None,
        "dims": list(spec.dimensions),
        "metrics": list(spec.requested_metrics or ()),
        "wants_margin": bool(getattr(spec, "wants_margin", False)),
        "asks_profit": bool(getattr(spec, "asks_profitability", False)),
        "store_scope": getattr(spec, "store_scope", None),
        "clar": bool(spec.clarification_needed),
        "_spec": spec,
    }
    result = await tiered_answer(
        query, pool, FID, ctx.role, precomputed_spec=spec,
        session_key=session_key, history=history,
    )
    if not result:
        return row | {"kind": "None", "text": ""}
    text = result.get("answer_text") or ""
    return row | {
        "kind": result.get("kind") or "-",
        "code": (result.get("code") or "").replace("RESTAURANT_OPS_", ""),
        "contract": result.get("contract_pass"),
        "text": text,
        "md5": hashlib.md5(text.encode("utf-8")).hexdigest()[:10],
        "n_charts": len(result.get("charts") or []),
        "n_kpis": len(result.get("kpis") or []),
    }


async def main() -> int:
    arm = os.environ.get("ARM", "?")
    pool = await ctx.pool()
    if getattr(ctx, "llm_dead_slots", None):
        print("rc=2 LLM 死槽: %s —— 每条答案都会是 fail-closed 拒答"
              % (ctx.llm_dead_slots,))
        return 2

    # 阳性对照：这个租户真的有门店、有 POS 数据。⛔ 少了它，一片空读数
    # 和「产品答不上来」长得一模一样。
    set_factory_id(FID)
    async with pool.acquire() as conn:
        n_store = await conn.fetchval(
            "SELECT count(*) FROM dim_store WHERE factory_id = $1", FID)
        n_pos = await conn.fetchval(
            "SELECT count(*) FROM fact_pos_transaction WHERE factory_id = $1", FID)
    print("[阳性对照] MOCK_REST 门店 %s 家 / POS %s 笔" % (n_store, n_pos))
    if not n_store or not n_pos:
        print("rc=2 阳性对照失败：读不到门店或 POS —— 本次读数作废")
        return 2

    # 🔴 仪器自检：`_build_spec` 的包必须真的到达了生产调用点。
    #    ⛔ 少了它，一个从没被调用的包会让每一行 `llm_dims=None`，
    #       而那和「LLM 一个维度都没给」长得一模一样。
    if ri._build_spec is not _traced_build_spec:
        print("rc=2 `_build_spec` 的包被覆盖了 —— 来源标记全部作废")
        return 2

    cases = [c for c in _CASES if not _WANT or c[0][0] in _WANT]
    print("[口径] arm=%s ROUNDS=%d 用例 %d 条（评测题库，⛔ 非真实流量）"
          % (arm, ROUNDS, len(cases)))
    clear_caches(verbose=True)
    print()

    rows = []
    for rnd in range(ROUNDS):
        for tag, q, prev, why in cases:
            clear_caches()
            history = None
            # 🔴 session_key 每个 case × 每个 arm × 每轮独立 —— 共用会串台
            skey = "vfp-%s-%s-r%d" % (arm, tag, rnd)
            if prev:
                first = await run_turn(pool, prev, skey)
                history = [
                    {"role": "user", "content": prev},
                    {"role": "assistant", "content": first.get("text", "")},
                ]
            row = await run_turn(pool, q, skey, history=history)
            row |= {"tag": tag, "round": rnd, "prev": prev, "why": why}
            rows.append(row)
            print("[%s r%d] kind=%s code=%s clar=%s llm_dims=%s dims=%s "
                  "VETO_dims=%s VETO_metrics=%s VETO_margin=%s dir_re=%s "
                  "metrics=%s margin=%s scope=%s authority=%s nbs=%d md5=%s n=%d"
                  % (tag, rnd, row.get("kind"), row.get("code"),
                     row.get("clar"), row.get("llm_dims"), row.get("dims"),
                     row.get("veto_dims"), row.get("veto_metrics"),
                     row.get("veto_margin"), row.get("dir_regex"),
                     row.get("metrics"), row.get("wants_margin"),
                     row.get("store_scope"), row.get("authority"),
                     row.get("n_build_spec"),
                     row.get("md5"), len(row.get("text", ""))))

    assert_not_an_llm_artifact(
        [r.get("_spec") for r in rows if r.get("_spec") is not None],
        llm_dead_slots=ctx.llm_dead_slots,
    )

    # 🔴 仪器活着的第二道对照：`_build_spec` 真的被调过，且**入参真的到手**。
    #    全 0 / 全 None ⇒ 包没生效 ⇒ 所有 VETO_* 读数作废（rc=2，⛔ 不是 rc=0）。
    n_traced = sum(1 for r in rows if (r.get("n_build_spec") or 0) > 0)
    n_llm_dims = sum(1 for r in rows if r.get("llm_dims") is not None)
    print("\n[仪器自检] 有 _build_spec 调用的行 %d/%d；拿到 llm_dimensions 的行 %d/%d"
          % (n_traced, len(rows), n_llm_dims, len(rows)))
    if not n_traced or not n_llm_dims:
        print("rc=2 来源标记没接上 —— 本次 VETO_* 读数全部作废")
        return 2

    print("\n" + "=" * 92)
    print("老板看到的原文  (arm=%s)" % arm)
    print("=" * 92)
    for r in rows:
        print("\n### [%s r%d] %r  (上一轮: %r)" %
              (r["tag"], r["round"], r["q"], r["prev"]))
        print("    量什么: %s" % r["why"])
        print("    kind=%s code=%s dims=%s metrics=%s margin=%s profit=%s "
              "clar=%s md5=%s len=%d"
              % (r.get("kind"), r.get("code"), r.get("dims"),
                 r.get("metrics"), r.get("wants_margin"),
                 r.get("asks_profit"), r.get("clar"), r.get("md5"),
                 len(r.get("text", ""))))
        print("    LLM 原始计划: %s"
              % json.dumps(r.get("parsed"), ensure_ascii=False, sort_keys=True))
        print("    ---8<---")
        for line in (r.get("text") or "(空)").splitlines()[:30]:
            print("    | " + line)
        print("    --->8---")

    out = os.environ.get("DUMP_JSON")
    if out:
        with open(out, "w", encoding="utf-8", newline="") as fh:
            json.dump([{k: v for k, v in r.items() if k != "_spec"}
                       for r in rows], fh, ensure_ascii=False, indent=1)
        print("\nJSON -> %s" % out)
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
