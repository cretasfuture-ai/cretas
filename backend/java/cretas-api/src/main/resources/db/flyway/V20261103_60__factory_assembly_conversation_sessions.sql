-- Factory Assembly owns a namespaced conversation store.  It is deliberately
-- separate from the legacy conversation_sessions table: the latter has no
-- trustworthy platform/factory actor namespace and is not altered here.
CREATE TABLE IF NOT EXISTS factory_assembly_conversation_sessions (
    session_id              VARCHAR(36) PRIMARY KEY,
    factory_id              VARCHAR(50),
    user_id                 BIGINT,
    actor_type              VARCHAR(32) NOT NULL DEFAULT 'FACTORY_USER',
    original_input          TEXT NOT NULL,
    final_intent_code       VARCHAR(100),
    current_round           INTEGER NOT NULL DEFAULT 0,
    max_rounds              INTEGER DEFAULT 5,
    status                  VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    session_mode            VARCHAR(30) DEFAULT 'INTENT_RECOGNITION',
    known_intent_code       VARCHAR(100),
    required_parameters_json TEXT,
    collected_parameters_json TEXT,
    messages_json           TEXT,
    candidates_json         TEXT,
    last_confidence         DOUBLE PRECISION,
    timeout_minutes         INTEGER DEFAULT 10,
    created_at              TIMESTAMP WITHOUT TIME ZONE,
    updated_at              TIMESTAMP WITHOUT TIME ZONE,
    last_active_at          TIMESTAMP WITHOUT TIME ZONE,
    completed_at            TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT ck_assembly_conversation_actor_type
        CHECK (actor_type IN ('FACTORY_USER', 'PLATFORM_ADMIN')),
    CONSTRAINT ck_assembly_conversation_status
        CHECK (status IN ('ACTIVE', 'COMPLETED', 'TIMEOUT', 'CANCELLED', 'MAX_ROUNDS_REACHED')),
    CONSTRAINT ck_assembly_conversation_mode
        CHECK (session_mode IN ('INTENT_RECOGNITION', 'PARAMETER_COLLECTION'))
);

CREATE INDEX IF NOT EXISTS idx_assembly_cs_factory_user
    ON factory_assembly_conversation_sessions (factory_id, user_id);
CREATE INDEX IF NOT EXISTS idx_assembly_cs_factory_actor
    ON factory_assembly_conversation_sessions (factory_id, actor_type, user_id);
CREATE INDEX IF NOT EXISTS idx_assembly_cs_status
    ON factory_assembly_conversation_sessions (status);
CREATE INDEX IF NOT EXISTS idx_assembly_cs_created_at
    ON factory_assembly_conversation_sessions (created_at);
