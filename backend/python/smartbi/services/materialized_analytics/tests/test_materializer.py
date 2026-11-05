"""test_materializer.py"""
from __future__ import annotations

from smartbi.services.materialized_analytics.materializer import (
    build_schema, materialize_upload,
)
from smartbi.services.materialized_analytics.schema import Domain, FieldRole


def test_build_schema_maps_roles_correctly():
    field_meta = [
        {"original_name": "门店名称", "is_dimension": True, "is_measure": False, "is_time": False, "display_order": 1},
        {"original_name": "订单日期", "is_dimension": False, "is_measure": False, "is_time": True, "display_order": 2},
        {"original_name": "销售金额", "is_dimension": False, "is_measure": True, "is_time": False, "display_order": 3},
        {"original_name": "菜品名称", "is_dimension": True, "is_measure": False, "is_time": False, "display_order": 4},
    ]
    schema = build_schema(upload_id=42, factory_id="F001", field_meta=field_meta,
                          row_count=100, sample_rows=[])
    assert schema.domain == Domain.UNKNOWN  # retired restaurant detection is fail-closed
    assert schema.primary_measure == "销售金额"
    assert schema.time_field == "订单日期"
    assert len(schema.fields) == 4
    # Role mapping
    roles = {f.name: f.role for f in schema.fields}
    assert roles["门店名称"] == FieldRole.DIMENSION
    assert roles["订单日期"] == FieldRole.TIME
    assert roles["销售金额"] == FieldRole.MEASURE


def test_build_schema_primary_measure_prefers_keyword():
    # Should prefer 销售金额 over the alphabetically earlier 成本
    field_meta = [
        {"original_name": "成本", "is_dimension": False, "is_measure": True, "is_time": False, "display_order": 1},
        {"original_name": "销售金额", "is_dimension": False, "is_measure": True, "is_time": False, "display_order": 2},
        {"original_name": "门店", "is_dimension": True, "is_measure": False, "is_time": False, "display_order": 3},
        {"original_name": "菜品", "is_dimension": True, "is_measure": False, "is_time": False, "display_order": 4},
    ]
    schema = build_schema(upload_id=1, factory_id="F001", field_meta=field_meta,
                          row_count=10, sample_rows=[])
    assert schema.primary_measure == "销售金额"


def test_build_schema_unknown_domain_for_generic_fields():
    field_meta = [
        {"original_name": "col_a", "is_dimension": True, "is_measure": False, "is_time": False, "display_order": 1},
        {"original_name": "col_b", "is_dimension": False, "is_measure": True, "is_time": False, "display_order": 2},
    ]
    schema = build_schema(upload_id=1, factory_id="F001", field_meta=field_meta,
                          row_count=10, sample_rows=[])
    assert schema.domain == Domain.UNKNOWN
