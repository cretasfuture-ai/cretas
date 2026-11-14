-- 意图飞轮: 线上 AI 对话每一轮一条 (INTENT-DISTILL-20261111, Steve 2026-11-14)
--
-- 用途: 以后蒸馏决策模型 (线上 Luna) 与按使用习惯做 RL 的原始记录 (输入 -> 决策 -> 用户反应)。
-- 为什么不复用 intent_match_records: 那张表的读者很多 (错误归因、影子路由、反馈服务…), user_input 是 NOT NULL
-- 明文; 保密厂的原话不能以明文落在任何一个读者看得到的列里, 给它加状态列就得每个读者都记得过滤 (同
-- training_sample_secrecy_holds 的理由)。这张表除了飞轮写入、导出 / 缺口汇总脚本没有别的读者。
--
-- 保密 (Steve 2026-11-14: 不按开关整厂排除):
--   secrecy_mode = PLAIN        本厂保密 (a/c/d/e) 全关: user_input / context_summary 存原文;
--   secrecy_mode = REDACTED     本厂任一项开着 (或读不到配置, 按开着): 原文列必须为空 (ck_aift_no_raw_when_secret),
--                               原文只能以密文存 (*_cipher, AES-GCM, 密钥 CRETAS_FLYWHEEL_RAW_KEY; 没配密钥就不留);
--   secrecy_mode = TEXT_DROPPED 本厂开着保密但这次脱敏做不了 (名单查不出): 原文、导出文本都不留, 只留决策元数据。
--   *_export 是训练导出唯一读的列: 保密厂 = 出外脱敏 (c) 同一套代号 + 数值抹掉; 保密关的厂 = 原文只把本厂人名 /
--   客户名换代号 (隐私, Steve 2026-11-04 起任何开关都不管)。
-- confirm_digest: 这一轮出写确认时确认卡的 commandDigest (摘要, 不含参数原文); 用户点确认 (confirm 入口) 时按它回填 CONFIRMED。
-- reaction 为空 = 还没判 (导出时按时间窗再推导 ABANDONED / NO_FOLLOWUP / PENDING), ⛔ 不把不知道写成别的值。

CREATE TABLE IF NOT EXISTS ai_intent_flywheel_turns (
    id                    BIGSERIAL     PRIMARY KEY,
    turn_id               VARCHAR(36)   NOT NULL,
    factory_id            VARCHAR(50)   NOT NULL,
    user_id               BIGINT,
    session_id            VARCHAR(100),
    entry                 VARCHAR(10)   NOT NULL,
    input_kind            VARCHAR(10)   NOT NULL,
    catalog_version       VARCHAR(80)   NOT NULL,
    option_set_hash       VARCHAR(80),
    secrecy_mode          VARCHAR(20)   NOT NULL,
    user_input            TEXT,
    user_input_export     TEXT,
    user_input_cipher     TEXT,
    context_summary       TEXT,
    context_export        TEXT,
    requested_intent_code VARCHAR(100),
    decision_source       VARCHAR(40)   NOT NULL,
    decision_path         TEXT,
    decision_action       VARCHAR(20)   NOT NULL,
    decided_intent_code   VARCHAR(100),
    confidence            NUMERIC(6,4),
    candidates_json       TEXT,
    final_status          VARCHAR(40)   NOT NULL,
    response_intent_code  VARCHAR(100),
    confirm_digest        VARCHAR(128),
    latency_ms            INTEGER,
    reaction              VARCHAR(30),
    reaction_option_rank  INTEGER,
    reaction_turn_id      VARCHAR(36),
    reacted_at            TIMESTAMP,
    created_at            TIMESTAMP     NOT NULL DEFAULT now(),
    CONSTRAINT uk_aift_turn_id UNIQUE (turn_id),
    CONSTRAINT ck_aift_factory CHECK (btrim(factory_id) <> ''),
    CONSTRAINT ck_aift_entry CHECK (entry IN ('NORMAL', 'STREAM')),
    CONSTRAINT ck_aift_input_kind CHECK (input_kind IN ('TEXT', 'BUTTON')),
    CONSTRAINT ck_aift_secrecy_mode CHECK (secrecy_mode IN ('PLAIN', 'REDACTED', 'TEXT_DROPPED')),
    CONSTRAINT ck_aift_no_raw_when_secret CHECK (secrecy_mode = 'PLAIN' OR (user_input IS NULL AND context_summary IS NULL)),
    CONSTRAINT ck_aift_dropped_no_export CHECK (secrecy_mode <> 'TEXT_DROPPED'
        OR (user_input_export IS NULL AND context_export IS NULL)),
    CONSTRAINT ck_aift_action CHECK (decision_action IN ('DIRECT', 'SHOW', 'ASK', 'NONE', 'UNKNOWN')),
    CONSTRAINT ck_aift_reaction CHECK (reaction IS NULL OR reaction IN (
        'OPTION_SELECTED', 'OPTION_OUTSIDE_CANDIDATES', 'ANSWERED_CLARIFICATION', 'CHANGED_REQUEST',
        'CONFIRMED', 'CONFIRM_SKIPPED', 'SAME_INTENT_FOLLOWUP', 'MOVED_ON', 'UNKNOWN')),
    CONSTRAINT ck_aift_rank CHECK (reaction_option_rank IS NULL OR reaction_option_rank >= 1),
    CONSTRAINT ck_aift_reacted CHECK ((reaction IS NULL) = (reacted_at IS NULL)),
    CONSTRAINT ck_aift_latency CHECK (latency_ms IS NULL OR latency_ms >= 0)
);

CREATE INDEX IF NOT EXISTS idx_aift_factory_created ON ai_intent_flywheel_turns (factory_id, created_at);
CREATE INDEX IF NOT EXISTS idx_aift_confirm_digest ON ai_intent_flywheel_turns (factory_id, confirm_digest);
CREATE INDEX IF NOT EXISTS idx_aift_session ON ai_intent_flywheel_turns (factory_id, user_id, session_id, created_at);
