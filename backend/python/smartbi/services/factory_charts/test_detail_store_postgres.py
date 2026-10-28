"""Cross-process checks for the PostgreSQL detail store.

The live test is opt-in because it needs a disposable database with the
V20261101_18 migration applied. It intentionally uses two Python processes;
two ASGI clients in one process would not catch worker-local cache bugs.
"""
import asyncio
import os
import subprocess
import sys
import time
import uuid
from pathlib import Path
from urllib.parse import urlparse

import pandas as pd
import pytest

from . import detail_store, details
from . import engine
from .engine import Dataset


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


def test_shared_snapshot_binds_all_three_upload_ids_even_for_identical_rows():
    frame = pd.DataFrame([{"日期": "2026-01-01", "单位": "kg", "产品": "A", "产量": 1}])
    dataset = {"frame": frame, "source": "uploaded-production-inspection-fused · 显式上传"}
    item = {"id": "joint", "source": dataset["source"], "unit": "kg"}
    owner = ("F006", 12, "factory_super_admin")
    request = {"source": "upload", "upload_id": 101, "inspection_upload_id": 102, "cost_upload_id": 103, "period_type": "all"}
    first = list(detail_store._prepared([item], [dataset], request, owner))[0][1]
    for field in ("upload_id", "inspection_upload_id", "cost_upload_id"):
        changed = list(detail_store._prepared([item], [dataset], {**request, field: 999}, owner))[0][1]
        assert first["rows"] == changed["rows"]
        assert first["snapshot_key"] != changed["snapshot_key"]


@pytest.mark.asyncio
@pytest.mark.timeout(180)
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
        snapshot_key = registered[0]["detailSnapshotKey"]
        same_snapshot = await detail_store.register_shared(
            [item], [{"frame": frame, "source": item["source"]}],
            {"period_type": "all"}, owner, True)
        assert same_snapshot[0]["detailContext"]["id"] == context_id
        assert same_snapshot[0]["detailSnapshotKey"] == snapshot_key

        # A changed immutable row must not reuse the old context even when its
        # aggregate value remains the same.
        changed_frame = frame.copy()
        changed_frame.loc[0, "产品"] = "cross-worker-changed"
        changed = await detail_store.register_shared(
            [item], [{"frame": changed_frame, "source": item["source"]}],
            {"period_type": "all"}, owner, True)
        assert changed[0]["detailContext"]["id"] != context_id
        assert changed[0]["detailSnapshotKey"] != snapshot_key

        other_owner = ("F006", 9982, "factory_super_admin")
        cross_owner = await detail_store.register_shared(
            [item], [{"frame": frame, "source": item["source"]}],
            {"period_type": "all"}, other_owner, True)
        assert cross_owner[0]["detailContext"]["id"] != context_id
        assert cross_owner[0]["detailSnapshotKey"] != snapshot_key

        assert await admin.fetchval(
            "SELECT has_table_privilege($1,$2,'UPDATE')", role,
            f"{schema}.smart_bi_factory_chart_detail_snapshots",
        ) is False
        snapshot_id = await admin.fetchval(
            "SELECT snapshot_id FROM smart_bi_factory_chart_detail_contexts WHERE context_id=$1", context_id)
        with pytest.raises(asyncpg.PostgresError):
            await admin.execute(
                "UPDATE smart_bi_factory_chart_detail_snapshots SET source=source WHERE snapshot_id=$1",
                snapshot_id,
            )
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
    child_started = time.monotonic()
    child = subprocess.Popen([sys.executable, "-c", script], cwd=str(ROOT.parent.parent), env=env,
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        child_stdout, child_stderr = child.communicate(timeout=120)
    except subprocess.TimeoutExpired as exc:
        elapsed = time.monotonic() - child_started
        child.kill()
        child_stdout, child_stderr = child.communicate()
        log_dir = ROOT.parent.parent / "test-output"
        log_dir.mkdir(parents=True, exist_ok=True)
        (log_dir / "sc28-pg-cross-process-timeout.txt").write_text(
            "original_timeout_seconds=45\n"
            "original_failure=subprocess.TimeoutExpired during child import/startup\n"
            f"new_timeout_seconds=120\nelapsed_seconds={elapsed:.2f}\n"
            f"stdout={child_stdout or exc.stdout!r}\nstderr={child_stderr or exc.stderr!r}\n",
            encoding="utf-8",
        )
        await admin.execute(f"DROP SCHEMA {schema} CASCADE")
        await admin.close()
        raise
    child_elapsed = time.monotonic() - child_started
    print(f"CROSS_PROCESS_CHILD_STARTUP_SECONDS={child_elapsed:.2f}")
    result = subprocess.CompletedProcess(child.args, child.returncode, child_stdout, child_stderr)
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
        overcap_owner = ("F006-overcap", 9980, "factory_super_admin")
        overcap_source = "production · overcap"
        overcap_frame = pd.DataFrame([{"日期": "2026-01-01", "单位": "kg", "产品": "C", "产量": 1}])
        overcap_first = await detail_store.register_shared(
            [{"id": "overcap-1", "title": "产量", "source": overcap_source, "unit": "kg"}],
            [{"frame": overcap_frame, "source": overcap_source}],
            {"period_type": "all"}, overcap_owner, True)
        assert overcap_first[0]["detailContext"]["id"]
        overcap_second = await detail_store.register_shared(
            [{"id": "overcap-2", "title": "产量", "source": overcap_source, "unit": "kg"}],
            [{"frame": overcap_frame, "source": overcap_source}],
            {"period_type": "all"}, overcap_owner, True)
        assert "detailContext" not in overcap_second[0]
        assert overcap_second[0]["detailStatus"]["state"] == "capacity"
        assert overcap_second[0]["detailSnapshotKey"]

        reclaim_owner = ("F006-reclaim", 9983, "factory_super_admin")
        reclaim_item = {"id": "reclaim-1", "title": "产量", "source": "production · reclaim", "unit": "kg"}
        reclaim_ds = [{"frame": pd.DataFrame([{"日期": "2026-01-01", "单位": "kg", "产品": "R", "产量": 1}]),
                       "source": "production · reclaim"}]
        first = await detail_store.register_shared([reclaim_item], reclaim_ds, {"period_type": "all"}, reclaim_owner, True)
        first_id = first[0]["detailContext"]["id"]
        first_snapshot_key = first[0]["detailSnapshotKey"]
        first_snapshot_id = await admin.fetchval(
            "SELECT snapshot_id FROM smart_bi_factory_chart_detail_contexts WHERE context_id=$1", first_id)
        await admin.execute("UPDATE smart_bi_factory_chart_detail_contexts SET expires_at=CURRENT_TIMESTAMP - interval '1 second' WHERE context_id=$1", first_id)
        second = await detail_store.register_shared([{**reclaim_item, "id": "reclaim-2"}], reclaim_ds,
                                                     {"period_type": "all"}, reclaim_owner, True)
        assert second[0]["detailContext"]["id"]
        assert second[0]["detailContext"]["id"] != first_id
        replacement_snapshot_id = await admin.fetchval(
            "SELECT snapshot_id FROM smart_bi_factory_chart_detail_contexts WHERE context_id=$1",
            second[0]["detailContext"]["id"],
        )
        assert replacement_snapshot_id != first_snapshot_id
        assert await admin.fetchval(
            "SELECT count(*) FROM smart_bi_factory_chart_detail_snapshots WHERE dedup_key=$1",
            first_snapshot_key,
        ) == 1
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


@pytest.mark.asyncio
async def test_real_shared_store_stock_count_mixed_units_and_bucket_point(monkeypatch):
    dsn = os.getenv("FACTORY_SELECTION_TEST_PG_URL") or os.getenv("DETAIL_STORE_TEST_PG_URL")
    if not dsn:
        pytest.skip("set FACTORY_SELECTION_TEST_PG_URL for disposable PostgreSQL")
    target = urlparse(dsn)
    assert target.hostname in ("localhost", "127.0.0.1") and target.port == 55439
    asyncpg = pytest.importorskip("asyncpg")
    admin = await asyncpg.connect(dsn)
    schema = "stock_detail_" + uuid.uuid4().hex
    role = "stock_reader_" + uuid.uuid4().hex[:12]
    await admin.execute(f"CREATE SCHEMA {schema}")
    await admin.execute(f"CREATE ROLE {role} NOLOGIN")
    await admin.execute(f"SET search_path TO {schema},public")
    await admin.execute(MIGRATION.read_text(encoding="utf-8"))
    await admin.execute(f"GRANT USAGE ON SCHEMA {schema} TO {role}")
    await admin.execute(f"GRANT SELECT,INSERT,DELETE ON ALL TABLES IN SCHEMA {schema} TO {role}")
    await admin.execute(f"GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA {schema} TO {role}")

    async def setup(connection):
        await connection.execute(f"SET ROLE {role}")
        await connection.execute(f"SET search_path TO {schema},public")

    pool = await asyncpg.create_pool(dsn, min_size=1, max_size=1, setup=setup,
                                     server_settings={"search_path": f"{schema},public"})
    original = detail_store.config.get_pg_pool
    detail_store.config.get_pg_pool = lambda: pool
    try:
        units = (["kg"] * 34) + (["个"] * 17) + (["袋"] * 16)
        ages = ([10] * 20) + ([45] * 20) + ([120] * 27)
        frame = pd.DataFrame([
            {"批次": f"pg-b-{i:03d}", "日期": "2026-10-01", "单位": unit,
             "产品": "混单位原料", "库存数量": 1, "库龄天数": age}
            for i, (unit, age) in enumerate(zip(units, ages), 1)
        ])
        dataset = Dataset(frame, "inventory · PG正余额批次")
        request = type("Request", (), {"period_type": "all", "include_optional": False})()
        candidates, _ = engine.build([dataset], request, True)
        candidate = next(item for item in candidates if item["id"] == "inventory.stock.age:bar")
        owner = ("F006", 771, "factory_super_admin")
        registered = await detail_store.register_shared([candidate], [dataset], request, owner, True)
        context_id = registered[0]["detailContext"]["id"]
        first = await detail_store.page_shared({"context_id": context_id, "page": 1, "page_size": 50}, owner, True, True)
        second = await detail_store.page_shared({"context_id": context_id, "page": 2, "page_size": 50}, owner, True, True)
        assert first["data"]["total"] == 67 and len(first["data"]["rows"]) == 50
        assert second["data"]["total"] == 67 and len(second["data"]["rows"]) == 17
        assert {row["单位"] for row in first["data"]["rows"]} | {row["单位"] for row in second["data"]["rows"]} == {"kg", "个", "袋"}
        filtered = await detail_store.page_shared({"context_id": context_id, "point": {"name": "不超过30天"}, "page_size": 50}, owner, True, True)
        assert filtered["data"]["total"] == 20
        tampered = dict(candidate)
        tampered["engine"] = {"stockCountContract": dict(candidate["engine"]["stockCountContract"], bucketColumn="单位")}
        rejected = await detail_store.register_shared([tampered], [dataset], request, owner, True)
        assert "detailContext" not in rejected[0]
        with pytest.raises(details.DetailError) as denied:
            await detail_store.page_shared({"context_id": context_id, "page": 1}, ("F999", 771, "factory_super_admin"), True, True)
        assert denied.value.status_code == 410
        trusted_frame = pd.DataFrame([
            {"日期": "2026-10-01", "单位": "kg", "产品（单位）": "相同SKU（kg）", "数量": 2},
            {"日期": "2026-10-01", "单位": "盒", "产品（单位）": "相同SKU（盒）", "数量": 3},
        ])
        trusted_source = "finished-goods-inventory · PG混单位精确点位"
        trusted_candidate = {"id": "trusted-point", "source": trusted_source, "unit": "数量",
                             "engine": {"pointDimension": "产品（单位）", "detailUnitPolicy": "trusted_quantity_rows"},
                             "chart": {"chartType": "bar_horizontal", "echartsOption": {}}}
        registered = await detail_store.register_shared([trusted_candidate], [Dataset(trusted_frame, trusted_source)], request, owner, True)
        trusted_context = registered[0]["detailContext"]["id"]
        loaded = await detail_store._load(trusted_context, owner)
        assert loaded.point_dimension == "产品（单位）"
        for label, unit, quantity in (("相同SKU（kg）", "kg", 2), ("相同SKU（盒）", "盒", 3)):
            result = await detail_store.page_shared({"context_id": trusted_context, "point": {"name": label}}, owner, True, True)
            assert result["data"]["total"] == 1
            assert result["data"]["rows"][0]["单位"] == unit
            assert result["data"]["rows"][0]["数量"] == quantity
        with pytest.raises(details.DetailError) as bad_point:
            await detail_store.page_shared({"context_id": trusted_context, "point": {"name": "不存在"}}, owner, True, True)
        assert bad_point.value.status_code == 422
    finally:
        detail_store.config.get_pg_pool = original
        await pool.close()
        await admin.execute(f"DROP SCHEMA {schema} CASCADE")
        await admin.execute(f"DROP ROLE {role}")
        await admin.close()
