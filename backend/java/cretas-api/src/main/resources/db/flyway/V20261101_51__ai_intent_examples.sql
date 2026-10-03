-- 意图例句表: 第②层候选补足用 (backend/python/ai/matcher/candidates.py)。
--
-- 每个意图若干条车间口语例句 + 向量 (bge-base-zh-v1.5, 768 维, 语料侧不加检索前缀,
-- 由 scripts/phase2b/backfill-intent-examples.py 经 cretas-embedding 服务写入)。
-- 检索按意图取例句最大相似度, 再与描述向量相似度混合。
-- 纯 Python 读写, Java 侧没有实体映射, JPA validate 不涉及。
-- pgvector 扩展已由 V20260505_01 等迁移启用。

CREATE TABLE IF NOT EXISTS ai_intent_examples (
    id            BIGSERIAL PRIMARY KEY,
    intent_code   VARCHAR(100) NOT NULL,
    example_text  VARCHAR(300) NOT NULL,
    embedding     vector(768),
    source        VARCHAR(32)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_ai_intent_examples UNIQUE (intent_code, example_text)
);

CREATE INDEX IF NOT EXISTS idx_ai_intent_examples_intent ON ai_intent_examples (intent_code);
