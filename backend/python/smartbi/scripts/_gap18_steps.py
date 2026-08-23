# -*- coding: utf-8 -*-
"""#18 归因七步链: 逐步实测「做没做」—— 真实入口, 绕过叙述缓存。

⛔ 判据是**行为**, 不是「源码里有没有那个函数」。每一步的标记都取自
   老板真正看到的那段正文 (`answer_text`), 或产出它的 FactBook 结构。

🔑 每条读数带来源标记 (形态 A¹³): `kind` / `source` / `synthesize 调用次数` /
   `attribution 是否非空`。⚠️ `synthesize 调用 0 次` 或 `attribution 全空`
   ⇒ 这一轮**没量到东西**, ⛔ 不能把它写成「这一步没做」。

⚠️ 阳性对照 (会读出「全无」的仪器必须配): 至少一轮 `synthesize` 被调用且
   `factbook.attribution` 非空。全部轮次都没有 ⇒ rc=2, 本次读数作废。
   ⛔ 「仪器坏掉时它会不会也成立」—— 不会: 仪器坏时 SEEN 为空, 该对照必假。

三态 rc: 0=量到了 / 1=量到了且有步骤缺失 / 2=没量到

  ATTRIB_ROUNDS=2 /tmp/runprobe.sh smartbi.scripts._gap18_steps /tmp/gap18.log
"""
from __future__ import annotations

import asyncio
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))

from smartbi.scripts._probe_bootstrap import bootstrap_probe  # noqa: E402

ctx = bootstrap_probe("MOCK_REST")

from smartbi.agent import synthesis_engine as se  # noqa: E402
from smartbi.agent.narrative_cache import NarrativeCacheService  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

FID = "MOCK_REST"
ROUNDS = int(os.environ.get("ATTRIB_ROUNDS", "2"))

QUESTIONS = [q for q in (os.environ.get("GAP18_QS") or "").split("|") if q] or [
    "我要不要关掉最差的那家店",
    "上周营收为什么跌了",
    "毛利为什么下滑",
    "哪家店拖后腿",
]

_CLEARED = ("clear_semantic_plan_cache", "clear_route_cache",
            "clear_tenant_gate_cache", "clear_promoted_routes_cache")

#: 第⑥步的段头由 `_append_investigation_directions` 确定性拼出来。
#: ⛔ 不取整句 (含 cause 变量), 取两端不变的部分。
STEP6_HEAD_A = "差距出在「"
STEP6_HEAD_B = "接下来往哪儿查"
#: 第⑦步收尾句 —— 取 `_INVESTIGATION_LAST_RESORT` 的**模块常量本身**,
#: ⛔ 不抄一份字面串 (形态 D: 两份一定会漂)。
STEP7_MARK = se._INVESTIGATION_LAST_RESORT[:18]

#: 第⑤步「反问现实事件」的形态: 向老板**提问**线下发生了什么。
#: ⚠️ 这是一条**阴性**判据 —— 读出 0 之前必须先证明仪器活着 (见阳性对照)。
STEP5_ASK_MARKS = ("是不是有商场活动", "施工", "周边有没有", "最近有没有发生",
                   "周边变化", "有没有搞活动", "对面新开")

SEEN: list = []


def _instrument():
    orig_syn = se.ComprehensiveSynthesisEngine.synthesize
    orig_fb = se.ComprehensiveSynthesisEngine._build_factbook

    async def wrapped_fb(self, factory_id, date_range, plan, **kw):
        fb = await orig_fb(self, factory_id, date_range, plan, **kw)
        if SEEN:
            SEEN[-1]["attribution"] = fb.attribution
            SEEN[-1]["missing_dims"] = [d.get("code") for d in
                                        (fb.missing_dimensions or [])]
            SEEN[-1]["avail_dims"] = [d.get("code") for d in
                                      (fb.available_dimensions or [])]
            SEEN[-1]["period_comparison"] = getattr(fb, "period_comparison", None)
        return fb

    async def wrapped(self, factory_id, question, date_range, **kw):
        rec = {"question": question, "plan": None, "attribution": None,
               "missing_dims": None, "avail_dims": None, "period_comparison": None,
               "source": None, "answer": None}
        SEEN.append(rec)
        rec["plan"] = self.plan_dimensions(
            question, has_history=bool(kw.get("conversation_history")),
            dimension_hints=kw.get("dimension_hints"))
        res = await orig_syn(self, factory_id, question, date_range, **kw)
        rec["source"] = getattr(res, "source", None)
        rec["answer"] = getattr(res, "answer", None)
        return res

    se.ComprehensiveSynthesisEngine.synthesize = wrapped
    se.ComprehensiveSynthesisEngine._build_factbook = wrapped_fb


def _clear():
    for n in _CLEARED:
        getattr(ri, n)()


async def _ask(pool, q, key):
    _clear()
    set_factory_id(FID)
    async with rr.dish_catalogue_scope(pool, FID):
        spec = await ri.parse_restaurant_query(
            q, pool, factory_id=FID, session_key=key, semantic_first=True)
    return await svc.tiered_answer(q, pool, FID, ctx.role,
                                   precomputed_spec=spec, session_key=key) or {}


def _num_in(text: str, val) -> bool:
    """数字进没进正文 —— ⛔ 不按中文词计数。

    `sanitize_customer_ai_text` 把「客单价」改写成「每单平均消费」,
    按词计数会读出**反向**结论 (设计卡实测)。
    """
    if val is None:
        return False
    try:
        f = float(val)
    except (TypeError, ValueError):
        return False
    cands = {f"{f:,.1f}".rstrip("0").rstrip("."), f"{f:.1f}".rstrip("0").rstrip("."),
             f"{int(f):,}", str(int(f))}
    # 🔴 正文是给老板看的, 金额一律**折成「万」并四舍五入** ——
    #    第一版只找 `10134` 这种原值, 而正文写的是「少了1万多块」/「-1.0万」,
    #    于是「①总量差」被读成 ❌没出现。**那是仪器的错, 不是产品少了这一步。**
    #    (形态 A: 我量的数不是我真正想知道的那个数)
    a = abs(f)
    if a >= 1000:
        wan = a / 10000.0
        cands |= {f"{wan:.1f}", f"{wan:.0f}", f"{wan:.2f}".rstrip("0").rstrip(".")}
    return any(c and c in text for c in cands)


def _steps_present(text: str, rec: dict) -> dict:
    """逐步判「这一步出现在老板看到的东西里没有」。"""
    att = rec.get("attribution") or {}
    lg = att.get("laggard") or {}
    has_att = bool(att) and not att.get("no_data")
    missing = set(rec.get("missing_dims") or [])
    avail = set(rec.get("avail_dims") or [])

    out = {}
    # ① 总量差多少 —— 差额的数字进正文
    out["①总量差"] = has_att and _num_in(text, lg.get("delta_revenue"))
    # ② 量 x 价 —— 客流效应/客单价效应 任一进正文
    out["②量价分解"] = has_att and (
        _num_in(text, lg.get("avg_ticket")) or _num_in(text, att.get("chain_avg_ticket"))
        or _num_in(text, lg.get("bills")) or _num_in(text, att.get("bench_bills")))
    # ③ 客流再拆: 同比/环比有没有; 外部客流有没有数据源
    out["③客流再拆(同环比)"] = rec.get("period_comparison") is not None
    out["③外部客流数据源"] = "physical_traffic" in avail
    # ④ 落到哪几家店/菜/时段
    out["④落到店/菜/时段"] = has_att and bool(lg.get("store_name")) and (
        lg.get("store_name") in text)
    # ⑤ 反问现实事件 —— 阴性判据
    out["⑤反问现实事件"] = any(m in text for m in STEP5_ASK_MARKS)
    # ⑥ 排查方向
    out["⑥排查方向"] = (STEP6_HEAD_A in text) and (STEP6_HEAD_B in text)
    # ⑦ 排除法收口
    out["⑦排除法收口"] = STEP7_MARK in text
    return out


async def main() -> int:
    if getattr(ctx, "llm_dead_slots", None):
        print("rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2
    pool = await ctx.pool()
    _instrument()

    async def _miss(self, *a, **kw):
        return None

    NarrativeCacheService.get = _miss
    if hasattr(NarrativeCacheService, "get_semantic"):
        NarrativeCacheService.get_semantic = _miss

    print(f"清了这几个缓存: {', '.join(_CLEARED)}")
    print(f"租户={FID} 角色={ctx.role} 库={ctx.db_name} 轮数={ROUNDS}")
    print(f"⑦标记取自模块常量 se._INVESTIGATION_LAST_RESORT[:18]={STEP7_MARK!r}")

    tally = []
    for r in range(ROUNDS):
        for q in QUESTIONS:
            SEEN.clear()
            res = await _ask(pool, q, f"gap18-{r}-{q[:6]}")
            text = res.get("answer_text") or ""
            rec = next((x for x in SEEN if x.get("attribution")), None) or (
                SEEN[0] if SEEN else {})
            steps = _steps_present(text, rec) if SEEN else {}
            att = rec.get("attribution") or {}
            print("=" * 78)
            print(f"轮{r + 1} 问: {q}")
            print(f"  [来源标记] kind={res.get('kind')!r} intent={res.get('intent')!r} "
                  f"source={rec.get('source')!r} synthesize调用={len(SEEN)}次 "
                  f"attribution={'非空' if att and not att.get('no_data') else '空'} "
                  f"正文长={len(text)}")
            if att and not att.get("no_data"):
                lg = att.get("laggard") or {}
                print(f"  laggard={lg.get('store_name')!r} 主因={att.get('primary_cause')!r} "
                      f"Δ={lg.get('delta_revenue')}")
            for k, v in steps.items():
                print(f"    {k}: {'✅出现' if v else '❌没出现'}")
            print("---- 老板看到的原文 ----")
            print(text)
            print()
            tally.append((r + 1, q, res.get("kind"), len(SEEN),
                          bool(att and not att.get("no_data")), steps))

    # ── 阳性对照 ──────────────────────────────────────────────────────────
    # 🔴 `kind` 必须进有效性判据 —— **拒答/澄清也是一份「不一样的答案」**。
    #    第一版只要求 `synth>0 and att非空`, 于是一条 clarification 轮
    #    (正文里当然没有叙述) 被算进分母, 把 ⑥⑦ 读成 1/2。
    live = [t for t in tally if t[3] > 0 and t[4] and t[2] == "answer"]
    print("=" * 78)
    print(f"阳性对照: kind=answer 且 synthesize 被调用且 attribution 非空 "
          f"= {len(live)}/{len(tally)}")
    if not live:
        print("rc=2 INSTRUMENT_DEAD —— 一轮都没跑到归因, "
              "⛔ 不能把这次读数写成「这些步骤没做」")
        return 2

    print("--- 逐步汇总 (只统计上面那 %d 条有效轮次) ---" % len(live))
    keys = list(live[0][5].keys())
    missing_steps = []
    for k in keys:
        hit = sum(1 for t in live if t[5].get(k))
        print(f"  {k}: {hit}/{len(live)}")
        if hit == 0:
            missing_steps.append(k)
    print("--- 逐轮明细 (⛔ 不只贴汇总) ---")
    for r, q, kind, n, ok, steps in tally:
        flags = "".join("1" if steps.get(k) else "0" for k in keys) if steps else "-"
        print(f"  轮{r} {q[:12]:<14} kind={str(kind):<10} synth={n} att={ok} {flags}")
    print(f"  (位序: {' '.join(keys)})")

    if missing_steps:
        print("MISSING: " + ", ".join(missing_steps))
        return 1
    print("ALL_STEPS_PRESENT")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
