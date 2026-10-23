-- Additive one-shot purchase delegation. No scheduler rows or production grants are seeded.
CREATE TABLE purchase_grant_timer_bindings (
 id UUID PRIMARY KEY,
 factory_id VARCHAR(50) NOT NULL,
 grant_id VARCHAR(36) NOT NULL REFERENCES ai_purchase_task_grants(id),
 user_id BIGINT NOT NULL,
 scheduled_task_id UUID NOT NULL REFERENCES scheduled_tasks(id),
 expected_grant_version BIGINT NOT NULL CHECK(expected_grant_version > 0),
 input_digest VARCHAR(128) NOT NULL,
 scope_digest VARCHAR(64) NOT NULL,
 input_snapshot TEXT NOT NULL,
 command_digest VARCHAR(128) NOT NULL,
 execute_at TIMESTAMP NOT NULL,
 expires_at TIMESTAMP NOT NULL,
 cron_expression VARCHAR(100) NOT NULL,
 task_revision BIGINT NOT NULL,
 state VARCHAR(20) NOT NULL CHECK(state IN ('PENDING','RUNNING','SUCCEEDED','REFUSED','UNKNOWN')),
 result_purchase_order_id VARCHAR(191),
 failure_reason VARCHAR(500),
 created_at TIMESTAMP NOT NULL DEFAULT NOW(),
 updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
 version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT uk_purchase_timer_grant UNIQUE(grant_id),
 CONSTRAINT uk_purchase_timer_task UNIQUE(scheduled_task_id),
 CONSTRAINT ck_purchase_timer_window CHECK(execute_at < expires_at)
);
CREATE INDEX idx_purchase_timer_owner ON purchase_grant_timer_bindings(factory_id,user_id);
-- Rollback policy: leave this additive table in place when rolling application back.
-- Disable only this handler's tasks under a separately authorized rollback before reverting binaries.
-- Never drop delegation/outcome evidence or replay RUNNING/UNKNOWN rows.
