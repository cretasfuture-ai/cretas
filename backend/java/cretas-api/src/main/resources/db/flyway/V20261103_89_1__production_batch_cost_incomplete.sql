-- V20261103_89_1__production_batch_cost_incomplete.sql
--
-- S3-13 (2026-10-27 F006 实测): 原料批次没有单价时, 物化代码把批次总成本诚实置 null,
-- 但 ProductionBatch @PreUpdate (calculateMetrics) 每次 save 都用 material/labor 分项重新求和,
-- 于是「不知道」被改回「原料按 0 计的已知数」(批次 10998 total_cost=114.80, unit_cost 为空);
-- 联产小结再拿这个假已知数分给第一个产出, 合计不守恒。
--
-- cost_incomplete=true 表示汇总成本未知, 实体不再自动求和。存量一律 false (行为不变);
-- 已经被改写的历史批次不在此回填 —— 要回填须先逐批核对投入单价, 单独处理。
--
-- 编号 89_1: 排在 main 的 V20261103_89 之后、待合并的 _90 (#1224) / _91 (#1229) 之前,
-- 本迁移须先于它们部署, 否则会成为乱序版本。

ALTER TABLE production_batches
    ADD COLUMN IF NOT EXISTS cost_incomplete BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN production_batches.cost_incomplete IS
    '汇总成本不完整 (有投入单价未知): total_cost/unit_cost 为未知, 不由分项求和';
