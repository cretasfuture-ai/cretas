-- Preserve the applicant captured from JWT when a disposal record is created.
-- Existing records intentionally remain NULL: the approver must never be presented as the applicant.
ALTER TABLE disposal_records
    ADD COLUMN IF NOT EXISTS applicant_id INTEGER,
    ADD COLUMN IF NOT EXISTS applicant_name VARCHAR(100);
