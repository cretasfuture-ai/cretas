-- Publishing a canonical blueprint is not applying its business configuration.
-- Preserve existing bindings and allow the unapplied state to remain explicit.
ALTER TABLE public.factory_blueprint_bindings
    ALTER COLUMN applied_version DROP NOT NULL;
