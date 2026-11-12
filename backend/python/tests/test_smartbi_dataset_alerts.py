import json

import pytest

from smartbi.services.dataset_alerts import analyze_dataset


def _row(**overrides):
    row = {
        "工单编码": "WO-1",
        "成品良品量": 100,
        "材料成本": 100,
        "人工成本": 50,
        "产品代码": "SKU-1",
        "单位": "kg",
        "产品名称": "产品一",
    }
    row.update(overrides)
    return row


def _report(rows, *, source_type="excel", source_id="upload-1", **kwargs):
    return analyze_dataset(rows, factory_id="F001", source_type=source_type, source_id=source_id, **kwargs)


def _business_shape(report):
    # Source identity is intentionally excluded: Excel/API ingestion of the
    # same rows must produce the same business findings and evidence.
    return {
        "summary": report["summary"],
        "capabilities": report["capabilities"],
        "items": [
            {key: item[key] for key in ("type", "family", "severity", "blocksUnitCost", "fields", "evidence", "checkOptions")}
            for item in report["items"]
        ],
    }


def test_same_table_excel_and_api_have_same_business_result_except_source_identity():
    excel = _report([_row(), _row(**{"工单编码": "WO-2", "材料成本": 130})], source_id="excel-1")
    api = _report([_row(), _row(**{"工单编码": "WO-2", "材料成本": 130})], source_type="api", source_id="api-1")
    assert _business_shape(excel) == _business_shape(api)
    assert excel["source"] != api["source"]
    assert excel["runId"] != api["runId"]


def test_finance_table_is_not_hard_interpreted_as_work_order_cost():
    report = _report([_row(**{"原料成本": 200})], source_type="finance")
    assert report["capabilities"]["cost"]["status"] == "not_applicable"
    assert report["capabilities"]["cost"]["hasCostColumns"] is True
    assert not any(item["type"] in {
        "COST_ROW_INELIGIBLE", "FINISHED_GOOD_NONPOSITIVE", "NEGATIVE_COST_VALUE",
        "WORK_ORDER_GRAIN_AMBIGUOUS", "PRODUCT_NAME_CONFLICT", "NUMERIC_FIELD_ZERO",
    } for item in report["items"])
    assert not any(item["type"] == "UNIT_COST_SPREAD_CANDIDATE" for item in report["items"])


def test_null_zero_and_invalid_numeric_values_are_distinct():
    report = _report([_row(**{"材料成本": None, "人工成本": 0, "成品良品量": "bad"})])
    types = {item["type"] for item in report["items"]}
    assert "NUMERIC_FIELD_MISSING" in types
    assert "NUMERIC_FIELD_ZERO" in types
    assert "NUMERIC_FIELD_INVALID" in types
    assert report["capabilities"]["cost"]["status"] == "insufficient"


def test_duplicate_rows_are_candidates_and_are_not_deleted():
    report = _report([_row(), _row()])
    duplicate = next(item for item in report["items"] if item["type"] == "DUPLICATE_ROWS_CANDIDATE")
    assert report["summary"]["rows"] == 2
    assert report["summary"]["scannedRows"] == 2
    assert [ref["rowNumber"] for ref in duplicate["sourceRefs"]] == [1, 2]


def test_cross_unit_missing_identity_and_name_conflict_do_not_create_peer_signal():
    cross_unit = [_row(**{"工单编码": f"WO-{i}", "单位": "kg" if i < 3 else "盒", "材料成本": i * 100}) for i in range(1, 5)]
    missing_name = [_row(**{"工单编码": f"WO-{i}", "产品名称": None, "材料成本": i * 100}) for i in range(1, 4)]
    conflict = [
        _row(**{"工单编码": "WO-1", "产品名称": "甲", "材料成本": 100}),
        _row(**{"工单编码": "WO-2", "产品名称": "乙", "材料成本": 300}),
        _row(**{"工单编码": "WO-3", "产品名称": "甲", "材料成本": 500}),
    ]
    assert not any(item["type"] == "UNIT_COST_SPREAD_CANDIDATE" for item in _report(cross_unit)["items"])
    assert not any(item["type"] == "UNIT_COST_SPREAD_CANDIDATE" for item in _report(missing_name)["items"])
    conflict_report = _report(conflict)
    assert any(item["type"] == "PRODUCT_NAME_CONFLICT" for item in conflict_report["items"])
    assert not any(item["type"] == "UNIT_COST_SPREAD_CANDIDATE" for item in conflict_report["items"])


def test_truncation_is_explicit_and_cannot_look_like_full_scan():
    report = _report([_row(**{"工单编码": f"WO-{i}"}) for i in range(5001)])
    assert report["summary"]["rows"] == 5001
    assert report["summary"]["scannedRows"] == 5000
    assert report["summary"]["truncated"] is True
    scan = next(item for item in report["items"] if item["type"] == "SCAN_TRUNCATED")
    assert scan["evidence"]["rowsTruncated"] is True


def test_ambiguous_column_mapping_blocks_cost_readiness():
    report = _report(
        [_row()],
        columns=[
            {"originalName": "材料成本", "standardName": "materialCost", "isMeasure": True},
            {"originalName": "材料金额", "standardName": "materialCost", "isMeasure": True},
        ],
    )
    assert any(item["type"] == "FIELD_MAPPING_AMBIGUOUS" for item in report["items"])
    assert "materialCost" in report["capabilities"]["costMissingFields"]


def test_name_conflict_blocks_all_same_code_unit_peer_subgroups():
    rows = [
        _row(**{"工单编码": "WO-1", "产品名称": "甲", "材料成本": 100}),
        _row(**{"工单编码": "WO-2", "产品名称": "甲", "材料成本": 100}),
        _row(**{"工单编码": "WO-3", "产品名称": "甲", "材料成本": 100}),
        _row(**{"工单编码": "WO-4", "产品名称": "乙", "材料成本": 500}),
    ]
    report = _report(rows)
    assert any(item["type"] == "PRODUCT_NAME_CONFLICT" for item in report["items"])
    assert not any(item["type"] == "UNIT_COST_SPREAD_CANDIDATE" for item in report["items"])


def test_partial_identity_values_are_referenced_and_not_summary_only():
    rows = [_row(**{"工单编码": None}), _row(**{"产品代码": ""}), _row(**{"单位": None})]
    report = _report(rows)
    assert report["capabilities"]["cost"]["status"] == "insufficient"
    assert report["capabilities"]["cost"]["scannedRows"] == 3
    assert report["capabilities"]["cost"]["eligibleRows"] == 0
    item = next(item for item in report["items"] if item["type"] == "COST_ROW_INELIGIBLE")
    assert {ref["rowNumber"] for ref in item["sourceRefs"]} == {1, 2, 3}


def test_nonfinite_source_values_are_strict_json_safe():
    report = _report([_row(**{"材料成本": float("nan")})])
    json.dumps(report, allow_nan=False)
    assert any(item["type"] == "NUMERIC_FIELD_INVALID" for item in report["items"])


def test_empty_scope_is_rejected():
    with pytest.raises(ValueError, match="DATASET_SCOPE_REQUIRED"):
        analyze_dataset([], factory_id="", source_type="excel", source_id="source-1")
    with pytest.raises(ValueError, match="DATASET_SCOPE_REQUIRED"):
        analyze_dataset([], factory_id="F001", source_type="excel", source_id="")


def test_aliases_require_explicit_mapping_when_two_are_present():
    rows = [_row(**{"材料成本": 100, "原料成本": 200})]
    ambiguous = _report(rows)
    assert any(item["type"] == "FIELD_MAPPING_AMBIGUOUS" and "materialCost" in item["fields"] for item in ambiguous["items"])
    explicit = _report(rows, field_mapping={"materialCost": "材料成本"})
    assert not any(item["type"] == "FIELD_MAPPING_AMBIGUOUS" and "materialCost" in item["fields"] for item in explicit["items"])


def test_repeated_work_order_does_not_turn_two_orders_into_three_peers():
    rows = [
        _row(**{"工单编码": "WO-1", "材料成本": 100}),
        _row(**{"工单编码": "WO-2", "材料成本": 100}),
        _row(**{"工单编码": "WO-2", "材料成本": 500}),
    ]
    report = _report(rows)
    grain = next(item for item in report["items"] if item["type"] == "WORK_ORDER_GRAIN_AMBIGUOUS")
    assert {ref["rowNumber"] for ref in grain["sourceRefs"]} == {2, 3}
    assert report["capabilities"]["cost"]["status"] == "insufficient"
    assert report["capabilities"]["cost"]["eligibleRows"] == 1
    assert report["capabilities"]["cost"]["eligibleCoverage"] == (1 / 3)
    assert report["capabilities"]["cost"]["ambiguousWorkOrderRows"] == 2
    assert not any(item["type"] == "UNIT_COST_SPREAD_CANDIDATE" for item in report["items"])


def test_same_work_order_with_different_sku_is_not_silently_aggregated():
    rows = [
        _row(**{"工单编码": "WO-1", "产品代码": "SKU-1"}),
        _row(**{"工单编码": "WO-1", "产品代码": "SKU-2", "材料成本": 500}),
        _row(**{"工单编码": "WO-2", "产品代码": "SKU-1", "材料成本": 100}),
    ]
    report = _report(rows)
    assert any(item["type"] == "WORK_ORDER_GRAIN_AMBIGUOUS" for item in report["items"])
    assert not any(item["type"] == "UNIT_COST_SPREAD_CANDIDATE" for item in report["items"])


def test_three_unique_work_orders_can_compare_around_repeated_work_order():
    rows = [
        _row(**{"工单编码": "WO-DUP", "材料成本": 100}),
        _row(**{"工单编码": "WO-DUP", "材料成本": 500}),
        _row(**{"工单编码": "WO-1", "材料成本": 100}),
        _row(**{"工单编码": "WO-2", "材料成本": 100}),
        _row(**{"工单编码": "WO-3", "材料成本": 500}),
    ]
    report = _report(rows)
    assert any(item["type"] == "WORK_ORDER_GRAIN_AMBIGUOUS" for item in report["items"])
    assert report["capabilities"]["cost"]["status"] == "insufficient"
    assert report["capabilities"]["cost"]["eligibleRows"] == 3
    assert report["capabilities"]["cost"]["eligibleCoverage"] == 0.6
    spread = next(item for item in report["items"] if item["type"] == "UNIT_COST_SPREAD_CANDIDATE")
    assert spread["evidence"]["sampleSize"] == 3
    assert {ref["rowNumber"] for ref in spread["sourceRefs"]} == {3, 4, 5}


def test_generic_kind_disables_cost_rules_but_keeps_general_numeric_quality():
    report = _report(
        [_row(**{"成品良品量": 0, "材料成本": -1, "人工成本": None})],
        dataset_kind="generic",
    )
    assert report["capabilities"]["cost"]["status"] == "not_applicable"
    assert report["capabilities"]["cost"]["hasCostColumns"] is True
    assert not any(item["type"] in {
        "COST_ROW_INELIGIBLE", "FINISHED_GOOD_NONPOSITIVE", "NEGATIVE_COST_VALUE",
        "WORK_ORDER_GRAIN_AMBIGUOUS", "NUMERIC_FIELD_ZERO",
    } for item in report["items"])
    numeric = [item for item in report["items"] if item["type"] in {"NUMERIC_FIELD_MISSING", "NUMERIC_FIELD_INVALID"}]
    assert numeric and all(item["blocksUnitCost"] is False for item in numeric)


def test_auto_requires_explicit_work_order_column_and_does_not_use_sales_order_alias():
    row = _row()
    row.pop("工单编码")
    row["orderCode"] = "SALES-1"
    report = _report([row], dataset_kind="auto")
    assert report["capabilities"]["cost"]["status"] == "not_applicable"
    assert not any(item["type"] in {"COST_ROW_INELIGIBLE", "WORK_ORDER_GRAIN_AMBIGUOUS", "FINISHED_GOOD_NONPOSITIVE", "NEGATIVE_COST_VALUE"} for item in report["items"])


def test_production_cost_zero_and_negative_values_block_cost_capability():
    report = _report(
        [_row(**{"成品良品量": 0, "材料成本": -1, "人工成本": -2})],
        dataset_kind="production_cost",
    )
    assert report["capabilities"]["cost"]["status"] == "insufficient"
    cost_types = {"FINISHED_GOOD_NONPOSITIVE", "NEGATIVE_COST_VALUE", "COST_CAPABILITY_LIMITED"}
    assert cost_types.issubset({item["type"] for item in report["items"]})
    assert all(item["blocksUnitCost"] is True for item in report["items"] if item["type"] in cost_types)
