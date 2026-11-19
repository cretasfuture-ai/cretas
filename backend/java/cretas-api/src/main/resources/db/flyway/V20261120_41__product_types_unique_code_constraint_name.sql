-- product_types (factory_id, code) 唯一约束统一命名为 uk_product_types_factory_code。
--
-- 背景: #1873 的 GlobalExceptionHandler 只在约束名是 uk_product_types_factory_code 时提示
-- 「请换一个编码或留空自动生成」。新库由 V20260416_00 bootstrap 建出该名字, 但 prod 的表早于
-- bootstrap, 是 Hibernate 按未命名的 @UniqueConstraint 建的唯一约束, 实名为随机串
-- ukd96kjf87wgjnvn95q1qyom8pg (2026-11-19 只读核对: pg_constraint contype='u'), 导致该提示在 prod 永远不出现。
--
-- 做法: 本 schema 下已有目标名的唯一约束/索引就什么都不做; 否则
--   1) 找 product_types 上键列恰好是 (factory_id, code) 的唯一约束, RENAME CONSTRAINT (连同底层索引改名; prod 走这条);
--   2) 没有约束时找同样键列的有效、非部分、非表达式唯一索引, ALTER INDEX RENAME。
-- 只改名, 不重建, 不动数据。改名要短暂 ACCESS EXCLUSIVE 锁: lock_timeout 5s, 拿不到锁就失败回滚, 不让后续读写排队。
-- 找不到这样的唯一约束/索引就报错停下 (说明库与预期不符, 不该静默跳过)。

SET LOCAL lock_timeout = '5s';

DO $$
DECLARE
    v_target   CONSTANT text := 'uk_product_types_factory_code';
    v_con      text;
    v_idx      text;
BEGIN
    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
               JOIN pg_index i ON i.indexrelid = c.oid
               WHERE c.relname = v_target AND n.nspname = current_schema()
                 AND i.indrelid = 'product_types'::regclass AND i.indisunique) THEN
        RAISE NOTICE 'V20261120_41: % 已存在, 不改', v_target;
        RETURN;
    END IF;

    -- 1) 唯一约束 (ALTER TABLE ... RENAME CONSTRAINT 会连同底层索引一起改名)
    SELECT con.conname INTO v_con
    FROM pg_constraint con
    WHERE con.conrelid = 'product_types'::regclass
      AND con.contype = 'u'
      AND (SELECT array_agg(a.attname::text ORDER BY a.attname)
           FROM unnest(con.conkey) k JOIN pg_attribute a
             ON a.attrelid = con.conrelid AND a.attnum = k) = ARRAY['code', 'factory_id']
    LIMIT 1;

    IF v_con IS NOT NULL THEN
        EXECUTE format('ALTER TABLE product_types RENAME CONSTRAINT %I TO %I', v_con, v_target);
        RAISE NOTICE 'V20261120_41: 约束 % 改名为 %', v_con, v_target;
        RETURN;
    END IF;

    -- 2) 只有唯一索引、没有约束: 只比较键列 (indnkeyatts, 不含 INCLUDE 列), 只认有效索引
    SELECT ic.relname INTO v_idx
    FROM pg_index i
    JOIN pg_class ic ON ic.oid = i.indexrelid
    WHERE i.indrelid = 'product_types'::regclass
      AND i.indisunique AND NOT i.indisprimary AND i.indisvalid
      AND i.indpred IS NULL AND i.indexprs IS NULL
      AND (SELECT array_agg(a.attname::text ORDER BY a.attname)
           FROM unnest((i.indkey::int2[])[0:i.indnkeyatts - 1]) k JOIN pg_attribute a
             ON a.attrelid = i.indrelid AND a.attnum = k) = ARRAY['code', 'factory_id']
    LIMIT 1;

    IF v_idx IS NULL THEN
        RAISE EXCEPTION 'V20261120_41: product_types 上找不到 (factory_id, code) 唯一约束或唯一索引';
    END IF;

    EXECUTE format('ALTER INDEX %I RENAME TO %I', v_idx, v_target);
    RAISE NOTICE 'V20261120_41: 唯一索引 % 改名为 %', v_idx, v_target;
END $$;
