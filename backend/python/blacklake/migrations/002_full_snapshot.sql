-- Explicit local-test installation, in the caller's transaction with role grants.
-- This archive does not change the seven-day PoC business tables or cursors.
CREATE TABLE blacklake.full_snapshot (
    tenant_id text NOT NULL,
    source_factory_id text NOT NULL,
    run_id text NOT NULL,
    resource text NOT NULL,
    external_id text NOT NULL,
    content_hash text NOT NULL,
    source_updated_at timestamptz,
    normalized jsonb NOT NULL,
    observed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,source_factory_id,run_id,resource,external_id)
);
ALTER TABLE blacklake.full_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE blacklake.full_snapshot FORCE ROW LEVEL SECURITY;
CREATE POLICY scope ON blacklake.full_snapshot USING (
    tenant_id=current_setting('blacklake.tenant_id',true) AND
    source_factory_id=current_setting('blacklake.source_factory_id',true)
) WITH CHECK (
    tenant_id=current_setting('blacklake.tenant_id',true) AND
    source_factory_id=current_setting('blacklake.source_factory_id',true)
);
