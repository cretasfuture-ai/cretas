-- Durable automatic invoice todo outbox. Existing rows intentionally remain NULL
-- so the compensating scanner cannot backfill historical completed orders.
ALTER TABLE sales_orders ADD COLUMN IF NOT EXISTS invoice_todo_status VARCHAR(20);
ALTER TABLE sales_orders ADD COLUMN IF NOT EXISTS invoice_todo_attempts INTEGER DEFAULT 0;
ALTER TABLE sales_orders ADD COLUMN IF NOT EXISTS invoice_todo_next_attempt_at TIMESTAMP;
ALTER TABLE sales_orders ADD COLUMN IF NOT EXISTS invoice_todo_last_error TEXT;

CREATE INDEX IF NOT EXISTS idx_sales_orders_invoice_todo_due
    ON sales_orders (invoice_todo_status, invoice_todo_next_attempt_at);

CREATE UNIQUE INDEX IF NOT EXISTS uk_invoice_factory_number
    ON invoice_records (factory_id, invoice_number)
    WHERE deleted_at IS NULL;
