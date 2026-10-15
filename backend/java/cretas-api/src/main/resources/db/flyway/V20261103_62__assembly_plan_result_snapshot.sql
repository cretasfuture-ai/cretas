-- Persist the actual apply result alongside its user-reviewed plan.
ALTER TABLE public.factory_build_plan_snapshots
    ADD COLUMN IF NOT EXISTS result_json text;
