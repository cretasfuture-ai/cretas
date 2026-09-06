-- ============================================================
-- 给 process_sheet_rows.plan_id / report_reversal_logs.plan_id 补 FK (NOT VALID)
-- ============================================================
-- 任务: ARCH-PRODUCTION-PLAN-HARD-DELETE-PATH-20260906
--
-- 背景 (2026-09-06 实测, 只读 psql on cretas_prod_db):
--   BaseEntity(@MappedSuperclass) 上的 @SQLDelete 被 Hibernate **静默忽略** (R68 注释
--   自己记着这件事), 而 R68-FIX-A 当年只把 @Where 批量补进了 342 个子类, @SQLDelete
--   **没有跟着批量补** —— 改之前全仓只有 20 个实体带它, ProductionPlan 不在其中。
--   于是 productionPlanRepository.delete(plan) 一直是**物理 DELETE**。
--   同一个 PR 已给 ProductionPlan / ProductionBatch / ProcessSheetRow 补上 @SQLDelete,
--   本迁移补的是**另一半**: 数据库侧对物理删的拦截。
--
--   production_plans 只有 3 条 FK 指向它 (material_consumptions /
--   production_plan_batch_usages / production_settlement_output_lines), 全是
--   confdeltype = 'a' (NO ACTION)。process_sheet_rows.plan_id **一条 FK 都没有** ——
--   2026-08-29 那次带外多表 SQL 清理删掉 5 张 LIUSHANMEN 计划(以及 batches /
--   consumptions / settlements / sales orders)时漏了这张表, 留下 56 行已结算的工单行
--   成为孤儿(含 row 1500 —— prod 上唯一一条 semiFinished:true 的上游引用)。
--   这 56 行是**那天生产的唯一存档**, ⛔ 不得清理。
--
-- 为什么是 NOT VALID:
--   Postgres 的 NOT VALID 外键 = 「新写入必须合法, 存量违例照旧留着」。
--   父表侧的 RI 触发器 **照常创建、照常生效** —— 也就是说 ON DELETE RESTRICT 从这一刻
--   起就能拦住「物理删掉一条仍被引用的 production_plans 行」, 而 56 行存量孤儿不受影响。
--   ⛔ 不要有人事后跑 `ALTER TABLE ... VALIDATE CONSTRAINT`: 那会当场因为这 56 行失败。
--   要 VALIDATE 只有两条路, 都不是这个 PR 的范围: 给孤儿补回父行, 或删掉孤儿(⛔ 见上)。
--
-- 实测口径 (2026-09-06, prod):
--   process_sheet_rows            : 145 行, 其中 56 行 plan_id 无父行; NULL 0 行
--   report_reversal_logs          :  18 行, 其中 13 行 plan_id 无父行; NULL 3 行
--   process_sheet_row_change_log  : 152 行 / 56 孤儿  <- append-only 日志表, **不加 FK**
--   scheduling_alerts             : 204 行 / 163 孤儿 <- 日志表, **不加 FK**
--   process_entry_idempotency     :   0 行            <- 幂等表, 本 PR 不动
--   backup_stuck_plans_20260802   :   1 行            <- 带日期的备份表, 不动
--
-- 列类型 (FK 可行性, 实测):
--   production_plans.id           character varying(191) NOT NULL  (production_plans_pkey)
--   process_sheet_rows.plan_id    character varying(64)  NOT NULL
--   report_reversal_logs.plan_id  character varying(191) NULL
--   长度不同不影响 —— 两侧都是 character varying, 等值比较走同一个操作符类。
--
-- 🔴 为什么 H2 / @DataJpaTest 看不见这个迁移:
--   三个测试 profile (application-test / application-pg-test / application-r10-it)
--   全都写着 spring.flyway.enabled=false, H2 的 schema 由 hibernate ddl-auto=create
--   从实体注解生成 —— 而 ProcessSheetRow.planId 是一个普通 @Column, 没有 @JoinColumn,
--   所以 H2 上**不会**有这条 FK。⇒ 本迁移的四态验收只能在真 Postgres 上做
--   (本 PR 用一次性 docker postgres 跑过, 见 PR 正文), 源码级由
--   ProcessSheetRowPlanFkMigrationContractTest 钉住文本形状。
--
-- ⚠️ 连带改动: tests/v1-e2e/scripts/seed-and-reset.sh 原来直接
--   `DELETE FROM production_plans WHERE factory_id = 'F_E2E_TEST'`, 在 RESTRICT 下会被
--   拒绝。同一个 PR 已给它补上先删 process_sheet_rows / report_reversal_logs。
--
-- 🔴 第三块 uk_production_plan_create_request 是**连带必须做的**, 不是顺手优化:
--   把 productionPlanRepository.delete() 从物理删换成软删, 会让被删的那一行**继续占着**
--   幂等键。而查幂等键那一步 (ProductionPlanServiceImpl:2125 ->
--   findByFactoryIdAndCreatedByAndClientRequestId) 是 Spring Data 派生查询,
--   类级 @Where(deleted_at IS NULL) 会被拼进去 —— 它**看不见**软删行。
--   两边口径不一致 ⇒ 「查不到 -> INSERT -> 撞唯一索引 23505」, 正是
--   SoftDeleteBlindSpotContractTest 记的那一类缺陷 (uk_cache_key / uk_voucher_factory_number
--   都是这个形状), 只是这次由本 PR 亲手把它从潜伏变成可达。
--   ⇒ 让索引与查询口径对齐: 索引也只管活行。
--   实测 (2026-09-06 prod): production_plans 61 行, 8 行软删、其中 **7 行带
--   client_request_id 正占着索引**; 活行里带 client_request_id 的 41 行, 按新谓词分组
--   **零重复** ⇒ 收紧谓词只会从索引里去掉行, 不可能建不出来。
--
-- 幂等: 三个 DO 块都先查 pg_constraint / pg_indexes / information_schema, 重复执行是 no-op。
-- ============================================================

-- ── process_sheet_rows.plan_id ──────────────────────────────────────────────
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = current_schema() AND table_name = 'process_sheet_rows'
    ) THEN
        RAISE NOTICE 'process_sheet_rows 不存在, 跳过';
        RETURN;
    END IF;

    -- RESTRICT 的父表删除检查要按 plan_id 反查子表; uk_sheet_row 的前导列是
    -- factory_id 而不是 plan_id, 用不上 ⇒ 单独建一条索引。
    CREATE INDEX IF NOT EXISTS idx_process_sheet_rows_plan_id
        ON process_sheet_rows (plan_id);

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_process_sheet_rows_plan'
          AND conrelid = 'process_sheet_rows'::regclass
    ) THEN
        ALTER TABLE process_sheet_rows
            ADD CONSTRAINT fk_process_sheet_rows_plan
            FOREIGN KEY (plan_id) REFERENCES production_plans (id)
            ON DELETE RESTRICT
            NOT VALID;
    END IF;
END $$;

-- ── report_reversal_logs.plan_id ────────────────────────────────────────────
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = current_schema() AND table_name = 'report_reversal_logs'
    ) THEN
        RAISE NOTICE 'report_reversal_logs 不存在, 跳过';
        RETURN;
    END IF;

    CREATE INDEX IF NOT EXISTS idx_report_reversal_logs_plan_id
        ON report_reversal_logs (plan_id);

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_report_reversal_logs_plan'
          AND conrelid = 'report_reversal_logs'::regclass
    ) THEN
        ALTER TABLE report_reversal_logs
            ADD CONSTRAINT fk_report_reversal_logs_plan
            FOREIGN KEY (plan_id) REFERENCES production_plans (id)
            ON DELETE RESTRICT
            NOT VALID;
    END IF;
END $$;

-- ── uk_production_plan_create_request: 索引口径与查询口径对齐, 只管活行 ─────
-- ⚠️ 同一个名字可能是**两种对象**, 拆开处理(2026-09-06 #629 终审提出, 实测两侧都跑过):
--   · prod 上它是**独立的部分唯一 INDEX**(V20261029_05 用 CREATE UNIQUE INDEX 建的),
--     pg_constraint 里查不到 ⇒ 只能 DROP INDEX。
--   · 而 ProductionPlan 实体上写着 @Table(uniqueConstraints = @UniqueConstraint(
--     name = "uk_production_plan_create_request", ...)) ⇒ 任何由 Hibernate
--     ddl-auto=create/update 建起来的库(全新环境/切片测试)上它是 **UNIQUE CONSTRAINT**。
--     那种情况下 DROP INDEX 会直接报「cannot drop index ... because constraint ... requires it」。
--   ⇒ 先按约束查, 查到就 ALTER TABLE ... DROP CONSTRAINT; 否则才 DROP INDEX。
DO $$
DECLARE
    current_def text;
    is_constraint boolean;
BEGIN
    SELECT indexdef INTO current_def
    FROM pg_indexes
    WHERE schemaname = current_schema()
      AND indexname = 'uk_production_plan_create_request';

    IF current_def IS NULL THEN
        RAISE NOTICE 'uk_production_plan_create_request 不存在, 跳过';
        RETURN;
    END IF;

    IF position('deleted_at IS NULL' in current_def) > 0 THEN
        RAISE NOTICE 'uk_production_plan_create_request 已经只管活行, 跳过';
        RETURN;
    END IF;

    SELECT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uk_production_plan_create_request'
          AND conrelid = 'production_plans'::regclass
    ) INTO is_constraint;

    IF is_constraint THEN
        RAISE NOTICE '它是 UNIQUE CONSTRAINT(Hibernate ddl-auto 建的), 走 DROP CONSTRAINT';
        ALTER TABLE production_plans DROP CONSTRAINT uk_production_plan_create_request;
    ELSE
        RAISE NOTICE '它是独立 UNIQUE INDEX(prod 形态), 走 DROP INDEX';
        DROP INDEX uk_production_plan_create_request;
    END IF;

    CREATE UNIQUE INDEX uk_production_plan_create_request
        ON production_plans (factory_id, created_by, client_request_id)
        WHERE client_request_id IS NOT NULL AND deleted_at IS NULL;
END $$;
