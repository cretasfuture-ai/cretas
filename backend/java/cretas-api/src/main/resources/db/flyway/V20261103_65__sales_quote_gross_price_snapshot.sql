SET LOCAL lock_timeout = '5s';
ALTER TABLE sales_order_items ADD COLUMN quoted_gross_unit_price NUMERIC(15,2);
COMMENT ON COLUMN sales_order_items.quoted_gross_unit_price IS
    'Approved gross unit price for newly validated quote orders; NULL preserves historical pricing';
