"""Real PG: tenant RLS/immutability in DB; administrator read ACL in service.

Use only the opt-in disposable loopback database used by the migration gate.
No additional database role ACL is implied by the existing tenant-only policy.
"""
import json
import uuid
import copy

import pytest

from smartbi.agent.eval import AgentOpsService, PostgresAgentOpsStore
from smartbi.agent.eval.store import AgentOpsAccessError, AgentOpsConflictError
from .helpers import context, seed_factory_history
from .test_postgres_migration_gate import PG_CONFIRM, PG_DSN, MIGRATIONS, V04, _app_dsn, _apply, _render

asyncpg = pytest.importorskip("asyncpg")
pytestmark = pytest.mark.skipif(not PG_DSN or PG_CONFIRM != "YES", reason="requires disposable loopback PG opt-in")


@pytest.mark.asyncio
@pytest.mark.parametrize("upgrade", [False, True])
async def test_factory_trace_real_store_rls_replay_acl_and_append_only(upgrade):
    from urllib.parse import urlsplit
    assert urlsplit(PG_DSN).hostname in {"127.0.0.1", "localhost", "::1"}
    suffix = uuid.uuid4().hex
    role, schema = "trace_app_" + suffix, "factory_trace_" + suffix
    password = uuid.uuid4().hex
    admin = await asyncpg.connect(PG_DSN)
    pool = None
    role_created = schema_created = False
    try:
        database = str(await admin.fetchval("SELECT current_database()"))
        assert database == "postgres" or "test" in database.lower()
        await admin.execute(f'CREATE ROLE "{role}" LOGIN PASSWORD \'{password}\'')
        role_created = True
        await admin.execute(f'CREATE SCHEMA "{schema}"')
        schema_created = True
        await admin.execute(f'GRANT USAGE ON SCHEMA "{schema}" TO "{role}"')
        if upgrade:
            for name in ("V20260426_02__chat_session.sql", "V20260427_01__chat_session_v3_history.sql",
                         "V20261028_01__smart_bi_agent_run_event.sql", "V20261028_02__chat_session_user_identity.sql"):
                await _apply(admin, schema, _render((MIGRATIONS / name).read_text(encoding="utf-8"), role))
        await _apply(admin, schema, _render(V04.read_text(encoding="utf-8"), role))
        await _apply(admin, schema, _render((MIGRATIONS / "V20261103_01__factory_agent_trace.sql").read_text(encoding="utf-8"), role))

        async def setup(connection):
            await connection.execute(f'SET search_path TO "{schema}", public')

        pool = await asyncpg.create_pool(_app_dsn(PG_DSN, role, password), min_size=1, max_size=2, setup=setup)
        store = PostgresAgentOpsStore(pool)
        service = AgentOpsService(store)
        # Persist an old-build source produced by the frozen algorithm, read it
        # through the real PG store, then rerun to a new append-only record.
        # These are synthetic factory inputs, not the two production rows.
        source = await seed_factory_history(store)
        source_before = copy.deepcopy(source.safe_dict())
        history_ctx = context("F006", user="42", role="platform_admin")
        rerun = await service.rerun_experiment(history_ctx, source.experiment_id, request_id=str(uuid.uuid4()))
        persisted = await store.get_experiment(history_ctx, rerun.experiment_id)
        assert persisted.evaluator_build == source.evaluator_build
        assert persisted.snapshot_digest == source.snapshot_digest
        assert persisted.source_experiment_id == source.experiment_id
        assert persisted.operation_kind == "RERUN" and persisted.aggregate["passedCount"] == 1
        assert (await store.get_experiment(history_ctx, source.experiment_id)).safe_dict() == source_before
        run_id = str(uuid.uuid4())
        payload = {"runId": run_id, "intentCode": "MATERIAL_BATCH_QUERY", "outcome": "SUCCESS", "latencyMs": 420,
                   "steps": [{"toolName": "material_batch_query", "latencyMs": 200, "outcome": "SUCCESS", "sourceRef": "material_batch"},
                             {"toolName": "material_fifo_recommend", "latencyMs": 180, "outcome": "SUCCESS", "sourceRef": "material_batch"}]}
        operator = context("F006", role="operator")
        await service.record_factory_trace(operator, payload)
        await service.record_factory_trace(operator, payload)
        for administrator in ("factory_super_admin", "platform_admin", "permission_admin"):
            page = await service.trace(context("F006", role=administrator), run_id, after_sequence=0, limit=1)
            assert page["routeCode"] == "MATERIAL_BATCH_QUERY"
            assert page["page"]["hasMore"] is True
            assert page["events"][0]["toolName"] == "material_batch_query"
        with pytest.raises(AgentOpsAccessError):
            await service.trace(operator, run_id, after_sequence=0, limit=10)
        with pytest.raises(AgentOpsAccessError):
            await service.trace(context("F999"), run_id, after_sequence=0, limit=10)
        with pytest.raises(AgentOpsConflictError):
            await service.record_factory_trace(operator, {**payload, "latencyMs": 421})
        with pytest.raises(AgentOpsConflictError):
            await service.record_factory_trace(context("F999"), payload)

        async with pool.acquire() as connection:
            async with connection.transaction():
                await connection.execute("SELECT set_config('app.factory_id','F999',true)")
                assert await connection.fetchval("SELECT COUNT(*) FROM smart_bi_factory_agent_trace") == 0
                with pytest.raises(asyncpg.InsufficientPrivilegeError):
                    async with connection.transaction():
                        await connection.execute("INSERT INTO smart_bi_factory_agent_trace(run_id,factory_id,intent_code,outcome,latency_ms,steps,recorded_by) VALUES($1::uuid,'F006','MATERIAL_BATCH_QUERY','SUCCESS',1,$2::jsonb,'operator')", str(uuid.uuid4()), json.dumps(payload["steps"]))
            for sql in ("UPDATE smart_bi_factory_agent_trace SET latency_ms=1", "DELETE FROM smart_bi_factory_agent_trace"):
                with pytest.raises(asyncpg.InsufficientPrivilegeError):
                    await connection.execute(sql)
        await admin.execute(f'SET search_path TO "{schema}", public')
        assert await admin.fetchval("SELECT COUNT(*) FROM smart_bi_factory_agent_trace") == 1
        for sql in ("UPDATE smart_bi_factory_agent_trace SET latency_ms=1", "DELETE FROM smart_bi_factory_agent_trace"):
            with pytest.raises(asyncpg.RaiseError, match="immutable"):
                await admin.execute(sql)
    finally:
        if pool is not None:
            await pool.close()
        if schema_created:
            await admin.execute("RESET search_path")
            await admin.execute(f'DROP SCHEMA "{schema}" CASCADE')
        if role_created:
            await admin.execute(f'DROP ROLE "{role}"')
        await admin.close()
