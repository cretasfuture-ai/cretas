CREATE TABLE fact_external_work_order_cost (
    factory_id            VARCHAR(50) NOT NULL,
    source_system         VARCHAR(30) NOT NULL,
    work_order_code       TEXT NOT NULL,
    cost_date             DATE NOT NULL,
    product_code          TEXT NOT NULL,
    product_name          TEXT,
    output_unit           TEXT,
    output_qty            NUMERIC,
    material_cost         NUMERIC,
    labor_cost            NUMERIC,
    other_cost            NUMERIC,
    total_cost            NUMERIC,
    unit_cost             NUMERIC,
    snapshot_id           BIGINT NOT NULL,
    snapshot_collected_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (factory_id, source_system, work_order_code)
);

CREATE TABLE fact_external_material_issue (
    factory_id            VARCHAR(50) NOT NULL,
    source_system         VARCHAR(30) NOT NULL,
    line_id               TEXT NOT NULL,
    issue_date            DATE NOT NULL,
    work_order_code       TEXT,
    material_code         TEXT NOT NULL,
    material_name         TEXT,
    unit                  TEXT,
    quantity              NUMERIC NOT NULL,
    cost_unit_price       NUMERIC,
    signed_cost_amount    NUMERIC,
    moving_average_price  NUMERIC,
    effective_unit_price  NUMERIC,
    issue_cost            NUMERIC,
    price_source          TEXT NOT NULL
        CHECK (price_source IN ('reported', 'moving_avg', 'price_missing', 'invalid_valuation')),
    snapshot_id           BIGINT NOT NULL,
    snapshot_collected_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (factory_id, source_system, line_id)
);

CREATE TABLE external_cost_snapshot_state (
    factory_id            VARCHAR(50) NOT NULL,
    source_system         VARCHAR(30) NOT NULL,
    dataset               TEXT NOT NULL CHECK (dataset IN ('project_cost', 'production_issue')),
    snapshot_id           BIGINT NOT NULL,
    snapshot_collected_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (factory_id, source_system, dataset)
);

ALTER TABLE fact_external_work_order_cost ENABLE ROW LEVEL SECURITY;
ALTER TABLE fact_external_work_order_cost FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fact_external_work_order_cost FOR ALL
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

ALTER TABLE fact_external_material_issue ENABLE ROW LEVEL SECURITY;
ALTER TABLE fact_external_material_issue FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fact_external_material_issue FOR ALL
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

ALTER TABLE external_cost_snapshot_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE external_cost_snapshot_state FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON external_cost_snapshot_state FOR ALL
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

GRANT SELECT, INSERT, UPDATE, DELETE ON fact_external_work_order_cost, fact_external_material_issue TO smartbi_user;
GRANT SELECT, INSERT, UPDATE ON external_cost_snapshot_state TO smartbi_user;
