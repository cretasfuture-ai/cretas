-- Positive Web session allowlist; no fixed or idle expiration.
CREATE TABLE web_login_sessions (
    session_hash VARCHAR(64) PRIMARY KEY,
    actor_type VARCHAR(24) NOT NULL CHECK (actor_type IN ('FACTORY_USER', 'PLATFORM_ADMIN')),
    user_id BIGINT NOT NULL,
    factory_id VARCHAR(64),
    password_fingerprint VARCHAR(64) NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_web_login_sessions_actor ON web_login_sessions(actor_type, user_id);
