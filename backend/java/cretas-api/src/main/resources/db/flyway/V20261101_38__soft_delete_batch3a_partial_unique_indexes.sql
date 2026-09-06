-- ============================================================
-- 软删批 3-A：让唯一键与应用自己的查重口径一致
-- ============================================================
-- 任务: OPS-SQLDELETE-RATCHET-BATCH3A-20260907（brief 卡 B 第 4/5 条）
--
-- 背景：本批给 MaterialBatch / MaterialProductConversion / RawMaterialType 三个实体补了
-- 类级 @SQLDelete（此前 repository.delete() 是物理删）。软删之后，「这个键被占用了吗」
-- 这类查询会开始撒谎 —— 但**往哪个方向撒谎，取决于那个查询自己看不看得见已删行**：
--
--   判据：**让数据库唯一键与应用自己的查重口径一致。**
--
--   ① 应用用**派生查询**查重（受类级 @Where 过滤，只看活行）
--      ⇒ 软删后应用说「空着」而全量唯一键说「占用」⇒ 用户撞到看不懂的约束冲突
--      ⇒ 唯一键必须改成 WHERE deleted_at IS NULL 的**部分索引**。
--   ② 应用用**原生「含已删」查询**查重
--      ⇒ 应用说「占用」，全量唯一键也说「占用」⇒ 两边一致 ⇒ **保持全量，本迁移不动它**。
--
-- 本批三个实体逐个判（2026-09-07 实测调用点）：
--
--   material_product_conversions  ConversionServiceImpl:67
--                                 existsByFactoryIdAndMaterialTypeIdAndProductTypeId（派生，只看活行）
--                                 ⇒ ① 转部分索引（本文件第 1 段）
--   material_batches              MaterialBatchRepository:98
--                                 findByFactoryIdAndSourceDocTypeAndSourceEventKey（派生，只看活行）
--                                 ⇒ ① 已有的 uq_material_batch_source_event 补上 deleted_at 条件（第 2 段）
--   raw_material_types            RawMaterialTypeRepository:179
--                                 findCodeConflictIncludingDeleted（原生，**含已删**）
--                                 ⇒ ② ⛔ 本迁移**不动** ukoddtjujjj3qfsb0o2nyqvcddi，保持全量。
--                                 与 users.username 的 countByUsernameIncludingDeleted 同一口径：
--                                 「软删后业务标识不释放」是代码里已实施的既成口径，不是待定选项。
--
-- ⛔ 不用「IF NOT EXISTS 就跳过」那种防御式写法（2026-09-04 role_definitions 事故：
--    防御 DO-block 静默 no-op 还报 success）。每一步都显式断言前置状态，不符就 RAISE EXCEPTION。
-- ⛔ 不写死 id。
-- ============================================================

-- ── 1) material_product_conversions: 全量唯一约束 -> 部分唯一索引 ──────────────
--    改前: CONSTRAINT uk9pqnlf3djwkas41ua9uvc4ctw UNIQUE (factory_id, material_type_id, product_type_id)
--    改后: UNIQUE INDEX uq_conversion_factory_material_product (...) WHERE deleted_at IS NULL
--    ⚠️ 这是**放宽**，不是收紧 —— 存量数据不可能因此冲突（原约束更严），所以「存量容忍」天然成立。
DO $$
DECLARE
    con_name TEXT;
BEGIN
    SELECT c.conname INTO con_name
      FROM pg_constraint c
     WHERE c.conrelid = 'public.material_product_conversions'::regclass
       AND c.contype = 'u'
       AND pg_get_constraintdef(c.oid) = 'UNIQUE (factory_id, material_type_id, product_type_id)';

    IF con_name IS NULL THEN
        -- 允许「已经改过」这一种，但必须是**部分索引已存在**才算，⛔ 不允许两者都没有
        IF to_regclass('public.uq_conversion_factory_material_product') IS NULL THEN
            RAISE EXCEPTION 'V20261101_38: material_product_conversions 既没有全量唯一约束也没有部分索引 —— 形状不认识, 停下';
        END IF;
        RAISE NOTICE 'V20261101_38: material_product_conversions 已是部分索引, 跳过';
    ELSE
        EXECUTE format('ALTER TABLE public.material_product_conversions DROP CONSTRAINT %I', con_name);
        RAISE NOTICE 'V20261101_38: 已删除全量唯一约束 %', con_name;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_conversion_factory_material_product
    ON public.material_product_conversions (factory_id, material_type_id, product_type_id)
    WHERE deleted_at IS NULL;

COMMENT ON INDEX public.uq_conversion_factory_material_product IS
    '转换率配置唯一性(软删后释放)。与 ConversionServiceImpl 的派生查重口径一致: 只看活行。';

-- ── 2) material_batches: 幂等键索引补上 deleted_at 条件 ────────────────────────
--    改前: uq_material_batch_source_event (factory_id, source_doc_type, source_event_key)
--            WHERE source_event_key IS NOT NULL
--    改后: 同上 AND deleted_at IS NULL
--    ⚠️ 同样是放宽。软删一个批次之后，同一来源事件应当可以重新收货 —— 而应用侧那条
--       findByFactoryIdAndSourceDocTypeAndSourceEventKey 是派生查询，本来就看不见已删行。
DO $$
DECLARE
    idx_def TEXT;
BEGIN
    SELECT indexdef INTO idx_def FROM pg_indexes
     WHERE schemaname = 'public' AND indexname = 'uq_material_batch_source_event';

    IF idx_def IS NULL THEN
        RAISE EXCEPTION 'V20261101_38: 找不到 uq_material_batch_source_event —— 形状不认识, 停下';
    END IF;

    IF idx_def ILIKE '%deleted_at IS NULL%' THEN
        RAISE NOTICE 'V20261101_38: uq_material_batch_source_event 已含 deleted_at 条件, 跳过';
    ELSE
        DROP INDEX public.uq_material_batch_source_event;
        CREATE UNIQUE INDEX uq_material_batch_source_event
            ON public.material_batches (factory_id, source_doc_type, source_event_key)
            WHERE source_event_key IS NOT NULL AND deleted_at IS NULL;
        RAISE NOTICE 'V20261101_38: uq_material_batch_source_event 已补上 deleted_at IS NULL';
    END IF;
END $$;

-- ── 3) 断言最终形状（⛔ 不是「看起来没报错就算过」）────────────────────────────
DO $$
DECLARE
    conv_idx  TEXT;
    batch_idx TEXT;
    rmt_con   INT;
BEGIN
    SELECT indexdef INTO conv_idx FROM pg_indexes
     WHERE schemaname='public' AND indexname='uq_conversion_factory_material_product';
    IF conv_idx IS NULL OR conv_idx NOT ILIKE '%deleted_at IS NULL%' THEN
        RAISE EXCEPTION 'V20261101_38: uq_conversion_factory_material_product 缺失或不是部分索引: %', conv_idx;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_constraint
                WHERE conrelid='public.material_product_conversions'::regclass AND contype='u') THEN
        RAISE EXCEPTION 'V20261101_38: material_product_conversions 上仍有全量唯一约束 —— 它会与部分索引互相矛盾';
    END IF;

    SELECT indexdef INTO batch_idx FROM pg_indexes
     WHERE schemaname='public' AND indexname='uq_material_batch_source_event';
    IF batch_idx IS NULL OR batch_idx NOT ILIKE '%deleted_at IS NULL%' THEN
        RAISE EXCEPTION 'V20261101_38: uq_material_batch_source_event 没有 deleted_at 条件: %', batch_idx;
    END IF;

    -- 阴性对照：raw_material_types 的全量唯一键**必须还在**（本迁移故意不动它）
    SELECT count(*) INTO rmt_con FROM pg_constraint
     WHERE conrelid='public.raw_material_types'::regclass AND contype='u'
       AND pg_get_constraintdef(oid) = 'UNIQUE (factory_id, code)';
    IF rmt_con <> 1 THEN
        RAISE EXCEPTION 'V20261101_38: raw_material_types 的全量唯一键应当保持不动, 实测 % 个', rmt_con;
    END IF;

    RAISE NOTICE 'V20261101_38: 形状断言通过 (conversions 部分索引 / batches 含 deleted_at / raw_material_types 保持全量)';
END $$;
