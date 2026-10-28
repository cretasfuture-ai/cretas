-- V20261103_124__shortage_case_actions.sql
--
-- S4 缺料异常处理链 (L1 切片 2): 处理实例上的执行台账。
--   每一次「替缺口做的事」记一行: 生成采购草稿 (结果 = 采购单 id)、人工决定 (取消 / 转人工 / 关闭)。
--   同一实例同一物料只能有一张「还在走」的采购草稿 (部分唯一索引), 重复点「生成采购草稿」拿到的是同一张,
--   不会再建一张 —— 这就是去重, ⛔ 不给 purchase_orders 加列 (采购表另有所有者)。
-- 存量为空。

CREATE TABLE shortage_case_actions (
    id                  BIGSERIAL     PRIMARY KEY,
    case_id             VARCHAR(36)   NOT NULL REFERENCES shortage_cases (id),
    factory_id          VARCHAR(50)   NOT NULL,
    action_type         VARCHAR(32)   NOT NULL CHECK (action_type IN ('PURCHASE_DRAFT', 'DECISION')),
    material_type_id    VARCHAR(191),
    status              VARCHAR(16)   NOT NULL CHECK (status IN ('DONE', 'SUPERSEDED')),
    result_ref          VARCHAR(191),
    quantity            NUMERIC(20,6) CHECK (quantity IS NULL OR quantity > 0),
    unit                VARCHAR(32),
    decision            VARCHAR(16)   CHECK (decision IS NULL OR decision IN ('CANCEL', 'MANUAL', 'CLOSE')),
    reason              TEXT,
    actor_type          VARCHAR(32)   NOT NULL CHECK (actor_type IN ('FACTORY_USER', 'PLATFORM_ADMIN', 'SYSTEM')),
    actor_id            BIGINT,
    created_at          TIMESTAMP     NOT NULL,
    updated_at          TIMESTAMP     NOT NULL,
    deleted_at          TIMESTAMP,
    CHECK (action_type <> 'PURCHASE_DRAFT' OR (material_type_id IS NOT NULL AND result_ref IS NOT NULL)),
    CHECK (action_type <> 'DECISION' OR (decision IS NOT NULL AND reason IS NOT NULL AND length(trim(reason)) > 0))
);

-- 一个实例 + 一种物料只能有一张在走的采购草稿; 那张草稿被取消 / 驳回后把这一行置 SUPERSEDED 才能再生成
CREATE UNIQUE INDEX uk_shortage_case_action_open_draft
    ON shortage_case_actions (case_id, material_type_id)
    WHERE action_type = 'PURCHASE_DRAFT' AND status = 'DONE' AND deleted_at IS NULL;

CREATE INDEX idx_shortage_case_actions_case
    ON shortage_case_actions (case_id, created_at);
