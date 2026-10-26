"""Cross-process checks for the PostgreSQL detail store.

The live test is opt-in because it needs a disposable database with the
V20261101_18 migration applied. It intentionally uses two Python processes;
two ASGI clients in one process would not catch worker-local cache bugs.
"""
import asyncio
import os
import subprocess
import sys
import uuid
from pathlib import Path
from urllib.parse import urlparse

import pandas as pd
import pytest

from . import detail_store, details


ROOT = Path(__file__).parents[3]  # backend/python
MIGRATION = ROOT / "smartbi" / "database" / "migrations" / "V20261101_18__factory_chart_detail_contexts.sql"


def test_migration_is_rls_scoped_and_snapshots_are_immutable():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "ENABLE ROW LEVEL SECURITY" in sql
    assert "FORCE ROW LEVEL SECURITY" in sql
    assert "current_setting('app.factory_id', true)" in sql
    assert "user_id VARCHAR" in sql
    assert "role VARCHAR" in sql
    assert "smart_bi_chart_detail_snapshot_immutable" in sql
    assert "GRANT SELECT, INSERT" in sql


def test_prepared_shared_snapshot_dedup_key_binds_owner_source_period_unit():
    owner = ("F006", 12, "factory_super_admin")
    request = {"period_type": "all"}
    frame = pd.DataFrame([{"日期": "2026-01-01", "单位": "kg", "产品": "A", "产量": 1}])
    dataset = {"frame": frame, "source": "production · shared"}
    item = {"id": "p", "title": "产量", "source": "production · shared", "unit": "kg"}
    first = list(detail_store._prepared([item], [dataset], request, owner))[0][1]
    second = list(detail_store._prepared([item], [dataset], request, ("F001", 12, "factory_super_admin")))[0][1]
    assert first["snapshot_key"] != second["snapshot_key"]
    assert first["rows"] == second["rows"]


@pytest.mark.asyncio
async def test_two_processes_register_then_read_shared_context(monkeypatch):
    dsn = os.getenv("FACTORY_SELECTION_TEST_PG_URL") or os.getenv("DETAIL_STORE_TEST_PG_URL")
    if not dsn:
        pytest.skip("set FACTORY_SELECTION_TEST_PG_URL for disposable PostgreSQL")
    target = urlparse(dsn)
    assert target.hostname in ("localhost", "127.0.0.1"), "dedicated local test cluster only"
    assert target.port == 55439, "owned disposable cluster only"
    asyncpg = pytest.importorskip("asyncpg")
    admin = await asyncpg.connect(dsn)
    schema = "factory_chart_detail_" + uuid.uuid4().hex
    role = "smartbi_user"
    await admin.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='smartbi_user') THEN CREATE ROLE smartbi_user NOLOGIN; END IF; END $$")
    await admin.execute(f"CREATE SCHEMA {schema}")
    await admin.execute(f"SET search_path TO {schema},public")
    # Migration/setup errors are real failures. The disposable-cluster guard
    # above is the only permitted skip condition.
    await admin.execute(MIGRATION.read_text(encoding="utf-8"))
    await admin.execute(f"GRANT USAGE ON SCHEMA {schema} TO {role}")

    async def app_role(connection):
        await connection.execute(f"SET ROLE {role}")
        await connection.execute(f"SET search_path TO {schema},public")

    pool = await asyncpg.create_pool(dsn, min_size=1, max_size=2, server_settings={"search_path": f"{schema},public"}, setup=app_role)
    original = detail_store.config.get_pg_pool
    detail_store.config.get_pg_pool = lambda: pool
    try:
        owner = ("F006", 9981, "factory_super_admin")
        frame = pd.DataFrame([{"日期": "2026-01-01", "单位": "kg", "产品": "cross-worker", "产量": 1}])
        item = {"id": "cross-worker", "title": "产量", "source": "production · cross-worker", "unit": "kg",
                "chart": {"echartsOption": {"xAxis": {"data": ["2026-01-01"]}}}}
        registered = await detail_store.register_shared([item], [{"frame": frame, "source": item["source"]}],
                                                         {"period_type": "all"}, owner, True)
        context_id = registered[0]["detailContext"]["id"]
    finally:
        detail_store.config.get_pg_pool = original
        await pool.close()

    script = r'''
import asyncio, os, sys
import asyncpg
from smartbi import config
from smartbi.services.factory_charts import detail_store
async def main():
    async def app_role(connection):
        await connection.execute("SET ROLE smartbi_user")
        await connection.execute("SET search_path TO " + os.environ["DETAIL_SCHEMA"] + ",public")
    pool = await asyncpg.create_pool(os.environ["DETAIL_STORE_TEST_PG_URL"], min_size=1, max_size=1,
                                     server_settings={"search_path": os.environ["DETAIL_SCHEMA"] + ",public"}, setup=app_role)
    detail_store.config.get_pg_pool = lambda: pool
    try:
        value = await detail_store.page_shared({"context_id": os.environ["DETAIL_CONTEXT"], "page": 1, "page_size": 10}, ("F006", 9981, "factory_super_admin"), True, True)
        assert value["data"]["rows"][0]["产品"] == "cross-worker"
        try:
            await detail_store.page_shared({"context_id": os.environ["DETAIL_CONTEXT"], "page": 1, "page_size": 10}, ("F006", 9982, "factory_super_admin"), True, True)
        except detail_store.details.DetailError as exc:
            assert exc.status_code == 403
        else:
            raise AssertionError("same-factory wrong owner must be 403")
        try:
            await detail_store.page_shared({"context_id": os.environ["DETAIL_CONTEXT"], "page": 1, "page_size": 10}, ("F999", 9981, "factory_super_admin"), True, True)
        except detail_store.details.DetailError as exc:
            assert exc.status_code == 410
        else:
            raise AssertionError("foreign factory must be hidden by RLS")
        print("CROSS_PROCESS_READ=PASS")
    finally:
        await pool.close()
asyncio.run(main())
'''
    env = os.environ.copy()
    env["PYTHONPATH"] = os.pathsep.join((str(ROOT), str(ROOT / "smartbi")))
    env["DETAIL_STORE_TEST_PG_URL"] = dsn
    env["DETAIL_CONTEXT"] = context_id
    env["DETAIL_SCHEMA"] = schema
    result = subprocess.run([sys.executable, "-c", script], cwd=str(ROOT.parent.parent), env=env,
                            capture_output=True, text=True, timeout=45)
    if result.returncode != 0 or "CROSS_PROCESS_READ=PASS" not in result.stdout:
        await admin.execute(f"DROP SCHEMA {schema} CASCADE")
        await admin.close()
        raise AssertionError(result.stderr or result.stdout)

    # Expired contexts and their orphaned immutable snapshots are reclaimable;
    # a full per-factory cache must not remain permanently wedged.
    pool = await asyncpg.create_pool(dsn, min_size=1, max_size=2,
                                     server_settings={"search_path": f"{schema},public"}, setup=app_role)
    original = detail_store.config.get_pg_pool
    detail_store.config.get_pg_pool = lambda: pool
    monkeypatch.setattr(detail_store, "MAX_CONTEXTS", 1)
    try:
        reclaim_owner = ("F006-reclaim", 9983, "factory_super_admin")
        reclaim_item = {"id": "reclaim-1", "title": "产量", "source": "production · reclaim", "unit": "kg"}
        reclaim_ds = [{"frame": pd.DataFrame([{"日期": "2026-01-01", "单位": "kg", "产品": "R", "产量": 1}]),
                       "source": "production · reclaim"}]
        first = await detail_store.register_shared([reclaim_item], reclaim_ds, {"period_type": "all"}, reclaim_owner, True)
        first_id = first[0]["detailContext"]["id"]
        await admin.execute("UPDATE smart_bi_factory_chart_detail_contexts SET expires_at=CURRENT_TIMESTAMP - interval '1 second' WHERE context_id=$1", first_id)
        second = await detail_store.register_shared([{**reclaim_item, "id": "reclaim-2"}], reclaim_ds,
                                                     {"period_type": "all"}, reclaim_owner, True)
        assert second[0]["detailContext"]["id"]
        concurrent_owner = ("F006-concurrent", 9984, "factory_super_admin")
        results = await asyncio.gather(
            detail_store.register_shared([{**reclaim_item, "id": "concurrent-1", "source": "production · concurrent"}],
                                          [{"frame": reclaim_ds[0]["frame"], "source": "production · concurrent"}],
                                          {"period_type": "all"}, concurrent_owner, True),
            detail_store.register_shared([{**reclaim_item, "id": "concurrent-2", "source": "production · concurrent"}],
                                          [{"frame": reclaim_ds[0]["frame"], "source": "production · concurrent"}],
                                          {"period_type": "all"}, concurrent_owner, True),
        )
        assert sum("detailContext" in result[0] for result in results) == 1
    finally:
        detail_store.config.get_pg_pool = original
        await pool.close()
        await admin.execute(f"DROP SCHEMA {schema} CASCADE")
        await admin.close()
