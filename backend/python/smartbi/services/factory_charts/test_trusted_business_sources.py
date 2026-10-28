from contextlib import asynccontextmanager
from decimal import Decimal

import pytest

from smartbi.api.financial_dashboard import FactoryRecommendationRequest
from smartbi.services.factory_charts import trusted_business_sources as sources


def request(**kwargs):
    return FactoryRecommendationRequest(use_ai=False, **kwargs)


def test_sql_contracts_keep_customer_source_distinct_and_bind_every_tenant():
    assert 'c.source AS "客户来源"' in sources.CUSTOMER_SOURCE_SALES_SQL
    assert 'c.factory_id = so.factory_id' in sources.CUSTOMER_SOURCE_SALES_SQL
    assert 'c.source AS "客户来源"' in sources.CUSTOMER_SOURCE_SALES_NO_PRICE_SQL
    for restricted in ("unit_price", "discount_rate", "total_amount"):
        assert restricted not in sources.CUSTOMER_SOURCE_SALES_NO_PRICE_SQL
    assert '渠道' not in sources.CUSTOMER_SOURCE_SALES_SQL
    for sql in (sources.CUSTOMER_SOURCE_SALES_SQL, sources.CUSTOMER_SOURCE_SALES_NO_PRICE_SQL,
                sources.PURCHASE_BATCH_LINEAGE_SQL, sources.FINISHED_GOODS_INVENTORY_SQL,
                sources.FINISHED_GOODS_SHIPMENT_SQL):
        assert "$1" in sql and "F006" not in sql
        assert "set_config" not in sql
    assert "source_doc_type" in sources.PURCHASE_BATCH_LINEAGE_SQL
    assert "source_doc_id" in sources.PURCHASE_BATCH_LINEAGE_SQL
    assert 'mb.material_type_id AS "批次物料ID"' in sources.PURCHASE_BATCH_LINEAGE_SQL
    assert 'rmt.id AS "物料主数据ID"' in sources.PURCHASE_BATCH_LINEAGE_SQL
    assert 'rmt.factory_id = pr.factory_id' in sources.PURCHASE_BATCH_LINEAGE_SQL
    assert "mb.factory_id = pr.factory_id" in sources.PURCHASE_BATCH_LINEAGE_SQL
    assert "mb.deleted_at IS NULL" in sources.PURCHASE_BATCH_LINEAGE_SQL
    assert "fgb.status = 'AVAILABLE'" in sources.FINISHED_GOODS_INVENTORY_SQL
    assert "produced_quantity - COALESCE(fgb.shipped_quantity, 0)" in sources.FINISHED_GOODS_INVENTORY_SQL
    assert "sales_delivery_item_batch_allocations" in sources.FINISHED_GOODS_SHIPMENT_SQL
    assert "sdr.status IN ('SHIPPED', 'DELIVERED')" in sources.FINISHED_GOODS_SHIPMENT_SQL


def test_purchase_lineage_requires_reverse_source_and_snapshot_units():
    rows = [
        {"收货单ID": "r1", "收货工厂ID": "F006", "收货状态": "CONFIRMED", "收货行ID": 1,
         "物料批次ID": "mb1", "物料ID": "mat1", "批次物料ID": "mat1", "物料主数据ID": "mat1", "物料主数据工厂ID": "F006", "批次工厂ID": "F006", "批次来源类型": "PURCHASE_RECEIVE",
         "批次来源单据ID": "r1", "收货数量": Decimal("2"), "收货单位": "箱",
         "批次数量单位": "kg", "批次入库数量": Decimal("20"), "包装单位快照": "箱",
         "库存基础单位快照": "kg", "包装换算因子快照": Decimal("10"),
         "库存数量快照": Decimal("20")},
        {"收货单ID": "r2", "收货工厂ID": "F006", "收货状态": "CONFIRMED", "收货行ID": 2,
         "物料批次ID": "mb2", "物料ID": "mat2", "批次物料ID": "mat2", "物料主数据ID": "mat2", "物料主数据工厂ID": "F006", "批次工厂ID": "F006", "批次来源类型": "MANUAL_ADJUST",
         "批次来源单据ID": "r2", "收货数量": Decimal("1"), "收货单位": "kg",
         "批次数量单位": "kg", "批次入库数量": Decimal("1")},
        {"收货单ID": "r3", "收货工厂ID": "F006", "收货状态": "CONFIRMED", "收货行ID": 3,
         "物料批次ID": "mb3", "物料ID": "mat3", "批次物料ID": "mat3", "物料主数据ID": "mat3", "物料主数据工厂ID": "F006", "批次工厂ID": "F006", "批次来源类型": "PURCHASE_RECEIVE",
         "批次来源单据ID": "other", "收货数量": Decimal("1"), "收货单位": "kg",
         "批次数量单位": "kg", "批次入库数量": Decimal("1")},
    ]
    qualified, gaps = sources.qualify_purchase_batch_rows(rows, "F006")
    assert [row["物料批次ID"] for row in qualified] == ["mb1"]
    assert qualified[0]["收货数量"] == Decimal("2")
    assert qualified[0]["收货单位"] == "箱"
    assert qualified[0]["数量"] == Decimal("20")
    assert qualified[0]["数量单位"] == "kg"
    assert qualified[0]["物料（单位）"] == "mat1（kg）"
    assert gaps and "来源类型不是PURCHASE_RECEIVE" in " ".join(gaps[0]["missing"])
    assert "来源单据未反向指向收货单" in " ".join(gaps[0]["missing"])


def test_purchase_lineage_does_not_guess_units_when_snapshot_is_missing():
    row = {"收货单ID": "r1", "收货工厂ID": "F006", "收货状态": "CONFIRMED", "收货行ID": 1,
           "物料批次ID": "mb1", "物料ID": "mat1", "批次物料ID": "mat1", "物料主数据ID": "mat1", "物料主数据工厂ID": "F006", "批次工厂ID": "F006", "批次来源类型": "PURCHASE_RECEIVE",
           "批次来源单据ID": "r1", "收货数量": Decimal("2"), "收货单位": "箱",
           "批次数量单位": "kg", "批次入库数量": Decimal("2")}
    qualified, gaps = sources.qualify_purchase_batch_rows([row], "F006")
    assert qualified == []
    assert "单位不一致" in gaps[0]["missing"][0]


def test_purchase_lineage_rejects_cross_material_batch_match():
    row = {"收货单ID": "r1", "收货工厂ID": "F006", "收货状态": "CONFIRMED", "收货行ID": 1,
           "物料批次ID": "mb1", "物料ID": "mat1", "批次物料ID": "mat2", "物料主数据ID": "mat1", "物料主数据工厂ID": "F006", "批次工厂ID": "F006",
           "批次来源类型": "PURCHASE_RECEIVE", "批次来源单据ID": "r1", "收货数量": Decimal("2"),
           "收货单位": "kg", "批次数量单位": "kg", "批次入库数量": Decimal("2"),
           "包装单位快照": "kg", "库存基础单位快照": "kg", "包装换算因子快照": Decimal("1"),
           "库存数量快照": Decimal("2")}
    qualified, gaps = sources.qualify_purchase_batch_rows([row], "F006")
    assert qualified == []
    assert "物料类型不一致" in gaps[0]["missing"][0]


def test_purchase_lineage_requires_snapshot_to_equal_batch_receipt_quantity():
    row = {"收货单ID": "r1", "收货工厂ID": "F006", "收货状态": "CONFIRMED", "收货行ID": 1,
           "物料批次ID": "mb1", "物料ID": "mat1", "批次物料ID": "mat1", "物料主数据ID": "mat1", "物料主数据工厂ID": "F006", "批次工厂ID": "F006", "批次来源类型": "PURCHASE_RECEIVE",
           "批次来源单据ID": "r1", "收货数量": Decimal("2"), "收货单位": "箱",
           "批次数量单位": "kg", "批次入库数量": Decimal("10"), "包装单位快照": "箱",
           "库存基础单位快照": "kg", "包装换算因子快照": Decimal("10"),
           "库存数量快照": Decimal("20")}
    qualified, gaps = sources.qualify_purchase_batch_rows([row], "F006")
    assert qualified == []
    assert "批次入库数量不一致" in gaps[0]["missing"][0]


def test_purchase_lineage_rejects_missing_or_cross_factory_material_master():
    row = {"收货单ID": "r1", "收货工厂ID": "F006", "收货状态": "CONFIRMED", "收货行ID": 1,
           "物料批次ID": "mb1", "物料ID": "mat1", "批次物料ID": "mat1", "批次工厂ID": "F006",
           "批次来源类型": "PURCHASE_RECEIVE", "批次来源单据ID": "r1", "收货数量": Decimal("1"),
           "收货单位": "kg", "批次数量单位": "kg", "批次入库数量": Decimal("1"),
           "物料主数据ID": "mat1", "物料主数据工厂ID": "F007"}
    qualified, gaps = sources.qualify_purchase_batch_rows([row], "F006")
    assert qualified == []
    assert "物料主数据缺失、删除或跨工厂" in gaps[0]["missing"][0]


def test_current_finished_goods_snapshot_is_independent_and_conserving():
    rows = [
        {"成品批次ID": "fg1", "工厂ID": "F006", "状态": "AVAILABLE", "单位": "kg",
         "已生产数量": Decimal("100"), "已发货数量": Decimal("20"), "已预留数量": Decimal("10"),
         "可用数量": Decimal("70")},
        {"成品批次ID": "fg2", "工厂ID": "F006", "状态": "AVAILABLE", "单位": "kg",
         "已生产数量": Decimal("100"), "已发货数量": Decimal("20"), "已预留数量": Decimal("10"),
         "可用数量": Decimal("71")},
        {"成品批次ID": "fg3", "工厂ID": "F007", "状态": "AVAILABLE", "单位": "kg",
         "已生产数量": Decimal("1"), "已发货数量": Decimal("0"), "已预留数量": Decimal("0"),
         "可用数量": Decimal("1")},
    ]
    qualified, gaps = sources.qualify_finished_goods_rows(rows, "F006")
    assert [row["成品批次ID"] for row in qualified] == ["fg1"]
    assert any("余额未守恒" in item for item in gaps[0]["missing"])
    assert any("租户不一致" in item for item in gaps[0]["missing"])


def test_finished_goods_missing_status_and_duplicate_ids_quarantine_all_rows():
    base = {"成品批次ID": "fg1", "工厂ID": "F006", "单位": "kg", "已生产数量": Decimal("10"),
            "已发货数量": Decimal("1"), "已预留数量": Decimal("1"), "可用数量": Decimal("8")}
    _, missing_status_gaps = sources.qualify_finished_goods_rows([base], "F006")
    assert any("非AVAILABLE" in item for gap in missing_status_gaps for item in gap["missing"])
    qualified, gaps = sources.qualify_finished_goods_rows([base, {**base, "状态": "AVAILABLE"}], "F006")
    assert qualified == []
    assert any("成品批次重复" in item for item in gaps[0]["missing"])


def test_customer_source_sales_quarantines_missing_acquisition_source_before_reconciliation():
    rows = [{"订单行ID": "l1", "订单ID": "o1", "客户来源": None, "数量": Decimal("1"),
             "单价": Decimal("5"), "折扣率": Decimal("0"), "订单总额": Decimal("5")}]
    qualified, gaps = sources.qualify_customer_source_sales_rows(rows, True)
    assert qualified == []
    assert gaps and gaps[0]["id"] == "sales.customer_source.coverage"


def test_finished_goods_shipment_requires_same_sku_unit_and_unique_relation():
    base = {"发货行ID": "di1", "发货单ID": "d1", "订单ID": "so1", "客户ID": "c1", "发货状态": "SHIPPED",
            "成品批次ID": "fg1", "产品ID": "p1", "发货数量": Decimal("2"), "发货单位": "kg",
            "申报发货数量": Decimal("2"), "申报发货单位": "kg",
            "发货关系ID": "a1", "批次工厂ID": "F006", "批次产品ID": "p1", "批次单位": "kg"}
    bad_sku = {**base, "发货关系ID": "a2", "批次产品ID": "p2"}
    bad_unit = {**base, "发货关系ID": "a3", "批次单位": "箱"}
    qualified, gaps = sources.qualify_finished_goods_shipments([base, bad_sku, bad_unit, base], "F006")
    assert qualified == []
    assert any("SKU不一致" in item for item in gaps[0]["missing"])
    assert any("单位不一致" in item for item in gaps[0]["missing"])
    assert any("关系重复" in item for item in gaps[0]["missing"])


def test_finished_goods_shipment_rejects_direct_and_allocation_path_overlap():
    base = {"发货行ID": "di1", "发货单ID": "d1", "订单ID": "so1", "客户ID": "c1", "发货状态": "SHIPPED",
            "成品批次ID": "fg1", "产品ID": "p1", "发货数量": Decimal("2"), "发货单位": "kg",
            "申报发货数量": Decimal("2"), "申报发货单位": "kg",
            "批次工厂ID": "F006", "批次产品ID": "p1", "批次单位": "kg"}
    direct = {**base, "发货关系ID": "di1", "关系来源": "DELIVERY_ITEM_FK"}
    allocation = {**base, "发货关系ID": "a1", "成品批次ID": "fg2", "关系来源": "BATCH_ALLOCATION"}
    qualified, gaps = sources.qualify_finished_goods_shipments([direct, allocation], "F006")
    assert qualified == []
    assert any("同时存在直接批次和allocation路径" in item for item in gaps[0]["missing"])


def test_finished_goods_shipment_rejects_non_shipped_parent_status():
    row = {"发货行ID": "di1", "发货单ID": "d1", "订单ID": "so1", "客户ID": "c1",
           "发货状态": "PICKED", "成品批次ID": "fg1", "产品ID": "p1", "发货数量": Decimal("2"),
           "发货单位": "kg", "发货关系ID": "a1", "批次工厂ID": "F006", "批次产品ID": "p1",
           "批次单位": "kg", "关系来源": "DELIVERY_ITEM_FK"}
    qualified, gaps = sources.qualify_finished_goods_shipments([row], "F006")
    assert qualified == []
    assert "SHIPPED或DELIVERED" in gaps[0]["missing"][0]


def test_finished_goods_allocation_quantity_must_equal_declared_delivery():
    base = {"发货行ID": "di1", "发货单ID": "d1", "订单ID": "so1", "客户ID": "c1", "发货状态": "SHIPPED",
            "成品批次ID": "fg1", "产品ID": "p1", "发货单位": "kg", "申报发货数量": Decimal("5"),
            "申报发货单位": "kg", "批次工厂ID": "F006", "批次产品ID": "p1", "批次单位": "kg",
            "关系来源": "BATCH_ALLOCATION"}
    rows = [{**base, "发货关系ID": "a1", "发货数量": Decimal("3")},
            {**base, "发货关系ID": "a2", "发货数量": Decimal("3")}]
    qualified, gaps = sources.qualify_finished_goods_shipments(rows, "F006")
    assert qualified == []
    assert any("allocation合计未守恒" in item for item in gaps[0]["missing"])


class FakeConnection:
    def __init__(self, rows, calls):
        self.rows = rows
        self.calls = calls

    async def execute(self, sql, *params):
        self.calls.append(("execute", sql, params))

    @asynccontextmanager
    async def transaction(self):
        yield self

    async def fetch(self, sql, *params):
        self.calls.append(("fetch", sql, params))
        if "sales_order_items i" in sql:
            return self.rows["sales"]
        if "purchase_receive_items pri" in sql:
            return self.rows["purchase"]
        if "sales_delivery_item_batch_allocations" in sql:
            return self.rows["shipment"]
        if "finished_goods_batches fgb" in sql:
            return self.rows["inventory"]
        raise AssertionError(sql)


class FakePool:
    def __init__(self, rows, calls):
        self.connection = FakeConnection(rows, calls)

    @asynccontextmanager
    async def acquire(self):
        yield self.connection


@pytest.mark.asyncio
async def test_load_is_tenant_period_bound_and_exposes_only_qualified_facts(monkeypatch):
    calls = []
    rows = {
        "sales": [{"订单行ID": "l1", "订单ID": "o1", "客户ID": "c1", "客户来源": "REFERRAL",
                   "数量": Decimal("2"), "单价": Decimal("5"), "折扣率": Decimal("0"),
                   "订单总额": Decimal("10"), "单位": "kg"}],
        "purchase": [], "shipment": [], "inventory": [{"成品批次ID": "fg1", "工厂ID": "F006",
                   "状态": "AVAILABLE", "单位": "kg", "已生产数量": Decimal("10"),
                   "已发货数量": Decimal("1"), "已预留数量": Decimal("1"), "可用数量": Decimal("8")}],
    }

    async def get_pool():
        return FakePool(rows, calls)

    monkeypatch.setattr(sources.config, "get_cretas_pool", get_pool)
    datasets, errors, gaps = await sources.load("F006", request(period_type="all"), True)
    assert errors == []
    assert {dataset.source.split(" · ", 1)[0] for dataset in datasets} == {
        "customer-source-sales", "purchase-batch-lineage", "finished-goods-shipment",
        "finished-goods-inventory",
    }
    sales = next(dataset for dataset in datasets if dataset.source.startswith("customer-source-sales"))
    assert sales.frame.iloc[0]["客户来源"] == "REFERRAL"
    assert sales.frame.iloc[0]["订单行小计"] == Decimal("10.00")
    assert any(call[0] == "execute" and call[2] == ("F006",) for call in calls)
    fetches = [call for call in calls if call[0] == "fetch"]
    assert len(fetches) == 4
    assert all(call[2][0] == "F006" for call in fetches)


@pytest.mark.asyncio
async def test_load_ranged_period_does_not_present_current_fg_as_history(monkeypatch):
    calls = []
    rows = {"sales": [], "purchase": [], "shipment": [], "inventory": []}

    async def get_pool():
        return FakePool(rows, calls)

    monkeypatch.setattr(sources.config, "get_cretas_pool", get_pool)
    datasets, errors, gaps = await sources.load(
        "F006", request(period_type="month_range", year=2026, start_month=2, end_month=2), False
    )
    assert errors == []
    assert not any(dataset.source.startswith("finished-goods-inventory") for dataset in datasets)
    assert any(gap["id"] == "finished_goods.historical_snapshot" for gap in gaps)
    assert all(len(call[2]) == 3 for call in calls if call[0] == "fetch")
