-- 食品 DAG 缺口 10: 联产工序「固定比例」成本分摊, 随工艺版本冻结到运行时端口 (Steve 2026-11-03)。
-- NULL = 本工序按重量分摊 (默认, 即现状)。只在 OUTPUT 端口上有值。
ALTER TABLE workflow_task_ports ADD COLUMN IF NOT EXISTS fixed_cost_share NUMERIC(9,4);
COMMENT ON COLUMN workflow_task_ports.fixed_cost_share IS '固定比例成本分摊份额(%), 仅 OUTPUT 端口且工序选固定比例时非空; NULL=按重量';
