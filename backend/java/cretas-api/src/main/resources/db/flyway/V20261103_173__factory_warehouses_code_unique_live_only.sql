-- ============================================================================
-- factory_warehouses: 仓库编码唯一只管活行 —— 软删后可以用同一个编码重建
--
-- 票: D-R2-01 (2026-11-03 S4 验收)。QATEST_1021_001 里软删 WH-LOG 之后再建 WH-LOG,
--   接口先查活行 (findByFactoryIdAndCodeAndDeletedAtIsNull) 没查到, 落库时被
--   uk_fw_factory_code UNIQUE (factory_id, code) 拒掉 —— 这条约束把软删行也算进去了。
--
-- 【prod 只读实测 2026-11-03】
--   pg_constraint: uk_fw_factory_code | contype=u | UNIQUE (factory_id, code)   <- 是约束, 不是裸索引
--   factory_warehouses: 40 行, 其中软删 6 行; 活行 (factory_id, code) 重复 0 组
--   ⚠️ PG 的 UNIQUE CONSTRAINT 不能带 WHERE ⇒ 只能先摘约束, 再建同名的部分唯一索引
--      (同 V20261101_40 §2 的做法)。同名是为了让日志 / 报错里的名字不变。
--
-- 【故意保留的旧写法】V20261020_01 / V20261023_01 里的 ON CONFLICT (factory_id, code) 只在本迁移
--   之前执行 (新库按序跑时约束还在; prod 早已跑完), 不受影响。⛔ 本迁移之后新写的种子
--   要用 ON CONFLICT (factory_id, code) WHERE deleted_at IS NULL, 否则 PG 找不到仲裁索引。
--
-- 全部在 Flyway 的同一个事务里: 自检不过就整体回滚, 约束原样保留。
-- ============================================================================

-- §1 自检: 活行里已经有重复编码就停 (建索引会失败, 先给出看得懂的原因)
DO $$
DECLARE dup_groups bigint;
BEGIN
    SELECT count(*) INTO dup_groups
    FROM (SELECT factory_id, code
          FROM factory_warehouses
          WHERE deleted_at IS NULL
          GROUP BY factory_id, code
          HAVING count(*) > 1) d;
    IF dup_groups > 0 THEN
        RAISE EXCEPTION 'factory_warehouses 活行里有 % 组 (factory_id, code) 重复, 先人工处理再迁移', dup_groups;
    END IF;
END $$;

-- §2 摘掉把软删行也算进去的全量键 (约束形态和裸索引形态都处理)
ALTER TABLE factory_warehouses DROP CONSTRAINT IF EXISTS uk_fw_factory_code;
DROP INDEX IF EXISTS uk_fw_factory_code;

-- §3 同名部分唯一索引: 活行互斥, 软删行不占编码
CREATE UNIQUE INDEX IF NOT EXISTS uk_fw_factory_code
    ON factory_warehouses (factory_id, code)
    WHERE deleted_at IS NULL;

-- §4 事后核对: 约束已不在, 索引在且带 deleted_at 谓词
DO $$
DECLARE pred text;
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conname = 'uk_fw_factory_code'
                 AND conrelid = 'factory_warehouses'::regclass) THEN
        RAISE EXCEPTION 'uk_fw_factory_code 仍是约束, 迁移没有生效';
    END IF;
    SELECT pg_get_expr(ix.indpred, ix.indrelid) INTO pred
    FROM pg_index ix
    JOIN pg_class i ON i.oid = ix.indexrelid
    WHERE i.relname = 'uk_fw_factory_code'
      AND ix.indrelid = 'factory_warehouses'::regclass
      AND ix.indisunique;
    IF pred IS NULL OR pred NOT ILIKE '%deleted_at IS NULL%' THEN
        RAISE EXCEPTION 'uk_fw_factory_code 不是带 deleted_at IS NULL 的部分唯一索引 (谓词=%)', coalesce(pred, '<无>');
    END IF;
END $$;
