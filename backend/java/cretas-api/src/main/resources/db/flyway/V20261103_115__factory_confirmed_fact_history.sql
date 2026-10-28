-- S2-01/S2-02: 对话建厂的「已确认事实」与逐条冲突选择 + 版本历史。
--
-- 每行是某一家工厂的某一个访谈事项在某个时刻的一个取值及其来源:
--   CONFIRMED         当前生效的已确认事实 (每工厂每事项最多一条, 部分唯一索引钉住)
--   CONFLICT_PENDING  新答案与已确认事实不同, 等管理员逐条选择来源; 选定前不改写已确认事实
--   SUPERSEDED        曾经生效, 已被后来选定的新事实取代 (保留作历史)
--   REJECTED          新答案被管理员否决 (保留作历史)
--
-- ⛔ 不覆盖、不删除: 旧事实只改状态。工厂隔离靠 factory_id, 所有查询都带它。

CREATE TABLE IF NOT EXISTS factory_confirmed_fact (
    id             VARCHAR(64)  PRIMARY KEY,
    factory_id     VARCHAR(191) NOT NULL,
    field_key      VARCHAR(128) NOT NULL,
    field_label    VARCHAR(255) NULL,
    value_text     TEXT         NOT NULL,
    status         VARCHAR(24)  NOT NULL,
    version_no     INTEGER      NOT NULL,
    source_kind    VARCHAR(32)  NOT NULL,
    source_ref     VARCHAR(255) NULL,
    supersedes_id  VARCHAR(64)  NULL,
    decided_by     VARCHAR(128) NULL,
    decided_at     TIMESTAMP    NULL,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_factory_confirmed_fact_status
        CHECK (status IN ('CONFIRMED', 'CONFLICT_PENDING', 'SUPERSEDED', 'REJECTED'))
);

CREATE INDEX IF NOT EXISTS idx_fcf_factory_field ON factory_confirmed_fact (factory_id, field_key, version_no);

CREATE UNIQUE INDEX IF NOT EXISTS uq_fcf_one_confirmed_per_field
    ON factory_confirmed_fact (factory_id, field_key) WHERE status = 'CONFIRMED';

CREATE UNIQUE INDEX IF NOT EXISTS uq_fcf_one_pending_per_field
    ON factory_confirmed_fact (factory_id, field_key) WHERE status = 'CONFLICT_PENDING';
