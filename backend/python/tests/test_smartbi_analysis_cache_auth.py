"""analysis-cache tenant gate (2026-10-22).

These routes used to be in PUBLIC_PREFIXES and filtered only by upload_id: on prod an
unauthenticated GET reached the handler (200) while other SmartBI paths answered 401, so
anyone could read, overwrite or delete any factory's cached charts / KPIs / AI analysis
by counting upload ids. The handlers now require a login and an upload owned by the
caller's factory. Real SQL against SQLite so the ownership filter itself is exercised.
"""
from __future__ import annotations

import asyncio
from contextlib import contextmanager

import pytest
from fastapi import HTTPException
from sqlalchemy import create_engine
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.ext.compiler import compiles
from sqlalchemy.orm import sessionmaker
from starlette.requests import Request

import auth_middleware
from smartbi.api import analysis_cache
from smartbi.database.models import Base, SmartBiPgAnalysisResult, SmartBiPgExcelUpload


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
                                          analysis_result={"aiAnalysis": f"secret of {factory}"}))
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


def test_own_factory_reads_its_cache(db):
    body = asyncio.run(analysis_cache.get_analysis_cache(OWN_UPLOAD, _request(OWN)))
    assert body["cached"] is True and body["aiAnalysis"] == f"secret of {OWN}"


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
    body = analysis_cache.SaveCacheRequest(aiAnalysis="fresh")   # current web-admin may omit factory_id
    assert asyncio.run(analysis_cache.save_analysis_cache(OWN_UPLOAD, body, _request(OWN))) == {"success": True}
    assert _cached_text(db, OWN_UPLOAD) == "fresh"
    assert asyncio.run(analysis_cache.delete_analysis_cache(OWN_UPLOAD, _request(OWN)))["deleted"] == 1
    assert _cached_text(db, OWN_UPLOAD) is None
