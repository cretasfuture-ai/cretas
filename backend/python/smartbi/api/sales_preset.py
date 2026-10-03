"""Sprint 4 W2 S-REPORTS-PRESETS — preset sales reports (9 active + 5 Sprint 5 stubs).

Routes (all under /api/smartbi/{factory_id}/sales-preset/):
  GET /daily-report?date=YYYY-MM-DD            日报 (active)
  GET /monthly-report?yearMonth=YYYY-MM        月报 (active)
  GET /yearly-report?year=YYYY                 年报 (active)
  GET /customer-rank?startDate&endDate&limit   客户销售排行 top N (active)
  GET /product-rank?startDate&endDate&limit    产品销售排行 top N (active)
  GET /salesperson-performance?startDate&endDate          业绩 by 业务员 (active)
  GET /aging                                   应收账龄 (Sprint 5 stub)
  GET /collection-rate                         客户回款率 (Sprint 5 stub)
  GET /return-rate                             退货率 (Sprint 5 stub)
  GET /gross-margin?startDate&endDate&order&limit         销售毛利率 by 产品 (active, PRICE_VIEW_ROLES only)
  GET /purchase-frequency                      客户购买频次 (Sprint 5 stub)
  GET /growth-rate?endMonth&months             月度增长率 MoM/YoY (active)
  GET /regional-comparison?startDate&endDate&level        区域销售对比 + 去年同期 (active)
  GET /customer-tier                           客户分级销售贡献 (Sprint 5 stub)

All endpoints enforce factory_id match via _enforce_factory_match.
Read from cretas_db (sales_orders / sales_order_items / customers / product_types).

Phase 2A direction: prefers gold table reads when available; for MVP top-5
the data freshness is real-time critical so we read business tables directly.
"""
from __future__ import annotations

import logging
from datetime import date, datetime, timedelta
from typing import Optional

from fastapi import APIRouter, HTTPException, Query, Request

from smartbi.api._revenue_report_helpers import _enforce_factory_match

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/smartbi/{factory_id}/sales-preset")


async def _get_pool():
    from smartbi.config import get_cretas_pool
    pool = await get_cretas_pool()
    if pool is None:
        raise HTTPException(503, detail="cretas pool unavailable")
    return pool


# ─── Helpers ─────────────────────────────────────────────────────────────


def _parse_date(s: str, field: str) -> date:
    try:
        return datetime.strptime(s, "%Y-%m-%d").date()
    except ValueError as e:
        raise HTTPException(400, detail=f"invalid {field}: {s!r}, want YYYY-MM-DD") from e


def _date_range(start: Optional[str], end: Optional[str]) -> tuple[date, date]:
    if start is None or end is None:
        # default last 30 days
        today = date.today()
        return today - timedelta(days=30), today
    return _parse_date(start, "startDate"), _parse_date(end, "endDate")


def _stub(name: str) -> dict:
    return {
        "success": True,
        "data": None,
        "message": f"{name} 留 Sprint 5 (Phase II) 实现",
        "deferred": True,
    }


def _money_fields(total_amount, tax_amount=None) -> dict[str, float]:
    """Expose report money fields from persisted untaxed and tax totals.

    Keep ``revenue`` as the legacy untaxed alias.
    """
    taxable = float(total_amount or 0)
    tax = float(tax_amount or 0)
    gross = taxable + tax
    return {
        "revenue": taxable,
        "taxableAmount": taxable,
        "taxAmount": tax,
        "totalAmountWithTax": gross,
    }


# ─── Active endpoints (top 5) ─────────────────────────────────────────────


@router.get("/daily-report")
async def daily_report(factory_id: str, request: Request, date_: str | None = Query(default=None, alias="date")):
    """日报: 当日销售订单数 / 件数 / 销售额 / 已收款 / 未收款.

    Query: date=YYYY-MM-DD (defaults to today).
    """
    caller = _enforce_factory_match(factory_id, request)
    d = _parse_date(date_, "date") if date_ else date.today()

    pool = await _get_pool()
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", caller)
        row = await conn.fetchrow(
            """
            SELECT COUNT(*) AS order_count,
                   COALESCE(SUM(total_amount), 0) AS total_amount,
                   COALESCE(SUM(tax_amount), 0) AS tax_amount,
                   COALESCE(SUM(paid_amount), 0) AS paid,
                   COALESCE(SUM(total_amount), 0)
                     + COALESCE(SUM(tax_amount), 0)
                     - COALESCE(SUM(paid_amount), 0) AS unpaid
            FROM sales_orders
            WHERE factory_id = $1 AND order_date = $2
              AND status NOT IN ('DRAFT', 'CANCELLED')
              AND deleted_at IS NULL
            """,
            caller, d,
        )
    return {
        "success": True,
        "data": {
            "date": d.isoformat(),
            "orderCount": row["order_count"],
            **_money_fields(row["total_amount"], row["tax_amount"]),
            "paid": float(row["paid"]),
            "unpaid": float(row["unpaid"]),
        },
        "message": "ok",
    }


@router.get("/monthly-report")
async def monthly_report(factory_id: str, request: Request, yearMonth: str | None = None):
    """月报: 当月销售额 / 已收款 / 应收 / 订单数 + 按日明细.

    Query: yearMonth=YYYY-MM (defaults to current month).
    """
    caller = _enforce_factory_match(factory_id, request)
    if yearMonth is None:
        today = date.today()
        ym_year, ym_month = today.year, today.month
    else:
        try:
            ym_year, ym_month = map(int, yearMonth.split("-"))
        except ValueError as e:
            raise HTTPException(400, detail="yearMonth must be YYYY-MM") from e

    start = date(ym_year, ym_month, 1)
    if ym_month == 12:
        end = date(ym_year + 1, 1, 1) - timedelta(days=1)
    else:
        end = date(ym_year, ym_month + 1, 1) - timedelta(days=1)

    pool = await _get_pool()
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", caller)
        total = await conn.fetchrow(
            """
            SELECT COUNT(*) AS order_count,
                   COALESCE(SUM(total_amount), 0) AS total_amount,
                   COALESCE(SUM(tax_amount), 0) AS tax_amount,
                   COALESCE(SUM(paid_amount), 0) AS paid
            FROM sales_orders
            WHERE factory_id = $1 AND order_date BETWEEN $2 AND $3
              AND status NOT IN ('DRAFT', 'CANCELLED')
              AND deleted_at IS NULL
            """,
            caller, start, end,
        )
        daily = await conn.fetch(
            """
            SELECT order_date,
                   COUNT(*) AS order_count,
                   COALESCE(SUM(total_amount), 0) AS total_amount,
                   COALESCE(SUM(tax_amount), 0) AS tax_amount
            FROM sales_orders
            WHERE factory_id = $1 AND order_date BETWEEN $2 AND $3
              AND status NOT IN ('DRAFT', 'CANCELLED')
              AND deleted_at IS NULL
            GROUP BY order_date
            ORDER BY order_date
            """,
            caller, start, end,
        )
    return {
        "success": True,
        "data": {
            "yearMonth": f"{ym_year:04d}-{ym_month:02d}",
            "orderCount": total["order_count"],
            **_money_fields(total["total_amount"], total["tax_amount"]),
            "paid": float(total["paid"]),
            "unpaid": float(total["total_amount"]) + float(total["tax_amount"]) - float(total["paid"]),
            "daily": [
                {
                    "date": r["order_date"].isoformat(),
                    "orderCount": r["order_count"],
                    **_money_fields(r["total_amount"], r["tax_amount"]),
                }
                for r in daily
            ],
        },
        "message": "ok",
    }


@router.get("/yearly-report")
async def yearly_report(factory_id: str, request: Request, year: int | None = None):
    """年报: 当年销售额 / 应收 + 按月明细."""
    caller = _enforce_factory_match(factory_id, request)
    y = year if year is not None else date.today().year
    start = date(y, 1, 1)
    end = date(y, 12, 31)

    pool = await _get_pool()
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", caller)
        total = await conn.fetchrow(
            """
            SELECT COUNT(*) AS order_count,
                   COALESCE(SUM(total_amount), 0) AS total_amount,
                   COALESCE(SUM(tax_amount), 0) AS tax_amount,
                   COALESCE(SUM(paid_amount), 0) AS paid
            FROM sales_orders
            WHERE factory_id = $1 AND order_date BETWEEN $2 AND $3
              AND status NOT IN ('DRAFT', 'CANCELLED')
              AND deleted_at IS NULL
            """,
            caller, start, end,
        )
        monthly = await conn.fetch(
            """
            SELECT EXTRACT(MONTH FROM order_date)::int AS m,
                   COUNT(*) AS order_count,
                   COALESCE(SUM(total_amount), 0) AS total_amount,
                   COALESCE(SUM(tax_amount), 0) AS tax_amount
            FROM sales_orders
            WHERE factory_id = $1 AND order_date BETWEEN $2 AND $3
              AND status NOT IN ('DRAFT', 'CANCELLED')
              AND deleted_at IS NULL
            GROUP BY 1
            ORDER BY 1
            """,
            caller, start, end,
        )
    return {
        "success": True,
        "data": {
            "year": y,
            "orderCount": total["order_count"],
            **_money_fields(total["total_amount"], total["tax_amount"]),
            "paid": float(total["paid"]),
            "monthly": [
                {"month": r["m"], "orderCount": r["order_count"], **_money_fields(r["total_amount"], r["tax_amount"])}
                for r in monthly
            ],
        },
        "message": "ok",
    }


@router.get("/customer-rank")
async def customer_rank(
    factory_id: str,
    request: Request,
    startDate: str | None = None,
    endDate: str | None = None,
    limit: int = 20,
):
    """客户销售排行 top N: by total revenue."""
    caller = _enforce_factory_match(factory_id, request)
    start, end = _date_range(startDate, endDate)
    if not (1 <= limit <= 100):
        raise HTTPException(400, "limit must be 1..100")

    pool = await _get_pool()
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", caller)
        rows = await conn.fetch(
            """
            SELECT so.customer_id,
                   COALESCE(c.name, so.customer_id) AS customer_name,
                   COUNT(*) AS order_count,
                   COALESCE(SUM(so.total_amount), 0) AS total_amount,
                   COALESCE(SUM(so.tax_amount), 0) AS tax_amount,
                   COALESCE(SUM(so.total_amount), 0) + COALESCE(SUM(so.tax_amount), 0) AS total_amount_with_tax,
                   COALESCE(SUM(so.paid_amount), 0) AS paid
            FROM sales_orders so
            LEFT JOIN customers c ON c.id = so.customer_id
            WHERE so.factory_id = $1 AND so.order_date BETWEEN $2 AND $3
              AND so.status NOT IN ('DRAFT', 'CANCELLED')
              AND so.deleted_at IS NULL
            GROUP BY so.customer_id, c.name
            ORDER BY total_amount_with_tax DESC
            LIMIT $4
            """,
            caller, start, end, limit,
        )
    return {
        "success": True,
        "data": {
            "startDate": start.isoformat(),
            "endDate": end.isoformat(),
            "rank": [
                {
                    "rank": i + 1,
                    "customerId": r["customer_id"],
                    "customerName": r["customer_name"],
                    "orderCount": r["order_count"],
                    **_money_fields(r["total_amount"], r["tax_amount"]),
                    "paid": float(r["paid"]),
                }
                for i, r in enumerate(rows)
            ],
        },
        "message": "ok",
    }


@router.get("/product-rank")
async def product_rank(
    factory_id: str,
    request: Request,
    startDate: str | None = None,
    endDate: str | None = None,
    limit: int = 20,
):
    """产品销售排行 top N: by total revenue + qty."""
    caller = _enforce_factory_match(factory_id, request)
    start, end = _date_range(startDate, endDate)
    if not (1 <= limit <= 100):
        raise HTTPException(400, "limit must be 1..100")

    pool = await _get_pool()
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", caller)
        rows = await conn.fetch(
            """
            SELECT soi.product_type_id,
                   MAX(soi.product_name) AS product_name,
                   SUM(soi.quantity) AS total_qty,
                   MAX(soi.unit) AS unit,
                   SUM(COALESCE(soi.quantity, 0) * COALESCE(soi.unit_price, 0)
                       * (1 - COALESCE(soi.discount_rate, 0))) AS total_amount,
                   SUM(COALESCE(soi.quantity, 0) * COALESCE(soi.unit_price, 0)
                       * COALESCE(soi.tax_rate, 0) / 100
                       * (1 - COALESCE(soi.discount_rate, 0))) AS tax_amount,
                   SUM(COALESCE(soi.quantity, 0) * COALESCE(soi.unit_price, 0)
                       * (1 + COALESCE(soi.tax_rate, 0) / 100)
                       * (1 - COALESCE(soi.discount_rate, 0))) AS total_amount_with_tax,
                   COUNT(DISTINCT soi.sales_order_id) AS order_count
            FROM sales_order_items soi
            JOIN sales_orders so ON so.id = soi.sales_order_id
            WHERE so.factory_id = $1 AND so.order_date BETWEEN $2 AND $3
              AND so.status NOT IN ('DRAFT', 'CANCELLED')
              AND so.deleted_at IS NULL
              AND soi.deleted_at IS NULL
            GROUP BY soi.product_type_id
            ORDER BY total_amount_with_tax DESC NULLS LAST
            LIMIT $4
            """,
            caller, start, end, limit,
        )
    return {
        "success": True,
        "data": {
            "startDate": start.isoformat(),
            "endDate": end.isoformat(),
            "rank": [
                {
                    "rank": i + 1,
                    "productTypeId": r["product_type_id"],
                    "productName": r["product_name"],
                    "totalQty": float(r["total_qty"]) if r["total_qty"] is not None else 0.0,
                    "unit": r["unit"],
                    **_money_fields(r["total_amount"], r["tax_amount"]),
                    "orderCount": r["order_count"],
                }
                for i, r in enumerate(rows)
            ],
        },
        "message": "ok",
    }


# ─── 经营分析 4 张 (业务员 / 区域 / 增长率 / 毛利率) ──────────────────────
#
# 口径约定 (与上面 top 5 一致):
#   - 订单级金额取 sales_orders.total_amount (未税) + tax_amount;
#   - 只算 status NOT IN ('DRAFT', 'CANCELLED') 且未软删的订单;
#   - 「不知道」一律给 None, ⛔ 不翻译成 0 (没填业务员 / 地址识别不了 / 没回填成本
#     各自单独计数返回, 由前端明确展示)。


def _validated_range(start: Optional[str], end: Optional[str]) -> tuple[date, date]:
    s, e = _date_range(start, end)
    if s > e:
        raise HTTPException(400, detail="startDate must not be after endDate")
    return s, e


def _shift_year(d: date, years: int) -> date:
    try:
        return d.replace(year=d.year + years)
    except ValueError:  # 2 月 29 日 → 平年取 2 月 28 日
        return d.replace(year=d.year + years, day=28)


def _rate(current: float, base: Optional[float]) -> Optional[float]:
    """(current - base) / base; 基数缺失或 <= 0 时无法计算, 返回 None。"""
    if base is None or base <= 0:
        return None
    return (current - base) / base


def _with_tax(total_amount, tax_amount) -> float:
    return float(total_amount or 0) + float(tax_amount or 0)


def _salesperson_payload(rows: list) -> dict:
    """rows: 每行一个业务员分组 (sp_key 为 None 表示订单上没填业务员)。"""
    grand_with_tax = sum(_with_tax(r["total_amount"], r["tax_amount"]) for r in rows)
    grand_orders = sum(int(r["order_count"]) for r in rows)
    assigned = [r for r in rows if r["sp_key"] is not None]
    unassigned = [r for r in rows if r["sp_key"] is None]
    assigned.sort(key=lambda r: _with_tax(r["total_amount"], r["tax_amount"]), reverse=True)

    def share(r) -> Optional[float]:
        if grand_with_tax <= 0:
            return None
        return _with_tax(r["total_amount"], r["tax_amount"]) / grand_with_tax

    unassigned_row = unassigned[0] if unassigned else None
    return {
        "rank": [
            {
                "rank": i + 1,
                "salespersonId": r["salesperson_id"],
                "salespersonName": r["salesperson_name"],
                "orderCount": int(r["order_count"]),
                "customerCount": int(r["customer_count"]),
                **_money_fields(r["total_amount"], r["tax_amount"]),
                "paid": float(r["paid"] or 0),
                "unpaid": _with_tax(r["total_amount"], r["tax_amount"]) - float(r["paid"] or 0),
                "share": share(r),
            }
            for i, r in enumerate(assigned)
        ],
        "unassigned": {
            "orderCount": int(unassigned_row["order_count"]) if unassigned_row else 0,
            **_money_fields(
                unassigned_row["total_amount"] if unassigned_row else 0,
                unassigned_row["tax_amount"] if unassigned_row else 0,
            ),
            "share": share(unassigned_row) if unassigned_row else None,
        },
        "summary": {
            "orderCount": grand_orders,
            "assignedOrderCount": grand_orders - (int(unassigned_row["order_count"]) if unassigned_row else 0),
            "totalAmountWithTax": grand_with_tax,
        },
    }


@router.get("/salesperson-performance")
async def salesperson_performance(
    factory_id: str,
    request: Request,
    startDate: str | None = None,
    endDate: str | None = None,
):
    """业绩 by 业务员: 按销售订单上的业务员汇总; 没填业务员的订单单独计数。"""
    caller = _enforce_factory_match(factory_id, request)
    start, end = _validated_range(startDate, endDate)

    pool = await _get_pool()
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", caller)
        rows = await conn.fetch(
            """
            SELECT CASE
                     WHEN so.salesperson_id IS NOT NULL THEN 'id:' || so.salesperson_id::text
                     WHEN NULLIF(TRIM(so.salesperson), '') IS NOT NULL THEN 'name:' || TRIM(so.salesperson)
                   END AS sp_key,
                   MAX(so.salesperson_id) AS salesperson_id,
                   MAX(COALESCE(NULLIF(TRIM(u.full_name), ''), NULLIF(TRIM(so.salesperson), ''), u.username))
                     AS salesperson_name,
                   COUNT(*) AS order_count,
                   COUNT(DISTINCT so.customer_id) AS customer_count,
                   COALESCE(SUM(so.total_amount), 0) AS total_amount,
                   COALESCE(SUM(so.tax_amount), 0) AS tax_amount,
                   COALESCE(SUM(so.paid_amount), 0) AS paid
            FROM sales_orders so
            LEFT JOIN users u ON u.id = so.salesperson_id
            WHERE so.factory_id = $1 AND so.order_date BETWEEN $2 AND $3
              AND so.status NOT IN ('DRAFT', 'CANCELLED')
              AND so.deleted_at IS NULL
            GROUP BY 1
            """,
            caller, start, end,
        )
    return {
        "success": True,
        "data": {"startDate": start.isoformat(), "endDate": end.isoformat(), **_salesperson_payload(list(rows))},
        "message": "ok",
    }


@router.get("/aging")
async def aging(factory_id: str, request: Request):
    _enforce_factory_match(factory_id, request)
    return _stub("应收账龄 (30/60/90/120/180+)")


@router.get("/collection-rate")
async def collection_rate(factory_id: str, request: Request):
    _enforce_factory_match(factory_id, request)
    return _stub("客户回款率")


@router.get("/return-rate")
async def return_rate(factory_id: str, request: Request):
    _enforce_factory_match(factory_id, request)
    return _stub("退货率")


_GROSS_MARGIN_FORBIDDEN = (
    "销售毛利率报表含成本数据, 需要价格查看权限 (工厂超管 / 财务经理 / 销售经理 / 采购经理等), "
    "请联系管理员开通"
)


def _gross_margin_row(r) -> dict:
    revenue = float(r["revenue"] or 0)
    costed_revenue = float(r["costed_revenue"] or 0)
    costed_lines = int(r["costed_line_count"])
    cost = float(r["cost"]) if costed_lines > 0 and r["cost"] is not None else None
    gross_profit = costed_revenue - cost if cost is not None else None
    margin_rate = (
        gross_profit / costed_revenue
        if gross_profit is not None and costed_revenue > 0 else None
    )
    return {
        "productTypeId": r["product_type_id"],
        "productName": r["product_name"],
        "unit": r["unit"],
        "totalQty": float(r["total_qty"] or 0),
        "orderCount": int(r["order_count"]),
        "revenue": revenue,
        "costedRevenue": costed_revenue,
        "cost": cost,
        "grossProfit": gross_profit,
        "marginRate": margin_rate,
        "costCoverage": costed_revenue / revenue if revenue > 0 else None,
        "lineCount": int(r["line_count"]),
        "costedLineCount": costed_lines,
    }


def _gross_margin_payload(rows: list, order: str, limit: int) -> dict:
    """毛利只在「已回填成本」的明细上算: 收入与成本同口径 (都是未税、折后)。

    没回填成本的明细不计入毛利, 用 costCoverage 告诉用户这个毛利率覆盖了多少收入。
    """
    products = [_gross_margin_row(r) for r in rows]
    with_margin = [p for p in products if p["marginRate"] is not None]
    without_margin = [p for p in products if p["marginRate"] is None]
    with_margin.sort(key=lambda p: p["marginRate"], reverse=(order == "desc"))
    without_margin.sort(key=lambda p: p["revenue"], reverse=True)
    ranked = (with_margin + without_margin)[:limit]

    revenue = sum(p["revenue"] for p in products)
    costed_revenue = sum(p["costedRevenue"] for p in products)
    costs = [p["cost"] for p in products if p["cost"] is not None]
    cost = sum(costs) if costs else None
    gross_profit = costed_revenue - cost if cost is not None else None
    return {
        "rank": [{"rank": i + 1, **p} for i, p in enumerate(ranked)],
        "summary": {
            "productCount": len(products),
            "costedProductCount": len(with_margin),
            "revenue": revenue,
            "costedRevenue": costed_revenue,
            "cost": cost,
            "grossProfit": gross_profit,
            "marginRate": (
                gross_profit / costed_revenue
                if gross_profit is not None and costed_revenue > 0 else None
            ),
            "costCoverage": costed_revenue / revenue if revenue > 0 else None,
        },
    }


@router.get("/gross-margin")
async def gross_margin(
    factory_id: str,
    request: Request,
    startDate: str | None = None,
    endDate: str | None = None,
    order: str = "desc",
    limit: int = 50,
):
    """销售毛利率 by 产品: 折后未税收入 − 未税成本 (sales_order_items.cost_unit_price)。

    含成本数据, 只对 PRICE_VIEW_ROLES 开放 (与 Java @PriceSensitive 同一份白名单)。
    """
    caller = _enforce_factory_match(factory_id, request)
    from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES
    role = str(getattr(request.state, "role", "") or "").strip().lower()
    if role not in PRICE_VIEW_ROLES:
        raise HTTPException(403, detail=_GROSS_MARGIN_FORBIDDEN)
    if order not in ("desc", "asc"):
        raise HTTPException(400, detail="order must be desc or asc")
    if not (1 <= limit <= 200):
        raise HTTPException(400, detail="limit must be 1..200")
    start, end = _validated_range(startDate, endDate)

    pool = await _get_pool()
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", caller)
        rows = await conn.fetch(
            """
            SELECT soi.product_type_id,
                   MAX(soi.product_name) AS product_name,
                   MAX(soi.unit) AS unit,
                   SUM(COALESCE(soi.quantity, 0)) AS total_qty,
                   COUNT(DISTINCT soi.sales_order_id) AS order_count,
                   COUNT(*) AS line_count,
                   COUNT(*) FILTER (WHERE soi.cost_unit_price IS NOT NULL) AS costed_line_count,
                   SUM(COALESCE(soi.quantity, 0) * COALESCE(soi.unit_price, 0)
                       * (1 - COALESCE(soi.discount_rate, 0))) AS revenue,
                   SUM(COALESCE(soi.quantity, 0) * COALESCE(soi.unit_price, 0)
                       * (1 - COALESCE(soi.discount_rate, 0)))
                     FILTER (WHERE soi.cost_unit_price IS NOT NULL) AS costed_revenue,
                   SUM(COALESCE(soi.quantity, 0) * soi.cost_unit_price)
                     FILTER (WHERE soi.cost_unit_price IS NOT NULL) AS cost
            FROM sales_order_items soi
            JOIN sales_orders so ON so.id = soi.sales_order_id
            WHERE so.factory_id = $1 AND so.order_date BETWEEN $2 AND $3
              AND so.status NOT IN ('DRAFT', 'CANCELLED')
              AND so.deleted_at IS NULL
              AND soi.deleted_at IS NULL
            GROUP BY soi.product_type_id
            """,
            caller, start, end,
        )
    return {
        "success": True,
        "data": {
            "startDate": start.isoformat(),
            "endDate": end.isoformat(),
            "order": order,
            **_gross_margin_payload(list(rows), order, limit),
        },
        "message": "ok",
    }


@router.get("/purchase-frequency")
async def purchase_frequency(factory_id: str, request: Request):
    _enforce_factory_match(factory_id, request)
    return _stub("客户购买频次")


def _parse_year_month(s: Optional[str]) -> tuple[int, int]:
    if s is None:
        today = date.today()
        return today.year, today.month
    try:
        y, m = map(int, s.split("-"))
        date(y, m, 1)
    except ValueError as e:
        raise HTTPException(400, detail="endMonth must be YYYY-MM") from e
    return y, m


def _add_months(y: int, m: int, delta: int) -> tuple[int, int]:
    idx = y * 12 + (m - 1) + delta
    return idx // 12, idx % 12 + 1


def _growth_payload(rows: list, end_y: int, end_m: int, months: int, today: date) -> dict:
    """rows: 每行一个自然月 (m = 当月 1 日)。窗口内没有订单的月份是真实的 0。

    环比 / 同比的基数取窗口外也查回来的月份 (查询多拉了 12 个月),
    基数 <= 0 时比率为 None (无法计算), ⛔ 不给 0%。
    """
    by_month: dict[tuple[int, int], dict] = {}
    for r in rows:
        d = r["m"]
        by_month[(d.year, d.month)] = {
            "orderCount": int(r["order_count"]),
            **_money_fields(r["total_amount"], r["tax_amount"]),
        }
    empty = {"orderCount": 0, **_money_fields(0, 0)}

    out = []
    for k in range(months - 1, -1, -1):
        y, m = _add_months(end_y, end_m, -k)
        cur = by_month.get((y, m), empty)
        prev = by_month.get(_add_months(y, m, -1), empty)
        last_year = by_month.get((y - 1, m), empty)
        out.append({
            "yearMonth": f"{y:04d}-{m:02d}",
            **cur,
            "prevMonthAmountWithTax": prev["totalAmountWithTax"],
            "lastYearAmountWithTax": last_year["totalAmountWithTax"],
            "momRate": _rate(cur["totalAmountWithTax"], prev["totalAmountWithTax"]),
            "yoyRate": _rate(cur["totalAmountWithTax"], last_year["totalAmountWithTax"]),
            "isPartial": (y, m) == (today.year, today.month),
        })
    return {"months": out}


@router.get("/growth-rate")
async def growth_rate(
    factory_id: str,
    request: Request,
    endMonth: str | None = None,
    months: int = 12,
):
    """月度增长率: 最近 N 个月的含税销售额 + 环比 (MoM) + 同比 (YoY)。"""
    caller = _enforce_factory_match(factory_id, request)
    if not (1 <= months <= 36):
        raise HTTPException(400, detail="months must be 1..36")
    end_y, end_m = _parse_year_month(endMonth)
    first_y, first_m = _add_months(end_y, end_m, -(months - 1) - 12)
    start = date(first_y, first_m, 1)
    next_y, next_m = _add_months(end_y, end_m, 1)
    end = date(next_y, next_m, 1) - timedelta(days=1)

    pool = await _get_pool()
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", caller)
        rows = await conn.fetch(
            """
            SELECT date_trunc('month', so.order_date)::date AS m,
                   COUNT(*) AS order_count,
                   COALESCE(SUM(so.total_amount), 0) AS total_amount,
                   COALESCE(SUM(so.tax_amount), 0) AS tax_amount
            FROM sales_orders so
            WHERE so.factory_id = $1 AND so.order_date BETWEEN $2 AND $3
              AND so.status NOT IN ('DRAFT', 'CANCELLED')
              AND so.deleted_at IS NULL
            GROUP BY 1
            ORDER BY 1
            """,
            caller, start, end,
        )
    return {
        "success": True,
        "data": {
            "endMonth": f"{end_y:04d}-{end_m:02d}",
            **_growth_payload(list(rows), end_y, end_m, months, date.today()),
        },
        "message": "ok",
    }


_NO_ADDRESS = "__NO_ADDRESS__"
_UNRECOGNIZED = "__UNRECOGNIZED__"


def _region_payload(rows: list, cur_start: date, cur_end: date, level: str) -> dict:
    """rows: 每行一张订单 (order_date / address / total_amount / tax_amount / customer_id),
    覆盖本期与去年同期。地址为空 → 「无地址」; 解析不出省 → 「未识别地区」, 两者都不并入任何区域。
    """
    from smartbi.services.address_region import big_region_of, province_full_name, province_short_of

    cur: dict[str, dict] = {}
    prev: dict[str, float] = {}
    for r in rows:
        address = r["address"]
        if address is None or not str(address).strip():
            key = _NO_ADDRESS
        else:
            short = province_short_of(address)
            if short is None:
                key = _UNRECOGNIZED
            elif level == "region":
                key = big_region_of(short)
            else:
                key = short
        with_tax = _with_tax(r["total_amount"], r["tax_amount"])
        if cur_start <= r["order_date"] <= cur_end:
            bucket = cur.setdefault(key, {"orders": 0, "customers": set(), "total": 0.0, "tax": 0.0})
            bucket["orders"] += 1
            if r["customer_id"] is not None:
                bucket["customers"].add(r["customer_id"])
            bucket["total"] += float(r["total_amount"] or 0)
            bucket["tax"] += float(r["tax_amount"] or 0)
        else:
            prev[key] = prev.get(key, 0.0) + with_tax

    grand = sum(b["total"] + b["tax"] for b in cur.values())

    def entry(key: str) -> dict:
        b = cur.get(key, {"orders": 0, "customers": set(), "total": 0.0, "tax": 0.0})
        cur_with_tax = b["total"] + b["tax"]
        prev_with_tax = prev.get(key)
        return {
            "orderCount": b["orders"],
            "customerCount": len(b["customers"]),
            **_money_fields(b["total"], b["tax"]),
            "lastYearAmountWithTax": prev_with_tax if prev_with_tax is not None else 0.0,
            "yoyRate": _rate(cur_with_tax, prev_with_tax),
            "share": cur_with_tax / grand if grand > 0 else None,
        }

    region_keys = (set(cur) | set(prev)) - {_NO_ADDRESS, _UNRECOGNIZED}
    regions = []
    for key in region_keys:
        label = key if level == "region" else province_full_name(key)
        regions.append({"region": key, "label": label, **entry(key)})
    regions.sort(key=lambda x: (x["totalAmountWithTax"], x["lastYearAmountWithTax"]), reverse=True)

    recognized = sum(x["totalAmountWithTax"] for x in regions)
    return {
        "regions": [{"rank": i + 1, **x} for i, x in enumerate(regions)],
        "noAddress": entry(_NO_ADDRESS),
        "unrecognized": entry(_UNRECOGNIZED),
        "summary": {
            "orderCount": sum(b["orders"] for b in cur.values()),
            "totalAmountWithTax": grand,
            "recognizedShare": recognized / grand if grand > 0 else None,
        },
    }


@router.get("/regional-comparison")
async def regional_comparison(
    factory_id: str,
    request: Request,
    startDate: str | None = None,
    endDate: str | None = None,
    level: str = "province",
):
    """区域销售对比: 按收货地址解析省份 (level=province) 或大区 (level=region),
    并与去年同期对比。地址取订单收货地址, 为空时退回客户收货地址、账单地址。
    """
    caller = _enforce_factory_match(factory_id, request)
    if level not in ("province", "region"):
        raise HTTPException(400, detail="level must be province or region")
    start, end = _validated_range(startDate, endDate)
    if (end - start).days > 365:
        # 超过一年时本期与去年同期重叠, 重叠部分的订单只会被算进本期 → 同比失真
        raise HTTPException(400, detail="区域对比的日期范围不能超过 1 年 (需与去年同期对比)")
    prev_start, prev_end = _shift_year(start, -1), _shift_year(end, -1)

    pool = await _get_pool()
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", caller)
        rows = await conn.fetch(
            """
            SELECT so.order_date,
                   so.customer_id,
                   COALESCE(NULLIF(TRIM(so.delivery_address), ''),
                            NULLIF(TRIM(c.shipping_address), ''),
                            NULLIF(TRIM(c.billing_address), '')) AS address,
                   so.total_amount,
                   so.tax_amount
            FROM sales_orders so
            LEFT JOIN customers c ON c.id = so.customer_id
            WHERE so.factory_id = $1
              AND (so.order_date BETWEEN $2 AND $3 OR so.order_date BETWEEN $4 AND $5)
              AND so.status NOT IN ('DRAFT', 'CANCELLED')
              AND so.deleted_at IS NULL
            """,
            caller, start, end, prev_start, prev_end,
        )
    return {
        "success": True,
        "data": {
            "startDate": start.isoformat(),
            "endDate": end.isoformat(),
            "lastYearStartDate": prev_start.isoformat(),
            "lastYearEndDate": prev_end.isoformat(),
            "level": level,
            **_region_payload(list(rows), start, end, level),
        },
        "message": "ok",
    }


@router.get("/customer-tier")
async def customer_tier(factory_id: str, request: Request):
    _enforce_factory_match(factory_id, request)
    return _stub("客户分级销售贡献")
