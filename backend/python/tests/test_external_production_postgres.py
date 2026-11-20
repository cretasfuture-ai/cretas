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
from smartbi.gold.external_cost_etl import sync_external_cost
from smartbi.gold.external_production_etl import sync_external_production
from smartbi.gold import factory_production_etl
from smartbi.services.factory_charts import conversation, conversation_store, cost_scope

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
COST_ROUTE = "/api/smartbi/factory/external-cost"


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
                        "V20260531_01__smart_bi_distillation_samples.sql",
                        "V20260916_01__agg_supplier_price.sql",
                        "V20261002_01__factory_production_gold.sql",
                        "V20261003_01__agg_supplier_price_mb_dedup.sql",
                        "V20261004_01__fact_production_report.sql",
                        "V20261104_01__distillation_secrecy_recheck_index.sql",
                        "V20261120_01__external_production_daily.sql",
                        "V20261120_02__external_production_cost.sql",
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


def _work_order(
    code, *, day=DAY, unit="KG", qty="8.25", total="109.112345678", product=CODE, unit_cost="13.2257",
    material="100.012345678", labor="9.10",
):
    return dict(workOrderCode=code, costDate=day, productCode=product, productName="产品 A", outputUnit=unit,
                outputQty=qty, materialCost=material, laborCost=labor, otherCost="0.0",
                totalCost=total, unitCost=unit_cost)


def _issue(line, order, *, day="2026-11-17", unit="g", quantity="10000", price="0",
           amount="0", moving="0.0025"):
    return dict(lineId=line, issueDate=day, workOrderCode=order, materialCode="RM01", materialName="原料",
                unit=unit, quantity=quantity, costUnitPrice=price, signedCostAmount=amount, movingAveragePrice=moving)


@pytest.fixture
def full_cost_access(monkeypatch):
    async def scope(request, factory, product_ids):
        assert request.state.factory_id == factory and product_ids == []
        return cost_scope.CostScope(cost_scope.FULL)
    monkeypatch.setattr(cost_scope, "resolve_cost_scope", scope)


@pytest.mark.asyncio
async def test_nightly_external_cost_reaches_http_including_decimal_yield_price_and_isolation(
    nightly_stores, full_cost_access, monkeypatch,
):
    bronze, _, target = nightly_stores
    for factory in (FACTORY, DEMO_FIXTURE, "NATIVE-OTHER"):
        await bronze.execute("INSERT INTO factory_external_integrations VALUES ($1, 'blacklake', 'ACTIVE')", factory)
    await _snapshot(bronze, FACTORY, COLLECTED - timedelta(days=1),
                    [_work_order("OLD", total="99999")], dataset="project_cost")
    await _snapshot(bronze, FACTORY, COLLECTED, [
        _work_order("WO-kg"), _work_order("WO-box", unit="盒", qty="12", total="20.02", product="P2", unit_cost="0"),
        _work_order("WO-empty", day="2026-11-16", total=None, product="P3", unit_cost=None),
        _work_order("WO-components", day="2026-11-17", total="0", product="P4", unit_cost=None, material=None, labor=None),
    ], dataset="project_cost")
    await _snapshot(bronze, FACTORY, COLLECTED, [
        _issue("1", "WO-kg"),
        _issue("2", "WO-box", day=DAY, unit="个", quantity="14", moving="0"),
        _issue("3", None, day=DAY, unit="KG", quantity="1.05", price="6.125", amount="-6.43125", moving="7.9"),
        _issue("4", None, day=DAY, unit="KG", quantity="1", price="-6.125", amount="6.125"),
        _issue("5", None, day=DAY, unit="KG", quantity="1", price="2", amount="0"),
    ], dataset="production_issue")
    await _snapshot(bronze, DEMO_FIXTURE, COLLECTED, [_work_order("DEMO")], dataset="project_cost")
    await bronze.execute("""
        INSERT INTO production_batches
            (id, factory_id, batch_number, product_type_id, product_name,
             status, end_time, actual_quantity, good_quantity, unit)
        VALUES (13, 'NATIVE-OTHER', 'NATIVE-13', 'fixture', 'Native fixture',
                'COMPLETED', '2026-11-18 14:00:00', 80, 75, 'KG'),
               (14, $1, 'OLD-NATIVE-14', 'fixture', 'Old native fixture',
                'COMPLETED', '2025-11-18 14:00:00', 80, 75, 'KG')
    """, FACTORY)

    result = await _run_nightly(nightly_stores)
    assert result.returncode == 0, result.stderr
    assert "external_cost factory=LIUSHANMEN completed" in result.stderr
    assert DEMO_FIXTURE not in result.stderr
    headers = _headers(role="factory_super_admin")
    async with _client() as client:
        response = await client.get(COST_ROUTE, params={"startDate": DAY, "endDate": DAY}, headers=headers)
        assert response.status_code == 200
        payload = json.loads(response.text, parse_float=Decimal)["data"]
        assert payload["sourceLabel"] == "黑湖"
        assert payload["costCollectedAt"] == payload["issueCollectedAt"] == COLLECTED.isoformat()
        summary = payload["summary"]
        assert summary is not None, "nightly ETL did not populate cost facts for the HTTP endpoint"
        assert summary["totalCost"] == Decimal("129.132345678")
        assert summary["materialCost"] == Decimal("200.024691356")
        assert summary["laborCost"] == Decimal("18.20")
        assert summary["issuedMaterialCost"] == Decimal("31.43125")
        assert summary["unitCosts"] == [{
            "unit": "KG", "cost": Decimal("13.2257"), "workOrderCount": 1,
            "zeroMaterialCostOrders": 0,
        }]
        assert summary["costMissingOrders"] == 0 and summary["unitCostMissingOrders"] == 1
        assert summary["materialCostMissingOrders"] == summary["laborCostMissingOrders"] == 0
        assert summary["priceMissingCount"] == summary["yieldComparableOrders"] == 1
        assert summary["valuationIssueCount"] == 2
        assert summary["overheadMissingCount"] == summary["workOrderCount"] == 2
        assert summary["yieldRate"] == Decimal("82.5")
        by_order = {row["workOrderCode"]: row for row in payload["workOrders"]}
        assert set(by_order) == {"WO-kg", "WO-box"}
        assert by_order["WO-kg"]["inputByUnit"] == [{"unit": "kg", "quantity": 10}]
        assert by_order["WO-box"]["yieldUnavailableReason"] == "incompatible_units"
        assert by_order["WO-box"]["yieldRate"] is None
        assert by_order["WO-box"]["unitCost"] is None
        assert payload["products"][1]["unitCostMissingOrders"] == 1
        assert payload["products"][1]["unitCosts"] == []
        details = {row["lineId"]: row for row in payload["materialIssues"]}
        assert details["1"]["issueDate"] == "2026-11-17" and details["1"]["priceSource"] == "moving_avg"
        assert details["2"]["issueCost"] is None and details["2"]["priceSource"] == "price_missing"
        assert details["3"]["workOrderCode"] is None and details["3"]["priceSource"] == "reported"
        for line_id in ("4", "5"):
            assert details[line_id]["priceSource"] == "invalid_valuation" and details[line_id]["issueCost"] is None
        assert payload["daily"][0]["totalCost"] == summary["totalCost"]
        assert payload["daily"][0]["unitCostMissingOrders"] == 1
        assert payload["daily"][0]["hasMaterialCredit"] is False
        assert payload["products"][0]["totalCost"] == Decimal("109.112345678")
        unfiltered = json.loads((await client.get(COST_ROUTE, headers=headers)).text, parse_float=Decimal)["data"]
        assert unfiltered["summary"]["workOrderCount"] == 4
        assert unfiltered["summary"]["costMissingOrders"] == 1
        assert unfiltered["summary"]["unitCostMissingOrders"] == 3
        assert unfiltered["summary"]["materialCostMissingOrders"] == unfiltered["summary"]["laborCostMissingOrders"] == 1
        assert unfiltered["summary"]["totalCost"] == Decimal("129.132345678")
        for group in (unfiltered["daily"][0], next(row for row in unfiltered["products"] if row["productCode"] == "P3")):
            assert group["costMissingOrders"] == group["unitCostMissingOrders"] == 1
            assert group["totalCost"] is None and group["unitCosts"] == []
        for group in (unfiltered["daily"][1], next(row for row in unfiltered["products"] if row["productCode"] == "P4")):
            assert group["totalCost"] == 0 and group["costMissingOrders"] == 0
            assert group["materialCost"] is None and group["laborCost"] is None
            assert group["materialCostMissingOrders"] == group["laborCostMissingOrders"] == 1
        native_period = await client.get(COST_ROUTE, params={"endDate": "2025-12-01"}, headers=headers)
        assert native_period.json()["data"] is None
        assert (await client.get(COST_ROUTE, params={"factory_id": "F006"}, headers=headers)).status_code == 403
        for factory in (DEMO_FIXTURE, "F006", "NATIVE-OTHER"):
            assert (await client.get(COST_ROUTE, headers=_headers(factory))).json()["data"] is None
        masked = (await client.get(COST_ROUTE, headers=_headers())).json()["data"]
        assert masked["permissionMessage"] and masked["summary"] is None and masked["materialIssues"] == []
        empty = (await client.get(COST_ROUTE, params={"startDate": "2027-01-01"}, headers=headers)).json()["data"]
        assert empty["summary"] is None and empty["workOrders"] == [] and empty["costCollectedAt"]

        async def scoped_access(*_):
            return cost_scope.CostScope(cost_scope.SCOPED, frozenset({CODE}))

        monkeypatch.setattr(cost_scope, "resolve_cost_scope", scoped_access)
        scoped = (await client.get(COST_ROUTE, headers=headers)).json()["data"]
        assert scoped["permissionMessage"] and scoped["summary"] is None
        assert scoped["products"] == scoped["workOrders"] == scoped["materialIssues"] == []

    async with _tenant(target, "NATIVE-OTHER") as conn:
        assert await conn.fetchval("SELECT count(*) FROM fact_external_work_order_cost") == 0
        assert await conn.fetchval("SELECT count(*) FROM fact_external_material_issue") == 0


@pytest.mark.asyncio
async def test_external_cost_excludes_zero_unit_cost_and_preserves_signed_material_credit(stores, full_cost_access):
    bronze, source, target = stores
    await bronze.execute("INSERT INTO factory_external_integrations VALUES ($1, 'blacklake', 'ACTIVE')", FACTORY)
    await _snapshot(bronze, FACTORY, COLLECTED, [
        _work_order("ZERO-TOTAL", qty="2", total="0", material="0", labor="0", unit_cost="0"),
        _work_order("ZERO-OUTPUT", qty="0", total="0", material="0", labor="0", unit_cost="0"),
        _work_order("REPORTED", qty="8", total="80", unit_cost="10"),
        _work_order("PLACEHOLDER-ZERO", qty="480", total="39738.99", unit_cost="0"),
        _work_order("CREDIT", day="2026-11-04", total="-140600", material="-152933", labor="12333", unit_cost="-16"),
        _work_order("UNCONFIRMED-CREDIT", day="2026-11-05", total="-8", material="-9", labor="1", unit_cost="-1"),
    ], dataset="project_cost")
    await _snapshot(bronze, FACTORY, COLLECTED, [
        _issue("credit", "CREDIT", day="2026-11-03", price="-10", amount="152933"),
        _issue("unrelated", None, day="2026-11-05", price="-1", amount="9"),
    ], dataset="production_issue")
    assert (await sync_external_cost(source, target, FACTORY))["snapshots"] == 2
    async with _tenant(target, FACTORY) as conn:
        assert await conn.fetchval(
            "SELECT unit_cost FROM fact_external_work_order_cost WHERE work_order_code = 'PLACEHOLDER-ZERO'"
        ) == 0
    async with _client() as client:
        response = await client.get(COST_ROUTE, headers=_headers(role="factory_super_admin"))
    assert response.status_code == 200
    payload = response.json()["data"]
    by_order = {row["workOrderCode"]: row for row in payload["workOrders"]}
    assert by_order["ZERO-TOTAL"]["unitCost"] is None
    assert by_order["ZERO-OUTPUT"]["unitCost"] == 0
    assert by_order["PLACEHOLDER-ZERO"]["unitCost"] is None
    assert by_order["CREDIT"]["unitCost"] == -16
    daily = {row["costDate"]: row for row in payload["daily"]}
    assert daily[DAY]["unitCosts"] == [{
        "unit": "KG", "cost": 10, "workOrderCount": 1, "zeroMaterialCostOrders": 0,
    }]
    assert daily[DAY]["unitCostMissingOrders"] == 2
    assert daily[DAY]["hasMaterialCredit"] is False
    assert daily["2026-11-04"]["hasMaterialCredit"] is True
    assert daily["2026-11-04"]["materialCost"] == -152933
    assert daily["2026-11-04"]["totalCost"] == -140600
    assert daily["2026-11-05"]["hasMaterialCredit"] is False


@pytest.mark.asyncio
async def test_external_cost_explains_unit_samples_yield_and_issue_scope(stores, full_cost_access):
    bronze, source, target = stores
    await bronze.execute("INSERT INTO factory_external_integrations VALUES ($1, 'blacklake', 'ACTIVE')", FACTORY)
    await _snapshot(bronze, FACTORY, COLLECTED, [
        _work_order("BAG-A", product="BAG-A", unit="袋", qty="3830", unit_cost="0.181027",
                    material="0", labor="693.33", total="693.33"),
        _work_order("BAG-B", product="BAG-B", unit="袋", qty="207", unit_cost="0", material="0", total="0"),
        _work_order("BAG-C", product="BAG-C", unit="袋", qty="107", unit_cost="0", material="0", total="0"),
        _work_order("MASS-A", qty="4966.23", material="30"),
        _work_order("MASS-B", qty="3622.257", material="40"),
    ], dataset="project_cost")
    await _snapshot(bronze, FACTORY, COLLECTED, [
        _issue("bag", "BAG-A", day=DAY, unit="个", quantity="3333", price="1", amount="-10"),
        _issue("mass-a", "MASS-A", day=DAY, unit="KG", quantity="4966.23", price="1", amount="-20"),
        _issue("mass-b-early", "MASS-B", day="2026-11-17", unit="KG", quantity="1445", price="1", amount="-30"),
        _issue("mass-b-current", "MASS-B", day=DAY, unit="KG", quantity="2177.257", price="1", amount="-40"),
        _issue("other", "OUTSIDE-COST-SCOPE", day=DAY, price="1", amount="-50"),
        _issue("unlinked", None, day=DAY, price="1", amount="-60"),
        _issue("out-of-window", "OUTSIDE-COST-SCOPE", day="2026-11-17", price="1", amount="-70"),
    ], dataset="production_issue")
    assert (await sync_external_cost(source, target, FACTORY))["snapshots"] == 2
    async with _client() as client:
        response = await client.get(
            COST_ROUTE, params={"startDate": DAY, "endDate": DAY}, headers=_headers(role="factory_super_admin"),
        )
    assert response.status_code == 200
    payload = json.loads(response.text, parse_float=Decimal)["data"]
    summary = payload["summary"]
    per_bag = next(item for item in summary["unitCosts"] if item["unit"] == "袋")
    assert per_bag == {
        "unit": "袋", "cost": Decimal("0.181027"), "workOrderCount": 1, "zeroMaterialCostOrders": 1,
    }
    assert summary["unitCostMissingOrders"] == payload["daily"][0]["unitCostMissingOrders"] == 2
    assert payload["daily"][0]["unitCosts"] == summary["unitCosts"]
    bag_products = {row["productCode"]: row for row in payload["products"] if row["productCode"].startswith("BAG-")}
    assert bag_products["BAG-A"]["unitCosts"] == [per_bag]
    by_order = {row["workOrderCode"]: row for row in payload["workOrders"]}
    for code, qty in (("BAG-B", 207), ("BAG-C", 107)):
        assert bag_products[code]["unitCostMissingOrders"] == 1 and bag_products[code]["unitCosts"] == []
        assert by_order[code]["outputQty"] == qty and by_order[code]["unitCost"] is None
    mass_product = next(product for product in payload["products"] if product["productCode"] == CODE)
    assert mass_product["yieldRate"] == 100
    assert mass_product["yieldComparableOrders"] == mass_product["workOrderCount"] == 2
    assert mass_product["inputByUnit"] == [{"unit": "kg", "quantity": Decimal("8588.487")}]
    assert summary["materialCost"] == 70
    assert summary["issuedMaterialCost"] == 210
    assert payload["daily"][0]["issuedMaterialCost"] == 100
    assert sorted(line["lineId"] for line in payload["materialIssues"]) == [
        "bag", "mass-a", "mass-b-current", "mass-b-early", "other", "unlinked",
    ]


@pytest.mark.asyncio
async def test_external_cost_upsert_retains_history_on_partial_empty_and_failed_snapshots(stores, full_cost_access):
    bronze, source, target = stores
    await bronze.execute("INSERT INTO factory_external_integrations VALUES ($1, 'blacklake', 'ACTIVE')", FACTORY)
    first = await _snapshot(
        bronze, FACTORY, COLLECTED, [_work_order("WO"), _work_order("WO-closed")], dataset="project_cost",
    )
    assert (await sync_external_cost(source, target, FACTORY))["snapshots"] == 1
    async with _client() as client:
        initial = (await client.get(COST_ROUTE, headers=_headers(role="factory_super_admin"))).json()["data"]
        assert initial["issueCollectedAt"] is None and initial["summary"]["priceMissingCount"] is None
        assert initial["workOrders"][0]["yieldUnavailableReason"] == "missing_issue"
    await _snapshot(bronze, FACTORY, COLLECTED, [_issue("1", "WO"), _issue("3", "WO-closed")],
                    dataset="production_issue")
    assert (await sync_external_cost(source, target, FACTORY))["snapshots"] == 2
    async with _tenant(target, FACTORY) as conn:
        version = await conn.fetchval("SELECT xmin::text FROM fact_external_work_order_cost WHERE work_order_code = 'WO'")
    await sync_external_cost(source, target, FACTORY)
    async with _tenant(target, FACTORY) as conn:
        assert await conn.fetchval(
            "SELECT xmin::text FROM fact_external_work_order_cost WHERE work_order_code = 'WO'",
        ) == version

    later = COLLECTED + timedelta(hours=1)
    await _snapshot(bronze, FACTORY, later, [_issue("2", "WO")], dataset="production_issue")
    await _snapshot(bronze, FACTORY, later, [_work_order("WO", total="NaN")], dataset="project_cost")
    with pytest.raises(ValueError, match="finite"):
        await sync_external_cost(source, target, FACTORY)
    async with _tenant(target, FACTORY) as conn:
        assert await conn.fetchval("SELECT array_agg(line_id ORDER BY line_id) FROM fact_external_material_issue") == ["1", "3"]
        assert await conn.fetchval("SELECT array_agg(DISTINCT snapshot_id) FROM fact_external_work_order_cost") == [first]

    partial_at = later + timedelta(hours=1)
    cost_update = await _snapshot(bronze, FACTORY, partial_at, [_work_order("WO", total="120")], dataset="project_cost")
    issue_update = await _snapshot(bronze, FACTORY, partial_at, [_issue("1", "WO", quantity="11000")],
                                   dataset="production_issue")
    await sync_external_cost(source, target, FACTORY)
    async with _client() as client:
        headers = _headers(role="factory_super_admin")
        partial = json.loads((await client.get(COST_ROUTE, headers=headers)).text, parse_float=Decimal)["data"]
        orders = {row["workOrderCode"]: row for row in partial["workOrders"]}
        assert set(orders) == {"WO", "WO-closed"}
        assert orders["WO"]["totalCost"] == 120 and orders["WO"]["yieldRate"] == 75
        assert orders["WO-closed"]["totalCost"] == Decimal("109.112345678")
        assert orders["WO-closed"]["yieldRate"] == Decimal("82.5")
        assert {row["lineId"]: row["quantity"] for row in partial["materialIssues"]} == {"1": 11000, "3": 10000}
        async with _tenant(target, FACTORY) as conn:
            assert await conn.fetchval(
                "SELECT snapshot_id FROM fact_external_work_order_cost WHERE work_order_code = 'WO'",
            ) == cost_update
            assert await conn.fetchval(
                "SELECT snapshot_id FROM fact_external_material_issue WHERE line_id = '1'",
            ) == issue_update

        await _snapshot(bronze, FACTORY, partial_at + timedelta(hours=1), [], dataset="project_cost")
        await sync_external_cost(source, target, FACTORY)
        after_empty_cost = json.loads((await client.get(COST_ROUTE, headers=headers)).text, parse_float=Decimal)["data"]
        assert after_empty_cost["costCollectedAt"] != after_empty_cost["issueCollectedAt"]
        await _snapshot(bronze, FACTORY, partial_at + timedelta(hours=2), [], dataset="production_issue")
        await sync_external_cost(source, target, FACTORY)
        after_empty_issue = json.loads((await client.get(COST_ROUTE, headers=headers)).text, parse_float=Decimal)["data"]
        assert after_empty_issue["issueCollectedAt"] != partial["issueCollectedAt"]
        for actual in (after_empty_cost, after_empty_issue):
            for field in ("summary", "daily", "products", "workOrders", "materialIssues"):
                assert actual[field] == partial[field]

        await _snapshot(bronze, FACTORY, partial_at + timedelta(hours=3), [_issue("1", "WO", quantity="12000")],
                        dataset="production_issue")
        await sync_external_cost(source, target, FACTORY)
        mixed = (await client.get(COST_ROUTE, headers=headers)).json()["data"]
        orders = {row["workOrderCode"]: row for row in mixed["workOrders"]}
        assert orders["WO"]["yieldUnavailableReason"] == "snapshot_mismatch"
        assert orders["WO-closed"]["yieldRate"] == 82.5
        await bronze.execute("UPDATE factory_external_integrations SET status = 'SUSPENDED' WHERE factory_id = $1", FACTORY)
        assert (await client.get(COST_ROUTE, headers=headers)).json()["data"] is None


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
async def test_page_endpoint_uses_tenant_period_freshness_and_native_precedence(stores, full_cost_access):
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
    await bronze.execute("INSERT INTO factory_external_integrations VALUES ('F006', 'blacklake', 'ACTIVE')")
    await _snapshot(bronze, "F006", COLLECTED, [_work_order("F006-EXTERNAL")], dataset="project_cost")
    assert (await sync_external_cost(source, target, "F006"))["snapshots"] == 1
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
        assert native_after["data"]["summary"]["totalCost"] is None
        f006_cost_headers = _headers("F006", role="factory_super_admin")
        native_cost = await client.get(native_url, params=filters, headers=f006_cost_headers)
        assert native_cost.status_code == 200 and native_cost.json()["data"]["summary"]["totalCost"] == 33
        assert "blacklake" not in native_cost.text.lower()
        external_cost = await client.get(COST_ROUTE, params=filters, headers=f006_cost_headers)
        assert external_cost.status_code == 200 and external_cost.json() == {"success": True, "data": None, "message": ""}
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
async def test_factory_ask_data_uses_blacklake_facts_without_upload_or_remote_llm(stores, monkeypatch, full_cost_access):
    from types import SimpleNamespace
    from smartbi.services.factory_charts import (
        blacklake_cost, blacklake_production, captured_sources, detail_store, finance_sources, operational_sources,
        settled_sources, trusted_business_sources,
    )
    bronze, source, target = stores
    await bronze.execute("INSERT INTO factory_external_integrations VALUES ($1, 'blacklake', 'ACTIVE')", FACTORY)
    await _snapshot(bronze, FACTORY, COLLECTED, [*PAYLOAD, _line("KG", "700", "6", code="POTHER")])
    assert await sync_external_production(source, target) == 1
    await _snapshot(bronze, FACTORY, COLLECTED, [
        _work_order("ASK-COST", total="180"),
        _work_order("ASK-CREDIT", day="2026-11-04", product="CREDIT", material="-500", total="-499"),
    ], dataset="project_cost")
    assert (await sync_external_cost(source, target, FACTORY))["snapshots"] == 1

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
        if "工单总成本" in question:
            selected = [item for item in catalog if item["sourceKey"] == "blacklake-cost"]
            if "授权对照" in question:
                assert not selected
                return [], {"mode": "unavailable"}
            assert len(selected) == 2
            return [item["id"] for item in selected if ".trend:" in item["id"]], {"mode": "ai"}
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
    report_scope = {"source": "business", "periodType": "month_range", "year": 2026, "startMonth": 11, "endMonth": 11}
    async with _client() as client:
        response = await client.post("/api/smartbi/financial-dashboard/factory-analysis",
            headers=_headers(role="factory_super_admin"), json={**report_scope, "question": "请画黑湖每日工单总成本趋势"})
        assert response.status_code == 200, response.text
        cost_answer = response.json()["data"]
        assert cost_answer["status"] == "answered"
        assert [(fact["value"], fact["unit"]) for fact in cost_answer["facts"]] == [("180", "元")]
        assert any("不含制费" in limitation for limitation in cost_answer["limitations"])
        assert "2026-11-04" not in str(cost_answer["recommendations"])
        assert any(item["sourceKey"] == "blacklake-cost" for item in cost_answer["catalog"])
        negative_day = date(2026, 11, 4)
        negative_only, error = await blacklake_cost.load(
            FACTORY, negative_day, negative_day, SimpleNamespace(state=SimpleNamespace(factory_id=FACTORY)),
        )
        assert error is None
        no_charts, gaps = blacklake_cost.build(negative_only)
        assert no_charts == []
        assert len(gaps) == 2 and all("排除后无可分析数据" in gap["missing"][0] for gap in gaps)

        async def scoped_access(*_):
            return cost_scope.CostScope(cost_scope.SCOPED, frozenset({CODE}))

        monkeypatch.setattr(cost_scope, "resolve_cost_scope", scoped_access)
        denied = await client.post("/api/smartbi/financial-dashboard/factory-analysis",
            headers=_headers(role="factory_super_admin"), json={**report_scope, "question": "工单总成本授权对照"})
        assert denied.status_code == 200, denied.text
        assert all(item["sourceKey"] != "blacklake-cost" for item in denied.json()["data"]["catalog"])
    await bronze.execute(
        "UPDATE factory_external_integrations SET status = 'SUSPENDED' WHERE factory_id = $1",
        FACTORY,
    )
    assert len(await _facts(target, FACTORY)) == 8
    request = financial_dashboard.FactoryRecommendationRequest(
        period_type="month_range", year=2026, start_month=11, end_month=11,
    )
    assert await blacklake_production.load(FACTORY, request) == (None, None)
