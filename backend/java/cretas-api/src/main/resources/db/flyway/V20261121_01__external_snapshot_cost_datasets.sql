ALTER TABLE external_source_snapshots
    DROP CONSTRAINT ck_ess_dataset,
    ADD CONSTRAINT ck_ess_dataset CHECK (dataset IN ('report_output', 'project_cost', 'production_issue'));
