-- 按工厂备份套餐 (手册 docs/security/tenant-backup-plans-runbook.md; Steve 2026-10-26 拍板)
--
-- 平台整库灾备 (scripts/ops/backup-db.sh) 照旧, 是底线; 这里是在它之上、按工厂可配置的「工厂数据快照」:
-- 定时调用整厂导出 (FactoryDataService, trigger_source = BACKUP_SCHEDULER) -> age 加密 -> OSS
-- oss://<bucket>/tenants/<工厂编号>/ -> 按保留期清理。
--
-- 1) tenant_backup_plans: 每厂一行套餐。频率 × 保留期自由组合; 只有平台能改 (口径 Q6)。
--    新建工厂由调度器自动补一行「每天 × 7 天」(source = AUTO_NEW_FACTORY); 本迁移【不回填】现有工厂
--    (口径: 现有工厂由平台逐个开)。
-- 2) tenant_backup_plan_changes: 套餐每次改动留痕 (谁、何时、改前、改后)。
-- 3) tenant_backup_runs: 每次执行一行 (含被拒绝的 SKIPPED)。审计能区分定时任务与手动 (trigger_source)。
--
-- ⛔ 三张表都不加指向 factories 的外键: 工厂删除后执行记录还要在, 证明「这个厂的备份删干净了」。
-- 只存编号、计数、哈希和时间, 不存业务内容; 整厂导出 / 删除把它们登记为 RECEIPT (不导出、不删)。

CREATE TABLE IF NOT EXISTS tenant_backup_plans (
    factory_id          VARCHAR(64)  PRIMARY KEY,
    enabled             BOOLEAN      NOT NULL,
    frequency           VARCHAR(16)  NOT NULL,
    retention_days      INT          NOT NULL,
    include_attachments BOOLEAN      NOT NULL DEFAULT FALSE,
    key_holder          VARCHAR(16)  NOT NULL DEFAULT 'PLATFORM',
    -- 客户自持钥匙时的 age 公钥 (只有公钥; 私钥在客户手里, 我们解不开)
    customer_recipient  VARCHAR(128),
    source              VARCHAR(24)  NOT NULL,
    updated_by_admin_id BIGINT,
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL,
    CONSTRAINT ck_tenant_backup_plans_frequency CHECK (frequency IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    CONSTRAINT ck_tenant_backup_plans_retention CHECK (retention_days IN (7, 30, 90, 365)),
    CONSTRAINT ck_tenant_backup_plans_key_holder CHECK (key_holder IN ('PLATFORM', 'CUSTOMER')),
    CONSTRAINT ck_tenant_backup_plans_customer_key
        CHECK (key_holder <> 'CUSTOMER' OR customer_recipient IS NOT NULL),
    CONSTRAINT ck_tenant_backup_plans_source CHECK (source IN ('AUTO_NEW_FACTORY', 'PLATFORM'))
);

COMMENT ON TABLE tenant_backup_plans IS '按工厂备份套餐; 无外键, 工厂删除后保留';

CREATE TABLE IF NOT EXISTS tenant_backup_plan_changes (
    id          BIGSERIAL    PRIMARY KEY,
    factory_id  VARCHAR(64)  NOT NULL,
    actor_type  VARCHAR(32)  NOT NULL,
    actor_id    BIGINT,
    actor_role  VARCHAR(64),
    before_plan JSONB,
    after_plan  JSONB        NOT NULL,
    reason      VARCHAR(500),
    changed_at  TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tenant_backup_plan_changes_factory
    ON tenant_backup_plan_changes (factory_id, changed_at DESC);

CREATE TABLE IF NOT EXISTS tenant_backup_runs (
    id                 BIGSERIAL    PRIMARY KEY,
    factory_id         VARCHAR(64)  NOT NULL,
    trigger_source     VARCHAR(32)  NOT NULL,
    actor_type         VARCHAR(32),
    actor_id           BIGINT,
    status             VARCHAR(16)  NOT NULL,
    started_at         TIMESTAMP    NOT NULL,
    finished_at        TIMESTAMP,
    export_request_id  BIGINT,
    key_holder         VARCHAR(16),
    attachments_mode   VARCHAR(16),
    retention_days     INT,
    object_key         VARCHAR(255),
    package_bytes      BIGINT,
    encrypted_bytes    BIGINT,
    encrypted_sha256   VARCHAR(64),
    error_message      TEXT,
    CONSTRAINT ck_tenant_backup_runs_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED'))
);

CREATE INDEX IF NOT EXISTS idx_tenant_backup_runs_factory
    ON tenant_backup_runs (factory_id, started_at DESC);

COMMENT ON TABLE tenant_backup_runs IS '按工厂备份执行记录; 无外键, 工厂删除后保留';
