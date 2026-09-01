-- 主原料份额 (owner 2026-09-02 裁定走 (a): 配比进画布 INPUT 端口, 发布即新修订, 在途计划按钉住的修订不受影响)
-- 冻结到运行时端口; 建领料单时抄到明细行 —— 仓管「填一个原料, 其余按份额自动跳」(30 吨 ⇒ 70 吨)。
-- 两列都允许 NULL: NULL = 该端口/该行不参与推算, 领料仍按实际领用。⛔ 不回填历史数据。
ALTER TABLE workflow_task_ports ADD COLUMN IF NOT EXISTS input_share_ratio NUMERIC(9,4);
ALTER TABLE factory_material_requisition_items ADD COLUMN IF NOT EXISTS input_share_ratio NUMERIC(9,4);
COMMENT ON COLUMN workflow_task_ports.input_share_ratio IS '主原料份额(%), 仅 INPUT 端口; NULL=不参与推算';
COMMENT ON COLUMN factory_material_requisition_items.input_share_ratio IS '主原料份额(%), 建单时抄自计划钉住的工艺修订端口; NULL=按实际领用';
