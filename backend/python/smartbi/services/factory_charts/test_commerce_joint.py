from types import SimpleNamespace
from decimal import Decimal

import pandas as pd
import pytest

from . import commerce_joint
from .engine import Dataset, analysis_subjects, build, scope_datasets
from .trusted_business_sources import (
    COMMERCE_JOINT_SOURCE,
    COMMERCE_JOINT_SALES_NO_PRICE_SQL,
    COMMERCE_JOINT_SALES_SQL,
    FINISHED_GOODS_SHIPMENT_SQL,
)


def request(**changes):
    values = dict(period_type="all", year=2026, start_month=1, end_month=12,
                  guided=False, analysis_subject=None, include_optional=True)
    values.update(changes)
    return SimpleNamespace(**values)


def sale(line, order, product="P1", unit="kg", quantity="10", channel="直营",
         customer="C1", total="100", price="10", day="2026-10-01"):
    return {"订单行ID": line, "订单ID": order, "工厂ID": "F006", "日期": day,
            "客户ID": customer, "客户": customer, "产品ID": product, "产品": product,
            "单位": unit, "数量": Decimal(quantity), "渠道": channel,
            "渠道定义列": "cf_smartbi_sales_channel", "单价": Decimal(price),
            "折扣率": Decimal("0"), "订单总额": Decimal(total)}


def shipment(line, order, batch, product="P1", unit="kg", quantity="10",
             relation="d1", source="DELIVERY_ITEM_FK", factory="F006", customer="C1"):
    return {"发货行ID": line + "-delivery", "销售订单行ID": line, "发货单ID": relation,
            "订单ID": order, "客户ID": customer, "发货状态": "SHIPPED", "成品批次ID": batch,
            "产品ID": product, "发货数量": Decimal(quantity), "发货单位": unit,
            "申报发货数量": Decimal(quantity), "申报发货单位": unit,
            "发货关系ID": relation, "关系来源": source, "批次工厂ID": factory,
            "批次产品ID": product, "批次单位": unit}


def allocation(line, order, batch, qty, relation, declared="10", product="P1", unit="kg"):
    row = shipment(line, order, batch, product, unit, qty, relation,
                   "BATCH_ALLOCATION")
    row["申报发货数量"] = Decimal(declared)
    return row


def inventory(batch, product="P1", unit="kg", available="10", factory="F006"):
    available = Decimal(available)
    return {"日期": "2026-10-29", "成品批次ID": batch, "工厂ID": factory,
            "批次": batch, "产品ID": product, "产品": product, "单位": unit,
            "已生产数量": available + Decimal("5"), "已发货数量": Decimal("0"),
            "已预留数量": Decimal("5"), "可用数量": available, "状态": "AVAILABLE"}


def prepared(sales, shipments, inventory_rows, can_price=True, req=None):
    return commerce_joint.prepare(sales, shipments, inventory_rows, "F006",
                                  req or request(), can_price)


def orders(frame):
    return frame[frame["事实类型"].eq("ORDER_LINE")]


def test_sales_stock_and_customer_channel_join_exact_line_and_units():
    sales = [sale("l1", "o1", total="100", quantity="10"),
             sale("l2", "o2", customer="C2", channel="经销", total="45", quantity="5", price="9")]
    shipments = [allocation("l1", "o1", "b1", "6", "a1"),
                 allocation("l1", "o1", "b2", "4", "a2"),
                 shipment("l2", "o2", "b3", quantity="5", relation="d3", customer="C2")]
    frame, gaps = prepared(sales, shipments, [inventory("b1", available="10"),
                                               inventory("b2", available="5"),
                                               inventory("b3", available="8"),
                                               inventory("b4", available="99")])
    assert not gaps
    assert frame is not None and len(orders(frame)) == 2
    assert orders(frame).set_index("订单行ID").loc["l1", "已核对发货数量"] == Decimal("10")
    assert orders(frame).set_index("订单行ID").loc["l1", "关联成品批次ID"] == "b1,b2"
    batches = frame[frame["事实类型"].eq("INVENTORY_BATCH")]
    assert batches.set_index("成品批次ID")["当前可用数量"].to_dict() == {
        "b1": Decimal(10), "b2": Decimal(5), "b3": Decimal(8), "b4": Decimal(99)}
    assert batches["订单行ID"].isna().all()
    assert frame[frame["事实类型"].eq("SHIPMENT_RELATION")]["发货数量"].sum() == Decimal(15)
    candidates, build_gaps = commerce_joint.build(frame, request(), True)
    assert not build_gaps
    assert {item["id"].split("@", 1)[0] for item in candidates} == {"sales.stock", "customer.channel"}
    stock = next(item for item in candidates if item["id"].startswith("sales.stock@"))
    assert next(kpi["value"] for kpi in stock["kpis"] if kpi["label"] == "当前可用库存") == 122.0
    channel = next(item for item in candidates if item["id"].startswith("customer.channel@"))
    assert channel["unit"] == "元"
    assert channel["chart"]["echartsOption"]["yAxis"]["name"] == "元"
    assert channel["engine"]["sourceQuantityUnit"] == "kg"
    assert channel["kpis"][0]["value"] == "145.0" or channel["kpis"][0]["value"] == 145.0
    assert all("C" in row[0] for row in channel["tableData"]["rows"])


def test_same_sku_different_orders_do_not_cross_join():
    sales = [sale("l1", "o1", total="10", quantity="1"),
             sale("l2", "o2", total="20", quantity="2")]
    shipments = [shipment("l1", "o1", "b1", quantity="1", relation="d1"),
                 shipment("l2", "o2", "b2", quantity="2", relation="d2")]
    frame, gaps = prepared(sales, shipments, [inventory("b1", available="3"), inventory("b2", available="4")])
    assert not gaps and frame is not None
    assert set(orders(frame)["订单ID"]) == {"o1", "o2"}
    assert orders(frame).set_index("订单行ID").loc["l1", "关联成品批次ID"] == "b1"
    assert orders(frame).set_index("订单行ID").loc["l2", "关联成品批次ID"] == "b2"


def test_shipment_customer_must_match_the_order_customer():
    frame, gaps = prepared(
        [sale("l1", "o1", customer="C1", total="10", quantity="1")],
        [shipment("l1", "o1", "b1", customer="C2", quantity="1", relation="d1")],
        [inventory("b1", available="3")],
    )
    assert frame is None
    assert any("客户" in item["missing"][0] for item in gaps)


def test_missing_order_line_key_is_a_visible_gap_not_a_silent_drop():
    bad = shipment("l1", "o1", "b1", quantity="1", relation="d1")
    bad["销售订单行ID"] = None
    frame, gaps = prepared(
        [sale("l1", "o1", quantity="1", total="10")],
        [bad],
        [inventory("b1", available="3")],
    )
    assert frame is None
    assert any("销售订单行ID" in item["missing"][0] for item in gaps)


def test_shipment_quantity_cannot_exceed_the_order_line_quantity():
    frame, gaps = prepared(
        [sale("l1", "o1", quantity="1", total="10")],
        [shipment("l1", "o1", "b1", quantity="2", relation="d1")],
        [inventory("b1", available="3")],
    )
    assert frame is None
    assert any("数量守恒" in item["missing"][0] for item in gaps)


def test_joint_charts_group_by_ids_not_colliding_display_labels_or_repeat_stock():
    first = sale("l1", "o1", product="P1", customer="C1", channel="直营", quantity="1", total="10")
    second = sale("l2", "o2", product="P2", customer="C1", channel="直营", quantity="1", total="20", price="20")
    first["产品"] = second["产品"] = "同名产品"
    frame, gaps = prepared(
        [first, second],
        [shipment("l1", "o1", "b1", product="P1", quantity="1", relation="d1"),
         shipment("l2", "o2", "b2", product="P2", quantity="1", relation="d2")],
        [inventory("b1", product="P1", available="2"), inventory("b2", product="P2", available="3")],
    )
    assert frame is not None and not gaps
    candidates, build_gaps = commerce_joint.build(frame, request(), True)
    assert not build_gaps
    stock = next(item for item in candidates if item["id"].startswith("sales.stock@"))
    assert sorted(stock["chart"]["echartsOption"]["series"][2]["data"]) == [2.0, 3.0]
    assert stock["kpis"][2]["value"] == 5.0
    channel = next(item for item in candidates if item["id"].startswith("customer.channel@"))
    assert len(channel["tableData"]["rows"]) == 2
    assert channel["unit"] == "元" and channel["engine"]["sourceQuantityUnit"] == "kg"


def test_same_batch_split_across_order_lines_emits_one_stock_fact_and_details_are_openable():
    sales = [sale("l1", "o1", product="P1", quantity="1", total="10"),
             sale("l2", "o2", product="P1", quantity="1", total="20", price="20")]
    shipments = [shipment("l1", "o1", "b1", product="P1", quantity="1", relation="d1"),
                 shipment("l2", "o2", "b1", product="P1", quantity="1", relation="d2")]
    frame, gaps = prepared(sales, shipments, [inventory("b1", product="P1", available="3")])
    assert frame is not None and not gaps
    batches = frame[frame["事实类型"].eq("INVENTORY_BATCH")]
    assert batches["当前可用数量"].tolist() == [Decimal("3")]
    assert batches["库存事实批次ID"].tolist() == ["b1"]
    assert orders(frame)["当前可用数量"].isna().all()
    candidates, build_gaps = commerce_joint.build(frame, request(), True)
    assert not build_gaps
    stock = next(item for item in candidates if item["id"].startswith("sales.stock@"))
    assert stock["kpis"][2]["value"] == 3.0


def test_engine_dataset_source_is_preserved_through_detail_registration_and_page():
    frame, gaps = prepared(
        [sale("l1", "o1", quantity="1", total="10")],
        [shipment("l1", "o1", "b1", quantity="1", relation="d1")],
        [inventory("b1", available="3")],
    )
    assert frame is not None and not gaps
    dataset = Dataset(frame, COMMERCE_JOINT_SOURCE, financial=True, currency="CNY")
    candidates, build_gaps = build([dataset], request(), True)
    assert not build_gaps
    assert {candidate["source"] for candidate in candidates} == {dataset.source}
    assert {tuple(candidate["id"].split("@", 1)[0].split(".")[-2:]) for candidate in candidates} == {
        ("sales", "stock"), ("customer", "channel")
    }

    from . import details
    owner = ("F006", 1309, "factory_super_admin")
    details.clear_cache()
    try:
        registered = details.register(candidates, [dataset], request(), owner, True)
        assert all(item["source"] == dataset.source and item.get("detailContext", {}).get("id")
                   for item in registered)
        for item in registered:
            page = details.page({"context_id": item["detailContext"]["id"], "page": 1, "page_size": 20},
                                owner, True, True)
            assert page["data"]["source"] == dataset.source
            assert page["data"]["rows"]
            assert {str(row["单位"]).strip() for row in page["data"]["rows"]} == {"kg"}
            assert item["detailContext"]["drilldown"] is False
            assert {row["事实类型"] for row in page["data"]["rows"]} == {
                "ORDER_LINE", "SHIPMENT_RELATION", "INVENTORY_BATCH"}
    finally:
        details.clear_cache()


def test_sql_channel_is_explicit_column_and_no_price_query_never_reads_amount():
    from .trusted_business_sources import COMMERCE_JOINT_SALES_NO_PRICE_SQL, COMMERCE_JOINT_SALES_SQL

    for sql in (COMMERCE_JOINT_SALES_SQL, COMMERCE_JOINT_SALES_NO_PRICE_SQL):
        assert "column_name = 'cf_smartbi_sales_channel'" in sql
        assert "so.cf_smartbi_sales_channel" in sql
        assert "to_jsonb(so)" not in sql
    assert "total_amount" not in COMMERCE_JOINT_SALES_NO_PRICE_SQL
    assert "订单总额" not in COMMERCE_JOINT_SALES_NO_PRICE_SQL
    assert FINISHED_GOODS_SHIPMENT_SQL.count("COALESCE(sdr.record_role, 'LEGACY') <> 'MASTER'") == 2


def test_customer_owned_batch_must_match_order_customer():
    batch = inventory("b1", available="3")
    batch.update({"所有权": "CUSTOMER_OWNED", "所有客户ID": "C2"})
    frame, gaps = prepared(
        [sale("l1", "o1", customer="C1", quantity="1", total="10")],
        [shipment("l1", "o1", "b1", customer="C1", quantity="1", relation="d1")],
        [batch],
    )
    assert frame is None
    assert any("客户" in item["missing"][0] for item in gaps)


def test_duplicate_direct_and_allocation_paths_are_a_gap_not_a_double_count():
    frame, gaps = prepared(
        [sale("l1", "o1", total="10", quantity="1")],
        [shipment("l1", "o1", "b1", quantity="1", relation="d1"),
         allocation("l1", "o1", "b1", "1", "a1", declared="1")],
        [inventory("b1", available="3")])
    assert frame is None
    assert any("直接批次" in item["missing"][0] for item in gaps)


def test_cross_factory_sku_unit_and_missing_values_fail_closed_without_zero_fill():
    bad_factory = shipment("l1", "o1", "b1", quantity="1", relation="d1", factory="F007")
    frame, gaps = prepared([sale("l1", "o1", total="10", quantity="1")], [bad_factory],
                           [inventory("b1", available="3")])
    assert frame is None and gaps
    bad_unit = shipment("l1", "o1", "b1", unit="box", quantity="1", relation="d2")
    frame, gaps = prepared([sale("l1", "o1", total="10", quantity="1")], [bad_unit],
                           [inventory("b1", available="3")])
    assert frame is None and gaps
    missing_channel = sale("l1", "o1", total="10", quantity="1")
    missing_channel["渠道"] = None
    frame, gaps = prepared([missing_channel], [shipment("l1", "o1", "b1", quantity="1", relation="d3")],
                           [inventory("b1", available="3")])
    assert frame is not None and gaps
    candidates, _ = commerce_joint.build(frame, request(), True)
    assert any(item["id"].startswith("sales.stock@") for item in candidates)
    assert not any(item["id"].startswith("customer.channel@") for item in candidates)


def test_no_price_permission_exposes_quantity_only_and_never_fake_amount():
    row = sale("l1", "o1", total="10", quantity="1")
    for key in ("单价", "折扣率", "订单总额"):
        row.pop(key)
    frame, gaps = prepared([row], [shipment("l1", "o1", "b1", quantity="1", relation="d1")],
                           [inventory("b1", available="3")], can_price=False)
    assert not gaps and frame is not None
    candidates, build_gaps = commerce_joint.build(frame, request(), False)
    assert not build_gaps
    channel = next(item for item in candidates if item["id"].startswith("customer.channel@"))
    assert channel["kpis"][0]["unit"] == "kg"
    assert channel["engine"]["sourceQuantityUnit"] == "kg"
    assert "订单行小计" not in frame


def test_current_snapshot_is_not_used_for_historical_period():
    frame, gaps = prepared([sale("l1", "o1", total="10", quantity="1")],
                           [shipment("l1", "o1", "b1", quantity="1", relation="d1")],
                           [inventory("b1", available="3")],
                           req=request(period_type="month_range", year=2026, start_month=10, end_month=10))
    assert frame is not None
    candidates, build_gaps = commerce_joint.build(frame, request(period_type="month_range", year=2026,
                                                                  start_month=10, end_month=10), True)
    assert any(item["id"].startswith("customer.channel@") for item in candidates)
    assert not any(item["id"].startswith("sales.stock@") for item in candidates)
    assert any("当前成品库存" in item["missing"][0] for item in gaps + build_gaps)


def test_customer_channel_survives_when_current_inventory_is_unavailable():
    frame, gaps = prepared(
        [sale("l1", "o1", quantity="1", total="10")],
        [shipment("l1", "o1", "depleted-batch", quantity="1", relation="d1")],
        [],
    )
    assert frame is not None
    candidates, build_gaps = commerce_joint.build(frame, request(), True)
    assert any(item["id"].startswith("customer.channel@") for item in candidates)
    assert not any(item["id"].startswith("sales.stock@") for item in candidates)
    assert any("库存" in item["missing"][0] for item in gaps + build_gaps)


def test_subject_filter_and_candidate_limit_are_fail_closed():
    sales = [sale("l1", "o1", total="10", quantity="1", product="P1")]
    frame, gaps = prepared(sales, [shipment("l1", "o1", "b1", product="P1", quantity="1", relation="d1")],
                           [inventory("b1", product="P1", available="3")])
    assert frame is not None and not gaps
    dataset = Dataset(frame, "commerce-joint · 订单-发货-当前库存/客户-显式渠道联合")
    subject = analysis_subjects([dataset], True)[0]
    scoped, gap, _ = scope_datasets([dataset], request(guided=True, analysis_subject=subject["id"]), [subject], True)
    assert gap is None and len(scoped) == 1
    candidates, build_gaps = build(scoped, request(guided=True, analysis_subject=subject["id"]), True)
    assert candidates and not any(item["id"].startswith("commerce") for item in build_gaps)

    many = []
    for index in range(101):
        row = sale(f"l{index}", f"o{index}", product=f"P{index}", total="1", quantity="1")
        many.append(row)
    many_frame = frame.loc[[0]].copy()
    many_frame = pd.DataFrame([{**row, "事实类型": "ORDER_LINE", "已核对发货数量": Decimal("1"), "发货数量": None, "当前可用数量": None,
                                "关联成品批次ID": "b", "产品（单位）": f"{row['产品ID']}（kg）",
                                "客户渠道产品": f"C / 直营 / {row['产品ID']}（kg）",
                                "事实限定": "test"} for row in many])
    many_frame.attrs["commerce_stock_totals"] = {(f"P{index}", "kg"): Decimal("1") for index in range(101)}
    candidates, build_gaps = commerce_joint.build(many_frame, request(), True)
    assert candidates == []
    assert any("超过100" in item["missing"][0] for item in build_gaps)


def test_same_order_line_can_ship_from_the_same_batch_on_two_deliveries():
    first = shipment("l1", "o1", "b1", quantity="4", relation="d1")
    second = shipment("l1", "o1", "b1", quantity="6", relation="d2")
    first["发货行ID"], second["发货行ID"] = "di1", "di2"
    frame, gaps = prepared([sale("l1", "o1", quantity="10", total="100")],
                           [first, second], [inventory("b1", available="3")])
    assert frame is not None and not gaps
    assert orders(frame)["已核对发货数量"].tolist() == [Decimal(10)]
    assert len(frame[frame["事实类型"].eq("SHIPMENT_RELATION")]) == 2
    assert frame["当前可用数量"].dropna().tolist() == [Decimal(3)]


def test_malformed_inventory_is_a_coverage_gap_and_missing_relationship_identity_is_rejected():
    bad = inventory("b2", available="99")
    bad["产品ID"] = None
    frame, gaps = prepared([sale("l1", "o1", quantity="1", total="10")],
                           [shipment("l1", "o1", "b1", quantity="1", relation="d1")],
                           [inventory("b1", available="3"), bad])
    assert frame is not None and any("SKU" in str(gap) for gap in gaps)
    candidates, _ = commerce_joint.build(frame, request(), True)
    assert not any(item["id"].startswith("sales.stock@") for item in candidates)
    assert any(item["id"].startswith("customer.channel@") for item in candidates)
    for missing in ("成品批次ID", "产品ID", "批次产品ID", "发货单位", "批次单位", "发货单ID", "发货关系ID", "发货行ID"):
        relation = shipment("l1", "o1", "b1", quantity="1", relation="d1")
        relation[missing] = "   "
        if missing == "产品ID":
            relation["批次产品ID"] = None
        rejected, relation_gaps = prepared([sale("l1", "o1", quantity="1", total="10")],
                                           [relation], [inventory("b1", available="3")])
        assert rejected is None and relation_gaps


def test_amount_candidates_for_distinct_quantity_units_have_unique_ids_and_exact_details():
    frame, gaps = prepared(
        [sale("l1", "o1", quantity="1", total="10"),
         sale("l2", "o2", product="P2", unit="box", quantity="1", total="20", price="20")],
        [shipment("l1", "o1", "b1", quantity="1", relation="d1"),
         shipment("l2", "o2", "b2", product="P2", unit="box", quantity="1", relation="d2")],
        [inventory("b1", available="3"), inventory("b2", product="P2", unit="box", available="4")])
    assert frame is not None and not gaps
    dataset = Dataset(frame, COMMERCE_JOINT_SOURCE, financial=True, currency="CNY")
    candidates, gaps = build([dataset], request(), True)
    assert len(candidates) == 4 and len({item["id"] for item in candidates}) == 4
    from . import details
    owner = ("F006", 1309, "factory_super_admin")
    details.clear_cache()
    try:
        for item in details.register(candidates, [dataset], request(), owner, True):
            assert item["detailContext"]["drilldown"] is False
            source_unit = item["engine"].get("sourceQuantityUnit") or item["unit"]
            data = details.page({"context_id": item["detailContext"]["id"]}, owner, True, True)["data"]
            assert {row["单位"] for row in data["rows"]} == {source_unit}
            with pytest.raises(details.DetailError):
                details.page({"context_id": item["detailContext"]["id"], "point": {"name": "unproven-composite-point"}}, owner, True, True)
        fake = {**candidates[0], "source": "upload-999", "engine": {"pointDimension": "客户渠道产品", "pointDrilldown": False}}
        fake_dataset = Dataset(frame, "upload-999", financial=True, currency="CNY")
        assert not details.register([fake], [fake_dataset], request(), owner, True)[0].get("detailContext")
    finally:
        details.clear_cache()


def test_long_customer_and_product_labels_are_bounded_only_on_axis_and_keep_full_data():
    customer = "客户-华东冷链餐饮集团-2026长期合作渠道"
    products = [
        ("P1", "低温熟成去骨鸡腿排超长商品名一号", "3"),
        ("P2", "低温熟成去骨鸡腿排超长商品名二号", "3"),
        ("P3", "低温熟成去骨鸡腿排超长商品名三号", "7"),
    ]
    sales = []
    shipments = []
    inventory_rows = []
    for index, (product_id, product_name, available) in enumerate(products, start=1):
        line_id = f"long-label-line-{index}"
        order_id = f"long-label-order-{index}"
        row = sale(line_id, order_id, product=product_id, customer=customer,
                   quantity="1", total="10", channel="直营长渠道")
        row["产品"] = product_name
        sales.append(row)
        shipments.append(shipment(line_id, order_id, f"long-label-batch-{index}",
                                  product=product_id, quantity="1", relation=f"long-label-delivery-{index}",
                                  customer=customer))
        inventory_rows.append(inventory(f"long-label-batch-{index}", product=product_id,
                                        available=available))

    frame, gaps = prepared(sales, shipments, inventory_rows)
    assert frame is not None and not gaps
    candidates, build_gaps = commerce_joint.build(frame, request(), True)
    assert not build_gaps

    stock = next(item for item in candidates if item["id"].startswith("sales.stock@"))
    channel = next(item for item in candidates if item["id"].startswith("customer.channel@"))
    expected_products = [f"{name}（kg）" for _, name, _ in products]
    expected_channels = [f"{customer} / 直营长渠道 / {name}（kg）" for _, name, _ in products]

    assert stock["chart"]["echartsOption"]["xAxis"]["data"] == expected_products
    assert [row[0] for row in stock["tableData"]["rows"]] == expected_products
    assert stock["chart"]["echartsOption"]["series"][2]["data"] == [3.0, 3.0, 7.0]
    assert stock["kpis"][2]["value"] == 13.0
    assert stock["chart"]["echartsOption"]["tooltip"]["trigger"] == "axis"

    assert channel["chart"]["echartsOption"]["xAxis"]["data"] == expected_channels
    assert [row[0] for row in channel["tableData"]["rows"]] == expected_channels
    assert channel["chart"]["echartsOption"]["series"][0]["data"] == [10.0, 10.0, 10.0]
    assert channel["kpis"][0]["value"] == 30.0
    assert channel["unit"] == "元"
    assert channel["engine"]["sourceQuantityUnit"] == "kg"

    for item, expected_rotate in ((stock, 35), (channel, 0)):
        option = item["chart"]["echartsOption"]
        axis_label = option["xAxis"]["axisLabel"]
        assert option["grid"]["containLabel"] is True
        assert axis_label["rotate"] == expected_rotate
        assert 0 < axis_label["width"] <= 120
        assert axis_label["overflow"] == "truncate"
        assert axis_label["ellipsis"] == "..."
