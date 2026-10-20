-- Durable, single-use, server-bound purchase draft authorisations.
-- This table records an expressly confirmed CNY/kg scope; it does not add
-- currency support to purchase_orders and does not open an execution route.
CREATE TABLE IF NOT EXISTS ai_purchase_task_grants (
    id                    VARCHAR(36)  PRIMARY KEY,
    factory_id            VARCHAR(50)  NOT NULL,
    user_id               BIGINT       NOT NULL,
    tool_name             VARCHAR(100) NOT NULL,
    descriptor_version    VARCHAR(64)  NOT NULL,
    supplier_id           VARCHAR(191) NOT NULL,
    material_type_id      VARCHAR(191) NOT NULL,
    quantity_cap          NUMERIC(15,4) NOT NULL,
    quantity_unit         VARCHAR(20)  NOT NULL,
    unit_price            NUMERIC(15,4) NOT NULL,
    amount_cap            NUMERIC(18,2) NOT NULL,
    currency              VARCHAR(3)   NOT NULL,
    tax_rate              NUMERIC(5,2) NOT NULL,
    input_digest          VARCHAR(128) NOT NULL,
    approved_input_snapshot TEXT       NOT NULL,
    preview_token_id      BIGINT,
    expires_at            TIMESTAMP    NOT NULL,
    revoked_at            TIMESTAMP,
    revoked_by            BIGINT,
    revoke_reason         VARCHAR(500),
    version               BIGINT       NOT NULL DEFAULT 1,
    consumed_at           TIMESTAMP,
    consumed_request_key  VARCHAR(128),
    consumed_input_digest VARCHAR(128),
    result_purchase_order_id VARCHAR(191),
    result_quantity       NUMERIC(15,4),
    result_amount         NUMERIC(18,2),
    created_at            TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_ai_purchase_grant_quantity_positive CHECK (quantity_cap > 0),
    CONSTRAINT ck_ai_purchase_grant_price_positive CHECK (unit_price > 0),
    CONSTRAINT ck_ai_purchase_grant_amount_positive CHECK (amount_cap > 0),
    CONSTRAINT ck_ai_purchase_grant_version_positive CHECK (version > 0),
    CONSTRAINT ck_ai_purchase_grant_scope_fixed CHECK (
        quantity_unit = 'kg' AND currency = 'CNY' AND tax_rate = 0
    ),
    CONSTRAINT ck_ai_purchase_grant_terminal_state CHECK (
        NOT (revoked_at IS NOT NULL AND consumed_at IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_ai_purchase_grant_scope
    ON ai_purchase_task_grants (factory_id, user_id);
CREATE INDEX IF NOT EXISTS idx_ai_purchase_grant_expiry
    ON ai_purchase_task_grants (expires_at);
CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_purchase_grant_preview_token
    ON ai_purchase_task_grants (preview_token_id)
    WHERE preview_token_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_purchase_grant_consumed_request
    ON ai_purchase_task_grants (factory_id, user_id, consumed_request_key)
    WHERE consumed_request_key IS NOT NULL;
