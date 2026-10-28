-- 工艺保密 V1 (两档方案: A 工艺保密访问 / B 核心工艺不入云)
--
-- 设计要点:
--   * 按产品 (factory_id + product_type_id) 设保密策略; 没有策略的产品行为完全不变。
--   * 完整工艺的查看 / 导出 / 编辑只认: 保密负责人, 或负责人签发的、未到期未撤销的授权。
--     factory_super_admin / permission_admin / 平台角色都不因头衔自动获得。
--   * CUSTOMER_HELD (B 档): 云端对任何人都不返回、不接收完整工艺; 只登记客户侧工艺包
--     (不可展开的引用 + 执行端 Ed25519 公钥) 和经签名的批次结果。
--   * 审计表只记录谁 / 何时 / 哪个产品 / 什么动作 / 结论, 不存任何工艺正文。

CREATE TABLE IF NOT EXISTS process_secret_policies (
    id               BIGSERIAL    PRIMARY KEY,
    factory_id       VARCHAR(50)  NOT NULL,
    product_type_id  VARCHAR(100)  NOT NULL,
    storage_mode     VARCHAR(30)  NOT NULL,
    owner_user_id    BIGINT       NOT NULL,
    policy_version   BIGINT       NOT NULL,
    created_by       BIGINT       NOT NULL,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    CONSTRAINT ck_process_secret_policy_mode CHECK (storage_mode IN ('CLOUD_RESTRICTED', 'CUSTOMER_HELD')),
    CONSTRAINT ck_process_secret_policy_version_positive CHECK (policy_version > 0),
    CONSTRAINT uq_process_secret_policy_product UNIQUE (factory_id, product_type_id)
);

CREATE TABLE IF NOT EXISTS process_secret_grants (
    id               BIGSERIAL    PRIMARY KEY,
    factory_id       VARCHAR(50)  NOT NULL,
    product_type_id  VARCHAR(100)  NOT NULL,
    grantee_type     VARCHAR(30)  NOT NULL,
    grantee_id       BIGINT       NOT NULL,
    can_view_full    BOOLEAN      NOT NULL,
    can_export       BOOLEAN      NOT NULL,
    can_edit         BOOLEAN      NOT NULL,
    purpose          VARCHAR(200) NOT NULL,
    expires_at       TIMESTAMP    NOT NULL,
    granted_by       BIGINT       NOT NULL,
    revoked_at       TIMESTAMP,
    revoked_by       BIGINT,
    created_at       TIMESTAMP    NOT NULL,
    CONSTRAINT ck_process_secret_grant_grantee CHECK (grantee_type IN ('FACTORY_USER', 'PLATFORM_ADMIN')),
    CONSTRAINT ck_process_secret_grant_has_action CHECK (can_view_full OR can_export OR can_edit)
);

CREATE INDEX IF NOT EXISTS idx_process_secret_grant_lookup
    ON process_secret_grants (factory_id, product_type_id, grantee_type, grantee_id);

CREATE TABLE IF NOT EXISTS process_secret_access_events (
    id               BIGSERIAL    PRIMARY KEY,
    factory_id       VARCHAR(50)  NOT NULL,
    product_type_id  VARCHAR(100),
    actor_type       VARCHAR(30)  NOT NULL,
    actor_id         BIGINT,
    action           VARCHAR(40)  NOT NULL,
    decision         VARCHAR(20)  NOT NULL,
    reason           VARCHAR(200),
    resource_ref     VARCHAR(200),
    created_at       TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_process_secret_event_product
    ON process_secret_access_events (factory_id, product_type_id, created_at);

CREATE TABLE IF NOT EXISTS process_secret_external_packages (
    id                   BIGSERIAL    PRIMARY KEY,
    factory_id           VARCHAR(50)  NOT NULL,
    product_type_id      VARCHAR(100)  NOT NULL,
    package_ref          VARCHAR(64)  NOT NULL,
    package_version      VARCHAR(64)  NOT NULL,
    output_unit          VARCHAR(20)  NOT NULL,
    executor_public_key  VARCHAR(200) NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    created_by           BIGINT       NOT NULL,
    created_at           TIMESTAMP    NOT NULL,
    CONSTRAINT ck_process_secret_package_status CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT uq_process_secret_package_version UNIQUE (factory_id, package_ref, package_version)
);

CREATE TABLE IF NOT EXISTS process_secret_external_results (
    id                    BIGSERIAL     PRIMARY KEY,
    factory_id            VARCHAR(50)   NOT NULL,
    package_id            BIGINT        NOT NULL REFERENCES process_secret_external_packages (id),
    event_id              VARCHAR(128)  NOT NULL,
    production_batch_id   BIGINT        NOT NULL,
    output_quantity       NUMERIC(15,4) NOT NULL,
    output_unit           VARCHAR(20)   NOT NULL,
    signed_at             TIMESTAMP     NOT NULL,
    payload_digest        VARCHAR(64)   NOT NULL,
    review_status         VARCHAR(30)   NOT NULL,
    submitted_by          BIGINT        NOT NULL,
    received_at           TIMESTAMP     NOT NULL,
    CONSTRAINT ck_process_secret_result_quantity_positive CHECK (output_quantity > 0),
    CONSTRAINT ck_process_secret_result_review CHECK (review_status IN ('PENDING_REVIEW', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT uq_process_secret_result_event UNIQUE (factory_id, event_id)
);
