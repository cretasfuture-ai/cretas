-- Forward typed actor identities must satisfy separation in their own namespace.
-- Historical identities remain unknown and retain the original legacy constraint.
ALTER TABLE factory_activation_executions
    DROP CONSTRAINT ck_factory_activation_execution_separation;

ALTER TABLE factory_activation_executions
    ADD CONSTRAINT ck_factory_activation_execution_separation CHECK (
        CASE WHEN status = 'CLAIMED' THEN TRUE
             WHEN requested_actor_type IS NULL AND approved_actor_type IS NULL AND executed_actor_type IS NULL
             THEN approved_by IS NOT NULL AND requested_by IS NOT NULL
                  AND approved_by <> executed_by AND approved_by <> requested_by
             ELSE requested_actor_type IS NOT NULL AND requested_actor_id IS NOT NULL
                  AND approved_actor_type IS NOT NULL AND approved_actor_id IS NOT NULL
                  AND executed_actor_type IS NOT NULL AND executed_actor_id IS NOT NULL
                  AND ROW(approved_actor_type, approved_actor_id)
                      IS DISTINCT FROM ROW(executed_actor_type, executed_actor_id)
                  AND ROW(approved_actor_type, approved_actor_id)
                      IS DISTINCT FROM ROW(requested_actor_type, requested_actor_id)
        END
    );
