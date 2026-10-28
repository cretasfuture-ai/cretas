"""Opt-in integration proof of the real 256-context factory boundary."""
import asyncio
import os
import uuid
from urllib.parse import urlparse

import pandas as pd
import pytest

from . import detail_store
from .test_detail_store_postgres import MIGRATION


@pytest.mark.asyncio
async def test_actual_256_concurrent_admission_reuse_owner_and_expiry(monkeypatch):
    dsn = os.getenv('DETAIL_STORE_TEST_PG_URL')
    if not dsn:
        pytest.skip('Owned disposable PostgreSQL fixture is required')
    target = urlparse(dsn)
    assert target.hostname in ('127.0.0.1', 'localhost') and target.port == 55439
    assert detail_store.MAX_CONTEXTS == 256
    assert detail_store.CONTEXT_TTL_SECONDS == 300
    asyncpg = pytest.importorskip('asyncpg')
    schema = 'factory_capacity_256_' + uuid.uuid4().hex
    admin = await asyncpg.connect(dsn)
    pool = None
    try:
        await admin.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='smartbi_user') THEN CREATE ROLE smartbi_user NOLOGIN; END IF; END $$")
        await admin.execute(f'CREATE SCHEMA {schema}')
        await admin.execute(f'SET search_path TO {schema},public')
        await admin.execute(MIGRATION.read_text(encoding='utf-8'))
        await admin.execute(f'GRANT USAGE ON SCHEMA {schema} TO smartbi_user')
        assert not await admin.fetchval("SELECT has_table_privilege('smartbi_user',$1,'UPDATE')", schema + '.smart_bi_factory_chart_detail_contexts')

        async def setup(connection):
            await connection.execute('SET ROLE smartbi_user')
            await connection.execute(f'SET search_path TO {schema},public')

        pool = await asyncpg.create_pool(dsn, min_size=1, max_size=4, setup=setup,
                                        server_settings={'search_path': f'{schema},public'})
        async def get_pool():
            return pool
        monkeypatch.setattr(detail_store.config, 'get_pg_pool', get_pool)
        source = 'production · owned capacity fixture'
        dataset = {'source': source, 'frame': pd.DataFrame([{'日期': '2026-10-28', '批次': 'QA-CAP-1', '单位': 'kg', '产量': 1}])}
        candidates = [{'id': f'capacity-{index}', 'title': '验收产量', 'source': source, 'unit': 'kg',
                       'chart': {'chartType': 'bar', 'echartsOption': {}}} for index in range(300)]
        owner = ('F006', 1309, 'factory_super_admin')
        request = {'period_type': 'all'}

        async def register(items, actor=owner):
            return await detail_store.register_shared(items, [dataset], request, actor, False)

        batches = await asyncio.gather(*(register(candidates[start:start + 100]) for start in (0, 100, 200)))
        admitted = [item for batch in batches for item in batch if item.get('detailContext')]
        refused = [item for batch in batches for item in batch if not item.get('detailContext')]
        assert len(admitted) == 256 and len(refused) == 44
        assert all(item['detailStatus']['state'] == 'capacity' and item['detailSnapshotKey'] for item in refused)
        assert len({item['detailContext']['id'] for item in admitted}) == 256
        assert await admin.fetchval("SELECT count(*) FROM smart_bi_factory_chart_detail_contexts WHERE factory_id='F006' AND expires_at>CURRENT_TIMESTAMP") == 256

        original = {item['id']: item for item in admitted}
        reused = await register(admitted)
        assert all(item['detailContext']['id'] == original[item['id']]['detailContext']['id'] for item in reused)
        assert await admin.fetchval("SELECT count(*) FROM smart_bi_factory_chart_detail_contexts WHERE factory_id='F006'") == 256
        other_actor = (await register([admitted[0]], ('F006', 1310, 'factory_super_admin')))[0]
        assert not other_actor.get('detailContext') and other_actor['detailStatus']['state'] == 'capacity'
        assert other_actor['detailSnapshotKey'] != admitted[0]['detailSnapshotKey']
        other_factory = (await register([candidates[0]], ('F007', 1309, 'factory_super_admin')))[0]
        assert other_factory['detailContext']['id'] not in {item['detailContext']['id'] for item in admitted}

        # Only the fixture administrator simulates elapsed time. The runtime role
        # still has no UPDATE privilege, and no deployed limit/TTL is lowered.
        await admin.execute("UPDATE smart_bi_factory_chart_detail_contexts SET expires_at=CURRENT_TIMESTAMP-interval '1 second' WHERE factory_id='F006'")
        restored = (await register([candidates[0]]))[0]
        assert restored['detailContext']['id'] not in {item['detailContext']['id'] for item in admitted}
        assert await admin.fetchval("SELECT count(*) FROM smart_bi_factory_chart_detail_contexts WHERE factory_id='F006'") == 1
        assert await admin.fetchval("SELECT count(*) FROM smart_bi_factory_chart_detail_contexts WHERE factory_id='F007'") == 1
    finally:
        if pool is not None:
            await pool.close()
        await admin.execute(f'DROP SCHEMA IF EXISTS {schema} CASCADE')
        await admin.close()
