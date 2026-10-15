-- Server-owned approvals bind immutable plans; no historical identity is inferred.
CREATE TABLE factory_assembly_approvals (
    id VARCHAR(36) PRIMARY KEY,
    factory_id VARCHAR(50) NOT NULL,
    blueprint_id VARCHAR(100) NOT NULL,
    capability_id VARCHAR(40) NOT NULL,
    draft_id VARCHAR(120) NOT NULL,
    plan_digest CHAR(64) NOT NULL,
    plan_request_json JSONB NOT NULL,
    plan_json JSONB NOT NULL,
    approved_json JSONB,
    requested_actor_type VARCHAR(32) NOT NULL,
    requested_actor_id BIGINT NOT NULL,
    approved_actor_type VARCHAR(32),
    approved_actor_id BIGINT,
    approval_digest CHAR(64),
    approved_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT ck_assembly_approval_capability CHECK (capability_id IN ('modules', 'workflow')),
    CONSTRAINT ck_assembly_approval_status CHECK (status IN ('REQUESTED', 'APPROVED', 'EXECUTED')),
    CONSTRAINT ck_assembly_approval_requested_actor CHECK (
        requested_actor_type IN ('FACTORY_USER', 'PLATFORM_ADMIN') AND requested_actor_id > 0),
    CONSTRAINT ck_assembly_approval_approved_actor CHECK (
        (approved_actor_type IS NULL AND approved_actor_id IS NULL)
        OR (approved_actor_type IS NOT NULL AND approved_actor_id IS NOT NULL
            AND approved_actor_type IN ('FACTORY_USER', 'PLATFORM_ADMIN') AND approved_actor_id > 0
            AND ROW(approved_actor_type, approved_actor_id) IS DISTINCT FROM ROW(requested_actor_type, requested_actor_id))),
    CONSTRAINT ck_assembly_approval_binding CHECK (
        (status = 'REQUESTED' AND approved_actor_type IS NULL AND approved_json IS NULL
            AND approval_digest IS NULL AND approved_at IS NULL AND expires_at IS NULL)
        OR (status IN ('APPROVED', 'EXECUTED') AND approved_actor_type IS NOT NULL
            AND approved_actor_id IS NOT NULL AND approved_json IS NOT NULL
            AND approval_digest IS NOT NULL AND approved_at IS NOT NULL
            AND expires_at IS NOT NULL AND expires_at > approved_at)),
    CONSTRAINT ck_assembly_approval_plan_digest CHECK (plan_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_assembly_approval_digest CHECK (approval_digest IS NULL OR approval_digest ~ '^[0-9a-f]{64}$')
);
CREATE INDEX idx_assembly_approval_factory_status ON factory_assembly_approvals(factory_id, status);
