from contextlib import asynccontextmanager
from decimal import Decimal

import pytest

from smartbi.api.financial_dashboard import FactoryRecommendationRequest
from smartbi.services.factory_charts import engine
from smartbi.services.factory_charts import operational_sources as sources


def request(**kwargs):
    return FactoryRecommendationRequest(use_ai=False, **kwargs)


def row(**values):
    return values


def test_reconcile_uses_java_discount_rounding_and_never_reuses_quoted_gross_price():
    rows = [
        row(**{"订单行ID": "line-1", "订单ID": "order-1", "日期": "2026-10-01", "产品ID": "sku-1",
               "产品": "产品一", "单位": "kg", "数量": Decimal("3"), "单价": Decimal("10.005"),
               "折扣率": Decimal("10"), "报价含税单价": Decimal("99"), "订单总额": Decimal("30.02")}),
        row(**{"订单行ID": "line-2", "订单ID": "order-1", "日期": "2026-10-01", "产品ID": None,
               "产品": None, "单位": "kg", "数量": Decimal("1"), "单价": Decimal("3"),
               "折扣率": Decimal("0"), "报价含税单价": Decimal("99"), "订单总额": Decimal("30.02")}),
    ]

    output, gaps = sources.reconcile_sales_rows(rows)

    assert gaps == []
    assert [r["订单行小计"] for r in output] == [Decimal("27.02"), Decimal("3.00")]
    assert output[1]["产品ID"] is None and output[1]["产品"] is None


def test_reconcile_quarantines_missing_id_duplicate_and_unreconciled_orders_without_allocation():
    rows = [
        row(**{"订单行ID": "duplicate", "订单ID": "bad-1", "数量": 1, "单价": 10, "折扣率": 0, "订单总额": 10}),
        row(**{"订单行ID": "duplicate", "订单ID": "bad-2", "数量": 1, "单价": 10, "折扣率": 0, "订单总额": 9}),
        row(**{"订单行ID": None, "订单ID": "bad-3", "数量": 1, "单价": 1, "折扣率": 0, "订单总额": 1}),
        row(**{"订单行ID": "unreconciled", "订单ID": "bad-4", "数量": 1, "单价": 10, "折扣率": 0, "订单总额": 9}),
    ]

    output, gaps = sources.reconcile_sales_rows(rows)

    assert output == []
    assert len(gaps) == 1
    assert "未分摊订单差额" in gaps[0]["missing"][0]
    assert {"duplicate line id", "missing line id", "line sum does not reconcile to order total"} \
        <= {entry.split("张订单", 1)[1].split("，", 1)[0] for entry in gaps[0]["missing"]}


class FakeConnection:
    def __init__(self, rows_by_marker, calls):
        self.rows_by_marker = rows_by_marker
        self.calls = calls

    async def execute(self, sql, *params):
        self.calls.append(("execute", sql, params))

    @asynccontextmanager
    async def transaction(self):
        yield self

    async def fetch(self, sql, *params):
        self.calls.append(("fetch", sql, params))
        for marker, rows in sorted(self.rows_by_marker.items(), key=lambda item: len(item[0]), reverse=True):
            if marker in sql:
                return rows
        raise AssertionError(f"unexpected SQL: {sql}")


class FakePool:
    def __init__(self, rows_by_marker, calls):
        self.connection = FakeConnection(rows_by_marker, calls)

    @asynccontextmanager
    async def acquire(self):
        yield self.connection


@pytest.mark.asyncio
async def test_load_is_tenant_scoped_role_stripped_and_preserves_wip_units(monkeypatch):
    calls = []
    rows = {
        "sales_order_items i": [{"订单行ID": "l1", "订单ID": "o1", "日期": "2026-10-01", "产品ID": "p1",
                                  "产品": "A", "单位": "kg", "数量": 2}],
        "smart_bi_department_data": [{"日期": "2026-10-01", "部门": "生产", "部门ID": "d1",
                                        "人数": 3, "销售金额": 100, "销售目标": 120, "部门成本": 20}],
        "production_reports pr": [{"报工ID": 1, "日期": "2026-10-01", "投入量": 10, "投入单位": "kg",
                                     "产出量": 9, "产出单位": "kg", "来源WIP号": "W1"}],
        "semi_finished_inventory sfi": [{"WIP库存ID": 2, "工序批次号": "W1", "可用量": 4, "单位": "kg",
                                           "状态": "AVAILABLE"}],
        "semi_finished_inventory_transactions tx": [{"WIP流水ID": 3, "WIP库存ID": 2, "流水数量": 1,
                                                       "单位": "kg", "来源引用": "W1"}],
    }
    pool = FakePool(rows, calls)

    async def get_pool():
        return pool

    monkeypatch.setattr(sources.config, "get_cretas_pool", get_pool)
    datasets, errors, gaps = await sources.load(
        "F006", request(period_type="month_range", year=2026, start_month=10, end_month=10), False
    )

    assert errors == []
    frames = {dataset.source.split(" · ", 1)[0]: dataset.frame for dataset in datasets}
    department_dataset = next(dataset for dataset in datasets if dataset.source.startswith("department"))
    assert set(frames) == {"sales-sku", "department", "wip-report", "wip-ledger"}
    assert department_dataset.financial is False and department_dataset.currency == ""
    assert "销售金额" not in frames["department"]
    assert "部门成本" not in frames["department"]
    assert "数量" in frames["sales-sku"] and "订单总额" not in frames["sales-sku"]
    assert frames["wip-report"].iloc[0]["投入单位"] == "kg"
    assert any(g["id"] == "sales.sku.reconciliation" for g in gaps)
    assert any(g["id"] == "wip.inventory.snapshot" for g in gaps)

    fetches = [call for call in calls if call[0] == "fetch"]
    assert len(fetches) == 4
    for _, sql, params in fetches:
        assert "$1" in sql and "F006" not in sql
        assert params[0] == "F006"
        assert params[1:] == (sources.date(2026, 10, 1), sources.date(2026, 10, 31))
        assert "unit_cost" not in sql.lower()
        if "smart_bi_department_data" in sql:
            assert sql == sources.DEPARTMENT_NO_PRICE_SQL
            assert not any(column in sql for column in ('sales_amount', 'sales_target', 'cost_amount'))
    assert all(call[0] != "execute" or call[2] == ("F006",) for call in calls)


@pytest.mark.asyncio
async def test_load_price_role_emits_only_reconciled_sales_and_cny_metadata(monkeypatch):
    calls = []
    rows = {
        "sales_order_items i": [
            {"订单行ID": "l1", "订单ID": "o1", "日期": "2026-10-01", "产品ID": "p1", "产品": "A",
             "单位": "kg", "数量": Decimal("2"), "单价": Decimal("5"), "折扣率": Decimal("10"),
             "报价含税单价": Decimal("99"), "订单总额": Decimal("9")},
            {"订单行ID": "l2", "订单ID": "o2", "日期": "2026-10-01", "产品ID": "p2", "产品": "B",
             "单位": "kg", "数量": Decimal("1"), "单价": Decimal("5"), "折扣率": Decimal("0"),
             "报价含税单价": Decimal("99"), "订单总额": Decimal("6")},
        ],
        "smart_bi_department_data": [
            {"日期": "2026-10-01", "部门": "生产", "部门ID": "d1", "人数": 3,
             "销售金额": 100, "销售目标": 120, "部门成本": 20},
            {"日期": "2026-10-02", "部门": "销售", "部门ID": "d2", "人数": 2,
             "销售金额": 50, "销售目标": 60, "部门成本": 10},
        ],
        "production_reports pr": [],
        "semi_finished_inventory sfi": [],
        "semi_finished_inventory_transactions tx": [],
    }
    pool = FakePool(rows, calls)

    async def get_pool():
        return pool

    monkeypatch.setattr(sources.config, "get_cretas_pool", get_pool)
    datasets, errors, gaps = await sources.load("F006", request(period_type="all"), True)

    assert errors == []
    sales = next(dataset for dataset in datasets if dataset.source.startswith("sales-sku"))
    assert len(sales.frame) == 1
    assert sales.frame.iloc[0]["订单行小计"] == Decimal("9.00")
    assert sales.currency == "CNY"
    department = next(dataset for dataset in datasets if dataset.source.startswith("department"))
    assert department.currency == "CNY" and department.financial is True
    assert {"实际（元）", "预算（元）", "部门成本（元）"} <= set(department.frame.columns)
    candidates, _ = engine.build([department], request(period_type="all"), True)
    assert any(candidate["id"].endswith("finance.business_unit_analysis") for candidate in candidates)
    assert any(g["id"] == "sales.sku.reconciliation" for g in gaps)


def test_sql_contracts_are_parameterized_and_do_not_allocate_costs():
    for sql in (sources.SALES_SKU_SQL, sources.SALES_SKU_NO_PRICE_SQL, sources.DEPARTMENT_SQL,
                sources.WIP_REPORT_SQL, sources.WIP_INVENTORY_SQL, sources.WIP_TRANSACTION_SQL):
        assert "$1" in sql and "factory_id = $1" in sql
        assert "F006" not in sql
    assert "unit_cost" not in sources.WIP_REPORT_SQL.lower()
    assert "50" not in sources.WIP_REPORT_SQL
    assert 'i.id AS "订单行ID"' in sources.SALES_SKU_SQL
    assert 'i.id AS "订单行ID"' in sources.SALES_SKU_NO_PRICE_SQL
    assert 'i.id AS "行ID"' not in sources.SALES_SKU_SQL


@pytest.mark.asyncio
async def test_actual_operational_load_reaches_engine_sales_sku_candidate_with_canonical_line_id(monkeypatch):
    calls = []
    rows = {
        "sales_order_items i": [{"订单行ID": "line-851", "订单ID": "order-851", "日期": "2026-10-01",
                                  "产品ID": "sku-851", "产品": "QA SKU", "单位": "kg", "数量": Decimal("3"),
                                  "单价": Decimal("10"), "折扣率": Decimal("0"), "报价含税单价": Decimal("10"),
                                  "订单总额": Decimal("30")}],
        "smart_bi_department_data": [], "production_reports pr": [],
        "semi_finished_inventory sfi": [], "semi_finished_inventory_transactions tx": [],
    }
    async def get_pool():
        return FakePool(rows, calls)
    monkeypatch.setattr(sources.config, "get_cretas_pool", get_pool)
    datasets, errors, gaps = await sources.load("F006", request(period_type="all"), True)
    assert errors == [] and not gaps
    sales = next(dataset for dataset in datasets if dataset.source.startswith("sales-sku"))
    assert list(sales.frame["订单行ID"]) == ["line-851"]
    from smartbi.services.factory_charts.business_metrics import build as business_build
    direct, direct_gaps = business_build(sales.frame, engine.columns(sales.frame), request(period_type="all"), True, sales.source)
    assert direct, direct_gaps
    sku = next(candidate for candidate in direct if candidate["id"].startswith("sales.sku"))
    assert sku["source"].startswith("sales-sku")
    assert sku["kpis"] and sku["kpis"][0]["value"] == "30"


def test_wip_flow_emits_only_a_forward_recorded_conservation_edge():
    reports = [{
        "报工ID": 11, "__factory_id": "F006", "日期": "2026-10-01",
        "投入量": Decimal("5"), "投入单位": "kg", "来源WIP号": "W1",
        "工序批次号": "W2",
    }]
    inventory = [{
        "WIP库存ID": 7, "__factory_id": "F006", "工序批次号": "W1", "单位": "kg",
    }]
    transactions = [{
        "WIP流水ID": 8, "__factory_id": "F006", "WIP库存ID": 7,
        "流水类型": "OUT", "来源类型": "SECONDARY_CONSUME", "流水数量": Decimal("-5"),
        "单位": "kg", "报工ID": 11,
    }]

    edges, gaps = sources.assemble_wip_flows(reports, inventory, transactions, "F006")

    assert gaps == []
    assert edges == [{
        "日期": "2026-10-01", "来源节点": "W1", "去向节点": "W2",
        "流量": Decimal("5"), "单位": "kg", "流转记录ID": 8, "报工ID": 11,
    }]


@pytest.mark.parametrize("change", [
    {"流水数量": Decimal("-4")},
    {"单位": "箱"},
    {"单位": None},
    {"WIP库存ID": 999},
    {"__factory_id": "F007"},
])
def test_wip_flow_quarantines_nonconserving_or_unverifiable_rows(change):
    reports = [{
        "报工ID": 11, "__factory_id": "F006", "投入量": Decimal("5"), "投入单位": "kg",
        "来源WIP号": "W1", "工序批次号": "W2",
    }]
    inventory = [{"WIP库存ID": 7, "__factory_id": "F006", "工序批次号": "W1", "单位": "kg"}]
    tx = {
        "WIP流水ID": 8, "__factory_id": "F006", "WIP库存ID": 7,
        "流水类型": "OUT", "来源类型": "SECONDARY_CONSUME", "流水数量": Decimal("-5"),
        "单位": "kg", "报工ID": 11,
    }
    tx.update(change)

    edges, gaps = sources.assemble_wip_flows(reports, inventory, [tx], "F006")

    assert edges == []
    assert gaps and gaps[0]["id"] == "wip.flow.conservation"


def test_wip_sql_uses_yield_fact_and_exclusive_end_boundary():
    assert "pr.report_type = 'YIELD'" in sources.WIP_REPORT_SQL
    assert "pr.deleted_at IS NULL" in sources.WIP_REPORT_SQL
    assert "sfi.deleted_at IS NULL" in sources.WIP_TRANSACTION_SQL
    for sql in (sources.SALES_SKU_SQL, sources.SALES_SKU_NO_PRICE_SQL, sources.DEPARTMENT_SQL,
                sources.WIP_REPORT_SQL, sources.WIP_TRANSACTION_SQL):
        assert "< ($3::date + INTERVAL '1 day')" in sql
    assert "tx.created_at::date" not in sources.WIP_TRANSACTION_SQL
    assert 'pr.factory_id AS "__factory_id"' in sources.WIP_REPORT_SQL
    assert 'sfi.factory_id AS "__factory_id"' in sources.WIP_INVENTORY_SQL
    assert 'tx.factory_id AS "__factory_id"' in sources.WIP_TRANSACTION_SQL
