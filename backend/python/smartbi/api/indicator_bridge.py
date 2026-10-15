"""Typed, tenant-scoped indicator bridge for Java's SmartBI client."""
from __future__ import annotations

import logging
from datetime import date
from decimal import Decimal, InvalidOperation
from datetime import datetime
from typing import Any, Dict, Optional

from fastapi import APIRouter, HTTPException, Query, Request

from smartbi.config import get_pg_pool
from smartbi.gold.restaurant.dish_margin import compute_dish_margins

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/smartbi", tags=["Indicator Bridge"])


def _period_start(value: Optional[date]) -> date:
    today = date.today()
    return value or today.replace(day=1)


def _period_end(value: Optional[date]) -> date:
    return value or date.today()


def _decimal(value: Any) -> Optional[Decimal]:
    if value is None:
        return None
    try:
        result = Decimal(str(value))
    except (InvalidOperation, ValueError, TypeError):
        raise HTTPException(status_code=502, detail="indicator returned a non-numeric value")
    if not result.is_finite():
        raise HTTPException(status_code=502, detail="indicator returned a non-finite value")
    return result


@router.get("/indicator/restaurant-dish-margin")
async def restaurant_dish_margin_indicator(
    request: Request,
    factory_id: Optional[str] = Query(None),
    period_start: Optional[date] = Query(None),
    period_end: Optional[date] = Query(None),
) -> Dict[str, Any]:
    """Return the measured-cost dish margin for one authenticated factory/window."""
    identity = getattr(request.state, "factory_id", None)
    if not identity:
        raise HTTPException(status_code=401, detail="missing authenticated factory")
    if factory_id and factory_id != identity:
        raise HTTPException(status_code=403, detail="factory scope mismatch")
    trusted_factory = identity
    start = _period_start(period_start)
    end = _period_end(period_end)
    if start > end:
        raise HTTPException(status_code=422, detail="period_start must not be after period_end")

    pool = await get_pg_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="db pool unavailable")
    try:
        result = await compute_dish_margins(
            pool, trusted_factory, date_range=(start, end)
        )
    except Exception as exc:
        logger.exception("indicator bridge failed for factory=%s", trusted_factory)
        raise HTTPException(status_code=502, detail="indicator computation failed") from exc

    total_revenue = _decimal(result.get("totalRevenueWithCost"))
    rate = _decimal(result.get("avgRate"))
    # avgRateWithEstimated is deliberately excluded: it mixes measured and
    # industry-default costs and therefore is not this typed indicator.
    coverage = result.get("coverage") or {}
    coverage_ratio = _decimal(coverage.get("revenueRatio"))
    complete_cost_coverage = (
        coverage_ratio is not None
        and (coverage_ratio - Decimal("1")).copy_abs() <= Decimal("0.000000001")
    )
    reason = None
    if total_revenue is None or total_revenue <= 0 or rate is None:
        value = None
        status = "NO_DATA"
        computed_at = None
        reason = "NO_MEASURED_COST_SAMPLE"
    elif not complete_cost_coverage:
        value = None
        status = "NO_DATA"
        computed_at = None
        reason = "COST_COVERAGE_INCOMPLETE"
    else:
        decimal_value = rate * Decimal("100")
        if not decimal_value.is_finite():
            raise HTTPException(status_code=502, detail="indicator returned a non-finite value")
        # Keep the arithmetic Decimal-safe, then emit a JSON number for typed
        # clients (FastAPI serializes Decimal as a string otherwise).
        value = float(decimal_value)
        status = "AVAILABLE"
        computed_at = datetime.now().replace(microsecond=0).isoformat()

    return {
        "success": True,
        "data": {
            "factory_id": trusted_factory,
            "period_start": start.isoformat(),
            "period_end": end.isoformat(),
            "value": value,
            "status": status,
            "source": "REAL_BUSINESS:RESTAURANT_DISH_MARGIN",
            "computed_at": computed_at,
            "unit": "%",
            "coverage_basis": "measured-cost dishes only; cost is the current snapshot",
            "coverage": coverage,
            "reason": reason,
        },
    }
