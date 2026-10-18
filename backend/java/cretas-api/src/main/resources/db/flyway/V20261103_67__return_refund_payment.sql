-- P5 customer refund: bind the local payment request to one completed sales return.
-- This version is intentionally after the last current migration: earlier
-- migrations recreate ck_aat_type and would otherwise discard AR_REFUND.
ALTER TABLE payment_requests
    ADD COLUMN IF NOT EXISTS return_order_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS payment_reference VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_payment_requests_return_order
    ON payment_requests (return_order_id)
    WHERE deleted_at IS NULL;

-- One active/paid refund reservation per return. Rejected requests may be
-- recreated, while an approved/paid source remains idempotent.
CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_requests_return_refund_live
    ON payment_requests (factory_id, return_order_id)
    WHERE deleted_at IS NULL
      AND return_order_id IS NOT NULL
      AND status IN ('PENDING', 'FINANCE_REVIEW', 'APPROVED', 'PAID');

CREATE UNIQUE INDEX IF NOT EXISTS uk_ar_ap_transactions_return_refund_payment_reference
    ON ar_ap_transactions (factory_id, payment_reference)
    WHERE deleted_at IS NULL
      AND transaction_type = 'AR_REFUND'
      AND payment_reference IS NOT NULL;

-- A return-bound request must remain a sales request with a complete source.
ALTER TABLE payment_requests DROP CONSTRAINT IF EXISTS ck_pr_return_refund_source;
ALTER TABLE payment_requests
    ADD CONSTRAINT ck_pr_return_refund_source CHECK (
        return_order_id IS NULL
        OR (source_type = 'SALES' AND sales_order_id IS NOT NULL AND customer_id IS NOT NULL
            AND purchase_order_id IS NULL AND supplier_id IS NULL)
    );

-- Keep the enum/check contract in sync after all migrations that widen it.
ALTER TABLE ar_ap_transactions DROP CONSTRAINT IF EXISTS ck_aat_type;
ALTER TABLE ar_ap_transactions DROP CONSTRAINT IF EXISTS ar_ap_transactions_transaction_type_check;
ALTER TABLE ar_ap_transactions
    ADD CONSTRAINT ck_aat_type CHECK (transaction_type IN (
        'AR_INVOICE', 'AR_PAYMENT', 'AR_ADJUSTMENT', 'AR_CREDIT_NOTE', 'AR_REFUND',
        'AP_INVOICE', 'AP_PAYMENT', 'AP_ADJUSTMENT', 'AP_CREDIT_NOTE'
    ));

-- Refunds are positive customer settlements and must be traceable to this
-- local workflow; the service still validates the exact amount and customer.
ALTER TABLE ar_ap_transactions DROP CONSTRAINT IF EXISTS ck_aat_return_refund_source;
ALTER TABLE ar_ap_transactions
    ADD CONSTRAINT ck_aat_return_refund_source CHECK (
        transaction_type <> 'AR_REFUND'
        OR (amount > 0 AND source_type = 'RETURN_REFUND' AND source_id IS NOT NULL
            AND counterparty_type = 'CUSTOMER' AND counterparty_id IS NOT NULL)
    );
