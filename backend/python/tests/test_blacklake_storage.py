"""Opt-in disposable PostgreSQL/Redis tests. No production fallback DSN."""
import copy
import os
import uuid
import secrets
from datetime import datetime, timezone
from pathlib import Path

import pytest

from blacklake.contracts import BlacklakeError, Settings
from blacklake.coordination import RedisCoordination
from blacklake.storage import PostgresStore
from blacklake.sync import Synchronizer
from tests.test_blacklake_connector import Coordination, contract


@pytest.fixture(scope="module")
def database():
    import psycopg2
    from psycopg2.extensions import parse_dsn
    dsn = os.getenv("BLACKLAKE_TEST_ADMIN_DSN")
    if not dsn:
        pytest.skip("BLACKLAKE_TEST_ADMIN_DSN not configured (no production fallback)")
    parsed = parse_dsn(dsn)
    if not parsed.get("dbname", "").startswith("blacklake_connector_test_"):
        pytest.fail("Dedicated disposable blacklake_connector_test_* database required")
    admin = psycopg2.connect(dsn)
    runtime_role = "blacklake_test_" + uuid.uuid4().hex[:12]
    runtime_password = secrets.token_urlsafe(24)
    with admin:
        with admin.cursor() as cur:
            from psycopg2 import sql
            cur.execute((Path(__file__).parents[1] / "blacklake/migrations/001_blacklake.sql").read_text())
            cur.execute(sql.SQL("CREATE ROLE {} LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD %s").format(sql.Identifier(runtime_role)),
                        (runtime_password,))
            cur.execute(sql.SQL("GRANT USAGE ON SCHEMA blacklake TO {}").format(sql.Identifier(runtime_role)))
            cur.execute(sql.SQL("GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA blacklake TO {}").format(sql.Identifier(runtime_role)))
    yield parsed, runtime_role, runtime_password
    admin.close()


@pytest.fixture
def store(database):
    import psycopg2
    parsed, role, password = database
    parsed = dict(parsed, user=role, password=password)
    conn = psycopg2.connect(**parsed)
    settings = Settings("test-" + uuid.uuid4().hex, "factory-test", "phone", "password")
    store = PostgresStore(conn, settings)
    yield store
    conn.close()


NOW = datetime(2026, 11, 1, tzinfo=timezone.utc)
START = datetime(2026, 10, 31, tzinfo=timezone.utc)


class FakeClient:
    def __init__(self, settings, batches):
        self.settings, self.batches = settings, batches
        self.coordination = Coordination()

    def pages(self, resource, body):
        for batch in self.batches:
            if isinstance(batch, Exception):
                raise batch
            yield batch


def row(external_id="one", **kwargs):
    return dict({"factory": "factory-test", "id": external_id,
                 "updated": NOW.isoformat(), "fineAmount": 20}, **kwargs)


def test_migration_upsert_versions_and_tenant_factory_rls(store):
    import psycopg2
    with store.transaction() as cur:
        store.upsert(cur, "project", "one", NOW, {"v": 1}, {"good": "20"})
        store.upsert(cur, "project", "one", NOW, {"v": 1}, {"good": "20"})
        cur.execute("SELECT count(*) FROM blacklake.record")
        assert cur.fetchone()[0] == 1
        cur.execute("SELECT count(*) FROM blacklake.raw_record")
        assert cur.fetchone()[0] == 1
    with store.transaction() as cur:
        store.upsert(cur, "project", "one", NOW, {"v": 2}, {"good": "25"})
        cur.execute("SELECT count(*) FROM blacklake.raw_record")
        assert cur.fetchone()[0] == 2
        # RLS still protects SQL that accidentally omits explicit predicates.
        cur.execute("SELECT set_config('blacklake.tenant_id','another-tenant',true)")
        cur.execute("SELECT count(*) FROM blacklake.record")
        assert cur.fetchone()[0] == 0
    with store.transaction() as cur:
        cur.execute("SELECT set_config('blacklake.source_factory_id','another-factory',true)")
        cur.execute("SELECT count(*) FROM blacklake.record")
        assert cur.fetchone()[0] == 0
        with pytest.raises(psycopg2.errors.InsufficientPrivilege):
            store.upsert(cur, "project", "two", NOW, {}, {})
    store.connection.rollback()


def test_failed_page_rolls_back_records_and_cursor_then_recovers(store):
    client = FakeClient(store.settings, [[row()], BlacklakeError("UPSTREAM_RETRY_NEXT_RUN")])
    sync = Synchronizer(client, store, contract())
    with pytest.raises(BlacklakeError, match="RETRY_NEXT_RUN"):
        sync.sync_window("project", START, NOW)
    assert store.records("project") == [] and store.cursor("project") is None
    with store.transaction() as cur:
        cur.execute("SELECT status,error_code FROM blacklake.sync_run")
        assert cur.fetchall() == [("failed", "UPSTREAM_RETRY_NEXT_RUN")]
    client.batches = [[row()]]
    assert sync.sync_window("project", START, NOW)["data"]["records"] == 1
    assert store.cursor("project")[0] == NOW
    sync.sync_window("project", START, NOW)
    assert len(store.records("project")) == 1


def test_source_mismatch_duplicate_ids_and_old_update(store):
    client = FakeClient(store.settings, [[row(factory="foreign")]])
    sync = Synchronizer(client, store, contract())
    with pytest.raises(BlacklakeError, match="FACTORY_MISMATCH"):
        sync.sync_window("project", START, NOW)
    client.batches = [[row(), row()]]
    with pytest.raises(BlacklakeError, match="DUPLICATE_ID"):
        sync.sync_window("project", START, NOW)
    assert not store.records("project")
    with store.transaction() as cur:
        store.upsert(cur, "project", "one", NOW, {"value": 2}, {"good": "2"})
        store.upsert(cur, "project", "one", START, {"value": 1}, {"good": "1"})
    assert store.records("project")[0][1]["good"] == "2"


def test_incremental_gate_and_contract_change(store):
    c = contract()
    c["resources"]["project"]["updated_only_verified"] = False
    sync = Synchronizer(FakeClient(store.settings, [[row()]]), store, c)
    with pytest.raises(BlacklakeError, match="UPDATED_ONLY"):
        sync.sync_window("project", START, NOW, incremental=True)
    sync.sync_window("project", START, NOW)
    assert store.cursor("project") is None
    c = contract()
    sync = Synchronizer(FakeClient(store.settings, [[row()]]), store, c)
    sync.sync_window("project", START, NOW)
    changed = copy.deepcopy(c)
    changed["resources"]["project"]["fields"]["unit"] = "unit"
    with pytest.raises(BlacklakeError, match="CONTRACT_CHANGED"):
        Synchronizer(sync.client, store, changed).sync_window("project", START, NOW)


def test_privileged_database_role_rejected(database):
    import psycopg2
    conn = psycopg2.connect(**database[0])
    try:
        with pytest.raises(BlacklakeError, match="BYPASSES_RLS"):
            PostgresStore(conn, Settings("t", "f", "p", "s"))
    finally:
        conn.close()


def test_reconciliation_and_sync_reject_ignored_update_filter(store):
    client = FakeClient(store.settings, [[row(updated="2026-10-01T00:00:00Z")]])
    sync = Synchronizer(client, store, contract())
    with pytest.raises(BlacklakeError, match="FILTER_NOT_APPLIED"):
        sync.sync_window("project", START, NOW)
    assert not store.records("project") and store.cursor("project") is None
    with pytest.raises(BlacklakeError, match="FILTER_NOT_APPLIED"):
        sync.reconcile_window("project", START, NOW)


def test_reconciliation_finds_changed_missing_and_matches(store):
    client = FakeClient(store.settings, [[row()]])
    sync = Synchronizer(client, store, contract())
    sync.sync_window("project", START, NOW)
    assert sync.reconcile_window("project", START, NOW)["data"]["matching_records"] == 1
    client.batches = [[row(fineAmount=21), row("two")]]
    report = sync.reconcile_window("project", START, NOW)["data"]
    assert report["changed_records"] == 1 and report["missing_locally"] == 1
    assert len(store.records("project")) == 1


def test_numeric_audit_detects_corruption(store):
    from blacklake.acceptance import audit_store
    client = FakeClient(store.settings, [[row()]])
    Synchronizer(client, store, contract()).sync_window("project", START, NOW)
    with store.transaction() as cur:
        cur.execute("UPDATE blacklake.record SET normalized=jsonb_set(normalized,'{good}','\"999\"')")
    audit = audit_store(store, contract())
    assert audit["numeric_mismatches"] == 1 and not audit["passed"]


def test_plan_start_window_rejects_out_of_period_source_rows(store):
    c = contract()
    spec = c["resources"]["project"]
    spec.update(window_basis="plan", updated_only_verified=False)
    spec["fields"]["event_time"] = "planStart"
    client = FakeClient(store.settings, [[row(planStart="2026-10-01T00:00:00Z")]])
    with pytest.raises(BlacklakeError, match="PLAN_START_FILTER_NOT_APPLIED"):
        Synchronizer(client, store, c).sync_window("project", START, NOW)
    assert not store.records("project")


def test_real_redis_lock_token_and_atomic_limits():
    url = os.getenv("BLACKLAKE_TEST_REDIS_URL")
    if not url:
        pytest.skip("BLACKLAKE_TEST_REDIS_URL not configured")
    import redis
    r = redis.Redis.from_url(url)
    key = "blacklake-test:" + uuid.uuid4().hex
    first = RedisCoordination(r, key, allow_insecure_synthetic_test=True)
    second = RedisCoordination(r, key, allow_insecure_synthetic_test=True)
    try:
        with first.lease():
            with pytest.raises(BlacklakeError, match="BUSY"):
                with second.lease():
                    pytest.fail("competing lock admitted")
            first.set_token("synthetic-token")
            assert second.get_token() == "synthetic-token"
            from blacklake.coordination import LIMIT
            keys = [key + suffix for suffix in (":qps", ":hour", ":login", ":duplicate")]
            assert r.eval(LIMIT, 4, *keys, "request1", 0, 1) == 1
            assert r.eval(LIMIT, 4, *keys, "request2", 0, 1) == 0
            for i in range(19):
                assert r.eval(LIMIT, 4, *keys, "request-" + str(i), 0, 0) == 1
            assert r.eval(LIMIT, 4, *keys, "request21", 0, 0) == 0
            assert r.zcard(keys[1]) == 20
            # Login budget and overall hourly budget are independent of QPS.
            r.delete(*keys)
            seconds, micros = r.time()
            stamp = seconds * 1000 + micros / 1000
            r.zadd(keys[2], {str(i): stamp for i in range(75)})
            assert r.eval(LIMIT, 4, *keys, "login76", 1, 0) == 0
            assert r.eval(LIMIT, 4, *keys, "ordinary", 0, 0) == 1
            r.delete(*keys)
            r.zadd(keys[1], {str(i): stamp for i in range(10000)})
            assert r.eval(LIMIT, 4, *keys, "hour10001", 0, 0) == 0
            first.clear_token()
            assert second.get_token() is None
        with second.lease():
            second.check_lease()
    finally:
        keys = list(r.scan_iter(key + "*"))
        if keys:
            r.delete(*keys)
        r.close()
