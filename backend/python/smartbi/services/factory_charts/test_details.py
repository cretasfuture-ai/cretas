import time

import pandas as pd
import pytest

from . import details


class Request:
    period_type = "month_range"
    year = 2026
    start_month = 1
    end_month = 1


class RequestAll:
    period_type = "all"
    year = None


def dataset():
    return details_dataset(pd.DataFrame([
        {"日期": "2026-01-01", "产品": "A", "单位": "kg", "产量": 2, "总成本": 8, "OEE": .9},
        {"日期": "2026-01-02", "产品": "B", "单位": "kg", "产量": 3, "总成本": 12, "OEE": .8},
        {"日期": "2026-02-01", "产品": "C", "单位": "kg", "产量": 99, "总成本": 99, "OEE": .7},
    ]), "production · upload")


def details_dataset(frame, source):
    return {"frame": frame, "source": source}


@pytest.fixture(autouse=True)
def clean():
    details.clear_cache()
    yield
    details.clear_cache()


def candidate():
    return {"id": "production.output:line", "title": "产量", "source": "production · upload",
            "chart": {"echartsOption": {"xAxis": {"data": ["2026-01-01", "2026-01-02"]}}},
            "tableData": [{"label": "2026-01-01", "value": 2}]}


def test_register_freezes_exact_period_and_pages_original_rows():
    owner = ("F006", 12, "factory_super_admin")
    got = details.register([candidate()], [dataset()], Request(), owner, True)
    context = got[0]["detailContext"]["id"]
    result = details.page({"context_id": context, "page": 1, "page_size": 1}, owner, True, True)
    assert result["success"] is True
    assert result["data"]["total"] == 2
    assert result["data"]["rows"][0]["产品"] == "A"
    assert result["data"]["hasMore"] is True
    result = details.page({"context_id": context, "page": 2, "page_size": 1}, owner, True, True)
    assert result["data"]["rows"][0]["产品"] == "B"
    assert "C" not in str(result)


def test_point_and_manual_filter_are_bounded_and_price_is_hidden():
    owner = ("F006", 12, "factory_super_admin")
    context = details.register([candidate()], [dataset()], Request(), owner, True)[0]["detailContext"]["id"]
    result = details.page({"context_id": context, "page": 1, "page_size": 10,
                           "filters": [{"field": "产品", "value": "B"}],
                           "point": {"name": "2026-01-02"}}, owner, False, True)
    assert result["data"]["total"] == 1
    assert result["data"]["rows"][0]["产品"] == "B"
    assert "总成本" not in result["data"]["columns"]
    with pytest.raises(details.DetailError) as exc:
        details.page({"context_id": context, "page": 1, "page_size": 10,
                      "filters": [{"field": "秘密字段", "value": "x"}]}, owner, True, True)
    assert exc.value.status_code == 422
    with pytest.raises(details.DetailError) as exc:
        details.page({"context_id": context, "page": 1, "page_size": 10,
                      "point": {"name": "missing"}}, owner, True, True)
    assert exc.value.status_code == 422


def test_owner_finance_and_expiry_guards():
    owner = ("F006", 12, "factory_super_admin")
    financial_candidate = {**candidate(), "source": "cashflow · upload", "domain": "财务"}
    financial_dataset = {**dataset(), "source": "cashflow · upload"}
    context = details.register([financial_candidate], [financial_dataset], Request(), owner, True)[0]["detailContext"]["id"]
    with pytest.raises(details.DetailError) as exc:
        details.page({"context_id": context, "page": 1, "page_size": 1}, ("F001", 12, "factory_super_admin"), True, True)
    assert exc.value.status_code == 403
    with pytest.raises(details.DetailError) as exc:
        details.page({"context_id": context, "page": 1, "page_size": 1}, owner, False, True)
    assert exc.value.status_code == 403
    frame = details._FRAMES[context]
    frame.expires_at = time.monotonic() - 1
    with pytest.raises(details.DetailError) as exc:
        details.requires_finance(context, owner)
    assert exc.value.status_code == 410


def test_unavailable_oversized_frame_does_not_advertise_context(monkeypatch):
    owner = ("F006", 12, "factory_super_admin")
    monkeypatch.setattr(details, "MAX_FRAME_BYTES", 1)
    got = details.register([candidate()], [dataset()], Request(), owner, True)
    assert "detailContext" not in got[0]


def test_series_name_is_metric_and_month_point_matches_daily_source():
    owner = ("F006", 12, "factory_super_admin")
    item = {**candidate(), "unit": "kg"}
    context = details.register([item], [dataset()], Request(), owner, True)[0]["detailContext"]["id"]
    result = details.page({"context_id": context, "page": 1, "page_size": 10,
                           "point": {"name": "2026-01", "seriesName": "产量"}}, owner, True, True)
    assert result["data"]["total"] == 2
    assert {row["单位"] for row in result["data"]["rows"]} == {"kg"}


def test_candidate_unit_is_an_exact_scope_for_multi_unit_source():
    owner = ("F006", 12, "factory_super_admin")
    frame = pd.DataFrame([
        {"日期": "2026-01-01", "产品": "A", "单位": "kg", "产量": 2},
        {"日期": "2026-01-01", "产品": "A", "单位": "盒", "产量": 20},
    ])
    source = details_dataset(frame, "production · multi")
    kg = {**candidate(), "source": "production · multi", "unit": "kg"}
    box = {**candidate(), "source": "production · multi", "unit": "盒", "id": "production.output:box"}
    result = details.register([kg, box], [source], Request(), owner, True)
    assert details.page({"context_id": result[0]["detailContext"]["id"], "page": 1, "page_size": 10}, owner, True, True)["data"]["rows"][0]["单位"] == "kg"
    assert details.page({"context_id": result[1]["detailContext"]["id"], "page": 1, "page_size": 10}, owner, True, True)["data"]["rows"][0]["单位"] == "盒"


def test_trusted_point_dimension_is_exact_and_preserves_duplicate_labels_and_units():
    owner = ("F006", 12, "factory_super_admin")
    frame = details_dataset(pd.DataFrame([
        {"日期": "2026-01-01", "产品ID": "sku-a", "产品（单位）": "sku-a（kg）", "数量": 2, "数量单位": "kg"},
        {"日期": "2026-01-02", "产品ID": "sku-a", "产品（单位）": "sku-a（kg）", "数量": 3, "数量单位": "kg"},
        {"日期": "2026-01-03", "产品ID": "sku-a", "产品（单位）": "sku-a（盒）", "数量": 20, "数量单位": "盒"},
    ]), "finished-goods-inventory · current")
    item = {"id": "finished_goods.inventory.current", "title": "当前库存", "source": frame["source"],
            "unit": "数量", "engine": {"pointDimension": "产品（单位）", "detailUnitPolicy": "trusted_quantity_rows"},
            "chart": {"echartsOption": {"xAxis": {"data": ["sku-a（kg）", "sku-a（盒）"]}}},
            "tableData": [{"label": "sku-a（kg）", "value": 5}, {"label": "sku-a（盒）", "value": 20}]}
    context = details.register([item], [frame], RequestAll(), owner, True)[0]["detailContext"]["id"]
    result = details.page({"context_id": context, "page": 1, "page_size": 10,
                           "point": {"name": "sku-a（kg）"}}, owner, True, True)
    assert result["data"]["total"] == 2
    assert {row["数量单位"] for row in result["data"]["rows"]} == {"kg"}


def test_trusted_customer_source_point_dimension_does_not_alias_order_channel():
    owner = ("F006", 12, "factory_super_admin")
    frame = details_dataset(pd.DataFrame([
        {"日期": "2026-01-01", "客户来源": "REFERRAL", "订单行ID": "l1", "订单行小计": 10},
        {"日期": "2026-01-02", "客户来源": "REFERRAL", "订单行ID": "l2", "订单行小计": 15},
        {"日期": "2026-01-03", "客户来源": "WEBSITE", "订单行ID": "l3", "订单行小计": 20},
    ]), "customer-source-sales · acquisition")
    item = {"id": "sales.customer_source", "title": "客户来源销售金额（获客归因，非订单销售渠道）",
            "source": frame["source"], "unit": "元", "engine": {"pointDimension": "客户来源"},
            "chart": {"echartsOption": {"xAxis": {"data": ["REFERRAL", "WEBSITE"]}}},
            "tableData": [{"label": "REFERRAL", "value": 25}, {"label": "WEBSITE", "value": 20}]}
    context = details.register([item], [frame], RequestAll(), owner, True)[0]["detailContext"]["id"]
    result = details.page({"context_id": context, "page": 1, "page_size": 10,
                           "point": {"name": "REFERRAL"}}, owner, True, True)
    assert result["data"]["total"] == 2
    assert {row["客户来源"] for row in result["data"]["rows"]} == {"REFERRAL"}


def test_money_or_ratio_display_uses_single_source_unit_and_rejects_ambiguous_units():
    owner = ("F006", 12, "factory_super_admin")
    single = details_dataset(pd.DataFrame([
        {"日期": "2026-01-01", "产品": "A", "单位": "kg", "产量": 2, "销售金额": 10, "OEE": .9},
    ]), "sales · single")
    money = {**candidate(), "source": "sales · single", "unit": "元"}
    registered = details.register([money], [single], Request(), owner, True)
    assert registered[0]["detailContext"]["id"]
    multi = details_dataset(pd.DataFrame([
        {"日期": "2026-01-01", "产品": "A", "单位": "kg", "产量": 2, "销售金额": 10},
        {"日期": "2026-01-01", "产品": "A", "单位": "盒", "产量": 20, "销售金额": 20},
    ]), "sales · multi-money")
    ambiguous = {**money, "source": "sales · multi-money"}
    assert "detailContext" not in details.register([ambiguous], [multi], Request(), owner, True)[0]
    explicit = {**ambiguous, "sourceQuantityUnit": "kg"}
    assert details.register([explicit], [multi], Request(), owner, True)[0]["detailContext"]["id"]


def test_finance_gate_is_source_based_and_unknown_nonmoney_headers_are_stripped():
    owner = ("F006", 12, "factory_super_admin")
    frame = pd.DataFrame([{"日期": "2026-01-01", "产品": "A", "单位": "kg", "产量": 2,
                           "工资名称": "secret", "秘密金额": 88}])
    business = {**candidate(), "source": "upload · business", "domain": "财务"}
    context = details.register([business], [details_dataset(frame, "upload · business")], Request(), owner, True)[0]["detailContext"]["id"]
    assert details.requires_finance(context, owner) is False
    result = details.page({"context_id": context, "page": 1, "page_size": 10}, owner, False, False)
    assert "工资名称" not in result["data"]["columns"]
    assert "秘密金额" not in result["data"]["columns"]
    finance = {**business, "source": "cashflow · upload"}
    finance_context = details.register([finance], [details_dataset(frame, "cashflow · upload")], Request(), owner, True)[0]["detailContext"]["id"]
    assert details.requires_finance(finance_context, owner) is True


def test_same_source_contexts_share_snapshot_and_candidate_facts_are_unchanged(monkeypatch):
    owner = ("F006", 12, "factory_super_admin")
    original = candidate()
    candidates = [{**original, "id": f"candidate-{index}"} for index in range(3)]
    monkeypatch.setattr(details, "MAX_FRAMES", 3)
    source = dataset()
    result = details.register(candidates, [source], Request(), owner, True)
    ids = [row["detailContext"]["id"] for row in result]
    assert len(details._STORAGES) == 1
    assert details._TOTAL_BYTES > 0
    assert all(details.page({"context_id": context, "page": 1, "page_size": 10}, owner, True, True)["data"]["total"] == 2 for context in ids)
    assert all(row["id"] == f"candidate-{index}" for index, row in enumerate(result))
    # Mutating the in-memory source after registration cannot alter the snapshot.
    source["frame"].loc[0, "产品"] = "MUTANT"
    assert details.page({"context_id": ids[0], "page": 1, "page_size": 10}, owner, True, True)["data"]["rows"][0]["产品"] == "A"


def test_cache_eviction_expiry_and_2000_row_first_last_pages(monkeypatch):
    owner = ("F006", 12, "factory_super_admin")
    frame = pd.DataFrame([{"日期": f"2026-01-{(index % 28) + 1:02d}", "产品": f"P{index}",
                           "单位": "kg", "产量": index} for index in range(2000)])
    source = details_dataset(frame, "production · 2000")
    candidates = [{**candidate(), "source": "production · 2000", "unit": "kg", "id": f"p-{index}"} for index in range(3)]
    monkeypatch.setattr(details, "MAX_FRAMES", 2)
    result = details.register(candidates, [source], RequestAll(), owner, True)
    contexts = [row["detailContext"]["id"] for row in result]
    with pytest.raises(details.DetailError) as exc:
        details.page({"context_id": contexts[0], "page": 1, "page_size": 100}, owner, True, True)
    assert exc.value.status_code == 410
    first = details.page({"context_id": contexts[1], "page": 1, "page_size": 100}, owner, True, True)["data"]
    last = details.page({"context_id": contexts[2], "page": 20, "page_size": 100}, owner, True, True)["data"]
    assert first["total"] == last["total"] == 2000
    assert first["rows"][0]["产品"] == "P0"
    assert last["rows"][-1]["产品"] == "P1999"
    details._FRAMES[contexts[1]].expires_at = time.monotonic() - 1
    with pytest.raises(details.DetailError) as exc:
        details.requires_finance(contexts[1], owner)
    assert exc.value.status_code == 410
