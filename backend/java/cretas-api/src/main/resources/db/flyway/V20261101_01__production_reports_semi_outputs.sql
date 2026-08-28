-- V20261101_01__production_reports_semi_outputs.sql
-- 多半成品产出 (画布 1 进 N 出) 的填报值。
-- 格式: [{"semiCode":"PTSEMI-LSM-0003","quantity":50.00,"isRework":true}, ...]
-- null = 旧式单产出报工, 走 semi_code/semi_output_quantity 老字段 (向后兼容零回归)。
ALTER TABLE production_reports ADD COLUMN IF NOT EXISTS semi_outputs jsonb;

COMMENT ON COLUMN production_reports.semi_outputs IS
  '多半成品产出填报值 [{semiCode,quantity,isRework}]; null=走旧的单产出字段';
