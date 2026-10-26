from decimal import Decimal

from smartbi.api import factory_overview


def test_product_sql_matches_sales_order_item_java_line_amount_rounding():
    sql = factory_overview.PRODUCT_SQL

    # SalesOrderItem.getLineAmount(): round quantity*unitPrice first, then
    # apply discount and round again.  quoted_gross_unit_price belongs only to
    # getLineAmountWithTax and must not enter this aggregate.
    assert "ROUND(ROUND(i.quantity * i.unit_price, 2)" in sql
    assert "COALESCE(i.discount_rate, 0) / 100" in sql
    assert "quoted_gross_unit_price" not in sql
    assert "i.deleted_at IS NULL" in sql
    assert "so.factory_id = $1" in sql
    assert "so.status NOT IN ('CANCELLED', 'DRAFT', 'FINANCE_REJECTED')" in sql


def test_product_summary_keeps_null_amount_as_missing_in_visible_output():
    result = factory_overview.summarize(
        [{"status": "COMPLETED", "orders": 1, "amount": None, "amount_rows": 0,
          "first_date": None, "last_date": None}],
        [],
        [{"product_name": "无价产品", "unit": "kg", "quantity": Decimal("2"), "amount": None}],
    )

    assert result["orderAmount"] is None
    assert result["ordersWithoutAmount"] == 1
    assert result["topProducts"] == [{"name": "无价产品", "unit": "kg", "quantity": 2.0, "amount": None}]
