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


@pytest.fixture
def full_archive(store, database):
    import psycopg2
    from psycopg2 import sql
    from blacklake.full_archive import Archive
    admin = psycopg2.connect(**database[0])
    with admin:
        with admin.cursor() as cur:
            cur.execute("SELECT to_regclass('blacklake.full_snapshot')")
            if cur.fetchone()[0] is None:
                cur.execute((Path(__file__).parents[1] / "blacklake/migrations/002_full_snapshot.sql").read_text())
            cur.execute(sql.SQL("GRANT SELECT,INSERT,UPDATE ON blacklake.full_snapshot TO {}").format(sql.Identifier(database[1])))
    admin.close()

    class Client:
        settings = store.settings
        coordination = Coordination()
        factory_verified = True
        rows = [{"id": 1, "name": "synthetic", "org": "factory-test"}]
        calls = 0

        def query(self, resource, body):
            self.calls += 1
            return {"data": self.rows}

    profile = {"product": {"paged": False, "items": "data", "keys": ["id"],
                           "factory": "org", "fields": {"name": "name"}}}
    return Archive(Client(), store, profile, str(uuid.uuid4()))


def test_full_archive_nullable_source_time_atomic_receipt_and_resume(full_archive):
    first = full_archive.ingest("product", {})
    assert first == {"resource": "product", "count": 1, "reused": False}
    assert full_archive.ingest("product", {})["reused"] is True
    assert full_archive.client.calls == 1
    audit = full_archive.audit()
    assert audit["records"] == 1 and audit["raw_hash_mismatches"] == 0
    with full_archive.store.transaction() as cur:
        cur.execute("SELECT source_updated_at,normalized FROM blacklake.full_snapshot")
        value, normalized = cur.fetchone()
        assert value is None and normalized["source_timestamp_available"] is False
        cur.execute("SELECT set_config('blacklake.source_factory_id','foreign',true)")
        cur.execute("SELECT count(*) FROM blacklake.full_snapshot")
        assert cur.fetchone()[0] == 0


def test_full_archive_cross_factory_and_parent_scope_roll_back(full_archive):
    full_archive.client.rows = [{"id": 1, "org": "factory-test"}, {"id": 2, "org": "foreign"}]
    with pytest.raises(BlacklakeError, match="FACTORY_MISMATCH"):
        full_archive.ingest("product", {})
    assert full_archive.audit()["records"] == 0
    full_archive.client.rows = [{"id": 1, "org": "factory-test"}]
    with pytest.raises(BlacklakeError, match="PARENT_SCOPE_MISMATCH"):
        full_archive.ingest("product", {}, membership=lambda row: row["id"] == 999)
    assert full_archive.audit()["records"] == 0


def test_full_archive_rejects_duplicate_or_mutated_source_across_partitions(full_archive):
    full_archive.ingest("product", {"partition": 1})
    with pytest.raises(BlacklakeError, match="DUPLICATE_ACROSS_PARTITIONS"):
        full_archive.ingest("product", {"partition": 2})
    full_archive.client.rows = [{"id": 1, "org": "factory-test", "name": "changed"}]
    with pytest.raises(BlacklakeError, match="SOURCE_CHANGED_DURING_SCAN"):
        full_archive.ingest("product", {"partition": 3})
    assert full_archive.audit()["records"] == 1


def test_full_archive_duplicate_rows_in_one_partition_roll_back(full_archive):
    full_archive.client.rows *= 2
    with pytest.raises(BlacklakeError, match="FULL_DUPLICATE_ID"):
        full_archive.ingest("product", {})
    assert full_archive.audit()["records"] == 0


def test_full_archive_preserves_unresolved_current_process(full_archive):
    full_archive.contract["project_expand"] = {"paged": False, "items": "data", "wrap_parent_response": True,
                                                "keys": ["requested_project_id"], "fields": {"resolved": "identity_resolved"}}
    full_archive.client.rows = [{"projectId": None, "currentProcessId": None}]
    full_archive.ingest("project_expand", {"projectIds": [10]})
    raw = full_archive.raw_rows("project_expand")
    assert raw[0]["identity_resolved"] is False and raw[0]["response"][0]["projectId"] is None


def test_full_archive_materials_cannot_clone_ids_under_different_parents(full_archive):
    full_archive.contract["materials"] = {"paged": False, "items": "data", "keys": ["id"], "fields": {"id": "id"}}
    full_archive.ingest("materials", {"projectId": 10}, context=(10,))
    with pytest.raises(BlacklakeError, match="MATERIAL_ID_REPEATED_ACROSS_PARENTS"):
        full_archive.ingest("materials", {"projectId": 20}, context=(20,))
    with full_archive.store.transaction() as cur:
        cur.execute("SELECT normalized FROM blacklake.full_snapshot")
        assert cur.fetchone()[0]["parent_scope_verified"] is False


def test_full_archive_materials_rejects_wrong_returned_parent(full_archive):
    full_archive.contract["materials"] = {"paged": False, "items": "data", "keys": ["id"], "fields": {"id": "id"}}
    full_archive.client.rows = [{"id": 1, "projectId": 20}]
    with pytest.raises(BlacklakeError, match="PARENT_SCOPE_MISMATCH"):
        full_archive.ingest("materials", {"projectId": 10}, context=(10,))


def test_full_archive_schema_contract_checks_primary_key_and_policy(full_archive):
    from blacklake.readiness import validate_archive_schema
    with full_archive.store.transaction() as cur:
        validate_archive_schema(cur)


def test_full_archive_validation_version_changes_receipt_identity(full_archive, monkeypatch):
    import blacklake.full_archive as module
    before = full_archive.partition_id("product", {}, ())
    monkeypatch.setattr(module, "ARCHIVE_VALIDATION_VERSION", module.ARCHIVE_VALIDATION_VERSION + 1)
    assert full_archive.partition_id("product", {}, ()) != before


@pytest.mark.parametrize("case", ["different_run", "unfinished", "missing_row"])
def test_full_offline_verification_rejects_wrong_or_partial_run(case):
    from blacklake.full_verify import validate_retained_run
    run = {"run_id": "one", "run_finished": True}
    report = {"run_id": "one", "source_totals": {"product": 2}}
    audit = {"records": 2, "counts": {"product": 2}}
    if case == "different_run":
        report["run_id"] = "old"
    elif case == "unfinished":
        run["run_finished"] = False
    else:
        audit = {"records": 1, "counts": {"product": 1}}
    with pytest.raises(BlacklakeError):
        validate_retained_run(run, report, audit)


def test_full_archive_schema_rejects_truncate_grant(full_archive, database):
    import psycopg2
    from psycopg2 import sql
    from blacklake.readiness import validate_archive_schema
    admin = psycopg2.connect(**database[0])
    try:
        with admin:
            with admin.cursor() as cur:
                cur.execute(sql.SQL("GRANT TRUNCATE ON blacklake.full_snapshot TO {}").format(sql.Identifier(database[1])))
        with full_archive.store.transaction() as cur:
            with pytest.raises(BlacklakeError, match="EXCESS_PRIVILEGES"):
                validate_archive_schema(cur)
    finally:
        with admin:
            with admin.cursor() as cur:
                cur.execute(sql.SQL("REVOKE TRUNCATE ON blacklake.full_snapshot FROM {}").format(sql.Identifier(database[1])))
        admin.close()


def test_full_offline_context_never_loads_source_account_or_cache(monkeypatch, tmp_path):
    import blacklake.full_verify as module
    # Secret loading is stubbed below; set LOCALAPPDATA so Linux CI has a path to name.
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path))
    loaded = []

    def load(path):
        loaded.append(path.name)
        if path.name == "source-binding.dpapi":
            return {"owner_confirmed": True, "expected_org_id": "synthetic"}
        assert path.name == "test-database.dpapi"
        return {"runtime_dsn": "synthetic"}

    monkeypatch.setattr(module, "load_local_secret", load)
    monkeypatch.setattr(module, "database_ready", lambda *args, **kwargs: None)
    monkeypatch.setattr(module.psycopg2, "connect", lambda dsn: object())
    monkeypatch.setattr(module, "PostgresStore", lambda connection, settings: settings)
    _, scope = module.offline_context()
    assert scope.source_factory_id == "synthetic"
    assert loaded == ["source-binding.dpapi", "test-database.dpapi"]


def test_smartbi_real_postgres_import_hash_and_tenant_isolation(store, database, monkeypatch):
    import psycopg2
    from psycopg2 import sql
    from psycopg2.extras import Json
    from blacklake.smartbi_store import read
    from blacklake.full_archive import digest
    admin=psycopg2.connect(**database[0])
    run_id=str(uuid.uuid4())
    metadata={'source_totals':{'project':1},'finished_at':'2026-11-03T09:21:49+00:00',
              'run_id':run_id,'stock_pairs_not_returned':2}
    payload={'id':1,'projectCode':'SYNTHETIC','status':'0','projectUnit':'kg'}
    with admin:
        with admin.cursor() as cur:
            cur.execute((Path(__file__).parents[1]/'blacklake/migrations/003_smartbi_dataset.sql').read_text())
            cur.execute(sql.SQL('ALTER ROLE {} NOINHERIT').format(sql.Identifier(database[1])))
            cur.execute(sql.SQL('GRANT USAGE ON SCHEMA blacklake_bi TO {}').format(sql.Identifier(database[1])))
            cur.execute(sql.SQL('GRANT SELECT ON ALL TABLES IN SCHEMA blacklake_bi TO {}').format(sql.Identifier(database[1])))
            cur.execute('INSERT INTO blacklake_bi.dataset(tenant_id,source_factory_id,run_id,metadata) VALUES(%s,%s,%s,%s)',(*store.scope,run_id,Json(metadata)))
            cur.execute('INSERT INTO blacklake_bi.record VALUES(%s,%s,%s,%s,%s,%s,%s)',(*store.scope,run_id,'project','1',digest(payload),Json(payload)))
    from psycopg2.extensions import make_dsn
    settings={'dsn':make_dsn(**dict(database[0],user=database[1],password=database[2])),
              'tenant_id':store.scope[0],'source_factory_id':store.scope[1],'run_id':run_id}
    result=read(settings)
    assert result['production']['projectCount']==1
    assert result['cost']['projects'][0]['materialCost'] is None
    from blacklake.gap_evidence import EVIDENCE_SCHEMA
    import blacklake.smartbi_store as reader
    evidence={'schema':EVIDENCE_SCHEMA,'status':'complete','negativeControlVerified':True,
              'tenantId':store.scope[0],'sourceFactoryId':store.scope[1],'runId':run_id,
              'snapshotFingerprint':digest([('project','1',digest(payload))]),'verifiedMaterialParents':[]}
    with monkeypatch.context() as patch:
        patch.setattr(reader,'load_local_secret',lambda _:evidence)
        with_file=dict(settings,gap_evidence_file='synthetic-protected-evidence')
        assert read(with_file)['costIntelligence']['gapEvidence']['status']=='complete'
        evidence['sourceFactoryId']='foreign'
        scoped=read(with_file)['costIntelligence']
        assert scoped['gapEvidence']['status']=='scope_or_snapshot_mismatch' and scoped['verifiedMaterialReferences']=={}
        evidence['sourceFactoryId']=store.scope[1];evidence['status']='incomplete'
        assert read(with_file)['costIntelligence']['verifiedMaterialReferences']=={}
    from blacklake.bi_readiness import validate
    for permission,grantee in [('SELECT','PUBLIC'),('INSERT',database[1])]:
        with admin:
            with admin.cursor() as cur:
                cur.execute(sql.SQL('GRANT {} ON blacklake_bi.record TO {}').format(sql.SQL(permission),sql.Identifier(grantee) if grantee!='PUBLIC' else sql.SQL('PUBLIC')))
        with pytest.raises(RuntimeError,match='EXTRA_GRANTEE|GRANTS_UNSAFE'):
            read(settings)
        with admin:
            with admin.cursor() as cur:
                cur.execute(sql.SQL('REVOKE {} ON blacklake_bi.record FROM {}').format(sql.SQL(permission),sql.Identifier(grantee) if grantee!='PUBLIC' else sql.SQL('PUBLIC')))
    with pytest.raises(RuntimeError,match='DATASET_NOT_FOUND'):
        read(dict(settings,tenant_id='foreign'))
    with store.transaction() as cur:
        cur.execute('SELECT count(*) FROM blacklake_bi.record')
        assert cur.fetchone()[0]==1
        with pytest.raises(psycopg2.errors.InsufficientPrivilege):
            cur.execute('DELETE FROM blacklake_bi.record')
    with admin:
        with admin.cursor() as cur:
            cur.execute("UPDATE blacklake_bi.record SET payload=payload||'{\"planAmount\":999}'::jsonb")
    with pytest.raises(RuntimeError,match='INTEGRITY_FAILED'): read(settings)
    admin.close()
