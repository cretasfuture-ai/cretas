-- Client actions and verified API outcomes are separate from durable business change audit.
CREATE TABLE IF NOT EXISTS activity_events (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    factory_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    source VARCHAR(16) NOT NULL,
    action VARCHAR(128) NOT NULL,
    route VARCHAR(256) NOT NULL,
    detail JSONB NOT NULL DEFAULT '{}'::jsonb,
    correlation_id VARCHAR(64),
    client_action_id VARCHAR(64),
    client VARCHAR(16) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT activity_events_source_check CHECK (source IN ('screen', 'click', 'input', 'block', 'api')),
    CONSTRAINT activity_events_actor_event_unique UNIQUE (factory_id, user_id, event_id)
);

CREATE INDEX IF NOT EXISTS idx_activity_events_factory_time
    ON activity_events (factory_id, occurred_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_activity_events_expiry
    ON activity_events (occurred_at);
CREATE INDEX IF NOT EXISTS idx_activity_events_user_time
    ON activity_events (factory_id, user_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_activity_events_correlation
    ON activity_events (factory_id, correlation_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_activity_events_action
    ON activity_events (factory_id, client_action_id) WHERE client_action_id IS NOT NULL;
