-- Temperature units are canonical dimensions without a universal multiplicative factor.
-- Linear units remain strictly positive and fail closed for missing/invalid factors.
ALTER TABLE critical_control_draft_items
    ALTER COLUMN unit_factor_to_base DROP NOT NULL;

ALTER TABLE critical_control_draft_items
    DROP CONSTRAINT IF EXISTS ck_critical_control_item_unit;

ALTER TABLE critical_control_draft_items
    ADD CONSTRAINT ck_critical_control_item_unit CHECK (
        btrim(unit_code) <> '' AND btrim(unit_base_code) <> '' AND btrim(unit_display_name) <> ''
        AND btrim(unit_conversion_family) <> ''
        AND unit_display_scale BETWEEN 0 AND 12
        AND unit_dimension IN ('MASS','VOLUME','COUNT','TIME','TEMPERATURE','RATIO')
        AND (
            (
                unit_dimension = 'TEMPERATURE'
                AND (
                    (
                        unit_factor_to_base IS NULL
                        AND lower(btrim(unit_base_code)) = lower(btrim(unit_code))
                        AND upper(btrim(unit_conversion_family)) = 'TEMPERATURE'
                    )
                    OR (
                        unit_factor_to_base IS NOT NULL
                        AND unit_factor_to_base > 0
                    )
                )
            )
            OR (
                unit_dimension <> 'TEMPERATURE'
                AND unit_factor_to_base IS NOT NULL
                AND unit_factor_to_base > 0
            )
        )
        AND unit_snapshot_digest ~ '^[0-9a-f]{64}$');
