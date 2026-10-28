from decimal import Decimal

import pandas as pd

from smartbi.api.financial_dashboard import FactoryRecommendationRequest
from smartbi.services.factory_charts.engine import Dataset, build
from smartbi.services.factory_charts import trusted_business_metrics as metrics


def request(**kwargs):
    return FactoryRecommendationRequest(use_ai=False, **kwargs)


def trusted_datasets():
    return [
        Dataset(pd.DataFrame([
            {"日期": "2026-10-01", "订单ID": "so-1", "订单行ID": "line-1", "客户来源": "REFERRAL",
             "订单行小计": Decimal("20"), "单位": "kg", "事实限定": "已逐单对账"},
            {"日期": "2026-10-02", "订单ID": "so-2", "订单行ID": "line-2", "客户来源": "WEBSITE",
             "订单行小计": Decimal("5"), "单位": "箱", "事实限定": "已逐单对账"},
        ]), "customer-source-sales · 客户来源获客归因（非订单销售渠道）", financial=True, currency="CNY"),
        Dataset(pd.DataFrame([
            {"日期": "2026-10-01", "收货单ID": "r-1", "收货行ID": 1, "收货状态": "CONFIRMED",
             "收货数量": Decimal("2"), "收货单位": "箱", "物料": "原料A", "批次数量单位": "kg",
             "批次工厂ID": "F006", "批次来源类型": "PURCHASE_RECEIVE", "批次来源单据ID": "r-1",
             "物料ID": "mat-1", "批次物料ID": "mat-1", "批次入库数量": Decimal("20"),
             "包装单位快照": "箱", "库存数量快照": Decimal("20"), "库存基础单位快照": "kg",
             "包装换算因子快照": Decimal("10"), "数量": Decimal("20"), "数量单位": "kg",
             "物料（单位）": "原料A（kg）", "事实限定": "已确认收货 + 收货行物料批次双向来源校验"},
        ]), "purchase-batch-lineage · 已确认收货与物料批次双向来源"),
        Dataset(pd.DataFrame([
            {"日期": "2026-10-01", "发货行ID": "di-1", "发货单ID": "d-1", "成品批次ID": "fg-1",
             "产品ID": "sku-1", "发货数量": Decimal("3"), "发货单位": "kg", "申报发货数量": Decimal("3"),
             "申报发货单位": "kg", "批次工厂ID": "F006", "批次产品ID": "sku-1", "批次单位": "kg",
             "发货状态": "SHIPPED", "数量": Decimal("3"), "数量单位": "kg", "产品（单位）": "sku-1（kg）",
             "关系来源": "DELIVERY_ITEM_FK", "事实限定": "已记录发货行/批次关系"},
        ]), "finished-goods-shipment · 已记录成品批次发货关系（不做单位换算）"),
        Dataset(pd.DataFrame([
            {"日期": "2026-10-28", "成品批次ID": "fg-1", "工厂ID": "F006", "产品ID": "sku-1",
             "单位": "kg", "已生产数量": Decimal("10"), "已发货数量": Decimal("2"),
             "已预留数量": Decimal("1"), "可用数量": Decimal("7"), "状态": "AVAILABLE",
             "数量": Decimal("7"), "数量单位": "kg", "产品（单位）": "sku-1（kg）",
             "事实限定": "当前AVAILABLE成品批次余额；非历史倒推"},
        ]), "finished-goods-inventory · 独立成品库存当前快照（不倒推历史）"),
    ]


def test_engine_builds_one_named_trusted_candidate_per_qualified_source_with_evidence_contracts():
    candidates, gaps = build(trusted_datasets(), request(period_type="all"), True)
    assert not gaps
    by_prefix = {candidate["source"].split(" · ", 1)[0]: candidate for candidate in candidates}
    assert set(by_prefix) == {
        "customer-source-sales", "purchase-batch-lineage",
        "finished-goods-shipment", "finished-goods-inventory",
    }
    for prefix, candidate in by_prefix.items():
        metadata = candidate["metadata"]
        assert metadata["trusted"] is True
        assert metadata["source"] == candidate["source"]
        assert metadata["fact"] and metadata["detail"] and metadata["role"]
        assert metadata["period"]
        assert metadata["unit"] in {"元", "数量"}
        assert candidate["tableData"]
        assert candidate["kpis"]
        assert candidate["source"].startswith(prefix + " · ")
    customer = by_prefix["customer-source-sales"]
    assert "非订单销售渠道" in "；".join(customer["metadata"]["limitations"])
    assert {row["label"]: row["value"] for row in customer["tableData"]} == {
        "REFERRAL": 20.0, "WEBSITE": 5.0,
    }
    assert by_prefix["purchase-batch-lineage"]["tableData"][0]["value"] == 20.0
    assert by_prefix["purchase-batch-lineage"]["tableData"][0]["label"] == "原料A（kg）"
    assert by_prefix["finished-goods-inventory"]["tableData"][0]["value"] == 7.0


def test_trusted_sources_never_fall_back_to_generic_numeric_id_metrics():
    dataset = Dataset(pd.DataFrame([{
        "成品批次ID": 1001, "工厂ID": "F006", "状态": "AVAILABLE", "未知数字": 999,
    }]), "finished-goods-inventory · 独立成品库存当前快照（不倒推历史）")
    candidates, gaps = build([dataset], request(period_type="all"), True)
    assert candidates == []
    assert any("finished_goods.inventory.current" in gap["id"] for gap in gaps)


def test_customer_source_role_and_period_guards_are_fail_closed():
    dataset = trusted_datasets()[0]
    denied, denied_gaps = build([dataset], request(period_type="all"), False)
    assert denied == []
    assert denied_gaps and "权限" in "；".join(denied_gaps[0]["missing"])
    ranged = request(period_type="month_range", year=2026, start_month=2, end_month=2)
    outside, outside_gaps = build([dataset], ranged, True)
    assert outside == []
    assert outside_gaps and "期间" in "；".join(outside_gaps[0]["missing"])


def test_purchase_and_fg_guards_are_not_recomputed_from_unqualified_rows():
    purchase = trusted_datasets()[1]
    purchase.frame.loc[0, "库存数量快照"] = Decimal("3")
    fg = trusted_datasets()[3]
    fg.frame.loc[0, "状态"] = None
    candidates, gaps = build([purchase, fg], request(period_type="all"), True)
    assert candidates == []
    assert any("快照守恒" in gap["title"] for gap in gaps)
    assert any("AVAILABLE" in "；".join(gap["missing"]) for gap in gaps)


def test_trusted_metric_module_does_not_claim_unknown_source_as_catalogue():
    frame = pd.DataFrame([{"数量": 7, "ID": 12}])
    assert metrics.build(frame, {}, request(period_type="all"), True, "unknown · source") == ([], [])
