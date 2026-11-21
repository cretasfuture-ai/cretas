"""analysis-cache tenant gate (2026-10-22).

These routes used to be in PUBLIC_PREFIXES and filtered only by upload_id: on prod an
unauthenticated GET reached the handler (200) while other SmartBI paths answered 401, so
anyone could read, overwrite or delete any factory's cached charts / KPIs / AI analysis
by counting upload ids. The handlers now require a login and an upload owned by the
caller's factory. Real SQL against SQLite so the ownership filter itself is exercised.
"""
from __future__ import annotations

import asyncio
import socket
from contextlib import contextmanager

import pytest
from fastapi import FastAPI, HTTPException
from httpx import ASGITransport, AsyncClient
from redis.exceptions import ConnectionError as RedisConnectionError
from sqlalchemy import create_engine
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.ext.compiler import compiles
from sqlalchemy.orm import sessionmaker
from starlette.requests import Request

import auth_middleware
from common import smartbi_analysis_cache as storage
from common.smartbi_analysis_cache import analysis_result, invalidate, request_scope, result_key
from smartbi.api import analysis_cache, chart_insight
from smartbi.database.models import Base, SmartBiPgAnalysisResult, SmartBiPgExcelUpload
from tests.analysis_cache_fixtures import MemoryCache, analysis_cache as shared_cache  # noqa: F401
from tests.test_chart_insight_answer_cache import SECRET, headers


@compiles(JSONB, "sqlite")
def _jsonb_as_json(_type, _compiler, **_kw):
    return "JSON"


OWN, OTHER = "F_OWN", "F_OTHER"
OWN_UPLOAD, OTHER_UPLOAD, MISSING_UPLOAD = 11, 22, 99


def _request(factory_id):
    return Request({
        "type": "http", "method": "GET", "path": "/api/smartbi/analysis-cache/1",
        "query_string": b"", "headers": [],
        "state": {} if factory_id is None else {"factory_id": factory_id},
    })


@pytest.fixture()
def db(monkeypatch):
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine, tables=[SmartBiPgExcelUpload.__table__, SmartBiPgAnalysisResult.__table__])
    Session = sessionmaker(bind=engine)
    with Session() as s:
        for upload_id, factory in ((OWN_UPLOAD, OWN), (OTHER_UPLOAD, OTHER)):
            s.add(SmartBiPgExcelUpload(id=upload_id, factory_id=factory, file_name=f"{factory}.xlsx"))
            s.add(SmartBiPgAnalysisResult(id=upload_id, upload_id=upload_id, factory_id=factory,
                                          analysis_type="enrichment_cache",
                                          analysis_result={"aiAnalysis": f"secret of {factory}", "charts": [{"config": {}}]}))
        s.commit()

    @contextmanager
    def ctx():
        s = Session()
        try:
            yield s
            s.commit()
        finally:
            s.close()

    monkeypatch.setattr(analysis_cache, "get_db_context", ctx)
    monkeypatch.setattr(analysis_cache, "is_postgres_enabled", lambda: True)
    return Session


def _status(coro):
    with pytest.raises(HTTPException) as err:
        asyncio.run(coro)
    return err.value.status_code


def _cached_text(Session, upload_id):
    with Session() as s:
        row = s.query(SmartBiPgAnalysisResult).filter_by(upload_id=upload_id).first()
        return None if row is None else row.analysis_result.get("aiAnalysis")


def test_route_is_no_longer_public():
    path = "/api/smartbi/analysis-cache/22"
    assert not any(path.startswith(p) for p in auth_middleware.PUBLIC_PREFIXES)


def test_own_factory_reads_its_charts_from_legacy_projection(db):
    body = asyncio.run(analysis_cache.get_analysis_cache(OWN_UPLOAD, _request(OWN)))
    assert body["cached"] is True and body["charts"]
    assert body["aiAnalysis"] is None and body["_partial"]


@pytest.mark.asyncio
async def test_saved_ai_text_requires_current_role_scoped_revision(db, shared_cache):
    owner = _request(OWN)
    owner.state.auth_method = "jwt"
    owner.state.role = "factory_super_admin"
    peer = _request(OWN)
    peer.state.auth_method = "jwt"
    peer.state.role = "warehouse_worker"
    scope = request_scope(owner)
    key = result_key(scope, "workbook", [OWN_UPLOAD], prompt_version="v1", model_version="v1")
    async with analysis_result(key) as cached:
        await cached.store({"answer": "成本结论"})
        token = cached.response_fields["cacheToken"]

    await analysis_cache.save_analysis_cache(OWN_UPLOAD, analysis_cache.SaveCacheRequest(
        aiAnalysis="成本结论", aiCacheToken=token, structuredAI={"summary": "成本结论"}, charts=[{"config": {}}],
    ), owner)
    assert (await analysis_cache.get_analysis_cache(OWN_UPLOAD, owner))["aiAnalysis"] == "成本结论"
    peer_view = await analysis_cache.get_analysis_cache(OWN_UPLOAD, peer)
    assert peer_view["aiAnalysis"] is None and peer_view["structuredAI"] is None and peer_view["_partial"]
    assert await invalidate(scope, token)
    revoked = await analysis_cache.get_analysis_cache(OWN_UPLOAD, owner)
    assert revoked["aiAnalysis"] is None and revoked["aiCacheToken"] is None and revoked["charts"]


PROJECTION = f"/api/smartbi/analysis-cache/{OWN_UPLOAD}"
FEEDBACK = "/api/smartbi/analysis-feedback"
ADMIN = "factory_super_admin"
OPERATOR = "warehouse_worker"


@pytest.fixture
async def projection_client(db):
    app = FastAPI()
    app.add_middleware(auth_middleware.JWTAuthMiddleware, jwt_secret=SECRET)
    app.include_router(analysis_cache.router, prefix="/api/smartbi")
    app.include_router(chart_insight.router, prefix="/api/smartbi")
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://fixture") as client:
        yield client


def projection_auth(role=ADMIN, factory=OWN):
    return headers(factory=factory, role=role)


async def test_signed_same_factory_role_never_reads_unscoped_legacy_ai(projection_client):
    for role in (ADMIN, OPERATOR):
        response = await projection_client.get(PROJECTION, headers=projection_auth(role))
        assert response.status_code == 200
        body = response.json()
        assert body["charts"] and body["_partial"] and body["aiAnalysis"] is None and body["structuredAI"] is None


async def put_projection(client, role, text):
    scope = storage.AnalysisScope(OWN, role)
    key = result_key(scope, "workbook", [OWN_UPLOAD, text], prompt_version="v1", model_version="v1")
    async with analysis_result(key) as cached:
        await cached.store({"answer": text})
        token = cached.response_fields["cacheToken"]
    response = await client.post(PROJECTION, headers=projection_auth(role), json={
        "aiAnalysis": text, "structuredAI": {"summary": text}, "aiCacheToken": token,
        "charts": [{"config": {}}],
    })
    assert response.status_code == 200 and response.json()["success"]
    return key, token


def redis_unavailable(cache, monkeypatch):
    if isinstance(cache, MemoryCache):
        def unavailable():
            raise RedisConnectionError("fixture connection refused")
        monkeypatch.setattr(storage, "get_cache", unavailable)
    else:
        with socket.socket() as lease:
            lease.bind(("127.0.0.1", 0))
            port = lease.getsockname()[1]
        monkeypatch.setenv("SMARTBI_ANALYSIS_REDIS_URL", f"redis://127.0.0.1:{port}/0")


async def test_projection_read_preserves_saved_ai_during_redis_outage(projection_client, shared_cache, monkeypatch):
    _, token = await put_projection(projection_client, ADMIN, "成本仍可见")
    redis_unavailable(shared_cache, monkeypatch)
    response = await projection_client.get(PROJECTION, headers=projection_auth())
    assert response.status_code == 200
    result = response.json()
    assert result["aiAnalysis"] == "成本仍可见" and result["structuredAI"]["summary"] == "成本仍可见"
    assert result["aiCacheToken"] == token and not result["_partial"]


@pytest.mark.timeout(180)
async def test_projection_survives_2049_lua_writes_and_can_still_be_revoked(projection_client, shared_cache, monkeypatch):
    if isinstance(shared_cache, MemoryCache):
        pytest.skip("requires a private Redis for capacity eviction evidence")
    key, token = await put_projection(projection_client, ADMIN, "最早保留的分析")
    cache = storage.get_cache()
    try:
        for i in range(2048):
            next_key = result_key(key.scope, "capacity-probe", i, prompt_version="v1", model_version="v1")
            entry = await cache.acquire(next_key)
            assert await cache.complete(entry, {"answer": i})
        assert await cache.redis.hlen(storage._VALUES) == 2048
        assert await cache.redis.hget(storage._VALUES, key.field) is None
    finally:
        await cache.redis.aclose()
    response = await projection_client.get(PROJECTION, headers=projection_auth())
    assert response.json()["aiAnalysis"] == "最早保留的分析" and not response.json()["_partial"]
    vote = await projection_client.post(FEEDBACK, headers=projection_auth(), json={
        "cache_token": token, "upload_id": OWN_UPLOAD,
    })
    assert vote.status_code == 200, vote.text
    redis_unavailable(shared_cache, monkeypatch)
    revised = (await projection_client.get(PROJECTION, headers=projection_auth())).json()
    assert revised["aiAnalysis"] is None and revised["_partial"] and revised["charts"]


async def test_role_projections_keep_their_own_content_and_only_explicit_vote_hides_it(projection_client, shared_cache):
    _, admin_token = await put_projection(projection_client, ADMIN, "管理员成本结论")
    _, operator_token = await put_projection(projection_client, OPERATOR, "操作员产量结论")
    for role, token, text in ((ADMIN, admin_token, "管理员成本结论"),
                              (OPERATOR, operator_token, "操作员产量结论")) * 2:
        response = await projection_client.get(PROJECTION, headers=projection_auth(role))
        assert response.status_code == 200
        result = response.json()
        assert result["aiAnalysis"] == text and result["aiCacheToken"] == token and not result["_partial"]
    body = {"cache_token": admin_token, "upload_id": OWN_UPLOAD}
    denied = await projection_client.post(FEEDBACK, headers=projection_auth(OPERATOR), json=body)
    assert denied.status_code == 404
    authorized = await projection_client.post(FEEDBACK, headers=projection_auth(), json=body)
    assert authorized.status_code == 200
    admin = (await projection_client.get(PROJECTION, headers=projection_auth())).json()
    operator = (await projection_client.get(PROJECTION, headers=projection_auth(OPERATOR))).json()
    assert admin["aiAnalysis"] is None and admin["_partial"]
    assert operator["aiAnalysis"] == "操作员产量结论" and not operator["_partial"]


async def test_scoped_projection_uses_safe_rollback_marker_and_survives_kpi_precompute(
    projection_client, shared_cache, db, monkeypatch,
):
    from smartbi import config
    from smartbi.api import insight

    _, token = await put_projection(projection_client, ADMIN, "保留 AI 内容")
    with db() as session:
        row = session.query(SmartBiPgAnalysisResult).filter_by(upload_id=OWN_UPLOAD).one()
        assert row.analysis_result["_partial"] is True
        assert not {"aiAnalysis", "aiCacheToken", "structuredAI"}.intersection(row.analysis_result)

    async def data(_upload):
        return [{"名称": "样本"}]

    async def strategies(_upload):
        return {}

    async def no_pool():
        return None

    monkeypatch.setattr(insight, "_load_upload_data", data)
    monkeypatch.setattr(insight, "_load_agg_strategy_map", strategies)
    monkeypatch.setattr(config, "get_pg_pool", no_pool)
    assert (await analysis_cache.precompute_enrichment_cache_for_upload(OWN_UPLOAD, OWN))["success"]
    response = await projection_client.get(PROJECTION, headers=projection_auth())
    result = response.json()
    assert result["aiAnalysis"] == "保留 AI 内容" and result["aiCacheToken"] == token
    assert result["kpiSummary"]["rowCount"] == 1 and not result["_partial"]


@pytest.mark.parametrize(("factory", "upload_id", "status"), [
    (None, OTHER_UPLOAD, 401),          # no login
    (OWN, OTHER_UPLOAD, 404),           # another factory's upload looks like a missing one
    (OWN, MISSING_UPLOAD, 404),
])
def test_read_is_gated(db, factory, upload_id, status):
    assert _status(analysis_cache.get_analysis_cache(upload_id, _request(factory))) == status


def test_cross_factory_write_and_delete_change_nothing(db):
    body = analysis_cache.SaveCacheRequest(aiAnalysis="overwritten")
    assert _status(analysis_cache.save_analysis_cache(OTHER_UPLOAD, body, _request(OWN))) == 404
    assert _status(analysis_cache.save_analysis_cache(OTHER_UPLOAD, body, _request(None))) == 401
    assert _status(analysis_cache.delete_analysis_cache(OTHER_UPLOAD, _request(OWN))) == 404
    assert _status(analysis_cache.delete_analysis_cache(OTHER_UPLOAD, _request(None))) == 401
    assert _cached_text(db, OTHER_UPLOAD) == f"secret of {OTHER}"   # untouched


def test_body_factory_cannot_override_the_login(db):
    spoof = analysis_cache.SaveCacheRequest(factory_id=OTHER, aiAnalysis="x")
    assert _status(analysis_cache.save_analysis_cache(OWN_UPLOAD, spoof, _request(OWN))) == 403


def test_own_factory_write_and_delete_still_work(db):
    request = _request(OWN)
    request.state.auth_method = "jwt"
    request.state.role = ADMIN
    body = analysis_cache.SaveCacheRequest(aiAnalysis="fresh")   # current web-admin may omit factory_id
    assert asyncio.run(analysis_cache.save_analysis_cache(OWN_UPLOAD, body, request)) == {"success": True}
    assert asyncio.run(analysis_cache.get_analysis_cache(OWN_UPLOAD, request))["aiAnalysis"] == "fresh"
    assert asyncio.run(analysis_cache.delete_analysis_cache(OWN_UPLOAD, request))["deleted"] == 1
    assert _cached_text(db, OWN_UPLOAD) is None
