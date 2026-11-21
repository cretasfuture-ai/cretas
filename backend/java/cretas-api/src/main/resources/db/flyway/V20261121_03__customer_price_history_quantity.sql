ALTER TABLE customer_price_history
    ADD COLUMN IF NOT EXISTS quantity NUMERIC(15,4),
    ADD COLUMN IF NOT EXISTS quantity_unit VARCHAR(20),
    ADD COLUMN IF NOT EXISTS packaging_unit VARCHAR(20),
    ADD COLUMN IF NOT EXISTS packaging_base_unit VARCHAR(20),
    ADD COLUMN IF NOT EXISTS packaging_factor NUMERIC(20,8);

-- History retained one row per order/product without a line ID. Recover the quantity
-- only when exactly one live source line matches the recorded unit price.
WITH matched AS (
    SELECT h.id, MIN(i.quantity) AS quantity, MIN(i.unit) AS quantity_unit,
        MIN(i.packaging_unit) AS packaging_unit, MIN(i.packaging_base_unit) AS packaging_base_unit,
        MIN(i.packaging_factor) AS packaging_factor
    FROM customer_price_history h
    JOIN sales_orders o ON o.id = h.source_sales_order_id AND o.factory_id = h.factory_id
        AND o.customer_id = h.customer_id AND o.deleted_at IS NULL
    JOIN sales_order_items i ON i.sales_order_id = o.id AND i.product_type_id = h.product_type_id
        AND i.unit_price = h.unit_price AND i.deleted_at IS NULL
    WHERE h.quantity IS NULL AND h.deleted_at IS NULL
    GROUP BY h.id
    HAVING COUNT(*) = 1 AND MIN(i.quantity) > 0
)
UPDATE customer_price_history h
SET quantity = matched.quantity, quantity_unit = matched.quantity_unit,
    packaging_unit = matched.packaging_unit, packaging_base_unit = matched.packaging_base_unit,
    packaging_factor = matched.packaging_factor
FROM matched
WHERE h.id = matched.id AND h.quantity IS NULL;

-- A valid NUMERIC(15,4) price divided by 0.0001 can require 17 integer digits as a percentage.
ALTER TABLE purchase_order_items ALTER COLUMN price_deviation_pct TYPE NUMERIC(21,4);
