-- Apply explicitly to the dedicated PoC database using its migration owner.
-- Never imported/applied automatically by FastAPI. No ERP tables are altered.
-- Caller must execute the whole migration AND role/grant setup in one transaction.
CREATE SCHEMA blacklake;
CREATE TABLE blacklake.raw_record (
    tenant_id text NOT NULL,
    source_factory_id text NOT NULL,
    resource text NOT NULL,
    external_id text NOT NULL,
    content_hash text NOT NULL,
    payload jsonb NOT NULL,
    observed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, source_factory_id, resource, external_id, content_hash)
);
CREATE TABLE blacklake.record (
    tenant_id text NOT NULL,
    source_factory_id text NOT NULL,
    resource text NOT NULL,
    external_id text NOT NULL,
    source_updated_at timestamptz NOT NULL,
    normalized jsonb NOT NULL,
    content_hash text NOT NULL,
    observed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, source_factory_id, resource, external_id)
);
CREATE TABLE blacklake.cursor (
    tenant_id text NOT NULL,
    source_factory_id text NOT NULL,
    resource text NOT NULL,
    watermark timestamptz NOT NULL,
    contract_hash text NOT NULL,
    PRIMARY KEY (tenant_id, source_factory_id, resource)
);
CREATE TABLE blacklake.sync_run (
    tenant_id text NOT NULL,
    source_factory_id text NOT NULL,
    external_id text NOT NULL,
    resource text NOT NULL,
    window_start timestamptz NOT NULL,
    window_end timestamptz NOT NULL,
    row_count integer,
    status text NOT NULL CHECK (status IN ('success', 'failed')),
    error_code text,
    finished_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, source_factory_id, external_id)
);
DO $$
DECLARE tab text;
BEGIN
  FOREACH tab IN ARRAY ARRAY['raw_record','record','cursor','sync_run'] LOOP
    EXECUTE format('ALTER TABLE blacklake.%I ENABLE ROW LEVEL SECURITY', tab);
    EXECUTE format('ALTER TABLE blacklake.%I FORCE ROW LEVEL SECURITY', tab);
    EXECUTE format('CREATE POLICY scope ON blacklake.%I USING (
      tenant_id = current_setting(''blacklake.tenant_id'', true) AND
      source_factory_id = current_setting(''blacklake.source_factory_id'', true)
    ) WITH CHECK (
      tenant_id = current_setting(''blacklake.tenant_id'', true) AND
      source_factory_id = current_setting(''blacklake.source_factory_id'', true)
    )', tab);
  END LOOP;
END $$;
