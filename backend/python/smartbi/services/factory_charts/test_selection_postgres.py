"""Real PostgreSQL migration/upsert/readback. Opt in with a disposable local DB only."""
import os
import uuid
from pathlib import Path
from urllib.parse import urlparse

import pytest
from httpx import ASGITransport, AsyncClient
from .test_selection import app_for
from . import selection


@pytest.mark.asyncio
async def test_real_postgres_migration_and_factory_user_source_isolation(monkeypatch):
    url=os.environ.get('FACTORY_SELECTION_TEST_PG_URL')
    ci_url=os.environ.get('SMARTBI_PG_DSN') if os.environ.get('CRETAS_WORKFLOW_PG_VERIFY')=='true' else None
    url=url or ci_url
    if not url: pytest.skip('disposable local PostgreSQL not supplied')
    target=urlparse(url)
    assert target.hostname in ('localhost','127.0.0.1'), 'dedicated local test cluster only'
    assert target.port==55439 or (url==ci_url and target.username=='cretas_ci' and target.path=='/cretas_workflow_verify_test'), 'owned disposable cluster only'
    import asyncpg
    conn=await asyncpg.connect(url)
    await conn.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='smartbi_user') THEN CREATE ROLE smartbi_user NOLOGIN; END IF; END $$")
    schema='factory_selection_'+uuid.uuid4().hex
    await conn.execute(f'CREATE SCHEMA {schema}')
    await conn.execute(f'SET search_path TO {schema}')
    migration=Path(__file__).resolve().parents[2]/'database/migrations/V20261025_01__factory_chart_selection.sql'
    await conn.execute(migration.read_text(encoding='utf-8'))
    await conn.execute(migration.read_text(encoding='utf-8'))
    await conn.execute(f'GRANT USAGE ON SCHEMA {schema} TO smartbi_user')
    async def app_role(connection):
        await connection.execute('SET ROLE smartbi_user')
    pool=await asyncpg.create_pool(url,min_size=1,max_size=2,server_settings={'search_path':schema},setup=app_role)
    async def get_pool(): return pool
    monkeypatch.setattr(selection.config,'get_pg_pool',get_pool)
    try:
        async def read(factory='F006',user=7,source='business'):
            async with AsyncClient(transport=ASGITransport(app=app_for(factory,user)),base_url='http://test') as c:
                r=await c.get('/factory-selection?source='+source)
                assert r.status_code==200
                return r.json()['data']['selectedIds']
        async with AsyncClient(transport=ASGITransport(app=app_for()),base_url='http://test') as c:
            r=await c.put('/factory-selection',json={'source':'business','selected_ids':['production.output:line']})
            assert r.status_code==200 and r.json()['data']['updatedAt']
            assert await read()==['production.output:line']
            assert await read('F001') is None
            assert await read(user=8) is None
            assert await read(source='upload') is None
            assert (await c.put('/factory-selection',json={'source':'business','selected_ids':[]})).status_code==200
            assert await read()==[]
        assert await conn.fetchval('SELECT count(*) FROM smart_bi_factory_chart_selections')==1
        async with pool.acquire() as scoped:
            async with scoped.transaction():
                await scoped.execute("SELECT set_config('app.factory_id','F001',true)")
                assert await scoped.fetchval('SELECT count(*) FROM smart_bi_factory_chart_selections')==0
                with pytest.raises(asyncpg.InsufficientPrivilegeError):
                    await scoped.execute("INSERT INTO smart_bi_factory_chart_selections VALUES ('F006',9,'business','[]',CURRENT_TIMESTAMP)")
        with pytest.raises(asyncpg.CheckViolationError):
            await conn.execute("INSERT INTO smart_bi_factory_chart_selections VALUES ('F006',9,'business','{}',CURRENT_TIMESTAMP)")
    finally:
        await pool.close()
        await conn.execute(f'DROP SCHEMA {schema} CASCADE')
        await conn.close()
