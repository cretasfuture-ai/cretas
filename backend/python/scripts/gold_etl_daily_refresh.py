"""Run the factory Gold ETLs from the daily systemd timer."""
from __future__ import annotations

import argparse
import asyncio
from contextlib import AsyncExitStack
import logging

from smartbi.config import get_cretas_pool, get_pg_pool
from smartbi.gold.external_cost_etl import sync_external_cost
from smartbi.gold.external_production_etl import DEMO_FACTORY_PREFIX
from smartbi.gold.factory_production_etl import FactoryEtlStats, run_factory_etl_with_retry
from smartbi.gold.supplier_price_ingest_etl import run_supplier_price_ingest

logger = logging.getLogger(__name__)

_DISCOVERY_SQL = """
    SELECT factory_id,
           bool_or(pipeline = 'production') AS has_production,
           bool_or(pipeline = 'external_cost') AS has_external_cost,
           bool_or(pipeline = 'supplier_price') AS has_material_batches
    FROM (
        SELECT DISTINCT factory_id, 'production' AS pipeline
        FROM production_batches WHERE deleted_at IS NULL
        UNION
        SELECT DISTINCT factory_id, 'production'
        FROM production_reports WHERE deleted_at IS NULL AND report_type = 'YIELD'
        UNION
        SELECT DISTINCT factory_id, 'production'
        FROM external_source_snapshots WHERE dataset = 'report_output'
        UNION
        SELECT DISTINCT s.factory_id, 'external_cost'
        FROM external_source_snapshots AS s
        JOIN factory_external_integrations AS i USING (factory_id, external_system)
        WHERE s.external_system = 'blacklake' AND i.status = 'ACTIVE'
          AND s.dataset IN ('project_cost', 'production_issue')
        UNION
        SELECT DISTINCT factory_id, 'supplier_price'
        FROM material_batches WHERE deleted_at IS NULL
    ) AS sources
    WHERE factory_id <> '' AND NOT starts_with(factory_id, $1)
      AND ($2::text[] IS NULL OR factory_id = ANY($2))
    GROUP BY factory_id
    ORDER BY factory_id
"""


async def _run_pipeline(name, run, source, target, factory_id: str) -> bool:
    try:
        stats = await run(source, target, factory_id)
        errors = stats.errors if isinstance(stats, FactoryEtlStats) else stats["errors"]
    except Exception:
        logger.exception("%s factory=%s failed", name, factory_id)
        return False
    if errors:
        logger.error("%s factory=%s failed: %s", name, factory_id, stats)
        return False
    logger.info("%s factory=%s completed: %s", name, factory_id, stats)
    return True


async def run_refresh(factory_ids: list[str] | None) -> int:
    async with AsyncExitStack() as pools:
        source = await get_cretas_pool()
        if source is None:
            raise RuntimeError("Cretas source pool unavailable")
        pools.push_async_callback(source.close)
        async with source.acquire() as conn, conn.transaction(readonly=True):
            factories = await conn.fetch(_DISCOVERY_SQL, DEMO_FACTORY_PREFIX, factory_ids)
        if not factories:
            logger.error("No eligible factories discovered for Gold ETL")
            return 2

        target = await get_pg_pool()
        if target is None:
            raise RuntimeError("SmartBI target pool unavailable")
        pools.push_async_callback(target.close)
        runs = failures = 0
        for row in factories:
            for enabled, name, run in (
                (row["has_production"], "factory_production", run_factory_etl_with_retry),
                (row["has_external_cost"], "external_cost", sync_external_cost),
                (row["has_material_batches"], "supplier_price", run_supplier_price_ingest),
            ):
                if enabled:
                    runs += 1
                    if not await _run_pipeline(name, run, source, target, row["factory_id"]):
                        failures += 1

        logger.info("Gold ETL summary: factories=%d runs=%d failures=%d", len(factories), runs, failures)
        return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--factories", help="Comma-separated filter for discovered factories")
    parser.add_argument("--dry-run", action="store_true", help="Print the intended scope without connecting")
    args = parser.parse_args()
    factory_ids = None
    if args.factories is not None:
        factory_ids = [value.strip() for value in args.factories.split(",") if value.strip()]
        if not factory_ids:
            parser.error("--factories requires at least one factory ID")
        factory_ids = [value for value in factory_ids if not value.startswith(DEMO_FACTORY_PREFIX)]
    if args.dry_run:
        logger.info("Gold ETL dry run: factory filter=%s", factory_ids)
        return 0
    try:
        return asyncio.run(run_refresh(factory_ids))
    except Exception:
        logger.exception("Gold ETL could not complete discovery or initialize database pools")
        return 2


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s [%(name)s] %(message)s")
    raise SystemExit(main())
