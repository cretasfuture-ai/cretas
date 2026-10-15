-- Historical activation identities remain unknown; no backfill from bare numeric IDs.
ALTER TABLE bom_recipes ADD COLUMN activated_actor_type VARCHAR(32);
ALTER TABLE bom_recipes ADD COLUMN activated_actor_id BIGINT;
ALTER TABLE bom_recipes ADD CONSTRAINT ck_bom_activation_typed_actor CHECK (
    (activated_actor_type IS NULL AND activated_actor_id IS NULL)
    OR (activated_actor_type IS NOT NULL AND activated_actor_id IS NOT NULL
        AND activated_actor_id > 0
        AND ((activated_actor_type = 'PLATFORM_ADMIN' AND activated_by IS NULL)
            OR (activated_actor_type = 'FACTORY_USER' AND activated_by IS NOT NULL
                AND activated_by = activated_actor_id)))
);
