"""Read-only facts for the SAFETY_STOCK/interim-settle production path.

This source deliberately does not reuse ``fact_production_batch``.  A normal
stock-replenishment plan can be stopped and its finished-goods inventory posted
while its per-process ``production_batches`` row is still ``IN_PROGRESS``.
The only accepted row has an explicit process-row settlement stamp, an interim
session manifest, a stopped plan, and a production-to-finished lineage edge.
"""

import pandas as pd
from decimal import Decimal, InvalidOperation

import smartbi.config as config
from .engine import Dataset
from .metrics import complete_manufacturing_cost
from . import cost_authorization


# ``process_sheet_rows`` is the authoritative submission/stamp pair for the
# interim path.  ``production_interim_settlement.summary`` is JSON written by
# InterimSettleServiceImpl and contains ``finishedGoodsBatchNumbers`` and
# ``finishedQuantity``.  The lineage edge is polymorphic by design, so both
# sides are constrained by factory and type before joining.
_SETTLED_SELECT = """
SELECT fgb.production_date AS "日期", pb.id::text AS "来源主键",
       pb.production_plan_id AS "计划ID", pb.batch_number AS "批次",
       pb.product_type_id AS "产品ID",
       COALESCE(pb.product_name, pb.product_type_id, '未关联产品') AS "产品",
       fgb.id AS "成品批次ID", fgb.batch_number AS "成品Manifest",
       fgb.production_plan_id AS "成品计划ID", fgb.product_type_id AS "成品产品ID",
       pol.quantity_unit AS "单位", fgb.unit AS "成品单位",
       pol.reported_quantity AS "产出报告数量", pol.received_quantity AS "实收数量",
       pol.status AS "产出行状态", pol.reported_quantity AS "良品数",
       ps.defect_quantity AS "不良数", ps.defect_count_outcome AS "不良清点结论",
       pol.reported_quantity + ps.defect_quantity AS "产量",
       pb.status AS "生产批次状态",
       pb.material_cost AS "材料成本", pb.labor_cost AS "人工成本",
       pb.equipment_cost AS "设备成本", pb.other_cost AS "其他成本",
       pb.total_cost AS "总成本", pb.cost_incomplete AS "成本不完整",
       pb.updated_at AS "成本更新时间",
       ps.posting_status AS "结算状态", psr.submission_status AS "报工提交状态",
       psr.interim_settled_at AS "小结时间", pis.session_seq AS "小结序号"
"""

_SETTLED_FROM = """
WITH output_plan_scope AS (
    SELECT pol.factory_id, pol.production_plan_id
    FROM production_settlement_output_lines pol
    JOIN production_plans pp
      ON pp.id = pol.production_plan_id
     AND pp.factory_id = pol.factory_id
     AND pp.deleted_at IS NULL
     AND pp.source_type = 'SAFETY_STOCK'
     AND pp.status = 'COMPLETED'
    WHERE pol.factory_id = $1
    GROUP BY pol.factory_id, pol.production_plan_id
    HAVING COUNT(*) = 1
       AND COUNT(DISTINCT pol.product_type_id) = 1
       AND COUNT(DISTINCT pol.quantity_unit) = 1
), output_candidate AS (
    SELECT pol.*
    FROM production_settlement_output_lines pol
    JOIN output_plan_scope ops
      ON ops.factory_id = pol.factory_id
     AND ops.production_plan_id = pol.production_plan_id
    WHERE pol.factory_id = $1
      AND pol.deleted_at IS NULL
      AND pol.status IN ('REPORTED', 'RECEIVED')
      AND pol.finished_goods_batch_id IS NOT NULL
      AND pol.reported_quantity > 0
      AND pol.received_quantity IS NOT NULL
      AND pol.received_quantity = pol.reported_quantity
), interim_scope AS (
    SELECT pis.factory_id, pis.production_plan_id,
           MIN(pis.session_seq) AS session_seq,
           MIN(manifest.batch_number) AS batch_number,
           MIN(CASE WHEN pis.summary->>'finishedQuantity' ~ '^[0-9]+(\\.[0-9]+)?$'
                    THEN (pis.summary->>'finishedQuantity')::numeric END) AS finished_quantity
    FROM production_interim_settlement pis
    LEFT JOIN LATERAL jsonb_array_elements_text(
        CASE WHEN jsonb_typeof(pis.summary->'finishedGoodsBatchNumbers') = 'array'
             THEN pis.summary->'finishedGoodsBatchNumbers' ELSE '[]'::jsonb END
    ) AS manifest(batch_number) ON TRUE
    WHERE pis.factory_id = $1
      AND pis.deleted_at IS NULL
    GROUP BY pis.factory_id, pis.production_plan_id
    HAVING COUNT(DISTINCT pis.session_seq) = 1
       AND COUNT(DISTINCT manifest.batch_number) = 1
       AND BOOL_AND(CASE WHEN jsonb_typeof(pis.summary->'finishedGoodsBatchNumbers') = 'array'
                        THEN jsonb_array_length(pis.summary->'finishedGoodsBatchNumbers') = 1
                        ELSE FALSE END)
       AND BOOL_AND(COALESCE(pis.summary->>'finishedQuantity' ~ '^[0-9]+(\\.[0-9]+)?$', FALSE))
)
FROM output_candidate pol
JOIN production_plans pp
  ON pp.id = pol.production_plan_id
 AND pp.factory_id = pol.factory_id
 AND pp.deleted_at IS NULL
JOIN finished_goods_batches fgb
  ON fgb.id = pol.finished_goods_batch_id
 AND fgb.factory_id = pol.factory_id
 AND fgb.production_plan_id = pol.production_plan_id
 AND fgb.product_type_id = pol.product_type_id
 AND fgb.deleted_at IS NULL
JOIN production_batches pb
  ON pb.factory_id = pol.factory_id
 AND pb.production_plan_id = pol.production_plan_id
 AND pb.deleted_at IS NULL
 AND pb.status IN ('IN_PROGRESS', 'PRODUCING', 'COMPLETED')
JOIN batch_lineage_edges ble
  ON ble.factory_id = pb.factory_id
 AND ble.source_id = pb.id::text
 AND ble.target_id = fgb.id
 AND ble.edge_type = 'PRODUCTION_TO_FINISHED'
 AND ble.source_type = 'PRODUCTION_BATCH'
 AND ble.target_type = 'FINISHED_BATCH'
JOIN production_interim_settlement pis
  ON pis.factory_id = pol.factory_id
 AND pis.production_plan_id = pol.production_plan_id
 AND pis.deleted_at IS NULL
JOIN interim_scope
  ON interim_scope.factory_id = pis.factory_id
 AND interim_scope.production_plan_id = pis.production_plan_id
 AND interim_scope.session_seq = pis.session_seq
 AND pis.summary ? 'finishedGoodsBatchNumbers'
 AND interim_scope.batch_number = fgb.batch_number
JOIN production_settlements ps
  ON ps.id = pol.settlement_id
 AND ps.factory_id = pol.factory_id
 AND ps.production_plan_id = pol.production_plan_id
 AND ps.deleted_at IS NULL
 AND ps.posting_status = 'POSTED'
JOIN process_sheet_rows psr
  ON psr.factory_id = pol.factory_id
 AND psr.plan_id = pol.production_plan_id
 AND psr.batch_id = pb.id
 AND psr.deleted_at IS NULL
 AND psr.submission_status = 'SUBMITTED'
 AND psr.interim_settled_at IS NOT NULL
WHERE pol.factory_id = $1
  AND pp.source_type = 'SAFETY_STOCK'
  AND pp.status = 'COMPLETED'
  AND ps.finished_goods_batch_id = fgb.id
  AND interim_scope.finished_quantity = pol.reported_quantity
  AND ps.defect_count_outcome IN ('NONE_CONFIRMED', 'COUNTED')
  AND ps.defect_quantity IS NOT NULL
  AND ps.defect_quantity >= 0
  AND (
       (ps.defect_count_outcome = 'NONE_CONFIRMED' AND ps.defect_quantity = 0)
       OR (ps.defect_count_outcome = 'COUNTED' AND ps.defect_quantity > 0)
  )
  AND fgb.production_date IS NOT NULL
  AND ($2::date IS NULL OR fgb.production_date >= $2)
  AND ($3::date IS NULL OR fgb.production_date <= $3)
"""

# PostgreSQL requires the CTE before SELECT. Keep the verified join body
# readable above while splitting the shared text at its final FROM anchor.
_SETTLED_CTES, _SETTLED_FROM = _SETTLED_FROM.split("\nFROM output_candidate", 1)
_SETTLED_FROM = "\nFROM output_candidate" + _SETTLED_FROM

_ORDER_LIMIT = """
ORDER BY fgb.production_date, pb.id, fgb.id, pis.session_seq
LIMIT 10001
"""

SETTLED_SOURCE_SQL = _SETTLED_CTES + _SETTLED_SELECT + _SETTLED_FROM + _ORDER_LIMIT

# A user without price permission must not cause a SQL read of any monetary
# column.  The query can still prove the non-money lineage, but the qualifier
# intentionally leaves it in a permission/cost gap rather than calling it a
# trustworthy cost fact.
_SETTLED_NO_PRICE_SELECT = """
SELECT fgb.production_date AS "日期", pb.id::text AS "来源主键",
       pb.production_plan_id AS "计划ID", pb.batch_number AS "批次",
       pb.product_type_id AS "产品ID",
       COALESCE(pb.product_name, pb.product_type_id, '未关联产品') AS "产品",
       fgb.id AS "成品批次ID", fgb.batch_number AS "成品Manifest",
       fgb.production_plan_id AS "成品计划ID", fgb.product_type_id AS "成品产品ID",
       pol.quantity_unit AS "单位", fgb.unit AS "成品单位",
       pol.reported_quantity AS "产出报告数量", pol.received_quantity AS "实收数量",
       pol.status AS "产出行状态", pol.reported_quantity AS "良品数",
       ps.defect_quantity AS "不良数", ps.defect_count_outcome AS "不良清点结论",
       pol.reported_quantity + ps.defect_quantity AS "产量",
       pb.status AS "生产批次状态",
       ps.posting_status AS "结算状态", psr.submission_status AS "报工提交状态",
       psr.interim_settled_at AS "小结时间", pis.session_seq AS "小结序号"
"""
SETTLED_SOURCE_NO_PRICE_SQL = _SETTLED_CTES + _SETTLED_NO_PRICE_SELECT + _SETTLED_FROM + _ORDER_LIMIT


def _text(value):
    if value is None:
        return None
    value = str(value).strip()
    return value or None


def _number(value):
    if value is None or pd.isna(value):
        return None
    try:
        parsed = Decimal(str(value))
        return parsed if parsed.is_finite() else None
    except (InvalidOperation, TypeError, ValueError):
        return None


def _valid_cost(row):
    return row.get("成本不完整") is False and complete_manufacturing_cost(row)


def qualify_settled_rows(rows, *, can_price=True):
    """Return qualified rows and explicit gaps; never repair missing values.

    Duplicate stable keys are quarantined as a group.  The caller can retain
    the gap count/source row count without silently choosing the first row.
    """
    rows = [dict(row) for row in (rows or [])]
    by_key = {}
    for row in rows:
        by_key.setdefault(_text(row.get("来源主键")), []).append(row)
    duplicates = {key for key, values in by_key.items() if key and len(values) != 1}
    by_plan = {}
    for row in rows:
        by_plan.setdefault(_text(row.get("计划ID")), []).append(row)
    invalid_plans = set()
    for plan, plan_rows in by_plan.items():
        products = {_text(item.get("产品ID")) for item in plan_rows}
        finished_batches = {_text(item.get("成品批次ID")) for item in plan_rows}
        if (plan is None or len(plan_rows) != 1 or len(products) != 1
                or len(finished_batches) != 1):
            invalid_plans.add(plan)
    qualified, gaps = [], []
    cost_reason_prefixes = {
        "无成本权限，成本事实未读取",
        "成本分项或总成本不完整，未补零",
    }
    for row in rows:
        key = _text(row.get("来源主键"))
        reasons = []
        if key is None:
            reasons.append("缺少稳定生产批次键")
        if key in duplicates:
            reasons.append("稳定生产批次键重复，整组拒绝")
        if _text(row.get("计划ID")) is None:
            reasons.append("缺少生产计划来源")
        if (_text(row.get("成品计划ID")) != _text(row.get("计划ID"))
                or _text(row.get("成品产品ID")) != _text(row.get("产品ID"))):
            reasons.append("成品计划或SKU与生产批次不一致")
        if _text(row.get("生产批次状态")) not in {"IN_PROGRESS", "PRODUCING", "COMPLETED"}:
            reasons.append("生产批次状态不是允许的生产/完成状态")
        if pd.isna(pd.to_datetime(row.get("日期"), errors="coerce")):
            reasons.append("实际生产日期缺失或非法")
        if _text(row.get("计划ID")) in invalid_plans:
            reasons.append("整计划不是单最终SKU/成品批次/唯一事实行，整计划拒绝")
        if _text(row.get("成品批次ID")) is None or _text(row.get("成品Manifest")) is None:
            reasons.append("缺少成品批次/manifest明确血缘")
        product = _text(row.get("产品ID"))
        unit = _text(row.get("单位"))
        if product is None or unit is None:
            reasons.append("缺少单SKU或计量单位")
        if _text(row.get("产出行状态")) not in {"REPORTED", "RECEIVED"}:
            reasons.append("产出行缺少明确已报产出快照")
        reported = _number(row.get("产出报告数量"))
        received = _number(row.get("实收数量"))
        if (reported is None or received is None or reported <= 0
                or received != reported or _text(row.get("成品单位")) != unit):
            reasons.append("成品产出报告/实收数量或单位不守恒")
        good = _number(row.get("良品数"))
        defect = _number(row.get("不良数"))
        if reported is not None and good is not None and reported != good:
            reasons.append("产出报告数量与良品数量不一致")
        actual = _number(row.get("产量"))
        if good is None or good <= 0:
            reasons.append("成品良品数量不是已验证的正数")
        if defect is None or defect < 0:
            reasons.append("不良数量缺失，未补零")
        if actual is None or good is None or defect is None or actual != good + defect:
            reasons.append("实际产量未按良品+明确不良清点守恒")
        outcome = _text(row.get("不良清点结论"))
        if outcome not in {"NONE_CONFIRMED", "COUNTED"}:
            reasons.append("不良清点结论不是明确已清点")
        if not can_price:
            reasons.append("无成本权限，成本事实未读取")
        elif not _valid_cost(row):
            reasons.append("成本分项或总成本不完整，未补零")
            # A row that still contains monetary values but fails the settled
            # cost invariant is not a trustworthy quantity row.  Partial
            # authorization is different: the phase-1 merge has no monetary
            # values at all and may safely retain its independently-qualified
            # output facts with a cost gap.
            if any(row.get(column) is not None for column in
                   ("材料成本", "人工成本", "设备成本", "其他成本", "总成本")):
                reasons.append("成本事实不满足结算不变量")
        non_cost_reasons = [reason for reason in reasons if reason not in cost_reason_prefixes]
        if non_cost_reasons:
            gaps.append({"id": "settled.production", "source": "settled-production",
                         "key": key, "missing": reasons})
            continue
        if reasons:
            # Cost permission/quality is orthogonal to the already-proven
            # production, output, lineage, date, and unit facts.  Keep this
            # row as a quantity fact and make the price gap explicit.  Never
            # synthesize zeros for the unavailable cost fields.
            sanitized = dict(row)
            for column in ("材料成本", "人工成本", "设备成本", "其他成本", "总成本",
                           "成本不完整", "成本更新时间"):
                sanitized.pop(column, None)
            qualified.append(sanitized)
            gaps.append({"id": "settled.production", "source": "settled-production",
                         "key": key, "missing": reasons})
            continue
        qualified.append(row)
    return qualified, gaps


async def load(factory_id, request, can_price, auth_request=None):
    """Read settled production facts through the existing tenant-scoped pool."""
    from .sources import dates

    start, end = dates(request)
    # Manufacturing costs are never read before the Java authority call.
    if auth_request is None:
        can_price = False
    sql = SETTLED_SOURCE_SQL if can_price else SETTLED_SOURCE_NO_PRICE_SQL
    phase1_rows = []
    try:
        pool = await config.get_cretas_pool()
        if pool is None:
            raise RuntimeError("pool unavailable")
        async with pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
                if auth_request is not None and can_price:
                    # First pass proves stable product IDs without selecting any cost column.
                    id_rows = await conn.fetch(SETTLED_SOURCE_NO_PRICE_SQL, factory_id, start, end)
                    phase1_rows = id_rows
                    product_ids = sorted({_text(row.get('产品ID')) for row in id_rows if _text(row.get('产品ID'))})
                    authority = await cost_authorization.authorize_batches(auth_request, factory_id, product_ids)
                    if not authority.allowed_product_ids:
                        rows = id_rows
                        can_price = False
                        sql = SETTLED_SOURCE_NO_PRICE_SQL
                    else:
                        scoped_sql = sql.replace("\nORDER BY fgb.production_date", "\n  AND pb.product_type_id = ANY($4::text[])\nORDER BY fgb.production_date")
                        scoped_rows = await conn.fetch(scoped_sql,
                                                       factory_id, start, end, sorted(authority.allowed_product_ids))
                        costs = {str(item.get("来源主键")): item for item in scoped_rows}
                        cost_keys = ("材料成本", "人工成本", "设备成本", "其他成本", "总成本", "成本不完整", "成本更新时间")
                        rows = []
                        for base in id_rows:
                            merged = dict(base)
                            extra = costs.get(str(base.get("来源主键")))
                            if extra is not None:
                                merged.update({key: extra.get(key) for key in cost_keys})
                            rows.append(merged)
                else:
                    rows = await conn.fetch(sql, factory_id, start, end)
        if len(rows) > 10000:
            return [], [{"source": "settled-production", "message": "明细超过安全上限，请缩小期间后重试"}], []
        qualified, gaps = qualify_settled_rows(rows, can_price=can_price)
        if not qualified:
            return [], [], gaps
        frame = pd.DataFrame(qualified)
        money = [] if can_price else [c for c in frame if any(k in c for k in ("金额", "成本"))]
        frame = frame.drop(columns=money, errors="ignore")
        return [Dataset(frame, "settled-production · 已停产安全库存小结事实（单批次明确血缘；已报产出快照，非当前库存余额；成本为当前归集修订值，非结算时点快照）",
                         currency="CNY")], [], gaps
    except cost_authorization.CostAuthorizationError:
        # The phase-1 facts are still useful and are never replaced by an
        # empty result when the authority is unavailable.
        if len(phase1_rows) > 10000:
            return [], [{"source": "settled-production", "message": "明细超过安全上限，请缩小期间后重试"}], []
        qualified, gaps = qualify_settled_rows(phase1_rows, can_price=False)
        gaps.append({"id": "settled.production", "source": "settled-production",
                     "missing": ["生产成本授权失败，成本事实未读取；合格数量事实保留"]})
        datasets = ([Dataset(pd.DataFrame(qualified), "settled-production · 无价生产事实（成本授权失败）", currency="CNY")]
                    if qualified else [])
        return datasets, [], gaps
    except Exception:
        return [], [{"source": "settled-production", "message": "来源读取失败，请重试或联系管理员核对数据服务"}], []
