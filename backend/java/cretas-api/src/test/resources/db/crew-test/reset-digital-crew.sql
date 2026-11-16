-- 只给 DigitalCrewWorkItemRepositoryQueryValidationTest 的 PG 路径用: 一次性验证库上先删掉数字班组的五张表,
-- 再由迁移脚本 V20261115_61 原样重建 (CREATE TABLE IF NOT EXISTS 遇到旧表不会补新列)。
DROP TABLE IF EXISTS digital_crew_delegation_locks;
DROP TABLE IF EXISTS digital_crew_work_items;
DROP TABLE IF EXISTS digital_crew_setting_logs;
DROP TABLE IF EXISTS digital_crew_role_mappings;
DROP TABLE IF EXISTS digital_crew_member_settings;
