"""Publish the latest Blacklake work-order costs and material issues."""
from __future__ import annotations

import json
from datetime import date
from decimal import Decimal

import asyncpg

from .external_production_etl import DEMO_FACTORY_PREFIX, _quantity

_LATEST_SQL = """
    SELECT DISTINCT ON (s.dataset)
        s.id, s.dataset, s.collected_at AT TIME ZONE 'UTC' AS collected_at, s.row_count, s.payload
    FROM external_source_snapshots AS s
    JOIN factory_external_integrations AS i USING (factory_id, external_system)
    WHERE s.factory_id = $1 AND s.external_system = 'blacklake' AND i.status = 'ACTIVE'
      AND s.dataset IN ('project_cost', 'production_issue')
    ORDER BY s.dataset, s.collected_at DESC, s.id DESC
"""

_COST_COLUMNS = {
    "cost_date": "costDate", "product_code": "productCode", "product_name": "productName",
    "output_unit": "outputUnit", "output_qty": "outputQty", "material_cost": "materialCost",
    "labor_cost": "laborCost", "other_cost": "otherCost", "total_cost": "totalCost",
    "unit_cost": "unitCost",
}
_ISSUE_COLUMNS = {
    "issue_date": "issueDate", "work_order_code": "workOrderCode", "material_code": "materialCode",
    "material_name": "materialName", "unit": "unit", "quantity": "quantity",
    "cost_unit_price": "costUnitPrice", "signed_cost_amount": "signedCostAmount",
    "moving_average_price": "movingAveragePrice", "effective_unit_price": "effectiveUnitPrice",
    "issue_cost": "issueCost", "price_source": "priceSource",
}


def _optional_quantity(value) -> Decimal | None:
    return None if value is None else _quantity(value)


def _cost_values(row: dict) -> tuple:
    amounts = [_optional_quantity(row.get(field)) for field in (
        "outputQty", "materialCost", "laborCost", "otherCost", "totalCost", "unitCost",
    )]
    return (date.fromisoformat(row["costDate"]), row["productCode"], row.get("productName"),
            row.get("outputUnit"), *amounts)


def _issue_values(row: dict) -> tuple:
    quantity = _quantity(row["quantity"])
    reported, signed, moving = (_optional_quantity(row.get(key)) for key in (
        "costUnitPrice", "signedCostAmount", "movingAveragePrice",
    ))
    invalid_reported = reported and (reported < 0 or signed is None or signed >= 0)
    invalid_fallback = not reported and moving is not None and moving < 0
    if quantity <= 0 or invalid_reported or invalid_fallback:
        price, amount, source = None, None, "invalid_valuation"
    elif reported:
        # Blacklake signed stock decreases correspond to positive issue cost.
        price, amount, source = reported, -signed, "reported"
    elif moving:
        price, amount, source = moving, quantity * moving, "moving_avg"
    else:
        price, amount, source = None, None, "price_missing"
    return (date.fromisoformat(row["issueDate"]), row.get("workOrderCode"), row["materialCode"],
            row.get("materialName"), row.get("unit"), quantity, reported, signed, moving,
            price, amount, source)


# These identifiers are fixed by the two schemas, never supplied by a caller.
_DATASETS = {
    "project_cost": ("fact_external_work_order_cost", "work_order_code", "workOrderCode",
                     _COST_COLUMNS, _cost_values),
    "production_issue": ("fact_external_material_issue", "line_id", "lineId",
                         _ISSUE_COLUMNS, _issue_values),
}


async def _publish(conn: asyncpg.Connection, factory: str, snapshot: asyncpg.Record) -> None:
    dataset = snapshot["dataset"]
    snapshot_id, collected_at = snapshot["id"], snapshot["collected_at"]
    current = await conn.fetchrow(
        """SELECT snapshot_id, snapshot_collected_at FROM external_cost_snapshot_state
           WHERE factory_id = $1 AND source_system = 'blacklake' AND dataset = $2""",
        factory, dataset,
    )
    if current and (collected_at, snapshot_id) <= (current["snapshot_collected_at"], current["snapshot_id"]):
        return

    payload = json.loads(snapshot["payload"], parse_float=Decimal)
    if not isinstance(payload, list) or len(payload) != snapshot["row_count"]:
        raise ValueError(f"{dataset} row_count does not match payload")
    table, key_column, key_field, fields, values = _DATASETS[dataset]
    columns = (*fields, "snapshot_id", "snapshot_collected_at")
    rows = [(factory, row[key_field], *values(row), snapshot_id, collected_at) for row in payload]
    if len({row[1] for row in rows}) != len(rows):
        raise ValueError(f"{dataset} contains duplicate row keys")
    if rows:
        params = ", ".join(f"${i}" for i in range(3, len(columns) + 3))
        updates = ", ".join(f"{col} = EXCLUDED.{col}" for col in columns)
        await conn.executemany(
            f"""INSERT INTO {table} (factory_id, source_system, {key_column}, {', '.join(columns)})
                VALUES ($1, 'blacklake', $2, {params})
                ON CONFLICT (factory_id, source_system, {key_column}) DO UPDATE SET {updates}""",
            rows,
        )
    # The collector sends a partial window. An omitted key (or an empty
    # snapshot) carries no deletion signal for the historical facts.
    await conn.execute(
        """INSERT INTO external_cost_snapshot_state
               (factory_id, source_system, dataset, snapshot_id, snapshot_collected_at)
           VALUES ($1, 'blacklake', $2, $3, $4)
           ON CONFLICT (factory_id, source_system, dataset) DO UPDATE
           SET snapshot_id = EXCLUDED.snapshot_id, snapshot_collected_at = EXCLUDED.snapshot_collected_at""",
        factory, dataset, snapshot_id, collected_at,
    )


async def sync_external_cost(source: asyncpg.Pool, target: asyncpg.Pool, factory: str) -> dict:
    if factory.startswith(DEMO_FACTORY_PREFIX):
        return {"snapshots": 0, "errors": []}
    async with source.acquire() as conn, conn.transaction(readonly=True):
        snapshots = await conn.fetch(_LATEST_SQL, factory)
    if snapshots:
        async with target.acquire() as conn, conn.transaction():
            await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory)
            await conn.execute(
                "SELECT pg_advisory_xact_lock(hashtext('external_cost'), hashtext($1))", factory,
            )
            for snapshot in snapshots:
                await _publish(conn, factory, snapshot)
    return {"snapshots": len(snapshots), "errors": []}
