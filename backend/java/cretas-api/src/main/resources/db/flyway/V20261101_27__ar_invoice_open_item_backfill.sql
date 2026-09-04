-- AR invoice open-item parity fix.
--
-- ## The bug
--
-- V20261028_94 added settled_amount / outstanding_amount / payment_status / currency_code
-- to ar_ap_transactions and wired AP_INVOICE booking (ArApServiceImpl#persistPayable,
-- #recordPayableFromSource) to initialize them via initializeOpenItem(). AR_INVOICE booking
-- (#persistReceivable, used by both recordReceivable and recordReceivableIfAbsent /
-- shipDelivery's auto-AR) never called it. Prod census 2026-09-04: AP_INVOICE 31/31 rows
-- have outstanding_amount + payment_status set; AR_INVOICE 0/15 have NULL/NULL. Same table,
-- same columns — the AR booking path just never wrote them. The application code fix (same
-- PR as this migration) makes new AR_INVOICE rows call the same helper AP already uses.
-- This migration backfills the rows that predate that fix.
--
-- ## Why this is not a blanket `outstanding_amount = amount`
--
-- Unlike AP, there is no per-invoice AR settlement/allocation mechanism: AR_PAYMENT rows
-- (ArApServiceImpl#recordArPayment) only carry an optional sales_order_id, never a specific
-- AR_INVOICE id, and ar_ap_payment_allocations is scoped to AP only (its FK column is
-- literally named payable_transaction_id — see ArApPaymentAllocation entity javadoc
-- "Immutable allocation from one AP payment ledger row to one AP invoice open item"). So a
-- blanket "outstanding = amount" would silently reopen AR invoices that customers already
-- paid off via recordArPayment — the same trap V20261028_94 called out for legacy AP rows
-- ("Treating all of them as fully unpaid would reopen payables that may already have been
-- paid ... Keep their balance fields unknown ... until finance reconciles them").
--
-- ## What we can attribute safely
--
-- sum(AR_PAYMENT.amount) grouped by sales_order_id is already the authoritative "how much
-- has this SO been paid" number in prod (ArApTransactionRepository#sumArPaymentsBySalesOrderId
-- drives SalesOrder.paidAmount/settlementFlag today). That number is safe to treat as "paid
-- against this invoice" only when the SO maps to exactly one (non-deleted) AR_INVOICE row —
-- true for the pre-2026-08-28 per-SO invoices (source_id IS NULL, one per SO by construction)
-- and for any SO that has shipped in a single delivery under the newer per-shipment scheme
-- (V20261031_06). When an SO has 2+ AR_INVOICE rows (multi-shipment) there is no reliable way
-- to split a SO-level payment across them, and an AR_INVOICE with no sales_order_id (manual/
-- on-account receivable via ArApController) has no SO to look payments up against either —
-- both cases fall back to NEEDS_RECONCILIATION, same as the AP precedent.
--
-- Idempotent: only touches AR_INVOICE rows where outstanding_amount IS NULL, so re-running
-- after the app-side fix has already populated new rows is a no-op for them.

WITH so_ar_invoice_counts AS (
    SELECT factory_id, sales_order_id, COUNT(*) AS invoice_count
    FROM ar_ap_transactions
    WHERE transaction_type = 'AR_INVOICE'
      AND deleted_at IS NULL
      AND sales_order_id IS NOT NULL
    GROUP BY factory_id, sales_order_id
),
so_ar_payments AS (
    SELECT factory_id, sales_order_id, COALESCE(SUM(ABS(amount)), 0) AS paid
    FROM ar_ap_transactions
    WHERE transaction_type = 'AR_PAYMENT'
      AND deleted_at IS NULL
      AND sales_order_id IS NOT NULL
    GROUP BY factory_id, sales_order_id
),
attributable AS (
    SELECT t.id AS transaction_id,
           LEAST(ABS(t.amount), COALESCE(p.paid, 0)) AS paid_for_this_invoice
    FROM ar_ap_transactions t
    JOIN so_ar_invoice_counts c
      ON c.factory_id = t.factory_id AND c.sales_order_id = t.sales_order_id
    LEFT JOIN so_ar_payments p
      ON p.factory_id = t.factory_id AND p.sales_order_id = t.sales_order_id
    WHERE t.transaction_type = 'AR_INVOICE'
      AND t.deleted_at IS NULL
      AND t.outstanding_amount IS NULL
      -- Only when this SO has exactly one AR_INVOICE — see header comment.
      AND c.invoice_count = 1
)
UPDATE ar_ap_transactions t
SET currency_code = COALESCE(t.currency_code, 'CNY'),
    settled_amount = a.paid_for_this_invoice,
    outstanding_amount = ABS(t.amount) - a.paid_for_this_invoice,
    payment_status = CASE
        WHEN ABS(t.amount) - a.paid_for_this_invoice <= 0 THEN 'PAID'
        WHEN a.paid_for_this_invoice > 0 THEN 'PARTIALLY_PAID'
        ELSE 'UNPAID'
    END
FROM attributable a
WHERE t.id = a.transaction_id;

-- Everything still NULL here is either sales_order_id IS NULL (no SO to look payments up
-- against) or one of 2+ AR_INVOICE rows sharing a sales_order_id (no reliable per-invoice
-- split) — mark unknown-needs-reconciliation, same treatment V20261028_94 gave historical
-- AP invoices rather than guessing.
UPDATE ar_ap_transactions
SET currency_code = COALESCE(currency_code, 'CNY'),
    payment_status = 'NEEDS_RECONCILIATION'
WHERE transaction_type = 'AR_INVOICE'
  AND deleted_at IS NULL
  AND payment_status IS NULL;

-- Widen the open-item invariant from AP_INVOICE-only to AR_INVOICE too, so this gap cannot
-- silently reopen for a third invoice type. Same shape as V20261028_94's
-- ck_ar_ap_payable_open_item, generalized: for AR_INVOICE/AP_INVOICE rows with a non-null
-- amount, either NEEDS_RECONCILIATION-with-both-balances-unknown, or settled+outstanding
-- reconcile to abs(amount) with a status consistent with the split.
ALTER TABLE ar_ap_transactions
    DROP CONSTRAINT IF EXISTS ck_ar_ap_payable_open_item;
ALTER TABLE ar_ap_transactions
    DROP CONSTRAINT IF EXISTS ck_ar_ap_open_item;
ALTER TABLE ar_ap_transactions
    ADD CONSTRAINT ck_ar_ap_open_item CHECK (
        transaction_type NOT IN ('AP_INVOICE', 'AR_INVOICE')
        OR amount IS NULL
        OR (payment_status = 'NEEDS_RECONCILIATION'
            AND settled_amount IS NULL
            AND outstanding_amount IS NULL)
        OR (settled_amount IS NOT NULL
            AND outstanding_amount IS NOT NULL
            AND settled_amount >= 0
            AND outstanding_amount >= 0
            AND settled_amount + outstanding_amount = ABS(amount)
            AND payment_status IN ('UNPAID', 'PARTIALLY_PAID', 'PAID')
            AND ((payment_status = 'UNPAID' AND settled_amount = 0 AND outstanding_amount > 0)
                OR (payment_status = 'PARTIALLY_PAID' AND settled_amount > 0 AND outstanding_amount > 0)
                OR (payment_status = 'PAID' AND outstanding_amount = 0)))
    );

-- Verification (run manually / paste into PR): before vs after counts.
--   SELECT transaction_type, payment_status, outstanding_amount IS NULL AS outstanding_is_null,
--          COUNT(*) FROM ar_ap_transactions
--   WHERE transaction_type IN ('AR_INVOICE', 'AP_INVOICE') AND deleted_at IS NULL
--   GROUP BY 1, 2, 3 ORDER BY 1, 2;
