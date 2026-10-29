from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from types import SimpleNamespace
import uuid
from pathlib import Path
from urllib.parse import urlparse

import pandas as pd
import pytest

from . import cost_authorization, detail_store, details, engine, newstock_buckets


ROOT = Path(__file__).parents[3]
MIGRATION = ROOT / "smartbi" / "database" / "migrations" / "V20261101_18__factory_chart_detail_contexts.sql"


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


def test_prepared_mixed_units_drop_empty_cost_after_candidate_filter_and_keep_digest_contract():
    request = SimpleNamespace(period_type="all", year=None, start_month=1, end_month=12,
                              analysis_subject=None, source="production", include_optional=False)
    source = "production · mixed-units"
    frame = pd.DataFrame([
        {"日期": "2026-01-01", "单位": "kg", "产品ID": "p-cost", "产量": 10, "总成本": 11},
        {"日期": "2026-01-02", "单位": "盒", "产品ID": "p-empty", "产量": 20, "总成本": None},
    ])
    dataset = engine.Dataset(frame, source)
    candidates = [
        {"id": "cost-kg", "title": "kg成本", "source": source, "unit": "元/kg",
         "chart": {"chartType": "bar", "echartsOption": {}}},
        {"id": "cost-box", "title": "盒成本", "source": source, "unit": "元/盒",
         "chart": {"chartType": "bar", "echartsOption": {}}},
    ]

    prepared = dict((item["id"], snapshot) for item, snapshot in detail_store._prepared(
        candidates, [dataset], request, ("F006", 42, "factory_super_admin")))
    priced = prepared["cost-kg"]
    empty = prepared["cost-box"]
    assert priced["columns"] == ("日期", "单位", "产品ID", "产量", "总成本")
    assert priced["price_columns"] == ["总成本"]
    assert priced["dimension_contract"]["costGuard"]["productIds"] == ["p-cost"]
    assert empty["columns"] == ("日期", "单位", "产品ID", "产量")
    assert empty["price_columns"] == []
    assert "costGuard" not in empty["dimension_contract"]
    assert all("总成本" not in row for row in empty["rows"])
    assert empty["snapshot_key"] != priced["snapshot_key"]

    # A genuine zero is a present cost value and must continue to require the
    # existing authorization guard rather than being mistaken for null.
    zero = engine.Dataset(pd.DataFrame([
        {"日期": "2026-01-02", "单位": "盒", "产品ID": "p-zero", "产量": 20, "总成本": 0},
    ]), "production · zero-cost")
    zero_candidate = {**candidates[1], "source": zero.source}
    zero_snapshot = list(detail_store._prepared([zero_candidate], [zero], request,
                                                  ("F006", 42, "factory_super_admin")))[0][1]
    assert zero_snapshot["columns"][-1] == "总成本"
    assert zero_snapshot["price_columns"] == ["总成本"]
    assert zero_snapshot["dimension_contract"]["costGuard"]["productIds"] == ["p-zero"]


def test_prepared_stock_contract_keeps_verified_bucket_dimension_after_cost_column_drop():
    request = SimpleNamespace(period_type="all", year=None, start_month=1, end_month=12,
                              analysis_subject=None, source="inventory", include_optional=False)
    source = "inventory · stock-cost-mixed"
    dataset = engine.Dataset(pd.DataFrame([
        {"批次": "b1", "日期": "2026-01-01", "单位": "kg", "产品": "A",
         "库存数量": 1, "库龄天数": 10, "总成本": None},
        {"批次": "b2", "日期": "2026-01-01", "单位": "kg", "产品": "B",
         "库存数量": 1, "库龄天数": 45, "总成本": None},
    ]), source)
    candidate = {
        "id": "inventory.stock.age:bar", "title": "库存库龄", "source": source, "unit": "批",
        "engine": {"stockCountContract": newstock_buckets.contract(
            "age", "库存数量", "库龄天数", "库存库龄桶")},
        "chart": {"chartType": "bar", "echartsOption": {}},
    }
    item, snapshot = list(detail_store._prepared(
        [candidate], [dataset], request, ("F006", 42, "factory_super_admin")))[0]
    assert item["id"] == candidate["id"] and snapshot is not None
    assert "总成本" not in snapshot["columns"]
    assert "库存库龄桶" in snapshot["columns"]
    assert "库存库龄桶" in snapshot["dimension_contract"]["fields"]
    assert {row["库存库龄桶"] for row in snapshot["rows"]} == {"不超过30天", "31–90天"}


@pytest.mark.asyncio
async def test_real_shared_mixed_unit_cost_snapshots_filter_columns_and_recheck_authority(monkeypatch):
    dsn = os.getenv("FACTORY_SELECTION_TEST_PG_URL") or os.getenv("DETAIL_STORE_TEST_PG_URL")
    if not dsn:
        pytest.skip("set FACTORY_SELECTION_TEST_PG_URL for disposable PostgreSQL")
    target = urlparse(dsn)
    assert target.hostname in ("localhost", "127.0.0.1") and target.port == 55439
    asyncpg = pytest.importorskip("asyncpg")
    admin = await asyncpg.connect(dsn)
    schema = "cost_guard_" + uuid.uuid4().hex
    role = "smartbi_user"
    await admin.execute(
        "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='smartbi_user') "
        "THEN CREATE ROLE smartbi_user NOLOGIN; END IF; END $$"
    )
    await admin.execute(f"CREATE SCHEMA {schema}")
    await admin.execute(f"SET search_path TO {schema},public")
    await admin.execute(MIGRATION.read_text(encoding="utf-8"))
    await admin.execute(f"GRANT USAGE ON SCHEMA {schema} TO {role}")

    async def setup(connection):
        await connection.execute(f"SET ROLE {role}")
        await connection.execute(f"SET search_path TO {schema},public")

    pool = await asyncpg.create_pool(dsn, min_size=1, max_size=1,
                                     server_settings={"search_path": f"{schema},public"}, setup=setup)
    original = detail_store.config.get_pg_pool
    detail_store.config.get_pg_pool = lambda: pool
    try:
        source = "production · mixed-units-real-pg"
        dataset = engine.Dataset(pd.DataFrame([
            {"日期": "2026-01-01", "单位": "kg", "产品ID": "p-cost", "产量": 10, "总成本": 11},
            {"日期": "2026-01-02", "单位": "盒", "产品ID": "p-empty", "产量": 20, "总成本": None},
        ]), source)
        candidates = [
            {"id": "real-cost-kg", "title": "kg成本", "source": source, "unit": "元/kg",
             "chart": {"chartType": "bar", "echartsOption": {}}},
            {"id": "real-cost-box", "title": "盒成本", "source": source, "unit": "元/盒",
             "chart": {"chartType": "bar", "echartsOption": {}}},
        ]
        owner = ("F006", 4201, "factory_super_admin")
        request = SimpleNamespace(period_type="all", year=None, start_month=1, end_month=12,
                                  analysis_subject=None, source="production", include_optional=False)
        registered = await detail_store.register_shared(candidates, [dataset], request, owner, True)
        by_id = {item["id"]: item for item in registered}
        assert by_id["real-cost-kg"]["detailContext"]["id"]
        assert by_id["real-cost-box"]["detailContext"]["id"]

        empty_page = await detail_store.page_shared(
            {"context_id": by_id["real-cost-box"]["detailContext"]["id"]}, owner, True, True
        )
        assert empty_page["data"]["rows"]
        assert "总成本" not in empty_page["data"]["columns"]
        assert all("总成本" not in row for row in empty_page["data"]["rows"])

        async def allow_cost(*_args, **_kwargs):
            return cost_authorization.CostAuthorization("F006", 4201, frozenset({"p-cost"}), False)

        monkeypatch.setattr(cost_authorization, "authorize_batches", allow_cost)
        priced_page = await detail_store.page_shared(
            {"context_id": by_id["real-cost-kg"]["detailContext"]["id"]}, owner, True, True, _request()
        )
        assert "总成本" in priced_page["data"]["columns"]
        assert priced_page["data"]["rows"][0]["总成本"] == 11

        async def deny_cost(*_args, **_kwargs):
            return cost_authorization.CostAuthorization("F006", 4201, frozenset(), False)

        monkeypatch.setattr(cost_authorization, "authorize_batches", deny_cost)
        with pytest.raises(details.DetailError) as denied:
            await detail_store.page_shared(
                {"context_id": by_id["real-cost-kg"]["detailContext"]["id"]}, owner, True, True, _request()
            )
        assert denied.value.status_code == 403

        legacy_key = "f" * 64
        legacy_rows = [{"单位": "kg", "产品ID": "p-legacy", "总成本": 9}]
        legacy_columns = ["单位", "产品ID", "总成本"]
        legacy_dimensions = {"fields": ["单位", "产品ID"], "pointDimension": None}
        legacy_snapshot = await admin.fetchval(
            f"INSERT INTO {schema}.smart_bi_factory_chart_detail_snapshots "
            "(factory_id,user_id,role,source,period_label,unit,rows,columns,dimensions,price_columns,financial,size_bytes,dedup_key) "
            "VALUES($1,$2,$3,$4,$5,$6,$7::jsonb,$8::jsonb,$9::jsonb,$10::jsonb,$11,$12,$13) RETURNING snapshot_id",
            "F006", "4201", "factory_super_admin", "production · legacy-cost", "全部历史", "元",
            json.dumps(legacy_rows, ensure_ascii=False), json.dumps(legacy_columns, ensure_ascii=False),
            json.dumps(legacy_dimensions, ensure_ascii=False), json.dumps(["总成本"], ensure_ascii=False),
            True, 123, legacy_key,
        )
        legacy_context = "legacy-cost-" + uuid.uuid4().hex
        await admin.execute(
            f"INSERT INTO {schema}.smart_bi_factory_chart_detail_contexts "
            "(context_id,snapshot_id,factory_id,user_id,role,candidate_id,title,expires_at) "
            "VALUES($1,$2,$3,$4,$5,$6,$7,$8)",
            legacy_context, legacy_snapshot, "F006", "4201", "factory_super_admin", "legacy-cost", "旧成本",
            datetime.now(timezone.utc) + timedelta(minutes=5),
        )
        with pytest.raises(details.DetailError) as legacy_denied:
            await detail_store.page_shared({"context_id": legacy_context}, owner, True, True, _request())
        assert legacy_denied.value.status_code == 403
    finally:
        detail_store.config.get_pg_pool = original
        await pool.close()
        await admin.execute(f"DROP SCHEMA {schema} CASCADE")
        await admin.close()


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
