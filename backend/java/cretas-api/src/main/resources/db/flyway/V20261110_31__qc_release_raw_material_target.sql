-- V20261110_31__qc_release_raw_material_target.sql
--
-- D-S411-04: 原料收货批次处于「质检中」(material_batches.status = 'INSPECTING') 时, 质量角色要在
-- 「质量管理 → 质检放行」放行 / 判不合格。放行记录多一种对象: RAW_MATERIAL_BATCH (target_id = material_batches.id)。
--
-- V20261103_88 建表时 target_type 的 CHECK 是列内联写法, 约束名由 PG 自动生成 (qc_release_records_target_type_check);
-- 这里按定义找出所有约束 target_type 的 CHECK 删掉, 换成一个有名字的约束。只放宽 (多一个合法值), 存量行不受影响。
-- 可重放: 第二次执行时删掉的是本迁移自己建的那条, 再建同样的一条。

DO $$
DECLARE
    r record;
BEGIN
    FOR r IN
        SELECT c.conname
        FROM pg_constraint c
        WHERE c.conrelid = 'qc_release_records'::regclass
          AND c.contype = 'c'
          AND pg_get_constraintdef(c.oid) LIKE '%target_type%'
    LOOP
        EXECUTE format('ALTER TABLE qc_release_records DROP CONSTRAINT %I', r.conname);
    END LOOP;

    ALTER TABLE qc_release_records
        ADD CONSTRAINT ck_qc_release_records_target_type
        CHECK (target_type IN ('SEMI_FINISHED', 'WIP_MATERIAL_BATCH', 'RAW_MATERIAL_BATCH', 'FINISHED_GOODS'));
END $$;

COMMENT ON COLUMN qc_release_records.target_type IS
    '放行对象: SEMI_FINISHED 半成品库存行 / WIP_MATERIAL_BATCH 在制半成品批次 / RAW_MATERIAL_BATCH 原料收货批次 (D-S411-04) / FINISHED_GOODS 成品批次';
