from types import SimpleNamespace

import pytest

from . import cost_authorization as auth
from . import sources


def request(user=42, factory="F006", token="Bearer abc"):
    return SimpleNamespace(
        state=SimpleNamespace(user_id=user, factory_id=factory),
        headers={"authorization": token},
    )


def test_product_ids_are_bounded_and_duplicate_free():
    assert auth.validate_product_ids(["sku-a", "sku:b"]) == ["sku-a", "sku:b"]
    with pytest.raises(auth.CostAuthorizationError):
        auth.validate_product_ids(["sku-a", "sku-a"])
    with pytest.raises(auth.CostAuthorizationError):
        auth.validate_product_ids("sku-a")
    assert auth.validate_product_ids(["产品-甲"]) == ["产品-甲"]
    assert auth.validate_product_ids(["产品 α 01"]) == ["产品 α 01"]
    assert auth.row_product_ids({"产品编码（kg）": "P-1", "sku编码": "P-1"}) == ({"P-1"}, False)
    assert auth.row_product_ids({"产品编码": "P-1", "产品类型id": "P-2"})[1] is True
    assert auth.is_manufacturing_cost_column("总成本（元）")
    assert auth.is_manufacturing_cost_column("total_cost")
    assert not auth.is_manufacturing_cost_column("部门成本")
    internal = request(user="42")
    internal.state.auth_method = "internal"
    assert auth._state_identity(internal, "F006")[0] == 42


@pytest.mark.asyncio
async def test_authority_rejects_identity_and_out_of_scope_response(monkeypatch):
    class Response:
        status_code = 200
        def json(self):
            return {"success": True, "data": {"factoryId": "F006", "userId": 42,
                    "allowedProductIds": ["sku-secret"], "unscopedCostAllowed": False}}

    class Client:
        async def __aenter__(self): return self
        async def __aexit__(self, *args): pass
        async def post(self, *args, **kwargs): return Response()

    monkeypatch.setattr(auth, "java_base_url", lambda: "http://127.0.0.1:10010")
    monkeypatch.setattr(auth.httpx, "AsyncClient", lambda **kwargs: Client())
    with pytest.raises(auth.CostAuthorizationError, match="out-of-scope"):
        await auth.authorize(request(), "F006", ["sku-free"])


@pytest.mark.asyncio
async def test_authority_requires_current_identity_and_strict_user_type(monkeypatch):
    monkeypatch.setattr(auth, "java_base_url", lambda: "http://127.0.0.1:10010")
    with pytest.raises(auth.CostAuthorizationError, match="authenticated factory"):
        await auth.authorize(request(factory="F007"), "F006", [])

    class Response:
        status_code = 200
        def json(self):
            return {"success": True, "data": {"factoryId": "F006", "userId": 42.0,
                    "allowedProductIds": [], "unscopedCostAllowed": False}}
    class Client:
        async def __aenter__(self): return self
        async def __aexit__(self, *args): pass
        async def post(self, *args, **kwargs): return Response()
    monkeypatch.setattr(auth.httpx, "AsyncClient", lambda **kwargs: Client())
    with pytest.raises(auth.CostAuthorizationError, match="malformed|identity"):
        await auth.authorize(request(), "F006", [])


def test_cost_sql_is_second_phase_and_predicate_precedes_limit():
    assert "material_cost" not in sources.PRODUCTION_ID_SQL
    assert "labor_cost" not in sources.PROCESS_ID_SQL
    scoped = sources.PRODUCTION_SQL.replace(
        "\nORDER BY stat_date", "\n  AND product_type_id = ANY($4::text[])\nORDER BY stat_date")
    assert scoped.index("product_type_id = ANY") < scoped.index("ORDER BY") < scoped.index("LIMIT")


@pytest.mark.asyncio
async def test_real_internal_middleware_identity_reaches_authority_with_original_bearer(monkeypatch):
    from auth_middleware import JWTAuthMiddleware
    from fastapi import FastAPI, HTTPException, Request
    from httpx import ASGITransport, AsyncClient

    app = FastAPI()
    app.add_middleware(JWTAuthMiddleware, jwt_secret='fixture-jwt-secret')
    monkeypatch.setenv('INTERNAL_API_SECRET', 'fixture-internal-secret')
    seen = []

    @app.post('/api/smartbi/test-cost-scope')
    async def endpoint(http_request: Request):
        assert isinstance(http_request.state.user_id, str)
        try:
            result = await auth.authorize(http_request, 'F006', ['sku-a'])
        except auth.CostAuthorizationError:
            raise HTTPException(403, 'cost scope denied') from None
        return {'userId': result.user_id, 'allowed': sorted(result.allowed_product_ids)}

    browser = AsyncClient(transport=ASGITransport(app=app), base_url='http://test')
    class Response:
        status_code = 200
        def json(self):
            return {'success': True, 'data': {'factoryId': 'F006', 'userId': 42,
                    'allowedProductIds': ['sku-a'], 'unscopedCostAllowed': False}}
    class AuthorityClient:
        async def __aenter__(self): return self
        async def __aexit__(self, *_): return None
        async def post(self, url, **kwargs):
            seen.append((url, kwargs))
            return Response()
    monkeypatch.setattr(auth, 'java_base_url', lambda: 'http://127.0.0.1:10010')
    monkeypatch.setattr(auth.httpx, 'AsyncClient', lambda **_: AuthorityClient())
    headers = {'X-Internal-Secret': 'fixture-internal-secret', 'X-Factory-Id': 'F006',
               'X-User-Id': '42', 'X-User-Role': 'factory_super_admin',
               'Authorization': 'Bearer original-user-token'}
    async with browser:
        response = await browser.post('/api/smartbi/test-cost-scope', headers=headers)
        assert response.status_code == 200, response.text
        assert response.json() == {'userId': 42, 'allowed': ['sku-a']}
        assert len(seen) == 1
        assert seen[0][1]['headers'] == {'Authorization': 'Bearer original-user-token'}
        for invalid in ({'X-User-Id': 'true'}, {'X-Factory-Id': 'F007'}):
            denied = await browser.post('/api/smartbi/test-cost-scope', headers={**headers, **invalid})
            assert denied.status_code == 403
        assert len(seen) == 1, 'invalid internal identity must not reach the authority'
