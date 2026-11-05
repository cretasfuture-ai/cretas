-- Raw-material IDs are varchar(191) (including generated RMT_uuid values).
-- Keep the existing unique, foreign-key, and paired-unit CHECK constraints intact;
-- widening the column in place preserves all rows and constraints.
SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF to_regclass('material_packaging_hierarchy') IS NOT NULL THEN
        ALTER TABLE material_packaging_hierarchy
            ALTER COLUMN material_type_id TYPE VARCHAR(191);
    END IF;
END $$;
