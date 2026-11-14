-- 迁移 P4「发现的问题」(/issues): 问题的处置记录 (关闭结论 / 转成待办)。
--   一个来源对象 (缺料实例 / 交付风险 / 工艺参数偏离 / 成本损耗异常 / 冷链告警 / 每日核对运行) 在本页最多一行。
--   ⛔ 只记在这里, 不改来源单据的阶段 (来源仍在各自页面按原流程处理), 与原型「关闭只记在记录里, 不改来源数据」一致。
--   关闭必须选结论 (沿用原型清单: 已处理 / 误报 / 接受偏差) 并写一句说明 (去空白后 ≥ 2 字); 服务端校验, 这里再加 CHECK 兜底。
--   转待办 = 指派给操作人本人, 出现在「待办 › 指派给我」。恢复 = status 回 OPEN 并清空结论;
--   从「已关闭」恢复时, 上一次的结论 / 说明 / 关闭人 / 关闭时间留在 previous_* (已关闭的不能直接再关, 要改结论先恢复)。
-- 新表, 不回填。IF NOT EXISTS: 可重复执行。

CREATE TABLE IF NOT EXISTS factory_issue_dispositions (
    id                VARCHAR(36)  PRIMARY KEY,
    factory_id        VARCHAR(50)  NOT NULL,
    source_type       VARCHAR(32)  NOT NULL CHECK (source_type IN (
                          'SHORTAGE_CASE', 'DELIVERY_RISK', 'PROCESS_PARAM_DEVIATION',
                          'COST_DEVIATION', 'COLD_CHAIN_ALERT', 'DAILY_RECONCILE_RUN')),
    source_id         VARCHAR(191) NOT NULL,
    status            VARCHAR(16)  NOT NULL CHECK (status IN ('OPEN', 'CLOSED', 'CONVERTED')),
    conclusion        VARCHAR(32)  CHECK (conclusion IN ('HANDLED', 'FALSE_ALARM', 'ACCEPTED_DEVIATION')),
    reason            VARCHAR(200),
    title             VARCHAR(200) NOT NULL,
    link_path         VARCHAR(255),
    assignee_user_id  BIGINT,
    actor_type        VARCHAR(32)  NOT NULL,
    actor_id          BIGINT       NOT NULL,
    acted_at          TIMESTAMP    NOT NULL,
    previous_conclusion      VARCHAR(32)  CHECK (previous_conclusion IN ('HANDLED', 'FALSE_ALARM', 'ACCEPTED_DEVIATION')),
    previous_reason          VARCHAR(200),
    previous_closed_by_type  VARCHAR(32),
    previous_closed_by_id    BIGINT,
    previous_closed_at       TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_factory_issue_dispositions_source UNIQUE (factory_id, source_type, source_id),
    CONSTRAINT ck_factory_issue_dispositions_closed CHECK (
        status <> 'CLOSED' OR (conclusion IS NOT NULL AND reason IS NOT NULL AND LENGTH(TRIM(reason)) >= 2)),
    CONSTRAINT ck_factory_issue_dispositions_converted CHECK (
        status <> 'CONVERTED' OR assignee_user_id IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_factory_issue_dispositions_assignee
    ON factory_issue_dispositions (factory_id, assignee_user_id, status);

COMMENT ON TABLE factory_issue_dispositions IS
    '「发现的问题」处置记录 (关闭结论 / 转待办); 不改来源单据。';
COMMENT ON COLUMN factory_issue_dispositions.conclusion IS
    '关闭结论: HANDLED 已处理 / FALSE_ALARM 误报 / ACCEPTED_DEVIATION 接受偏差 (原型清单原样).';
