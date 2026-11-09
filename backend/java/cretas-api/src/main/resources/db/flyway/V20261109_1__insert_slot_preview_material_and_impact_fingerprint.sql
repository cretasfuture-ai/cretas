-- 缺口 17: 插单预览保存原料(锁定配方版本)与受影响计划的指纹, 确认时重算比对。
-- 旧行这些列为 NULL: 确认时一律按「预览缺指纹, 须重新预览」拒绝, 不放行。
ALTER TABLE insert_slot_previews ADD COLUMN IF NOT EXISTS bom_recipe_id VARCHAR(191) NULL;
ALTER TABLE insert_slot_previews ADD COLUMN IF NOT EXISTS bom_version INT NULL;
ALTER TABLE insert_slot_previews ADD COLUMN IF NOT EXISTS material_fingerprint VARCHAR(64) NULL;
ALTER TABLE insert_slot_previews ADD COLUMN IF NOT EXISTS impact_fingerprint VARCHAR(64) NULL;
