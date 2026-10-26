-- V20261103_85__ccp_step_monitoring.sql
--
-- 缺口 13 CCP 监控 (owner 2026-10-25)
--   1. haccp_checkpoints.work_process_id      关键控制点挂到工序 (Workflow 画布工序节点的 data.workProcessId)。
--      NULL = 未挂工序 (存量全部 NULL, 只做台账, 行为不变)。
--   2. haccp_monitoring_records 补「工序报工时录入」需要的列:
--      production_batch_id / production_plan_id / work_process_id / process_order / source,
--      临界限值快照 (限值以后改了, 当时按什么判仍可追溯), 录入人名,
--      偏离处理 (deviation_handled_at / _by / _by_name) —— 未处理的偏离挡质检放行与出货。
--
-- 存量 haccp_monitoring_records 的偏离行 deviation_handled_at 均为 NULL = 仍按「未处理」挡出货,
-- 与改动前 (任何偏离都挡) 一致, 不放松。
-- 全部 IF NOT EXISTS: 可重放。

ALTER TABLE haccp_checkpoints
    ADD COLUMN IF NOT EXISTS work_process_id VARCHAR(50);

CREATE INDEX IF NOT EXISTS idx_haccp_checkpoints_work_process
    ON haccp_checkpoints (factory_id, work_process_id)
    WHERE deleted_at IS NULL AND work_process_id IS NOT NULL;

COMMENT ON COLUMN haccp_checkpoints.work_process_id IS
    '缺口 13: 挂在哪个工序 (work_processes.id); 该工序报工时必须录入本 CCP 实测值';

ALTER TABLE haccp_monitoring_records
    ADD COLUMN IF NOT EXISTS production_batch_id BIGINT,
    ADD COLUMN IF NOT EXISTS production_plan_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS work_process_id VARCHAR(50),
    ADD COLUMN IF NOT EXISTS process_order INTEGER,
    ADD COLUMN IF NOT EXISTS source VARCHAR(32),
    ADD COLUMN IF NOT EXISTS critical_limit_min_snapshot DECIMAL(19,4),
    ADD COLUMN IF NOT EXISTS critical_limit_max_snapshot DECIMAL(19,4),
    ADD COLUMN IF NOT EXISTS unit_snapshot VARCHAR(20),
    ADD COLUMN IF NOT EXISTS operator_name VARCHAR(100),
    ADD COLUMN IF NOT EXISTS deviation_handled_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS deviation_handled_by BIGINT,
    ADD COLUMN IF NOT EXISTS deviation_handled_by_name VARCHAR(100),
    ADD COLUMN IF NOT EXISTS report_ref VARCHAR(160);

-- 挡放行的热路径: 按计划找未处理偏离 (按批次号的已有 idx_haccp_monitoring_factory_batch)
CREATE INDEX IF NOT EXISTS idx_haccp_monitoring_open_deviation_plan
    ON haccp_monitoring_records (factory_id, production_plan_id)
    WHERE is_deviation = TRUE AND deviation_handled_at IS NULL AND deleted_at IS NULL;

-- 报工凭据 (YR:<报工id> / PS:<计划>:<行>): CCP 与产出在同一事务落库, 按它能对上是哪一次报工
CREATE INDEX IF NOT EXISTS idx_haccp_monitoring_report_ref
    ON haccp_monitoring_records (factory_id, report_ref)
    WHERE report_ref IS NOT NULL;

COMMENT ON COLUMN haccp_monitoring_records.deviation_handled_at IS
    '缺口 13: 偏离已处理时间; is_deviation=TRUE 且本列 NULL = 未处理偏离, 挡质检放行与出货';
