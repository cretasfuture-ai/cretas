"""Factory-only quality analysis compatibility endpoint.\n\nRestaurant and branch analytical semantics are retired and rejected with HTTP 410.\n"""
from __future__ import annotations

import logging
from datetime import date, datetime
from decimal import ROUND_HALF_UP, Decimal
from typing import Any, Optional

from fastapi import APIRouter, Depends, HTTPException, Query, Request

from smartbi.services.factory_charts import cost_scope as process_secret
from smartbi_compat._rbac_role import require_analytics_read
from smartbi_compat._rbac_strip import strip_price_for_role
from smartbi_compat.api import analysis_production as factory_gold
from smartbi_compat.api.analysis_finance import _decimal_to_number
from smartbi_compat.auth import AuthContext
from smartbi_compat.schema_compat import _java_isoformat, wrap_response
from smartbi_compat.tenant import TenantType, get_tenant_type
from smartbi.shared.retired_tenants import is_retired_tenant

logger = logging.getLogger(__name__)
router = APIRouter()


# ============================================================
# Stub markers (chat-B2 + Phase 2D consume these strings in messages)
# ============================================================
#
# AGGRESSIVE-REVISED scope (Phase 2D Sub-B impl spec
# docs/superpowers/specs/2026-05-11-phase-2d-silver-migration-and-factory-impl-spec.md §3.2):
# ``_factory_quality_dispatch`` no longer raises NotImplementedError — it
# now returns an empty envelope mirroring the Java factory shape with the
# top-level ``dataAvailability = FACTORY_SILVER_PHASE_2D_PENDING`` marker
# so the frontend can render a "data pending" state instead of hitting a
# 500. The Silver-layer port (real-DB implementation) is the next chain
# Phase 2D PR-A/B/C/D.
#
# ``_FACTORY_BRANCH_DEFERRED_MSG`` is kept as a documentation constant —
# other modules / log messages still reference the string. The dispatch
# function no longer raises with it.

_FACTORY_BRANCH_DEFERRED_MSG = (
    "Factory quality analysis returns an empty envelope marked "
    "FACTORY_SILVER_PHASE_2D_PENDING pending the factory Silver schema "
    "migration (fact_quality_inspection / fact_quality_defect / "
    "fact_rework_record / fact_disposal_record / fact_customer_complaint). "
    "chat-B1 dispatch 2026-05-12 Option B: _JavaRandom mock-mirror fallback "
    "rejected (Q1 amendment §1; mirrors chat-A1 PR #350 production precedent)."
)


# Top-level envelope marker emitted by ``_factory_quality_dispatch`` so the
# frontend can distinguish "Phase 2D placeholder, no real data" from a normal
# data-bearing factory response. Tests import this constant to assert every
# stub response carries the exact marker string.
FACTORY_PHASE_2D_PENDING_MARKER = "FACTORY_SILVER_PHASE_2D_PENDING"



def _factory_quality_placeholder(
    start_date: date,
    end_date: date,
    analysis_type: Optional[str],
) -> dict:
    now_iso = _java_isoformat(datetime.now())
    base: dict[str, Any] = {
        "startDate": start_date.isoformat(),
        "endDate": end_date.isoformat(),
        "dataAvailability": FACTORY_PHASE_2D_PENDING_MARKER,
    }

    if analysis_type == "fpy":
        return {**base, "metrics": [], "trendChart": {}}

    if analysis_type == "defect":
        return {**base, "ranking": [], "paretoChart": {}}

    if analysis_type == "rework":
        return {**base, "metrics": [], "costChart": {}}

    return {
        "period": "CUSTOM",
        "startDate": start_date.isoformat(),
        "endDate": end_date.isoformat(),
        "kpiCards": [],
        "rankings": {},
        "charts": {},
        "aiInsights": [],
        "recommendations": [],
        "suggestions": [],
        "generatedAt": now_iso,
        "lastUpdated": now_iso,
        "fromCache": False,
        "cacheExpireAt": None,
        "dataAvailability": FACTORY_PHASE_2D_PENDING_MARKER,
    }


def _calculate_factory_quality_summary(rows: list) -> dict:
    production_summary = factory_gold._calculate_factory_production_rates(rows)
    total_actual = production_summary["totalActualQty"]
    total_good = production_summary["totalGoodQty"]
    defect_count = total_actual - total_good
    if defect_count < 0:
        defect_count = Decimal("0")
    return {
        **production_summary,
        "fpy": factory_gold._rate_pct(total_good, total_actual),
        "defectCount": defect_count,
        "defectRate": factory_gold._rate_pct(defect_count, total_actual),
        "qualityCost": production_summary["totalCost"],
    }


def _build_factory_quality_fpy_metrics(summary: dict) -> list:
    return [
        factory_gold._factory_metric(
            "FPY",
            "首次通过率 (FPY)",
            summary["fpy"],
            "%",
            factory_gold._alert_low(summary["fpy"], Decimal("95"), Decimal("98")),
        ),
        factory_gold._factory_metric(
            "TOTAL_INSPECTIONS",
            "总检验数",
            summary["totalActualQty"],
            "件",
            "GREEN",
        ),
        factory_gold._factory_metric(
            "DEFECT_COUNT",
            "不良品数",
            summary["defectCount"],
            "件",
            "GREEN",
        ),
        factory_gold._factory_metric(
            "DEFECT_RATE",
            "不良率",
            summary["defectRate"],
            "%",
            factory_gold._alert_high(summary["defectRate"], Decimal("5"), Decimal("2")),
        ),
    ]


def _scope_quality_cost(summary: dict, rows: list, scope) -> Optional[dict]:
    """工艺保密: make ``summary["qualityCost"]`` safe for this caller.

    ``qualityCost`` is the factory-wide sum of the per-day per-product Gold
    rows.  FULL callers keep it; for others it is REBUILT by summing only the
    allowed products' rows (⛔ never ``total - secret``); without an
    authorization answer it is ``None`` (fail closed, never 0).  Returns the
    ``costScope`` annotation or ``None`` when nothing was left out.
    """
    if scope.is_full:
        return None
    if scope.is_unavailable:
        summary["qualityCost"] = None
        # The spread production summary also carries the full factory total.
        summary["totalCost"] = None
        return process_secret.rollup_annotation(scope, 0)
    excluded = scope.excluded_product_ids(r["product_type_id"] for r in rows)
    if not excluded:
        return None
    # Only allowed rows that actually carry a cost: when none does there is no
    # visible total (None, not the Decimal 0 _to_decimal_or_zero would give).
    costed_rows = [r for r in rows
                   if scope.allows(r["product_type_id"]) and r["total_cost"] is not None]
    summary["qualityCost"] = (
        factory_gold._calculate_factory_production_rates(costed_rows)["totalCost"]
        if costed_rows else None
    )
    # ⛔ summary["totalCost"] came from **production_summary (all products);
    # overwrite it so no consumer can read the full total off the summary.
    summary["totalCost"] = summary["qualityCost"]
    return process_secret.rollup_annotation(scope, len(excluded))


def _build_factory_quality_rework_metrics(summary: dict) -> list:
    cost_description = "Gold only exposes total production cost; rework/scrap split is unavailable."
    note = summary.get("qualityCostNote")
    if note:
        cost_description = f"{cost_description} {note}"
    return [
        factory_gold._factory_metric("REWORK_COUNT", "返工数", None, "件"),
        factory_gold._factory_metric("REWORK_COST", "返工成本", None, "元"),
        factory_gold._factory_metric("SCRAP_COUNT", "报废数", None, "件"),
        factory_gold._factory_metric("SCRAP_COST", "报废成本", None, "元"),
        factory_gold._factory_metric(
            "TOTAL_QUALITY_COST",
            "质量成本总计",
            summary["qualityCost"],
            "元",
            "GREEN",
            cost_description,
        ),
        factory_gold._factory_metric("REWORK_RATE", "返工率", None, "%"),
        factory_gold._factory_metric("SCRAP_RATE", "报废率", None, "%"),
    ]


def _build_factory_quality_trend_chart(rows: list) -> dict:
    data = []
    for day_rows in factory_gold._group_rows_by_date(rows):
        summary = _calculate_factory_quality_summary(day_rows)
        stat_date = day_rows[0]["stat_date"]
        data.append({
            "date": stat_date.isoformat() if stat_date is not None else None,
            "fpy": factory_gold._decimal_metric_value(summary["fpy"]),
            "defectRate": factory_gold._decimal_metric_value(summary["defectRate"]),
            "totalInspections": factory_gold._decimal_metric_value(
                summary["totalActualQty"]
            ),
        })
    return {
        "chartType": "LINE",
        "title": "质量趋势",
        "xAxisField": "date",
        "yAxisField": "fpy",
        "seriesField": "metric",
        "data": data,
        "options": {"showLegend": True, "multiLine": True, "yAxisMax": 100},
    }


def _build_factory_quality_defect_ranking(rows: list) -> list:
    grouped: dict[Any, list] = {}
    for row in rows:
        grouped.setdefault(row["product_type_id"], []).append(row)

    ranking = []
    for product_type_id, product_rows in grouped.items():
        if product_type_id is None:
            continue
        summary = _calculate_factory_quality_summary(product_rows)
        ranking.append({
            "name": product_type_id,
            "value": factory_gold._decimal_metric_value(summary["defectCount"]),
            "target": None,
            "completionRate": factory_gold._decimal_metric_value(summary["defectRate"]),
            "alertLevel": factory_gold._alert_high(
                summary["defectRate"], Decimal("5"), Decimal("2")
            ),
        })
    ranking.sort(key=lambda item: item["value"] if item["value"] is not None else -1, reverse=True)
    for idx, item in enumerate(ranking, start=1):
        item["rank"] = idx
    return ranking


def _build_factory_quality_pareto_chart(rows: list) -> dict:
    ranking = _build_factory_quality_defect_ranking(rows)
    total_defects = sum(
        Decimal(str(item["value"])) for item in ranking if item["value"] is not None
    )
    cumulative = Decimal("0")
    data = []
    for item in ranking:
        count = Decimal(str(item["value"] or 0))
        pct = factory_gold._rate_pct(count, total_defects)
        cumulative += pct if pct is not None else Decimal("0")
        data.append({
            "defectType": item["name"],
            "count": item["value"],
            "percentage": factory_gold._decimal_metric_value(pct),
            "cumulative": factory_gold._decimal_metric_value(cumulative),
        })
    return {
        "chartType": "BAR",
        "title": "不良产品帕累托分析",
        "xAxisField": "defectType",
        "yAxisField": "count",
        "seriesField": "metric",
        "data": data,
        "options": {"showCumulativeLine": True, "showPercentage": True},
    }


def _build_factory_quality_cost_chart(summary: dict) -> dict:
    return {
        "chartType": "PIE",
        "title": "质量成本分布",
        "xAxisField": "category",
        "yAxisField": "cost",
        "data": [
            {"category": "返工成本", "cost": None},
            {"category": "报废成本", "cost": None},
            {
                "category": "生产总成本",
                "cost": factory_gold._decimal_metric_value(summary["qualityCost"]),
            },
        ],
        "options": {"showPercentage": True, "showLegend": True},
    }


# ============================================================
# Factory dispatch.

async def _factory_quality_dispatch(
    factory_id: str,
    start_date: date,
    end_date: date,
    analysis_type: Optional[str],
    *,
    cost_resolver: Optional[process_secret.CostScopeResolver] = None,
) -> dict:
    """Factory-tenant quality analysis dispatcher (Phase 2D placeholder).

    AGGRESSIVE-REVISED scope (Phase 2D Sub-B impl spec §3.2): returns a
    per-``analysisType`` empty envelope mirroring the Java factory response
    shape, with the top-level ``dataAvailability`` marker
    ``FACTORY_SILVER_PHASE_2D_PENDING`` to communicate "Phase 2D
    placeholder, not real data". This unblocks frontend rendering while
    the Silver-layer real-DB impl is pending the Phase 2D PR-A/B/C/D
    chain (factory ``fact_quality_inspection`` / ``fact_quality_defect`` /
    ``fact_rework_record`` / ``fact_disposal_record`` /
    ``fact_customer_complaint`` migrations).

    Phase 2D scope (per Sub-B spec §2): 1:1 port of Java
    ``QualityAnalysisServiceImpl`` mock — 4-metric quality family
    (FPY / DEFECT_RATE / REWORK_RATE / QUALITY_COST_RATE) + 7 method
    entry points + LinkedHashMap charts/rankings. The signature
    ``(factory_id, start_date, end_date, analysis_type)`` is locked so
    the Silver-layer port can drop in without router-level churn.

    Envelope shape per Subagent A audit of Java
    ``SmartBIAnalysisController.getQualityAnalysis`` lines 119-152:

    * ``overview`` (or None): ``DashboardResponse`` empty envelope
      (period/dates/kpiCards/rankings/charts/aiInsights/recommendations/
      suggestions/generatedAt/lastUpdated/fromCache/cacheExpireAt).
    * ``fpy``: ``{startDate, endDate, metrics: [], trendChart: {}}``.
    * ``defect``: ``{startDate, endDate, ranking: [], paretoChart: {}}``.
    * ``rework``: ``{startDate, endDate, metrics: [], costChart: {}}``.

    Phase-2D-specific deviation from Java parity: a top-level
    ``dataAvailability = FACTORY_SILVER_PHASE_2D_PENDING`` is added to
    every variant. The Java factory branch today emits no such field —
    this stub explicitly signals placeholder state. The empty
    ``aiInsights`` / ``suggestions`` lists in the overview variant also
    deviate from Java's empty-data path (which populates a "暂无质量数据"
    warning); the ``dataAvailability`` marker is the unambiguous signal
    here.
    """
    rows = await factory_gold._query_factory_batch_daily_gold(
        factory_id, start_date, end_date
    )
    if not rows:
        return _factory_quality_placeholder(start_date, end_date, analysis_type)

    now_iso = _java_isoformat(datetime.now())
    summary = _calculate_factory_quality_summary(rows)
    # 工艺保密: no resolver (internal call, no caller identity) = fail closed.
    if cost_resolver is None:
        scope = process_secret.unavailable(process_secret.REASON_NOT_RUN)
    else:
        scope = await cost_resolver.scope_for(r["product_type_id"] for r in rows)
    cost_scope = _scope_quality_cost(summary, rows, scope)
    if cost_scope is not None:
        summary["qualityCostNote"] = cost_scope.get("note") or cost_scope.get("reason")
    fpy_metrics = _build_factory_quality_fpy_metrics(summary)
    trend_chart = _build_factory_quality_trend_chart(rows)
    defect_ranking = _build_factory_quality_defect_ranking(rows)
    pareto_chart = _build_factory_quality_pareto_chart(rows)
    cost_chart = _build_factory_quality_cost_chart(summary)

    base: dict[str, Any] = {
        "startDate": start_date.isoformat(),
        "endDate": end_date.isoformat(),
    }

    if analysis_type == "fpy":
        return {**base, "metrics": fpy_metrics, "trendChart": trend_chart}

    if analysis_type == "defect":
        return {**base, "ranking": defect_ranking, "paretoChart": pareto_chart}

    if analysis_type == "rework":
        result = {
            **base,
            "metrics": _build_factory_quality_rework_metrics(summary),
            "costChart": cost_chart,
        }
        if cost_scope is not None:
            result["costScope"] = cost_scope
        return result

    kpi_cards = [
        factory_gold._factory_kpi_card(
            m["metricCode"],
            m["metricName"],
            factory_gold._to_decimal_or_none(m["value"]),
            m["unit"],
        )
        for m in fpy_metrics
    ]
    overview = {
        "period": "CUSTOM",
        "startDate": start_date.isoformat(),
        "endDate": end_date.isoformat(),
        "kpiCards": kpi_cards,
        "rankings": {"defect_type": defect_ranking, "product_line": []},
        "charts": {
            "quality_trend": trend_chart,
            "defect_pareto": pareto_chart,
            "quality_cost_distribution": cost_chart,
            "product_line_quality": {},
        },
        "aiInsights": [],
        "recommendations": [],
        "suggestions": [],
        "generatedAt": now_iso,
        "lastUpdated": now_iso,
        "fromCache": False,
        "cacheExpireAt": None,
    }
    if cost_scope is not None:
        overview["costScope"] = cost_scope
    return overview


@router.get("/api/mobile/{factory_id}/smart-bi/analysis/quality")
async def get_quality_analysis(
    factory_id: str,
    request: Request,
    startDate: date = Query(...),
    endDate: date = Query(...),
    analysisType: Optional[str] = Query(
        None,
        description="fpy / defect / rework (omit for overview) — Sub-B spec §1.1",
    ),
    auth: AuthContext = Depends(require_analytics_read),
) -> Any:
    """Quality analysis polymorphic endpoint (Q-DEC-8 Option A).

    Single URL serves both factory and restaurant tenants. Tenant-type
    discrimination happens server-side via ``cretas_db.factories.type``
    lookup mirroring Java ``SmartBIServiceImpl.isRestaurantTenant``.

    Per chat-B1 + chat-B2 ship 2026-05-12: restaurant branch is live;
    factory branch now returns an empty envelope marked
    ``FACTORY_SILVER_PHASE_2D_PENDING`` pending the Phase 2D Silver-layer
    real-DB impl (PR-A/B/C/D chain).
    """
    if is_retired_tenant(factory_id):
        raise HTTPException(status_code=410, detail="restaurant and branch analytical endpoints are retired")

    pool = None
    try:
        from smartbi.config import get_cretas_pool  # type: ignore

        pool = await get_cretas_pool()
    except Exception as e:
        logger.warning(
            "[analysis_quality] cretas_db pool acquisition failed factory=%s: %s",
            factory_id,
            e,
        )
        raise HTTPException(status_code=503, detail="tenant metadata unavailable") from e

    if pool is None:
        raise HTTPException(status_code=503, detail="tenant metadata unavailable")

    try:
        async with pool.acquire() as conn:
            tenant = await get_tenant_type(factory_id, conn)
    except Exception as e:
        logger.warning("[analysis_quality] tenant metadata lookup failed factory=%s: %s", factory_id, e)
        raise HTTPException(status_code=503, detail="tenant metadata unavailable") from e

    if tenant.is_restaurant_tenant:
        raise HTTPException(status_code=410, detail="restaurant and branch analytical endpoints are retired")

    # Factory dispatch returns the raw inner dict per its Phase 2D contract
    # (test_factory_stub.py locks the shape). Wrap at the router boundary so
    # HTTP responses mirror Java ``ResponseEntity.ok(ApiResponse.success(result))``
    # — fixes R1 P2 finding 3 (asymmetric envelope between factory/restaurant
    # branches; factory previously emitted raw dict, restaurant emitted envelope).
    raw = await _factory_quality_dispatch(
        factory_id, startDate, endDate, analysisType,
        cost_resolver=process_secret.CostScopeResolver(request, auth.factory_id),
    )
    return wrap_response(strip_price_for_role(raw, auth.role))










 # Factory-only compatibility route ends here.
