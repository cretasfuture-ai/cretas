-- Blacklake report_output: daily production facts, with the source's exact unit
-- and SKU. Input quantities and manufacturing costs are absent from this feed.
CREATE TABLE fact_external_production_daily (
    factory_id            VARCHAR(50) NOT NULL,
    source_system         VARCHAR(30) NOT NULL,
    report_date           DATE        NOT NULL,
    product_code          TEXT        NOT NULL,
    product_name          TEXT        NOT NULL,
    process_name          TEXT        NOT NULL,
    unit                  TEXT        NOT NULL,
    good_qty              NUMERIC     NOT NULL,
    defect_qty            NUMERIC     NOT NULL,
    report_count          BIGINT      NOT NULL,
    snapshot_id           BIGINT      NOT NULL,
    snapshot_collected_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (factory_id, source_system, report_date, product_code, process_name, unit)
);

-- Keep freshness independent of date filters and of whether the latest
-- snapshot contains rows. Older days can retain facts from previous snapshots.
CREATE TABLE external_production_snapshot_state (
    factory_id            VARCHAR(50) NOT NULL,
    source_system         VARCHAR(30) NOT NULL,
    snapshot_id           BIGINT      NOT NULL,
    snapshot_collected_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (factory_id, source_system)
);

ALTER TABLE fact_external_production_daily ENABLE ROW LEVEL SECURITY;
ALTER TABLE fact_external_production_daily FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fact_external_production_daily FOR ALL
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

ALTER TABLE external_production_snapshot_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE external_production_snapshot_state FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON external_production_snapshot_state FOR ALL
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

GRANT SELECT, INSERT, UPDATE, DELETE ON fact_external_production_daily TO smartbi_user;
GRANT SELECT, INSERT, UPDATE ON external_production_snapshot_state TO smartbi_user;
