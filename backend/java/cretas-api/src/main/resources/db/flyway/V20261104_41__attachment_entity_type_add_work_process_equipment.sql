-- 工序 SOP / 设备 SOP 附件: attachments.entity_type 白名单补 WORK_PROCESS、EQUIPMENT。
--
-- Attachment.EntityType 新增 WORK_PROCESS (entity_id = work_processes.id, 字符串主键) 与
-- EQUIPMENT (entity_id = factory_equipment.id 的十进制字符串)。entity_id 列是 varchar(191),
-- 两种主键都放得下, 不动列定义。
--
-- 纯放宽 (新集合 ⊇ 旧集合, 基线 V20261029_33 的 24 个值 + 2 个新值), 不动一行数据:
-- 现存行的值全部仍在新白名单内, ADD CONSTRAINT 的全表校验必然通过。
-- to_regclass 守卫沿用 V20261029_33: 全新 CI 库上 Flyway 先于 ddl-auto 时表还不存在, 裸 ALTER 会阻断启动。
-- 改枚举必须同步改这里, EnumCheckConstraintDriftTest 会挡。

DO $$
BEGIN
    IF to_regclass('public.attachments') IS NOT NULL THEN
        ALTER TABLE attachments DROP CONSTRAINT IF EXISTS chk_att_entity_type;
        ALTER TABLE attachments ADD CONSTRAINT chk_att_entity_type
            CHECK (entity_type::text = ANY (ARRAY[
                'CUSTOMER'::text,
                'CUSTOMER_TRACKING'::text,
                'PURCHASE_ORDER'::text,
                'PURCHASE_RECEIPT'::text,
                'CUSTOMER_SUPPLIED_RECEIPT'::text,
                'QUALITY_CHECK'::text,
                'PRODUCTION_BATCH'::text,
                'PAYMENT_VOUCHER'::text,
                'INVOICE'::text,
                'RD_SAMPLE'::text,
                'RECEIPT'::text,
                'RETURN_ORDER'::text,
                'SHIPMENT'::text,
                'WASTAGE_RECORD'::text,
                'GROUP_LEADER_REPORT'::text,
                'EXPENSE_REPORT'::text,
                'LEAVE_REQUEST'::text,
                'TIMECLOCK_PHOTO'::text,
                'SALES_ORDER'::text,
                'INVENTORY'::text,
                'ECN'::text,
                'CALL_RECORD'::text,
                'PRODUCTION_REPORT'::text,
                'WORK_PROCESS'::text,
                'EQUIPMENT'::text,
                'GENERIC'::text
            ]));
        COMMENT ON CONSTRAINT chk_att_entity_type ON attachments
            IS '与 Attachment.EntityType 全量对齐 (补 WORK_PROCESS / EQUIPMENT: 工序与设备 SOP 文件)。改枚举必须同步改这里, EnumCheckConstraintDriftTest 会挡。';
    END IF;
END $$;
