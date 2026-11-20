-- 每日差异核对②: 每日扫描留痕 + 按工厂配置负责人。
--   1. daily_reconcile_runs: 每个工厂每次定时 / 手动扫描一条记录, 包括「没量到」(阈值没配 / 近 30 天没有可评估的计划 / 全部算不出 / 扫描失败)。
--      ⛔ 没量到不当「正常」: status = NOT_MEASURED 并写明原因。
--   2. factory_settings 增加四个可空列: 成本差异 / 物料平衡两类异常的负责人 (指定到人, 或只指定岗位)。
--      NULL = 未配置, 创建异常时回退到代码里的默认岗位 (成本→finance_manager, 平衡→production_manager)。
-- 存量不回填。IF NOT EXISTS: 可重复执行。

CREATE TABLE IF NOT EXISTS daily_reconcile_runs (
    id                   VARCHAR(36)  PRIMARY KEY,
    factory_id           VARCHAR(50)  NOT NULL,
    trigger_source       VARCHAR(32)  NOT NULL,
    started_at           TIMESTAMP    NOT NULL,
    finished_at          TIMESTAMP,
    status               VARCHAR(16)  NOT NULL CHECK (status IN ('MEASURED', 'PARTIAL', 'NOT_MEASURED', 'FAILED')),
    not_measured_reason  TEXT,
    variance_configured  BOOLEAN      NOT NULL DEFAULT FALSE,
    balance_configured   BOOLEAN      NOT NULL DEFAULT FALSE,
    threshold_source     VARCHAR(16),
    evaluated_count      INTEGER      NOT NULL DEFAULT 0,
    created_count        INTEGER      NOT NULL DEFAULT 0,
    refreshed_count      INTEGER      NOT NULL DEFAULT 0,
    unknown_count        INTEGER      NOT NULL DEFAULT 0,
    failed_count         INTEGER      NOT NULL DEFAULT 0,
    notified_count       INTEGER      NOT NULL DEFAULT 0,
    notify_failed_count  INTEGER      NOT NULL DEFAULT 0,
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_daily_reconcile_runs_factory_started
    ON daily_reconcile_runs (factory_id, started_at DESC);

ALTER TABLE factory_settings
    ADD COLUMN IF NOT EXISTS cost_deviation_variance_owner_role     VARCHAR(50),
    ADD COLUMN IF NOT EXISTS cost_deviation_variance_owner_user_id  BIGINT,
    ADD COLUMN IF NOT EXISTS cost_deviation_balance_owner_role      VARCHAR(50),
    ADD COLUMN IF NOT EXISTS cost_deviation_balance_owner_user_id   BIGINT;

COMMENT ON COLUMN factory_settings.cost_deviation_variance_owner_user_id IS
    '成本差异异常的默认负责人 (用户 id, 必须属于本厂). NULL = 只按岗位.';
COMMENT ON COLUMN factory_settings.cost_deviation_variance_owner_role IS
    '成本差异异常的默认负责岗位. NULL = 代码默认 finance_manager.';
COMMENT ON COLUMN factory_settings.cost_deviation_balance_owner_user_id IS
    '物料平衡异常的默认负责人 (用户 id, 必须属于本厂). NULL = 只按岗位.';
COMMENT ON COLUMN factory_settings.cost_deviation_balance_owner_role IS
    '物料平衡异常的默认负责岗位. NULL = 代码默认 production_manager.';
