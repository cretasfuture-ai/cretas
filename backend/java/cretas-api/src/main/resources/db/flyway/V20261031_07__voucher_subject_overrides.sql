-- V20261031_05: 凭证科目可配 —— 补回丢失的 __default__ 种子 + 建按凭证类型的覆盖表
--
-- ## 缺陷一: __default__ 种子没了, 自动初始化变成永久静默 no-op
--
--   `VoucherSubjectMappingServiceImpl.autoInitFromDefault` 从 factory_id='__default__'
--   复制 6 条采购科目映射给新工厂。种子由 V20261011_20 于 2026-06-10 写入,
--   六膳门 2026-07-02 14:19 复制成功过(备注「自动从默认配置复制」)。
--
--   🔴 2026-08-28 prod 实测: 整表只剩六膳门那 6 行, `__default__` **一行不剩**
--   (含软删)。于是那段代码走的是:
--       if (defaults.isEmpty()) { log.warn(...); return; }   <- 永久静默
--   ⇒ 此后任何新工厂都拿不到科目映射, 而且**不报错**。F006 至今 0 条就是这么来的。
--   ⛔ 我没查清是谁删的(全量 grep 无 DELETE, 三个备份 schema 里也没有), 但缺行是事实。
--
-- ## 缺陷二: 另外 7 个生成器的科目全是硬编码, 客户改不了
--
--   实测各生成器写死的科目:
--     SalesReceipt        1122 应收账款 / 6001 主营业务收入 / 2221.01 销项税
--     PurchasePayment     1405 库存商品 / 2202 应付账款 / 2221.02 进项税
--     InventoryTransfer   1405 / 1405 (靠 cost_center 区分仓库)
--     Expense             6602.01 管理费用-损耗 / 1405
--     Return              6001 / 1122  或  2202 / 1403(1405)
--     Wage                2211 应付职工薪酬 / 1002 银行存款
--     Depreciation        6602.02 管理费用-折旧 / 1602 累计折旧
--
--   ⛔ **不能**把它们塞进 voucher_subject_mappings: 那张表的键是
--   (settlement_type, business_type), 而 `SettlementType` 是**采购专用**枚举
--   (预付/赊销先入库/未到票/月结/账期/现结), 没有中性值。调拨/工资/折旧的科目
--   与结算方式毫无关系 —— 硬塞进去是在滥用模型, 下一个人会被这张表误导。
--
--   ⇒ 新建一张键对的表: (factory_id, voucher_type, subject_role)。
--     voucher_type 用 VoucherType 枚举名; subject_role 是该凭证类型内部的角色名
--     (DEBIT / CREDIT / TAX_OUTPUT / TAX_INPUT / INVENTORY 等), 由各生成器自己定义并在
--     代码里逐个标注 —— 角色名是契约的一部分。
--
-- ## 兜底方向: fail-open
--   查不到覆盖就用生成器里那份硬编码默认值。⛔ 不 fail-closed:
--   没配科目就开不出凭证会把今天能用的功能变成不能用。
--   配错科目是"数字记错地方", 开不出凭证是"业务停摆" —— 后者更贵。

-- ---------- 1. 补回 __default__ 采购科目种子 ----------
-- 与 V20261011_20 逐字同源。幂等: 同一 UNIQUE 键 ON CONFLICT DO NOTHING。
INSERT INTO voucher_subject_mappings
    (id, factory_id, settlement_type, business_type,
     debit_subject_code, debit_subject_name, credit_subject_code, credit_subject_name, remark,
     created_at, updated_at)
VALUES
    (gen_random_uuid()::text, '__default__', 'PREPAID',       'PURCHASE', '1405', '原材料', '1002', '银行存款',   'V20261031_05 补回丢失的系统默认', NOW(), NOW()),
    (gen_random_uuid()::text, '__default__', 'CREDIT_FIRST',  'PURCHASE', '1405', '原材料', '2202', '应付账款',   'V20261031_05 补回丢失的系统默认', NOW(), NOW()),
    (gen_random_uuid()::text, '__default__', 'NO_INVOICE',    'PURCHASE', '1405', '原材料', '2241', '暂估应付款', 'V20261031_05 补回丢失的系统默认', NOW(), NOW()),
    (gen_random_uuid()::text, '__default__', 'MONTHLY',       'PURCHASE', '1405', '原材料', '2202', '应付账款',   'V20261031_05 补回丢失的系统默认', NOW(), NOW()),
    (gen_random_uuid()::text, '__default__', 'CREDIT_PERIOD', 'PURCHASE', '1405', '原材料', '2202', '应付账款',   'V20261031_05 补回丢失的系统默认', NOW(), NOW()),
    (gen_random_uuid()::text, '__default__', 'IMMEDIATE',     'PURCHASE', '1405', '原材料', '1001', '库存现金',   'V20261031_05 补回丢失的系统默认', NOW(), NOW())
ON CONFLICT (factory_id, settlement_type, business_type) DO NOTHING;

-- ---------- 2. 按凭证类型 × 科目角色的覆盖表 ----------
CREATE TABLE IF NOT EXISTS voucher_type_subject_overrides (
    id            VARCHAR(191) PRIMARY KEY,
    factory_id    VARCHAR(191) NOT NULL,
    voucher_type  VARCHAR(64)  NOT NULL,
    subject_role  VARCHAR(64)  NOT NULL,
    subject_code  VARCHAR(32)  NOT NULL,
    subject_name  VARCHAR(64),
    remark        VARCHAR(255),
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMP,
    CONSTRAINT uk_vtso_factory_type_role UNIQUE (factory_id, voucher_type, subject_role)
);

CREATE INDEX IF NOT EXISTS idx_vtso_lookup
    ON voucher_type_subject_overrides (factory_id, voucher_type)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE voucher_type_subject_overrides IS
    '按 (工厂, 凭证类型, 科目角色) 覆盖生成器里的默认会计科目。空表 = 全部走代码默认值。';
COMMENT ON COLUMN voucher_type_subject_overrides.subject_role IS
    '该凭证类型内部的角色名(DEBIT/CREDIT/TAX_OUTPUT/...), 由生成器定义, 是契约的一部分';

-- ⛔ 刻意**不**预置任何行: 空表就是"全部用代码默认值", 与改动前行为逐字一致。
--    预置一份"默认覆盖"会让这张表既是默认又是覆盖, 下次没人分得清哪个在生效。
