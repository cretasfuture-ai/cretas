-- Platform-side reads/writes of a factory's data (Steve: platform staff looking at a factory
-- must leave a record that factory can later see). Append-only, one row per HTTP request made
-- by a platform principal against an identifiable target factory. Written by
-- PlatformDataAccessLogInterceptor; read by GET /api/mobile/{factoryId}/platform-access-log.
-- No FK to factories: the write path must stay cheap and must not fail on unknown ids.
CREATE TABLE IF NOT EXISTS platform_data_access_log (
    id                 BIGSERIAL PRIMARY KEY,
    created_at         TIMESTAMP    NOT NULL,
    target_factory_id  VARCHAR(64)  NOT NULL,
    target_source      VARCHAR(16)  NOT NULL,
    actor_type         VARCHAR(24)  NOT NULL,
    actor_user_id      BIGINT       NOT NULL,
    actor_username     VARCHAR(100),
    actor_role         VARCHAR(64),
    actor_factory_id   VARCHAR(64),
    http_method        VARCHAR(10)  NOT NULL,
    request_path       VARCHAR(512) NOT NULL,
    query_param_names  VARCHAR(512),
    response_status    INTEGER      NOT NULL,
    client_ip          VARCHAR(64),
    remote_addr        VARCHAR(64),
    correlation_id     VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_platform_data_access_log_factory_created
    ON platform_data_access_log (target_factory_id, created_at);

COMMENT ON TABLE platform_data_access_log IS
    'One row per request by platform staff (super_admin/platform_admin/developer or PLATFORM token) that targets a factory; visible to that factory';
COMMENT ON COLUMN platform_data_access_log.target_source IS 'PATH = {factoryId} path variable, QUERY = ?factoryId= parameter';
COMMENT ON COLUMN platform_data_access_log.query_param_names IS 'Comma-separated query parameter NAMES only; values are never stored';
COMMENT ON COLUMN platform_data_access_log.client_ip IS 'X-Real-IP / first X-Forwarded-For / socket peer (forwarded headers are client-supplied)';
COMMENT ON COLUMN platform_data_access_log.remote_addr IS 'Socket peer address as seen by the servlet container';
