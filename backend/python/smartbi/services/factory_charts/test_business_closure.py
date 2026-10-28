from types import SimpleNamespace

import pandas as pd

from .business_metrics import build


def req(**kw):
    values = {"include_optional": False}
    values.update(kw)
    return SimpleNamespace(**values)


def candidate(candidates, prefix):
    return next(c for c in candidates if c["id"].startswith(prefix))


def test_quality_uses_weighted_denominator_and_does_not_average_rates():
    frame = pd.DataFrame({"日期": ["d1", "d2"], "合格数": [9, 45],
                          "不良数": [1, 45], "检验数": [10, 90]})
    candidates, gaps = build(frame, {}, req(), False, "quality")
    chart = candidate(candidates, "quality.time-trend")
    assert float(chart["kpis"][0]["value"]) == 54.0


def test_purchase_price_is_weighted_and_unit_mismatch_is_rejected():
    frame = pd.DataFrame({"原料": ["A", "A"], "入库数量": [1, 9],
                          "单位": ["kg", "kg"], "入库单价": [10, 20],
                          "价格单位": ["kg", "kg"]})
    candidates, gaps = build(frame, {}, req(), True, "purchase")
    assert candidate(candidates, "purchase.price")["tableData"][0]["value"] == 19
    assert not gaps

    mismatch = frame.copy()
    mismatch.loc[1, "价格单位"] = "g"
    candidates, gaps = build(mismatch, {}, req(), True, "purchase")
    assert not any(c["id"].startswith("purchase.price") for c in candidates)
    assert any(g["id"] == "purchase.price" for g in gaps)


def test_oee_requires_one_cycle_and_valid_quality_bounds():
    frame = pd.DataFrame({"计划运行分钟": [100], "实际运行分钟": [90],
                          "理想周期秒": [43.2], "良品数": [95], "总数": [100]})
    candidates, gaps = build(frame, {}, req(include_optional=True), False, "oee")
    oee = candidate(candidates, "production.oee")
    assert round(float(next(k["value"] for k in oee["kpis"] if k["label"] == "OEE")), 1) == 68.4
    assert not gaps

    different_cycles = pd.DataFrame({"计划运行分钟": [100, 100], "实际运行分钟": [90, 90],
                                     "理想周期秒": [0.72, 0.8], "良品数": [95, 95],
                                     "总数": [100, 100]})
    candidates, gaps = build(different_cycles, {}, req(include_optional=True), False, "oee")
    assert not any(c["id"] == "production.oee" for c in candidates)
    assert any(g["id"] == "production.oee" for g in gaps)

    invalid = frame.copy()
    invalid.loc[0, "良品数"] = 101
    candidates, gaps = build(invalid, {}, req(include_optional=True), False, "oee")
    assert not any(c["id"] == "production.oee" for c in candidates)
    assert any(g["id"] == "production.oee" for g in gaps)


def test_turnover_deduplicates_same_period_and_requires_price_permission():
    frame = pd.DataFrame({"会计期间": ["2026-01", "2026-01"],
                          "平均库存金额": [100, 100], "同期销货成本": [200, 200]})
    candidates, gaps = build(frame, {}, req(include_optional=True), True, "inventory")
    assert candidate(candidates, "stock.turnover")["tableData"][0]["value"] == 2
    assert not gaps

    candidates, gaps = build(frame, {}, req(include_optional=True), False, "inventory")
    assert not any(c["id"] == "stock.turnover" for c in candidates)
    assert any(g["id"] == "stock.turnover" for g in gaps)


def test_process_full_cost_and_loss_require_reconciliation():
    cost = pd.DataFrame({"工序": ["切"], "材料成本": [1], "人工成本": [2],
                         "设备成本": [3], "其他成本": [4], "总成本": [10]})
    candidates, gaps = build(cost, {}, req(), True, "process")
    assert candidate(candidates, "process.full-cost")["tableData"][0]["value"] == 10
    assert not any(g["id"] == "process.full-cost" for g in gaps)

    loss = pd.DataFrame({"日期": ["d1", "d2"], "工序": ["切", "切"], "单位": ["kg", "kg"],
                         "期初在制品": [10, 10], "投入量": [100, 100], "产量": [90, 95],
                         "损耗量": [5, 3], "期末在制品": [15, 12]})
    candidates, gaps = build(loss, {}, req(), False, "loss")
    assert candidate(candidates, "process.loss")["tableData"]
    assert not any(g["id"] == "process.loss" for g in gaps)

    broken = loss.copy()
    broken.loc[1, "期末在制品"] = 99
    candidates, gaps = build(broken, {}, req(), False, "loss")
    assert not any(c["id"].startswith("process.loss") for c in candidates)
    assert any(g["id"] == "process.loss" for g in gaps)


def test_fused_series_requires_unique_batch_and_shared_product_unit():
    frame = pd.DataFrame({"日期": ["d1", "d2"], "产品": ["P", "P"], "单位": ["kg", "kg"],
                          "批次": ["b1", "b2"], "产量": [100, 110], "良品数": [95, 105],
                          "总成本": [10, 11]})
    candidates, gaps = build(frame, {}, req(), True, "production")
    assert candidate(candidates, "production.fused@P@kg")["tableData"]["headers"]
    assert not any(g["id"] == "production.fused" for g in gaps)

    duplicate_batch = frame.copy()
    duplicate_batch.loc[1, "批次"] = "b1"
    candidates, gaps = build(duplicate_batch, {}, req(), True, "production")
    assert not any(c["id"].startswith("production.fused") for c in candidates)
    assert any(g["id"] == "production.fused" for g in gaps)


def test_sku_sales_requires_unique_lines_and_saved_order_reconciliation():
    frame = pd.DataFrame({"订单号": ["o1", "o1"], "订单行ID": ["l1", "l2"],
                          "SKU": ["s1", "s2"], "订单行小计": [30, 70], "订单总额": [100, 100]})
    candidates, gaps = build(frame, {}, req(), True, "sales")
    assert candidate(candidates, "sales.sku")["tableData"]
    assert not gaps

    duplicate = frame.copy()
    duplicate.loc[1, "订单行ID"] = "l1"
    candidates, gaps = build(duplicate, {}, req(), True, "sales")
    assert not any(c["id"] == "sales.sku" for c in candidates)
    assert any(g["id"] == "sales.sku" for g in gaps)

    invalid_number = frame.copy()
    invalid_number.loc[0, "订单行小计"] = "not-a-number"
    candidates, gaps = build(invalid_number, {}, req(), True, "sales")
    assert not any(c["id"] == "sales.sku" for c in candidates)
    assert any(g["id"] == "sales.sku" for g in gaps)
