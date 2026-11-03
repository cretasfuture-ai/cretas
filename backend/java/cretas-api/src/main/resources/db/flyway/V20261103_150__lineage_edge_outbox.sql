-- =====================================================================
-- 血缘边补写台账 (transactional outbox) —— 食品 DAG 缺口 02「追溯可靠性」
--
-- 问题: LineageEdgeRecorder 在业务事务提交之后(afterCommit)另开事务写边,
--       写失败只打一条 WARN 就丢 —— 没有重试, 没有积压读数。验厂时「追溯写失败怎么办」
--       答不上来; 而且进程在「业务已提交、边还没写」之间退出, 那条边同样永久丢失。
--
-- 做法: 业务事务里先登记一行 PENDING(与业务同提交/同回滚 ⇒ 回滚不留假边),
--       提交后照旧立刻写边并把这一行结掉(DONE); 写失败留 PENDING,
--       LineageEdgeOutboxReconciler 每分钟按退避重试, 连续失败到上限转 DEAD(仍计入积压)。
--
-- 不继承 BaseEntity / 无 deleted_at: 台账行是运维记录, 不软删; DONE 行留作审计。
-- =====================================================================
CREATE TABLE IF NOT EXISTS lineage_edge_outbox (
    id               VARCHAR(64)   NOT NULL PRIMARY KEY,
    factory_id       VARCHAR(50)   NOT NULL,
    edge_type        VARCHAR(30)   NOT NULL,
    source_type      VARCHAR(30)   NOT NULL,
    source_id        VARCHAR(191)  NOT NULL,
    target_type      VARCHAR(30)   NOT NULL,
    target_id        VARCHAR(191)  NOT NULL,
    quantity_used    NUMERIC(15, 4),
    unit             VARCHAR(20),
    event_time       TIMESTAMP,
    operator_id      BIGINT,
    ref_key          TEXT,
    meta             JSONB,
    status           VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    attempts         INTEGER       NOT NULL DEFAULT 0,
    last_error       VARCHAR(1000),
    resolved_outcome VARCHAR(20),
    next_attempt_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    done_at          TIMESTAMP,
    created_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_leo_status CHECK (status IN ('PENDING', 'DONE', 'DEAD'))
);

-- 补写器取数: 只扫没结掉的行
CREATE INDEX IF NOT EXISTS idx_leo_open_due
    ON lineage_edge_outbox (status, next_attempt_at)
    WHERE status <> 'DONE';

-- 溯源完整性标记: 「这个节点有没有正在补写的边」
CREATE INDEX IF NOT EXISTS idx_leo_open_factory_target
    ON lineage_edge_outbox (factory_id, target_id)
    WHERE status <> 'DONE';

COMMENT ON TABLE lineage_edge_outbox IS
    '血缘边补写台账: 业务事务内登记, 提交后写边并结掉; 失败由 LineageEdgeOutboxReconciler 重试。'
    'PENDING/DEAD 行数 = 追溯积压。';
