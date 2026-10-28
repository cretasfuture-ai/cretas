-- S6-06: 未知结果的人工裁定审计。只追加, 不改不删; 一次运行只能被裁定一次 (UNIQUE run_id)。

CREATE TABLE IF NOT EXISTS scheduler_run_resolutions (
    id               BIGSERIAL PRIMARY KEY,
    run_id           VARCHAR(36)  NOT NULL,
    factory_id       VARCHAR(50)  NOT NULL,
    from_status      VARCHAR(10)  NOT NULL,
    to_status        VARCHAR(10)  NOT NULL,
    note             TEXT         NOT NULL,
    resolved_by      BIGINT,
    resolved_by_role VARCHAR(100),
    resolved_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_scheduler_run_resolutions_run UNIQUE (run_id),
    CONSTRAINT ck_scheduler_run_resolutions_to CHECK (to_status IN ('SUCCESS', 'FAILED')),
    CONSTRAINT ck_scheduler_run_resolutions_from CHECK (from_status = 'UNKNOWN')
);

CREATE INDEX IF NOT EXISTS idx_scheduler_run_resolutions_factory
    ON scheduler_run_resolutions (factory_id, resolved_at DESC);
