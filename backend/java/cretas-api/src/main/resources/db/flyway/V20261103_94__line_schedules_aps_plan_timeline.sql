-- APS v1 (Steve 2026-10-28 选定口径): 按工艺图 + 预计工时排工序时间。
--
-- 自动排程是【计划级】的: 计划还没开工时没有批次, 所以排程要能挂在生产计划上。
-- 1) production_plan_id: 这条排程对应的生产计划 (手工排程为空, 照旧只挂批次)。
-- 2) task_timeline: 每道工序的排程时间线 [{taskName, processOrder, workProcessId, start, end, status}]。
--
-- ⛔ 不加指向 production_plans 的外键: 与 batch_id 一致, 排程是计划的快照, 计划被软删后排程记录仍要在。
-- 两列都可空, 存量行不回填 (存量全是手工排程)。

ALTER TABLE line_schedules ADD COLUMN IF NOT EXISTS production_plan_id VARCHAR(191) NULL;
ALTER TABLE line_schedules ADD COLUMN IF NOT EXISTS task_timeline JSONB NULL;
