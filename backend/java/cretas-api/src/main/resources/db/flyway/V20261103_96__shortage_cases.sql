-- V20261103_96__shortage_cases.sql
--
-- S4 缺料异常处理链 (L1 切片 1): 一个缺料事件 = 一个处理实例, 同一事件再来只刷新原实例。
--   1. shortage_cases        处理实例: 来源 (目前只有销售订单) + 阶段 + 责任岗位 + 输入指纹。
--   2. shortage_case_lines   按物料拆开的缺口: 需求 / 已放行可用 / 质检中 / 在途 / 剩余缺口, 单位跟物料走。
--   3. shortage_case_events  阶段流转与操作留痕 (谁、什么时候、为什么)。
--
-- 「缺口已解决」只看物料事实 (gap_resolved_at); 订单交付仍以销售单自己的状态为准, 两者分开 (S4-08)。
-- 「下了采购单」不等于解决: 在途量单独记, 只有放行入库才减少缺口。
-- 存量为空: 上线后只从新的财务审核通过 / 入库 / 质检放行事件开始建实例, 不回填历史订单。

CREATE TABLE shortage_cases (
    id                  VARCHAR(36)   PRIMARY KEY,
    factory_id          VARCHAR(50)   NOT NULL,
    source_type         VARCHAR(32)   NOT NULL CHECK (source_type IN ('SALES_ORDER')),
    source_id           VARCHAR(191)  NOT NULL,
    stage               VARCHAR(32)   NOT NULL CHECK (stage IN (
                            'DETECTED', 'PLANNED', 'EXECUTING', 'WAITING_SUPPLY',
                            'PARTIALLY_RESOLVED', 'GAP_RESOLVED', 'CANCELLED', 'MANUAL', 'CLOSED')),
    owner_role          VARCHAR(64)   NOT NULL,
    owner_user_id       BIGINT,
    gap_line_count      INTEGER       NOT NULL DEFAULT 0 CHECK (gap_line_count >= 0),
    input_fingerprint   VARCHAR(64)   NOT NULL,
    last_refreshed_at   TIMESTAMP     NOT NULL,
    last_refresh_reason VARCHAR(64)   NOT NULL,
    gap_resolved_at     TIMESTAMP,
    closed_at           TIMESTAMP,
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMP     NOT NULL,
    updated_at          TIMESTAMP     NOT NULL,
    deleted_at          TIMESTAMP
);

-- 同一来源只能有一个未关闭的实例: 重复的财务审核 / 入库事件只刷新它, 不新建
CREATE UNIQUE INDEX uk_shortage_case_open_source
    ON shortage_cases (factory_id, source_type, source_id)
    WHERE closed_at IS NULL AND deleted_at IS NULL;

CREATE INDEX idx_shortage_case_factory_stage
    ON shortage_cases (factory_id, stage)
    WHERE deleted_at IS NULL;

CREATE TABLE shortage_case_lines (
    id                  BIGSERIAL     PRIMARY KEY,
    case_id             VARCHAR(36)   NOT NULL REFERENCES shortage_cases (id),
    factory_id          VARCHAR(50)   NOT NULL,
    material_type_id    VARCHAR(191)  NOT NULL,
    material_name       VARCHAR(200),
    unit                VARCHAR(32),
    required_qty        NUMERIC(20,6) NOT NULL CHECK (required_qty >= 0),
    released_qty        NUMERIC(20,6) NOT NULL CHECK (released_qty >= 0),
    inspecting_qty      NUMERIC(20,6) NOT NULL CHECK (inspecting_qty >= 0),
    on_order_qty        NUMERIC(20,6) NOT NULL CHECK (on_order_qty >= 0),
    on_order_unit_mismatch_lines INTEGER NOT NULL DEFAULT 0 CHECK (on_order_unit_mismatch_lines >= 0),
    gap_qty             NUMERIC(20,6) NOT NULL CHECK (gap_qty >= 0),
    created_at          TIMESTAMP     NOT NULL,
    updated_at          TIMESTAMP     NOT NULL,
    deleted_at          TIMESTAMP
);

CREATE UNIQUE INDEX uk_shortage_case_line_material
    ON shortage_case_lines (case_id, material_type_id)
    WHERE deleted_at IS NULL;

CREATE TABLE shortage_case_events (
    id                  BIGSERIAL     PRIMARY KEY,
    case_id             VARCHAR(36)   NOT NULL REFERENCES shortage_cases (id),
    factory_id          VARCHAR(50)   NOT NULL,
    event_type          VARCHAR(32)   NOT NULL CHECK (event_type IN ('CREATED', 'REFRESHED', 'STAGE_CHANGED', 'NOTE')),
    from_stage          VARCHAR(32),
    to_stage            VARCHAR(32),
    trigger_reason      VARCHAR(64)   NOT NULL,
    detail              TEXT,
    actor_type          VARCHAR(32)   CHECK (actor_type IN ('FACTORY_USER', 'PLATFORM_ADMIN', 'SYSTEM')),
    actor_id            BIGINT,
    created_at          TIMESTAMP     NOT NULL,
    updated_at          TIMESTAMP     NOT NULL,
    deleted_at          TIMESTAMP
);

CREATE INDEX idx_shortage_case_events_case
    ON shortage_case_events (case_id, created_at);
