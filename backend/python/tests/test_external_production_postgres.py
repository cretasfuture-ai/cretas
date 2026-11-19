"""Real PostgreSQL source → Silver → FastAPI/ask-data regression tests."""
import asyncio
from contextlib import asynccontextmanager
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
import json
import os
from pathlib import Path
import subprocess
import sys
from urllib.parse import urlsplit, urlunsplit
from uuid import uuid4

import asyncpg
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
import jwt
import pytest

from auth_middleware import JWTAuthMiddleware
from smartbi import config
from smartbi.api import factory_etl_admin, factory_production_gold, financial_dashboard
from smartbi.gold.external_production_etl import sync_external_production
from smartbi.gold import factory_production_etl
from smartbi.services.factory_charts import conversation, conversation_store

DSN = os.environ.get("CBI_PG_TEST_DSN") or os.environ.get("SMARTBI_PG_DSN")
pytestmark = pytest.mark.skipif(not DSN, reason="Require disposable local or Python Gate PostgreSQL")
MIGRATIONS = Path(__file__).parents[1] / "smartbi/database/migrations"
FACTORY = "LIUSHANMEN"
DEMO_FIXTURE = "LSMDEMO-EXCLUSION-FIXTURE"
CODE = "C P2026100002"
DAY = "2026-11-18"
COLLECTED = datetime(2026, 11, 18, 15, 9, tzinfo=timezone.utc)
SECRET = uuid4().hex + uuid4().hex
ROUTE = "/api/smartbi/factory/external-production"


def _line(unit, good, defect, *, day=DAY, process="成品入库", code=CODE):
    return dict(reportDate=day, productCode=code, productName="产品 A",
                processName=process, unit=unit, goodQty=good, defectQty=defect, reportCount=2)


PAYLOAD = [
    _line("KG", "100.125", "10.000"),
    _line("只", "90", "0"),
    _line("包", "78", "2"),
    _line("盒", "20", "2"),
    _line("KG", "128", "4", process="出料"),
    _line("KG", "20", "0", day="2026-11-17"),
    _line("KG", "0", "0", day="2026-11-17", code="P0"),
]


@asynccontextmanager
async def _database(dsn, admin, prefix):
    name = prefix + uuid4().hex[:12]
    await admin.execute(f'CREATE DATABASE "{name}"')
    try:
        yield dsn._replace(path=f"/{name}")
    finally:
        await admin.execute(f'DROP DATABASE "{name}" WITH (FORCE)')


@pytest.fixture
async def stores(monkeypatch):
    dsn = urlsplit(DSN)
    assert dsn.hostname in {"127.0.0.1", "localhost", "::1"}, "Only disposable loopback PostgreSQL is supported"
    if not os.environ.get("CBI_PG_TEST_DSN"):
        assert dsn.username == "cretas_ci" and dsn.path == "/cretas_workflow_verify_test"
    admin = await asyncpg.connect(DSN)
    try:
        await admin.execute("""DO $$ BEGIN
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'smartbi_user') THEN
                CREATE ROLE smartbi_user NOLOGIN;
            END IF;
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cbi_snapshot_reader') THEN
                CREATE ROLE cbi_snapshot_reader LOGIN NOSUPERUSER NOBYPASSRLS;
            END IF;
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cbi_snapshot_silver') THEN
                CREATE ROLE cbi_snapshot_silver LOGIN NOSUPERUSER NOBYPASSRLS;
                GRANT smartbi_user TO cbi_snapshot_silver;
            END IF;
        END $$""")
        async with _database(dsn, admin, "cbi_bronze_") as bronze, _database(dsn, admin, "cbi_silver_") as silver:
            bronze_admin = await asyncpg.connect(urlunsplit(bronze))
            try:
                await bronze_admin.execute("""CREATE TABLE external_source_snapshots (
                    id BIGSERIAL PRIMARY KEY, factory_id VARCHAR(50) NOT NULL,
                    external_system VARCHAR(30) NOT NULL, dataset VARCHAR(40) NOT NULL,
                    collected_at TIMESTAMP NOT NULL, received_at TIMESTAMP NOT NULL DEFAULT now(),
                    row_count INTEGER NOT NULL, payload TEXT NOT NULL, payload_sha256 VARCHAR(64),
                    collector_run_id VARCHAR(80)
                );
                CREATE TABLE factory_external_integrations (
                    factory_id VARCHAR(50) NOT NULL, external_system VARCHAR(30) NOT NULL,
                    status VARCHAR(20) NOT NULL, PRIMARY KEY (factory_id, external_system)
                );
                GRANT SELECT ON external_source_snapshots TO cbi_snapshot_reader;
                GRANT SELECT (factory_id, external_system, status)
                    ON factory_external_integrations TO cbi_snapshot_reader;""")
                silver_admin = await asyncpg.connect(urlunsplit(silver))
                try:
                    for filename in (
                        "V20260916_01__agg_supplier_price.sql",
                        "V20261002_01__factory_production_gold.sql",
                        "V20261003_01__agg_supplier_price_mb_dedup.sql",
                        "V20261004_01__fact_production_report.sql",
                        "V20261120_01__external_production_daily.sql",
                    ):
                        await silver_admin.execute((MIGRATIONS / filename).read_text(encoding="utf-8"))
                finally:
                    await silver_admin.close()
                netloc = dsn.netloc.rsplit("@", 1)[-1]
                async with asyncpg.create_pool(
                    urlunsplit(bronze._replace(netloc=f"cbi_snapshot_reader@{netloc}")), min_size=1, max_size=3,
                ) as source, asyncpg.create_pool(
                    urlunsplit(silver._replace(netloc=f"cbi_snapshot_silver@{netloc}")), min_size=1, max_size=3,
                ) as target:
                    async def smartbi_pool():
                        return target

                    async def cretas_pool():
                        return source

                    monkeypatch.setattr(config, "get_pg_pool", smartbi_pool)
                    monkeypatch.setattr(config, "get_cretas_pool", cretas_pool)
                    yield bronze_admin, source, target
            finally:
                await bronze_admin.close()
    finally:
        await admin.close()


async def _snapshot(conn, factory, collected_at, rows, *, source="blacklake", dataset="report_output"):
    return await conn.fetchval(
        """INSERT INTO external_source_snapshots
             (factory_id, external_system, dataset, collected_at, row_count, payload)
           VALUES ($1, $2, $3, $4, $5, $6) RETURNING id""",
        factory, source, dataset, collected_at.replace(tzinfo=None), len(rows), json.dumps(rows),
    )


@asynccontextmanager
async def _tenant(pool, factory):
    async with pool.acquire() as conn, conn.transaction():
        await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory)
        yield conn


async def _facts(pool, factory):
    async with _tenant(pool, factory) as conn:
        return await conn.fetch("""SELECT *, xmin::text AS version FROM fact_external_production_daily
                                   ORDER BY report_date, product_code, process_name, unit""")


def _headers(factory=FACTORY, role="viewer"):
    token = jwt.encode({"sub": "local-fixture", "factoryId": factory, "role": role,
                        "userId": 49, "exp": datetime.now(timezone.utc) + timedelta(minutes=10)},
                       SECRET, algorithm="HS256")
    return {"Authorization": f"Bearer {token}"}


def _client():
    app = FastAPI()
    app.add_middleware(JWTAuthMiddleware, jwt_secret=SECRET)
    app.include_router(factory_production_gold.router, prefix="/api/smartbi")
    app.include_router(financial_dashboard.router, prefix="/api/smartbi/financial-dashboard")
    return AsyncClient(transport=ASGITransport(app=app), base_url="http://test")


@pytest.fixture
async def nightly_stores(stores):
    bronze, _, _ = stores
    await bronze.execute("""
        CREATE TABLE production_batches (
            id bigint PRIMARY KEY, factory_id varchar(50) NOT NULL, deleted_at timestamp,
            batch_number text, product_type_id text, product_name text,
            planned_quantity numeric, actual_quantity numeric, good_quantity numeric,
            defect_quantity numeric, unit text, status text, start_time timestamp, end_time timestamp,
            worker_count int, work_duration_minutes int, material_cost numeric, labor_cost numeric,
            equipment_cost numeric, other_cost numeric, total_cost numeric, unit_cost numeric,
            yield_rate numeric, is_trial boolean
        );
        CREATE TABLE production_reports (
            id bigint PRIMARY KEY, factory_id varchar(50) NOT NULL, deleted_at timestamp,
            report_type text, batch_id bigint, process_order int, process_category text,
            report_date date, report_kind text, input_quantity numeric, output_quantity numeric,
            good_quantity numeric, total_work_minutes int, total_workers int, labor_cost numeric,
            material_cost numeric
        );
        CREATE TABLE material_batches (
            id bigint PRIMARY KEY, factory_id varchar(50) NOT NULL, deleted_at timestamp,
            material_type_id text, supplier_id text, purchase_date date, inbound_date timestamp,
            unit_price numeric, receipt_quantity numeric, quantity_unit text
        );
        CREATE TABLE raw_material_types (id text PRIMARY KEY, deleted_at timestamp, name text);
        CREATE TABLE suppliers (id text PRIMARY KEY, deleted_at timestamp, name text);
        GRANT SELECT ON production_batches, production_reports, material_batches, raw_material_types,
            suppliers TO cbi_snapshot_reader;
    """)
    await bronze.execute("""
        INSERT INTO production_batches
            (id, factory_id, batch_number, product_type_id, product_name,
             status, end_time, actual_quantity, good_quantity, unit)
        VALUES (12, $1, 'DEMO-12', 'fixture', 'Excluded fixture',
                'COMPLETED', '2026-11-18 14:00:00', 99, 99, 'KG');
    """, DEMO_FIXTURE)
    await bronze.execute("""
        INSERT INTO material_batches
            (id, factory_id, material_type_id, purchase_date, unit_price, receipt_quantity, quantity_unit)
        VALUES (32, $1, 'M32', '2026-11-18', 99, 10, 'KG');
    """, DEMO_FIXTURE)
    await _snapshot(bronze, DEMO_FIXTURE, COLLECTED, PAYLOAD)
    return stores


async def _run_nightly(stores, *args):
    bronze, _, target = stores
    db = urlsplit(DSN)
    async with target.acquire() as conn:
        target_db = await conn.fetchval("SELECT current_database()")
    # Bind both pools explicitly to the same disposable loopback server as the fixtures.
    env = {**os.environ, "POSTGRES_ENABLED": "true"}
    for prefix, database, user in (
        ("FOOD_KB_POSTGRES", await bronze.fetchval("SELECT current_database()"), "cbi_snapshot_reader"),
        ("POSTGRES", target_db, "cbi_snapshot_silver"),
    ):
        env.update({
            f"{prefix}_HOST": db.hostname,
            f"{prefix}_PORT": str(db.port or 5432),
            f"{prefix}_DB": database,
            f"{prefix}_USER": user,
            f"{prefix}_PASSWORD": "",
        })
    result = await asyncio.to_thread(
        subprocess.run, [sys.executable, "-m", "scripts.gold_etl_daily_refresh", *args],
        cwd=MIGRATIONS.parents[2], env=env, capture_output=True, text=True,
        encoding="utf-8", errors="replace", timeout=45,
    )
    print(result.stderr, end="")
    return result


@pytest.mark.asyncio
@pytest.mark.parametrize("failed_factory", [False, True], ids=["success", "partial-failure"])
async def test_nightly_module_refreshes_external_and_native_factories(nightly_stores, failed_factory):
    bronze, _, target = nightly_stores
    await _snapshot(bronze, FACTORY, COLLECTED, PAYLOAD)
    if failed_factory:
        await _snapshot(bronze, "F001-BAD-FIXTURE", COLLECTED, {"invalid": "object instead of rows"})
    await bronze.execute("""
        INSERT INTO production_batches
            (id, factory_id, batch_number, product_type_id, product_name,
             status, end_time, actual_quantity, good_quantity, unit)
        VALUES (11, 'F006', 'B-11', 'P11', 'Local F006',
                'COMPLETED', '2026-11-18 14:00:00', 40, 38, 'KG');
        INSERT INTO production_reports (id, factory_id, report_type, report_date, good_quantity)
        VALUES (21, 'F-REPORT-ONLY', 'YIELD', '2026-11-18', 16);
        INSERT INTO material_batches
            (id, factory_id, material_type_id, supplier_id, purchase_date, unit_price, receipt_quantity, quantity_unit)
        VALUES (31, 'F-SUPPLIER-ONLY', 'M31', 'S31', '2026-11-18', 8.5, 10, 'KG');
    """)
    result = await _run_nightly(nightly_stores)
    assert result.returncode == int(failed_factory), result.stderr
    # Reads from separate sessions prove the CLI committed both outputs.
    external = await _facts(target, FACTORY)
    assert len(external) == 7, "nightly CLI did not sync the Blacklake snapshot"
    assert {r["unit"] for r in external} == {"KG", "只", "包", "盒"}
    async with _tenant(target, "F006") as conn:
        assert await conn.fetchval("""
            SELECT total_good_qty FROM agg_factory_batch_daily
            WHERE factory_id = 'F006' AND product_type_id IS NULL
        """) == Decimal("38")
    async with _tenant(target, "F-REPORT-ONLY") as conn:
        assert await conn.fetchval("SELECT good_qty FROM fact_production_report") == Decimal("16")
    async with _tenant(target, "F-SUPPLIER-ONLY") as conn:
        assert await conn.fetchval("SELECT unit_price FROM agg_supplier_price") == Decimal("8.5")
    assert DEMO_FIXTURE not in result.stderr
    async with _tenant(target, DEMO_FIXTURE) as conn:
        for table in ("fact_production_batch", "external_production_snapshot_state",
                      "fact_external_production_daily", "agg_factory_batch_daily", "agg_supplier_price"):
            assert await conn.fetchval(f"SELECT count(*) FROM {table}") == 0
    assert f"runs={4 + int(failed_factory)} failures={int(failed_factory)}" in result.stderr
    if failed_factory:
        assert "factory_production factory=F001-BAD-FIXTURE failed" in result.stderr
        assert await _facts(target, "F001-BAD-FIXTURE") == []


@pytest.mark.asyncio
async def test_nightly_module_exits_two_when_only_excluded_factories_exist(nightly_stores):
    result = await _run_nightly(nightly_stores)
    assert result.returncode == 2
    assert "No eligible factories discovered" in result.stderr
    assert DEMO_FIXTURE not in result.stderr


@pytest.mark.asyncio
async def test_snapshot_latest_replay_is_idempotent(stores):
    bronze, source, target = stores
    await _snapshot(bronze, FACTORY, COLLECTED - timedelta(days=1), [_line("KG", "900", "100")])
    latest = await _snapshot(bronze, FACTORY, COLLECTED, PAYLOAD)
    await _snapshot(bronze, FACTORY, COLLECTED + timedelta(days=1), [], dataset="other")
    await _snapshot(bronze, DEMO_FIXTURE, COLLECTED, PAYLOAD)
    assert await sync_external_production(source, target) == 1
    before = await _facts(target, FACTORY)
    assert len(before) == 7
    assert {row["snapshot_id"] for row in before} == {latest}
    assert {row["snapshot_collected_at"] for row in before} == {COLLECTED}
    same_day = [row for row in before if row["report_date"] == date.fromisoformat(DAY)]
    assert {row["product_code"] for row in same_day} == {CODE}
    assert {(r["process_name"], r["unit"], r["good_qty"], r["defect_qty"], r["report_count"]) for r in same_day} == {
        ("成品入库", "KG", Decimal("100.125"), Decimal("10.000"), 2),
        ("成品入库", "只", 90, 0, 2), ("成品入库", "包", 78, 2, 2),
        ("成品入库", "盒", 20, 2, 2), ("出料", "KG", 128, 4, 2),
    }
    assert await sync_external_production(source, target, DEMO_FIXTURE) == 0
    assert await _facts(target, DEMO_FIXTURE) == []
    async with _tenant(target, DEMO_FIXTURE) as conn:
        assert await conn.fetchval("SELECT count(*) FROM external_production_snapshot_state") == 0
    assert await sync_external_production(source, target) == 1
    assert await _facts(target, FACTORY) == before


@pytest.mark.asyncio
async def test_new_snapshot_replaces_covered_days_and_empty_keeps_history(stores):
    bronze, source, target = stores
    await _snapshot(bronze, FACTORY, COLLECTED, PAYLOAD)
    assert await sync_external_production(source, target, FACTORY) == 1
    next_at = COLLECTED + timedelta(days=1)
    next_id = await _snapshot(bronze, FACTORY, next_at, [_line("KG", "88.125", "1")])
    assert await sync_external_production(source, target, FACTORY) == 1
    facts = await _facts(target, FACTORY)
    assert len(facts) == 3
    assert [(row["snapshot_id"], row["good_qty"]) for row in facts if row["report_date"] == date.fromisoformat(DAY)] == [
        (next_id, Decimal("88.125")),
    ]
    empty_id = await _snapshot(bronze, FACTORY, next_at + timedelta(days=1), [])
    assert await sync_external_production(source, target, FACTORY) == 1
    assert await _facts(target, FACTORY) == facts
    async with _tenant(target, FACTORY) as conn:
        assert await conn.fetchval("SELECT snapshot_id FROM external_production_snapshot_state") == empty_id


@pytest.mark.asyncio
async def test_existing_factory_gold_runner_syncs_external_facts(stores, monkeypatch):
    bronze, source, target = stores
    await _snapshot(bronze, FACTORY, COLLECTED, PAYLOAD)

    async def native_run(*_args):
        return factory_production_etl.FactoryEtlStats(gold_daily_rollup_rows=1)

    monkeypatch.setattr(factory_production_etl, "run_factory_etl", native_run)
    result = await factory_production_etl.run_factory_etl_with_retry(source, target, FACTORY)
    assert (result.gold_daily_rollup_rows, result.external_snapshots_synced, result.errors) == (1, 1, [])
    assert len(await _facts(target, FACTORY)) == 7
    await bronze.execute("REVOKE SELECT ON external_source_snapshots FROM cbi_snapshot_reader")
    jobs = {"fixture": {"factory_id": FACTORY, "status": "queued"}}
    monkeypatch.setattr(factory_etl_admin, "_running_jobs", jobs)
    await factory_etl_admin._run_job("fixture", FACTORY)
    assert jobs["fixture"]["status"] == "error"
    assert jobs["fixture"]["stats"]["goldDailyRollupRows"] == 1
    assert jobs["fixture"]["stats"]["externalSnapshotsSynced"] == 0
    assert factory_etl_admin._derive_last_status(FACTORY, COLLECTED.isoformat()) == "error"


@pytest.mark.asyncio
async def test_page_endpoint_uses_tenant_period_freshness_and_native_precedence(stores):
    bronze, source, target = stores
    await _snapshot(bronze, FACTORY, datetime.now(timezone.utc) - timedelta(hours=37), PAYLOAD)
    async with _tenant(target, FACTORY) as conn:
        await conn.execute("""INSERT INTO fact_production_report (factory_id, source_pk, report_date)
                             VALUES ($1, 'old', '2026-09-02')""", FACTORY)
    async with _tenant(target, "F006") as conn:
        await conn.execute("""INSERT INTO fact_production_report (factory_id, source_pk, report_date)
                             VALUES ('F006', 'current', '2026-11-18')""")
        await conn.execute("""INSERT INTO agg_factory_batch_daily
            (factory_id, stat_date, batch_count, total_material_cost, total_cost)
            VALUES ('F006', '2026-11-18', 5, 33, 33)""")
    await _snapshot(bronze, "F006", COLLECTED, [_line("KG", "9900", "300")])
    filters = {"startDate": "2026-11-17", "endDate": DAY}
    async with _client() as client:
        native_url = "/api/smartbi/factory/cost-structure"
        native_before = (await client.get(native_url, params=filters, headers=_headers("F006"))).json()
        assert native_before["data"]["summary"]["batchCount"] == 5
        assert await sync_external_production(source, target) == 2
        async with _tenant(target, "F006") as conn:
            assert await conn.fetchval("SELECT count(*) FROM fact_external_production_daily") == 1
            assert await conn.fetchval("SELECT count(*) FROM external_production_snapshot_state") == 1
        async with _tenant(target, FACTORY) as conn:
            assert await conn.fetchval("SELECT count(*) FROM fact_external_production_daily") == 7
            assert await conn.fetchval(
                "SELECT count(*) FROM fact_external_production_daily WHERE factory_id = 'F006'"
            ) == 0
            assert await conn.fetchval(
                "SELECT count(*) FROM external_production_snapshot_state WHERE factory_id = 'F006'"
            ) == 0
            with pytest.raises(asyncpg.InsufficientPrivilegeError, match="row-level security"):
                async with conn.transaction():
                    await conn.execute("""INSERT INTO fact_external_production_daily VALUES
                        ('F006', 'blacklake', '2026-11-18', 'forbidden', 'fixture', '成品入库', 'KG',
                         1, 0, 1, 1, now())""")
        response = await client.get(ROUTE, params={**filters, "factory_id": FACTORY}, headers=_headers())
        assert response.status_code == 200
        data = response.json()["data"]
        assert data["sourceLabel"] == "黑湖" and data["stale"] is True
        assert data["snapshotCollectedAt"].endswith("+00:00")
        assert data["yieldRate"] is None and data["cost"] is None
        assert data["unavailableMessage"] == "黑湖数据不含投入量，无法计算"
        assert {item["unit"] for item in data["rows"]} == {"KG", "只", "包", "盒"}
        kg = next(row for row in data["rows"] if row["reportDate"] == DAY and
                  row["unit"] == "KG" and row["processName"] == "成品入库")
        assert kg["goodQty"] == 100.125 and kg["defectQty"] == 10
        assert kg["defectRate"] == pytest.approx(10 / 110.125 * 100)
        assert next(row for row in data["rows"] if row["productCode"] == "P0")["defectRate"] is None
        summary = await client.get(ROUTE, params={**filters, "summaryOnly": True}, headers=_headers())
        assert summary.json()["data"] == {**data, "rows": []}
        empty = await client.get(ROUTE, params={"startDate": "2026-11-30"}, headers=_headers())
        assert empty.json()["data"] == {**data, "rows": []}
        native_after = (await client.get(native_url, params=filters, headers=_headers("F006"))).json()
        assert native_after == native_before
        assert (await client.get(ROUTE, params=filters, headers=_headers("F006"))).json()["data"] is None
        assert (await client.get(ROUTE, params=filters, headers=_headers("UNRELATED"))).json()["data"] is None
        assert (await client.get(ROUTE, params={"factory_id": FACTORY}, headers=_headers("F006"))).status_code == 403
        assert (await client.get(ROUTE, headers=_headers(role="warehouse_worker"))).status_code == 403
        assert (await client.get(ROUTE)).status_code == 401
        await _snapshot(bronze, FACTORY, datetime.now(timezone.utc), PAYLOAD)
        await sync_external_production(source, target, FACTORY)
        current = await client.get(ROUTE, params=filters, headers=_headers())
        assert current.json()["data"]["stale"] is False


@pytest.mark.asyncio
async def test_factory_ask_data_uses_blacklake_facts_without_upload_or_remote_llm(stores, monkeypatch):
    from smartbi.services.factory_charts import (
        blacklake_production, captured_sources, detail_store, finance_sources, operational_sources,
        settled_sources, trusted_business_sources,
    )
    bronze, source, target = stores
    await bronze.execute("INSERT INTO factory_external_integrations VALUES ($1, 'blacklake', 'ACTIVE')", FACTORY)
    await _snapshot(bronze, FACTORY, COLLECTED, [*PAYLOAD, _line("KG", "700", "6", code="POTHER")])
    assert await sync_external_production(source, target) == 1

    async def no_other_sources(*args, **kwargs):
        return [], [], []

    for adapter in (captured_sources, finance_sources, operational_sources, settled_sources, trusted_business_sources):
        monkeypatch.setattr(adapter, "load", no_other_sources)

    def discover(candidates, *args):
        return candidates

    async def register(candidates, *args):
        return candidates

    monkeypatch.setattr(detail_store, "prepare_shared_metadata", discover)
    monkeypatch.setattr(detail_store, "register_shared", register)
    monkeypatch.setattr(conversation_store, "_STORE", conversation_store.MemoryConversationStore())

    async def planner(question, catalog, *args, **kwargs):
        selected = [item for item in catalog if item["sourceKey"] == "blacklake-production"]
        assert "产品 A" not in json.dumps(selected, ensure_ascii=False)
        assert {item.get("sourceQuantityUnit") for item in selected if item["unit"] == "%"} == {"KG", "只", "包", "盒"}
        return [item["id"] for item in selected
                if item["unit"] == "KG" and item["metric"].endswith(".finished.good")], {"mode": "ai"}

    monkeypatch.setattr(conversation, "plan", planner)
    async with _client() as client:
        response = await client.post("/api/smartbi/financial-dashboard/factory-analysis", headers=_headers(), json={
            "source": "business", "periodType": "month_range", "year": 2026, "startMonth": 11, "endMonth": 11,
            "question": f"产品 {CODE} 的成品入库良品数量趋势",
        })
    assert response.status_code == 200, response.text
    result = response.json()["data"]
    assert result["status"] == "answered", result
    assert [(f["value"], f["unit"]) for f in result["facts"]] == [("120.12", "KG")]
    assert "黑湖生产报工" in result["facts"][0]["source"]
    assert "黑湖数据不含投入量，无法计算" in result["limitations"]
    assert CODE in result["analysisScopeLabel"]
    await bronze.execute(
        "UPDATE factory_external_integrations SET status = 'SUSPENDED' WHERE factory_id = $1",
        FACTORY,
    )
    assert len(await _facts(target, FACTORY)) == 8
    request = financial_dashboard.FactoryRecommendationRequest(
        period_type="month_range", year=2026, start_month=11, end_month=11,
    )
    assert await blacklake_production.load(FACTORY, request) == (None, None)
