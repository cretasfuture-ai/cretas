from types import SimpleNamespace
import pytest
import httpx

_REAL_ASYNC_CLIENT = httpx.AsyncClient
from . import finance_auth


@pytest.mark.asyncio
@pytest.mark.parametrize('case,expected',[
    ('full',True),('denied',False),('restricted',False),('missing-scope-proof',False),
    ('wrong-factory',False),('failure',False),('anonymous',False)])
async def test_finance_permission_and_full_row_scope_are_proven(monkeypatch,case,expected):
    monkeypatch.setenv('JAVA_API_BASE_URL','http://127.0.0.1:10010')
    calls=[]
    class Client:
        def __init__(self,**kw):
            assert kw['follow_redirects'] is False
        async def __aenter__(self): return self
        async def __aexit__(self,*args): return False
        async def get(self,url,**kw):
            calls.append(url)
            assert url.startswith('http://127.0.0.1:10010/api/mobile/F006/finance/report/')
            if case=='failure': raise RuntimeError('unavailable')
            data={'factoryId':'F001' if case=='wrong-factory' else 'F006','scopeApplied':case=='restricted'}
            if case=='missing-scope-proof': data.pop('scopeApplied')
            return SimpleNamespace(status_code=403 if case=='denied' else 200,
                headers={'X-Finance-Row-Scope':'RESTRICTED'} if case=='restricted' else {},
                json=lambda:{'success':True,'data':data})
    monkeypatch.setattr(finance_auth.httpx,'AsyncClient',Client)
    request=SimpleNamespace(headers={} if case=='anonymous' else {'authorization':'Bearer fixture-token'})
    assert await finance_auth.full_factory_finance_access(request,'F006') is expected
    assert len(calls)==(0 if case=='anonymous' else 1)


@pytest.mark.asyncio
async def test_finance_auth_does_not_allow_client_factory_url_injection():
    req=SimpleNamespace(headers={'authorization':'Bearer fixture-token'})
    assert await finance_auth.full_factory_finance_access(req,'F006/../../F001') is False


def test_unconfigured_and_nonproduction_java_target_never_defaults_to_production(monkeypatch):
    monkeypatch.delenv('JAVA_API_BASE_URL',raising=False)
    monkeypatch.delenv('BACKEND_BASE_URL',raising=False)
    assert finance_auth.java_base_url()=='http://127.0.0.1:10010'
    monkeypatch.setenv('JAVA_API_BASE_URL','http://localhost:11010')
    assert finance_auth.java_base_url()=='http://localhost:11010'
    monkeypatch.setenv('JAVA_API_BASE_URL','http://untrusted.invalid')
    assert finance_auth.java_base_url() is None


@pytest.mark.asyncio
async def test_unconfigured_default_tries_blue_green_only_after_connection_refusal(monkeypatch):
    monkeypatch.delenv('JAVA_API_BASE_URL', raising=False)
    monkeypatch.delenv('BACKEND_BASE_URL', raising=False)
    calls = []

    def handler(request):
        calls.append((str(request.url), request.headers.get('authorization')))
        if request.url.port == 10010:
            raise httpx.ConnectError('refused', request=request)
        return httpx.Response(200, json={'success': True, 'data': {'factoryId': 'F006', 'scopeApplied': False}})

    transport = httpx.MockTransport(handler)

    class Client:
        def __init__(self, **kwargs):
            self.client = _REAL_ASYNC_CLIENT(transport=transport, **kwargs)
        async def __aenter__(self):
            await self.client.__aenter__()
            return self
        async def __aexit__(self, *args):
            return await self.client.__aexit__(*args)
        async def get(self, *args, **kwargs):
            return await self.client.get(*args, **kwargs)

    monkeypatch.setattr(finance_auth.httpx, 'AsyncClient', Client)
    request = SimpleNamespace(headers={'authorization': 'Bearer fixture-token'})
    assert await finance_auth.full_factory_finance_access(request, 'F006') is True
    assert [url.split('/api/', 1)[0] for url, _ in calls] == [
        'http://127.0.0.1:10010', 'http://127.0.0.1:10020']
    assert all(auth == 'Bearer fixture-token' for _, auth in calls)


@pytest.mark.asyncio
@pytest.mark.parametrize('status,body,headers', [
    (401, {'success': True, 'data': {'factoryId': 'F006', 'scopeApplied': False}}, {}),
    (403, {'success': True, 'data': {'factoryId': 'F006', 'scopeApplied': False}}, {}),
    (200, 'malformed-json', {}),
    (200, 'read-timeout', {}),
    (200, {'success': True, 'data': {'factoryId': 'OTHER', 'scopeApplied': False}}, {}),
    (200, {'success': True, 'data': {'factoryId': 'F006', 'scopeApplied': True}}, {}),
    (200, {'success': True, 'data': {'factoryId': 'F006', 'scopeApplied': False}}, {'X-Finance-Row-Scope': 'restricted'}),
])
async def test_default_denial_or_scope_mismatch_stops_without_trying_other_target(monkeypatch, status, body, headers):
    monkeypatch.delenv('JAVA_API_BASE_URL', raising=False)
    monkeypatch.delenv('BACKEND_BASE_URL', raising=False)
    calls = []

    def handler(request):
        calls.append(str(request.url))
        if body == 'malformed-json':
            return httpx.Response(status, content=b'{broken')
        if body == 'read-timeout':
            raise httpx.ReadTimeout('bounded timeout', request=request)
        return httpx.Response(status, json=body, headers=headers)

    transport = httpx.MockTransport(handler)

    class Client:
        def __init__(self, **kwargs): self.client = _REAL_ASYNC_CLIENT(transport=transport, **kwargs)
        async def __aenter__(self): await self.client.__aenter__(); return self
        async def __aexit__(self, *args): return await self.client.__aexit__(*args)
        async def get(self, *args, **kwargs): return await self.client.get(*args, **kwargs)

    monkeypatch.setattr(finance_auth.httpx, 'AsyncClient', Client)
    request = SimpleNamespace(headers={'authorization': 'Bearer fixture-token'})
    assert await finance_auth.full_factory_finance_access(request, 'F006') is False
    assert len(calls) == 1


@pytest.mark.asyncio
async def test_explicit_target_never_falls_back_on_connection_refusal(monkeypatch):
    monkeypatch.setenv('JAVA_API_BASE_URL', 'http://127.0.0.1:10010')
    calls = []

    def handler(request):
        calls.append(str(request.url))
        raise httpx.ConnectError('refused', request=request)

    transport = httpx.MockTransport(handler)

    class Client:
        def __init__(self, **kwargs): self.client = _REAL_ASYNC_CLIENT(transport=transport, **kwargs)
        async def __aenter__(self): await self.client.__aenter__(); return self
        async def __aexit__(self, *args): return await self.client.__aexit__(*args)
        async def get(self, *args, **kwargs): return await self.client.get(*args, **kwargs)

    monkeypatch.setattr(finance_auth.httpx, 'AsyncClient', Client)
    request = SimpleNamespace(headers={'authorization': 'Bearer fixture-token'})
    assert await finance_auth.full_factory_finance_access(request, 'F006') is False
    assert len(calls) == 1
