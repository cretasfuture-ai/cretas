"""普通 Web 在制过账链（MaterialConsumption）专用适配器。

This adapter is deliberately independent from the legacy SFI OUT/WIP_CONSUME
adapter.  It only admits a posted material-consumption row when both production
batches have submitted, settled process rows and the source WIP batch balances
exactly to all posted consumption rows.
"""
from __future__ import annotations

from collections import defaultdict
from decimal import Decimal, InvalidOperation
from typing import Any, Iterable


WIP_PLAN_FLOW_SQL = """
WITH posted_mc AS (
    SELECT mc.factory_id, mc.batch_id, SUM(mc.quantity) AS posted_quantity,
           COUNT(*) AS posted_count
    FROM material_consumptions mc
    WHERE mc.factory_id = $1 AND mc.deleted_at IS NULL
      AND mc.source_type = 'SEMI_FINISHED'
      AND mc.quantity > 0 AND mc.interim_settled_at IS NOT NULL
    GROUP BY mc.factory_id, mc.batch_id
), posted_mc_period AS (
    SELECT mc.factory_id, mc.batch_id, SUM(mc.quantity) AS period_posted_quantity
    FROM material_consumptions mc
    WHERE mc.factory_id = $1 AND mc.deleted_at IS NULL
      AND mc.source_type = 'SEMI_FINISHED' AND mc.quantity > 0
      AND mc.interim_settled_at IS NOT NULL
      AND ($2::date IS NULL OR mc.interim_settled_at >= $2::date)
      AND ($3::date IS NULL OR mc.interim_settled_at < ($3::date + INTERVAL '1 day'))
    GROUP BY mc.factory_id, mc.batch_id
), submitted_steps AS (
    SELECT ps.factory_id, ps.batch_id, MIN(ps.process_order) AS process_order
    FROM process_sheet_rows ps
    WHERE ps.factory_id = $1 AND ps.deleted_at IS NULL
      AND ps.submission_status = 'SUBMITTED' AND ps.interim_settled_at IS NOT NULL
      AND ps.batch_id IS NOT NULL
    GROUP BY ps.factory_id, ps.batch_id
), source_stock AS (
    SELECT mb.factory_id, mb.source_doc_id, COUNT(*) AS source_batch_count,
           SUM(mb.receipt_quantity) AS receipt_quantity,
           SUM(mb.used_quantity) AS used_quantity,
           MIN(mb.quantity_unit) AS quantity_unit
    FROM material_batches mb
    WHERE mb.factory_id = $1 AND mb.deleted_at IS NULL
      AND mb.source_doc_type = 'PRODUCTION_BATCH'
    GROUP BY mb.factory_id, mb.source_doc_id
)
SELECT mc.id AS "MC凭证ID", mc.factory_id AS "__factory_id",
       mc.interim_settled_at AS "过账日期", mc.batch_id AS "来源库存批次ID",
       mc.production_batch_id AS "目标生产批次ID", mc.quantity AS "数量",
       mc.source_type AS "来源类型", mc.production_plan_id AS "MC计划ID",
       srcpb.batch_number AS "来源批次号", srcpb.production_plan_id AS "计划ID",
       pp.status AS "计划状态",
       srcpb.unit AS "来源批次单位", srcpb.status AS "来源批次状态",
       srcstep.process_order AS "来源工序序", srcpb.product_name AS "来源产品",
       tgtpb.batch_number AS "目标批次号", tgtpb.unit AS "目标批次单位",
       tgtpb.status AS "目标批次状态", tgtstep.process_order AS "目标工序序",
       tgtpb.product_name AS "目标产品", stock.quantity_unit AS "来源库存单位",
       stock.receipt_quantity AS "来源库存入账量", stock.used_quantity AS "来源库存已用量",
       posted.posted_quantity AS "已过账消耗合计", posted.posted_count AS "已过账消耗行数",
       period_posted.period_posted_quantity AS "期间已过账消耗合计",
       stock.source_batch_count AS "来源库存批次数"
FROM material_consumptions mc
JOIN production_batches tgtpb
  ON tgtpb.id = mc.production_batch_id AND tgtpb.factory_id = mc.factory_id
 AND tgtpb.deleted_at IS NULL
JOIN production_batches srcpb
  ON srcpb.production_plan_id = tgtpb.production_plan_id
 AND srcpb.factory_id = mc.factory_id AND srcpb.deleted_at IS NULL
 AND srcpb.id::text = (
     SELECT mb.source_doc_id FROM material_batches mb
     WHERE mb.id = mc.batch_id AND mb.factory_id = mc.factory_id
       AND mb.deleted_at IS NULL AND mb.source_doc_type = 'PRODUCTION_BATCH'
   )
JOIN submitted_steps srcstep
  ON srcstep.factory_id = mc.factory_id AND srcstep.batch_id = srcpb.id
JOIN submitted_steps tgtstep
  ON tgtstep.factory_id = mc.factory_id AND tgtstep.batch_id = tgtpb.id
JOIN source_stock stock
  ON stock.factory_id = mc.factory_id AND stock.source_doc_id = srcpb.id::text
JOIN posted_mc posted
  ON posted.factory_id = mc.factory_id AND posted.batch_id = mc.batch_id
JOIN posted_mc_period period_posted
  ON period_posted.factory_id = mc.factory_id AND period_posted.batch_id = mc.batch_id
JOIN production_plans pp
  ON pp.id = tgtpb.production_plan_id AND pp.factory_id = mc.factory_id
 AND pp.deleted_at IS NULL
 AND UPPER(COALESCE(pp.status, '')) NOT IN ('CANCELLED', 'CANCELED', 'RECALLED', 'REJECTED', 'DRAFT', 'PENDING_APPROVAL')
WHERE mc.factory_id = $1 AND mc.deleted_at IS NULL
  AND mc.source_type = 'SEMI_FINISHED' AND mc.quantity > 0
  AND mc.interim_settled_at IS NOT NULL
  AND ($2::date IS NULL OR mc.interim_settled_at >= $2::date)
  AND ($3::date IS NULL OR mc.interim_settled_at < ($3::date + INTERVAL '1 day'))
  AND srcstep.process_order IS NOT NULL AND tgtstep.process_order IS NOT NULL
  AND srcstep.process_order < tgtstep.process_order
  AND UPPER(COALESCE(srcpb.status, '')) NOT IN ('CANCELLED', 'CANCELED', 'DELETED')
  AND UPPER(COALESCE(tgtpb.status, '')) NOT IN ('CANCELLED', 'CANCELED', 'DELETED')
  AND srcpb.unit IS NOT NULL AND tgtpb.unit = srcpb.unit
  AND stock.source_batch_count = 1
  AND stock.quantity_unit = srcpb.unit
  AND stock.receipt_quantity >= stock.used_quantity
  AND stock.used_quantity = posted.posted_quantity
ORDER BY mc.interim_settled_at, mc.id
LIMIT 10001
"""


def _dec(value: Any) -> Decimal | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        value = Decimal(str(value))
    except (InvalidOperation, ValueError, TypeError):
        return None
    return value if value.is_finite() else None


def assemble_wip_plan_flows(rows: Iterable[dict[str, Any]], factory_id: str | None = None):
    """Fail closed and return ``(edges, gaps)`` for already-posted MC rows.

    The SQL normally enforces these predicates; keeping the check here makes
    the adapter safe against fixture/import drift and gives the UI a useful
    gap instead of silently dropping an unbalanced source batch.
    """
    materialized = [dict(row) for row in rows]
    seen: set[Any] = set()
    grouped: dict[Any, list[dict[str, Any]]] = defaultdict(list)
    gaps: list[str] = []
    for row in materialized:
        mc_id = row.get("MC凭证ID")
        if mc_id is None or mc_id in seen:
            gaps.append(f"MC:{mc_id or '?'}凭证重复或缺失")
            continue
        seen.add(mc_id)
        grouped[row.get("来源库存批次ID")].append(row)
    edges: list[dict[str, Any]] = []
    for source_batch, batch_rows in grouped.items():
        first = batch_rows[0]
        reasons: list[str] = []
        fields = [first.get("来源批次号"), first.get("目标批次号"), first.get("计划ID"),
                  first.get("来源库存单位"), first.get("来源批次单位"), first.get("目标批次单位")]
        if any(v in (None, "") for v in fields):
            reasons.append("来源/目标批次、计划或单位缺失")
        for row in batch_rows:
            if factory_id is not None and row.get("__factory_id") not in (None, factory_id):
                reasons.append("租户不一致")
            if row.get("来源类型") != "SEMI_FINISHED":
                reasons.append("来源类型不是SEMI_FINISHED")
            if row.get("来源批次单位") != row.get("目标批次单位") or row.get("来源库存单位") != row.get("来源批次单位"):
                reasons.append("来源库存/批次单位冲突")
            if row.get("来源工序序") is None or row.get("目标工序序") is None or row.get("来源工序序") >= row.get("目标工序序"):
                reasons.append("工序不是正向链")
            if row.get("来源批次状态") in ("CANCELLED", "CANCELED", "DELETED") or row.get("目标批次状态") in ("CANCELLED", "CANCELED", "DELETED"):
                reasons.append("批次状态无效")
            if row.get("计划状态") in ("CANCELLED", "CANCELED", "RECALLED", "REJECTED", "DRAFT", "PENDING_APPROVAL"):
                reasons.append("生产计划状态无效")
        qtys = [_dec(row.get("数量")) for row in batch_rows]
        if any(q is None or q <= 0 for q in qtys):
            reasons.append("数量必须为正")
        if first.get("来源批次号") == first.get("目标批次号"):
            reasons.append("来源和目标批次相同")
        if first.get("来源工序序") is None or first.get("目标工序序") is None or first.get("来源工序序") >= first.get("目标工序序"):
            reasons.append("工序不是正向链")
        if len({(r.get("计划ID"), r.get("来源批次单位"), r.get("目标批次单位")) for r in batch_rows}) != 1:
            reasons.append("计划或单位冲突")
        if first.get("来源库存单位") != first.get("来源批次单位") or first.get("来源批次单位") != first.get("目标批次单位"):
            reasons.append("来源库存/批次单位冲突")
        receipt, used, posted = (_dec(first.get(k)) for k in ("来源库存入账量", "来源库存已用量", "已过账消耗合计"))
        period_posted = _dec(first.get("期间已过账消耗合计"))
        total = sum(qtys, Decimal(0)) if all(qtys) else None
        if first.get("来源库存批次数") not in (None, 1):
            reasons.append("来源库存批次不唯一")
        if receipt is None or used is None or posted is None or period_posted is None or receipt < used or used != posted or period_posted != total:
            reasons.append("来源库存入账量/已用量与全部已过账MC不守恒")
        if reasons:
            gaps.append(f"来源批次{source_batch or '?'}: " + "；".join(dict.fromkeys(reasons)))
            continue
        for row in batch_rows:
            edges.append({
                "过账日期": row.get("过账日期"), "来源节点": row.get("来源批次号"),
                "去向节点": row.get("目标批次号"), "流量": _dec(row.get("数量")),
                "单位": row.get("来源库存单位"), "MC凭证": f"MC:{row.get('MC凭证ID')}",
                "来源库存批次ID": source_batch, "目标生产批次ID": row.get("目标生产批次ID"),
                "计划ID": row.get("计划ID"), "来源产品": row.get("来源产品"),
                "目标产品": row.get("目标产品"),
            })
    return edges, ([{"id": "wip-plan-flow.conservation", "title": "普通Web在制过账流",
                      "missing": gaps}] if gaps else [])
