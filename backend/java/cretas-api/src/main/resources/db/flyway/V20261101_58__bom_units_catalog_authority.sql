-- V20261028_69 restored an obsolete static whitelist after canonical units
-- were introduced. UnitContractService remains the business write authority.
-- Preserve the nonblank invariant without excluding governed roll/metre units.
ALTER TABLE bom_recipe_items DROP CONSTRAINT IF EXISTS chk_bri_unit;
ALTER TABLE bom_recipe_items ADD CONSTRAINT chk_bri_unit
    CHECK (unit IS NOT NULL AND btrim(unit) <> '');
