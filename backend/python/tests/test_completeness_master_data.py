"""主数据完整度 (G4) 与缺失记录下钻 (G7) —— 查询构造的性质测试。

不连库: 这里守的是「SQL 只来自白名单」「文本空白算缺失」「既有默认返回行数不变」。
"""
import pytest

from client_requirement import completeness_calculator as cc


def test_master_entities_registered_but_not_in_default_set():
    for t in ("MATERIAL_TYPE", "CUSTOMER", "SUPPLIER"):
        assert t in cc.ENTITY_FIELD_MAP
        assert t not in cc.DEFAULT_ENTITY_TYPES
    # 既有页面不传 entity_types 时仍只拿原来的 5 个生产实体
    assert cc.DEFAULT_ENTITY_TYPES == [
        "PROCESSING_BATCH", "WORK_SESSION", "MATERIAL_BATCH", "QUALITY_INSPECTION", "EQUIPMENT"]


def test_text_fields_treat_blank_as_missing_numeric_do_not():
    q = cc.build_completeness_query("CUSTOMER")
    assert "COUNT(NULLIF(BTRIM(shipping_address), '')) as cnt_shipping_address" in q
    assert "COUNT(credit_limit) as cnt_credit_limit" in q  # 数值列不能 BTRIM
    assert "deleted_at IS NULL" in q and "factory_id = $1" in q


def test_existing_manufacturing_query_unchanged_shape():
    q = cc.build_completeness_query("EQUIPMENT")
    assert "COUNT(equipment_name) as cnt_equipment_name" in q
    assert "BTRIM" not in q


def test_every_text_field_is_a_declared_field():
    for t, cfg in cc.ENTITY_FIELD_MAP.items():
        assert set(cfg.get("text_fields", [])) <= set(cfg["fields"]), t


def test_missing_query_text_field_checks_null_or_blank_and_is_tenant_scoped():
    q = cc.build_missing_query("SUPPLIER", "address")
    assert "(address IS NULL OR BTRIM(address) = '')" in q
    assert "factory_id = $1" in q and "deleted_at IS NULL" in q and "LIMIT $2" in q
    n = cc.build_missing_query("SUPPLIER", "address", count=True)
    assert n.startswith("SELECT COUNT(*)") and "LIMIT" not in n and "(address IS NULL OR" in n


def test_missing_query_numeric_field_is_plain_is_null():
    q = cc.build_missing_query("CUSTOMER", "credit_limit")
    assert "credit_limit IS NULL" in q and "BTRIM" not in q


@pytest.mark.parametrize("entity,field", [
    ("CUSTOMER", "name; DROP TABLE customers"),
    ("CUSTOMER", "password"),
    ("users", "name"),
    ("CUSTOMER", ""),
])
def test_missing_query_rejects_anything_outside_the_whitelist(entity, field):
    with pytest.raises(ValueError):
        cc.build_missing_query(entity, field)
