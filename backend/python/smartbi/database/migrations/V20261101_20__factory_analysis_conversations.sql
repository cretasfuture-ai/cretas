-- Durable factory-analysis conversation metadata. No facts, rows, or raw DB data.
CREATE TABLE IF NOT EXISTS smart_bi_factory_analysis_conversations (
    context_id VARCHAR(200) PRIMARY KEY,
    factory_id VARCHAR(100) NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    role VARCHAR(100) NOT NULL,
    source VARCHAR(32) NOT NULL,
    upload_id BIGINT,
    scope JSONB NOT NULL DEFAULT '{}'::jsonb,
    selected_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    history JSONB NOT NULL DEFAULT '[]'::jsonb,
    unresolved_product BOOLEAN NOT NULL DEFAULT FALSE,
    unresolved_period BOOLEAN NOT NULL DEFAULT FALSE,
    period_error TEXT,
    expires_at TIMESTAMPTZ NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    lease_token VARCHAR(200),
    lease_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS smart_bi_factory_analysis_conversations_factory_expiry
    ON smart_bi_factory_analysis_conversations(factory_id, expires_at);
ALTER TABLE smart_bi_factory_analysis_conversations ENABLE ROW LEVEL SECURITY;
ALTER TABLE smart_bi_factory_analysis_conversations FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS smart_bi_factory_analysis_conversations_tenant ON smart_bi_factory_analysis_conversations;
CREATE POLICY smart_bi_factory_analysis_conversations_tenant
    ON smart_bi_factory_analysis_conversations
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));
GRANT SELECT, INSERT, UPDATE, DELETE ON smart_bi_factory_analysis_conversations TO smartbi_user;
