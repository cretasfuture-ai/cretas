-- Factory-domain AgentOps run traces (per-run steps, tools, latency, outcome).
-- Separate from smart_bi_agent_run, which is hard-locked to the restaurant route.
-- Append-only and tenant-isolated like the eval-set/experiment tables.

CREATE TABLE IF NOT EXISTS smart_bi_factory_agent_trace (
    run_id UUID NOT NULL,
    factory_id VARCHAR(64) NOT NULL,
    intent_code VARCHAR(96) NOT NULL CHECK (intent_code ~ '^[A-Z][A-Z0-9_]{2,95}$'),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILED', 'BLOCKED', 'UNKNOWN')),
    latency_ms INTEGER NOT NULL CHECK (latency_ms BETWEEN 0 AND 3600000),
    steps JSONB NOT NULL CHECK (jsonb_typeof(steps) = 'array'),
    recorded_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (run_id),
    CHECK (jsonb_array_length(steps) BETWEEN 1 AND 20),
    CHECK (octet_length(steps::text) <= 32768),
    CHECK (steps::text !~* '"(prompt|raw[_-]?prompt|secret|token|password|authorization|cookie|api[_-]?key|credential)"[[:space:]]*:')
);

CREATE INDEX IF NOT EXISTS idx_factory_agent_trace_tenant_created
    ON smart_bi_factory_agent_trace (factory_id, created_at DESC);

DROP TRIGGER IF EXISTS trg_factory_agent_trace_immutable ON smart_bi_factory_agent_trace;
CREATE TRIGGER trg_factory_agent_trace_immutable
BEFORE UPDATE OR DELETE ON smart_bi_factory_agent_trace
FOR EACH ROW EXECUTE FUNCTION smart_bi_agentops_append_only();

ALTER TABLE smart_bi_factory_agent_trace ENABLE ROW LEVEL SECURITY;
ALTER TABLE smart_bi_factory_agent_trace FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS factory_agent_trace_tenant_select ON smart_bi_factory_agent_trace;
CREATE POLICY factory_agent_trace_tenant_select ON smart_bi_factory_agent_trace
FOR SELECT USING (factory_id = current_setting('app.factory_id', true));
DROP POLICY IF EXISTS factory_agent_trace_tenant_insert ON smart_bi_factory_agent_trace;
CREATE POLICY factory_agent_trace_tenant_insert ON smart_bi_factory_agent_trace
FOR INSERT WITH CHECK (factory_id = current_setting('app.factory_id', true));

REVOKE ALL ON smart_bi_factory_agent_trace FROM PUBLIC;
REVOKE ALL ON smart_bi_factory_agent_trace FROM smartbi_user;
GRANT SELECT, INSERT ON smart_bi_factory_agent_trace TO smartbi_user;

COMMENT ON TABLE smart_bi_factory_agent_trace IS
    'Immutable tenant-scoped factory-domain agent run traces for AgentOps.';
