-- 源头个体: 类型(活体/胴体) / 入场日期 / 撤销原因 (仓储页面缺口, 原型 D-仓储)
-- 三列全部可空: 存量行不回填, 读到 NULL 就如实显示"未登记", 不猜。
ALTER TABLE source_individuals ADD COLUMN individual_type VARCHAR(16);
ALTER TABLE source_individuals ADD COLUMN entry_date DATE;
ALTER TABLE source_individuals ADD COLUMN revoke_reason VARCHAR(255);
ALTER TABLE source_individuals ADD COLUMN revoked_by VARCHAR(64);
ALTER TABLE source_individuals ADD COLUMN revoked_at TIMESTAMP;
ALTER TABLE source_individuals ADD CONSTRAINT chk_source_individual_type
    CHECK (individual_type IS NULL OR individual_type IN ('LIVE', 'CARCASS'));
