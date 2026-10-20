-- Expand the frozen purchase-task scope to the explicitly supported tax rates.
-- Currency and quantity unit remain CNY/kg; this migration changes no other
-- grant columns or constraints and does not modify V20261103_69.
ALTER TABLE ai_purchase_task_grants
    DROP CONSTRAINT IF EXISTS ck_ai_purchase_grant_scope_fixed;

ALTER TABLE ai_purchase_task_grants
    ADD CONSTRAINT ck_ai_purchase_grant_scope_fixed CHECK (
        quantity_unit = 'kg' AND currency = 'CNY' AND tax_rate IN (0, 13)
    );
