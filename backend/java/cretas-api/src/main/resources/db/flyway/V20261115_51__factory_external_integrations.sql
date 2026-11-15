-- =====================================================================
-- 统一原型迁移 P10「外部软件」(MIGRATION-PLAN 4.2 / 4.3, DECISIONS 十八 M6)
--
-- factory_external_integrations: 平台专属的外部软件接口授权记录。
--   ⛔ 工厂端没有任何写入口: 写入只走 /api/platform/factories/{factoryId}/integrations/** (有效的平台超级管理员);
--   工厂端只能在 feature-config 里只读地看到 moduleId = "integrations" 的那一行。
--   某工厂某系统「没有行」或 status <> 'ACTIVE' = 没授权: 外部对照列 / 对账 / 外部数据页一律不出现。
--   push_token_sha256: 本机采集推送快照用的专用凭证 (只存 SHA-256, 明文只在签发时返回一次)。
--     凭证绑定在这一行上 = 只能写这一家工厂这一个系统的快照; 行不是 ACTIVE 时推送一律拒绝。
--
-- external_source_snapshots: 外部系统只读快照, 独立于 Cretas 业务表。
--   一行 = 一个工厂 × 一个系统 × 一个数据集 × 一次采集时点; payload 是推送端投影后的 JSON (不含人员字段)。
--   collected_at / received_at 存 UTC (授权表的时间列与其它配置表一样是服务器本地时间)。
--   只追加: 同一采集时点重推同一份内容 = 幂等, 内容不同 = 拒绝 (触发器另外拒绝 UPDATE)。
--   不拒绝 DELETE: 平台删除工厂数据 (FactoryErasureService) 按 factory_id 删本厂全部行。
--
-- system / dataset 的取值与 Java 枚举 ExternalSystem / ExternalDataset 一致
-- (ExternalIntegrationMigrationPostgresContractTest 逐项核对)。
-- ⛔ 迁移只建表, 不灌数据: 授权行由平台在「工厂管理」里建。
-- =====================================================================
CREATE TABLE IF NOT EXISTS factory_external_integrations (
    factory_id            VARCHAR(50)   NOT NULL REFERENCES factories(id),
    external_system       VARCHAR(30)   NOT NULL,
    status                VARCHAR(20)   NOT NULL,
    source_org_id         VARCHAR(64),
    authorized_at         TIMESTAMP     NOT NULL,
    authorized_by         BIGINT        NOT NULL,
    note                  VARCHAR(500),
    push_token_sha256     VARCHAR(64),
    push_token_issued_at  TIMESTAMP,
    updated_at            TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_by            BIGINT        NOT NULL,
    CONSTRAINT pk_factory_external_integrations PRIMARY KEY (factory_id, external_system),
    CONSTRAINT ck_fei_system CHECK (external_system IN ('blacklake')),
    CONSTRAINT ck_fei_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT ck_fei_token_hex CHECK (push_token_sha256 IS NULL OR push_token_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_fei_push_token
    ON factory_external_integrations (push_token_sha256)
    WHERE push_token_sha256 IS NOT NULL;

CREATE TABLE IF NOT EXISTS external_source_snapshots (
    id                BIGSERIAL     PRIMARY KEY,
    factory_id        VARCHAR(50)   NOT NULL REFERENCES factories(id),
    external_system   VARCHAR(30)   NOT NULL,
    dataset           VARCHAR(40)   NOT NULL,
    collected_at      TIMESTAMP     NOT NULL,
    received_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
    row_count         INTEGER       NOT NULL,
    payload           TEXT          NOT NULL,
    payload_sha256    VARCHAR(64)   NOT NULL,
    collector_run_id  VARCHAR(80),
    CONSTRAINT ck_ess_system CHECK (external_system IN ('blacklake')),
    CONSTRAINT ck_ess_dataset CHECK (dataset IN ('report_output')),
    CONSTRAINT ck_ess_row_count CHECK (row_count >= 0),
    CONSTRAINT uq_ess_point UNIQUE (factory_id, external_system, dataset, collected_at)
);

CREATE INDEX IF NOT EXISTS idx_ess_latest
    ON external_source_snapshots (factory_id, external_system, dataset, collected_at DESC);

CREATE OR REPLACE FUNCTION external_source_snapshots_no_update()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'external_source_snapshots is append-only';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_ess_no_update ON external_source_snapshots;
CREATE TRIGGER trg_ess_no_update
    BEFORE UPDATE ON external_source_snapshots
    FOR EACH ROW EXECUTE FUNCTION external_source_snapshots_no_update();
