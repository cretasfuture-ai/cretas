"""Factory-isolation sweep (2026-10-22).

On prod, anonymous GETs through the public gateway answered 200 for
/api/client-requirement/companies (every surveyed company incl. contacts) and
/api/smartbi/layouts/{id}?factory_id=..., while several logged-in endpoints let the
caller pick any tenant with a body/query ``factory_id`` (yoy even set the RLS tenant
GUC from it). These tests drive the real middleware and the real handlers.
"""
from __future__ import annotations

import asyncio
from contextlib import contextmanager

import pytest
from fastapi import HTTPException
from sqlalchemy import BigInteger, create_engine
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.ext.compiler import compiles
from sqlalchemy.orm import sessionmaker
from starlette.requests import Request

from auth_middleware import INTERNAL_ONLY_PATHS, JWTAuthMiddleware, PUBLIC_PREFIXES
from common import tenant_identity


@compiles(JSONB, "sqlite")
def _jsonb_as_json(_type, _compiler, **_kw):
    return "JSON"


@compiles(BigInteger, "sqlite")
def _bigint_as_integer(_type, _compiler, **_kw):
    # SQLite 只对 INTEGER PRIMARY KEY 自增; 生产是 PG BIGSERIAL
    return "INTEGER"


OWN, OTHER = "F_OWN", "F_OTHER"


# ---------------------------------------------------------------- middleware


class _Echo:
    def __init__(self):
        self.reached = False

    async def __call__(self, scope, receive, send):
        self.reached = True
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await send({"type": "http.response.body", "body": b"ok"})


def _anonymous(path):
    mw = JWTAuthMiddleware(_Echo(), jwt_secret="test-secret-only", enabled=True)
    sent = []

    async def receive():
        return {"type": "http.request", "body": b"", "more_body": False}

    async def send(msg):
        sent.append(msg)

    asyncio.run(mw({"type": "http", "path": path, "headers": [], "method": "GET", "query_string": b""},
                   receive, send))
    status = next((m["status"] for m in sent if m["type"] == "http.response.start"), None)
    return status, mw.app.reached


@pytest.mark.parametrize("path", [
    "/api/smartbi/layouts/1",
    "/api/smartbi/layouts/save",
    "/api/smartbi/cross-sheet-analysis",
    "/api/smartbi/yoy-comparison",
    "/api/client-requirement/companies",
    "/api/client-requirement/link-factory",
    "/api/client-requirement/wizard/finalize",
    "/api/client-requirement/completeness/compute",
    "/api/excel/export/batch/save",
    "/api/classifier/load",
])
def test_formerly_public_factory_data_paths_now_reject_anonymous(monkeypatch, path):
    monkeypatch.setenv("INTERNAL_API_SECRET", "real-secret")
    status, reached = _anonymous(path)
    assert reached is False, f"{path} reached the handler without credentials"
    assert status == 401


def test_stateless_public_prefix_is_still_public(monkeypatch):
    # 阳性对照: 无状态的图表构建仍免登录 —— 证明上面的 401 来自收紧, 而不是中间件整体拒绝
    monkeypatch.setenv("INTERNAL_API_SECRET", "real-secret")
    status, reached = _anonymous("/api/chart/build")
    assert reached is True and status == 200


def test_dangerous_paths_are_internal_only_and_removed_prefixes_stay_removed():
    assert {"/api/excel/export/batch/save", "/api/classifier/load"} <= set(INTERNAL_ONLY_PATHS)
    for path in ("/api/smartbi/layouts", "/api/smartbi/cross-sheet-analysis", "/api/smartbi/yoy-comparison",
                 "/api/client-requirement/companies"):
        assert not any(path.startswith(p) for p in PUBLIC_PREFIXES), path


# ---------------------------------------------------------------- identity helpers


def _req(factory_id=None, role="operator", auth: "str | None" = "jwt", path_params=None, query=b""):
    state = {}
    if auth:
        state["auth_method"] = auth
    if factory_id:
        state["factory_id"] = factory_id
    if role:
        state["role"] = role
    scope = {"type": "http", "method": "GET", "path": "/x", "query_string": query, "headers": [],
             "state": state}
    if path_params is not None:
        scope["path_params"] = path_params
    return Request(scope)


def _status(fn, *args):
    with pytest.raises(HTTPException) as err:
        result = fn(*args)
        if asyncio.iscoroutine(result):
            asyncio.run(result)
    return err.value.status_code


def test_identity_helpers_pin_factory_users_and_make_platform_name_a_factory():
    assert _status(tenant_identity.caller_factory, _req(None, auth=None)) == 401
    assert tenant_identity.require_same_factory(_req(OWN), None) == OWN
    assert tenant_identity.require_same_factory(_req(OWN), OWN) == OWN
    assert _status(tenant_identity.require_same_factory, _req(OWN), OTHER) == 403
    assert _status(tenant_identity.scoped_factory, _req(OWN), OTHER) == 403
    platform = _req(None, role="super_admin")
    assert tenant_identity.scoped_factory(platform, OTHER) == OTHER
    assert _status(tenant_identity.scoped_factory, platform, None) == 400
    # 内部调用不是平台角色: 只能是 X-Factory-Id 那家
    internal = _req(OWN, role="super_admin", auth="internal")
    assert _status(tenant_identity.scoped_factory, internal, OTHER) == 403


def test_client_requirement_router_is_platform_only_except_own_factory_queries():
    from client_requirement import wizard_api
    deps = [d.dependency for d in wizard_api.router.dependencies]
    assert tenant_identity.require_platform_or_own_factory in deps
    check = tenant_identity.require_platform_or_own_factory
    assert _status(check, _req(OWN, role="factory_super_admin")) == 403             # /companies etc.
    assert _status(check, _req(None, auth=None)) == 401
    check(_req(None, role="platform_admin"))                                           # platform ok
    check(_req(OWN, role="factory_super_admin", path_params={"factory_id": OWN}))      # own gap-analysis
    assert _status(check, _req(OWN, role="factory_super_admin", path_params={"factory_id": OTHER})) == 403
    check(_req(None, role="super_admin", path_params={"factory_id": OTHER}))           # platform any


# ---------------------------------------------------------------- layouts (real SQL)


@pytest.fixture()
def layout_db(monkeypatch):
    from smartbi.database import connection
    from smartbi.database.models import SmartBiDashboardLayout
    engine = create_engine("sqlite://")
    SmartBiDashboardLayout.__table__.create(engine)
    Session = sessionmaker(bind=engine)
    with Session() as s:
        s.add(SmartBiDashboardLayout(id=1, factory_id=OTHER, upload_id=7, sheet_index=0,
                                     layout_data={"secret": "other factory layout"}))
        s.commit()

    @contextmanager
    def ctx():
        s = Session()
        try:
            yield s
            s.commit()
        finally:
            s.close()

    monkeypatch.setattr(connection, "get_db_context", ctx)
    monkeypatch.setattr(connection, "is_postgres_enabled", lambda: True)
    return Session


def test_layouts_are_scoped_to_the_callers_factory(layout_db):
    from smartbi.api import layout
    from smartbi.database.models import SmartBiDashboardLayout

    other_read = asyncio.run(layout.get_layout(7, _req(OWN), 0, None))
    assert other_read["data"] is None, "read another factory's layout"
    assert _status(layout.get_layout, 7, _req(OWN), 0, OTHER) == 403
    assert _status(layout.delete_layout, 7, _req(OWN), 0, OTHER) == 403
    asyncio.run(layout.delete_layout(7, _req(OWN), 0, None))
    with layout_db() as s:
        assert s.query(SmartBiDashboardLayout).filter_by(factory_id=OTHER).count() == 1, "deleted across tenants"

    saved = asyncio.run(layout.save_layout(layout.LayoutSaveRequest(upload_id=7, layout_data={"mine": 1}),
                                           _req(OWN)))
    assert saved.success and saved.data["factoryId"] == OWN
    assert _status(layout.save_layout, layout.LayoutSaveRequest(factory_id=OTHER, upload_id=7, layout_data={}),
                   _req(OWN)) == 403
    # 阳性对照: 本厂读到自己刚存的
    mine = asyncio.run(layout.get_layout(7, _req(OWN), 0, None))
    assert mine["data"]["layoutData"] == {"mine": 1}


# ---------------------------------------------------------------- handlers refuse a foreign factory before any DB work


def test_tenant_choosing_handlers_refuse_a_foreign_factory():
    from smartbi.api import cross_sheet, data_sync, db_analysis, nl_to_sql, yoy
    assert _status(yoy.yoy_comparison, yoy.YoYRequest(upload_id=1, factory_id=OTHER), _req(OWN)) == 403
    assert _status(cross_sheet.cross_sheet_analysis,
                   cross_sheet.CrossSheetRequest(upload_ids=[1], factory_id=OTHER), _req(OWN)) == 403
    assert _status(data_sync.sync_system_data, data_sync.SyncRequest(upload_id=1, factory_id=OTHER), _req(OWN)) == 403
    assert _status(nl_to_sql.nl_to_sql, nl_to_sql.NL2SQLRequest(query="销售额", upload_id=1, factory_id=OTHER),
                   _req(OWN)) == 403
    assert _status(db_analysis.aggregate_from_db,
                   db_analysis.AggregateRequest(factory_id=OTHER, upload_id=1, group_field="g", measure_field="m"),
                   _req(OWN)) == 403


def test_rfm_without_factory_no_longer_aggregates_every_factory(monkeypatch):
    from smartbi.api import rfm
    seen = {}
    monkeypatch.setattr(rfm, "_get_db_connection", lambda: object())
    monkeypatch.setattr(rfm, "_compute_rfm_from_db", lambda conn, factory_id: seen.setdefault("f", factory_id))
    asyncio.run(rfm.get_rfm_analysis(_req(OWN), None))
    assert seen["f"] == OWN
