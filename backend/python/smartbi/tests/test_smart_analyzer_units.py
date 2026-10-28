import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from services.smart_analyzer import DataScenario, FieldMapper  # noqa: E402


def test_unit_is_not_mapped_to_unit_price():
    mappings = FieldMapper().map_fields(
        [{"name": "单位", "data_type": "text"}], DataScenario.GENERAL_TABLE
    )

    assert mappings[0].standard_name == "product_unit"
    assert mappings[0].standard_name != "price"


def test_price_like_headers_are_not_units_and_units_sold_is_quantity():
    mappings = FieldMapper().map_fields(
        [
            {"name": "unit_price", "data_type": "numeric"},
            {"name": "price_per_unit", "data_type": "numeric"},
            {"name": "unit cost", "data_type": "numeric"},
            {"name": "units_sold", "data_type": "numeric"},
        ],
        DataScenario.GENERAL_TABLE,
    )

    assert [m.standard_name for m in mappings] == ["price", "price", "price", "quantity"]
