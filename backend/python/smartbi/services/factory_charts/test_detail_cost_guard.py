from datetime import datetime, timedelta, timezone
import hashlib
import json
from types import SimpleNamespace

import pandas as pd
import pytest

from . import cost_authorization, detail_store, details, engine


class _Acquire:
    def __init__(self, conn): self.conn = conn
    async def __aenter__(self): return self.conn
    async def __aexit__(self, *_): return None


class _Conn:
    def __init__(self, metadata, values, calls): self.metadata, self.values, self.calls = metadata, values, calls
    def transaction(self):
        class Tx:
            async def __aenter__(self): return self
            async def __aexit__(self, *_): return None
        return Tx()
    async def execute(self, sql, *args): self.calls.append(("execute", sql, args))
    async def fetchrow(self, sql, *args):
        self.calls.append(("fetchrow", sql, args))
        return self.values if "s.rows,s.columns" in sql else self.metadata


class _Pool:
    def __init__(self, metadata, values, calls): self.conn = _Conn(metadata, values, calls)
    def acquire(self): return _Acquire(self.conn)


class _Response:
    status_code = 200
    def __init__(self, body): self.body = body
    def json(self): return self.body


class _MockAsyncClient:
    def __init__(self, calls, body): self.calls, self.body = calls, body
    async def __aenter__(self): return self
    async def __aexit__(self, *_): return None
    async def post(self, url, **kwargs): self.calls.append((url, kwargs)); return _Response(self.body)


def _request(token="Bearer detail-token"):
    return SimpleNamespace(state=SimpleNamespace(factory_id="F006", user_id=42,
                                                  role="factory_super_admin"),
                           headers={"authorization": token})


def _rpc(monkeypatch, calls, allowed):
    body = {"success": True, "data": {"factoryId": "F006", "userId": 42,
            "allowedProductIds": allowed, "unscopedCostAllowed": False}}
    monkeypatch.setenv("JAVA_API_BASE_URL", "http://java.test:10010")
    monkeypatch.setattr(cost_authorization, "java_base_url", lambda: "http://java.test:10010")
    monkeypatch.setattr(cost_authorization.httpx, "AsyncClient", lambda **_: _MockAsyncClient(calls, body))


def _metadata(*, owner_user=42, role="factory_super_admin", expires=None, guard=True,
              price_columns=None, columns=None):
    dimensions = {"fields": ["产品ID", "产量", "总成本"], "pointDimension": None}
    if guard:
        dimensions["costGuard"] = {"productIds": ["p-public"], "unscoped": False}
    return {"context_id": "ctx-1", "user_id": owner_user, "role": role, "candidate_id": "cost-card",
            "title": "产品成本", "expires_at": expires or datetime.now(timezone.utc) + timedelta(minutes=5),
            "source": "production · auth-fixture", "period_label": "全部历史", "dimensions": dimensions,
            "columns": ["产品ID", "产量", "总成本"] if columns is None else columns,
            "price_columns": ["总成本"] if price_columns is None else price_columns,
            "financial": bool(price_columns is None or price_columns), "size_bytes": 123, "unit": "元"}


def _values():
    return {"rows": [{"产品ID": "p-public", "产量": 10, "总成本": 11},
                     {"产品ID": "p-secret", "产量": 20, "总成本": 99}],
            "columns": ["产品ID", "产量", "总成本"]}


def _pool_getter(pool):
    async def get_pool():
        return pool
    return get_pool


@pytest.mark.asyncio
async def test_cached_cost_snapshot_checks_metadata_then_rpc_and_denies_before_rows(monkeypatch):
    calls, rpc_calls = [], []
    pool = _Pool(_metadata(), _values(), calls)
    monkeypatch.setattr(detail_store.config, "get_pg_pool", _pool_getter(pool))
    _rpc(monkeypatch, rpc_calls, [])
    with pytest.raises(details.DetailError) as denied:
        await detail_store._load_impl("ctx-1", ("F006", 42, "factory_super_admin"), _request())
    assert denied.value.status_code == 403
    assert len(rpc_calls) == 1
    assert len([item for item in calls if item[0] == "fetchrow"]) == 1


@pytest.mark.asyncio
async def test_cached_mixed_guard_rejects_java_unscoped_mismatch_before_snapshot_rows(monkeypatch):
    calls, rpc_calls = [], []
    metadata = _metadata()
    metadata["dimensions"]["costGuard"] = {"productIds": ["p-public"], "unscoped": True}
    pool = _Pool(metadata, _values(), calls)
    monkeypatch.setattr(detail_store.config, "get_pg_pool", _pool_getter(pool))
    # Java grants the identified product but explicitly refuses unscoped cost.
    _rpc(monkeypatch, rpc_calls, ["p-public"])
    with pytest.raises(details.DetailError) as denied:
        await detail_store._load_impl("ctx-1", ("F006", 42, "factory_super_admin"), _request())
    assert denied.value.status_code == 403
    assert len(rpc_calls) == 1
    assert len([item for item in calls if item[0] == "fetchrow"]) == 1
    assert not any(item[0] == "fetchrow" and "s.rows,s.columns" in item[1] for item in calls)


@pytest.mark.asyncio
async def test_cached_cost_snapshot_missing_auth_or_legacy_guard_never_reads_rows(monkeypatch):
    for metadata, request in ((_metadata(), None), (_metadata(guard=False), _request())):
        calls, rpc_calls = [], []
        pool = _Pool(metadata, _values(), calls)
        monkeypatch.setattr(detail_store.config, "get_pg_pool", _pool_getter(pool))
        _rpc(monkeypatch, rpc_calls, ["p-public"])
        with pytest.raises(details.DetailError) as denied:
            await detail_store._load_impl("ctx-1", ("F006", 42, "factory_super_admin"), request)
        assert denied.value.status_code == 403
        assert len([item for item in calls if item[0] == "fetchrow"]) == 1


@pytest.mark.asyncio
async def test_owner_and_expiry_metadata_are_checked_before_snapshot_rows(monkeypatch):
    for metadata, owner, status in ((_metadata(owner_user=9), ("F006", 42, "factory_super_admin"), 403),
                                    (_metadata(expires=datetime.now(timezone.utc) - timedelta(seconds=1)),
                                     ("F006", 42, "factory_super_admin"), 410)):
        calls = []
        monkeypatch.setattr(detail_store.config, "get_pg_pool", _pool_getter(_Pool(metadata, _values(), calls)))
        with pytest.raises(details.DetailError) as denied:
            await detail_store._load_impl("ctx-1", owner, _request())
        assert denied.value.status_code == status
        assert len([item for item in calls if item[0] == "fetchrow"]) == 1


@pytest.mark.asyncio
async def test_requires_finance_shared_is_metadata_only_and_never_calls_cost_rpc(monkeypatch):
    calls, rpc_calls = [], []
    monkeypatch.setattr(detail_store.config, "get_pg_pool", _pool_getter(_Pool(_metadata(), _values(), calls)))
    _rpc(monkeypatch, rpc_calls, [])
    assert await detail_store.requires_finance_shared("ctx-1", ("F006", 42, "factory_super_admin")) is True
    assert len([item for item in calls if item[0] == "fetchrow"]) == 1
    assert rpc_calls == []


@pytest.mark.asyncio
@pytest.mark.parametrize("price_column", ["material_cost", "total_cost"])
async def test_cached_alias_cost_columns_require_guard_and_recheck_before_values(monkeypatch, price_column):
    calls, rpc_calls = [], []
    pool = _Pool(_metadata(price_columns=[price_column]), _values(), calls)
    monkeypatch.setattr(detail_store.config, "get_pg_pool", _pool_getter(pool))
    _rpc(monkeypatch, rpc_calls, [])
    with pytest.raises(details.DetailError) as denied:
        await detail_store._load_impl("ctx-1", ("F006", 42, "factory_super_admin"), _request())
    assert denied.value.status_code == 403
    assert len(rpc_calls) == 1
    assert len([item for item in calls if item[0] == "fetchrow"]) == 1
    calls.clear(); rpc_calls.clear()
    legacy = _metadata(price_columns=[price_column], guard=False)
    monkeypatch.setattr(detail_store.config, "get_pg_pool", _pool_getter(_Pool(legacy, _values(), calls)))
    with pytest.raises(details.DetailError) as legacy_denied:
        await detail_store._load_impl("ctx-1", ("F006", 42, "factory_super_admin"), _request())
    assert legacy_denied.value.status_code == 403
    assert rpc_calls == []
    assert len([item for item in calls if item[0] == "fetchrow"]) == 1


def test_prepared_cost_guard_uses_actual_engine_rows_and_immutable_hash():
    frame = pd.DataFrame([
        {"日期": "2026-01-01", "产品ID": "p-public", "单位": "kg", "产量": 10, "总成本": 11},
        {"日期": "2026-01-02", "产品ID": "p-secret", "单位": "kg", "产量": 20, "总成本": None},
    ])
    dataset = engine.Dataset(frame, "production · auth-fixture")
    request = SimpleNamespace(period_type="all", year=None, start_month=1, end_month=12,
                              analysis_subject=None, source="production", include_optional=False)
    # Exercise the real engine's source/subject discovery before handing the
    # immutable Dataset to the detail store.  The cost candidate shape is an
    # existing public engine contract; its rows still come from this Dataset.
    subjects = engine.analysis_subjects([dataset], True)
    assert isinstance(subjects, list)
    candidate = {"id": "production.cost:fixture", "title": "产品成本", "source": dataset.source,
                 "unit": "元", "chart": {"chartType": "bar", "echartsOption": {}}}
    prepared = list(detail_store._prepared([candidate], [dataset], request, ("F006", 42, "factory_super_admin")))[0][1]
    assert prepared["dimension_contract"]["costGuard"]["productIds"] == ["p-public"]
    assert prepared["snapshot_key"]
    frame.loc[0, "总成本"] = 12
    changed = list(detail_store._prepared([candidate], [engine.Dataset(frame, dataset.source)], request,
                                          ("F006", 42, "factory_super_admin")))[0][1]
    assert changed["snapshot_key"] != prepared["snapshot_key"]
    altered_guard = dict(prepared["dimension_contract"])
    altered_guard["costGuard"] = {"productIds": ["p-public", "p-secret"], "unscoped": False}
    guard_changed_key = hashlib.sha256(json.dumps(
        [("F006", 42, "factory_super_admin"), prepared["source"], {}, prepared["period"],
         prepared["unit"], prepared["rows"], prepared["columns"], altered_guard],
        ensure_ascii=False, sort_keys=True, default=str, separators=(",", ":")).encode("utf-8")).hexdigest()
    assert guard_changed_key != prepared["snapshot_key"]


def test_prepared_guard_marks_missing_product_id_rows_unscoped_and_supports_semantic_aliases():
    request = SimpleNamespace(period_type="all", year=None, start_month=1, end_month=12,
                              analysis_subject=None, source="production", include_optional=False)
    candidate = {"id": "cost-alias", "title": "成本", "source": "production · alias",
                 "unit": "元", "chart": {"chartType": "bar", "echartsOption": {}}}
    mixed = engine.Dataset(pd.DataFrame([
        {"SKU": "p1", "总成本": 10}, {"SKU": None, "总成本": 20},
    ]), "production · alias")
    mixed_snapshot = list(detail_store._prepared([candidate], [mixed], request,
                                                  ("F006", 42, "factory_super_admin")))[0][1]
    assert mixed_snapshot["dimension_contract"]["costGuard"] == {
        "productIds": ["p1"], "unscoped": True}
    for column in ("SKU", "product_id", "productTypeId", "产品ID（kg）"):
        source = engine.Dataset(pd.DataFrame([{column: "p1", "总成本": 10}]), "production · alias")
        snapshot = list(detail_store._prepared([candidate], [source], request,
                                               ("F006", 42, "factory_super_admin")))[0][1]
        assert snapshot["dimension_contract"]["costGuard"]["productIds"] == ["p1"], column


def test_cost_guard_is_union_of_all_cost_row_id_columns_and_conflicts_are_not_collapsed():
    request = SimpleNamespace(period_type="all", year=None, start_month=1, end_month=12,
                              analysis_subject=None, source="production", include_optional=False)
    candidate = {"id": "cost-conflicts", "title": "成本", "source": "production · conflicts",
                 "unit": "元", "chart": {"chartType": "bar", "echartsOption": {}}}
    dataset = engine.Dataset(pd.DataFrame([
        {"productId": "PUBLIC", "产品ID": "PUBLIC", "总成本": 10},
        {"productId": "PUBLIC", "产品ID": "SECRET", "总成本": 99},
        {"productId": "SECRET", "产品ID": "SECRET", "总成本": 20},
        {"总成本": 30},
    ]), "production · conflicts")
    snapshot = list(detail_store._prepared([candidate], [dataset], request,
                                           ("F006", 42, "factory_super_admin")))[0][1]
    assert snapshot["dimension_contract"]["costGuard"] == {
        "productIds": ["PUBLIC", "SECRET"], "unscoped": True}


def test_engine_stable_product_id_aliases_and_unicode_bracket_contract():
    request = SimpleNamespace(period_type="all", year=None, start_month=1, end_month=12,
                              analysis_subject=None, source="production", include_optional=False)
    candidate = {"id": "cost-id-contract", "title": "成本", "source": "production · ids",
                 "unit": "元", "chart": {"chartType": "bar", "echartsOption": {}}}
    for column in ("产品编号", "产品编码", "sku编码", "产品类型id", "产品ID（🔒）"):
        dataset = engine.Dataset(pd.DataFrame([{column: "产品-α", "总成本": 10}]), "production · ids")
        snapshot = list(detail_store._prepared([candidate], [dataset], request,
                                               ("F006", 42, "factory_super_admin")))[0][1]
        assert snapshot["dimension_contract"]["costGuard"]["productIds"] == ["产品-α"], column
    plain = engine.Dataset(pd.DataFrame([{"编码": "产品-α", "总成本": 10}]), "production · ids")
    plain_snapshot = list(detail_store._prepared([candidate], [plain], request,
                                                 ("F006", 42, "factory_super_admin")))[0][1]
    assert plain_snapshot["dimension_contract"]["costGuard"] == {
        "productIds": [], "unscoped": True}


@pytest.mark.asyncio
async def test_no_cost_snapshot_does_not_call_authority(monkeypatch):
    calls, rpc_calls = [], []
    metadata = _metadata(guard=False, price_columns=[], columns=["产品ID", "产量"])
    pool = _Pool(metadata, _values(), calls)
    monkeypatch.setattr(detail_store.config, "get_pg_pool", _pool_getter(pool))
    _rpc(monkeypatch, rpc_calls, [])
    frame = await detail_store._load_impl("ctx-1", ("F006", 42, "factory_super_admin"), None)
    assert frame.rows and rpc_calls == []
    assert len([item for item in calls if item[0] == "fetchrow"]) == 2
