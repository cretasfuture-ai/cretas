-- A15 (工厂纪律排查 2026-11-10, Steve 11-11 开工): 客供料收货与采购收货同一个超收上限。
-- 应用层已按 OverReceiveLimit (工厂设置 purchaseOverReceiveRate > 全局属性 > 不限制) 校验累计实收,
-- 原表约束 received_quantity <= expected_quantity 会让每一笔合法超收在提交时被数据库拒绝 (独立审查发现)。
-- 只去掉上界, 保留「不能为负」。
ALTER TABLE sales_order_supplied_material_requirements
    DROP CONSTRAINT IF EXISTS chk_sosmr_received_quantity;

ALTER TABLE sales_order_supplied_material_requirements
    ADD CONSTRAINT chk_sosmr_received_quantity CHECK (received_quantity >= 0);
