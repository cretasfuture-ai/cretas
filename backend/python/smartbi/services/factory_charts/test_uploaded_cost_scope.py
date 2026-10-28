from types import SimpleNamespace

import pytest
import smartbi.config as smartbi_config
from fastapi import FastAPI, Request
from httpx import ASGITransport, AsyncClient

from smartbi.api import financial_dashboard as dashboard
from . import cost_authorization


class _Acquire:
    def __init__(self, conn): self.conn = conn
    async def __aenter__(self): return self.conn
    async def __aexit__(self, *_): return None


class _Connection:
    def __init__(self, id_rows, full_rows, calls, allow_unscoped=False):
        self.id_rows, self.full_rows, self.calls, self.allow_unscoped = id_rows, full_rows, calls, allow_unscoped

    def transaction(self):
        class Tx:
            async def __aenter__(self): return self
            async def __aexit__(self, *_): return None
        return Tx()

    async def fetch(self, sql, *args):
        self.calls.append((sql, args))
        if "SELECT DISTINCT" in sql:
            return self.id_rows
        if "CASE WHEN" in sql:
            allowed = set(args[2] if len(args) > 2 else ())
            projected = []
            for row in self.full_rows:
                value = dict(row["row_data"])
                product = value.get("productId") or value.get("产品ID") or value.get("product_type_id")
                identifiers = [value.get(key) for key in ("productId", "产品ID", "product_type_id")
                               if value.get(key) not in (None, "")]
                conflicting = len({str(identifier) for identifier in identifiers}) > 1
                unauthorized_product = ((product in (None, "") and not self.allow_unscoped)
                                        or (product not in (None, "") and str(product) not in allowed))
                if conflicting or unauthorized_product:
                    for key in ("总成本", "总成本（元）", "Total_Cost", "材料成本", "人工成本", "设备成本", "其他成本", "单位成本"):
                        value.pop(key, None)
                projected.append({"row_data": value, "sheet_name": row["sheet_name"]})
            return projected
        return self.full_rows


class _Pool:
    def __init__(self, id_rows, full_rows, calls, allow_unscoped=False):
        # Current metadata pass returns key_name alongside product_id.
        self.conn = _Connection([{**row, "key_name": row.get("key_name", "总成本")} for row in id_rows], full_rows, calls, allow_unscoped)
    def acquire(self): return _Acquire(self.conn)


class _Response:
    status_code = 200
    def __init__(self, body): self.body = body
    def json(self): return self.body


class _MockAsyncClient:
    def __init__(self, calls, body): self.calls, self.body = calls, body
    async def __aenter__(self): return self
    async def __aexit__(self, *_): return None
    async def post(self, url, **kwargs):
        self.calls.append((url, kwargs))
        return _Response(self.body)


def _request(token="Bearer upload-token"):
    return SimpleNamespace(
        state=SimpleNamespace(factory_id="F006", user_id=42, role="factory_super_admin"),
        headers={"authorization": token},
    )


def _rpc(monkeypatch, calls, allowed, *, unscoped=False, factory="F006", user=42):
    body = {"success": True, "data": {"factoryId": factory, "userId": user,
            "allowedProductIds": allowed, "unscopedCostAllowed": unscoped}}
    monkeypatch.setenv("JAVA_API_BASE_URL", "http://java.test:10010")
    monkeypatch.setattr(cost_authorization, "java_base_url", lambda: "http://java.test:10010")
    monkeypatch.setattr(cost_authorization.httpx, "AsyncClient", lambda **_: _MockAsyncClient(calls, body))


def _row(product, cost):
    return {"row_data": {"productId": product, "产量": 10, "总成本": cost}, "sheet_name": "生产成本"}


def _pool_getter(pool):
    async def get_pool():
        return pool
    return get_pool


def _dashboard_app():
    app = FastAPI()
    @app.middleware("http")
    async def identity(request: Request, call_next):
        request.state.factory_id = "F006"
        request.state.user_id = 42
        request.state.role = "factory_super_admin"
        return await call_next(request)
    app.include_router(dashboard.router)
    return app


@pytest.mark.asyncio
async def test_uploaded_fixed_product_ids_check_tenant_status_then_rpc_before_full_rows(monkeypatch):
    db_calls, rpc_calls = [], []
    pool = _Pool([{"product_id": "p-public"}, {"product_id": "p-secret"}],
                 [_row("p-public", 11), _row("p-secret", 99)], db_calls)
    monkeypatch.setattr(smartbi_config, "get_pg_pool", _pool_getter(pool))
    _rpc(monkeypatch, rpc_calls, [])
    frame = await dashboard._load_data(dashboard.FinancialDashboardRequest(upload_id=7), "F006",
                                       _request(), require_cost_authorization=True)
    assert list(frame["产量"]) == [10, 10]
    assert "总成本" not in frame.columns
    assert len(db_calls) == 2 and "factory_id" in db_calls[0][0] and "upload_status = 'COMPLETED'" in db_calls[0][0]
    assert "SELECT DISTINCT" in db_calls[0][0]
    assert len(rpc_calls) == 1
    assert "CASE WHEN" in db_calls[1][0] and "d.row_data, d.sheet_name" not in db_calls[1][0]


@pytest.mark.asyncio
async def test_uploaded_authority_identity_mismatch_is_before_row_data_full_select(monkeypatch):
    db_calls, rpc_calls = [], []
    pool = _Pool([{"product_id": "p-public"}], [_row("p-public", 11)], db_calls)
    monkeypatch.setattr(smartbi_config, "get_pg_pool", _pool_getter(pool))
    _rpc(monkeypatch, rpc_calls, ["p-public"], factory="OTHER")
    with pytest.raises(dashboard.DataSourceError) as mismatch:
        await dashboard._load_data(dashboard.FinancialDashboardRequest(upload_id=8), "F006",
                                    _request(), require_cost_authorization=True)
    assert mismatch.value.status_code == 403
    assert len(db_calls) == 1 and len(rpc_calls) == 1


@pytest.mark.asyncio
async def test_uploaded_legacy_unscoped_requires_explicit_grant_and_only_then_reads_rows(monkeypatch):
    db_calls, rpc_calls = [], []
    pool = _Pool([{"product_id": None, "key_name": "总成本"}],
                 [{"row_data": {"产量": 10, "总成本": 17}, "sheet_name": "生产成本"}], db_calls,
                 allow_unscoped=True)
    monkeypatch.setattr(smartbi_config, "get_pg_pool", _pool_getter(pool))
    _rpc(monkeypatch, rpc_calls, [], unscoped=True)
    frame = await dashboard._load_data(dashboard.FinancialDashboardRequest(upload_id=9), "F006",
                                        _request(), require_cost_authorization=True)
    assert list(frame["总成本"]) == [17]
    assert len(db_calls) == 2
    assert "SELECT DISTINCT" in db_calls[0][0] and "row_data" in db_calls[1][0]
    assert len(rpc_calls) == 1

    db_calls.clear(); rpc_calls.clear()
    _rpc(monkeypatch, rpc_calls, [], unscoped=False)
    with pytest.raises(dashboard.DataSourceError) as denied:
        await dashboard._load_data(dashboard.FinancialDashboardRequest(upload_id=9), "F006",
                                    _request(), require_cost_authorization=True)
    assert denied.value.status_code == 403
    assert len(db_calls) == 1 and len(rpc_calls) == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("cost_key", ["总成本（元）", "Total_Cost"])
async def test_uploaded_cost_aliases_still_require_current_authority(monkeypatch, cost_key):
    db_calls, rpc_calls = [], []
    source_row = {"productId": "p-secret", "产品ID": "p-secret", "产量": 10, cost_key: 99}
    pool = _Pool([{"product_id": "p-secret", "key_name": cost_key}],
                 [{"row_data": source_row, "sheet_name": "成本"}], db_calls)
    monkeypatch.setattr(smartbi_config, "get_pg_pool", _pool_getter(pool))
    _rpc(monkeypatch, rpc_calls, [])
    frame = await dashboard._load_data(dashboard.FinancialDashboardRequest(upload_id=11), "F006",
                                       _request(), require_cost_authorization=True)
    assert list(frame["产量"]) == [10]
    assert cost_key not in frame.columns
    assert len(rpc_calls) == 1 and len(db_calls) == 2
    assert "CASE WHEN" in db_calls[1][0]


@pytest.mark.asyncio
async def test_conflicting_product_id_fields_cannot_authorize_public_and_expose_secret_cost(monkeypatch):
    db_calls, rpc_calls = [], []
    source_row = {"productId": "PUBLIC", "产品ID": "SECRET", "产量": 10, "总成本": 99}
    pool = _Pool([{"product_id": "PUBLIC", "key_name": "总成本"}],
                 [{"row_data": source_row, "sheet_name": "成本"}], db_calls)
    monkeypatch.setattr(smartbi_config, "get_pg_pool", _pool_getter(pool))
    _rpc(monkeypatch, rpc_calls, ["PUBLIC"])
    frame = await dashboard._load_data(dashboard.FinancialDashboardRequest(upload_id=12), "F006",
                                       _request(), require_cost_authorization=True)
    assert list(frame["产量"]) == [10]
    assert "总成本" not in frame.columns
    assert len(rpc_calls) == 1 and "CASE WHEN" in db_calls[1][0]


@pytest.mark.asyncio
async def test_mixed_public_secret_and_unscoped_rows_only_project_authorized_cost(monkeypatch):
    db_calls, rpc_calls = [], []
    rows = [
        {"row_data": {"productId": "PUBLIC", "产品ID": "PUBLIC", "产量": 1, "总成本": 10}, "sheet_name": "成本"},
        {"row_data": {"productId": "SECRET", "产品ID": "SECRET", "产量": 2, "总成本": 99}, "sheet_name": "成本"},
        {"row_data": {"产量": 3, "总成本": 30}, "sheet_name": "成本"},
        {"row_data": {"productId": "PUBLIC", "产品ID": "SECRET", "产量": 4, "总成本": 88}, "sheet_name": "成本"},
    ]
    pool = _Pool([{"product_id": "PUBLIC", "key_name": "总成本"},
                  {"product_id": "SECRET", "key_name": "总成本"},
                  {"product_id": None, "key_name": "总成本"}], rows, db_calls, allow_unscoped=True)
    monkeypatch.setattr(smartbi_config, "get_pg_pool", _pool_getter(pool))
    _rpc(monkeypatch, rpc_calls, ["PUBLIC"], unscoped=True)
    frame = await dashboard._load_data(dashboard.FinancialDashboardRequest(upload_id=13), "F006",
                                       _request(), require_cost_authorization=True)
    assert list(frame["产量"]) == [1, 2, 3, 4]
    # The SQL projection must preserve non-price facts while hiding SECRET and
    # conflicting alias costs; PUBLIC and anonymous unscoped costs remain.
    assert list(frame["总成本"].dropna()) == [10, 30]
    assert len(rpc_calls) == 1 and "CASE WHEN" in db_calls[1][0]


@pytest.mark.asyncio
async def test_low_price_role_never_receives_full_upload_cost_even_when_java_allows_public(monkeypatch):
    db_calls, rpc_calls = [], []
    pool = _Pool([{"product_id": "PUBLIC", "key_name": "总成本"}],
                 [_row("PUBLIC", 99)], db_calls)
    monkeypatch.setattr(smartbi_config, "get_pg_pool", _pool_getter(pool))
    _rpc(monkeypatch, rpc_calls, ["PUBLIC"])
    request = _request()
    request.state.role = "quality_inspector"
    frame = await dashboard._load_data(dashboard.FinancialDashboardRequest(upload_id=14), "F006",
                                       request, require_cost_authorization=True)
    assert list(frame["产量"]) == [10]
    assert "总成本" not in frame.columns
    assert len(db_calls) == 2 and "CASE WHEN" in db_calls[1][0]


@pytest.mark.asyncio
@pytest.mark.parametrize("headers,expected_rpc,expected_status,expected_db_calls", [
    ({"Authorization": "Bearer route-token"}, 1, 200, 2),
    ({}, 0, 403, 1),
], ids=["authority-denied-safe-quantity", "missing-bearer"])
@pytest.mark.parametrize("path", ["/generate", "/batch"])
async def test_single_upload_generate_and_batch_do_not_bypass_cost_authorization(
        monkeypatch, path, headers, expected_rpc, expected_status, expected_db_calls):
    db_calls, rpc_calls = [], []
    pool = _Pool([{"product_id": "p-secret"}], [_row("p-secret", 99)], db_calls)
    monkeypatch.setattr(smartbi_config, "get_pg_pool", _pool_getter(pool))
    _rpc(monkeypatch, rpc_calls, [])
    # Keep the public handlers real, but make chart shaping deterministic so
    # the assertion is about the authorization call path and returned rows.
    monkeypatch.setattr(dashboard.dashboard_service, "generate_chart",
                        lambda **kwargs: {"rows": kwargs["raw_data"].to_dict("records")})
    monkeypatch.setattr(dashboard.dashboard_service, "generate_dashboard",
                        lambda **kwargs: {"rows": kwargs["raw_data"].to_dict("records")})
    async with AsyncClient(transport=ASGITransport(app=_dashboard_app()), base_url="http://test") as client:
        response = await client.post(path, json={"upload_id": 7}, headers=headers)
    assert response.status_code == expected_status, response.text
    assert len(db_calls) == expected_db_calls and "SELECT DISTINCT" in db_calls[0][0]
    assert len(rpc_calls) == expected_rpc
    if expected_status == 200:
        body = response.json()
        rows = body["rows"]
        assert rows and all("总成本" not in row for row in rows)
        assert all(row["产量"] == 10 for row in rows)
        assert "CASE WHEN" in db_calls[1][0]
