-- S6-03 / S6-06: 定时观察每次运行的结果持久化 (此前只在 factory_scheduler_configs 留「最后一次」状态, 结果载荷被丢弃)。
--
-- status 只有三态: SUCCESS / FAILED / UNKNOWN。结果无法判定 (超时、网关未知、另一实例在途) 记 UNKNOWN,
-- 绝不记 SUCCESS; 原始运行状态留在 raw_status。UNKNOWN 可由人工裁定为成功或失败 (resolution), 裁定另有审计表 (V113)。
-- data_snapshot 是工具返回载荷的快照 (超过 64KB 不存, snapshot_truncated=true), 引用即本行 run_id。

CREATE TABLE IF NOT EXISTS scheduler_run_results (
    id                 BIGSERIAL PRIMARY KEY,
    run_id             VARCHAR(36)  NOT NULL,
    config_id          BIGINT       NOT NULL,
    factory_id         VARCHAR(50)  NOT NULL,
    task_code          VARCHAR(64)  NOT NULL,
    tool_name          VARCHAR(100),
    config_snapshot    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    planned_fire_key   VARCHAR(100),
    started_at         TIMESTAMP    NOT NULL,
    finished_at        TIMESTAMP    NOT NULL,
    status             VARCHAR(10)  NOT NULL,
    raw_status         VARCHAR(30)  NOT NULL,
    error_msg          TEXT,
    summary            TEXT,
    data_snapshot      JSONB,
    snapshot_truncated BOOLEAN      NOT NULL DEFAULT FALSE,
    resolution         VARCHAR(10),
    resolved_by        BIGINT,
    resolved_at        TIMESTAMP,
    resolution_note    TEXT,
    created_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_scheduler_run_results_run_id UNIQUE (run_id),
    CONSTRAINT ck_scheduler_run_results_status CHECK (status IN ('SUCCESS', 'FAILED', 'UNKNOWN')),
    CONSTRAINT ck_scheduler_run_results_resolution CHECK (resolution IS NULL OR resolution IN ('SUCCESS', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_scheduler_run_results_task
    ON scheduler_run_results (factory_id, task_code, started_at DESC);
