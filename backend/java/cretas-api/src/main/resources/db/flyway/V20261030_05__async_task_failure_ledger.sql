-- @Async 失败的出口台账。
--
-- 为什么需要它 (2026-08-19 prod 普查):
--   @Async void 方法抛出的异常, 当前只被 Spring 默认的 SimpleAsyncUncaughtExceptionHandler
--   打一条 ERROR 就丢掉 —— 用户侧无感 (HTTP 仍然 200)、无记录、无补偿。
--   实测 error 流 2026-06-19 ~ 2026-08-19 共 211 次逃逸, 全部来自 3 个监听器:
--     86  SalesFinanceApproveVoucherListener.onFinanceApproved       -> vflag 永久停在 PENDING
--     82  SalesOrderShortageReportListener.onSalesOrderFinanceApproved -> 缺料报告全丢
--     43  SupplyChainOrchestrator.onSalesOrderFinanceApproved        -> 生产计划没建出来
--   同期 production_plans 里 PP-AUTO-% 是 0 行, sales_orders 19/38 卡在 PENDING,
--   而三张业务表的 vflag=FAILED 全是 0 —— 写 FAILED 的正是抛异常的那次 save。
--
-- ⛔ 为什么不是 JPA 实体 (刻意没有 @Entity, 由 JdbcTemplate 直接写):
--   要记的失败本身就是 JPA 的失败 —— 211 次里 110 次 UnexpectedRollbackException、
--   86 次 ObjectOptimisticLockingFailureException、15 次 DataIntegrityViolationException。
--   用 JPA 记 JPA 的失败, 记录器和被记录的东西共用同一套会坏的机器。
--   本仓已有实测: SalesOrderShortageReportListener.persistFailedPlaceholder 就是
--   「用 JPA save 写一行 FAILED 占位」, prod 上 76/77 次连占位行都没写进去
--   (该方法在同一个已被标记 rollback-only 的事务里跑, 且 JSONB 列的 Hibernate
--    dirty-check deepCopy 会二次反序列化失败)。
--   ⇒ 本表: 无实体 / 无 @Version / 无 dirty-check / 无 JSONB, 单条
--     INSERT ... ON CONFLICT DO UPDATE 原子完成, 蓝绿双 JVM (10010/10020) 并发安全。
--
-- ⚠️ 没有 to_regclass 守卫: 那条惯例是给「ALTER/回填一张 Hibernate ddl-auto 建的表」
--    用的 (全新 CI DB 上 Flyway 先于 Hibernate 跑)。本表没有对应实体, Hibernate 永远
--    不会碰它, 本迁移是唯一创建者 ⇒ CREATE TABLE IF NOT EXISTS 已经完备。

CREATE TABLE IF NOT EXISTS async_task_failures (
    id                BIGSERIAL PRIMARY KEY,

    -- 去重键: methodSignature|factoryId|businessKey|exceptionClass, 空值写字面量 '-'。
    -- ⛔ 不用 (factory_id, business_key, ...) 复合唯一索引 —— PG 默认把 NULL 视为互不相等,
    --    提取不到 factory_id 的行会绕过去重无限增长。用一个永不为 NULL 的字符串键规避。
    dedup_key         VARCHAR(255) NOT NULL,

    -- ⚠️ 可空, 且 NULL 的含义是「提取器判不出来」, 不是「不属于任何工厂」。
    -- prod 实测 211 条逃逸的 MDC 槽位 [u:] [f:] 全是空的 —— 异步线程不带用户/工厂上下文,
    -- 所以 factory_id 只能从事件参数里取。取不到就留 NULL, ⛔ 不许兜底成 'UNKNOWN' 之类,
    -- 那会把「我不知道」翻译成一个能被下游当真的值。
    factory_id        VARCHAR(50),

    method_signature  VARCHAR(255) NOT NULL,
    business_key      VARCHAR(191),
    exception_class   VARCHAR(255) NOT NULL,
    exception_message TEXT,
    stack_excerpt     TEXT,

    -- 运维该做什么。由代码里的登记表提供, 不是自由文本。
    remediation_hint  TEXT,

    status            VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    occurrence_count  INTEGER   NOT NULL DEFAULT 1,
    first_seen_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    last_seen_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    resolved_at       TIMESTAMP,
    resolved_by       VARCHAR(100),
    resolve_note      TEXT,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_async_task_failure_dedup UNIQUE (dedup_key),
    CONSTRAINT chk_async_task_failure_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT chk_async_task_failure_count CHECK (occurrence_count >= 1)
);

-- 定时跑批只问「还有没有没处理完的」——这个索引就是那条查询的全部。
CREATE INDEX IF NOT EXISTS idx_async_task_failure_open
    ON async_task_failures (status, last_seen_at DESC);

-- 运维按工厂看明细。
CREATE INDEX IF NOT EXISTS idx_async_task_failure_factory
    ON async_task_failures (factory_id, status, last_seen_at DESC);

COMMENT ON TABLE async_task_failures IS
    '@Async void 方法逃逸异常的台账。只收【已登记】的方法(见 AsyncFailureEnrollment), '
    '未登记的仍然只打日志 —— 宁可窄而可信, 不要宽到被人忽略。';
COMMENT ON COLUMN async_task_failures.factory_id IS
    'NULL = 提取器判不出工厂, 不是「没有工厂」。异步线程不带 MDC 上下文, 只能从事件参数取。';
COMMENT ON COLUMN async_task_failures.dedup_key IS
    'methodSignature|factoryId|businessKey|exceptionClass, 空值写 ''-''。超 255 字符会被截断 —— '
    '截断后两条不同的失败理论上可能并成一条, 这是已知的代理判据局限。';
COMMENT ON COLUMN async_task_failures.occurrence_count IS
    '跨 resolve 周期累加。已 RESOLVED 的失败再次发生会被重新置回 OPEN —— 复发是新消息。';
