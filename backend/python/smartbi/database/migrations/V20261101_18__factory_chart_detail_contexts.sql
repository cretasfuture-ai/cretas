-- Cross-worker SmartBI chart detail snapshots. Technical cache only:
-- immutable source rows are held briefly so another uvicorn worker can page
-- the same recommendation. No business tables are written.

CREATE TABLE IF NOT EXISTS smart_bi_factory_chart_detail_snapshots (
    snapshot_id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    role VARCHAR(128) NOT NULL,
    source TEXT NOT NULL,
    period_label TEXT NOT NULL,
    unit TEXT NOT NULL DEFAULT '',
    rows JSONB NOT NULL,
    columns JSONB NOT NULL,
    dimensions JSONB NOT NULL,
    price_columns JSONB NOT NULL,
    financial BOOLEAN NOT NULL DEFAULT FALSE,
    size_bytes INTEGER NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 8388608),
    dedup_key CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS smart_bi_factory_chart_detail_contexts (
    context_id VARCHAR(128) PRIMARY KEY,
    snapshot_id BIGINT NOT NULL REFERENCES smart_bi_factory_chart_detail_snapshots(snapshot_id),
    factory_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    role VARCHAR(128) NOT NULL,
    candidate_id TEXT NOT NULL,
    title TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_smart_bi_chart_detail_context_expiry
    ON smart_bi_factory_chart_detail_contexts (expires_at);
CREATE INDEX IF NOT EXISTS idx_smart_bi_chart_detail_context_owner
    ON smart_bi_factory_chart_detail_contexts (factory_id, user_id, role);

CREATE OR REPLACE FUNCTION smart_bi_chart_detail_snapshot_immutable()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'factory chart detail snapshots are immutable';
    END IF;
    RETURN OLD;
END;
$$;

DROP TRIGGER IF EXISTS trg_smart_bi_chart_detail_snapshot_immutable
    ON smart_bi_factory_chart_detail_snapshots;
CREATE TRIGGER trg_smart_bi_chart_detail_snapshot_immutable
    BEFORE UPDATE ON smart_bi_factory_chart_detail_snapshots
    FOR EACH ROW EXECUTE FUNCTION smart_bi_chart_detail_snapshot_immutable();

ALTER TABLE smart_bi_factory_chart_detail_snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE smart_bi_factory_chart_detail_snapshots FORCE ROW LEVEL SECURITY;
ALTER TABLE smart_bi_factory_chart_detail_contexts ENABLE ROW LEVEL SECURITY;
ALTER TABLE smart_bi_factory_chart_detail_contexts FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS smart_bi_chart_detail_snapshot_tenant ON smart_bi_factory_chart_detail_snapshots;
CREATE POLICY smart_bi_chart_detail_snapshot_tenant ON smart_bi_factory_chart_detail_snapshots
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));
DROP POLICY IF EXISTS smart_bi_chart_detail_context_tenant ON smart_bi_factory_chart_detail_contexts;
CREATE POLICY smart_bi_chart_detail_context_tenant ON smart_bi_factory_chart_detail_contexts
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

GRANT SELECT, INSERT, DELETE ON smart_bi_factory_chart_detail_snapshots TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE smart_bi_factory_chart_detail_snapshots_snapshot_id_seq TO smartbi_user;
GRANT SELECT, INSERT, DELETE ON smart_bi_factory_chart_detail_contexts TO smartbi_user;
