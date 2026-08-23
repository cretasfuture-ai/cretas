-- Label QC customer-risk routing: clean AI-only results leave the customer review queue.
-- Keep task/photo status checks aligned with the Java enums before AUTO_PASSED is persisted.
ALTER TABLE label_qc_tasks
    DROP CONSTRAINT IF EXISTS chk_label_qc_task_status;

ALTER TABLE label_qc_tasks
    ADD CONSTRAINT chk_label_qc_task_status CHECK (
        status IN (
            'DRAFT',
            'UPLOADING',
            'QUEUED',
            'ANALYZING',
            'NEEDS_REVIEW',
            'AUTO_PASSED',
            'REVIEWED',
            'ANALYSIS_FAILED'
        )
    );

ALTER TABLE label_qc_photos
    DROP CONSTRAINT IF EXISTS chk_label_qc_photo_status;

ALTER TABLE label_qc_photos
    ADD CONSTRAINT chk_label_qc_photo_status CHECK (
        status IN (
            'UPLOADED',
            'QUEUED',
            'ANALYZING',
            'ANALYZED',
            'ANALYSIS_FAILED',
            'AUTO_PASSED',
            'REVIEWED'
        )
    );
