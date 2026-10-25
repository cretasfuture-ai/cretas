-- Chart preferences are user UI state, independent of uploaded business rows.
-- An empty JSON array means the user explicitly hid all charts; no row means unset.
CREATE TABLE IF NOT EXISTS smart_bi_factory_chart_selections (
    factory_id VARCHAR(50) NOT NULL,
    user_id BIGINT NOT NULL,
    source VARCHAR(10) NOT NULL CHECK (source IN ('business', 'upload')),
    selected_ids JSONB NOT NULL CHECK (jsonb_typeof(selected_ids) = 'array'),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (factory_id, user_id, source)
);

ALTER TABLE smart_bi_factory_chart_selections ENABLE ROW LEVEL SECURITY;
ALTER TABLE smart_bi_factory_chart_selections FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON smart_bi_factory_chart_selections;
CREATE POLICY tenant_isolation ON smart_bi_factory_chart_selections
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));
GRANT SELECT, INSERT, UPDATE ON smart_bi_factory_chart_selections TO smartbi_user;
