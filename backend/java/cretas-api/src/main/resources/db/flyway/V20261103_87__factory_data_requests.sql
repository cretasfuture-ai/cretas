-- 整厂数据导出 / 删除 (手册 docs/security/factory-data-export-and-erasure-runbook.md)
--
-- 1) factory_data_requests: 回执台账。每次预演 / 导出 / 删除申请 / 撤销 / 删除一行:
--    谁、何时、对哪个工厂、每张表多少行、导出包的 SHA256、冷静期到哪天。
-- 2) factory_erasure_audit_retention: 删除工厂时审计记录的留存区 (口径 Q3: 6 个月)。
--    只留谁 / 何时 / 做了什么 / 对象编号, 不留内容字段; retain_until 过了由
--    FactoryErasureRetentionJob 每天清除。
--
-- ⛔ 两张表都故意不加指向 factories 的外键 —— 工厂删掉之后它们还要在, 证明「删过了、删了多少」。
-- 只存编号、计数、哈希和时间, 不存业务内容。删除功能在目录里把它们登记为 RECEIPT (不导出、不删)。
-- created_at 不设默认值: 写入方 (FactoryDataRequestLedger / FactoryAuditRetention) 总是显式给值。

CREATE TABLE IF NOT EXISTS factory_data_requests (
    id                  BIGSERIAL PRIMARY KEY,
    request_type        VARCHAR(24)  NOT NULL,
    factory_id          VARCHAR(64)  NOT NULL,
    factory_name        VARCHAR(255),
    actor_type          VARCHAR(32)  NOT NULL,
    actor_id            BIGINT       NOT NULL,
    actor_role          VARCHAR(64),
    status              VARCHAR(16)  NOT NULL,
    related_request_id  BIGINT,
    not_before          TIMESTAMP,
    summary             JSONB,
    package_sha256      VARCHAR(64),
    package_bytes       BIGINT,
    error_message       TEXT,
    created_at          TIMESTAMP    NOT NULL,
    completed_at        TIMESTAMP,
    -- 谁触发的: 接口调用 MANUAL; 定时任务写自己的名字 (如 BACKUP_SCHEDULER), 审计里能区分
    trigger_source      VARCHAR(32)  NOT NULL DEFAULT 'MANUAL',
    CONSTRAINT ck_factory_data_requests_type
        CHECK (request_type IN ('PREVIEW', 'EXPORT', 'ERASURE_REQUEST', 'ERASURE_CANCEL', 'ERASURE')),
    CONSTRAINT ck_factory_data_requests_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_factory_data_requests_factory
    ON factory_data_requests (factory_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_factory_data_requests_related
    ON factory_data_requests (related_request_id);

COMMENT ON TABLE factory_data_requests IS '整厂数据导出/删除回执; 无外键, 工厂删除后保留';

CREATE TABLE IF NOT EXISTS factory_erasure_audit_retention (
    id                  BIGSERIAL PRIMARY KEY,
    erasure_request_id  BIGINT       NOT NULL,
    factory_id          VARCHAR(64)  NOT NULL,
    source_database     VARCHAR(16)  NOT NULL,
    source_table        VARCHAR(64)  NOT NULL,
    source_row_key      VARCHAR(128) NOT NULL,
    actor               VARCHAR(255),
    occurred_at         TIMESTAMP,
    action              VARCHAR(255),
    object_ref          VARCHAR(255),
    retain_until        TIMESTAMP    NOT NULL,
    created_at          TIMESTAMP    NOT NULL,
    CONSTRAINT uq_factory_erasure_audit_retention
        UNIQUE (erasure_request_id, source_database, source_table, source_row_key)
);

CREATE INDEX IF NOT EXISTS idx_factory_erasure_audit_retention_until
    ON factory_erasure_audit_retention (retain_until);

COMMENT ON TABLE factory_erasure_audit_retention IS '已删除工厂的审计留存 (谁/何时/做了什么/对象编号), 6 个月后清除';
