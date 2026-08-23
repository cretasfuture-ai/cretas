# -*- coding: utf-8 -*-
"""全景重测 —— **修复前基线**：24 问句 × 2 轮，逐问句摊开老板会看到的东西。

## 与 `restaurant_panorama_probe.py` 的分工（⛔ 不是它的替代品）

那个探针答的是「答上多少条」。这个探针答的是**「为什么是这一条」**：
每一格都要能追到 LLM 给了什么、代码改成了什么、老板最后读到什么。

    restaurant_panorama_probe   verdict / kind / tier / contract        汇总口径
    _panorama24（本文件）       + llm_code/llm_dimensions（钩子）        逐格归因
                                + store_slot/dish_slot/clarification
                                + 答案原文首 200 字
                                + 交付定义 ①④⑤ 的机械可判部分

⛔ **问句集直接 import 那个模块的 `QUESTIONS`，本文件不写第二份**
   （形态 D：同一个东西有两份，它一定会漂 —— 而这份要漂的话，
   漂的方向恰好是「两个基线的分母不一样却被并排比」，
   那正是 `restaurant_panorama_probe` 头部记着的 41/48 vs 40/48 事故）。

## 五条交付定义，本探针能判哪几条（⛔ 判不了的明确登记为未覆盖）

    ① 答得上                 ✅ 机械可判  kind == "answer"
    ② 说一件他没想到的事      ❌ 未覆盖    需要「他想到了什么」的基准，机器没有
    ③ 追问接得住             ❌ 未覆盖    需要第二轮追问语料 + 判「接住」的定义
                                          （本仓已实测：「两轮答案不一样」是错的
                                            判据 —— 拒答也不一样）
    ④ 该给表格就给表格        ⚠️ 代理判据  正文有 markdown 表格 × 问句属排行/对比/构成
    ⑤ 答不了时说清三件事      ⚠️ 代理判据  拒答文本里同时命中「缺什么/怎么拿到/你要干什么」

⚠️ ④⑤ 是**代理判据**（形态 E：代理判据要标出来，并且要能被替换）。
   它们量的是**词面**，不是语义。所以本探针把命中的词逐条打出来，
   让读的人能自己复核 —— ⛔ 不要只看那个 ✓/✗。

## 硬纪律（照做，⛔ 不是提醒）

* 阳性对照：菜单目录 > 0 条、`_build_spec` 钩子调用数 > 0。任一为 0 ⇒ rc=2。
* 三态退出码：0 没问题 / 1 有问题（读数有效，指向缺陷）/ 2 没量到（读数作废）。
* **每轮开跑前清缓存并贴出清了哪几个** —— 用模块自带的 helper，⛔ 不拼名字。
  ⇒ 两轮都是**冷编译**，所以「两轮不一致」量的是 LLM 的不确定性，
    ⛔ 不是缓存回放（本仓踩过：不清缓存时第二轮命中第一轮写进去的计划，
    两侧根本是同一个东西）。
* 每条读数带来源标记 `source_tier` / `planner_authority`。

## 用法

    /tmp/runprobe.sh smartbi.scripts._panorama24 /tmp/pan24.log
"""
from __future__ import annotations

import asyncio
import functools
import hashlib
import os
import time

from smartbi.scripts._probe_bootstrap import (
    LLM_UNAVAILABLE_AUTHORITY,
    bootstrap_probe,
)

FID = os.environ.get("PROBE_FID", "MOCK_REST")
ROUNDS = int(os.environ.get("PROBE_ROUNDS", "2"))
#: 生产入口 `gold_reads` 对同一 sessionId 全程共用一个 key；
#: 独立 key 量的是「每一句自己的能力」—— 做工作队列要的是后者。
SHARED_KEY = os.environ.get("PROBE_SHARED_KEY") == "1"

ctx = bootstrap_probe(FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.gold.restaurant.restaurant_intent_service import (  # noqa: E402
    should_delegate,
    tiered_answer,
)
from smartbi.scripts.restaurant_panorama_probe import QUESTIONS as _ALL_QUESTIONS  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: 冒烟用的截断。⛔ **基线必须不设它** —— 设了之后分母就不是 24，
#: 与任何别的基线不可比（形态 D 的直接成因：口径不落盘）。
#: 报数时 `问句集来源` 那一行会把实际条数打出来，读的人分得清。
_QMAX = int(os.environ.get("PROBE_QMAX", "0"))
QUESTIONS = _ALL_QUESTIONS[:_QMAX] if _QMAX else _ALL_QUESTIONS

#: 交付定义 ④「该给表格就给表格」里的**该**。⛔ 这是人工判定的一份清单，
#: 按 `QUESTIONS` 的下标登记 —— 排行 / 对比 / 构成三类。
#: 保守登记：拿不准的（趋势、占比单值、决策题）**不登记**，
#: 宁可少判也不要造一条会误报的判据（形态 E：一道天天误报的闸最终会被关掉）。
TABLE_EXPECTED_IDX = frozenset({
    0,   # 哪家店卖得最好          排行
    1,   # 哪道菜毛利最高          排行
    3,   # 这个月比上个月好还是差   对比
    4,   # 这周比上周怎么样        对比
    6,   # 哪家店成本最高          排行
    8,   # 哪家店缺货最严重        排行
    10,  # 哪个时段生意最好        排行
    12,  # 按门店看领料趋势        按门店拆 = 构成
    13,  # 损耗都是哪些类型造成的   构成
    16,  # 外卖和堂食各占多少      构成
    17,  # 哪个供应商报价最贵      排行
    23,  # 毛利最低的菜品有哪些    排行
})

#: 交付定义 ⑤ 的三层，每层一组**代理**关键词。
#: ⛔ 它量的是词面不是语义 —— 命中词会逐条打出来供复核。
REFUSAL_LAYERS = {
    "缺什么": ("缺", "没有", "还没", "暂时", "不支持", "无法", "拿不到",
             "未录入", "未配置", "不在", "超出"),
    "怎么拿到": ("需要", "可以", "建议", "请", "补", "录入", "上传", "联系",
              "配置", "先", "开通", "维护"),
    "你要干什么": ("是想", "想看", "要不要", "换个", "换一", "或者", "你指的",
               "你要", "帮你", "?", "？"),
}

#: 口径二用的**代理**标记：`kind == "answer"` 但正文其实是「这个数算不出来」。
#:
#: 🔴 加这一格的直接原因（2026-08-18 实测）：`Q18 哪个供应商报价最贵` 和
#:    `Q16 食材成本占营收多少` 都是 `kind=answer` / `contract=True`，
#:    而正文是「**比不了价**」「**算不出来**」—— 它们进了 79.2% 那个分子。
#:    ▎**「产品回了一段话」和「老板拿到那个数」是两件事**，
#:    ▎而只报第一个口径会让工作队列少两条。
#:
#: ⚠️ 它扫的是**全文**不是首 200 字 —— 首 200 字里没有不等于没有（形态 A⁗）。
#: ⚠️ 代理判据：量的是词面。命中的词**和它的位置**会逐条打出来供复核（形态 E）。
DATA_GAP_MARKS = ("算不出来", "比不了", "还没有数据", "没敢算", "算不了")

#: 🔴 **只扫全文会把「有答案 + 附带一句别的算不出来」误判成拒答。**
#:    第一版就是那样，当场把 `Q01 哪家店卖得最好`（1246 字、带排行表格、
#:    数给全了）算进了「拿不到那个数」——**口径二从 43.8% 假性压低了 9 条**。
#:
#: ▎判据要分的是「这段话本身是一句拒答」还是「这段话有答案，附带一条说明」。
#: ⇒ 用**位置**：拒答把「算不出来」放在**抬头**；有答案的把它放在正文靠后的
#:   口径说明里。阈值不是拍的 —— 逐条打 `gap_pos`，从实测分布上读出来。
#:
#: ⛔ 这仍然是代理判据。所以两个子口径**都报**，并把 `gap_pos` 打出来，
#:    让读的人能自己把阈值挪一格看看结论变不变。
GAP_HEADLINE_CHARS = 120

#: ⛔ 用模块自带的 helper，不要拼名字 —— 拼错的属性名不会报错，
#: 只会让清理**静默失效**（本仓踩过：`_PLAN_CACHE` 根本不存在）。
CACHE_CLEARERS = (
    "clear_semantic_plan_cache",
    "clear_route_cache",
    "clear_tenant_gate_cache",
    "clear_promoted_routes_cache",
)

#: `_build_spec` 钩子收到的每一次调用。阳性对照就靠它 —— 为 0 ⇒ 钩子没挂上。
_CALLS: list = []


def clear_caches() -> list:
    for name in CACHE_CLEARERS:
        getattr(ri, name)()
    return list(CACHE_CLEARERS)


def install_hook() -> None:
    """钩 `_build_spec`，把 LLM 原始输出留在读数里。

    ⛔ `functools.wraps` 保签名 —— 生产代码里有按签名/名字做的判断时，
       一个裸 lambda 会静默改掉行为，那就不是「观测」是「变异」了。
    """
    original = ri._build_spec

    @functools.wraps(original)
    def _spy(*args, **kwargs):
        _CALLS.append({
            "code": (args[0] if args else kwargs.get("code")),
            "tier": kwargs.get("tier"),
            "llm_dimensions": kwargs.get("llm_dimensions"),
            "llm_dish": kwargs.get("llm_dish"),
            "llm_store": kwargs.get("llm_store"),
            "llm_requested_metrics": kwargs.get("llm_requested_metrics"),
            "clarification_needed": kwargs.get("clarification_needed"),
        })
        return original(*args, **kwargs)

    ri._build_spec = _spy


def _fp(text: str) -> str:
    return hashlib.md5((text or "").encode("utf-8")).hexdigest()[:8]


def _fmt(v) -> str:
    if v is None:
        return "∅"
    if isinstance(v, (list, tuple)):
        return ",".join(str(x) for x in v) or "∅"
    return str(v)


def has_markdown_table(text: str) -> bool:
    """代理判据：正文里有没有 markdown 表格。

    ⛔ 只认「换行后紧跟竖线」**且**有分隔行 —— 单看 `|` 会把
       普通句子里的竖线也数进来。
    """
    return ("\n|" in (text or "")) and ("---" in (text or ""))


def refusal_layers_hit(text: str) -> dict:
    """代理判据：拒答文本命中了三层里的哪几层，**并把命中的词带出来**。

    ⛔ 返回命中词而不只是 True/False —— 代理判据的读数必须能被人复核，
       否则它和「我猜的」没有区别。
    """
    out = {}
    for layer, words in REFUSAL_LAYERS.items():
        out[layer] = tuple(w for w in words if w in (text or ""))
    return out


async def run_turn(pool, query: str, session_key: str) -> dict:
    """完全复刻 `gold_reads.py` 那条生产序列（含 dish_catalogue_scope 的范围）。"""
    _CALLS.clear()
    set_factory_id(FID)
    async with rr.dish_catalogue_scope(pool, FID):
        catalogue = rr.current_dish_catalogue()
        spec = await ri.parse_restaurant_query(
            query, pool, factory_id=FID, session_key=session_key,
            semantic_first=True,
        )
    row = {"q": query, "catalogue_n": len(catalogue) if catalogue else 0,
           "hook_calls": len(_CALLS)}

    # ── LLM 原始输出（钩子）—— ⛔ 取**第一次**调用，那是规划器的输出；
    #    后续调用可能来自 contract-repair 的重建。
    first = _CALLS[0] if _CALLS else None
    row["llm_code"] = (first or {}).get("code")
    row["llm_dimensions"] = (first or {}).get("llm_dimensions")
    row["llm_dish"] = (first or {}).get("llm_dish")
    row["llm_store"] = (first or {}).get("llm_store")

    if spec is None:
        return row | {"kind": "spec-None", "n": 0, "text": ""}

    row |= {
        "intent": (spec.intent or "∅").replace("RESTAURANT_OPS_", ""),
        "tier": spec.source_tier,
        "authority": spec.planner_authority,
        "dims": list(spec.dimensions),
        "store_slot": spec.store_slot,
        "dish_slot": spec.dish_slot,
        "clarification_needed": spec.clarification_needed,
    }
    if not should_delegate(spec, None, query=query):
        return row | {"kind": "no-delegate", "n": 0, "text": "", "contract": None}
    result = await tiered_answer(
        query, pool, FID, ctx.role, precomputed_spec=spec, session_key=session_key,
    )
    if not result:
        return row | {"kind": "None", "n": 0, "text": "", "contract": None}
    text = result.get("answer_text") or ""
    return row | {
        "kind": result.get("kind") or "-",
        "code": (result.get("code") or "").replace("RESTAURANT_OPS_", ""),
        "contract": result.get("contract_pass"),
        "n": len(text),
        "fp": _fp(text),
        "text": text,
        # ⛔ 扫**全文**，不是首 200 字 —— 首 200 字里没有 ≠ 没有（形态 A⁗）。
        "gap_marks": tuple(m for m in DATA_GAP_MARKS if m in text),
        # 第一个标记出现在第几个字。⛔ 这一格才是判「拒答 vs 附带说明」的东西。
        "gap_pos": min([text.find(m) for m in DATA_GAP_MARKS if m in text]
                       or [-1]),
    }


def emit(idx: int, rnd: int, row: dict) -> None:
    """逐格摊开。⛔ 不折叠 —— 这个探针存在的全部理由就是这几行。"""
    text = row.get("text") or ""
    print("\n" + "=" * 78)
    print("Q%02d r%d  %s" % (idx + 1, rnd, row["q"]))
    print("  ① kind=%-14s 字数=%-5s contract=%-5s code=%s"
          % (row.get("kind", "-"), row.get("n", "-"),
             row.get("contract"), row.get("code", "-")))
    print("  来源  tier=%-12s authority=%s  fp=%s"
          % (row.get("tier", "-"), row.get("authority", "-"), row.get("fp", "-")))
    print("  LLM   code=%-24s dimensions=%s"
          % (_fmt(row.get("llm_code")), _fmt(row.get("llm_dimensions"))))
    print("        dish=%-14s store=%s  钩子调用=%d"
          % (_fmt(row.get("llm_dish")), _fmt(row.get("llm_store")),
             row.get("hook_calls", 0)))
    print("  最终  intent=%-22s dims=%s" % (_fmt(row.get("intent")),
                                            _fmt(row.get("dims"))))
    print("        store_slot=%-12s dish_slot=%-12s clarification_needed=%s"
          % (_fmt(row.get("store_slot")), _fmt(row.get("dish_slot")),
             row.get("clarification_needed")))
    # ④ 表格
    expected = idx in TABLE_EXPECTED_IDX
    got = has_markdown_table(text)
    if expected:
        print("  ④ 该给表格=是  实际有表格=%s  → %s"
              % (got, "✓" if got else "✗ 缺表格"))
    else:
        print("  ④ 该给表格=否（不在排行/对比/构成清单）实际有表格=%s" % got)
    # 口径二：`kind=answer` 但正文说「算不出来」
    if row.get("kind") == "answer" and row.get("gap_marks"):
        pos = row.get("gap_pos", -1)
        print("  ⚠️ 口径二: 命中 %s @第%d字 → %s"
              % ("、".join(row["gap_marks"]), pos,
                 "**整段是拒答**（抬头就说算不出来）" if 0 <= pos < GAP_HEADLINE_CHARS
                 else "有答案，这句是靠后的口径说明（⛔ 不算拒答）"))
    # ⑤ 拒答三件事 —— 只在非 answer 时判
    if row.get("kind") != "answer":
        hits = refusal_layers_hit(text)
        full = all(hits[layer] for layer in REFUSAL_LAYERS)
        print("  ⑤ 拒答三层 → %s" % ("✓ 三层齐" if full else "✗ 缺层"))
        for layer in REFUSAL_LAYERS:
            print("       %-8s %s" % (layer, "、".join(hits[layer]) or "—— 未命中"))
    else:
        print("  ⑤ 不适用（kind=answer）")
    print("  老板看到（原文首 200 字）:")
    print("    %s" % (text[:200].replace("\n", " ⏎ ") or "（空）"))


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s ROLE=%s ROUNDS=%d SHARED_KEY=%s TODAY=%s"
          % (ctx.db_name, FID, ctx.role, ROUNDS, SHARED_KEY,
             time.strftime("%Y-%m-%d")))
    print("问句集来源=smartbi.scripts.restaurant_panorama_probe.QUESTIONS  共 %d 条"
          % len(QUESTIONS))
    print("④ 该给表格的问句 = %d 条（下标 %s）"
          % (len(TABLE_EXPECTED_IDX), sorted(TABLE_EXPECTED_IDX)))

    if ctx.llm_dead_slots:
        print("⛔ rc=2 本进程这些槽一个活账号都没有: %s ——"
              " 每条答案都会是 fail-closed 拒答，和「被问倒了」长得一样"
              % "、".join(ctx.llm_dead_slots))
        return 2

    install_hook()
    rows = []
    for rnd in range(1, ROUNDS + 1):
        cleared = clear_caches()
        print("\n" + "#" * 78)
        print("# 第 %d 轮   已清缓存: %s" % (rnd, "、".join(cleared)))
        print("#" * 78)
        for idx, q in enumerate(QUESTIONS):
            key = ("p24-shared-r%d" % rnd) if SHARED_KEY else ("p24-r%d-q%d" % (rnd, idx))
            row = await run_turn(pool, q, key)
            row["round"] = rnd
            row["idx"] = idx
            rows.append(row)
            emit(idx, rnd, row)

    total = len(rows)
    # ── 阳性对照（⛔ 不许省，先判它再看任何结论）──────────────────────────
    print("\n" + "=" * 78)
    print("阳性对照")
    cat_min = min(r.get("catalogue_n", 0) for r in rows)
    hook_total = sum(r.get("hook_calls", 0) for r in rows)
    print("  菜单目录最小条数 = %d（0 ⇒ 目录没加载，读数作废）" % cat_min)
    print("  _build_spec 钩子总调用 = %d（0 ⇒ 钩子没挂上，LLM 那两列全是假的）"
          % hook_total)
    llm_dead = [r for r in rows if r.get("authority") == LLM_UNAVAILABLE_AUTHORITY]
    print("  planner_authority=%s 的条数 = %d" % (LLM_UNAVAILABLE_AUTHORITY,
                                                  len(llm_dead)))
    if cat_min == 0:
        print("⛔ rc=2 菜单目录为 0 —— 本次读数作废")
        return 2
    if hook_total == 0:
        print("⛔ rc=2 钩子一次都没被调用 —— LLM 列全是 ∅，分不清「没给」和「没钩上」")
        return 2
    if llm_dead:
        print("⛔ rc=2 %d/%d 条是 fail-closed 拒答，**不是产品被问倒了**"
              % (len(llm_dead), total))
        return 2

    # ── 汇总 ────────────────────────────────────────────────────────────
    answered = sum(1 for r in rows if r.get("kind") == "answer")
    gaps = sum(1 for r in rows if r.get("contract") is False)
    print("\n" + "=" * 78)
    print("汇总（口径：租户=%s / 问句集=restaurant_panorama_probe.QUESTIONS %d 条 /"
          " 轮数=%d / session_key=%s / 每轮开跑前清 4 个缓存）"
          % (FID, len(QUESTIONS), ROUNDS, "共用" if SHARED_KEY else "独立"))
    print("  ① 答上率·口径一（kind==answer）= %d/%d = %.1f%%"
          % (answered, total, answered / total * 100))
    # ── 口径二 ⛔ 两个都报，不只报对自己有利的那个 ──────────────────────
    marked = [r for r in rows if r.get("kind") == "answer" and r.get("gap_marks")]
    gapped = [r for r in marked if 0 <= r.get("gap_pos", -1) < GAP_HEADLINE_CHARS]
    tail = [r for r in marked if r not in gapped]
    real = answered - len(gapped)
    print("  ① 答上率·口径二（kind==answer **且抬头不是「算不出来」**）"
          "= %d/%d = %.1f%%" % (real, total, real / total * 100))
    print("     ⚠️ 代理判据：抬头 = 前 %d 字。逐条 gap_pos 见下，"
          "⛔ 阈值挪一格结论就可能变，自己复核。" % GAP_HEADLINE_CHARS)
    if gapped:
        print("     口径一算答上、口径二不算的 %d 条 —— **抬头就说算不出来**:"
              % len(gapped))
        for q in dict.fromkeys(r["q"] for r in gapped):
            hit = next(r for r in gapped if r["q"] == q)
            print("       · %-24s 命中 %s @第%d字"
                  % (q, "、".join(hit["gap_marks"]), hit["gap_pos"]))
    if tail:
        print("     🔵 阴性对照 —— 命中了标记但**位置靠后**，判为有答案的 %d 条:"
              % len(tail))
        for q in dict.fromkeys(r["q"] for r in tail):
            hit = next(r for r in tail if r["q"] == q)
            print("       · %-24s 命中 %s @第%d字 (答案 %d 字)"
                  % (q, "、".join(hit["gap_marks"]), hit["gap_pos"], hit["n"]))
    print("  契约缺口（contract_pass=False）= %d" % gaps)

    kinds = {}
    for r in rows:
        kinds[r.get("kind", "-")] = kinds.get(r.get("kind", "-"), 0) + 1
    print("  kind 分布: %s" % "  ".join("%s=%d" % kv for kv in sorted(kinds.items())))
    tiers = {}
    for r in rows:
        tiers[r.get("tier", "-")] = tiers.get(r.get("tier", "-"), 0) + 1
    print("  来源标记 source_tier 分布: %s"
          % "  ".join("%s=%d" % kv for kv in sorted(tiers.items())))

    # ── 逐问句 kind 表（⛔ 逐行打，不折叠）──────────────────────────────
    print("\n逐问句 × 轮次（⛔ 不折叠）")
    print("%-4s %-24s %-16s %-16s %-6s %-6s %-6s %s"
          % ("#", "问句", "r1 kind", "r2 kind", "r1字数", "r2字数", "④表格", "一致"))
    by_idx = {}
    for r in rows:
        by_idx.setdefault(r["idx"], {})[r["round"]] = r
    inconsistent = []
    table_ok = table_bad = 0
    refusal_ok = refusal_bad = 0
    for idx, q in enumerate(QUESTIONS):
        per = by_idx.get(idx, {})
        r1, r2 = per.get(1), per.get(2)
        k1 = (r1 or {}).get("kind", "-")
        k2 = (r2 or {}).get("kind", "-") if ROUNDS > 1 else "—"
        same = (k1 == k2) if ROUNDS > 1 else True
        if not same:
            inconsistent.append((idx, q, k1, k2))
        if idx in TABLE_EXPECTED_IDX:
            for r in per.values():
                if has_markdown_table(r.get("text") or ""):
                    table_ok += 1
                else:
                    table_bad += 1
        for r in per.values():
            if r.get("kind") != "answer":
                hits = refusal_layers_hit(r.get("text") or "")
                if all(hits[layer] for layer in REFUSAL_LAYERS):
                    refusal_ok += 1
                else:
                    refusal_bad += 1
        tmark = "-"
        if idx in TABLE_EXPECTED_IDX:
            got = [has_markdown_table((per.get(x) or {}).get("text") or "")
                   for x in sorted(per)]
            tmark = "%d/%d" % (sum(got), len(got))
        print("Q%02d  %-24s %-16s %-16s %-6s %-6s %-6s %s"
              % (idx + 1, q, k1, k2, (r1 or {}).get("n", "-"),
                 (r2 or {}).get("n", "-"), tmark, "" if same else "🔴 不一致"))

    print("\n  ④ 该给表格给了吗（只统计清单内的 %d 条 × %d 轮）: 给了 %d / 没给 %d"
          % (len(TABLE_EXPECTED_IDX), ROUNDS, table_ok, table_bad))
    print("  ⑤ 非 answer 的条目里三层齐全: %d / 缺层 %d（⚠️ 代理判据，量的是词面）"
          % (refusal_ok, refusal_bad))

    print("\n两轮不一致的问句（⛔ 不取其中一轮）: %d 条" % len(inconsistent))
    for idx, q, k1, k2 in inconsistent:
        print("  🔴 Q%02d %s   r1=%s  r2=%s" % (idx + 1, q, k1, k2))

    print("\n答不上的问句 —— 这是工作队列（⛔ 逐条列）")
    print("  【队列 A】口径一没答上（kind != answer）")
    queue = []
    for idx, q in enumerate(QUESTIONS):
        per = by_idx.get(idx, {})
        bad = [(rn, r) for rn, r in sorted(per.items()) if r.get("kind") != "answer"]
        if bad:
            queue.append((idx, q, bad))
    for idx, q, bad in queue:
        marks = "、".join("r%d:%s/%s" % (rn, r.get("kind"), r.get("code", "-"))
                          for rn, r in bad)
        print("    Q%02d %-24s %s" % (idx + 1, q, marks))
    print("    小计 %d 条" % len(queue))

    print("  【队列 B】口径一算答上、口径二没答上"
          "（回了话，但那段话是「算不出来」）")
    queue_b = []
    for idx, q in enumerate(QUESTIONS):
        per = by_idx.get(idx, {})
        bad = [(rn, r) for rn, r in sorted(per.items())
               if r.get("kind") == "answer" and r.get("gap_marks")
               and 0 <= r.get("gap_pos", -1) < GAP_HEADLINE_CHARS]
        if bad:
            queue_b.append((idx, q, bad))
            print("    Q%02d %-24s %s" % (
                idx + 1, q,
                "、".join("r%d:%s/%s@%d" % (rn, r.get("code", "-"),
                                            "+".join(r["gap_marks"]),
                                            r["gap_pos"])
                          for rn, r in bad)))
    print("    小计 %d 条" % len(queue_b))
    print("  合计（A ∪ B）= %d 条问句老板拿不到那个数"
          % len({i for i, _, _ in queue} | {i for i, _, _ in queue_b}))

    print("\n未覆盖的交付定义（⛔ 明确登记，不假装判了）")
    print("  ② 说一件他没想到的事 —— 未覆盖：需要「他已经想到什么」的基准，"
          "机器侧没有这个输入")
    print("  ③ 追问接得住 —— 未覆盖：需要成对的追问语料；且「两轮答案不一样」"
          "是**错的**判据（拒答也不一样，本仓实测 9/10 假阳）")

    return 1 if (queue or gaps) else 0


if __name__ == "__main__":
    t0 = time.time()
    rc = asyncio.run(main())
    print("\nELAPSED=%.1fs  rc=%d" % (time.time() - t0, rc))
    raise SystemExit(rc)
