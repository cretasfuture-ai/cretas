"""Read report_output snapshots in Cretas and publish daily facts in SmartBI.

Run from backend/python: python -m smartbi.gold.external_production_etl
Use --factory-id to scope a run to a single factory.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
from datetime import date
from decimal import Decimal

import asyncpg

logger = logging.getLogger(__name__)
DEMO_FACTORY_PREFIX = "LSMDEMO"

_LATEST_SQL = """
    SELECT DISTINCT ON (factory_id, external_system)
        id, factory_id, external_system, collected_at AT TIME ZONE 'UTC' AS collected_at,
        row_count, payload
    FROM external_source_snapshots
    WHERE dataset = 'report_output'
      AND NOT starts_with(factory_id, $1)
      AND ($2::text IS NULL OR factory_id = $2)
    ORDER BY factory_id, external_system, external_source_snapshots.collected_at DESC, id DESC
"""

_INSERT_SQL = """
    INSERT INTO fact_external_production_daily (
        factory_id, source_system, report_date, product_code, product_name,
        process_name, unit, good_qty, defect_qty, report_count, snapshot_id,
        snapshot_collected_at
    ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
"""
_UPSERT_SQL = _INSERT_SQL + """
    ON CONFLICT (factory_id, source_system, report_date, product_code, process_name, unit)
    DO UPDATE SET product_name = EXCLUDED.product_name, good_qty = EXCLUDED.good_qty,
        defect_qty = EXCLUDED.defect_qty, report_count = EXCLUDED.report_count,
        snapshot_id = EXCLUDED.snapshot_id, snapshot_collected_at = EXCLUDED.snapshot_collected_at
    WHERE fact_external_production_daily.snapshot_id IS DISTINCT FROM EXCLUDED.snapshot_id
"""


def _quantity(value: str | int | Decimal) -> Decimal:
    if isinstance(value, bool) or not isinstance(value, (str, int, Decimal)):
        raise ValueError("quantity must be numeric")
    parsed = Decimal(value)
    if not parsed.is_finite():
        raise ValueError("quantity must be finite")
    return parsed


def _daily_rows(snapshot):
    payload = json.loads(snapshot["payload"], parse_float=Decimal)
    if not isinstance(payload, list) or len(payload) != snapshot["row_count"]:
        raise ValueError("report_output row_count does not match payload")
    grouped = {}
    for row in payload:
        day = date.fromisoformat(row["reportDate"])
        code, name, process, unit = (row[key] for key in (
            "productCode", "productName", "processName", "unit",
        ))
        if not all(isinstance(value, str) for value in (code, name, process, unit)):
            raise ValueError("report_output dimensions must be strings")
        good, defect = _quantity(row["goodQty"]), _quantity(row["defectQty"])
        count = row["reportCount"]
        if type(count) is not int or count < 0:
            raise ValueError("reportCount must be a nonnegative integer")
        key = (day, code, process, unit)
        if key in grouped:
            # The collector also groups by product name. A renamed SKU can
            # occupy two source rows for one fact key.
            old_name, old_good, old_defect, old_count = grouped[key]
            name = min(filter(None, (name, old_name)), default="")
            good, defect, count = good + old_good, defect + old_defect, count + old_count
        grouped[key] = name, good, defect, count
    return [
        (day, code, name, process, unit, good, defect, count)
        for (day, code, process, unit), (name, good, defect, count) in grouped.items()
    ]


async def _publish(pool: asyncpg.Pool, snapshot: asyncpg.Record) -> None:
    factory = snapshot["factory_id"]
    source = snapshot["external_system"]
    snapshot_id, collected_at = snapshot["id"], snapshot["collected_at"]
    async with pool.acquire() as conn, conn.transaction():
        await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory)
        # Serialize overlapping executions for the same source and reject stale workers.
        await conn.execute(
            "SELECT pg_advisory_xact_lock(hashtext('external_production'), hashtext($1))",
            factory + "/" + source,
        )
        current = await conn.fetchrow(
            """SELECT snapshot_collected_at, snapshot_id FROM external_production_snapshot_state
               WHERE factory_id = $1 AND source_system = $2""",
            factory, source,
        )
        if current and (collected_at, snapshot_id) < (
            current["snapshot_collected_at"], current["snapshot_id"],
        ):
            return

        rows = _daily_rows(snapshot)
        if rows:
            await conn.executemany(_UPSERT_SQL, [
                (factory, source, *row, snapshot_id, collected_at) for row in rows
            ])
            days = [row[0] for row in rows]
            # The feed contains no request window. Only the range bounded by
            # observed report dates is known to be covered by this snapshot.
            await conn.execute(
                """DELETE FROM fact_external_production_daily
                   WHERE factory_id = $1 AND source_system = $2
                     AND report_date BETWEEN $3 AND $4 AND snapshot_id <> $5""",
                factory, source, min(days), max(days), snapshot_id,
            )
        await conn.execute(
            """INSERT INTO external_production_snapshot_state
                   (factory_id, source_system, snapshot_id, snapshot_collected_at)
               VALUES ($1, $2, $3, $4)
               ON CONFLICT (factory_id, source_system) DO UPDATE
               SET snapshot_id = EXCLUDED.snapshot_id,
                   snapshot_collected_at = EXCLUDED.snapshot_collected_at
               WHERE external_production_snapshot_state.snapshot_id <> EXCLUDED.snapshot_id""",
            factory, source, snapshot_id, collected_at,
        )


async def sync_external_production(
    cretas_pool: asyncpg.Pool, smartbi_pool: asyncpg.Pool, factory_id: str | None = None,
) -> int:
    """Publish the latest report_output per eligible factory/source and return snapshot count."""
    if factory_id is not None and factory_id.startswith(DEMO_FACTORY_PREFIX):
        return 0
    async with cretas_pool.acquire() as conn, conn.transaction(readonly=True):
        snapshots = await conn.fetch(_LATEST_SQL, DEMO_FACTORY_PREFIX, factory_id)
    for snapshot in snapshots:
        await _publish(smartbi_pool, snapshot)
        logger.info("Processed production snapshot factory=%s source=%s id=%s rows=%s",
                    snapshot["factory_id"], snapshot["external_system"], snapshot["id"],
                    snapshot["row_count"])
    return len(snapshots)


async def _main(factory_id: str | None) -> None:
    from smartbi.config import get_cretas_pool, get_pg_pool

    if factory_id is not None and factory_id.startswith(DEMO_FACTORY_PREFIX):
        raise SystemExit("该工厂不在外部生产同步范围")
    cretas_pool = await get_cretas_pool()
    smartbi_pool = None
    try:
        smartbi_pool = await get_pg_pool()
        if cretas_pool is None or smartbi_pool is None:
            raise RuntimeError("外部生产同步需要 Cretas 和 SmartBI 数据库连接")
        total = await sync_external_production(cretas_pool, smartbi_pool, factory_id)
        logger.info("External production ETL finished: snapshots=%s", total)
    finally:
        if cretas_pool is not None:
            await cretas_pool.close()
        if smartbi_pool is not None:
            await smartbi_pool.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--factory-id")
    logging.basicConfig(level=logging.INFO)
    asyncio.run(_main(parser.parse_args().factory_id))
