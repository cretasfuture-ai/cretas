-- Native PG gate: executed in one transaction with temporary names only.
-- Reads the exact migration; it cannot alter the permanent business table.
BEGIN;
CREATE TEMP TABLE bom_recipe_items (
    unit varchar(20),
    CONSTRAINT chk_bri_unit CHECK (unit IN ('kg','个'))
);
INSERT INTO bom_recipe_items VALUES ('kg'), ('个');
\ir ../../backend/java/cretas-api/src/main/resources/db/flyway/V20261101_58__bom_units_catalog_authority.sql
INSERT INTO bom_recipe_items VALUES ('roll'), ('m');
DO $$
DECLARE invalid_unit text;
BEGIN
    IF (SELECT count(*) FROM pg_temp.bom_recipe_items) <> 4 THEN
        RAISE EXCEPTION 'positive row count mismatch';
    END IF;
    FOREACH invalid_unit IN ARRAY ARRAY['', '   ', NULL::text] LOOP
        BEGIN
            INSERT INTO pg_temp.bom_recipe_items VALUES (invalid_unit);
            RAISE EXCEPTION 'blank/null unit incorrectly accepted';
        EXCEPTION WHEN check_violation THEN
            NULL;
        END;
    END LOOP;
END $$;
SELECT 'BOM_UNIT_NATIVE_PG_PASS' AS result, count(*) AS rows FROM pg_temp.bom_recipe_items;
ROLLBACK;
