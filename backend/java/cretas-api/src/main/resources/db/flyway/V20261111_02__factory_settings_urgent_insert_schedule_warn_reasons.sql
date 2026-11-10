-- A18 (2026-11-10 Steve): 插单排程「排不进」的每种原因由工厂自己配成拦或照插并提示。
-- 逗号分隔的原因码; 空 = 全拦 (现状)。
ALTER TABLE factory_settings ADD COLUMN IF NOT EXISTS urgent_insert_schedule_warn_reasons VARCHAR(200);
