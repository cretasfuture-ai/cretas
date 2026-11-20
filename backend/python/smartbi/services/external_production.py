"""Tenant-scoped read model for daily external production facts."""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal

import asyncpg

from smartbi import config
from smartbi.gold.external_production_etl import DEMO_FACTORY_PREFIX

SOURCE_LABEL = "黑湖"
SOURCE_KEY = "blacklake-production"
FINISHED_PROCESS = "成品入库"
UNAVAILABLE_MESSAGE = "黑湖数据不含投入量，无法计算"
_MAX_ROWS = 20_000


@dataclass
class ExternalProduction:
    collected_at: datetime
    rows: list[asyncpg.Record]

    @property
    def stale(self) -> bool:
        return datetime.now(timezone.utc) - self.collected_at > timedelta(hours=36)


def defect_rate(good: Decimal, defect: Decimal) -> Decimal | None:
    denominator = good + defect
    return defect * 100 / denominator if denominator > 0 else None


async def _has_native_production(conn, factory_id: str, start: date | None, end: date | None) -> bool:
    return await conn.fetchval(
        """SELECT EXISTS (
               SELECT 1 FROM fact_production_report WHERE factory_id = $1
                AND ($2::date IS NULL OR report_date >= $2)
                AND ($3::date IS NULL OR report_date <= $3)
           ) OR EXISTS (
               SELECT 1 FROM fact_production_batch WHERE factory_id = $1
                AND ($2::date IS NULL OR stat_date >= $2)
                AND ($3::date IS NULL OR stat_date <= $3)
           )""",
        factory_id, start, end,
    )


async def load_external_production(
    factory_id: str, start: date | None, end: date | None,
    *, summary_only: bool = False,
) -> ExternalProduction | None:
    if factory_id.startswith(DEMO_FACTORY_PREFIX):
        return None
    pool = await config.get_pg_pool()
    if pool is None:
        raise RuntimeError("SmartBI database unavailable")
    async with pool.acquire() as conn, conn.transaction(readonly=True, isolation="repeatable_read"):
        await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
        snapshot = await conn.fetchrow(
            """SELECT snapshot_id, snapshot_collected_at FROM external_production_snapshot_state
               WHERE factory_id = $1 AND source_system = 'blacklake'""",
            factory_id,
        )
        if snapshot is None:
            return None
        # All-history discovery still includes a current external feed when
        # the only native production is older than the latest observed window.
        native_start = start
        if start is None and end is None:
            native_start = await conn.fetchval(
                """SELECT MIN(report_date) FROM fact_external_production_daily
                   WHERE factory_id = $1 AND source_system = 'blacklake' AND snapshot_id = $2""",
                factory_id, snapshot["snapshot_id"],
            )
        if await _has_native_production(conn, factory_id, native_start, end):
            return None
        rows = [] if summary_only else await conn.fetch(
            """SELECT report_date, product_code, product_name, process_name, unit,
                      good_qty, defect_qty, report_count
               FROM fact_external_production_daily
               WHERE factory_id = $1 AND source_system = 'blacklake'
                 AND ($2::date IS NULL OR report_date >= $2)
                 AND ($3::date IS NULL OR report_date <= $3)
               ORDER BY report_date, product_code, process_name, unit
               LIMIT $4""",
            factory_id, start, end, _MAX_ROWS + 1,
        )
    if len(rows) > _MAX_ROWS:
        raise ValueError("黑湖报工明细超过读取上限，请缩小日期范围")
    return ExternalProduction(snapshot["snapshot_collected_at"], rows)


def external_production_payload(data: ExternalProduction) -> dict:
    return {
        "sourceSystem": "blacklake",
        "sourceLabel": SOURCE_LABEL,
        "snapshotCollectedAt": data.collected_at.isoformat(),
        "stale": data.stale,
        "yieldRate": None,
        "cost": None,
        "unavailableMessage": UNAVAILABLE_MESSAGE,
        "rows": [
            {
                "reportDate": row["report_date"].isoformat(),
                "productCode": row["product_code"],
                "productName": row["product_name"],
                "processName": row["process_name"],
                "unit": row["unit"],
                "goodQty": row["good_qty"],
                "defectQty": row["defect_qty"],
                "defectRate": defect_rate(row["good_qty"], row["defect_qty"]),
                "reportCount": row["report_count"],
            }
            for row in data.rows
        ],
    }


@dataclass
class ExternalCost:
    snapshots: dict[str, datetime]
    work_orders: list[asyncpg.Record]
    issues: list[asyncpg.Record]


async def load_external_cost(factory_id: str, start: date | None, end: date | None) -> ExternalCost | None:
    if factory_id.startswith(DEMO_FACTORY_PREFIX):
        return None
    source = await config.get_cretas_pool()
    if source is None:
        raise RuntimeError("Cretas database unavailable")
    async with source.acquire() as conn:
        active = await conn.fetchval(
            """SELECT EXISTS (
                SELECT 1 FROM factory_external_integrations
                WHERE factory_id = $1 AND external_system = 'blacklake' AND status = 'ACTIVE'
            )""",
            factory_id,
        )
    if not active:
        return None
    pool = await config.get_pg_pool()
    if pool is None:
        raise RuntimeError("SmartBI database unavailable")
    async with pool.acquire() as conn, conn.transaction(readonly=True, isolation="repeatable_read"):
        await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
        native_start = start
        if start is None and end is None:
            native_start = await conn.fetchval(
                """SELECT LEAST(
                       (SELECT MIN(cost_date) FROM fact_external_work_order_cost
                        WHERE factory_id = $1 AND source_system = 'blacklake'),
                       (SELECT MIN(issue_date) FROM fact_external_material_issue
                        WHERE factory_id = $1 AND source_system = 'blacklake')
                   )""",
                factory_id,
            )
        if await _has_native_production(conn, factory_id, native_start, end):
            return None
        states = await conn.fetch(
            """SELECT dataset, snapshot_collected_at FROM external_cost_snapshot_state
               WHERE factory_id = $1 AND source_system = 'blacklake'""",
            factory_id,
        )
        snapshots = {row["dataset"]: row["snapshot_collected_at"] for row in states}
        work_orders = await conn.fetch(
            """SELECT work_order_code, cost_date, product_code, product_name, output_unit, output_qty,
                      material_cost, labor_cost, total_cost, unit_cost, snapshot_collected_at
               FROM fact_external_work_order_cost
               WHERE factory_id = $1 AND source_system = 'blacklake'
                 AND ($2::date IS NULL OR cost_date >= $2)
                 AND ($3::date IS NULL OR cost_date <= $3)
               ORDER BY cost_date DESC, work_order_code""",
            factory_id, start, end,
        )
        # An order can consume material on days outside the selected completion range.
        issues = await conn.fetch(
            """SELECT line_id, issue_date, work_order_code, material_code, material_name, unit, quantity,
                      effective_unit_price, issue_cost, price_source, signed_cost_amount, snapshot_collected_at
               FROM fact_external_material_issue
               WHERE factory_id = $1 AND source_system = 'blacklake'
                 AND ((($2::date IS NULL OR issue_date >= $2) AND ($3::date IS NULL OR issue_date <= $3))
                      OR work_order_code = ANY($4::text[]))
               ORDER BY issue_date DESC, line_id""",
            factory_id, start, end, [row["work_order_code"] for row in work_orders],
        )
    return ExternalCost(snapshots, work_orders, issues)


_MASS_UNITS = {
    "kg": Decimal(1), "公斤": Decimal(1), "千克": Decimal(1),
    "g": Decimal("0.001"), "克": Decimal("0.001"),
    "mg": Decimal("0.000001"), "毫克": Decimal("0.000001"),
    "斤": Decimal("0.5"), "t": Decimal(1000), "吨": Decimal(1000),
}


def _mass_factor(unit: str | None) -> Decimal | None:
    return _MASS_UNITS.get(unit.casefold()) if unit else None


def _quantities(issues: list) -> list[dict]:
    by_unit = defaultdict(Decimal)
    for issue in issues:
        unit = issue["unit"]
        factor = _mass_factor(unit)
        by_unit["kg" if factor else unit] += issue["quantity"] * (factor or 1)
    return [{"unit": unit, "quantity": qty}
            for unit, qty in sorted(by_unit.items(), key=lambda item: item[0] or "")]


def _order_yield(order, issues: list) -> tuple[Decimal | None, Decimal | None, str | None]:
    if not issues:
        return None, None, "missing_issue"
    factor = _mass_factor(order["output_unit"])
    issue_factors = [_mass_factor(row["unit"]) for row in issues]
    if factor is None or None in issue_factors:
        return None, None, "incompatible_units"
    if order["output_qty"] is None:
        return None, None, "missing_output"
    # The dataset watermarks can move even when a partial or empty snapshot
    # leaves these facts alone. Compare the actual inputs' collection times.
    if any(row["snapshot_collected_at"] != order["snapshot_collected_at"] for row in issues):
        return None, None, "snapshot_mismatch"
    if any(row["quantity"] <= 0 for row in issues):
        return None, None, "nonpositive_input"
    input_kg = sum((row["quantity"] * f for row, f in zip(issues, issue_factors)), Decimal(0))
    return order["output_qty"] * factor, input_kg, None


def _sum_present(values) -> Decimal | None:
    present = [value for value in values if value is not None]
    return sum(present, Decimal(0)) if present else None


def _reported_unit_cost(order) -> Decimal | None:
    cost = order["unit_cost"]
    if cost == 0 and (order["output_qty"] or 0) > 0:
        return None
    return cost


def _cost_metrics(entries: list, issues: list, *, issue_snapshot_available: bool) -> dict:
    orders_by_unit = defaultdict(list)
    missing_unit_costs = 0
    for order, *_ in entries:
        unit, qty, cost = order["output_unit"], order["output_qty"], _reported_unit_cost(order)
        if cost is None:
            missing_unit_costs += 1
        elif unit and qty is not None and qty > 0:
            # Unit cost is per source unit. Keep kg and g separate here so a
            # weighted price never combines unlike denominators.
            unit = unit.upper() if unit.isascii() else unit
            orders_by_unit[unit].append((qty, cost, order["material_cost"]))
    comparable = [(output, used) for _, output, used, _ in entries if used is not None]
    return {
        "materialCost": _sum_present(order["material_cost"] for order, *_ in entries),
        "materialCostMissingOrders": sum(order["material_cost"] is None for order, *_ in entries),
        "laborCost": _sum_present(order["labor_cost"] for order, *_ in entries),
        "laborCostMissingOrders": sum(order["labor_cost"] is None for order, *_ in entries),
        "totalCost": _sum_present(order["total_cost"] for order, *_ in entries),
        "costMissingOrders": sum(order["total_cost"] is None for order, *_ in entries),
        "unitCosts": [
            {
                "unit": unit,
                "cost": sum(qty * cost for qty, cost, _ in orders) / sum(qty for qty, *_ in orders),
                "workOrderCount": len(orders),
                "zeroMaterialCostOrders": sum(material == 0 for _, _, material in orders),
            }
            for unit, orders in sorted(orders_by_unit.items())
        ],
        "unitCostMissingOrders": missing_unit_costs,
        "inputByUnit": _quantities(issues),
        "issuedMaterialCost": _sum_present(row["issue_cost"] for row in issues),
        "yieldRate": (sum(output for output, _ in comparable) * 100 / sum(used for _, used in comparable)
                      if comparable else None),
        "yieldComparableOrders": len(comparable),
        "workOrderCount": len(entries),
        "priceMissingCount": (sum(row["price_source"] == "price_missing" for row in issues)
                              if issue_snapshot_available else None),
        "valuationIssueCount": (sum(row["price_source"] == "invalid_valuation" for row in issues)
                                if issue_snapshot_available else None),
        "overheadMissingCount": len(entries),
    }


def external_cost_payload(data: ExternalCost, *, permission_message: str | None = None) -> dict:
    cost_at = data.snapshots.get("project_cost")
    issue_at = data.snapshots.get("production_issue")
    result = {
        "sourceSystem": "blacklake", "sourceLabel": SOURCE_LABEL,
        "costCollectedAt": cost_at, "issueCollectedAt": issue_at,
        "stale": any(datetime.now(timezone.utc) - at > timedelta(hours=36) for at in data.snapshots.values()),
        "permissionMessage": permission_message,
        "summary": None, "daily": [], "products": [], "workOrders": [], "materialIssues": [],
    }
    if permission_message:
        return result
    orders, issues = data.work_orders, data.issues
    issues_by_order = defaultdict(list)
    for issue in issues:
        if issue["work_order_code"]:
            issues_by_order[issue["work_order_code"]].append(issue)
    by_day, by_product = defaultdict(list), defaultdict(list)
    entries = []
    for order in orders:
        code = order["work_order_code"]
        related = issues_by_order[code]
        output, used, reason = _order_yield(order, related)
        entry = (order, output, used, reason)
        entries.append(entry)
        by_day[order["cost_date"]].append(entry)
        by_product[order["product_code"]].append(entry)
        result["workOrders"].append({
            "workOrderCode": code, "costDate": order["cost_date"], "productCode": order["product_code"],
            "productName": order["product_name"], "outputUnit": order["output_unit"],
            "outputQty": order["output_qty"], "materialCost": order["material_cost"],
            "laborCost": order["labor_cost"], "totalCost": order["total_cost"], "unitCost": _reported_unit_cost(order),
            "inputByUnit": _quantities(related), "yieldRate": output * 100 / used if used is not None else None,
            "yieldUnavailableReason": reason,
        })

    def metrics(group):
        group_issues = [issue for order, *_ in group for issue in issues_by_order[order["work_order_code"]]]
        return _cost_metrics(group, group_issues, issue_snapshot_available=issue_at is not None)

    if orders or issues:
        result["summary"] = _cost_metrics(entries, issues, issue_snapshot_available=issue_at is not None)
    result["daily"] = [
        {
            "costDate": day, **metrics(group),
            # A positive signed stock movement on the same snapshot corroborates
            # a material credit on the order. Plot it on the order's cost date.
            "hasMaterialCredit": any(
                (order["material_cost"] or 0) < 0 and any(
                    (issue["signed_cost_amount"] or 0) > 0
                    and issue["snapshot_collected_at"] == order["snapshot_collected_at"]
                    for issue in issues_by_order[order["work_order_code"]]
                )
                for order, *_ in group
            ),
        }
        for day, group in sorted(by_day.items())
    ]
    result["products"] = [
        {"productCode": code, "productName": group[0][0]["product_name"], **metrics(group)}
        for code, group in sorted(by_product.items())
    ]
    result["materialIssues"] = [
        {
            "lineId": row["line_id"], "issueDate": row["issue_date"], "workOrderCode": row["work_order_code"],
            "materialCode": row["material_code"], "materialName": row["material_name"], "unit": row["unit"],
            "quantity": row["quantity"], "effectiveUnitPrice": row["effective_unit_price"],
            "issueCost": row["issue_cost"], "priceSource": row["price_source"],
        }
        for row in issues
    ]
    return result
