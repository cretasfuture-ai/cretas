-- 三层意图识别 (2026-10) 的第①层: 已审核的整句精确匹配表 + 实体别名词表。
--
-- 读方是 Python 识别服务 (backend/python/ai/registry.py), 只读 status='APPROVED' 的条目;
-- 归一化规则 (N2: 实体别名 -> <物料> 等标签, 数量 -> <量>, 单号 -> <号>, 日期 -> <日期>)
-- 写在那个模块里。Java 侧没有实体映射这两张表 (纯 Python 读), 所以 JPA validate 不涉及。
--
-- 唯一性由库保证 (不是靠调用方 check-then-insert): 同一工厂同一业态下, 一个归一化键只能
-- 指向一个意图; 同一工厂同一类实体下, 一个别名只登记一次。

CREATE TABLE IF NOT EXISTS ai_intent_phrase_registry (
    id                  BIGSERIAL PRIMARY KEY,
    factory_id          VARCHAR(64)  NOT NULL,
    business_type       VARCHAR(32)  NOT NULL DEFAULT 'FACTORY',
    normalized_text     VARCHAR(500) NOT NULL,
    intent_code         VARCHAR(100) NOT NULL,
    -- PENDING: 老师模型提出、待审; APPROVED: 生效; REJECTED: 审核否决 (保留以免被重复提出)
    status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    source              VARCHAR(32)  NOT NULL,
    teacher_model       VARCHAR(100),
    teacher_confidence  NUMERIC(5, 4),
    sample_text         VARCHAR(500),
    observed_count      INTEGER      NOT NULL DEFAULT 0,
    reviewed_by         VARCHAR(100),
    reviewed_at         TIMESTAMP,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_ai_phrase_registry_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT uq_ai_phrase_registry_key UNIQUE (factory_id, business_type, normalized_text)
);

CREATE INDEX IF NOT EXISTS idx_ai_phrase_registry_approved
    ON ai_intent_phrase_registry (factory_id, business_type)
    WHERE status = 'APPROVED';

CREATE TABLE IF NOT EXISTS ai_entity_lexicon (
    id           BIGSERIAL PRIMARY KEY,
    factory_id   VARCHAR(64)  NOT NULL,
    entity_type  VARCHAR(32)  NOT NULL,
    alias        VARCHAR(200) NOT NULL,
    source       VARCHAR(32)  NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_ai_entity_lexicon_type CHECK (entity_type IN
        ('MATERIAL', 'PRODUCT', 'PROCESS', 'SUPPLIER', 'CUSTOMER', 'EQUIPMENT', 'PERSON')),
    CONSTRAINT uq_ai_entity_lexicon UNIQUE (factory_id, entity_type, alias)
);
