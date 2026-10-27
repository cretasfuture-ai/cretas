import pandas as pd

from smartbi.services.factory_charts.business_metrics import build


def req():
    return type("Request", (), {"period_type": "month_range", "year": 2026, "start_month": 1, "end_month": 1})()


def test_customer_sales_and_exactly_reconciled_sku_are_candidates():
    frame = pd.DataFrame([
        {"日期": "2026-01-01", "客户": "C1", "SKU": "A", "订单行ID": "L1", "订单ID": "O1", "销售金额": 12, "订单行小计": 12, "订单总额": 12},
        {"日期": "2026-01-02", "客户": "C1", "SKU": "B", "订单行ID": "L2", "订单ID": "O2", "销售金额": 8, "订单行小计": 8, "订单总额": 8},
    ])
    cols = {"date": "日期", "customer": "客户", "product": "SKU", "order_id": "订单ID",
            "sales": "销售金额", "sku_subtotal": "订单行小计", "order_total": "订单总额"}
    candidates, gaps = build(frame, cols, req(), True, "sales")
    assert {c["id"] for c in candidates} == {"sales.customer:bar_horizontal", "sales.sku:bar_horizontal"}
    assert not gaps
    assert candidates[1]["chart"]["echartsOption"]["series"][0]["data"]


def test_sku_total_is_never_allocated_when_order_reconciliation_is_missing():
    frame = pd.DataFrame([{"日期": "2026-01-01", "SKU": "A", "销售金额": 20, "订单ID": "O1"}])
    candidates, gaps = build(frame, {"date": "日期", "product": "SKU", "sales": "销售金额", "order_id": "订单ID"}, req(), True, "sales")
    assert not any(c["id"].startswith("sales.sku:") for c in candidates)
    gap = next(g for g in gaps if g["id"] == "sales.sku")
    assert "订单行小计" in " ".join(gap["missing"])


def test_sku_sales_aggregates_by_id_but_displays_names_without_merging_same_names():
    frame = pd.DataFrame([
        {"产品ID": "sku-1", "产品": "同名产品", "订单行ID": "L1", "订单ID": "O1",
         "订单行小计": 12, "订单总额": 12},
        {"产品ID": "sku-2", "产品": "同名产品", "订单行ID": "L2", "订单ID": "O2",
         "订单行小计": 8, "订单总额": 8},
    ])
    candidates, gaps = build(frame, {"sku": "产品ID", "product": "产品", "order_id": "订单ID",
                                     "line_id": "订单行ID", "sku_subtotal": "订单行小计",
                                     "order_total": "订单总额"}, req(), True, "sales")
    assert not gaps
    points = next(c for c in candidates if c["id"].startswith("sales.sku"))["tableData"]
    assert {row["label"] for row in points} == {"同名产品（sku-1）", "同名产品（sku-2）"}
    assert {row["value"] for row in points} == {8.0, 12.0}


def test_sku_sales_explicitly_marks_missing_product_name_fallback():
    frame = pd.DataFrame([
        {"产品ID": "sku-1", "产品": None, "订单行ID": "L1", "订单ID": "O1",
         "订单行小计": 12, "订单总额": 12},
    ])
    candidates, gaps = build(frame, {"sku": "产品ID", "product": "产品", "order_id": "订单ID",
                                     "line_id": "订单行ID", "sku_subtotal": "订单行小计",
                                     "order_total": "订单总额"}, req(), True, "sales")
    assert not gaps
    candidate = next(c for c in candidates if c["id"].startswith("sales.sku"))
    assert candidate["tableData"] == [{"label": "sku-1", "value": 12.0}]
    assert any("缺少产品名称" in item for item in candidate["findings"]["attention"])


def test_quality_trend_requires_complete_nonnegative_operands():
    frame = pd.DataFrame([{"日期": "2026-01-01", "合格数": 9, "不良数": 1}, {"日期": "2026-01-02", "合格数": None, "不良数": 1}])
    candidates, gaps = build(frame, {"date": "日期", "pass": "合格数", "fail": "不良数"}, req(), False, "quality")
    assert not any(c["id"].startswith("quality.time-trend") for c in candidates)
    assert any(g["id"] == "quality.time-trend" for g in gaps)


def test_process_cost_does_not_zero_missing_operands_and_oee_is_optional():
    frame = pd.DataFrame([{"工序": "切", "材料成本": 3, "人工成本": None}])
    candidates, gaps = build(frame, {"process": "工序", "material_cost": "材料成本", "labor_cost": "人工成本"}, req(), True, "process")
    assert not any(c["id"].startswith("process.full-cost") for c in candidates)
    assert any(g["id"] == "process.full-cost" for g in gaps)


def test_weighted_purchase_quality_oee_turnover_and_full_cost_positive_values():
    purchase = pd.DataFrame({"原料": ["A", "A"], "入库数量": [1, 9], "单位": ["kg", "kg"], "入库单价": [10, 20], "价格单位": ["kg", "kg"]})
    c, g = build(purchase, {}, req(), True, "purchase")
    assert c[0]["tableData"][0]["value"] == 19
    quality = pd.DataFrame({"日期": ["d1", "d2"], "合格数": [9, 45], "不良数": [1, 45], "检验数": [10, 90]})
    c, g = build(quality, {}, req(), False, "quality")
    assert c[0]["tableData"][-1]["value"] == 50
    assert c[0]["kpis"][0]["value"] == '54'
    oee = pd.DataFrame({"计划运行分钟": [100], "实际运行分钟": [90], "理想周期秒": [43.2], "良品数": [95], "总数": [100]})
    c, g = build(oee, {}, type("R", (), {"include_optional": True})(), False, "oee")
    assert round(c[0]["tableData"][0]["value"], 1) == 68.4
    inv = pd.DataFrame({"会计期间": ['2026-01'], "平均库存金额": [100], "同期销货成本": [200]})
    c, g = build(inv, {}, type("R", (), {"include_optional": True})(), True, "inventory")
    assert c[0]["tableData"][0]["value"] == 2
    cost = pd.DataFrame({"工序": ["切"], "材料成本": [1], "人工成本": [2], "设备成本": [3], "其他成本": [4], "总成本": [10]})
    c, g = build(cost, {}, req(), True, "process")
    assert c[0]["tableData"][0]["value"] == 10


def test_numeric_negative_gates_and_optional_off():
    bad = pd.DataFrame({"原料": ["A"], "入库数量": [0], "单位": ["kg"], "入库单价": [10], "价格单位": ["g"]})
    c, gaps = build(bad, {}, req(), True, "purchase")
    assert not any(x["id"].startswith("purchase.price") for x in c)
    assert any(x["id"] == "purchase.price" for x in gaps)
    optional = pd.DataFrame({"产品": ["A"] * 10, "批次": [str(i) for i in range(10)], "日期": [f"d{i}" for i in range(10)], "产量": [1] * 10})
    c, _ = build(optional, {}, type("R", (), {"include_optional": False})(), False, "production")
    assert not any(x["id"].startswith(("oee", "inventory.turnover", "anomaly.")) for x in c)
