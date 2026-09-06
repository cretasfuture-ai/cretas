-- ============================================================
-- 让 prod schema 通过 Hibernate ddl-auto=validate: DB 侧那一半
-- ============================================================
-- 任务: OPS-PROD-DDL-AUTO-NONE-AND-SCHEMA-CANNOT-VALIDATE-20260906
--
-- 背景:
--   prod 一直跑 ddl-auto=none (application-pg-prod.properties 里那句注释
--   "PostgreSQL BYTEA/BLOB 类型映射差异导致验证失败" 就是当年关掉它的理由)。
--   2026-09-06 把真实 Spring 上下文按 profile=pg-prod + ddl-auto=validate 打到
--   一份 prod schema 拷贝上, 逐条枚举出 163 处 validate 失败:
--     138 处 = 实体注解写错 (columnDefinition 写着 MySQL 的 JSON/LONGTEXT/MEDIUMBLOB,
--              或没写而默认成 varchar), 库是对的 -> 同一个 PR 改实体, 不动库;
--      19 处 = 库确实错了且改动无损     -> 就是本文件;
--       6 处 = 实体要的表 prod 根本没有 -> ⛔ 本文件不建, 见 PR 正文的 🔴 清单。
--
--   ⚠️ 本迁移**不**把 prod 的启动参数改成 validate。prod 仍然是 ddl-auto=none。
--      它只是把库改成"就算真开 validate 也过得去"。
--
-- 无损性判据 (2026-09-06 只读 psql on cretas_prod_db 实测, 见 PR 正文表格):
--   * 16 个"布尔存成 text"的列: 13 个表为空; reflection_memories 593 行全 'false';
--     smart_bi_usage_records 1585 行全 'true'; work_types 2 行全 'false';
--     scale_protocol_test_cases 7 行是**字面 4 字符串** '\x00'/'\x01'
--     (length()=4, ascii()=92 反斜杠 —— MySQL BIT(1) 当年被按字面转义写了进来)。
--   * ai_intent_configs.confidence_boost: 417 行, 取值只有 0 / 0.15 / 5,
--     round(x,2) <> x 的行数 = 0 ⇒ float8 -> numeric(3,2) 不丢精度。
--   * isapi_devices.equipment_id: 0 行。实体是 Long, 而 factory_equipment.id 是 int8。
--   * workflow_templates: 0 行, 但实体带 @Where(clause = "deleted_at IS NULL") 而
--     prod 这张表**没有 deleted_at 列** ⇒ 任何一次 JPA 读都会
--     ERROR: column "deleted_at" does not exist。这不是"为了 validate"才补的列,
--     是一条真缺陷。
--
-- ⛔ 这里不用 "IF NOT EXISTS 就跳过" 那种防御式写法 (2026-09-04 role_definitions
--    事故: 防御 DO-block 静默 no-op 还报 success)。三态显式写出来:
--      列已经是目标类型 -> RAISE NOTICE 跳过 (合法, 例如 test env 已经先改过)
--      列是 text 且取值都认识 -> 转
--      其它任何情况       -> RAISE EXCEPTION, 让迁移红着停下
-- ============================================================

-- ------------------------------------------------------------
-- 1) 16 个语义布尔却存成 text 的列 -> boolean
-- ------------------------------------------------------------
DO $$
DECLARE
    r            RECORD;
    cur_type     TEXT;
    bad_count    BIGINT;
    converted    INT := 0;
    skipped      INT := 0;
    -- 认识的真值/假值。'\x01'/'\x00' 是 MySQL BIT(1) 被字面转义后的 4 字符串。
    true_vocab   TEXT[] := ARRAY['true','t','1','yes','y','\x01'];
    false_vocab  TEXT[] := ARRAY['false','f','0','no','n','\x00'];
BEGIN
    FOR r IN
        SELECT * FROM (VALUES
            ('ai_audit_logs',             'consumed_quota'),
            ('iot_device_data',           'processed'),
            ('isapi_event_logs',          'ai_analyzed'),
            ('isapi_event_logs',          'ai_hygiene_concern'),
            ('isapi_event_logs',          'ai_requires_action'),
            ('isapi_event_logs',          'ai_safety_concern'),
            ('isapi_event_logs',          'processed'),
            ('label_recognition_configs', 'trigger_on_field_detection'),
            ('label_recognition_configs', 'trigger_on_vmd'),
            ('label_recognition_records', 'batch_match'),
            ('reflection_memories',       'was_successful'),
            ('scale_protocol_test_cases', 'expected_stable'),
            ('semantic_cache_config',     'exact_match_only'),
            ('smart_bi_usage_records',    'success'),
            ('voice_recognition_config',  'save_audio_to_oss'),
            ('work_types',                'certification_required')
        ) AS t(tbl, col)
    LOOP
        SELECT c.udt_name INTO cur_type
          FROM information_schema.columns c
         WHERE c.table_schema = 'public' AND c.table_name = r.tbl AND c.column_name = r.col;

        IF cur_type IS NULL THEN
            RAISE EXCEPTION 'V20261101_35: %.% 不存在 —— 迁移的前提不成立', r.tbl, r.col;
        ELSIF cur_type = 'bool' THEN
            RAISE NOTICE 'V20261101_35: %.% 已经是 boolean, 跳过', r.tbl, r.col;
            skipped := skipped + 1;
            CONTINUE;
        ELSIF cur_type <> 'text' THEN
            RAISE EXCEPTION 'V20261101_35: %.% 既不是 text 也不是 boolean, 而是 % —— 不认识, 停下',
                r.tbl, r.col, cur_type;
        END IF;

        -- 阳性对照的反面: 先证明"没有我不认识的取值", 再动手
        EXECUTE format(
            'SELECT count(*) FROM public.%I WHERE %I IS NOT NULL '
            '  AND lower(%I) <> ALL ($1) AND lower(%I) <> ALL ($2)',
            r.tbl, r.col, r.col, r.col)
            INTO bad_count USING true_vocab, false_vocab;
        IF bad_count > 0 THEN
            RAISE EXCEPTION 'V20261101_35: %.% 有 % 行取值不在已知词表内 —— 拒绝把它们静默转成 NULL',
                r.tbl, r.col, bad_count;
        END IF;

        -- ⚠️ ALTER TABLE 是 utility 语句, EXECUTE ... USING 的 $1 在它里面不生效
        --    ("there is no parameter $1"), 所以词表必须以字面量拼进去。
        EXECUTE format(
            'ALTER TABLE public.%I ALTER COLUMN %I TYPE boolean USING ('
            '  CASE WHEN %I IS NULL THEN NULL'
            '       WHEN lower(%I) = ANY (%L::text[]) THEN true'
            '       ELSE false END)',
            r.tbl, r.col, r.col, r.col, true_vocab);
        converted := converted + 1;
    END LOOP;

    RAISE NOTICE 'V20261101_35: boolean 列 converted=% skipped=%', converted, skipped;
    IF converted + skipped <> 16 THEN
        RAISE EXCEPTION 'V20261101_35: 应处理 16 列, 实际 converted+skipped=%', converted + skipped;
    END IF;
END $$;

-- ------------------------------------------------------------
-- 2) ai_intent_configs.confidence_boost: float8 -> numeric(3,2)
--    实体是 BigDecimal + columnDefinition="DECIMAL(3,2)"; 库是 float8。
-- ------------------------------------------------------------
DO $$
DECLARE
    cur_type  TEXT;
    would_lose BIGINT;
BEGIN
    SELECT udt_name INTO cur_type FROM information_schema.columns
     WHERE table_schema='public' AND table_name='ai_intent_configs' AND column_name='confidence_boost';

    IF cur_type IS NULL THEN
        RAISE EXCEPTION 'V20261101_35: ai_intent_configs.confidence_boost 不存在';
    ELSIF cur_type = 'numeric' THEN
        RAISE NOTICE 'V20261101_35: confidence_boost 已经是 numeric, 跳过';
    ELSIF cur_type <> 'float8' THEN
        RAISE EXCEPTION 'V20261101_35: confidence_boost 类型是 % —— 不认识, 停下', cur_type;
    ELSE
        SELECT count(*) INTO would_lose FROM ai_intent_configs
         WHERE confidence_boost IS NOT NULL
           AND (confidence_boost::numeric <> round(confidence_boost::numeric, 2)
                OR abs(confidence_boost) >= 10);
        IF would_lose > 0 THEN
            RAISE EXCEPTION 'V20261101_35: confidence_boost 有 % 行会在 numeric(3,2) 下丢精度/溢出', would_lose;
        END IF;
        ALTER TABLE public.ai_intent_configs
            ALTER COLUMN confidence_boost TYPE numeric(3,2)
            USING round(confidence_boost::numeric, 2);
    END IF;
END $$;

-- ------------------------------------------------------------
-- 3) isapi_devices.equipment_id: varchar(36) -> bigint
--    实体是 Long equipmentId; 被指向的 factory_equipment.id 是 int8。表为空。
-- ------------------------------------------------------------
DO $$
DECLARE
    cur_type  TEXT;
    bad_count BIGINT;
BEGIN
    SELECT udt_name INTO cur_type FROM information_schema.columns
     WHERE table_schema='public' AND table_name='isapi_devices' AND column_name='equipment_id';

    IF cur_type IS NULL THEN
        RAISE EXCEPTION 'V20261101_35: isapi_devices.equipment_id 不存在';
    ELSIF cur_type = 'int8' THEN
        RAISE NOTICE 'V20261101_35: isapi_devices.equipment_id 已经是 bigint, 跳过';
    ELSIF cur_type <> 'varchar' THEN
        RAISE EXCEPTION 'V20261101_35: isapi_devices.equipment_id 类型是 % —— 不认识, 停下', cur_type;
    ELSE
        SELECT count(*) INTO bad_count FROM isapi_devices
         WHERE equipment_id IS NOT NULL AND btrim(equipment_id) !~ '^[0-9]+$';
        IF bad_count > 0 THEN
            RAISE EXCEPTION 'V20261101_35: isapi_devices.equipment_id 有 % 行不是纯数字', bad_count;
        END IF;
        ALTER TABLE public.isapi_devices
            ALTER COLUMN equipment_id TYPE bigint
            USING nullif(btrim(equipment_id), '')::bigint;
    END IF;
END $$;

-- ------------------------------------------------------------
-- 4) workflow_templates.deleted_at: 真缺陷, 不只是为了 validate
--    实体 WorkflowTemplate 带 @Where(clause = "deleted_at IS NULL"),
--    prod 这张表没有这一列 ⇒ 每一次读都 ERROR: column "deleted_at" does not exist。
-- ------------------------------------------------------------
DO $$
DECLARE
    cur_type TEXT;
BEGIN
    SELECT udt_name INTO cur_type FROM information_schema.columns
     WHERE table_schema='public' AND table_name='workflow_templates' AND column_name='deleted_at';

    IF cur_type IS NULL THEN
        ALTER TABLE public.workflow_templates ADD COLUMN deleted_at timestamp(6);
        RAISE NOTICE 'V20261101_35: workflow_templates.deleted_at 已补上';
    ELSIF cur_type = 'timestamp' THEN
        RAISE NOTICE 'V20261101_35: workflow_templates.deleted_at 已存在, 跳过';
    ELSE
        RAISE EXCEPTION 'V20261101_35: workflow_templates.deleted_at 类型是 % —— 不认识, 停下', cur_type;
    END IF;
END $$;

-- ------------------------------------------------------------
-- 5) 收尾断言: 上面 19 处必须全部落地, 否则整条迁移回滚
--    (硬约束 9: 验证"X 没发生"时必须有一个"Y 一定会发生"的读数 ——
--     这里的 Y 就是这 19 行的类型读数, 它设计上必然变。)
-- ------------------------------------------------------------
DO $$
DECLARE
    n_bool     INT;
    n_conf     INT;
    n_equip    INT;
    n_deleted  INT;
BEGIN
    SELECT count(*) INTO n_bool FROM information_schema.columns c
     WHERE c.table_schema='public' AND c.udt_name='bool'
       AND (c.table_name, c.column_name) IN (
            ('ai_audit_logs','consumed_quota'),('iot_device_data','processed'),
            ('isapi_event_logs','ai_analyzed'),('isapi_event_logs','ai_hygiene_concern'),
            ('isapi_event_logs','ai_requires_action'),('isapi_event_logs','ai_safety_concern'),
            ('isapi_event_logs','processed'),
            ('label_recognition_configs','trigger_on_field_detection'),
            ('label_recognition_configs','trigger_on_vmd'),
            ('label_recognition_records','batch_match'),
            ('reflection_memories','was_successful'),
            ('scale_protocol_test_cases','expected_stable'),
            ('semantic_cache_config','exact_match_only'),
            ('smart_bi_usage_records','success'),
            ('voice_recognition_config','save_audio_to_oss'),
            ('work_types','certification_required'));

    SELECT count(*) INTO n_conf FROM information_schema.columns
     WHERE table_schema='public' AND table_name='ai_intent_configs'
       AND column_name='confidence_boost' AND udt_name='numeric';

    SELECT count(*) INTO n_equip FROM information_schema.columns
     WHERE table_schema='public' AND table_name='isapi_devices'
       AND column_name='equipment_id' AND udt_name='int8';

    SELECT count(*) INTO n_deleted FROM information_schema.columns
     WHERE table_schema='public' AND table_name='workflow_templates'
       AND column_name='deleted_at' AND udt_name='timestamp';

    RAISE NOTICE 'V20261101_35 收尾读数: bool=%/16 confidence_boost=%/1 equipment_id=%/1 deleted_at=%/1',
        n_bool, n_conf, n_equip, n_deleted;

    IF n_bool <> 16 OR n_conf <> 1 OR n_equip <> 1 OR n_deleted <> 1 THEN
        RAISE EXCEPTION 'V20261101_35: 19 处只落地了 % 处', n_bool + n_conf + n_equip + n_deleted;
    END IF;
END $$;
