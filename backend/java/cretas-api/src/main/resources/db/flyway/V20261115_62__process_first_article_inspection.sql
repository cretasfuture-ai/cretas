-- 工序首检 (原型统一 E 线 BL17 承接, 设计 docs/specs/process-first-article-inspection-design.md)。
--   first_article_policies     : 按工厂开关 (OFF / WARN / BLOCK); 没有行 = OFF, 现有工厂行为不变。
--   first_article_standards    : 哪道工序 (work_processes.id) 要首检、检什么项 (items_json); 每道工序最多一条启用标准。
--   first_article_inspections  : 挂在工序任务 (work_process_tasks.id) 上的首检记录; 送检 → 判定 (合格/不合格) → 可作废重检。
-- 新表, 不回填。IF NOT EXISTS: 可重复执行。

CREATE TABLE IF NOT EXISTS first_article_policies (
    factory_id   VARCHAR(50)  PRIMARY KEY,
    mode         VARCHAR(16)  NOT NULL DEFAULT 'OFF' CHECK (mode IN ('OFF', 'WARN', 'BLOCK')),
    updated_by   BIGINT,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS first_article_standards (
    id               BIGSERIAL    PRIMARY KEY,
    factory_id       VARCHAR(50)  NOT NULL,
    work_process_id  VARCHAR(50)  NOT NULL,
    name             VARCHAR(100) NOT NULL,
    items_json       TEXT         NOT NULL,
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    revision         INTEGER      NOT NULL DEFAULT 1 CHECK (revision >= 1),
    deactivated_reason VARCHAR(500),
    created_by       BIGINT,
    updated_by       BIGINT,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at       TIMESTAMP
);
-- 一道工序同时只有一条启用标准 (改标准 = 同一行 revision + 1)。
CREATE UNIQUE INDEX IF NOT EXISTS uk_fa_standards_active_process
    ON first_article_standards (factory_id, work_process_id)
    WHERE is_active AND deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_fa_standards_factory ON first_article_standards (factory_id, is_active);

CREATE TABLE IF NOT EXISTS first_article_inspections (
    id                    BIGSERIAL     PRIMARY KEY,
    factory_id            VARCHAR(50)   NOT NULL,
    work_process_task_id  BIGINT        NOT NULL,
    production_batch_id   BIGINT        NOT NULL,
    work_process_id       VARCHAR(50)   NOT NULL,
    standard_id           BIGINT        NOT NULL,
    standard_revision     INTEGER       NOT NULL,
    standard_name         VARCHAR(100)  NOT NULL,
    standard_items_json   TEXT          NOT NULL,
    status                VARCHAR(16)   NOT NULL CHECK (status IN ('PENDING', 'PASSED', 'FAILED', 'VOID')),
    sample_quantity       NUMERIC(15,4) CHECK (sample_quantity IS NULL OR sample_quantity > 0),
    sample_unit           VARCHAR(20),
    request_notes         VARCHAR(500),
    requested_by          BIGINT,
    requested_at          TIMESTAMP     NOT NULL,
    results_json          TEXT,
    auto_verdict          VARCHAR(16)   CHECK (auto_verdict IS NULL OR auto_verdict IN ('PASSED', 'FAILED')),
    judged_by             BIGINT,
    judged_at             TIMESTAMP,
    judge_notes           VARCHAR(500),
    -- 送检人本人判定 (工厂「允许本人审批」开关开时才可能为 TRUE, 必须带原因; 开关关时服务端 403)
    self_judged           BOOLEAN       NOT NULL DEFAULT FALSE,
    self_judge_reason     VARCHAR(500),
    void_reason           VARCHAR(500),
    voided_by             BIGINT,
    voided_at             TIMESTAMP,
    created_at            TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP     NOT NULL DEFAULT NOW(),
    deleted_at            TIMESTAMP,
    -- 判定过的记录必须有判定人/时间; 作废必须有原因。
    CONSTRAINT ck_fa_inspections_judged CHECK (
        status NOT IN ('PASSED', 'FAILED') OR (judged_at IS NOT NULL AND auto_verdict IS NOT NULL)),
    CONSTRAINT ck_fa_inspections_self_judge CHECK (NOT self_judged OR self_judge_reason IS NOT NULL),
    CONSTRAINT ck_fa_inspections_void CHECK (
        status <> 'VOID' OR (void_reason IS NOT NULL AND voided_at IS NOT NULL))
);
-- 一个工序任务同时只有一张待判定的首检单 (并发送检由数据库兜底)。
CREATE UNIQUE INDEX IF NOT EXISTS uk_fa_inspections_pending_task
    ON first_article_inspections (factory_id, work_process_task_id)
    WHERE status = 'PENDING' AND deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_fa_inspections_task
    ON first_article_inspections (factory_id, work_process_task_id, requested_at DESC);
CREATE INDEX IF NOT EXISTS idx_fa_inspections_status
    ON first_article_inspections (factory_id, status, requested_at DESC);
