-- A1 (2026-11-10 Steve): 紧急插单「人够不够」的标准人数改为按产线配置, 不再写死 6 人。
-- 可空: 没填就不比较、不提示。后期可扩展成按产品配置。
ALTER TABLE production_lines ADD COLUMN IF NOT EXISTS standard_workers INTEGER;
COMMENT ON COLUMN production_lines.standard_workers IS '本产线标准人数, 空=未配置(插单不比较人数)';
