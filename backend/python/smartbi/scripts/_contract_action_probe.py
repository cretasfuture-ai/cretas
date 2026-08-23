# -*- coding: utf-8 -*-
"""契约的 `analysis_action` 到底还拦不拦人 —— 归因/建议型问句逐条摊开。

## 为什么要重测（⛔ 不是重做已有读数，是那份读数已经过期）

`docs/decisions/2026-08-17-追问被叙事接地闸毙掉-设计卡.md` 第九节报的
「契约 `analysis_action` 4/8」是 **2026-08-17 11:16** 的读数（commit
`a693e74c52`）。而认「诚实说明这一层做不到」的修复 `cb57be0798`（#2753）
落在**同一天 12:08** —— **晚 52 分钟**。

▎那份归因图量的是**没有 `_explicit_analysis_gap` 的代码**。
▎引用它去排优先级，等于拿修复前的读数给修复后的代码定靶心。

⇒ 本探针在**当前代码**上重测同一件事。

## 读数（每格都打，⛔ 不折叠成汇总）

    source_tier / planner_authority   来源标记 —— 没有它分不清「相等」是真的
                                      相等还是两侧本来就是同一个缓存条目
    analysis_action                   spec 上的那一格（判据的输入本身）
    required                          `required_elements(spec)` 全量
    missing                           `validate()` 的产出
    gap_fired                         `_explicit_analysis_gap(text)` 有没有开火
    text_len / text                   老板会看到的原文（⛔ 不截断到看不出问题）

## 阳性对照（⛔ 不许省）

1. `hook_calls > 0` —— 契约钩子没挂上时，「零拦截」和「没量到」是同一个读数。
2. `analysis_action in (diagnose, optimize)` 的问句数 > 0 —— 这一批问句要是
   一条都没解析成分析型，那量的根本不是 `analysis_action` 这道判据。

⚠️ 第 2 条是**跨样本**的：只在某一条问句上成立的阳性对照，证明不了仪器在
   别的问句上活着。所以按条打，不只打总数。

## 三态退出码

    0  没问题
    1  有问题（读数有效，且指向缺陷）
    2  没量到（阳性对照未过 / 样本 0 条）—— 本次读数作废

## 用法

    /tmp/runprobe.sh smartbi.scripts._contract_action_probe /tmp/ca.log
"""
from __future__ import annotations

import asyncio
import functools
import importlib.util
import json
import os
import sys

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_CONTRACT_ACTION_FID = os.environ.get("PROBE_FID", "MOCK_REST")
_CONTRACT_ACTION_ROUNDS = int(os.environ.get("PROBE_ROUNDS", "2"))
#: 每次 validate 调用的**全文**落到这里 —— 离线评判候选判据要的是真实语料，
#: ⛔ 不是我手写的桩（桩的自由度让我可以构造真实上游永不产生的形状）。
_CONTRACT_ACTION_CORPUS = os.environ.get("PROBE_CORPUS", "/tmp/ca_corpus.jsonl")

#: 🔴 **修后端到端读数用它，⛔ 不部署**。指向一份 `answer_contract.py`，
#: 在任何生产模块 import 它**之前**装进 `sys.modules` —— 于是
#: `restaurant_intent_service` 里那句 `_contract.validate(...)` 用的就是它。
#: ⚠️ 这是**同一个文件**（scp 上来的本地实现），不是第二份实现（形态 D）。
_CONTRACT_ACTION_OVERRIDE = os.environ.get("PROBE_CONTRACT_OVERRIDE", "")

_ca_ctx = bootstrap_probe(_CONTRACT_ACTION_FID)

_CA_MODNAME = "smartbi.gold.restaurant.answer_contract"
if _CONTRACT_ACTION_OVERRIDE:
    # ⛔ 必须在下面那行 import 之前 —— 之后再装就只能改到探针自己看到的那份，
    #    生产模块早已绑好了旧的（「机制在、没接上」的经典长相）。
    import smartbi.gold.restaurant as _ca_pkg  # noqa: E402
    _ca_spec = importlib.util.spec_from_file_location(
        _CA_MODNAME, _CONTRACT_ACTION_OVERRIDE)
    _ca_mod = importlib.util.module_from_spec(_ca_spec)
    sys.modules[_CA_MODNAME] = _ca_mod
    _ca_spec.loader.exec_module(_ca_mod)
    _ca_pkg.answer_contract = _ca_mod
    print("[override] answer_contract ← %s" % _CONTRACT_ACTION_OVERRIDE)

from smartbi.gold.restaurant import answer_contract as _ca_contract  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent as _ca_ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as _ca_rr  # noqa: E402
from smartbi.gold.restaurant.restaurant_intent_service import (  # noqa: E402
    should_delegate,
    tiered_answer,
)
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: 归因/建议型问句。前四条是设计卡第八节逐字记下的那四条
#: （`violations=0` 却仍然拒答的那批），其余是同族扩充。
#: ⛔ 常量名不用泛名（仓里有「同名常量漂移」闸）。
_CONTRACT_ACTION_QS = (
    "生意下滑了怎么办",
    "怎么让最差的那家店好起来",
    "客单价怎么才能提上去",
    "菜单该怎么调整",
    "为什么这个月毛利率下降了",
    "这个月营收比上个月低是什么原因",
    "哪家店的问题最大，问题出在哪",
    "损耗怎么才能降下来",
    "为什么这周的订单量少了",
    "怎么提高毛利率",
)

#: `PROBE_QS`（`|` 分隔）覆盖问句集 —— 用来拿**别的问句集**复测同一件事，
#: 例：全景重测队列里老板还拿不到数的那几条。
#: ⛔ 覆盖时报数必须把实际问句数打出来，否则两个基线的**分母不一样却被并排比**。
if os.environ.get("PROBE_QS"):
    _CONTRACT_ACTION_QS = tuple(
        q for q in os.environ["PROBE_QS"].split("|") if q.strip()
    )

#: ⛔ 用模块自带的 helper，不拼名字 —— 拼错的属性名不会报错，
#: 只会让清理**静默失效**。
_CONTRACT_ACTION_CLEARERS = (
    "clear_semantic_plan_cache",
    "clear_route_cache",
    "clear_tenant_gate_cache",
    "clear_promoted_routes_cache",
)

#: 契约钩子收到的每一次调用。阳性对照 1 就靠它。
_CONTRACT_ACTION_CALLS: list = []


def clear_caches() -> tuple:
    for name in _CONTRACT_ACTION_CLEARERS:
        getattr(_ca_ri, name)()
    return _CONTRACT_ACTION_CLEARERS


def install_contract_hook() -> None:
    """钩 `answer_contract.validate`，只记录、⛔ 不改判定。

    ⚠️ 生产侧是 `from ... import answer_contract as _contract` 然后
       `_contract.validate(...)` —— **模块属性查找发生在调用时**，
       所以打模块属性够得着它。（若那边写的是 `from ... import validate`
       就打不到 —— 那正是本仓 `test_mutation_targets_are_reachable` 守的东西。）
    """
    original = _ca_contract.validate

    @functools.wraps(original)
    def _spy(spec, answer_text, kpis=None, meta=None):
        result = original(spec, answer_text, kpis, meta)
        text = answer_text or ""
        _CONTRACT_ACTION_CALLS.append({
            "intent": (getattr(spec, "intent", None) or "∅"),
            "analysis_action": getattr(spec, "analysis_action", None),
            "source_tier": getattr(spec, "source_tier", None),
            "planner_authority": getattr(spec, "planner_authority", None),
            "required": list(_ca_contract.required_elements(spec)),
            "missing": list(result.missing),
            "gap_fired": _ca_contract._explicit_analysis_gap(text),
            "action_ok": _ca_contract._analysis_action_present(spec, text),
            "text_len": len(text),
            "text": text,
        })
        # 每条读数**落全文**到语料 —— 离线评判候选判据靠它。
        # ⛔ 不靠打印里的首 300 字（形态 A⁗：截断会把「没有」变成「我没看到」）。
        try:
            with open(_CONTRACT_ACTION_CORPUS, "a", encoding="utf-8",
                      newline="") as fh:
                fh.write(json.dumps(_CONTRACT_ACTION_CALLS[-1],
                                    ensure_ascii=False) + "\n")
        except Exception:  # noqa: BLE001 — 落盘坏了不该连累读数
            pass
        return result

    _ca_contract.validate = _spy


def _fmt(v) -> str:
    if v is None:
        return "∅"
    if isinstance(v, (list, tuple)):
        return ",".join(str(x) for x in v) or "∅"
    return str(v)


async def run_one(pool, query: str, session_key: str) -> dict:
    _CONTRACT_ACTION_CALLS.clear()
    set_factory_id(_CONTRACT_ACTION_FID)
    async with _ca_rr.dish_catalogue_scope(pool, _CONTRACT_ACTION_FID):
        catalogue = _ca_rr.current_dish_catalogue()
        spec = await _ca_ri.parse_restaurant_query(
            query, pool, factory_id=_CONTRACT_ACTION_FID,
            session_key=session_key, semantic_first=True,
        )
    row = {
        "q": query,
        "catalogue_n": len(catalogue) if catalogue else 0,
    }
    if spec is None:
        return row | {"kind": "spec-None", "calls": [], "text": ""}
    row |= {
        "intent": (spec.intent or "∅").replace("RESTAURANT_OPS_", ""),
        "analysis_action": spec.analysis_action,
        "source_tier": spec.source_tier,
        "planner_authority": spec.planner_authority,
        "dims": list(spec.dimensions),
        "clarification_needed": spec.clarification_needed,
    }
    if not should_delegate(spec, None, query=query):
        return row | {"kind": "no-delegate", "calls": [], "text": ""}
    result = await tiered_answer(
        query, pool, _CONTRACT_ACTION_FID, _ca_ctx.role,
        precomputed_spec=spec, session_key=session_key,
    )
    calls = list(_CONTRACT_ACTION_CALLS)
    if not result:
        return row | {"kind": "None", "calls": calls, "text": ""}
    return row | {
        "kind": result.get("kind") or "-",
        "contract_pass": result.get("contract_pass"),
        "served_by": result.get("served_by"),
        "calls": calls,
        "text": result.get("answer_text") or "",
    }


def emit(idx: int, rnd: int, row: dict) -> None:
    print("\n" + "=" * 78)
    print("Q%02d r%d  %s" % (idx + 1, rnd, row["q"]))
    print("  spec   intent=%-24s analysis_action=%s"
          % (_fmt(row.get("intent")), _fmt(row.get("analysis_action"))))
    print("  来源   source_tier=%-14s planner_authority=%s"
          % (_fmt(row.get("source_tier")), _fmt(row.get("planner_authority"))))
    print("  最终   kind=%-14s contract_pass=%-6s served_by=%s"
          % (_fmt(row.get("kind")), _fmt(row.get("contract_pass")),
             _fmt(row.get("served_by"))))
    print("         dims=%-18s clarification_needed=%s"
          % (_fmt(row.get("dims")), _fmt(row.get("clarification_needed"))))
    calls = row.get("calls") or []
    print("  契约调用=%d" % len(calls))
    for ci, call in enumerate(calls):
        print("    [%d] required=%s" % (ci, _fmt(call["required"])))
        print("        missing =%s" % _fmt(call["missing"]))
        print("        analysis_action=%-10s gap_fired=%-6s action_ok=%-6s len=%d"
              % (_fmt(call["analysis_action"]), call["gap_fired"],
                 call["action_ok"], call["text_len"]))
        print("        被校验的文本(首 300 字): %s"
              % (call["text"][:300].replace("\n", " ⏎ ") or "(空)"))
    print("  老板看到(首 300 字): %s"
          % ((row.get("text") or "")[:300].replace("\n", " ⏎ ") or "(空)"))


async def main() -> int:
    install_contract_hook()
    pool = await _ca_ctx.pool()

    rows: list = []
    for rnd in range(1, _CONTRACT_ACTION_ROUNDS + 1):
        cleared = clear_caches()
        print("\n### 第 %d 轮 —— 已清缓存: %s" % (rnd, ", ".join(cleared)))
        for idx, q in enumerate(_CONTRACT_ACTION_QS):
            try:
                row = await run_one(pool, q, session_key="ca-r%d-q%d" % (rnd, idx))
            except Exception as exc:  # noqa: BLE001
                # ⛔ 不 continue —— 失败要计数并逐条贴，它常是信息量最大的样本。
                row = {"q": q, "kind": "EXC:%s: %s" % (type(exc).__name__, exc),
                       "calls": [], "text": ""}
            row["idx"] = idx
            row["rnd"] = rnd
            rows.append(row)
            emit(idx, rnd, row)

    # ── 阳性对照 ────────────────────────────────────────────────────────
    total_calls = sum(len(r.get("calls") or []) for r in rows)
    analysis_specs = [r for r in rows
                      if r.get("analysis_action") in ("diagnose", "optimize")]
    catalogue_ok = any((r.get("catalogue_n") or 0) > 0 for r in rows)

    print("\n" + "=" * 78)
    print("阳性对照")
    print("  ① 契约钩子调用总数 = %d  (0 ⇒ 钩子没挂上, 读数作废)" % total_calls)
    print("  ② analysis_action∈(diagnose,optimize) 的样本 = %d/%d"
          % (len(analysis_specs), len(rows)))
    print("     逐条: %s" % ", ".join(
        "Q%02d r%d=%s" % (r["idx"] + 1, r["rnd"], _fmt(r.get("analysis_action")))
        for r in rows if "idx" in r))
    print("  ③ 菜单目录 > 0 条 = %s" % catalogue_ok)

    if total_calls == 0 or not analysis_specs or not catalogue_ok:
        print("\nrc=2 没量到 —— 阳性对照未通过, 本次读数作废")
        return 2

    # ── 归因汇总（口径写在标题里）───────────────────────────────────────
    print("\n" + "=" * 78)
    print("拒答归因（租户=%s / 问句=%d 条 / 轮数=%d / 样本=%d）"
          % (_CONTRACT_ACTION_FID, len(_CONTRACT_ACTION_QS),
             _CONTRACT_ACTION_ROUNDS, len(rows)))

    buckets = {"作答": 0, "契约·含analysis_action": 0, "契约·不含analysis_action": 0,
               "规划器反问(clarification_needed)": 0, "未走到契约": 0, "异常": 0}
    for r in rows:
        kind = str(r.get("kind") or "")
        if kind.startswith("EXC:"):
            buckets["异常"] += 1
            continue
        calls = r.get("calls") or []
        missing_all = [m for c in calls for m in c["missing"]]
        if kind == "answer":
            buckets["作答"] += 1
        elif r.get("clarification_needed") is True and not calls:
            buckets["规划器反问(clarification_needed)"] += 1
        elif not calls:
            buckets["未走到契约"] += 1
        elif "analysis_action" in missing_all:
            buckets["契约·含analysis_action"] += 1
        else:
            buckets["契约·不含analysis_action"] += 1
    for k, v in buckets.items():
        print("  %-34s %d/%d" % (k, v, len(rows)))

    # 缺项直方图
    hist: dict = {}
    for r in rows:
        for c in (r.get("calls") or []):
            for m in c["missing"]:
                hist[m] = hist.get(m, 0) + 1
    print("\n  契约缺项直方图（按 validate 调用计，非按问句）:")
    for k in sorted(hist, key=lambda x: -hist[x]):
        print("    %-26s %d" % (k, hist[k]))

    # gap_fired 分布 —— 修复 cb57be0798 到底救回来多少
    gap_rows = [c for r in rows for c in (r.get("calls") or [])
                if c["analysis_action"] in ("diagnose", "optimize")]
    fired = [c for c in gap_rows if c["gap_fired"]]
    print("\n  `_explicit_analysis_gap` 开火率: %d/%d 次 validate"
          % (len(fired), len(gap_rows)))
    blocked = [c for c in gap_rows if "analysis_action" in c["missing"]]
    print("  仍被 analysis_action 拦下: %d/%d 次 validate"
          % (len(blocked), len(gap_rows)))
    print("\n  被 analysis_action 拦下的文本逐条（⛔ 不折叠）:")
    for c in blocked:
        print("    - action=%s len=%d gap_fired=%s"
              % (c["analysis_action"], c["text_len"], c["gap_fired"]))
        print("      %s" % (c["text"][:400].replace("\n", " ⏎ ") or "(空)"))

    return 1 if blocked else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
