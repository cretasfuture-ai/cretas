import asyncio
import time

import jwt
import pytest
from starlette.requests import Request

from auth_middleware import JWTAuthMiddleware
from smartbi_compat.auth import verify_jwt_and_factory
from platform_factory_view_policy import factory_view_signing_key, has_view_marker

SECRET = "cretas-test-secret-key-for-view-contract"


def token(**overrides):
    claims = {
        "userId": 7,
        "factoryId": "PLATFORM",
        "username": "admin",
        "sub": "admin",
        "role": "platform_admin",
        "actorType": "PLATFORM_ADMIN",
        "platformFactoryView": True,
        "viewFactoryId": "F001",
        "viewReadOnly": True,
        "iat": int(time.time()),
        "exp": int(time.time()) + 900,
    }
    claims.update(overrides)
    for key in tuple(claims):
        if claims[key] is None:
            claims.pop(key)
    return jwt.encode(claims, factory_view_signing_key(SECRET) if has_view_marker(claims) else SECRET, algorithm="HS256")


def run_middleware(path, method="GET", jwt_token=None, query=b"", enabled=True):
    calls = []

    async def app(scope, receive, send):
        calls.append(scope.get("state", {}).copy())
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await send({"type": "http.response.body", "body": b"ok"})

    middleware = JWTAuthMiddleware(app, SECRET, enabled=enabled)
    scope = {
        "type": "http", "method": method, "path": path,
        "query_string": query,
        "headers": [(b"authorization", f"Bearer {jwt_token}".encode())] if jwt_token else [],
        "state": {},
    }
    sent = []

    async def send(message):
        sent.append(message)

    asyncio.run(middleware(scope, lambda: None, send))
    status = next((m["status"] for m in sent if m["type"] == "http.response.start"), None)
    return status, calls, scope


def test_target_get_uses_view_factory_as_effective_python_tenant():
    status, calls, scope = run_middleware("/api/mobile/F001/reports/dashboard/overview", jwt_token=token())
    assert status == 200
    assert calls[0]["factory_id"] == "F001"
    assert calls[0]["user_id"] == 7
    assert calls[0]["role"] == "platform_admin"


@pytest.mark.parametrize("path,method,query", [
    ("/api/mobile/F002/reports/dashboard/overview", "GET", b""),
    ("/api/mobile/F001/reports/dashboard/overview", "GET", b"factoryId=F001&factoryId=F002"),
    ("/api/mobile/F001/dashboard", "POST", b""),
    ("/api/mobile/F001/scheduling/config", "GET", b""),
    ("/api/platform/factories", "GET", b""),
])
def test_view_scope_denies_cross_tenant_duplicate_query_writes_and_unsafe_routes(path, method, query):
    status, _, _ = run_middleware(path, method, token(), query)
    assert status == 403


@pytest.mark.parametrize("claims", [
    {"platformFactoryView": True},
    {"viewFactoryId": "F001", "viewReadOnly": True},
    {"platformFactoryView": True, "viewFactoryId": "F001", "viewReadOnly": False},
])
def test_partial_view_markers_fail_closed(claims):
    partial = {"userId": 7, "factoryId": "PLATFORM", "role": "platform_admin", "actorType": "PLATFORM_ADMIN", "iat": int(time.time()), "exp": int(time.time()) + 900}
    partial.update(claims)
    status, _, _ = run_middleware("/api/mobile/F001/reports/dashboard/overview", jwt_token=jwt.encode(partial, factory_view_signing_key(SECRET), algorithm="HS256"))
    assert status == (403 if claims.get("platformFactoryView") is True else 401)


def test_old_primary_decoder_cannot_upgrade_a_view_credential_on_rollback():
    with pytest.raises(jwt.InvalidSignatureError):
        jwt.decode(token(), SECRET, algorithms=["HS256"])


def test_dedicated_key_matches_the_java_derivation_vector():
    assert factory_view_signing_key("cretas-test-secret-key-for-view-session-contract").hex() == (
        "b714f3b52912024a0c13bc10a4041e022cd3c508c3fa448328f6d3f3f29ea055"
    )


def test_ordinary_token_behavior_is_unchanged_for_factory_get():
    ordinary = token(factoryId="F001", platformFactoryView=None, viewFactoryId=None, viewReadOnly=None)
    status, calls, _ = run_middleware("/api/mobile/F001/reports/dashboard/overview", jwt_token=ordinary)
    assert status == 200
    assert calls[0]["factory_id"] == "F001"


@pytest.mark.parametrize("name", ["factoryId", "factory_id", "viewFactoryId", "view_factory_id", "tenantId", "tenant_id"])
@pytest.mark.parametrize("values", ["F002", "F001&{name}=F002", ""])
def test_every_tenant_alias_and_duplicate_is_rejected(name, values):
    query = (name + "=" + values.format(name=name)).encode()
    status, calls, _ = run_middleware("/api/mobile/F001/reports/dashboard/overview", jwt_token=token(), query=query)
    assert status == 403
    assert not calls


@pytest.mark.parametrize("change", [
    {"exp": int(time.time()) + 3600}, {"actorType": "FACTORY_USER"},
    {"role": "developer"}, {"factoryId": "F001"}, {"viewFactoryId": "x" * 65},
])
def test_contradictory_signed_claims_and_lifetime_are_rejected(change):
    status, calls, _ = run_middleware("/api/mobile/F001/reports/dashboard/overview", jwt_token=token(**change))
    assert status == 403
    assert not calls


@pytest.mark.parametrize("enabled", [True, False])
@pytest.mark.parametrize("path", ["/api/chart/save", "/api/insight/generate", "/health"])
def test_view_tokens_cannot_use_public_or_disabled_auth_shortcuts(enabled, path):
    status, calls, _ = run_middleware(path, jwt_token=token(), enabled=enabled)
    assert status == 403
    assert not calls


def request_for(path, method="GET", token_value=None):
    headers = [(b"authorization", f"Bearer {token_value}".encode())] if token_value else []
    return Request({"type": "http", "method": method, "path": path, "headers": headers, "query_string": b""})


def test_smartbi_dependency_rejects_view_bi_routes_and_partial_claims(monkeypatch):
    monkeypatch.setenv("JWT_SECRET", SECRET)
    with pytest.raises(Exception):
        asyncio.run(verify_jwt_and_factory(request_for("/api/mobile/F001/smart-bi/overview", token_value=token()), "F001"))
    with pytest.raises(Exception):
        asyncio.run(verify_jwt_and_factory(request_for("/api/mobile/F001/smart-bi/write", "POST", token()), "F001"))
    with pytest.raises(Exception):
        asyncio.run(verify_jwt_and_factory(request_for("/api/mobile/F001/smart-bi/overview", token_value=token(viewReadOnly=False)), "F001"))
