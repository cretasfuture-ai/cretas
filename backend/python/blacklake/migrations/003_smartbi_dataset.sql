-- Explicit installation into verified local smartbi_testenv only.
CREATE SCHEMA blacklake_bi;
REVOKE ALL ON SCHEMA blacklake_bi FROM PUBLIC;
CREATE TABLE blacklake_bi.dataset (
 tenant_id text NOT NULL, source_factory_id text NOT NULL, run_id text NOT NULL,
 metadata jsonb NOT NULL, loaded_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,source_factory_id,run_id)
);
CREATE TABLE blacklake_bi.record (
 tenant_id text NOT NULL, source_factory_id text NOT NULL, run_id text NOT NULL,
 resource text NOT NULL, external_id text NOT NULL, content_hash text NOT NULL, payload jsonb NOT NULL,
 PRIMARY KEY(tenant_id,source_factory_id,run_id,resource,external_id),
 FOREIGN KEY(tenant_id,source_factory_id,run_id) REFERENCES blacklake_bi.dataset
);
ALTER TABLE blacklake_bi.dataset ENABLE ROW LEVEL SECURITY;
ALTER TABLE blacklake_bi.dataset FORCE ROW LEVEL SECURITY;
ALTER TABLE blacklake_bi.record ENABLE ROW LEVEL SECURITY;
ALTER TABLE blacklake_bi.record FORCE ROW LEVEL SECURITY;
CREATE POLICY scope ON blacklake_bi.dataset USING (
 tenant_id=current_setting('blacklake.tenant_id',true) AND source_factory_id=current_setting('blacklake.source_factory_id',true)
);
CREATE POLICY scope ON blacklake_bi.record USING (
 tenant_id=current_setting('blacklake.tenant_id',true) AND source_factory_id=current_setting('blacklake.source_factory_id',true)
);
