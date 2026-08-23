# -*- coding: utf-8 -*-
"""同一句式「X卖得怎么样」，LLM 为什么对不同菜名给出**不同的时间范围**。

## 订正的起点（外部审计 6 轮 × 4 句，零抖动）

    米饭 / 罗氏虾 / 藤椒鸡   6/6 反问   clarify=True   win='全部历史'
    水煮牛肉                 6/6 答上   clarify=False  win='最近30天'

⇒ ⛔ **不是「模型抖动」** —— 稳定到 6/6。
⇒ ⛔ **不是「有默认值却仍反问」那条红线** —— 红线管的是「产品有默认却去问」，
     这里是 **LLM 没给时间概念**，产品据此反问是合规的。
⇒ 真正要问的是：**同一句式，LLM 凭什么对水煮牛肉给「最近30天」、对另外三道给「全部历史」？**

⚠️ 我上一轮的探针 `_q08_shape` **没打 `time_phrase` / `window_label` 那一格** ——
   那正是关键。这份补上。

## 钩两层（⛔ 缺一层就分不清是谁给的）

    _t3_llm_parse   →  LLM **原始 dict**（含 `time_range`）—— 模型到底说了什么
    _build_spec     →  `time_phrase` 入参 —— 提取之后传给编译的是什么
    最终 spec       →  `window_label` / `date_range` —— 老板实际被算的窗口

三层不一致时，差在哪一层一眼可见。

## ⚠️ session_key 每次唯一

`restaurant_pending_clarifications` 是 **Postgres 表、跨进程存活**。
复用 key 会让第二次跑把同一句话当成「用户在回答上一次的反问」。
📏 `restaurant_intent.py:5044-5058` 自己记着「米饭卖得怎么样」注册了一个
**合法**的槽位反问 pending，且 pending 在累积。
⇒ 本探针的 key 带 `PROBE_RUN_ID`（pid + 时间戳），⛔ 不跨次复用。

## 阳性/阴性对照（⛔ 不许省）

- 阳性：菜单目录 ≥ 4 条；`_t3_llm_parse` 钩子调用数 > 0；
        `水煮牛肉` 至少一轮 `clarify=False`（它是审计里稳定答上的那句 ——
        它要是也反问，说明我的仪器与审计不在同一条件下）
- 阴性：一个**必然不在菜单**的名字（佛跳墙）单列，看它落哪一档
"""
from __future__ import annotations

import asyncio
import functools
import json
import os
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_Q08TR_FID = "MOCK_REST"
_Q08TR_ROUNDS = 6
#: ⛔ 每次跑唯一 —— pending clarification 是跨进程存活的 Postgres 表。
_Q08TR_RUN_ID = "%d-%d" % (os.getpid(), int(time.time()))
ctx = bootstrap_probe(_Q08TR_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: 审计那 4 句 + 1 条阴性对照。⛔ 句式**逐字相同**，只换菜名 ——
#: 这一格是本探针的全部要害：句式是常量，菜名是变量。
_Q08TR_DISHES = ["米饭", "罗氏虾", "藤椒鸡", "水煮牛肉", "佛跳墙"]

_Q08TR_T3: list = []
_Q08TR_SPEC: list = []


def _install():
    orig_t3 = ri._t3_llm_parse
    orig_build = ri._build_spec

    @functools.wraps(orig_t3)
    async def _spy_t3(query, **kw):
        out = await orig_t3(query, **kw)
        _Q08TR_T3.append(out)
        return out

    @functools.wraps(orig_build)
    def _spy_build(code, query, **kw):
        _Q08TR_SPEC.append({
            "code": code,
            "time_phrase": kw.get("time_phrase"),
            "require_explicit_time": kw.get("require_explicit_time"),
            "allow_time_default": kw.get("allow_time_default"),
            "clarification_needed": kw.get("clarification_needed"),
            "llm_dish": kw.get("llm_dish"),
        })
        return orig_build(code, query, **kw)

    ri._t3_llm_parse = _spy_t3
    ri._build_spec = _spy_build


def _clear() -> list[str]:
    done = []
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, n)()
        done.append(n)
    return done


def _fmt(v):
    return "()" if v in (None, (), [], "") else repr(v)


def _t3_time_range(raw):
    """LLM 原始 dict 里的 `time_range` —— ⛔ 原样取，不加工。"""
    if not isinstance(raw, dict):
        return "<非 dict:%s>" % type(raw).__name__
    return raw.get("time_range", "<缺这个键>")


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s 轮数=%d RUN_ID=%s"
          % (ctx.db_name, _Q08TR_FID, _Q08TR_ROUNDS, _Q08TR_RUN_ID))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2

    set_factory_id(_Q08TR_FID)
    async with rr.dish_catalogue_scope(pool, _Q08TR_FID):
        cat = tuple(rr.current_dish_catalogue() or ())
    print("阳性对照·菜单目录 = %d 条" % len(cat))
    if len(cat) < 4:
        print("⛔ rc=2 菜单目录不足 4 条，读数作废")
        return 2
    for d in _Q08TR_DISHES:
        print("  前提·「%s」在菜单里 = %s" % (d, d in cat))

    _install()
    rows = []
    t3_calls = 0
    for rnd in range(1, _Q08TR_ROUNDS + 1):
        for dish in _Q08TR_DISHES:
            q = "%s卖得怎么样" % dish
            cleared = _clear()
            _Q08TR_T3.clear()
            _Q08TR_SPEC.clear()
            skey = "q08tr-%s-r%d-%s" % (_Q08TR_RUN_ID, rnd, dish)
            set_factory_id(_Q08TR_FID)
            async with rr.dish_catalogue_scope(pool, _Q08TR_FID):
                spec = await ri.parse_restaurant_query(
                    q, pool, factory_id=_Q08TR_FID,
                    session_key=skey, semantic_first=True)
                res = None
                if spec is not None and svc.should_delegate(spec, None, query=q):
                    res = await svc.tiered_answer(
                        q, pool, _Q08TR_FID, ctx.role,
                        precomputed_spec=spec, session_key=skey)
            t3_calls += len(_Q08TR_T3)
            kind = (res or {}).get("kind") or "-"
            t3_tr = _t3_time_range(_Q08TR_T3[0]) if _Q08TR_T3 else "<钩子没拿到>"
            sp = _Q08TR_SPEC[0] if _Q08TR_SPEC else {}
            rows.append((
                rnd, dish, kind,
                json.dumps(t3_tr, ensure_ascii=False) if not isinstance(t3_tr, str) else t3_tr,
                sp.get("time_phrase"),
                getattr(spec, "window_label", None) if spec else None,
                getattr(spec, "clarification_needed", None) if spec else None,
                # 🔴 LLM 选的 resolver —— 第一版漏了这一列, 而它才是差异的候选源头:
                #    不同 resolver 的默认时间窗口不同(成本卡是当前状态, 没有时间序列)。
                sp.get("code"),
                getattr(spec, "intent", None) if spec else None,
            ))

    print("\n" + "=" * 96)
    print("逐行（⛔ 不折叠）—— 三层：LLM 原始 time_range / 传给编译的 time_phrase / 最终窗口")
    print("%-3s %-6s %-14s %-10s %-10s %-24s %-22s %s"
          % ("r", "菜", "kind", "LLMtr", "phrase", "LLM 选的 code", "最终 intent", "window/clarify"))
    for (rnd, dish, kind, t3tr, tp, win, clar, code, intent) in rows:
        print("%-3s %-6s %-14s %-10s %-10s %-24s %-22s %s"
              % (rnd, dish, kind, str(t3tr)[:10], _fmt(tp)[:10],
                 (code or "").replace("RESTAURANT_OPS_", "")[:24],
                 (intent or "").replace("RESTAURANT_OPS_", "")[:22],
                 "%s/%s" % (_fmt(win), clar)))

    print("\n" + "=" * 96)
    print("按菜名汇总")
    print("%-8s %-10s %-10s %s" % ("菜", "答上", "反问", "LLM time_range 取值集合"))
    for dish in _Q08TR_DISHES:
        sub = [r for r in rows if r[1] == dish]
        ans = sum(1 for r in sub if r[2] == "answer")
        clr = sum(1 for r in sub if r[2] == "clarification")
        trs = sorted({str(r[3]) for r in sub})
        # 🔴 索引订正：rows 是 (rnd,dish,kind,t3tr,tp,win,clar,**code**,intent)
        #    第一版这里写 `r[8]` —— 取到的是**最终 intent**（全被 contract-repair
        #    改成 GROSS_MARGIN），于是汇总列显示「所有菜名 code 都一样」，
        #    而逐行表明明写着 RECIPE_COST。
        #    ▎同一份数据两个相反的读数，错的是索引不是数据。
        codes = sorted({(r[7] or "").replace("RESTAURANT_OPS_", "") for r in sub})
        intents = sorted({(r[8] or "").replace("RESTAURANT_OPS_", "") for r in sub})
        wins = sorted({str(r[5]) for r in sub})
        print("%-8s %-8s %-8s tr=%-6s LLM选=%-26s →intent=%-16s win=%s"
              % (dish, "%d/%d" % (ans, len(sub)), "%d/%d" % (clr, len(sub)),
                 "|".join(t[:6] for t in trs), "|".join(codes),
                 "|".join(intents), "|".join(wins)))

    print("\n阳性对照·_t3_llm_parse 钩子调用 = %d 次" % t3_calls)
    if t3_calls == 0:
        print("⛔ rc=2 钩子没挂上 —— LLM time_range 那一列全是假的，读数作废")
        return 2
    beef = [r for r in rows if r[1] == "水煮牛肉"]
    beef_ok = sum(1 for r in beef if r[6] is False)
    print("阳性对照·水煮牛肉 clarify=False = %d/%d 轮（审计读数是 6/6）"
          % (beef_ok, len(beef)))
    if beef_ok == 0:
        print("⛔ rc=2 审计里稳定答上的那句在我这儿一次都没答上 —— "
              "我和审计不在同一条件下，读数不可比")
        return 2

    print("\n🔑 判据：LLM 的 time_range 在**菜名之间**是不是稳定不同")
    per = {d: sorted({str(r[3]) for r in rows if r[1] == d}) for d in _Q08TR_DISHES}
    stable = all(len(v) == 1 for v in per.values())
    print("  每个菜名各自的 time_range 取值数: %s"
          % {d: len(v) for d, v in per.items()})
    if stable and len({v[0] for v in per.values()}) > 1:
        print("  ⇒ 🔴 **稳定且不同** —— 这是 LLM 对菜名的确定性差异，⛔ 不是抖动")
    elif stable:
        print("  ⇒ 稳定且相同 —— 差异在 time_range 之外的别处")
    else:
        print("  ⇒ 有菜名自身在抖 —— ⛔ 别下「稳定不同」的结论，逐行看上表")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
