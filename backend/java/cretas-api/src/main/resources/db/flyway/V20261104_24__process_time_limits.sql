-- 食品 DAG 缺口 14: 工序间与投料时限 (Steve 2026-11-04: 起算点 / 暂停 / 拆批混批都做成可配置)。
--
--   1. workflow_task_ports.time_limit_config   冻结自画布 INPUT 端口的 timeLimit; NULL = 不限时 (存量全部 NULL)。
--   2. process_time_marks                      人工登记的事实: 解冻开始 (START) / 冷藏暂停 (PAUSE)。时刻只取服务端当前时间。
--   3. process_time_limit_events               超时被拦的留痕 + 处置结论 (复检放行 / 判不合格)。
--
-- 全部新增, 不回填、不改存量行。计时账不落表: 从 semi_finished_inventory_transactions 回放。

ALTER TABLE workflow_task_ports ADD COLUMN IF NOT EXISTS time_limit_config JSONB;
COMMENT ON COLUMN workflow_task_ports.time_limit_config IS '工序间/投料时限配置, 仅 INPUT 端口; NULL=不限时 (缺口 14)';

CREATE TABLE IF NOT EXISTS process_time_marks (
    id              BIGSERIAL PRIMARY KEY,
    factory_id      VARCHAR(64)  NOT NULL,
    target_type     VARCHAR(32)  NOT NULL
        CHECK (target_type IN ('SEMI_FINISHED', 'WIP_MATERIAL_BATCH', 'RAW_MATERIAL_BATCH', 'FINISHED_GOODS')),
    target_id       VARCHAR(191) NOT NULL,
    mark_kind       VARCHAR(16)  NOT NULL CHECK (mark_kind IN ('START', 'PAUSE')),
    mark_code       VARCHAR(32)  NOT NULL,
    started_at      TIMESTAMP    NOT NULL,
    ended_at        TIMESTAMP,
    started_by      BIGINT       NOT NULL,
    started_by_name VARCHAR(100),
    ended_by        BIGINT,
    note            VARCHAR(500),
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ptm_factory_target
    ON process_time_marks (factory_id, target_type, target_id);
-- 同一对象同一种暂停只能有一条进行中
CREATE UNIQUE INDEX IF NOT EXISTS uq_ptm_open_pause
    ON process_time_marks (factory_id, target_type, target_id, mark_code)
    WHERE mark_kind = 'PAUSE' AND ended_at IS NULL AND deleted_at IS NULL;

COMMENT ON TABLE process_time_marks IS '时限计时的人工登记事实 (缺口 14): 解冻开始 / 冷藏暂停';

CREATE TABLE IF NOT EXISTS process_time_limit_events (
    id                    BIGSERIAL PRIMARY KEY,
    factory_id            VARCHAR(64)   NOT NULL,
    target_type           VARCHAR(32)   NOT NULL
        CHECK (target_type IN ('SEMI_FINISHED', 'WIP_MATERIAL_BATCH', 'RAW_MATERIAL_BATCH', 'FINISHED_GOODS')),
    target_id             VARCHAR(191)  NOT NULL,
    batch_number          VARCHAR(100),
    product_type_id       VARCHAR(191),
    production_batch_id   BIGINT,
    task_id               BIGINT,
    workflow_port_id      VARCHAR(128),
    process_name          VARCHAR(255),
    limit_minutes         INTEGER       NOT NULL,
    start_event           VARCHAR(32)   NOT NULL,
    mix_rule              VARCHAR(16)   NOT NULL,
    start_at              TIMESTAMP     NOT NULL,
    deadline_at           TIMESTAMP     NOT NULL,
    blocked_at            TIMESTAMP     NOT NULL,
    blocked_by            BIGINT,
    expired_quantity      DECIMAL(15,4),
    unit                  VARCHAR(20),
    covered_until         TIMESTAMP     NOT NULL,
    recheck_valid_minutes INTEGER,
    status                VARCHAR(16)   NOT NULL
        CHECK (status IN ('OPEN', 'RELEASED', 'REJECTED')),
    conclusion            TEXT,
    decided_by            BIGINT,
    decided_by_name       VARCHAR(100),
    decided_at            TIMESTAMP,
    valid_until           TIMESTAMP,
    created_at            TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP     NOT NULL DEFAULT NOW(),
    deleted_at            TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ptle_factory_target
    ON process_time_limit_events (factory_id, target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_ptle_factory_status
    ON process_time_limit_events (factory_id, status);
-- 同一对象同时只有一条待处置 (并发被拦时由它去重)
CREATE UNIQUE INDEX IF NOT EXISTS uq_ptle_open
    ON process_time_limit_events (factory_id, target_type, target_id)
    WHERE status = 'OPEN' AND deleted_at IS NULL;

COMMENT ON TABLE process_time_limit_events IS '超时被拦留痕与处置 (缺口 14): 每次待处置一行, 处置后保留';
COMMENT ON COLUMN process_time_limit_events.covered_until IS '处置覆盖到的最晚一笔的起算时刻; 之后新入的料不沾光';
