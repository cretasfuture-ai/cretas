ALTER TABLE label_qc_annotations
    ADD COLUMN IF NOT EXISTS row_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS audience VARCHAR(10) NOT NULL DEFAULT 'CUSTOMER',
    ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP;

ALTER TABLE label_qc_annotations
    DROP CONSTRAINT IF EXISTS chk_label_qc_annotation_audience;

ALTER TABLE label_qc_annotations
    ADD CONSTRAINT chk_label_qc_annotation_audience
        CHECK (audience IN ('CUSTOMER', 'PLATFORM'));

CREATE INDEX IF NOT EXISTS idx_label_qc_annotation_audience_queue
    ON label_qc_annotations(factory_id, audience, human_label, created_at)
    WHERE deleted_at IS NULL;

COMMENT ON COLUMN label_qc_annotations.audience IS
    'CUSTOMER means actionable factory QC risk; PLATFORM means model/geometry diagnostic only.';

COMMENT ON COLUMN label_qc_annotations.row_version IS
    'Optimistic lock used by independent platform diagnostic review.';
