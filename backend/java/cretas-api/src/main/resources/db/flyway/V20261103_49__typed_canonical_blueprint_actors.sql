-- Canonical blueprint actor namespace.
-- Legacy *_by columns remain as nullable factory-user compatibility values only.
-- Historical rows are intentionally not backfilled: a bare numeric id has no
-- trustworthy namespace and therefore remains typed-identity unknown.

ALTER TABLE public.factory_type_blueprints
    ADD COLUMN IF NOT EXISTS factory_id varchar(50),
    ADD COLUMN IF NOT EXISTS factory_name varchar(100),
    ADD COLUMN IF NOT EXISTS scope_type varchar(20) NOT NULL DEFAULT 'TEMPLATE',
    ADD COLUMN IF NOT EXISTS schema_version varchar(50) NOT NULL DEFAULT 'factory-template/v1',
    ADD COLUMN IF NOT EXISTS lifecycle_state varchar(20) NOT NULL DEFAULT 'PUBLISHED',
    ADD COLUMN IF NOT EXISTS is_current boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS source_snapshot jsonb,
    ADD COLUMN IF NOT EXISTS capability_snapshot jsonb,
    ADD COLUMN IF NOT EXISTS capability_catalog_version varchar(64),
    ADD COLUMN IF NOT EXISTS capability_catalog_digest varchar(64),
    ADD COLUMN IF NOT EXISTS plan_snapshot jsonb,
    ADD COLUMN IF NOT EXISTS validation_report jsonb,
    ADD COLUMN IF NOT EXISTS simulation_report jsonb,
    ADD COLUMN IF NOT EXISTS draft_idempotency_key varchar(100),
    ADD COLUMN IF NOT EXISTS request_digest varchar(64),
    ADD COLUMN IF NOT EXISTS blueprint_created_by bigint,
    ADD COLUMN IF NOT EXISTS blueprint_updated_by bigint,
    ADD COLUMN IF NOT EXISTS confirmed_at timestamp,
    ADD COLUMN IF NOT EXISTS published_at timestamp,
    ADD COLUMN IF NOT EXISTS published_by bigint,
    ADD COLUMN IF NOT EXISTS row_version bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS blueprint_created_actor_type varchar(32),
    ADD COLUMN IF NOT EXISTS blueprint_created_actor_id bigint,
    ADD COLUMN IF NOT EXISTS blueprint_updated_actor_type varchar(32),
    ADD COLUMN IF NOT EXISTS blueprint_updated_actor_id bigint,
    ADD COLUMN IF NOT EXISTS published_actor_type varchar(32),
    ADD COLUMN IF NOT EXISTS published_actor_id bigint;

ALTER TABLE public.blueprint_version_history
    ADD COLUMN IF NOT EXISTS factory_id varchar(50),
    ADD COLUMN IF NOT EXISTS schema_version varchar(50),
    ADD COLUMN IF NOT EXISTS lifecycle_state varchar(20),
    ADD COLUMN IF NOT EXISTS idempotency_key varchar(100),
    ADD COLUMN IF NOT EXISTS published_by bigint,
    ADD COLUMN IF NOT EXISTS created_actor_type varchar(32),
    ADD COLUMN IF NOT EXISTS created_actor_id bigint,
    ADD COLUMN IF NOT EXISTS published_actor_type varchar(32),
    ADD COLUMN IF NOT EXISTS published_actor_id bigint;

ALTER TABLE public.blueprint_applications
    ADD COLUMN IF NOT EXISTS blueprint_version integer,
    ADD COLUMN IF NOT EXISTS operation_type varchar(30),
    ADD COLUMN IF NOT EXISTS idempotency_key varchar(100),
    ADD COLUMN IF NOT EXISTS operation_result jsonb,
    ADD COLUMN IF NOT EXISTS module_results jsonb,
    ADD COLUMN IF NOT EXISTS recovery_plan jsonb,
    ADD COLUMN IF NOT EXISTS business_write_count integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS row_version bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS applied_actor_type varchar(32),
    ADD COLUMN IF NOT EXISTS applied_actor_id bigint;

ALTER TABLE public.form_templates
    ADD COLUMN IF NOT EXISTS created_actor_type varchar(32),
    ADD COLUMN IF NOT EXISTS created_actor_id bigint;

ALTER TABLE public.material_product_conversions
    ADD COLUMN IF NOT EXISTS created_actor_type varchar(32),
    ADD COLUMN IF NOT EXISTS created_actor_id bigint,
    ADD COLUMN IF NOT EXISTS updated_actor_type varchar(32),
    ADD COLUMN IF NOT EXISTS updated_actor_id bigint;

ALTER TABLE public.factory_configurations
    ADD COLUMN IF NOT EXISTS created_actor_type varchar(32),
    ADD COLUMN IF NOT EXISTS created_actor_id bigint;

ALTER TABLE public.factory_blueprint_bindings
    ADD COLUMN IF NOT EXISTS published_version integer,
    ADD COLUMN IF NOT EXISTS row_version bigint NOT NULL DEFAULT 0;

-- Existing/template rows get only canonical defaults.  Numeric legacy users
-- are intentionally not retyped as platform or factory actors.
UPDATE public.factory_type_blueprints
   SET scope_type = 'TEMPLATE', schema_version = 'factory-template/v1',
       lifecycle_state = 'PUBLISHED', is_current = false
 WHERE factory_id IS NULL AND (scope_type IS NULL OR scope_type = 'TEMPLATE');

ALTER TABLE public.factory_type_blueprints
    DROP CONSTRAINT IF EXISTS ck_factory_blueprint_created_actor_pair,
    DROP CONSTRAINT IF EXISTS ck_factory_blueprint_updated_actor_pair,
    DROP CONSTRAINT IF EXISTS ck_factory_blueprint_published_actor_pair,
    ADD CONSTRAINT ck_factory_blueprint_created_actor_pair CHECK (
        (blueprint_created_actor_type IS NULL AND blueprint_created_actor_id IS NULL)
        OR (blueprint_created_actor_type IS NOT NULL AND blueprint_created_actor_id IS NOT NULL AND blueprint_created_actor_type = 'FACTORY_USER' AND blueprint_created_actor_id > 0 AND blueprint_created_by IS NOT NULL AND blueprint_created_by = blueprint_created_actor_id)
        OR (blueprint_created_actor_type IS NOT NULL AND blueprint_created_actor_id IS NOT NULL AND blueprint_created_actor_type = 'PLATFORM_ADMIN' AND blueprint_created_actor_id > 0 AND blueprint_created_by IS NULL)),
    ADD CONSTRAINT ck_factory_blueprint_updated_actor_pair CHECK (
        (blueprint_updated_actor_type IS NULL AND blueprint_updated_actor_id IS NULL)
        OR (blueprint_updated_actor_type IS NOT NULL AND blueprint_updated_actor_id IS NOT NULL AND blueprint_updated_actor_type = 'FACTORY_USER' AND blueprint_updated_actor_id > 0 AND blueprint_updated_by IS NOT NULL AND blueprint_updated_by = blueprint_updated_actor_id)
        OR (blueprint_updated_actor_type IS NOT NULL AND blueprint_updated_actor_id IS NOT NULL AND blueprint_updated_actor_type = 'PLATFORM_ADMIN' AND blueprint_updated_actor_id > 0 AND blueprint_updated_by IS NULL)),
    ADD CONSTRAINT ck_factory_blueprint_published_actor_pair CHECK (
        (published_actor_type IS NULL AND published_actor_id IS NULL)
        OR (published_actor_type IS NOT NULL AND published_actor_id IS NOT NULL AND published_actor_type = 'FACTORY_USER' AND published_actor_id > 0 AND published_by IS NOT NULL AND published_by = published_actor_id)
        OR (published_actor_type IS NOT NULL AND published_actor_id IS NOT NULL AND published_actor_type = 'PLATFORM_ADMIN' AND published_actor_id > 0 AND published_by IS NULL));

ALTER TABLE public.blueprint_version_history
    DROP CONSTRAINT IF EXISTS ck_blueprint_history_created_actor_pair,
    DROP CONSTRAINT IF EXISTS ck_blueprint_history_published_actor_pair,
    ADD CONSTRAINT ck_blueprint_history_created_actor_pair CHECK (
        (created_actor_type IS NULL AND created_actor_id IS NULL)
        OR (created_actor_type IS NOT NULL AND created_actor_id IS NOT NULL AND created_actor_type = 'FACTORY_USER' AND created_actor_id > 0 AND created_by IS NOT NULL AND created_by = created_actor_id)
        OR (created_actor_type IS NOT NULL AND created_actor_id IS NOT NULL AND created_actor_type = 'PLATFORM_ADMIN' AND created_actor_id > 0 AND created_by IS NULL)),
    ADD CONSTRAINT ck_blueprint_history_published_actor_pair CHECK (
        (published_actor_type IS NULL AND published_actor_id IS NULL)
        OR (published_actor_type IS NOT NULL AND published_actor_id IS NOT NULL AND published_actor_type = 'FACTORY_USER' AND published_actor_id > 0 AND published_by IS NOT NULL AND published_by = published_actor_id)
        OR (published_actor_type IS NOT NULL AND published_actor_id IS NOT NULL AND published_actor_type = 'PLATFORM_ADMIN' AND published_actor_id > 0 AND published_by IS NULL));

ALTER TABLE public.blueprint_applications
    DROP CONSTRAINT IF EXISTS ck_blueprint_application_applied_actor_pair,
    ADD CONSTRAINT ck_blueprint_application_applied_actor_pair CHECK (
        (applied_actor_type IS NULL AND applied_actor_id IS NULL)
        OR (applied_actor_type IS NOT NULL AND applied_actor_id IS NOT NULL AND applied_actor_type = 'FACTORY_USER' AND applied_actor_id > 0 AND applied_by IS NOT NULL AND applied_by = applied_actor_id)
        OR (applied_actor_type IS NOT NULL AND applied_actor_id IS NOT NULL AND applied_actor_type = 'PLATFORM_ADMIN' AND applied_actor_id > 0 AND applied_by IS NULL));

ALTER TABLE public.blueprint_applications DROP CONSTRAINT IF EXISTS blueprint_applications_status_check;
ALTER TABLE public.blueprint_applications ADD CONSTRAINT blueprint_applications_status_check
    CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'PARTIAL_FAILED', 'ROLLED_BACK'));

CREATE UNIQUE INDEX IF NOT EXISTS uk_factory_blueprint_current_factory
    ON public.factory_type_blueprints (factory_id)
    WHERE deleted_at IS NULL AND factory_id IS NOT NULL AND is_current;
CREATE UNIQUE INDEX IF NOT EXISTS uk_factory_blueprint_draft_key
    ON public.factory_type_blueprints (factory_id, draft_idempotency_key)
    WHERE deleted_at IS NULL AND factory_id IS NOT NULL AND draft_idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_blueprint_application_receipt
    ON public.blueprint_applications (factory_id, operation_type, idempotency_key)
    WHERE deleted_at IS NULL AND operation_type IS NOT NULL AND idempotency_key IS NOT NULL;

-- Platform actors must never be forced into factory-user compatibility columns.
ALTER TABLE public.product_types ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE public.raw_material_types ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE public.factory_configurations ALTER COLUMN created_by DROP NOT NULL;

ALTER TABLE public.factory_configurations ADD CONSTRAINT ck_assembly_configuration_created_actor CHECK (
    (created_actor_type IS NULL AND created_actor_id IS NULL AND created_by IS NOT NULL AND created_by > 0)
    OR (created_actor_type IS NOT NULL AND created_actor_id IS NOT NULL AND created_actor_id > 0
        AND ((created_actor_type = 'FACTORY_USER' AND created_by IS NOT DISTINCT FROM created_actor_id)
            OR (created_actor_type = 'PLATFORM_ADMIN' AND created_by IS NULL)))
);

