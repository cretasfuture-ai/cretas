-- UPDATE promotion is open only for material drafts. Existing CREATE rows remain valid.
-- Preserve V42 and all previously applied checksums; out-of-order remains disabled.
ALTER TABLE master_data_draft_promotions
    DROP CONSTRAINT ck_master_data_draft_promotion_action;
ALTER TABLE master_data_draft_promotions
    ADD CONSTRAINT ck_master_data_draft_promotion_action
    CHECK (action = 'CREATE' OR (action = 'UPDATE' AND capability_id = 'materials'));
