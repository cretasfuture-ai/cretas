# -*- coding: utf-8 -*-
"""交付定义③「追问接得住」的真值 + 每一条接不住的**逐条定性**。

## 为什么重测而不是引用上一轮的 5/10

上一轮（`docs/decisions/2026-08-18-餐饮AI读路径-本轮交接.md` 第三节）读出
`✅5 / 🟠5 / 🔴0`，判据是「第二轮 md5 变了 **且** `kind == "answer"`」。
两个问题：

1. 📏 **`kind == "answer"` 不等于「不是拒答」**。产品自己的判据是
   `refusal_queue.is_refusal()` —— 它额外把 `code ∈ {DATA_GAP, OUT_OF_DOMAIN}`
   也算拒答（那两个 code 的 `kind` **就是** `"answer"`，「答」的内容是「答不了」）。
   ⇒ 只看 `kind` 会把「诚实地说答不了」读成「接住了」。
   ⛔ 本探针不新造判据，直接调 `is_refusal`（形态 D：同一件事不写第二份）。
2. 上一轮 10 对问句**全是「换维度」一种形态**。换时间 / 换指标 / 省略式
   （「那 X 呢」）一条都没有 ⇒ 那个 5/10 只覆盖了追问形态的四分之一。

## 判据（每一格都打出来，⛔ 不只打结论）

    接住   = 第二轮 not is_refusal  且  正文与第一轮不同
    拒答   = 第二轮 is_refusal
    原样重发 = 第二轮正文与第一轮逐字相同（North Star 明写的反例）

## ⚠️ 三个「读数作废」的口子（rc=2，⛔ 不折叠进 rc=0）

- LLM 槽没有活账号 ⇒ 每条答案都是那句 fail-closed 拒答，
  **与「产品被问倒了」长得一模一样**（`_probe_bootstrap` 的第 5 件套）。
- 菜单目录 0 条 ⇒ 库没连上。
- 阳性对照**跨样本**不成立：要求 ≥3 个**不同**问句对的第一轮拿到非拒答。
  ⛔ 一个只在某一条问句上成立的阳性对照，证明不了仪器在别的问句上活着。

## 阴性对照（⛔ 不许省）

`FOLLOWUP_CAP_NEGATIVE_Q` 是**已知算不出**的量（翻台率：缺桌台/开台时间/
就餐轮次/可用桌数）。它必须**不是** answer —— 否则说明 `is_refusal` 这把尺子
在恒返回同一个读数，那么上面所有「接住」都没有意义。

## 来源标记（形态 A¹³）

每条读数带 `source_tier`。⚠️ 它在 **spec 上**，⛔ 不在响应字典上 ——
`_lossmaking.py:139` 读 `res.get("source_tier")` 恒为 `None`，那一列一直印的是 `?`。
两轮「不一样」如果来自两次缓存重放，这个「不一样」不说明任何事。

## 叙述缓存

`narrative_cache` TTL 24h、`window_key` 含当天日期。带 `history` 的调用
**本来就跳过它**（`synthesis_engine.py:1374`「Skipped entirely when
conversation_history is present」），但**第一轮不带 history、会命中**。
⇒ 两轮一个走缓存一个不走，比它们的正文差异就是在比缓存和现算。
本探针 monkeypatch 掉**读**（⛔ 只读，不删 prod 数据），两轮同一个条件。
"""
from __future__ import annotations

import asyncio
import hashlib
import logging
import os
import re

from smartbi.scripts._probe_bootstrap import (
    assert_not_a_probe_artifact,
    bootstrap_probe,
)

FOLLOWUP_CAP_FID = "MOCK_REST"
FOLLOWUP_CAP_ROUNDS = 3
ctx = bootstrap_probe(FOLLOWUP_CAP_FID)

from smartbi.agent.narrative_cache import NarrativeCacheService  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.gold.restaurant.refusal_queue import is_refusal  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: 四种追问形态 × 3 条。⛔ 不全用「换维度」—— 上一轮就是那么读出 5/10 的。
#:
#: `shape` 取值：dim(换维度) / time(换时间) / metric(换指标) / ellipsis(省略式)
FOLLOWUP_CAP_PAIRS = (
    ("dim", "最近30天营收多少", "按门店分别看呢"),
    ("dim", "最近30天毛利怎么样", "按菜品看呢"),
    ("dim", "最近30天营收多少", "分餐段看呢"),
    ("time", "这个月营收多少", "上个月呢"),
    ("time", "最近30天毛利率是多少", "最近7天呢"),
    ("time", "最近30天订单数是多少", "上周呢"),
    ("metric", "最近30天营收多少", "毛利呢"),
    ("metric", "哪家店营收最高", "哪家店毛利最高"),
    ("metric", "最近30天营收多少", "损耗呢"),
    ("ellipsis", "哪家店营收最高", "那最低的呢"),
    ("ellipsis", "有没有店是亏钱的", "是哪几家"),
    ("ellipsis", "哪道菜毛利最高", "最低的那道呢"),
)

#: 上一轮（`2026-08-18-餐饮AI读路径-本轮交接.md` 第三节）判定为
#: 「**这些维度组合根本没有 resolver 服务** —— 能力表的事」的那 5 条追问。
#: 造不造能力表这个裁定直接压在它们身上 ⇒ ⛔ 不引用上一轮的定性，自己再测一次。
#: 基础问句由本轮补（上一轮只登记了追问那一半）。
FOLLOWUP_CAP_PAIRS_PRIOR = (
    ("dim", "最近30天营收多少", "哪家店晚市最好"),
    ("dim", "最近30天折扣了多少", "哪家店折扣最多"),
    ("dim", "最近30天库存怎么样", "哪家店最缺货"),
    ("dim", "最近30天食材成本多少", "哪个食材涨得最多"),
    ("dim", "最近30天营收趋势怎么样", "按门店看趋势"),
)

#: `FOLLOWUP_CAP_SET=prior` 切到上面那组。⛔ 不复制第二份探针（形态 D）。
FOLLOWUP_CAP_SETS = {
    "main": FOLLOWUP_CAP_PAIRS,
    "prior": FOLLOWUP_CAP_PAIRS_PRIOR,
}
FOLLOWUP_CAP_ACTIVE = FOLLOWUP_CAP_SETS[os.environ.get("FOLLOWUP_CAP_SET", "main")]

#: 阴性对照：已知算不出（缺桌台/开台时间/就餐轮次/可用桌数）。
#: 它必须**不是** answer，否则 `is_refusal` 是恒真式，本轮全部读数作废。
FOLLOWUP_CAP_NEGATIVE_Q = "翻台率怎么样"

#: 这几条日志直接回答 Q2 的定性：repair 有没有开火、被谁拦的。
FOLLOWUP_CAP_WATCH = (
    "contract-repair resolver",
    "contract-repair SKIPPED",
    "plan realigned to planner label",
    "label cannot serve dimensions",
    "执行前拦截",
    "能力范围",
)

FOLLOWUP_CAP_CLEARERS = (
    "clear_semantic_plan_cache",
    "clear_route_cache",
    "clear_tenant_gate_cache",
    "clear_promoted_routes_cache",
)


class _FollowupCapCollect(logging.Handler):
    def __init__(self):
        super().__init__()
        self.lines: list = []

    def emit(self, record):
        try:
            msg = record.getMessage()
        except Exception:  # noqa: BLE001
            return
        if any(w in msg for w in FOLLOWUP_CAP_WATCH):
            self.lines.append(re.sub(r"\s+", " ", msg)[:220])


def _clear():
    """⛔ 硬约束 3：清了哪几个要**贴出来**；⛔ 不给 getattr 默认值。"""
    for name in FOLLOWUP_CAP_CLEARERS:
        getattr(ri, name)()
    return FOLLOWUP_CAP_CLEARERS


def _md5(text):
    return hashlib.md5((text or "").encode("utf-8")).hexdigest()[:8]


def _fmt(value):
    return "()" if value in (None, (), []) else repr(value)


async def _ask(pool, query, history, session_key):
    """照生产的调法（`gold_reads.py:1251-1279`）问一句。

    ⛔ 不传 `precomputed_spec` —— 传了 `history` 就到不了规划器
    （`restaurant_intent_service.py:1926`：`if spec is None:` 才去 parse）。
    那样第二轮会**看起来正常**地丢掉全部上下文。
    """
    _clear()
    set_factory_id(FOLLOWUP_CAP_FID)
    res = await svc.tiered_answer(
        query, pool, FOLLOWUP_CAP_FID, ctx.role,
        history=history, session_key=session_key,
        capture_source="followup_capability_probe",
    )
    res = res or {}
    spec = res.get("spec")
    text = res.get("answer_text") or ""
    return {
        "kind": res.get("kind") or "-",
        "code": res.get("code") or "",
        "text": text,
        "n": len(text),
        "md5": _md5(text),
        # ⚠️ source_tier 在 **spec** 上，⛔ 不在响应字典上
        "tier": getattr(spec, "source_tier", None),
        "authority": getattr(spec, "planner_authority", None),
        "intent": getattr(spec, "intent", None),
        "plan": tuple(getattr(spec, "planned_intents", ()) or ()),
        "dims": tuple(getattr(spec, "dimensions", ()) or ()),
        "req_metrics": tuple(getattr(spec, "requested_metrics", ()) or ()),
        "store_slot": getattr(spec, "store_slot", None),
        "clarify": getattr(spec, "clarification_needed", None),
        "sctx": res.get("structured_context"),
        "refusal": is_refusal(res),
    }


def _print_turn(tag, query, row, logs):
    print("  ── %s 【%s】" % (tag, query))
    print("     kind=%s refusal=%s code=%s n=%d md5=%s tier=%s authority=%s"
          % (row["kind"], row["refusal"], row["code"] or "-", row["n"],
             row["md5"], row["tier"], row["authority"]))
    print("     intent=%s plan=%s dims=%s req_metrics=%s store_slot=%s clarify=%s"
          % (row["intent"], _fmt(row["plan"]), _fmt(row["dims"]),
             _fmt(row["req_metrics"]), _fmt(row["store_slot"]), row["clarify"]))
    if logs:
        for line in logs:
            print("     · " + line)
    print("     原文(前 260 字): "
          + (row["text"][:260].replace("\n", " ") or "(空)"))


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s 轮数=%d 问句对=%d 问句集=%s"
          % (ctx.db_name, FOLLOWUP_CAP_FID, FOLLOWUP_CAP_ROUNDS,
             len(FOLLOWUP_CAP_ACTIVE),
             os.environ.get("FOLLOWUP_CAP_SET", "main")))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2

    set_factory_id(FOLLOWUP_CAP_FID)
    async with rr.dish_catalogue_scope(pool, FOLLOWUP_CAP_FID):
        catalogue = rr.current_dish_catalogue()
    print("阳性对照·菜单目录 = %d 条" % len(catalogue or ()))
    if not catalogue:
        print("⛔ rc=2 菜单目录为 0 —— 库没连上，读数作废")
        return 2

    handler = _FollowupCapCollect()
    for name in ("smartbi.gold.restaurant.restaurant_intent",
                 "smartbi.gold.restaurant.restaurant_intent_service"):
        logging.getLogger(name).addHandler(handler)

    # 叙述缓存：⛔ 只读，不删 prod 数据 —— monkeypatch 让**读**失效。
    # 两轮必须同一个条件：带 history 的第二轮本来就跳过它，第一轮不跳。
    orig_get = NarrativeCacheService.get
    orig_semantic = getattr(NarrativeCacheService, "get_semantic", None)

    async def _always_miss(self, *args, **kwargs):
        return None

    NarrativeCacheService.get = _always_miss
    if orig_semantic is not None:
        NarrativeCacheService.get_semantic = _always_miss
    print("叙述缓存: get / get_semantic 已 monkeypatch 成 miss（只读，未删任何行）")

    verdicts = {}
    all_texts = []
    all_specs = []
    negative_rows = []
    try:
        for rnd in range(1, FOLLOWUP_CAP_ROUNDS + 1):
            print("\n" + "#" * 78)
            print("# 第 %d 轮  已清缓存: %s" % (rnd, "、".join(FOLLOWUP_CAP_CLEARERS)))
            print("#" * 78)

            # 阴性对照，每轮都跑 —— ⛔ 不只在第一轮跑
            handler.lines.clear()
            neg = await _ask(pool, FOLLOWUP_CAP_NEGATIVE_Q, None,
                             "fcp-neg-r%d" % rnd)
            negative_rows.append(neg)
            all_texts.append(neg["text"])
            print("\n[阴性对照] r%d" % rnd)
            _print_turn("阴性", FOLLOWUP_CAP_NEGATIVE_Q, neg, list(handler.lines))

            for idx, (shape, base_q, follow_q) in enumerate(FOLLOWUP_CAP_ACTIVE):
                key = "fcp-r%d-p%d" % (rnd, idx)
                print("\n" + "=" * 78)
                print("r%d P%02d [%s] %s  ⇒  %s" % (rnd, idx, shape, base_q, follow_q))

                handler.lines.clear()
                first = await _ask(pool, base_q, None, key)
                first_logs = list(handler.lines)
                all_texts.append(first["text"])
                all_specs.append(first["authority"])

                history = [{
                    "q": base_q[:200],
                    "a_summary": (first["text"] or "")[:500],
                    "context": first["sctx"] or {},
                }]

                handler.lines.clear()
                second = await _ask(pool, follow_q, history, key)
                second_logs = list(handler.lines)
                all_texts.append(second["text"])
                all_specs.append(second["authority"])

                _print_turn("第一轮", base_q, first, first_logs)
                _print_turn("第二轮", follow_q, second, second_logs)

                if second["refusal"]:
                    verdict = "拒答"
                elif second["text"] and second["text"] == first["text"]:
                    verdict = "原样重发"
                elif not second["text"]:
                    verdict = "空正文"
                else:
                    verdict = "接住"
                # 两轮里只要有一轮 tier 不是 llm，这条读数不是「两次真编译」
                both_llm = (first["tier"] == "llm" and second["tier"] == "llm")
                print("  ▶ 判定: %s   (两轮都是 llm 编译? %s   第一轮 refusal=%s)"
                      % (verdict, both_llm, first["refusal"]))
                verdicts.setdefault((shape, base_q, follow_q), []).append({
                    "rnd": rnd, "verdict": verdict, "both_llm": both_llm,
                    "first": first, "second": second,
                    # ⛔ 两轮的日志**分开存**。第一版把它们并成一个 list，
                    #    于是下面那道扫描把第一轮的 repair 日志算给了第二轮
                    #    （假阳性），同时漏掉了 repair **没开火**的真受害者
                    #    （假阴性 —— 恰恰是最重要的那条）。形态 A。
                    "first_logs": first_logs, "second_logs": second_logs,
                })
    finally:
        NarrativeCacheService.get = orig_get
        if orig_semantic is not None:
            NarrativeCacheService.get_semantic = orig_semantic

    # ── 探针自证：这些失败是不是我自己造的 ────────────────────────────────
    assert_not_a_probe_artifact(all_texts, prod_log_hits=None)

    # ── 阳性对照（跨样本）──────────────────────────────────────────────────
    answered_pairs = {
        pair for pair, runs in verdicts.items()
        if any(not r["first"]["refusal"] for r in runs)
    }
    print("\n" + "=" * 78)
    print("阳性对照·第一轮拿到非拒答的**不同问句对** = %d 个（要求 ≥3，跨样本）"
          % len(answered_pairs))
    if len(answered_pairs) < 3:
        print("⛔ rc=2 阳性对照跨样本不成立 —— 分不清「产品接不住」和「仪器没跑起来」")
        return 2

    # ── 阴性对照 ─────────────────────────────────────────────────────────
    neg_not_answer = sum(1 for r in negative_rows if r["refusal"])
    print("阴性对照·「%s」被判拒答 = %d/%d 轮"
          % (FOLLOWUP_CAP_NEGATIVE_Q, neg_not_answer, FOLLOWUP_CAP_ROUNDS))
    if neg_not_answer == 0:
        print("⛔ rc=2 阴性对照一轮都没红 —— `is_refusal` 在恒返回同一个读数，"
              "上面所有「接住」都没有意义")
        return 2

    # ── 汇总（⛔ 逐条贴，不只报比例）──────────────────────────────────────
    print("\n" + "=" * 78)
    print("逐条判定（%d 对 × %d 轮）" % (len(FOLLOWUP_CAP_ACTIVE), FOLLOWUP_CAP_ROUNDS))
    print("=" * 78)
    stable_caught = 0
    for (shape, base_q, follow_q), runs in verdicts.items():
        marks = "/".join(r["verdict"] for r in runs)
        llm_marks = "/".join("llm" if r["both_llm"] else "!" for r in runs)
        if all(r["verdict"] == "接住" for r in runs):
            stable_caught += 1
        print("[%-8s] %-18s ⇒ %-14s  %s   tier:%s"
              % (shape, base_q, follow_q, marks, llm_marks))
    print("\n3 轮全部「接住」的问句对 = %d/%d"
          % (stable_caught, len(FOLLOWUP_CAP_ACTIVE)))

    # ── Q3 的解冻条件 2：有没有**第二个**同形受害问句 ──────────────────────
    #
    # 🔴 判据是设计卡写死的三条，⛔ 不是「repair 开火了」：
    #       dims == ()  且  LLM 的 intent ∉ planner 的 plan
    #    第一版按「repair 开火」扫，两头都错 ——
    #      假阳性：`GROSS_MARGIN -> GROSS_MARGIN` 这种自我修复也算了进来；
    #      假阴性：真受害者那次 repair **根本没开火**（2601 的外层闸
    #              `if repair_candidate and dimensions:` 把整段跳过了），
    #              于是矛盾被原样留给下游 `_execution_mismatch` 拒答。
    #    ▎我想知道的是「code ∉ plan 且 dims == ()」，实际量的是「repair 开火了」。
    print("\n" + "=" * 78)
    print("解冻条件 2 扫描：dims == () 且 intent ∉ planned_intents")
    print("=" * 78)
    victims = {}
    for (shape, base_q, follow_q), runs in verdicts.items():
        for run in runs:
            for tag, row in (("第一轮", base_q), ("第二轮", follow_q)):
                data = run["first"] if tag == "第一轮" else run["second"]
                if data["dims"] == () and data["intent"] and (
                        data["intent"] not in data["plan"]):
                    victims.setdefault(
                        (tag, row, data["intent"], data["plan"],
                         data["req_metrics"], data["kind"]), []).append(run["rnd"])
    for (tag, q, intent, plan, req, kind), rounds in sorted(
            victims.items(), key=lambda kv: str(kv[0])):
        print("  [%d/%d 轮] %s 【%s】" % (len(rounds), FOLLOWUP_CAP_ROUNDS, tag, q))
        print("      intent=%s  plan=%s  dims=()  req_metrics=%s  kind=%s"
              % (intent, _fmt(plan), _fmt(req), kind))
    print("  同形受害的**不同问句** = %d 条" % len(victims))
    if not victims:
        print("  ⇒ 本轮**没有**观测到第二个同形受害问句")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
