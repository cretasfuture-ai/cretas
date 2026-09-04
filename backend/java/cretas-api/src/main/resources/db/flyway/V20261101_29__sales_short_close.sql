-- 短交结案审计 (2026-09-04 owner 拍板) —— 镜像采购侧「少收关闭」
-- (V20261029_24__purchase_receiving_manual_close.sql) 的字段结构。
--
-- 订单部分发货 (PARTIAL_DELIVERED) 后, 剩余数量经确认不再补发, 由销售经理显式结案
-- (SalesOrderStatus.SHORT_CLOSED)。关闭不回滚任何已发货/已入账数据, 这些字段只解释
-- "为什么剩余数量不再发货"。
--
-- 🔴 R44 review (2026-09-04): prod sales_orders 上有 CHECK ck_so_status 锁死九个值,
-- 不含新加的 SHORT_CLOSED —— 只加审计列会让本迁移「一路绿」(H2 单测/CI 全过), 但 owner
-- 第一次点「短交结案」保存时 PG 会拒绝那条 UPDATE (500, 功能 100% DOA)。与
-- V20260416_03__purchase_orders_ck_po_status_expand.sql (采购侧同形: CLOSED 加进
-- PurchaseOrderStatus 却漏扩 ck_po_status, prod 实测 2026-04-16 04:05:13 真的炸过)
-- 是同一类缺陷 —— DROP + ADD 是 PostgreSQL 改 CHECK 取值范围唯一的路。
--
-- ⚠️ 下面这九个值是只读 SSH 从 prod 原样抄出来的 (2026-09-04), 不是凭记忆重写:
--   ssh root@47.100.235.168 "sudo -u postgres psql -d cretas_prod_db -At -c \
--     \"SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='ck_so_status'\""
--   → CHECK (((status)::text = ANY ((ARRAY['DRAFT','CONFIRMED','PENDING_FINANCE_REVIEW',
--     'FINANCE_APPROVED','FINANCE_REJECTED','PROCESSING','PARTIAL_DELIVERED','COMPLETED',
--     'CANCELLED'])::text[])))
--
-- 🔴 为什么 H2 单测测不出这个洞: ck_so_status 这条约束**从来没有被 Flyway 建过**——
-- db/flyway/ 全目录 grep 不到它 (确认过, 零命中); 它只存在于历史遗留、现在已不被
-- spring.flyway.locations=classpath:db/flyway 扫描的 db/migration/
-- V20260411_10__fix_po_so_status_check_constraints.sql, prod 是当年跑那份旧脚本时
-- 手工/带外应用上的。H2 测试库走 Flyway 建表, 从来没见过这条约束, 所以对它的存在与否
-- 完全无感——23 个测试文件全绿只证明"H2 上没有这条 CHECK", 证明不了"UPDATE 能过 prod"。
-- 已在本地 docker postgres 上真实建表 + 原 CHECK + 真实 UPDATE 验证过 (阴性对照: 不跑
-- 下面这段直接 UPDATE status='SHORT_CLOSED' 确认会被拒 —— 与 prod 上 owner 会踩到的
-- 报错逐字一致: `ERROR: new row for relation "sales_orders" violates check constraint
-- "ck_so_status"`; 跑完下面这段后同一条 UPDATE 确认通过)。
--
-- DROP CONSTRAINT IF EXISTS 对「这条约束在这个库里压根不存在」(所有从纯 db/flyway/ 建起
-- 的库, 包括本仓 CI) 是安全的 no-op; sales_orders 表本身在更早的
-- V20260415_99__bootstrap_legacy_tables.sql 里就已经用 CREATE TABLE IF NOT EXISTS 建过,
-- 所以不需要 sales_delivery_records 那种 hotfix (V20260822_04) 用的 to_regclass 表存在性
-- 守卫 —— 到这个版本号时表必然已经存在。
ALTER TABLE sales_orders DROP CONSTRAINT IF EXISTS ck_so_status;

ALTER TABLE sales_orders ADD CONSTRAINT ck_so_status CHECK (
    status IN (
        'DRAFT',
        'CONFIRMED',
        'PENDING_FINANCE_REVIEW',
        'FINANCE_APPROVED',
        'FINANCE_REJECTED',
        'PROCESSING',
        'PARTIAL_DELIVERED',
        'COMPLETED',
        'CANCELLED',
        'SHORT_CLOSED'
    )
);

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
