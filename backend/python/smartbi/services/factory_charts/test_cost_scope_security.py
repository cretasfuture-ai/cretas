from types import SimpleNamespace

import pandas as pd
import pytest

from . import cost_authorization, engine, settled_sources, sources


class _Acquire:
    def __init__(self, connection):
        self.connection = connection

    async def __aenter__(self):
        return self.connection

    async def __aexit__(self, *_):
        return None


class _Transaction:
    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        return None


class _Connection:
    def __init__(self, rows, calls):
        self.rows = rows
        self.calls = calls

    def transaction(self):
        return _Transaction()

    async def execute(self, sql, *args):
        self.calls.append(("execute", sql, args))

    async def fetch(self, sql, *args):
        self.calls.append(("fetch", sql, args))
        # The real process query LEFT JOINs fact_production_batch to resolve
        # product_type_id.  Dispatch on the owning FROM table, otherwise a
        # JOIN would be misclassified as a production-batch query.
        if "FROM fact_production_report r" in sql and "product_type_id = ANY" in sql:
            return self.rows["process-priced"]
        if "FROM fact_production_report r" in sql:
            return self.rows["process-ids"]
        if "fact_production_batch" in sql and "product_type_id = ANY" in sql:
            return self.rows["production-priced"]
        if "fact_production_batch" in sql:
            return self.rows["production-ids"]
        return []


class _Pool:
    def __init__(self, rows, calls):
        self.connection = _Connection(rows, calls)

    def acquire(self):
        return _Acquire(self.connection)


class _Response:
    status_code = 200

    def __init__(self, body):
        self.body = body

    def json(self):
        return self.body


class _MockAsyncClient:
    def __init__(self, calls, body):
        self.calls, self.body = calls, body

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        return None

    async def post(self, url, **kwargs):
        self.calls.append((url, kwargs))
        return _Response(self.body)


def _request(token="Bearer test-token"):
    return SimpleNamespace(
        state=SimpleNamespace(factory_id="F006", user_id=42, role="factory_super_admin"),
        headers={"authorization": token},
    )


def _request_options():
    return SimpleNamespace(
        period_type="month_range", year=2026, start_month=1, end_month=1,
        guided=False, analysis_subject=None,
    )


def _rows():
    return {
        "production-ids": [
            {"日期": "2026-01-01", "来源主键": "b-public", "产品ID": "p-public",
             "批次": "B1", "单位": "kg", "产量": 10, "数据同步时间": "2026-01-02"},
            {"日期": "2026-01-02", "来源主键": "b-secret", "产品ID": "p-secret",
             "批次": "B2", "单位": "kg", "产量": 20, "数据同步时间": "2026-01-02"},
        ],
        "production-priced": [
            {"日期": "2026-01-01", "来源主键": "b-public", "产品ID": "p-public",
             "批次": "B1", "单位": "kg", "产量": 10, "材料成本": 11,
             "人工成本": 0, "设备成本": 0, "其他成本": 0, "总成本": 11,
             "数据同步时间": "2026-01-02"},
        ],
        "process-ids": [
            {"日期": "2026-01-01", "来源主键": "r1", "产品ID": "p-public", "工序": "mix",
             "数据同步时间": "2026-01-02"},
            {"日期": "2026-01-02", "来源主键": "r2", "产品ID": "p-public", "工序": "mix",
             "数据同步时间": "2026-01-02"},
        ],
        "process-priced": [
            {"日期": "2026-01-01", "来源主键": "r1", "产品ID": "p-public", "工序": "mix",
             "材料成本": 3, "数据同步时间": "2026-01-02"},
            {"日期": "2026-01-02", "来源主键": "r2", "产品ID": "p-public", "工序": "mix",
             "材料成本": 7, "数据同步时间": "2026-01-02"},
        ],
    }


def _pool_getter(pool):
    async def get_pool():
        return pool
    return get_pool


@pytest.mark.asyncio
async def test_source_cost_authorization_is_id_quantity_rpc_then_narrow_price_sql(monkeypatch):
    calls, rpc_calls = [], []
    pool = _Pool(_rows(), calls)
    monkeypatch.setattr(sources.config, "get_pg_pool", _pool_getter(pool))
    monkeypatch.setattr(sources.config, "get_cretas_pool", _pool_getter(pool))
    monkeypatch.setattr(settled_sources, "load", lambda *args, **kwargs: _empty_settled())
    monkeypatch.setenv("JAVA_API_BASE_URL", "http://java.test:10010")
    body = {"success": True, "data": {"factoryId": "F006", "userId": 42,
            "allowedProductIds": ["p-public"], "unscopedCostAllowed": False}}
    monkeypatch.setattr(cost_authorization, "java_base_url", lambda: "http://java.test:10010")
    monkeypatch.setattr(cost_authorization.httpx, "AsyncClient", lambda **_: _MockAsyncClient(rpc_calls, body))

    datasets, errors, _ = await sources.load("F006", _request_options(), True, auth_request=_request())
    production = next(item for item in datasets if item.source.startswith("production"))
    assert set(production.frame["产品ID"]) == {"p-public", "p-secret"}
    assert production.frame.loc[production.frame["产品ID"] == "p-public", "材料成本"].iloc[0] == 11
    assert production.frame.loc[production.frame["产品ID"] == "p-secret", "材料成本"].isna().all()
    assert len(rpc_calls) == 2  # production and process scopes are authorized independently
    sql_calls = [entry for entry in calls if entry[0] == "fetch"]
    production_price = next(entry for entry in sql_calls if "fact_production_batch" in entry[1] and "ANY" in entry[1])
    assert production_price[1].index("product_type_id = ANY") < production_price[1].index("ORDER BY") < production_price[1].index("LIMIT")
    assert production_price[2][-1] == ["p-public"]
    process_price = next(entry for entry in sql_calls
                         if "FROM fact_production_report r" in entry[1] and "ANY" in entry[1])
    assert "LEFT JOIN fact_production_batch b ON b.factory_id=r.factory_id AND b.source_pk=r.batch_id" in process_price[1]
    assert "r.source_pk AS \"来源主键\"" in process_price[1]
    assert "r.material_cost AS \"材料成本\"" in process_price[1]
    process = next(item for item in datasets if item.source.startswith("process"))
    assert list(process.frame["材料成本"]) == [3, 7]


@pytest.mark.asyncio
@pytest.mark.parametrize("bad_costs", [
    {"材料成本": 2, "人工成本": 3, "设备成本": 4, "其他成本": 1, "总成本": 100},
    {"材料成本": 2, "人工成本": None, "设备成本": 4, "其他成本": 1, "总成本": 7},
    {"材料成本": -1, "人工成本": 3, "设备成本": 4, "其他成本": 1, "总成本": 7},
    {"材料成本": float("nan"), "人工成本": 3, "设备成本": 4, "其他成本": 1, "总成本": 8},
], ids=["sum-mismatch", "missing-component", "negative-component", "nan-component"])
async def test_production_cost_requires_complete_finite_nonnegative_reconciled_components(monkeypatch, bad_costs):
    calls, rpc_calls = [], []
    rows = _rows()
    rows["production-priced"] = [{
        "日期": "2026-01-01", "来源主键": "b-public", "产品ID": "p-public",
        "批次": "B1", "单位": "kg", "产量": 10, **bad_costs,
        "数据同步时间": "2026-01-02",
    }]
    pool = _Pool(rows, calls)
    monkeypatch.setattr(sources.config, "get_pg_pool", _pool_getter(pool))
    monkeypatch.setattr(sources.config, "get_cretas_pool", _pool_getter(pool))
    monkeypatch.setattr(settled_sources, "load", lambda *args, **kwargs: _empty_settled())
    monkeypatch.setattr(cost_authorization, "java_base_url", lambda: "http://java.test:10010")
    monkeypatch.setattr(cost_authorization.httpx, "AsyncClient",
                        lambda **_: _MockAsyncClient(rpc_calls, {
                            "success": True, "data": {"factoryId": "F006", "userId": 42,
                            "allowedProductIds": ["p-public"], "unscopedCostAllowed": False}}))
    datasets, _, gaps = await sources.load("F006", _request_options(), True, auth_request=_request())
    production = next(item for item in datasets if item.source.startswith("production"))
    public = production.frame.loc[production.frame["产品ID"] == "p-public"]
    assert public["产量"].iloc[0] == 10
    cost_columns = [column for column in production.frame.columns if "成本" in column]
    assert not cost_columns or public[cost_columns].isna().all().all()
    assert any("成本" in str(gap) for gap in gaps)


@pytest.mark.asyncio
async def test_process_without_batch_product_mapping_never_reads_cost(monkeypatch):
    calls, rpc_calls = [], []
    rows = _rows()
    rows["process-ids"] = [
        {"日期": "2026-01-01", "来源主键": "r-unmapped", "产品ID": None,
         "工序": "mix", "数据同步时间": "2026-01-02"},
    ]
    rows["process-priced"] = [
        {"日期": "2026-01-01", "来源主键": "r-unmapped", "产品ID": None,
         "工序": "mix", "材料成本": 999, "数据同步时间": "2026-01-02"},
    ]
    pool = _Pool(rows, calls)
    monkeypatch.setattr(sources.config, "get_pg_pool", _pool_getter(pool))
    monkeypatch.setattr(sources.config, "get_cretas_pool", _pool_getter(pool))
    monkeypatch.setattr(settled_sources, "load", lambda *args, **kwargs: _empty_settled())
    monkeypatch.setattr(cost_authorization, "java_base_url", lambda: "http://java.test:10010")
    monkeypatch.setattr(cost_authorization.httpx, "AsyncClient",
                        lambda **_: _MockAsyncClient(rpc_calls, {
                            "success": True, "data": {"factoryId": "F006", "userId": 42,
                            "allowedProductIds": [], "unscopedCostAllowed": False}}))
    datasets, _, _ = await sources.load("F006", _request_options(), True, auth_request=_request())
    process = next(item for item in datasets if item.source.startswith("process"))
    assert "材料成本" not in process.frame
    assert not any(entry[0] == "fetch" and "FROM fact_production_report r" in entry[1]
                   and "r.material_cost" in entry[1] and "ANY" in entry[1] for entry in calls)


@pytest.mark.asyncio
async def test_settled_partial_cost_and_quantity_facts_are_both_retained(monkeypatch):
    calls, rpc_calls = [], []
    rows = _rows()
    rows["production-ids"] = [
        {"日期": "2026-01-01", "来源主键": "completed-1", "产品ID": "p-public", "单位": "kg", "产量": 10,
         "数据同步时间": "2026-01-02"},
    ]
    rows["production-priced"] = [
        {"日期": "2026-01-01", "来源主键": "completed-1", "产品ID": "p-public", "单位": "kg", "产量": 10,
         "材料成本": 50, "人工成本": 0, "设备成本": 0, "其他成本": 0, "总成本": 50,
         "数据同步时间": "2026-01-02"},
    ]
    pool = _Pool(rows, calls)
    monkeypatch.setattr(sources.config, "get_pg_pool", _pool_getter(pool))
    monkeypatch.setattr(sources.config, "get_cretas_pool", _pool_getter(pool))
    monkeypatch.setattr(cost_authorization, "java_base_url", lambda: "http://java.test:10010")
    monkeypatch.setattr(cost_authorization.httpx, "AsyncClient", lambda **_: _MockAsyncClient(rpc_calls, {
        "success": True, "data": {"factoryId": "F006", "userId": 42,
        "allowedProductIds": ["p-public"], "unscopedCostAllowed": False}}))
    async def partial(*args, **kwargs):
        return [engine.Dataset(pd.DataFrame([
            {"来源主键": "p1", "产品ID": "p-public", "产量": 10, "材料成本": 3},
            {"来源主键": "p2", "产品ID": "p-public", "产量": 20},
            {"来源主键": "completed-1", "产品ID": "p-public", "产量": 10, "材料成本": 999},
        ]), "settled-production · partial")], [], []
    monkeypatch.setattr(settled_sources, "load", partial)
    datasets, _, _ = await sources.load("F006", _request_options(), True, auth_request=_request())
    all_frames = pd.concat([item.frame for item in datasets if not item.frame.empty], ignore_index=True, sort=False)
    assert set(all_frames["来源主键"].dropna()) >= {"completed-1", "p1", "p2"}
    assert all_frames.loc[all_frames["来源主键"] == "p1", "材料成本"].notna().any()
    assert all_frames.loc[all_frames["来源主键"] == "p2", "产量"].notna().any()
    assert list(all_frames.loc[all_frames["来源主键"] == "completed-1", "材料成本"].dropna()) == [50]


@pytest.mark.asyncio
async def test_authorize_batches_513_ids_use_two_rpc_requests(monkeypatch):
    calls = []
    async def authorize(request, factory_id, product_ids, **kwargs):
        calls.append(list(product_ids))
        return cost_authorization.CostAuthorization(factory_id, 42, frozenset(product_ids), False)
    monkeypatch.setattr(cost_authorization, "authorize", authorize)
    result = await cost_authorization.authorize_batches(_request(), "F006", [f"p-{i}" for i in range(513)])
    assert [len(chunk) for chunk in calls] == [512, 1]
    assert len(result.allowed_product_ids) == 513


@pytest.mark.asyncio
@pytest.mark.parametrize("auth_request", [_request(token=""), None], ids=["no-bearer", "no-auth-request"])
async def test_source_without_current_auth_never_selects_full_cost_and_reports_gap(monkeypatch, auth_request):
    calls = []
    pool = _Pool(_rows(), calls)
    monkeypatch.setattr(sources.config, "get_pg_pool", _pool_getter(pool))
    monkeypatch.setattr(sources.config, "get_cretas_pool", _pool_getter(pool))
    monkeypatch.setattr(settled_sources, "load", lambda *args, **kwargs: _empty_settled())
    datasets, errors, gaps = await sources.load("F006", _request_options(), True, auth_request=auth_request)
    production = next(item for item in datasets if item.source.startswith("production"))
    assert "产量" in production.frame and not any("成本" in col for col in production.frame.columns)
    # Cost gaps are deliberately returned in the structured third channel;
    # ordinary read errors remain separate and must not expose SQL details.
    assert any("成本授权" in str(gap) for gap in gaps)
    assert not any(entry[0] == "fetch" and "fact_production_batch" in entry[1] and "材料成本" in entry[1]
                   for entry in calls)


async def _empty_settled():
    return [], [], []
