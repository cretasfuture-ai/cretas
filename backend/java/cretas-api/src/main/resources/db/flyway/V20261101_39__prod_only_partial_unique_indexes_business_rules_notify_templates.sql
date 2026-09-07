-- ============================================================
-- 把两个「只活在 prod 上」的部分唯一索引补进迁移
-- ============================================================
-- 任务: OPS-PROD-ONLY-PARTIAL-INDEXES
-- ── 🎯 这两个索引的来历（2026-09-07 补，⛔ 它们不是「凭空补上的定义」）──────────
-- 它们**原本就是由迁移创建的**，只是那两个文件后来被从仓库里删掉了。
-- prod 的 flyway_schema_history 里还留着记录：
--
--     V20260622_99__notify_templates_partial_unique.sql   applied 2026-05-22 08:13:32
--     V20260822_99__business_rules_partial_unique.sql     applied 2026-05-22 09:35:34
--     （两者都在 db/flyway / db/migration* / database/ 里**全都找不到**）
--
-- ⚠️ 注意版本号在应用日期的**未来**（应用于 05-22，号却是 06-22 / 08-22）
--    ⇒ 这是**手挑大号的 hotfix**，跑完即从仓库删掉。
-- ⚠️ 而且那两条各在 history 里出现**两次**（同一 version 两行 success=true）——
--    Flyway 不会把同一个 version 应用两次 ⇒ 那张台账本身被人手工动过。
--    见票 OPS-FLYWAY-HISTORY-TABLE-WAS-HAND-EDITED-20260907。
--
-- ⇒ 本迁移的性质因此是：**把一条被删掉的迁移重新落进版本控制**，
--    ⛔ 不是「补一个从没有过的定义」。这两句区别不小：前者说明 prod 上那两个索引
--    有过设计意图，后者会让人以为它们是某次手工操作的残留。
--
-- 📌 另一条来历（产品口径，2026-09-07 Steve 裁定）：**业务编号删掉后可以重新使用**
--    ⇒ 部分唯一索引（`WHERE deleted_at IS NULL`）是唯一正解，
--    ⛔ 不是「按 prod 现状照抄」。
--
-- 由 PartialUniqueIndexBackingSoftDeleteContractTest 的 PROD_ONLY_NOT_IN_MIGRATIONS 登记,
-- 那里写着「补它要单独一版迁移 + 存量重复检查, 与本次 P0 是两件事, 已单独登记」—— 就是这一版。
--
-- ── 实测的两边形态 (2026-09-07, ⛔ 不从注释推) ──────────────────────────────────
--
-- prod (cretas_prod_db):
--   business_rules   约束只有 business_rules_pkey; 索引里有
--     uq_business_rules_factory_code_active  (factory_id, rule_code)   WHERE deleted_at IS NULL
--   notify_templates 约束只有 notify_templates_pkey; 索引里有
--     uq_notify_templates_factory_code_active (factory_id, template_code) WHERE deleted_at IS NULL
--   ⇒ **全量唯一键在 prod 上早就没了**, 只剩部分索引。
--
-- 从迁移新建的库:
--   V20260621_01:16  CONSTRAINT uq_notify_templates_factory_code UNIQUE (factory_id, template_code)
--   V20260622_01:19  UNIQUE(factory_id, rule_code)      ← **内联无名**, PG 自动命名
--   ⇒ 新库拿到的是**全量**唯一键, **比 prod 严**。
--
-- 🔴 后果不是「不一致」这么抽象: 这两张表都是软删表, 在新库上「软删一条再建同 code」会撞约束,
--    而在 prod 上合法。任何从迁移新建的 PostgreSQL(测试环境 / 灾备 / 新租户库)都会重现这个
--    已经在 prod 上修掉的 bug —— 只是没人在那种库上跑过这条路径, 所以一直没人看见。
--
-- ── 存量重复检查 (prod 实测) ────────────────────────────────────────────────────
--   business_rules   活行 1 条,  (factory_id, rule_code) 重复组 0
--   notify_templates 活行 0 条,  (factory_id, template_code) 重复组 0
--   ⚠️ 阴性对照(证明这个查法**真能**数出重复, 否则「0 组」与「查法坏了」同形):
--      同一个查询**去掉** deleted_at 过滤后, business_rules 数出 **1** 组重复
--      ⇒ 存在「软删行与活行同键」的真实数据 ⇒ 这条谓词是**承重的**, ⛔ 不能建成全量唯一。
--
-- ⛔ 不用「IF NOT EXISTS 就跳过」那种防御式写法(2026-09-04 role_definitions 事故:
--    防御 DO-block 静默 no-op 还报 success)。每一步都显式断言前置状态, 不符就 RAISE EXCEPTION。
-- ⛔ 不写死 id, 不按名字找 business_rules 那个约束 —— 它是**内联无名**的, 名字由 PG 生成。
-- ============================================================

-- ── 0) 存量重复检查: 有重复就**停下**, ⛔ 不静默跳过 ──────────────────────────
--    这一步放在最前面: 建唯一索引失败的报错是 PG 的通用措辞, 说不清是哪张表哪几行。
DO $$
DECLARE
    dup_rules INT;
    dup_tpls  INT;
BEGIN
    SELECT count(*) INTO dup_rules FROM (
        SELECT factory_id, rule_code FROM public.business_rules
         WHERE deleted_at IS NULL GROUP BY 1, 2 HAVING count(*) > 1) d;
    SELECT count(*) INTO dup_tpls FROM (
        SELECT factory_id, template_code FROM public.notify_templates
         WHERE deleted_at IS NULL GROUP BY 1, 2 HAVING count(*) > 1) d;

    IF dup_rules > 0 THEN
        RAISE EXCEPTION 'V20261101_39: business_rules 上有 % 组活行 (factory_id, rule_code) 重复 —— 先清理存量再跑本迁移', dup_rules;
    END IF;
    IF dup_tpls > 0 THEN
        RAISE EXCEPTION 'V20261101_39: notify_templates 上有 % 组活行 (factory_id, template_code) 重复 —— 先清理存量再跑本迁移', dup_tpls;
    END IF;
    RAISE NOTICE 'V20261101_39: 存量重复检查通过 (business_rules % 组 / notify_templates % 组)', dup_rules, dup_tpls;
END $$;

-- ── 1) business_rules: 内联无名全量唯一 -> 部分唯一索引 ────────────────────────
--    ⚠️ 按**约束定义**找, 不按名字 —— 它在 V20260622_01 里是 `UNIQUE(factory_id, rule_code)`,
--       名字由 PG 生成(通常 business_rules_factory_id_rule_code_key), ⛔ 不可写死。
DO $$
DECLARE
    con_name TEXT;
BEGIN
    SELECT c.conname INTO con_name
      FROM pg_constraint c
     WHERE c.conrelid = 'public.business_rules'::regclass
       AND c.contype = 'u'
       AND pg_get_constraintdef(c.oid) = 'UNIQUE (factory_id, rule_code)';

    IF con_name IS NULL THEN
        -- 允许「已经是 prod 那个形态」这一种, 但必须是**部分索引已存在**才算,
        -- ⛔ 不允许两者都没有(那是"这张表现在完全不设防", 与本迁移的意图相反)。
        IF to_regclass('public.uq_business_rules_factory_code_active') IS NULL THEN
            RAISE EXCEPTION 'V20261101_39: business_rules 既没有全量唯一约束也没有部分索引 —— 形状不认识, 停下';
        END IF;
        RAISE NOTICE 'V20261101_39: business_rules 已是部分索引形态(prod), 跳过删约束';
    ELSE
        EXECUTE format('ALTER TABLE public.business_rules DROP CONSTRAINT %I', con_name);
        RAISE NOTICE 'V20261101_39: 已删除 business_rules 的全量唯一约束 %', con_name;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_business_rules_factory_code_active
    ON public.business_rules (factory_id, rule_code)
    WHERE deleted_at IS NULL;

COMMENT ON INDEX public.uq_business_rules_factory_code_active IS
    '业务规则 code 唯一(软删后释放)。prod 上 2026-09-07 之前就是这个形态, 本迁移把它补进版本控制。';

-- ── 2) notify_templates: 具名全量唯一 -> 部分唯一索引 ──────────────────────────
DO $$
DECLARE
    con_name TEXT;
BEGIN
    SELECT c.conname INTO con_name
      FROM pg_constraint c
     WHERE c.conrelid = 'public.notify_templates'::regclass
       AND c.contype = 'u'
       AND pg_get_constraintdef(c.oid) = 'UNIQUE (factory_id, template_code)';

    IF con_name IS NULL THEN
        IF to_regclass('public.uq_notify_templates_factory_code_active') IS NULL THEN
            RAISE EXCEPTION 'V20261101_39: notify_templates 既没有全量唯一约束也没有部分索引 —— 形状不认识, 停下';
        END IF;
        RAISE NOTICE 'V20261101_39: notify_templates 已是部分索引形态(prod), 跳过删约束';
    ELSE
        EXECUTE format('ALTER TABLE public.notify_templates DROP CONSTRAINT %I', con_name);
        RAISE NOTICE 'V20261101_39: 已删除 notify_templates 的全量唯一约束 %', con_name;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_notify_templates_factory_code_active
    ON public.notify_templates (factory_id, template_code)
    WHERE deleted_at IS NULL;

COMMENT ON INDEX public.uq_notify_templates_factory_code_active IS
    '通知模板 code 唯一(软删后释放)。prod 上 2026-09-07 之前就是这个形态, 本迁移把它补进版本控制。';

-- ── 3) 断言最终形状 (⛔ 不是「没报错就算过」) ─────────────────────────────────
--    ⚠️ 上面那两条建索引语句的 `IF NOT EXISTS` 只按**名字**判存在, 不比对定义 ——
--       (⛔ 这里刻意不写出那三个词的字面串: PartialUniqueIndexBackingSoftDeleteContractTest
--        用 `CREATE` + `UNIQUE` + `INDEX` 的正则数语句且**不剔注释**, 写全了会让它多数一条,
--        把「树里有几条部分唯一索引」这个用来跨分支比对的读数弄脏。同族坑本仓已中三次。)
--       prod 上那两个已经存在, 所以必须在这里**读回定义**确认它确实是部分索引,
--       否则「名字占着而定义是别的」会被静默接受。
DO $$
DECLARE
    rules_idx TEXT;
    tpls_idx  TEXT;
    full_cons INT;
BEGIN
    SELECT indexdef INTO rules_idx FROM pg_indexes
     WHERE schemaname = 'public' AND indexname = 'uq_business_rules_factory_code_active';
    IF rules_idx IS NULL OR rules_idx NOT ILIKE '%deleted_at IS NULL%' THEN
        RAISE EXCEPTION 'V20261101_39: uq_business_rules_factory_code_active 缺失或不是部分索引: %', rules_idx;
    END IF;

    SELECT indexdef INTO tpls_idx FROM pg_indexes
     WHERE schemaname = 'public' AND indexname = 'uq_notify_templates_factory_code_active';
    IF tpls_idx IS NULL OR tpls_idx NOT ILIKE '%deleted_at IS NULL%' THEN
        RAISE EXCEPTION 'V20261101_39: uq_notify_templates_factory_code_active 缺失或不是部分索引: %', tpls_idx;
    END IF;

    -- 两个全量唯一键都必须**不在了** —— 留着的话它比部分索引严, 部分索引等于白建。
    SELECT count(*) INTO full_cons FROM pg_constraint
     WHERE conrelid IN ('public.business_rules'::regclass, 'public.notify_templates'::regclass)
       AND contype = 'u';
    IF full_cons <> 0 THEN
        RAISE EXCEPTION 'V20261101_39: 两张表上还剩 % 个全量唯一约束 —— 它会盖过部分索引', full_cons;
    END IF;

    -- 阴性对照: 主键必须**还在**(本迁移只动唯一约束, 一个字都不该碰主键)。
    -- 没有这一条,「全量唯一约束 0 个」与「我把这两张表的约束全删了」读数相同。
    IF (SELECT count(*) FROM pg_constraint
         WHERE conrelid IN ('public.business_rules'::regclass, 'public.notify_templates'::regclass)
           AND contype = 'p') <> 2 THEN
        RAISE EXCEPTION 'V20261101_39: 两张表的主键应当各自保持不动, 实测数量不为 2';
    END IF;

    RAISE NOTICE 'V20261101_39: 形状断言通过 (两个部分唯一索引就位 / 全量唯一键 0 个 / 主键 2 个未动)';
END $$;
