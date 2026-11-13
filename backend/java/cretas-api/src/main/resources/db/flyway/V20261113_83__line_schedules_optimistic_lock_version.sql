-- 产线排程 line_schedules 加乐观锁版本号 (LineSchedule.@Version)。
-- 之前两个人同时改同一条排程, 后提交的直接覆盖先提交的; 加了版本号后, 后提交的拿到 409「排程已被别人修改，请刷新后再改」。
--
-- 存量行回填 0; 列非空, 默认 0: 不经过实体的写入 (原生 SQL / 其它服务直接 INSERT) 也拿到合法版本号,
-- 不会因为 version 为 NULL 让 Hibernate 读出来再保存时出错。
-- PostgreSQL 11+ 带常量默认值的 ADD COLUMN 只改元数据, 不重写表。可重复执行。

ALTER TABLE line_schedules ADD COLUMN IF NOT EXISTS version BIGINT;
UPDATE line_schedules SET version = 0 WHERE version IS NULL;
ALTER TABLE line_schedules ALTER COLUMN version SET DEFAULT 0;
ALTER TABLE line_schedules ALTER COLUMN version SET NOT NULL;

COMMENT ON COLUMN line_schedules.version IS '乐观锁版本号 (JPA @Version), 每次修改 +1';
