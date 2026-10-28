"""Opt-in two-process PostgreSQL checks for durable factory conversations."""
import asyncio
import os
import re
import subprocess
import sys
import uuid
from pathlib import Path
from urllib.parse import urlparse

import pytest

from . import conversation, conversation_store, details


@pytest.mark.asyncio
async def test_postgres_schema_only_runner_fixture():
    """Use the pre-created smartbi_user-owned validation schema only."""
    dsn = os.getenv("FACTORY_ANALYSIS_CONVERSATION_SCHEMA_DSN")
    schema = os.getenv("FACTORY_ANALYSIS_CONVERSATION_SCHEMA")
    if not dsn and not schema:
        pytest.skip("set FACTORY_ANALYSIS_CONVERSATION_SCHEMA_DSN and ..._SCHEMA for local PG17 fixture")
    assert dsn and schema and re.fullmatch(r"fadctx27_validation_[0-9a-f]{12,32}", schema)
    target = urlparse(dsn)
    assert target.hostname in ("localhost", "127.0.0.1") and target.port == 5432
    assert (target.path or "").lstrip("/") == "smartbi_db"
    asyncpg = pytest.importorskip("asyncpg")
    root = Path(__file__).parents[3]
    migration = root / "smartbi" / "database" / "migrations" / "V20261101_20__factory_analysis_conversations.sql"
    conn = await asyncpg.connect(dsn, server_settings={"search_path": f"{schema},public"})
    try:
        role = await conn.fetchrow("SELECT current_role, rolsuper, rolbypassrls FROM pg_roles WHERE rolname=current_user")
        assert role["current_role"] == "smartbi_user" and not role["rolsuper"] and not role["rolbypassrls"]
        assert (await conn.fetchval("SHOW server_version_num")).startswith("17")
        assert await conn.fetchval("SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname=$1", schema) == "smartbi_user"
        assert await conn.fetchval("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=$1 AND c.relkind='r'", schema) == 0
        await conn.execute(migration.read_text(encoding="utf-8"))
        await conn.execute("SELECT set_config('app.factory_id','F006',false)")
        pool = await asyncpg.create_pool(dsn, min_size=1, max_size=2,
            server_settings={"search_path": f"{schema},public"})
        async def get_pool():
            return pool
        old = conversation_store.set_store_for_tests(conversation_store.ConversationStore(get_pool))
        try:
            owner = ("F006", 1309, "factory_super_admin")
            created = await conversation.create_shared_context(owner, "business", None, {"period_type": "all"}, question="首轮")
            conversation.touch_context(created, "首轮", [])
            await conversation.save_shared_context(created)
            script = r'''
import asyncio, os, asyncpg
from smartbi.services.factory_charts import conversation, conversation_store
async def main():
    async def get_pool(): return pool
    async def setup(c):
        await c.execute("SELECT set_config('app.factory_id','F006',false)")
    pool = await asyncpg.create_pool(os.environ['CONV_DSN'], min_size=1, max_size=1,
        server_settings={'search_path': os.environ['CONV_SCHEMA'] + ',public'}, setup=setup)
    old = conversation_store.set_store_for_tests(conversation_store.ConversationStore(get_pool))
    try:
        ctx = await conversation.claim_shared_context(os.environ['CONV_ID'], ('F006',1309,'factory_super_admin'))
        conversation.touch_context(ctx, 'schema fixture followup', ['production.output:line'])
        await conversation.save_shared_context(ctx)
        print('SCHEMA_WORKER=PASS')
    finally:
        conversation_store.set_store_for_tests(old); await pool.close()
asyncio.run(main())
'''
            env = os.environ.copy()
            env.update(CONV_DSN=dsn, CONV_SCHEMA=schema, CONV_ID=created.context_id,
                       PYTHONPATH=os.pathsep.join((str(root), str(root / 'smartbi'))))
            result = subprocess.run([sys.executable, '-c', script], cwd=str(root.parent.parent),
                                    env=env, capture_output=True, text=True, timeout=45)
            assert result.returncode == 0 and 'SCHEMA_WORKER=PASS' in result.stdout, result.stderr or result.stdout
            print('SCHEMA_WORKER=PASS')
            claimed = await conversation.claim_shared_context(created.context_id, owner)
            assert [turn['question'] for turn in claimed.history] == ['首轮', 'schema fixture followup']
            await conversation.release_shared_context(claimed)
            assert (await conn.fetchval("SELECT current_user")) == "smartbi_user"
            held = await conversation.claim_shared_context(created.context_id, owner)
            for bad_owner, status in [(owner, 409),
                                      (("F006", 1310, "factory_super_admin"), 403),
                                      (("F006", 1309, "factory_viewer"), 403),
                                      (("F999", 1309, "factory_super_admin"), 410)]:
                with pytest.raises(details.DetailError) as denied:
                    await conversation.claim_shared_context(created.context_id, bad_owner)
                assert denied.value.status_code == status
            await conversation.release_shared_context(held)
            stale = await conversation.claim_shared_context(created.context_id, owner)
            await conn.execute("UPDATE smart_bi_factory_analysis_conversations SET lease_expires_at=CURRENT_TIMESTAMP - interval '1 second' WHERE context_id=$1", created.context_id)
            winner = await conversation.claim_shared_context(created.context_id, owner)
            conversation.touch_context(winner, "接管后继续", ["production.output:line"], scope={"period_type": "year", "year": 2025})
            await conversation.save_shared_context(winner)
            with pytest.raises(details.DetailError) as stale_write:
                await conversation.save_shared_context(stale)
            assert stale_write.value.status_code == 409
            await conversation.release_shared_context(stale)
            bounded = await conversation.claim_shared_context(created.context_id, owner)
            for _ in range(10):
                conversation.touch_context(bounded, "问" * 1100, ["production.output:line"] * 6)
            await conversation.save_shared_context(bounded)
            persisted = await conn.fetchrow("SELECT scope,history,selected_ids FROM smart_bi_factory_analysis_conversations WHERE context_id=$1", created.context_id)
            import json
            saved_history = json.loads(persisted['history'])
            assert len(saved_history) == 8 and all(len(turn['question']) == 1000 for turn in saved_history)
            assert len(json.loads(persisted['selected_ids'])) == 4
            assert json.loads(persisted['scope']) == {"period_type": "year", "year": 2025}
            assert all(set(turn) == {'question', 'selectedIds'} for turn in saved_history)
            rebound = await conversation.claim_shared_context(created.context_id, owner)
            rebound.user_id = 1310
            with pytest.raises(details.DetailError) as actor_write:
                await conversation.save_shared_context(rebound)
            assert actor_write.value.status_code == 409
            rebound.user_id = 1309
            await conversation.release_shared_context(rebound)
            await conn.execute("UPDATE smart_bi_factory_analysis_conversations SET expires_at=CURRENT_TIMESTAMP - interval '1 second' WHERE context_id=$1", created.context_id)
            with pytest.raises(details.DetailError) as expired:
                await conversation.claim_shared_context(created.context_id, owner)
            assert expired.value.status_code == 410
            old_capacity = conversation_store.MAX_CONTEXTS
            conversation_store.MAX_CONTEXTS = 1
            try:
                async def create_one():
                    try:
                        return await conversation.create_shared_context(owner, "business", None, {})
                    except details.DetailError as exc:
                        return exc
                admission = await asyncio.gather(create_one(), create_one())
                assert sum(not isinstance(item, details.DetailError) for item in admission) == 1
                assert [item.status_code for item in admission if isinstance(item, details.DetailError)] == [503]
                other = await conversation.create_shared_context(("F007", 1309, "factory_super_admin"), "business", None, {})
                await conversation.release_shared_context(other)
                # The real runtime role can delete its own factory's transient
                # state, while FORCE RLS protects the other factory's row.
                assert await conn.fetchval("SELECT count(*) FROM smart_bi_factory_analysis_conversations WHERE factory_id='F007'") == 0
                await conn.execute("DELETE FROM smart_bi_factory_analysis_conversations WHERE factory_id='F007'")
                await conn.execute("SELECT set_config('app.factory_id','F007',false)")
                assert await conn.fetchval("SELECT count(*) FROM smart_bi_factory_analysis_conversations") == 1
                await conn.execute("DELETE FROM smart_bi_factory_analysis_conversations WHERE factory_id='F007'")
                assert await conn.fetchval("SELECT count(*) FROM smart_bi_factory_analysis_conversations") == 0
                await conn.execute("SELECT set_config('app.factory_id','F006',false)")
                assert await conn.fetchval("SELECT count(*) FROM smart_bi_factory_analysis_conversations") == 1
                await conn.execute("DELETE FROM smart_bi_factory_analysis_conversations WHERE factory_id='F006'")
            finally:
                conversation_store.MAX_CONTEXTS = old_capacity
        finally:
            conversation_store.set_store_for_tests(old)
            await pool.close()
    finally:
        await conn.close()


@pytest.mark.asyncio
async def test_postgres_two_process_worker_reconstruction_rls_and_leases():
    dsn = os.getenv("FACTORY_ANALYSIS_CONVERSATION_TEST_PG_URL")
    if not dsn:
        pytest.skip("set FACTORY_ANALYSIS_CONVERSATION_TEST_PG_URL for disposable PostgreSQL")
    target = urlparse(dsn)
    assert target.hostname in ("localhost", "127.0.0.1")
    assert target.port == 55439 or (target.port == 5432 and (target.path or "").lstrip("/").startswith("fadctx27_validation_"))
    asyncpg = pytest.importorskip("asyncpg")
    admin = await asyncpg.connect(dsn)
    schema = "factory_analysis_conversation_test_" + uuid.uuid4().hex[:12]
    role = "conversation_test_" + uuid.uuid4().hex[:12]
    migration = Path(__file__).parents[3] / "smartbi" / "database" / "migrations" / "V20261101_20__factory_analysis_conversations.sql"
    try:
        await admin.execute(f"CREATE SCHEMA {schema}")
        await admin.execute(f"CREATE ROLE {role} NOLOGIN")
        await admin.execute(f"SET search_path TO {schema},public")
        await admin.execute(migration.read_text(encoding="utf-8"))
        await admin.execute(f"GRANT USAGE ON SCHEMA {schema} TO {role}")
        await admin.execute(f"GRANT SELECT,INSERT,UPDATE,DELETE ON smart_bi_factory_analysis_conversations TO {role}")

        async def setup(conn):
            await conn.execute(f"SET ROLE {role}")
            await conn.execute(f"SET search_path TO {schema},public")

        pool = await asyncpg.create_pool(dsn, min_size=1, max_size=2, setup=setup,
                                         server_settings={"search_path": f"{schema},public"})
        async def get_pool():
            return pool
        old = conversation_store.set_store_for_tests(conversation_store.ConversationStore(get_pool))
        try:
            owner = ("F006", 1309, "factory_super_admin")
            created = await conversation.create_shared_context(owner, "business", None, {"period_type": "all"}, question="首轮")
            context_id = created.context_id
            conversation.touch_context(created, "首轮", [])
            await conversation.save_shared_context(created)
            script = r'''
import asyncio, os
from smartbi.services.factory_charts import conversation, conversation_store
async def main():
    import asyncpg
    async def setup(c):
        await c.execute("SET ROLE " + os.environ["CONV_ROLE"])
        await c.execute("SET search_path TO " + os.environ["CONV_SCHEMA"] + ",public")
    pool = await asyncpg.create_pool(os.environ["CONV_DSN"], min_size=1, max_size=1, setup=setup,
        server_settings={"search_path": os.environ["CONV_SCHEMA"] + ",public"})
    async def get_pool():
        return pool
    old = conversation_store.set_store_for_tests(conversation_store.ConversationStore(get_pool))
    try:
        ctx = await conversation.claim_shared_context(os.environ["CONV_ID"], ("F006", 1309, "factory_super_admin"))
        conversation.touch_context(ctx, "跨进程继续", ["production.output:line"], scope={"period_type":"year", "year":2025})
        await conversation.save_shared_context(ctx)
        print("WORKER_RECONSTRUCTION=PASS")
    finally:
        conversation_store.set_store_for_tests(old)
        await pool.close()
asyncio.run(main())
'''
            env = os.environ.copy()
            root = Path(__file__).parents[3]
            env.update(CONV_DSN=dsn, CONV_SCHEMA=schema, CONV_ROLE=role, CONV_ID=context_id,
                       PYTHONPATH=os.pathsep.join((str(root), str(root / "smartbi"))))
            result = subprocess.run([sys.executable, "-c", script], cwd=str(root.parent.parent),
                                    env=env, capture_output=True, text=True, timeout=45)
            assert result.returncode == 0 and "WORKER_RECONSTRUCTION=PASS" in result.stdout, result.stderr or result.stdout
            reloaded = await conversation.claim_shared_context(context_id, owner)
            assert reloaded.history[-1]["question"] == "跨进程继续"
            assert reloaded.scope["year"] == 2025
            await conversation.release_shared_context(reloaded)
            for bad_owner, status in [(("F006", 1310, "factory_super_admin"), 403),
                                      (("F006", 1309, "factory_viewer"), 403),
                                      (("F999", 1309, "factory_super_admin"), 410)]:
                with pytest.raises(details.DetailError) as denied:
                    await conversation.claim_shared_context(context_id, bad_owner)
                assert denied.value.status_code == status
            held = await conversation.claim_shared_context(context_id, owner)
            with pytest.raises(details.DetailError) as busy:
                await conversation.claim_shared_context(context_id, owner)
            assert busy.value.status_code == 409
            await conversation.release_shared_context(held)
            await admin.execute("UPDATE smart_bi_factory_analysis_conversations SET expires_at=CURRENT_TIMESTAMP - interval '1 second' WHERE context_id=$1", context_id)
            with pytest.raises(details.DetailError) as expired:
                await conversation.claim_shared_context(context_id, owner)
            assert expired.value.status_code == 410
            old_capacity = conversation_store.MAX_CONTEXTS
            conversation_store.MAX_CONTEXTS = 1
            try:
                async def create_one():
                    try:
                        return await conversation.create_shared_context(("F006", 1400, "factory_super_admin"), "business", None, {})
                    except details.DetailError as exc:
                        return exc
                capacity_results = await asyncio.gather(create_one(), create_one())
                assert sum(not isinstance(item, details.DetailError) for item in capacity_results) == 1
                other_factory = await conversation.create_shared_context(("F007", 1400, "factory_super_admin"), "business", None, {})
                await conversation.release_shared_context(other_factory)
            finally:
                conversation_store.MAX_CONTEXTS = old_capacity
        finally:
            conversation_store.set_store_for_tests(old)
            await pool.close()
    finally:
        await admin.execute(f"DROP SCHEMA {schema} CASCADE")
        await admin.execute(f"DROP ROLE {role}")
        await admin.close()


@pytest.mark.asyncio
async def test_unavailable_storage_is_503_and_never_memory_fallback():
    store = conversation_store.ConversationStore(lambda: None)
    with pytest.raises(details.DetailError) as exc:
        await store.create(("F006", 1309, "factory_super_admin"), "business", None, {}, conversation.ConversationContext)
    assert exc.value.status_code == 503
