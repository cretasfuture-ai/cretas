-- 请购单驳回后允许改后重提 + 多轮审批历史 (C-5, Steve 2026-11-15 裁决)。
--   purchase_requisitions.submit_round: 当前提交轮次 (0 = 从未经本机制提交过, 含存量行)。
--   purchase_requisition_approval_records: 只追加的审批流水, 每轮提交/通过/驳回/重开各一行。
-- 状态列的 CHECK 约束不变: 驳回后重开回到既有的 DRAFT, 没有新状态值。
-- 不回填存量行: 存量待审批/已审批/已驳回单据保持 submit_round=0, 首次再操作时服务端按第 1 轮记入。

ALTER TABLE purchase_requisitions ADD COLUMN IF NOT EXISTS submit_round INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS purchase_requisition_approval_records (
    id                      VARCHAR(191) PRIMARY KEY,
    factory_id              VARCHAR(64)  NOT NULL,
    requisition_id          VARCHAR(191) NOT NULL,
    round_no                INTEGER      NOT NULL,
    seq                     INTEGER      NOT NULL,
    action                  VARCHAR(16)  NOT NULL CHECK (action IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'REOPENED')),
    actor_id                BIGINT,
    acted_at                TIMESTAMP    NOT NULL,
    reason                  TEXT,
    items_snapshot          JSONB,
    expected_date_snapshot  DATE,
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at              TIMESTAMP,
    CONSTRAINT uk_prar_requisition_seq UNIQUE (requisition_id, seq)
);
CREATE INDEX IF NOT EXISTS idx_prar_requisition
    ON purchase_requisition_approval_records (factory_id, requisition_id, seq);
