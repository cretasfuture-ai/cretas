-- 缺口 17 第 2 项: 插单预览保存排程指纹, 确认时重算比对。旧预览为 NULL ⇒ 确认要求重新预览。
ALTER TABLE insert_slot_previews ADD COLUMN IF NOT EXISTS schedule_fingerprint VARCHAR(64) NULL;
