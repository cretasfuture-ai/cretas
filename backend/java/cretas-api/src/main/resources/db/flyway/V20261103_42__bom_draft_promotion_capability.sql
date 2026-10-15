-- W5 opens the existing promotion ledger for BOM DRAFT -> formal BOM DRAFT.
-- The ledger remains polymorphic; no draft or live rows are rewritten.
ALTER TABLE master_data_draft_promotions
    DROP CONSTRAINT IF EXISTS ck_master_data_draft_promotion_capability;

ALTER TABLE master_data_draft_promotions
    ADD CONSTRAINT ck_master_data_draft_promotion_capability
    CHECK (capability_id IN ('products', 'materials', 'processes', 'bom'));
