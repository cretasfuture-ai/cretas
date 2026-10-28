"""S4: 对话建厂「产线产能」—— 只认 件 / kg / 批, 不换算; 「做某产品」必须对上本次成品; 认不出的进「没建」。"""
import json
from unittest.mock import AsyncMock

import pytest

from factory_assembly import generation

PRODUCTS = {"productTypes": [{"code": "P-BEEF", "name": "卤牛肉"}, {"code": "P-NECK", "name": "卤鸭脖 200g"}]}


def lines_of(answer):
    return generation._production_lines("存放区：按标准来\n产线产能：" + answer + "\n", PRODUCTS)


def test_each_line_keeps_the_unit_the_customer_said():
    lines, skipped = lines_of("卤制线 每小时 200 kg、包装线 每小时 1500 件、杀菌线 每小时 2 批")

    assert skipped == []
    assert [(l["name"], l["capacities"]) for l in lines] == [
        ("卤制线", [{"productCode": None, "capacityPerHour": 200, "capacityUnit": "KG"}]),
        ("包装线", [{"productCode": None, "capacityPerHour": 1500, "capacityUnit": "PIECE"}]),
        ("杀菌线", [{"productCode": None, "capacityPerHour": 2, "capacityUnit": "BATCH"}]),
    ]


def test_a_product_row_joins_its_line_and_resolves_to_the_products_own_code():
    lines, skipped = lines_of("卤制线 每小时 200 公斤；卤制线 做卤牛肉 每小时 50.5 千克，卤制线 做卤鸭脖 每小时 300 个")

    assert skipped == []
    assert len(lines) == 1
    assert lines[0]["capacities"] == [
        {"productCode": None, "capacityPerHour": 200, "capacityUnit": "KG"},
        {"productCode": "P-BEEF", "capacityPerHour": 50.5, "capacityUnit": "KG"},
        {"productCode": "P-NECK", "capacityPerHour": 300, "capacityUnit": "PIECE"},
    ]


def test_units_that_need_conversion_and_unknown_products_are_not_built_and_say_why():
    lines, skipped = lines_of("烤线 每小时 3 斤、冷却线 每小时 1 吨、杀菌线 做卤猪蹄 每小时 2 批、随便说一句")

    assert lines == []
    assert len(skipped) == 4
    assert "「斤」不认识" in skipped[0] and "不换算" in skipped[0]
    assert "「吨」不认识" in skipped[1]
    assert "卤猪蹄" in skipped[2] and "没对上成品" in skipped[2]
    assert "没认出" in skipped[3]


def test_not_configuring_now_builds_nothing_and_reports_nothing():
    assert lines_of("先不配，建完厂在「产线产能与设备」里补") == ([], [])
    assert generation._production_lines("存放区：按标准来\n", PRODUCTS) == ([], [])


@pytest.mark.asyncio
async def test_generation_carries_lines_and_lists_what_was_not_built(monkeypatch):
    candidate = {"industryCode": "meat", "industryName": "卤味",
                 "schemas": [{"entityType": "MATERIAL_BATCH", "entityName": "原料批次",
                              "fields": [{"key": "name", "type": "text"}]}],
                 "suggestedData": {"productTypes": [{"code": "P-BEEF", "name": "卤牛肉", "unit": "kg"}],
                                   "materialTypes": [{"code": "M-BEEF", "name": "牛腱", "unit": "kg"}],
                                   "conversionRates": [], "workProcesses": []}}
    monkeypatch.setattr(generation, "_call_llm", AsyncMock(return_value=json.dumps(candidate, ensure_ascii=False)))

    result = await generation.generate_configuration(generation.FactoryInitRequest(
        factory_description="主要成品：卤牛肉。\n产线产能：卤制线 每小时 200 kg、烤线 每小时 3 斤\n",
        factory_name="验收工厂"))

    assert result["success"] is True
    data = result["data"]
    assert data["suggestedData"]["productionLines"] == [
        {"name": "卤制线",
         "capacities": [{"productCode": None, "capacityPerHour": 200, "capacityUnit": "KG"}]}]
    not_built = [n for n in data["notBuilt"] if n["group"] == "产线与设备产能"]
    assert [n["item"] for n in not_built] == ["烤线 每小时 3 斤"]
    assert "产线产能与设备" in not_built[0]["howToFix"]
    assert "烤线" in data["aiSummary"]
