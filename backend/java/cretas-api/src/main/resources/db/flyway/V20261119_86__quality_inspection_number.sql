-- V20261119_86: 质检记录业务编号 quality_inspections.inspection_number (PROTO-QC-NUMBER)
--
-- 为什么: web-admin 质检列表/详情一直把内部 UUID 截前 8 位当「质检编号」给用户看 (如 3F2A9C1B),
-- 质检员、仓管没法拿它对单、口头报号。改成按工厂按天递增的业务编号: QI-YYYYMMDD-NNNN
-- (前缀 QI 与 EncodingRuleServiceImpl.getDefaultPrefix("QUALITY_INSPECTION") 同一约定)。
--
-- 为什么在数据库里发号 (BEFORE INSERT 触发器), 而不是 Java 里调 EncodingRuleService.generateCode:
--   1. 写质检记录的入口有 6+ 处 (REST 提交、动态质检表、AI 质检工具 3 个、供应链编排、旧 updateInspection 补建),
--      触发器保证每条 INSERT 都发号, 包括原生 SQL 和蓝绿切换窗口里旧槽位 (不认识这一列) 写进来的行。
--   2. EncodingRuleService 在本厂没配规则时回落 `System.currentTimeMillis() % 10000`, 既不递增也会撞号;
--      配了规则时「每日重置」是先读后写, 两个当天首单并发都会重置成 0 拿到同一个号。
--   3. 本号取「本厂本日已有编号的最大序号 + 1」, 统计**含软删行** ⇒ 删掉的号不会再发给新记录。
--   并发: 同厂同日用 pg_advisory_xact_lock 串行, 锁到事务结束; 唯一索引 (含软删行) 兜底。
--   日期: 用本行 created_at (应用按 JVM 本地时间写入) 的日期, 没有时用数据库本地时间。
--
-- Java 侧: QualityInspection.inspectionNumber 是 @Generated(INSERT) + insertable/updatable=false,
--   Hibernate 插入后回读本列; 应用永远不写也不改它。
--
-- 存量回填 (本迁移第 4 步), 影响行数口径:
--   全部工厂、含软删行、inspection_number 为空且 COALESCE(created_at 的日期, inspection_date) 不为空的行。
--   编号日期 = 该行 created_at 的日期 (无则 inspection_date); 同厂同日按 created_at、id 排序依次编号,
--   接在该厂该日已有编号的最大序号之后 (首次执行时本列全空, 即从 0001 起)。
--   两个日期都为空的行不编号, 界面显示「未编号」。实际影响行数由 RAISE NOTICE 打到 Flyway 日志 (DB: 行)。
--   回填期间临时停用 quality_inspections_update_timestamp 触发器 (若存在), 不让回填把 updated_at 全部刷成今天。
--
-- 版本号: out-of-order=false, 当天已有 _51 (main) / _71 (#1816, #1844 未合), 取 _86。

-- 1. 列
ALTER TABLE quality_inspections
    ADD COLUMN IF NOT EXISTS inspection_number VARCHAR(40);

COMMENT ON COLUMN quality_inspections.inspection_number IS '质检业务编号 QI-YYYYMMDD-NNNN, 同厂同日递增, 由 BEFORE INSERT 触发器 trg_quality_inspection_number 发号 (含软删行不复用); 应用只读';

-- 2. 唯一索引: 含软删行 (号永不复用, 所以软删行也占着号); 只排除未编号的行
CREATE UNIQUE INDEX IF NOT EXISTS uk_quality_inspections_factory_number
    ON quality_inspections (factory_id, inspection_number)
    WHERE inspection_number IS NOT NULL;

-- 3. 发号触发器
CREATE OR REPLACE FUNCTION assign_quality_inspection_number() RETURNS trigger AS $$
DECLARE
    v_prefix text;
    v_next   bigint;
BEGIN
    IF NEW.inspection_number IS NOT NULL OR NEW.factory_id IS NULL THEN
        RETURN NEW;
    END IF;
    v_prefix := 'QI-' || to_char(COALESCE(NEW.created_at, LOCALTIMESTAMP), 'YYYYMMDD') || '-';
    -- 同厂同日串行: 后来者等前一个事务提交后才读最大号
    PERFORM pg_advisory_xact_lock(hashtextextended('quality_inspection_number:' || NEW.factory_id || ':' || v_prefix, 0));
    -- 含软删行 (不加 deleted_at 条件): 删掉的号不再发
    SELECT COALESCE(MAX(CAST(substr(q.inspection_number, length(v_prefix) + 1) AS bigint)), 0) + 1
      INTO v_next
      FROM quality_inspections q
     WHERE q.factory_id = NEW.factory_id
       AND q.inspection_number LIKE v_prefix || '%'
       AND substr(q.inspection_number, length(v_prefix) + 1) ~ '^[0-9]{1,18}$';
    NEW.inspection_number := v_prefix
        || CASE WHEN v_next < 10000 THEN lpad(v_next::text, 4, '0') ELSE v_next::text END;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_quality_inspection_number ON quality_inspections;
CREATE TRIGGER trg_quality_inspection_number
    BEFORE INSERT ON quality_inspections
    FOR EACH ROW EXECUTE FUNCTION assign_quality_inspection_number();

-- 4. 存量回填
DO $$
DECLARE
    v_ts_trigger_enabled boolean;
    v_rows integer;
BEGIN
    SELECT t.tgenabled <> 'D' INTO v_ts_trigger_enabled
      FROM pg_trigger t
     WHERE t.tgrelid = 'quality_inspections'::regclass
       AND t.tgname = 'quality_inspections_update_timestamp'
       AND NOT t.tgisinternal;
    IF v_ts_trigger_enabled THEN
        EXECUTE 'ALTER TABLE quality_inspections DISABLE TRIGGER quality_inspections_update_timestamp';
    END IF;

    WITH todo AS (
        SELECT q.id, q.factory_id, q.created_at,
               COALESCE(CAST(q.created_at AS date), q.inspection_date) AS number_date
          FROM quality_inspections q
         WHERE q.inspection_number IS NULL
           AND COALESCE(CAST(q.created_at AS date), q.inspection_date) IS NOT NULL
    ), existing AS (
        SELECT q.factory_id, substr(q.inspection_number, 4, 8) AS day_key,
               MAX(CAST(substr(q.inspection_number, 13) AS bigint)) AS max_seq
          FROM quality_inspections q
         WHERE q.inspection_number ~ '^QI-[0-9]{8}-[0-9]{1,18}$'
         GROUP BY q.factory_id, substr(q.inspection_number, 4, 8)
    ), numbered AS (
        SELECT t.id, t.number_date,
               COALESCE(e.max_seq, 0)
                 + ROW_NUMBER() OVER (PARTITION BY t.factory_id, t.number_date
                                      ORDER BY t.created_at NULLS LAST, t.id) AS seq
          FROM todo t
          LEFT JOIN existing e
            ON e.factory_id = t.factory_id AND e.day_key = to_char(t.number_date, 'YYYYMMDD')
    )
    UPDATE quality_inspections q
       SET inspection_number = 'QI-' || to_char(n.number_date, 'YYYYMMDD') || '-'
           || CASE WHEN n.seq < 10000 THEN lpad(n.seq::text, 4, '0') ELSE n.seq::text END
      FROM numbered n
     WHERE q.id = n.id;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    RAISE NOTICE 'V20261119_86 quality_inspections.inspection_number backfilled % rows (all factories, incl. soft-deleted)', v_rows;

    IF v_ts_trigger_enabled THEN
        EXECUTE 'ALTER TABLE quality_inspections ENABLE TRIGGER quality_inspections_update_timestamp';
    END IF;
END $$;
