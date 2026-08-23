# -*- coding: utf-8 -*-
"""最高级指代（「最差的那家店」）到底被谁当成了没指定门店。

## 被测的那件事

老板说「怎么让**最差的那家店**好起来」—— 他**已经指定**了是哪家（按某个指标
排最后的那家）。产品反问「是指哪家店？」。

## 这个探针要回答的唯一问题（⛔ 从代码推不出来，两种可能跑的是同一段代码）

▎**「最差的那家店」是被 LLM 当成了店名／没给出 store 槽，
▎还是被确定性抽取器（`extract_store_mentions`）当成了店名？**

  LLM 侧  ⇒ 修法在 prompt / 契约（LLM 该输出「这是一个计算式指代」）
  代码侧  ⇒ 修法在确定性层（先算 laggard 再核对）

姊妹缺陷（「有几家店是亏钱的」）实测是**确定性抽取器**干的，`llm_store=None`。
⛔ 不假设这次也一样 —— 这个探针就是去分辨它。

## 读数（逐条打，⛔ 不只打汇总）

    llm_code / llm_dimensions / llm_store / llm_stores / llm_store_scope
    det_mentions      `extract_store_mentions(q)` —— 确定性抽取器**独立**跑一次
    det_scope         `_detect_store_scope(q)`
    最终 intent / dimensions / store_scope / store_slots / store_slot
    clarification_needed / missing_slot / clarification_question
    kind + 老板看到的原文首段

## 阳性/阴性对照（⛔ 不许省）

- 阳性①：钩子调用数 > 0（否则读数是「钩子没挂上」不是「LLM 没给 store」）
- 阳性②：菜单目录非空（执行链起得来）
- 阳性③：`extract_store_mentions("陆家嘴店的营收")` 必须抽得到东西
          —— 这台仪器读出「全无」时，要能分清「真的没有」和「抽取器坏了」
- 阴性 ：至少一句 `clarification_needed=True`（否则「全都答上了」和
          「整条链没跑到反问那一步」是同一个读数）
"""
from __future__ import annotations

import asyncio
import functools

from smartbi.scripts._probe_bootstrap import bootstrap_probe

SUPERLATIVE_FID = "MOCK_REST"
SUPERLATIVE_ROUNDS = 3
sup_ctx = bootstrap_probe(SUPERLATIVE_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: S = 含最高级指代（老板**已经指定**了门店，只是用计算式指的）
#: C = 对照组：真的没指定门店（反问是**对的**，⛔ 不许被本轮修复压掉）
SUPERLATIVE_QS = [
    ("S", "怎么让最差的那家店好起来"),
    ("S", "我要不要关掉最差的那家店"),
    ("S", "最好的那家店做对了什么"),
    ("S", "表现最好的门店是靠什么"),
    ("S", "垫底那家怎么救"),
    ("S", "最差的那家店问题出在哪"),
    ("S", "生意最差的那家店最近30天怎么样"),
    ("S", "拖后腿的那家店是哪家"),
    ("C", "生意下滑了怎么办"),
    ("C", "怎么提升营收"),
]

SUPERLATIVE_CALLS: list[dict] = []
#: `_semantic_spec_from_t3` 收到的**模型原始 JSON** —— `missing_fields` 只在这里看得到,
#: 它到不了 `_build_spec`。⛔ 少这一格就分不清「模型说缺门店」和「下游闸自己问的」。
SUPERLATIVE_PARSED: list[dict] = []


def _install_spy():
    orig = ri._build_spec

    @functools.wraps(orig)
    def _spy(code, query, **kw):
        SUPERLATIVE_CALLS.append({
            "code": code,
            "llm_dimensions": kw.get("llm_dimensions"),
            "llm_store": kw.get("llm_store"),
            "llm_stores": kw.get("llm_stores"),
            "llm_store_scope": kw.get("llm_store_scope"),
        })
        return orig(code, query, **kw)

    ri._build_spec = _spy

    orig_t3 = ri._semantic_spec_from_t3

    @functools.wraps(orig_t3)
    def _t3_spy(parsed, query, **kw):
        SUPERLATIVE_PARSED.append({
            "missing_fields": parsed.get("missing_fields"),
            "clarification_needed": parsed.get("clarification_needed"),
            "store_scope": parsed.get("store_scope"),
            "dimensions": parsed.get("dimensions"),
            "store": parsed.get("store"),
            "available_stores": len(kw.get("available_stores") or ()),
        })
        return orig_t3(parsed, query, **kw)

    ri._semantic_spec_from_t3 = _t3_spy


def _clear_caches() -> list[str]:
    done = []
    for name in ("clear_semantic_plan_cache", "clear_route_cache",
                 "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, name)()
        done.append(name)
    return done


def _fmt(v):
    return "()" if v in (None, (), [], "") else repr(v)


async def main() -> int:
    pool = await sup_ctx.pool()
    print("PROBE_DB=%s FID=%s 轮数=%d 句数=%d" % (
        sup_ctx.db_name, SUPERLATIVE_FID, SUPERLATIVE_ROUNDS, len(SUPERLATIVE_QS)))
    if getattr(sup_ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(sup_ctx.llm_dead_slots))
        return 2

    # 阳性对照③ —— 这台仪器读出「全无」时要分得清「真没有」和「它坏了」
    ctrl_mentions = rr.extract_store_mentions("陆家嘴店最近30天的营收")
    print("阳性对照·extract_store_mentions('陆家嘴店最近30天的营收') = %s" % ctrl_mentions)
    if not ctrl_mentions:
        print("⛔ rc=2 确定性抽取器对一个明确店名也读出空 —— 仪器坏了")
        return 2

    set_factory_id(SUPERLATIVE_FID)
    async with rr.dish_catalogue_scope(pool, SUPERLATIVE_FID):
        cat = rr.current_dish_catalogue()
    print("阳性对照·菜单目录 = %d 条" % len(cat or ()))
    if not cat:
        print("⛔ rc=2 菜单目录为 0")
        return 2

    _install_spy()
    hook_calls = 0
    clar_any = 0
    rows = []

    for rnd in range(1, SUPERLATIVE_ROUNDS + 1):
        for grp, q in SUPERLATIVE_QS:
            cleared = _clear_caches()
            SUPERLATIVE_CALLS.clear()
            SUPERLATIVE_PARSED.clear()
            set_factory_id(SUPERLATIVE_FID)
            session = "sup-r%d-%s" % (rnd, q)
            async with rr.dish_catalogue_scope(pool, SUPERLATIVE_FID):
                spec = await ri.parse_restaurant_query(
                    q, pool, factory_id=SUPERLATIVE_FID,
                    session_key=session, semantic_first=True)
            res = None
            if spec is not None and svc.should_delegate(spec, None, query=q):
                res = await svc.tiered_answer(
                    q, pool, SUPERLATIVE_FID, sup_ctx.role,
                    precomputed_spec=spec, session_key=session)
            kind = (res or {}).get("kind") or "-"
            text = (res or {}).get("answer_text") or ""
            hook_calls += len(SUPERLATIVE_CALLS)

            first = SUPERLATIVE_CALLS[0] if SUPERLATIVE_CALLS else {}
            praw = SUPERLATIVE_PARSED[0] if SUPERLATIVE_PARSED else {}
            det_mentions = tuple(rr.extract_store_mentions(q))
            det_scope, det_scope_mentions = ri._detect_store_scope(q)

            clar = bool(getattr(spec, "clarification_needed", False)) if spec else False
            if clar:
                clar_any += 1

            row = {
                "rnd": rnd, "grp": grp, "q": q,
                "llm_code": first.get("code"),
                "llm_dims": (tuple(first["llm_dimensions"])
                             if first.get("llm_dimensions") is not None else None),
                "llm_store": first.get("llm_store"),
                "llm_stores": first.get("llm_stores"),
                "llm_store_scope": first.get("llm_store_scope"),
                "det_mentions": det_mentions,
                "det_scope": det_scope,
                "intent": getattr(spec, "intent", None) if spec else None,
                "dims": tuple(getattr(spec, "dimensions", ()) or ()) if spec else (),
                "store_scope": getattr(spec, "store_scope", None) if spec else None,
                "store_slots": tuple(getattr(spec, "store_slots", ()) or ()) if spec else (),
                "store_slot": getattr(spec, "store_slot", None) if spec else None,
                "clar": clar,
                "missing_slot": getattr(spec, "missing_slot", None) if spec else None,
                "clar_q": getattr(spec, "clarification_question", None) if spec else None,
                "authority": getattr(spec, "planner_authority", None) if spec else None,
                "kind": kind, "n": len(text),
                "raw_missing": (tuple(praw["missing_fields"])
                                if praw.get("missing_fields") is not None else None),
                "raw_clar": praw.get("clarification_needed"),
                "raw_scope": praw.get("store_scope"),
                "raw_dims": (tuple(praw["dimensions"])
                             if praw.get("dimensions") is not None else None),
                "n_stores": praw.get("available_stores"),
            }
            rows.append(row)

            print("\n" + "=" * 78)
            print("r%d [%s] %s" % (rnd, grp, q))
            print("  清了: %s   钩子 %d 次" % ("、".join(cleared), len(SUPERLATIVE_CALLS)))
            print("  --- LLM 原始输出 ---")
            print("  llm_code            = %s" % _fmt(row["llm_code"]))
            print("  llm_dimensions      = %s" % _fmt(row["llm_dims"]))
            print("  llm_store           = %s" % _fmt(row["llm_store"]))
            print("  llm_stores          = %s" % _fmt(row["llm_stores"]))
            print("  llm_store_scope     = %s" % _fmt(row["llm_store_scope"]))
            print("  --- 模型原始 JSON（_semantic_spec_from_t3 的入参）---")
            print("  raw dimensions      = %s" % _fmt(row["raw_dims"]))
            print("  raw store_scope     = %s" % _fmt(row["raw_scope"]))
            print("  raw clarification_needed = %s" % _fmt(row["raw_clar"]))
            print("  🔑 raw missing_fields = %s" % _fmt(row["raw_missing"]))
            print("  available_stores 条数 = %s" % _fmt(row["n_stores"]))
            print("  --- 确定性抽取器（独立跑一次）---")
            print("  extract_store_mentions = %s" % _fmt(row["det_mentions"]))
            print("  _detect_store_scope    = %s / %s" % (_fmt(det_scope), _fmt(det_scope_mentions)))
            print("  --- 最终 spec ---")
            print("  intent              = %s" % _fmt(row["intent"]))
            print("  dimensions          = %s" % _fmt(row["dims"]))
            print("  store_scope         = %s" % _fmt(row["store_scope"]))
            print("  store_slots         = %s" % _fmt(row["store_slots"]))
            print("  store_slot          = %s" % _fmt(row["store_slot"]))
            print("  planner_authority   = %s" % _fmt(row["authority"]))
            print("  🔴 clarification_needed = %s  missing_slot=%s"
                  % (clar, _fmt(row["missing_slot"])))
            print("  clarification_question = %s" % _fmt(row["clar_q"]))
            print("  kind=%s n=%d" % (kind, len(text)))
            print("  老板看到（首 200 字）: %s" % text[:200].replace("\n", " ⏎ "))

    print("\n" + "=" * 78)
    print("汇总（⛔ 逐行，不折叠）")
    print("%-3s %-3s %-20s %-12s %-24s %-8s %-6s %s" % (
        "r", "组", "问句", "raw_dims", "raw_missing", "scope", "clar", "kind"))
    for r in rows:
        print("%-3s %-3s %-20s %-12s %-24s %-8s %-6s %s" % (
            r["rnd"], r["grp"], r["q"][:20], _fmt(r["raw_dims"])[:12],
            _fmt(r["raw_missing"])[:24], _fmt(r["store_scope"])[:8],
            "🔴是" if r["clar"] else "-", r["kind"]))

    print("\n" + "-" * 78)
    print("裁定读数（⛔ 带口径）")
    s_rows = [r for r in rows if r["grp"] == "S"]
    c_rows = [r for r in rows if r["grp"] == "C"]
    by_llm = sum(1 for r in s_rows if r["llm_store"] or r["llm_stores"])
    by_det = sum(1 for r in s_rows if r["det_mentions"])
    print("S 组（最高级指代）%d 次：LLM 给出店名 %d 次 / 确定性抽取器抽出店名 %d 次"
          % (len(s_rows), by_llm, by_det))
    print("S 组 clarification_needed = %d/%d" % (sum(1 for r in s_rows if r["clar"]), len(s_rows)))
    print("C 组（真没指定，反问是对的）clarification_needed = %d/%d"
          % (sum(1 for r in c_rows if r["clar"]), len(c_rows)))
    print("S 组 kind=answer = %d/%d" % (sum(1 for r in s_rows if r["kind"] == "answer"), len(s_rows)))

    print("\n阳性对照·钩子总调用 = %d" % hook_calls)
    if hook_calls == 0:
        print("⛔ rc=2 钩子没挂上，读数作废")
        return 2
    print("阴性对照·clarification_needed 出现 = %d 次（0 次 ⇒ 分不清"
          "「全答上了」和「没跑到反问那一步」）" % clar_any)
    if clar_any == 0:
        print("⛔ rc=2 一次反问都没出现")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
