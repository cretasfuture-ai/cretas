-- Human IDs are namespaced. Unknown historical identities are deliberately not backfilled.
-- Legacy users columns remain compatible for factory actors and NULL for platform actors.

ALTER TABLE product_types ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE product_types
    ADD COLUMN actor_type VARCHAR(32),
    ADD COLUMN actor_id BIGINT,
    ADD CONSTRAINT ck_asm_product_actor CHECK (
        (actor_type IS NULL AND actor_id IS NULL AND created_by IS NOT NULL AND created_by > 0)
        OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
            AND ((actor_type = 'FACTORY_USER' AND created_by IS NOT DISTINCT FROM actor_id)
                OR (actor_type = 'PLATFORM_ADMIN' AND created_by IS NULL)))
    );

ALTER TABLE raw_material_types ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE raw_material_types
    ADD COLUMN actor_type VARCHAR(32),
    ADD COLUMN actor_id BIGINT,
    ADD CONSTRAINT ck_asm_material_actor CHECK (
        (actor_type IS NULL AND actor_id IS NULL)
        OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
            AND ((actor_type = 'FACTORY_USER' AND created_by IS NOT DISTINCT FROM actor_id)
                OR (actor_type = 'PLATFORM_ADMIN' AND created_by IS NULL)))
    );

ALTER TABLE master_data_draft_promotions ALTER COLUMN operator_id DROP NOT NULL;
ALTER TABLE master_data_draft_promotions
    ADD COLUMN actor_type VARCHAR(32),
    ADD COLUMN actor_id BIGINT,
    ADD CONSTRAINT ck_asm_promotion_actor CHECK (
        (actor_type IS NULL AND actor_id IS NULL AND operator_id IS NOT NULL AND operator_id > 0)
        OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
            AND ((actor_type = 'FACTORY_USER' AND operator_id IS NOT DISTINCT FROM actor_id)
                OR (actor_type = 'PLATFORM_ADMIN' AND operator_id IS NULL)))
    );

ALTER TABLE bom_recipe_drafts ALTER COLUMN operator_id DROP NOT NULL;
ALTER TABLE bom_recipe_drafts
    ADD COLUMN actor_type VARCHAR(32),
    ADD COLUMN actor_id BIGINT,
    ADD CONSTRAINT ck_asm_bom_actor CHECK (
        (actor_type IS NULL AND actor_id IS NULL AND operator_id IS NOT NULL AND operator_id > 0)
        OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
            AND ((actor_type = 'FACTORY_USER' AND operator_id IS NOT DISTINCT FROM actor_id)
                OR (actor_type = 'PLATFORM_ADMIN' AND operator_id IS NULL)))
    );

ALTER TABLE factory_workflow_draft_materialization_ledger ALTER COLUMN operator_id DROP NOT NULL;
ALTER TABLE factory_workflow_draft_materialization_ledger
    ADD COLUMN actor_type VARCHAR(32),
    ADD COLUMN actor_id BIGINT,
    ADD CONSTRAINT ck_asm_workflow_draft_actor CHECK (
        (actor_type IS NULL AND actor_id IS NULL AND operator_id IS NOT NULL AND operator_id > 0)
        OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
            AND ((actor_type = 'FACTORY_USER' AND operator_id IS NOT DISTINCT FROM actor_id)
                OR (actor_type = 'PLATFORM_ADMIN' AND operator_id IS NULL)))
    );

ALTER TABLE product_process_workflow_activations ALTER COLUMN activated_by DROP NOT NULL;
ALTER TABLE product_process_workflow_activations
    ADD COLUMN actor_type VARCHAR(32),
    ADD COLUMN actor_id BIGINT,
    ADD CONSTRAINT ck_asm_workflow_activation_actor CHECK (
        (actor_type IS NULL AND actor_id IS NULL)
        OR (actor_type IS NOT NULL AND actor_id IS NOT NULL AND actor_id > 0
            AND ((actor_type = 'FACTORY_USER' AND activated_by IS NOT DISTINCT FROM actor_id)
                OR (actor_type = 'PLATFORM_ADMIN' AND activated_by IS NULL)))
    );

ALTER TABLE factory_activation_executions ALTER COLUMN requested_by DROP NOT NULL;
ALTER TABLE factory_activation_executions
    ADD COLUMN requested_actor_type VARCHAR(32),
    ADD COLUMN requested_actor_id BIGINT,
    ADD CONSTRAINT ck_asm_requested_actor CHECK (
        (requested_actor_type IS NULL AND requested_actor_id IS NULL)
        OR (requested_actor_type IS NOT NULL AND requested_actor_id IS NOT NULL AND requested_actor_id > 0
            AND ((requested_actor_type = 'FACTORY_USER' AND requested_by IS NOT DISTINCT FROM requested_actor_id)
                OR (requested_actor_type = 'PLATFORM_ADMIN' AND requested_by IS NULL)))
    );

ALTER TABLE factory_activation_executions ALTER COLUMN approved_by DROP NOT NULL;
ALTER TABLE factory_activation_executions
    ADD COLUMN approved_actor_type VARCHAR(32),
    ADD COLUMN approved_actor_id BIGINT,
    ADD CONSTRAINT ck_asm_approved_actor CHECK (
        (approved_actor_type IS NULL AND approved_actor_id IS NULL)
        OR (approved_actor_type IS NOT NULL AND approved_actor_id IS NOT NULL AND approved_actor_id > 0
            AND ((approved_actor_type = 'FACTORY_USER' AND approved_by IS NOT DISTINCT FROM approved_actor_id)
                OR (approved_actor_type = 'PLATFORM_ADMIN' AND approved_by IS NULL)))
    );

ALTER TABLE factory_activation_executions ALTER COLUMN executed_by DROP NOT NULL;
ALTER TABLE factory_activation_executions
    ADD COLUMN executed_actor_type VARCHAR(32),
    ADD COLUMN executed_actor_id BIGINT,
    ADD CONSTRAINT ck_asm_executed_actor CHECK (
        (executed_actor_type IS NULL AND executed_actor_id IS NULL AND executed_by IS NOT NULL AND executed_by > 0)
        OR (executed_actor_type IS NOT NULL AND executed_actor_id IS NOT NULL AND executed_actor_id > 0
            AND ((executed_actor_type = 'FACTORY_USER' AND executed_by IS NOT DISTINCT FROM executed_actor_id)
                OR (executed_actor_type = 'PLATFORM_ADMIN' AND executed_by IS NULL)))
    );

