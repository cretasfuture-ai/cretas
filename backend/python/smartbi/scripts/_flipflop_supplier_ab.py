# -*- coding: utf-8 -*-
"""A/B：老板收到的到底是那句假话，还是那个数。

## 为什么不能用「再跑几轮全景」来验收这次改动

本轮实测（2026-08-19，MOCK_REST，prod）：翻面率**不平稳** ——
同一棵树、同一批问句，一小时内从「8/36 条反问」掉到「连着四轮 0/48」。
▎在低抖动窗口里做 A/B，**任何改动都会显示成「修好了」。**

⇒ 验收判据必须**不依赖那个窗口**：把 prod 实测抓到的那份模型输出**原样喂进去**，
  两棵树各跑一次完整的产品出口（`_semantic_spec_from_t3` → `tiered_answer`），
  比老板最后读到的那段话。

## 口径

- 两棵树只差 `restaurant_intent.py` 一个文件（跑之前 `diff -rq` 证明）
- 同一个 8083 进程的 environ、同一个 venv 解释器、同一个租户、同一份输入
- 输入 = `_flipflop_layers` 在 prod 抓到的 L0 原文（`dimensions: ["supplier"]`）
- 阳性对照 = 同一份输入把 `dimensions` 换成 `[]`（模型自己另一种输出），
  两棵树都必须作答；它证明「这条链在这个进程里跑得动」，
  ⛔ 否则 base 那侧的拒答分不清是缺陷还是探针起不来

## 用法（每棵树各跑一次）

    PYTHONPATH=<树>:<树>/smartbi <venv>/bin/python -m smartbi.scripts._flipflop_supplier_ab
"""
from __future__ import annotations

import asyncio
import hashlib
import os

from smartbi.scripts._probe_bootstrap import (
    RESTAURANT_TENANTS_WITH_DATA,
    bootstrap_probe,
)

SUPPLIER_AB_FID = os.environ.get("PROBE_FID", RESTAURANT_TENANTS_WITH_DATA[0])
supplier_ab_ctx = bootstrap_probe(SUPPLIER_AB_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

SUPPLIER_AB_QUERY = "哪个供应商报价最贵"

#: prod 实测抓到的模型输出，逐字。⛔ 不手搓一份「差不多的」。
SUPPLIER_AB_PLAN = {
    "intent": "RESTAURANT_OPS_SUPPLIER_PRICE",
    "time_range": None,
    "wants_margin": False,
    "asks_profitability": False,
    "requested_metrics": [],
    "analysis_action": "lookup",
    "aggregation": "extremes",
    "dimensions": ["supplier"],
    "comparison": None,
    "dish": None,
    "store": None,
    "stores": [],
    "store_scope": None,
    "confidence": 0.95,
    "clarification_needed": False,
    "missing_fields": [],
    "clarification_question": None,
    "clarification_options": [],
}

#: 阳性对照：模型自己**另一种**输出。两棵树都必须作答。
SUPPLIER_AB_CONTROL_DIMENSIONS = []


def _fp(text: str) -> str:
    return hashlib.md5((text or "").encode("utf-8")).hexdigest()[:8]


async def _one(pool, dimensions, label: str) -> dict:
    plan = dict(SUPPLIER_AB_PLAN)
    plan["dimensions"] = list(dimensions)
    set_factory_id(SUPPLIER_AB_FID)
    async with rr.dish_catalogue_scope(pool, SUPPLIER_AB_FID):
        spec = ri._semantic_spec_from_t3(plan, SUPPLIER_AB_QUERY)
        result = None
        if spec is not None and svc.should_delegate(spec, None,
                                                    query=SUPPLIER_AB_QUERY):
            result = await svc.tiered_answer(
                SUPPLIER_AB_QUERY, pool, SUPPLIER_AB_FID, supplier_ab_ctx.role,
                precomputed_spec=spec,
                session_key="ff-ab-%s-%s" % (label, _fp(str(dimensions))),
            )
    text = (result or {}).get("answer_text") or ""
    return {
        "label": label,
        "dimensions_in": tuple(dimensions),
        "authority": getattr(spec, "planner_authority", None) if spec else None,
        "intent": getattr(spec, "intent", None) if spec else None,
        "dims_out": tuple(getattr(spec, "dimensions", ()) or ()) if spec else (),
        "plan_hash": getattr(spec, "plan_hash", None) if spec else None,
        "kind": (result or {}).get("kind") or "-",
        "n": len(text),
        "fp": _fp(text),
        "head": text[:200].replace("\n", " ⏎ "),
    }


def _emit(row: dict) -> None:
    print("\n  【%s】 输入 dimensions=%s" % (row["label"], list(row["dimensions_in"])))
    print("    planner_authority = %s" % row["authority"])
    print("    intent            = %s" % row["intent"])
    print("    编译后 dimensions  = %s" % list(row["dims_out"]))
    print("    plan_hash         = %s" % row["plan_hash"])
    print("    kind=%s  字数=%d  答案指纹=%s" % (row["kind"], row["n"], row["fp"]))
    print("    老板看到: %s" % (row["head"] or "（空）"))


async def main() -> int:
    pool = await supplier_ab_ctx.pool()
    print("PROBE_DB=%s FID=%s 树=%s" % (supplier_ab_ctx.db_name, SUPPLIER_AB_FID,
                                        os.path.dirname(os.path.dirname(
                                            os.path.dirname(ri.__file__)))))
    print("问句=%s  输入=prod 实测抓到的那份模型输出（逐字）" % SUPPLIER_AB_QUERY)

    control = await _one(pool, SUPPLIER_AB_CONTROL_DIMENSIONS, "阳性对照 dimensions=[]")
    _emit(control)
    if control["kind"] != "answer":
        print("\n⛔ rc=2 阳性对照没作答 —— 这条链在这个进程里跑不动，"
              "下面那条读数分不清「缺陷」和「探针起不来」")
        return 2

    target = await _one(pool, SUPPLIER_AB_PLAN["dimensions"], "被测 dimensions=[supplier]")
    _emit(target)

    print("\n" + "-" * 74)
    same_plan = target["plan_hash"] == control["plan_hash"]
    same_text = target["fp"] == control["fp"]
    print("  被测 vs 阳性对照：plan_hash 相同=%s  答案指纹相同=%s"
          % (same_plan, same_text))
    if target["kind"] == "answer" and same_plan and same_text:
        print("  ✅ 未登记的维度值不再作废整份计划 —— 老板拿到的与模型自己"
              "另一种输出**逐字相同**的那个答案")
        return 0
    if target["authority"] == "llm_contract_incomplete":
        print("  🔴 整份计划被作废：老板收到一句「我还没有完整理解这句话」，"
              "而 intent/confidence 都是对的 —— **那句话是假的**")
        return 1
    print("  ⚠️ 既不是修好也不是原状 —— 逐格看上面的读数，⛔ 不要归类")
    return 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
