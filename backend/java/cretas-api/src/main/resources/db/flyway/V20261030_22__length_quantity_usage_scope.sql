-- Length-based materials such as film are stocked, purchased and consumed by metre.
-- Preserve any future/tenant-specific scopes while adding the four governed
-- quantity/specification scopes required by the shared UnitContractService.
UPDATE unit_of_measurements
SET usage_scopes_json = (
    SELECT jsonb_agg(scope ORDER BY scope)
    FROM (
        SELECT DISTINCT jsonb_array_elements_text(
            coalesce(usage_scopes_json, '[]'::jsonb)
            || '["INVENTORY_QUANTITY","PURCHASE_QUANTITY","BOM_QUANTITY","SPECIFICATION"]'::jsonb
        ) AS scope
    ) merged_scopes
)
WHERE upper(coalesce(category, '')) = 'LENGTH';
