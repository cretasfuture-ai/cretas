-- 迁移 P8「数字班组」(MIGRATION-PLAN 2.7, DECISIONS 第二十一节): 五位数字员工的按厂启用、岗位映射、
-- 调整记录, 以及数字员工办的事 (等我确认 + 额度四态)。
--
--   · 数字员工 = 正式端已有的五个数字专员头像 (clerk 文员 / keeper 仓管 / planner 计划员 /
--     inspector 质检员 / accountant 成本会计), 能力 = 一组权限模块; 能不能办由登录人权限决定。
--     没有行 = 启用 (与原型一致, 设置页可停用)。
--   · 工作事项四态 (R29): DELEGATED 已交办 (等有权限的人确认, 不扣) / DONE 已办成 (确认执行后回读
--     预览凭据为 CONFIRMED 才记, 扣 1) / NOT_DONE 没办成 (有人不办 / 取消, 不扣) /
--     OUTCOME_UNKNOWN 结果不明 (执行抛异常或确认链路报结果不明, 不扣)。CONFIRMING = 确认请求在途 (不计)。
--     额度按 resolved_date (北京时间真实日期) 计数。
--   · 预览凭据只在确认人自己的浏览器里; 本表只存凭据的 command_digest 与预览人, 确认时三者 (人 / 摘要 / 事项) 对上才放行。
-- 新表, 不回填。IF NOT EXISTS: 可重复执行。

CREATE TABLE IF NOT EXISTS digital_crew_member_settings (
    id                  BIGSERIAL    PRIMARY KEY,
    factory_id          VARCHAR(50)  NOT NULL,
    member_key          VARCHAR(20)  NOT NULL CHECK (member_key IN ('clerk', 'keeper', 'planner', 'inspector', 'accountant')),
    enabled             BOOLEAN      NOT NULL,
    updated_by_user_id  BIGINT,
    updated_by_name     VARCHAR(100),
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_digital_crew_member_settings UNIQUE (factory_id, member_key)
);

CREATE TABLE IF NOT EXISTS digital_crew_role_mappings (
    id                  BIGSERIAL    PRIMARY KEY,
    factory_id          VARCHAR(50)  NOT NULL,
    frontline_role      VARCHAR(50)  NOT NULL,
    member_key          VARCHAR(20)  CHECK (member_key IS NULL OR member_key IN ('clerk', 'keeper', 'planner', 'inspector', 'accountant')),
    updated_by_user_id  BIGINT,
    updated_by_name     VARCHAR(100),
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_digital_crew_role_mappings UNIQUE (factory_id, frontline_role)
);

CREATE TABLE IF NOT EXISTS digital_crew_setting_logs (
    id                  BIGSERIAL    PRIMARY KEY,
    factory_id          VARCHAR(50)  NOT NULL,
    change_kind         VARCHAR(20)  NOT NULL CHECK (change_kind IN ('MEMBERS', 'ROLE_MAPPING')),
    actor_user_id       BIGINT,
    actor_name          VARCHAR(100) NOT NULL,
    reason              VARCHAR(200) NOT NULL CHECK (LENGTH(TRIM(reason)) >= 2),
    before_json         TEXT         NOT NULL,
    after_json          TEXT         NOT NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_digital_crew_setting_logs_factory
    ON digital_crew_setting_logs (factory_id, created_at DESC);

CREATE TABLE IF NOT EXISTS digital_crew_work_items (
    id                    BIGSERIAL    PRIMARY KEY,
    factory_id            VARCHAR(50)  NOT NULL,
    member_key            VARCHAR(20)  NOT NULL CHECK (member_key IN ('clerk', 'keeper', 'planner', 'inspector', 'accountant')),
    intent_code           VARCHAR(100) NOT NULL,
    intent_name           VARCHAR(200),
    title                 VARCHAR(200) NOT NULL,
    user_input            VARCHAR(500) NOT NULL,
    required_permission   VARCHAR(100) NOT NULL,
    module                VARCHAR(50)  NOT NULL,
    status                VARCHAR(20)  NOT NULL CHECK (status IN ('DELEGATED', 'CONFIRMING', 'DONE', 'NOT_DONE', 'OUTCOME_UNKNOWN')),
    quota_cost            INTEGER      NOT NULL DEFAULT 0 CHECK (quota_cost IN (0, 1)),
    preview_summary       TEXT,
    created_by_user_id    BIGINT       NOT NULL,
    created_by_name       VARCHAR(100) NOT NULL,
    created_at            TIMESTAMP    NOT NULL DEFAULT NOW(),
    previewed_by_user_id  BIGINT,
    previewed_at          TIMESTAMP,
    command_digest        VARCHAR(64),
    preview_token_id      BIGINT,
    confirmed_by_user_id  BIGINT,
    confirmed_by_name     VARCHAR(100),
    confirmed_at          TIMESTAMP,
    resolved_at           TIMESTAMP,
    resolved_date         DATE,
    result_status         VARCHAR(40),
    result_note           VARCHAR(500),
    evidence              VARCHAR(500),
    updated_at            TIMESTAMP    NOT NULL DEFAULT NOW(),
    version               BIGINT       NOT NULL DEFAULT 0,
    -- 只有已办成扣额度, 且已办成必有确认人与真实日期
    CONSTRAINT ck_digital_crew_work_items_quota CHECK (
        (status = 'DONE' AND quota_cost = 1 AND confirmed_by_user_id IS NOT NULL AND resolved_date IS NOT NULL)
        OR (status <> 'DONE' AND quota_cost = 0)),
    CONSTRAINT ck_digital_crew_work_items_resolved CHECK (
        status NOT IN ('NOT_DONE', 'OUTCOME_UNKNOWN') OR resolved_date IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_digital_crew_work_items_status
    ON digital_crew_work_items (factory_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_digital_crew_work_items_resolved
    ON digital_crew_work_items (factory_id, resolved_date);
-- 同一人对同一员工的同一句话, 只能有一件还在等确认 (双击 / 重放不会建出两件)
CREATE UNIQUE INDEX IF NOT EXISTS uk_digital_crew_work_items_open_dup
    ON digital_crew_work_items (factory_id, member_key, created_by_user_id, user_input)
    WHERE status = 'DELEGATED';

-- 每人每厂一行的交办锁: 交办前 SELECT ... FOR UPDATE 锁住, 再数件数、再插入 => 每人「已交办」上限不会被并发冲破
CREATE TABLE IF NOT EXISTS digital_crew_delegation_locks (
    lock_key    VARCHAR(80)  PRIMARY KEY,
    factory_id  VARCHAR(50)  NOT NULL,
    user_id     BIGINT       NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_digital_crew_delegation_locks UNIQUE (factory_id, user_id)
);

COMMENT ON TABLE digital_crew_work_items IS
    '数字员工办的事: 等我确认 + 额度四态 (已交办/已办成/没办成/结果不明); 只有回读核实的已办成扣 1。';
COMMENT ON COLUMN digital_crew_work_items.command_digest IS
    '确认人预览时铸的凭据摘要 (不存凭据本身); 确认请求的摘要必须与此相等, 且确认人 = previewed_by_user_id。';
COMMENT ON COLUMN digital_crew_work_items.preview_token_id IS
    '预览凭据行 id (不是凭据本身); 对账时按 id + 工厂 + 预览人回读凭据状态 (被通用确认入口用掉 / 确认在途卡住)。';
