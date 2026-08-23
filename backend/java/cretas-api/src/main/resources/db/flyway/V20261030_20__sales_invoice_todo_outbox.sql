-- Durable automatic invoice todo outbox. Existing rows intentionally remain NULL
-- so the compensating scanner cannot backfill historical completed orders.
ALTER TABLE sales_orders ADD COLUMN IF NOT EXISTS invoice_todo_status VARCHAR(20);
ALTER TABLE sales_orders ADD COLUMN IF NOT EXISTS invoice_todo_attempts INTEGER DEFAULT 0;
ALTER TABLE sales_orders ADD COLUMN IF NOT EXISTS invoice_todo_next_attempt_at TIMESTAMP;
ALTER TABLE sales_orders ADD COLUMN IF NOT EXISTS invoice_todo_last_error TEXT;

CREATE INDEX IF NOT EXISTS idx_sales_orders_invoice_todo_due
    ON sales_orders (invoice_todo_status, invoice_todo_next_attempt_at);

-- Completion writes must create the outbox even when an older application JAR
-- performs the status transition without the current JPA callback.  The
-- OLD-status guard deliberately leaves historical COMPLETED + NULL rows alone.
CREATE OR REPLACE FUNCTION set_sales_order_invoice_todo_on_completion()
RETURNS TRIGGER
LANGUAGE plpgsql
AS '
BEGIN
    IF OLD.status IS DISTINCT FROM ''COMPLETED''
       AND NEW.status = ''COMPLETED''
       AND NEW.invoice_todo_status IS NULL THEN
        NEW.invoice_todo_status := ''PENDING'';
        NEW.invoice_todo_attempts := 0;
        NEW.invoice_todo_next_attempt_at := NULL;
        NEW.invoice_todo_last_error := NULL;
    END IF;
    RETURN NEW;
END;
';

DROP TRIGGER IF EXISTS trg_sales_order_invoice_todo_on_completion ON sales_orders;

CREATE TRIGGER trg_sales_order_invoice_todo_on_completion
    BEFORE UPDATE OF status ON sales_orders
    FOR EACH ROW
    EXECUTE FUNCTION set_sales_order_invoice_todo_on_completion();

CREATE UNIQUE INDEX IF NOT EXISTS uk_invoice_factory_number
    ON invoice_records (factory_id, invoice_number)
    WHERE deleted_at IS NULL;
