import json
from types import SimpleNamespace
from contextlib import asynccontextmanager

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from smartbi.api import financial_dashboard as api
from smartbi.services.factory_charts import selection


def app_for(factory='F006', user=7, role='factory_super_admin'):
    app = FastAPI()
    @app.middleware('http')
    async def auth(req, call_next):
        req.state.factory_id, req.state.user_id, req.state.role = factory, user, role
        return await call_next(req)
    app.include_router(api.router)
    return app


@pytest.mark.asyncio
@pytest.mark.parametrize('factory,user,role,claimed,expected', [
    (None,7,'factory_super_admin',None,401), ('F006',None,'factory_super_admin',None,401),
    ('F006',7,'warehouse_worker',None,403), ('F006',7,'factory_super_admin','F001',403)])
async def test_selection_identity_and_role_before_pool(monkeypatch, factory,user,role,claimed,expected):
    async def forbidden(): raise AssertionError('identity must precede database access')
    monkeypatch.setattr(selection.config, 'get_pg_pool', forbidden)
    async with AsyncClient(transport=ASGITransport(app=app_for(factory,user,role)), base_url='http://test') as c:
        query = '&factory_id='+claimed if claimed else ''
        assert (await c.get('/factory-selection?source=business'+query)).status_code == expected
        assert (await c.put('/factory-selection',json={'source':'business','factory_id':claimed,'selected_ids':[]})).status_code == expected


@pytest.mark.asyncio
async def test_selection_empty_is_explicit_unset_distinct_and_all_scope_bound(monkeypatch):
    calls=[]
    class Connection:
        def transaction(self): return context(self)
        async def execute(self,*args): calls.append(args)
        async def fetchrow(self,sql,*args):
            calls.append((sql,*args))
            assert args[:3] == ('F006',7,'upload')
            if len(args)==4:
                assert json.loads(args[3])==[]
                return {'selected_ids':args[3], 'updated_at':None}
            return None
    @asynccontextmanager
    async def context(value): yield value
    async def pool(): return SimpleNamespace(acquire=lambda: context(Connection()))
    monkeypatch.setattr(selection.config,'get_pg_pool',pool)
    async with AsyncClient(transport=ASGITransport(app=app_for()),base_url='http://test') as c:
        unset = await c.get('/factory-selection?source=upload')
        assert unset.status_code==200 and unset.json()['data']['selectedIds'] is None
        saved = await c.put('/factory-selection',json={'source':'upload','selected_ids':[]})
        assert saved.status_code==200 and saved.json()['data']['selectedIds']==[]
        assert saved.json()['data']['factoryId']=='F006'
    assert all('factory_id=$1 AND user_id=$2 AND source=$3' in call[0]
               for call in calls if call[0].startswith('\nSELECT selected_ids'))


@pytest.mark.asyncio
@pytest.mark.parametrize('ids', [['x','x'],[''],['a'*301],['a']*101])
async def test_selection_rejects_invalid_ids(monkeypatch,ids):
    async def forbidden(): raise AssertionError('invalid payload must not access database')
    monkeypatch.setattr(selection.config,'get_pg_pool',forbidden)
    async with AsyncClient(transport=ASGITransport(app=app_for()),base_url='http://test') as c:
        assert (await c.put('/factory-selection',json={'source':'business','selected_ids':ids})).status_code==422


@pytest.mark.asyncio
async def test_selection_failure_visible_without_connection_details(monkeypatch):
    async def broken(): raise RuntimeError('postgres://secret/host/password')
    monkeypatch.setattr(selection.config,'get_pg_pool',broken)
    async with AsyncClient(transport=ASGITransport(app=app_for()),base_url='http://test') as c:
        r=await c.get('/factory-selection?source=business')
    assert r.status_code==503 and '同步失败' in r.text
    assert 'secret' not in r.text and 'password' not in r.text
