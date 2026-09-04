-- 短交结案审计 (2026-09-04 owner 拍板) —— 镜像采购侧「少收关闭」
-- (V20261029_24__purchase_receiving_manual_close.sql) 的字段结构。
--
-- 订单部分发货 (PARTIAL_DELIVERED) 后, 剩余数量经确认不再补发, 由销售经理显式结案
-- (SalesOrderStatus.SHORT_CLOSED)。关闭不回滚任何已发货/已入账数据, 这些字段只解释
-- "为什么剩余数量不再发货"。
ALTER TABLE sales_orders
    ADD COLUMN IF NOT EXISTS short_close_reason_code VARCHAR(40),
    ADD COLUMN IF NOT EXISTS short_close_notes TEXT,
    ADD COLUMN IF NOT EXISTS short_closed_by BIGINT,
    ADD COLUMN IF NOT EXISTS short_closed_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS short_closed_quantity NUMERIC(15,4);

COMMENT ON COLUMN sales_orders.short_close_reason_code IS '短交结案原因代码; 仅 status=SHORT_CLOSED 时填写';
COMMENT ON COLUMN sales_orders.short_close_notes       IS '短交结案补充说明; 独立于订单 remark, 便于审计';
COMMENT ON COLUMN sales_orders.short_closed_by         IS '执行短交结案的销售用户 (users.id)';
COMMENT ON COLUMN sales_orders.short_closed_at         IS '短交结案提交时间';
COMMENT ON COLUMN sales_orders.short_closed_quantity   IS '结案时锁定的缺口总量 = Σ max(0, quantity - deliveredQuantity)';
