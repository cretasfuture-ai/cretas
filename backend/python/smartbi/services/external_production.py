"""Tenant-scoped read model for daily external production facts."""
from __future__ import annotations

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
        if await conn.fetchval(
            """SELECT EXISTS (
                   SELECT 1 FROM fact_production_report WHERE factory_id = $1
                    AND ($2::date IS NULL OR report_date >= $2)
                    AND ($3::date IS NULL OR report_date <= $3)
               ) OR EXISTS (
                   SELECT 1 FROM fact_production_batch WHERE factory_id = $1
                    AND ($2::date IS NULL OR stat_date >= $2)
                    AND ($3::date IS NULL OR stat_date <= $3)
               )""",
            factory_id, native_start, end,
        ):
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
