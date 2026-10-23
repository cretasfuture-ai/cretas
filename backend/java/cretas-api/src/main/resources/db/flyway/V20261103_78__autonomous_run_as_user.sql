-- Cron schedules and event trigger chains run their tools through the tool execution gateway
-- as an explicit factory user, whose current account, factory and permissions the gateway
-- re-reads on every run. NULL means no run-as user: the run is refused, never executed as
-- "the system". Set by the config endpoints from the authenticated factory user who enables it.
-- No FK: the gateway already refuses a missing, inactive or other-factory user at run time, and
-- a deleted user must leave the config readable (it simply stops running).
ALTER TABLE factory_scheduler_configs ADD COLUMN run_as_user_id BIGINT;
ALTER TABLE factory_trigger_chains ADD COLUMN run_as_user_id BIGINT;

COMMENT ON COLUMN factory_scheduler_configs.run_as_user_id IS
    'Factory user the scheduled tool runs as (gateway re-reads permissions each run); NULL = refused';
COMMENT ON COLUMN factory_trigger_chains.run_as_user_id IS
    'Factory user the chain steps run as (gateway re-reads permissions each run); NULL = refused';
