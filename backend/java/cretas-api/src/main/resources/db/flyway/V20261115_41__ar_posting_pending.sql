-- 发货成功但应收自动挂账真正失败 → 待补账记录 (D-S3, Steve 2026-11-15 裁决: 发货不回滚, 写待补账并告警)。
-- 新表, 不回填。IF NOT EXISTS: 可重复执行。补账动作与 RESOLVED 置位本期未做, 仅预留状态/字段。
CREATE TABLE IF NOT EXISTS ar_posting_pending (
    id               VARCHAR(191)  PRIMARY KEY,
    factory_id       VARCHAR(50)   NOT NULL,
    delivery_id      VARCHAR(191)  NOT NULL,
    delivery_number  VARCHAR(100),
    sales_order_id   VARCHAR(191),
    customer_id      VARCHAR(191),
    amount           NUMERIC(18,2),
    failure_reason   VARCHAR(1000) NOT NULL,
    status           VARCHAR(16)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RESOLVED')),
    operated_by      BIGINT,
    resolved_at      TIMESTAMP,
    resolved_by      BIGINT,
    created_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
    deleted_at       TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_arpp_factory_status ON ar_posting_pending (factory_id, status, created_at);
CREATE INDEX IF NOT EXISTS idx_arpp_delivery ON ar_posting_pending (factory_id, delivery_id);
-- 同一发运单同时只留一条未处理的待补账 (重复失败不堆叠)。
CREATE UNIQUE INDEX IF NOT EXISTS uk_arpp_open_per_delivery
    ON ar_posting_pending (factory_id, delivery_id) WHERE status = 'PENDING' AND deleted_at IS NULL;
