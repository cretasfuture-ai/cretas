-- 澄清按钮点选日志: 用户替系统在候选里选了意图 = 一条带正确答案的学习样本。
--
-- 写方: Python /api/ai/intent/selection (Java 流式路径在澄清点选执行时异步回写)。
-- 用途: 同一归一化说法被多人多次选成同一个意图 → 提议进精确表 (ai_intent_phrase_registry, PENDING 待审)。
-- 纯 Python 读写, Java 侧没有实体映射, JPA validate 不涉及。只追加, 不改不删。

CREATE TABLE IF NOT EXISTS ai_intent_selection_log (
    id               BIGSERIAL PRIMARY KEY,
    factory_id       VARCHAR(64)  NOT NULL,
    user_id          VARCHAR(64),
    role             VARCHAR(64),
    query_text       VARCHAR(500) NOT NULL,
    normalized_text  VARCHAR(500),
    chosen_intent    VARCHAR(100) NOT NULL,
    candidates       JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ai_intent_selection_log_key
    ON ai_intent_selection_log (factory_id, normalized_text);
