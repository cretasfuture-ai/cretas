-- Persisted outcome of each consumed delegated run (S6 B3C-C3). Written once when the gateway
-- answers; a run consumed but never resolved (crash between consumption and the gateway) keeps a
-- null outcome, which the read-only reconciliation report lists after a cutoff. Never retried.
ALTER TABLE autonomous_tool_grant_runs ADD COLUMN outcome VARCHAR(20);
ALTER TABLE autonomous_tool_grant_runs ADD COLUMN audit_event_id VARCHAR(36);
ALTER TABLE autonomous_tool_grant_runs ADD COLUMN resolved_at TIMESTAMP;
ALTER TABLE autonomous_tool_grant_runs ADD CONSTRAINT ck_atgr_outcome
    CHECK (outcome IS NULL OR outcome IN ('SUCCEEDED', 'FAILED', 'DENIED', 'OUTCOME_UNKNOWN'));
CREATE INDEX idx_atgr_unresolved ON autonomous_tool_grant_runs (consumed_at) WHERE outcome IS NULL;
