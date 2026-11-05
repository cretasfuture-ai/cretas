-- 工序 SOP / 设备 SOP 附件: attachments.entity_type 白名单补 WORK_PROCESS、EQUIPMENT。
--
-- Attachment.EntityType 新增 WORK_PROCESS (entity_id = work_processes.id, 字符串主键) 与
-- EQUIPMENT (entity_id = factory_equipment.id 的十进制字符串)。entity_id 列是 varchar(191),
-- 两种主键都放得下, 不动列定义。
--
-- 除了回填旧 uploaded_by 的工厂 actor namespace 外，不改变既有附件归属；旧二进制继续写
-- uploaded_by 时由兼容 trigger 补齐 FACTORY_USER namespace。显式 platform namespace 不会被覆盖。
-- to_regclass 守卫沿用 V20261029_33: 全新 CI 库上 Flyway 先于 ddl-auto 时表还不存在, 裸 ALTER 会阻断启动。
-- 改枚举必须同步改这里, EnumCheckConstraintDriftTest 会挡。

DO $$
BEGIN
    IF to_regclass('public.attachments') IS NOT NULL THEN
        ALTER TABLE attachments ADD COLUMN IF NOT EXISTS uploaded_actor_type VARCHAR(32);
        ALTER TABLE attachments ADD COLUMN IF NOT EXISTS uploaded_actor_id BIGINT;
        UPDATE attachments
           SET uploaded_actor_type = 'FACTORY_USER', uploaded_actor_id = uploaded_by
         WHERE uploaded_actor_type IS NULL AND uploaded_by IS NOT NULL;
        ALTER TABLE attachments ALTER COLUMN uploaded_by DROP NOT NULL;
        ALTER TABLE attachments DROP CONSTRAINT IF EXISTS chk_att_uploaded_actor;
        ALTER TABLE attachments ADD CONSTRAINT chk_att_uploaded_actor CHECK (
            (uploaded_actor_type IS NOT NULL AND uploaded_actor_type = 'FACTORY_USER'
                AND uploaded_actor_id IS NOT NULL AND uploaded_by IS NOT NULL
                AND uploaded_actor_id = uploaded_by)
            OR (uploaded_actor_type IS NOT NULL AND uploaded_actor_type = 'PLATFORM_ADMIN'
                AND uploaded_actor_id IS NOT NULL AND uploaded_by IS NULL)
            OR (uploaded_actor_type IS NULL AND uploaded_actor_id IS NULL AND uploaded_by IS NULL)
        );
        CREATE INDEX IF NOT EXISTS idx_att_uploaded_actor
            ON attachments (factory_id, uploaded_actor_type, uploaded_actor_id);

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

-- Compatibility for old binaries that still write only uploaded_by. This trigger is deliberately
-- narrow: it fills only the completely absent namespace and leaves partial/explicit namespaces
-- for chk_att_uploaded_actor to reject.
CREATE OR REPLACE FUNCTION public.attachments_actor_namespace_compat()
RETURNS trigger
LANGUAGE plpgsql
AS $fn$
BEGIN
    IF NEW.uploaded_by IS NOT NULL
       AND NEW.uploaded_actor_type IS NULL
       AND NEW.uploaded_actor_id IS NULL THEN
        NEW.uploaded_actor_type := 'FACTORY_USER';
        NEW.uploaded_actor_id := NEW.uploaded_by;
    END IF;
    RETURN NEW;
END;
$fn$;

DO $$
BEGIN
    IF to_regclass('public.attachments') IS NOT NULL THEN
        DROP TRIGGER IF EXISTS trg_attachments_actor_namespace_compat ON public.attachments;
        CREATE TRIGGER trg_attachments_actor_namespace_compat
            BEFORE INSERT OR UPDATE OF uploaded_by, uploaded_actor_type, uploaded_actor_id
            ON public.attachments
            FOR EACH ROW
            EXECUTE FUNCTION public.attachments_actor_namespace_compat();
    END IF;
END $$;
