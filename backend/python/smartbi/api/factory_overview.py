"""工厂经营概览: 经营分析页顶部「销售概览」卡的销售订单汇总 (只读工厂业务库 cretas_db)。

原来这张卡读餐饮 POS 汇总 (账单/门店), 工厂账号下全是空的。现在读工厂自己的销售订单。

口径:
- 金额 = 订单 total_amount, 与 Java SalesServiceImpl.getSalesStatistics 一致; 不计已取消,
  另外也不计草稿和财务已驳回 (都还不是成交, 各自单独计数)。没有金额的订单单独计数, 不当成 0。
- 「在途」= Java OrderUsageWhitelists.SO_IN_FLIGHT (已确认 / 待财务审核 / 财务已批准 / 处理中)。
- 软删除的订单和明细 (deleted_at 非空) 不计。
- 工厂来自登录身份 (caller_factory), 不接受前端传入; 每条 SQL 都带 factory_id = $1。
"""
from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, HTTPException, Request

import smartbi.config as smartbi_config
from common.tenant_identity import caller_factory

router = APIRouter()

EXCLUDED_STATUSES = ("CANCELLED", "DRAFT", "FINANCE_REJECTED")
_EXCLUDED_SQL = ", ".join(f"'{s}'" for s in EXCLUDED_STATUSES)
IN_FLIGHT_STATUSES = ("CONFIRMED", "PENDING_FINANCE_REVIEW", "FINANCE_APPROVED", "PROCESSING")
TOP_N = 3

STATUS_SQL = """
SELECT so.status AS status,
       COUNT(*) AS orders,
       SUM(so.total_amount) AS amount,
       COUNT(so.total_amount) AS amount_rows,
       MIN(so.order_date) AS first_date,
       MAX(so.order_date) AS last_date
FROM sales_orders so
WHERE so.factory_id = $1 AND so.deleted_at IS NULL
GROUP BY so.status
"""

CUSTOMER_SQL = """
SELECT COALESCE(c.name, '未登记客户') AS customer_name,
       COUNT(*) AS orders,
       SUM(so.total_amount) AS amount
FROM sales_orders so
LEFT JOIN customers c ON c.id = so.customer_id AND c.factory_id = so.factory_id
WHERE so.factory_id = $1 AND so.deleted_at IS NULL
  AND so.status NOT IN ({excluded}) AND so.total_amount IS NOT NULL
GROUP BY COALESCE(c.name, '未登记客户')
ORDER BY SUM(so.total_amount) DESC
LIMIT $2
""".format(excluded=_EXCLUDED_SQL)

PRODUCT_SQL = """
SELECT i.product_name AS product_name,
       i.unit AS unit,
       SUM(i.quantity) AS quantity,
       SUM(ROUND(ROUND(i.quantity * i.unit_price, 2)
           * (1 - COALESCE(i.discount_rate, 0) / 100), 2)) AS amount
FROM sales_order_items i
JOIN sales_orders so ON so.id = i.sales_order_id
WHERE so.factory_id = $1 AND so.deleted_at IS NULL AND i.deleted_at IS NULL
  AND so.status NOT IN ({excluded})
  AND i.quantity IS NOT NULL AND i.unit_price IS NOT NULL
GROUP BY i.product_name, i.unit
ORDER BY SUM(ROUND(ROUND(i.quantity * i.unit_price, 2)
           * (1 - COALESCE(i.discount_rate, 0) / 100), 2)) DESC
LIMIT $2
""".format(excluded=_EXCLUDED_SQL)


def _num(value: Any) -> Optional[float]:
    if value is None:
        return None
    return float(value) if isinstance(value, Decimal) else float(value)


def _iso(value: Optional[date]) -> Optional[str]:
    return value.isoformat() if value else None


def summarize(status_rows: List[Dict[str, Any]], customer_rows: List[Dict[str, Any]],
              product_rows: List[Dict[str, Any]]) -> Dict[str, Any]:
    by_status = {r["status"]: r for r in status_rows}
    effective = [r for s, r in by_status.items() if s not in EXCLUDED_STATUSES]

    amounts = [r["amount"] for r in effective if r["amount"] is not None]
    first_dates = [r["first_date"] for r in effective if r["first_date"]]
    last_dates = [r["last_date"] for r in effective if r["last_date"]]

    return {
        "orderCount": sum(int(r["orders"]) for r in status_rows),
        "effectiveOrderCount": sum(int(r["orders"]) for r in effective),
        "cancelledCount": int(by_status.get("CANCELLED", {}).get("orders", 0)),
        "draftCount": int(by_status.get("DRAFT", {}).get("orders", 0)),
        "rejectedCount": int(by_status.get("FINANCE_REJECTED", {}).get("orders", 0)),
        "inFlightCount": sum(int(by_status[s]["orders"]) for s in IN_FLIGHT_STATUSES if s in by_status),
        # 有效订单里一张有金额的都没有时为 None (不是 0)
        "orderAmount": _num(sum(amounts, Decimal(0))) if amounts else None,
        "ordersWithoutAmount": sum(int(r["orders"]) - int(r["amount_rows"]) for r in effective),
        "firstOrderDate": _iso(min(first_dates)) if first_dates else None,
        "lastOrderDate": _iso(max(last_dates)) if last_dates else None,
        "topCustomers": [
            {"name": r["customer_name"], "orders": int(r["orders"]), "amount": _num(r["amount"])}
            for r in customer_rows
        ],
        "topProducts": [
            {"name": r["product_name"], "unit": r["unit"], "quantity": _num(r["quantity"]), "amount": _num(r["amount"])}
            for r in product_rows
        ],
    }


@router.get("/sales")
async def sales_summary(request: Request):
    """本厂全部历史的销售订单汇总: 单数 / 金额 / 在途 / 前几名客户与产品。"""
    factory_id = caller_factory(request)
    pool = await smartbi_config.get_cretas_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="业务数据库未配置，暂时无法汇总销售订单")
    async with pool.acquire() as conn:
        status_rows = [dict(r) for r in await conn.fetch(STATUS_SQL, factory_id)]
        customer_rows = [dict(r) for r in await conn.fetch(CUSTOMER_SQL, factory_id, TOP_N)]
        product_rows = [dict(r) for r in await conn.fetch(PRODUCT_SQL, factory_id, TOP_N)]
    return {"success": True, "data": summarize(status_rows, customer_rows, product_rows), "message": "ok"}
