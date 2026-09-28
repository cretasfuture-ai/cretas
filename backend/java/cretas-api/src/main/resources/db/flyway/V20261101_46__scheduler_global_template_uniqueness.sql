-- Global scheduler rows are templates: DynamicSchedulerService requires a concrete factoryId.
-- The old (factory_id, task_code) unique index does not constrain NULL factory_id on PG13.
-- Fail closed if a duplicate carries distinct configuration or execution history.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM factory_scheduler_configs a
        JOIN factory_scheduler_configs b
          ON a.factory_id IS NULL AND b.factory_id IS NULL
         AND a.task_code = b.task_code AND a.id < b.id
        WHERE (a.cron_expression, a.enabled, a.tool_or_method, a.params, a.description)
              IS DISTINCT FROM
              (b.cron_expression, b.enabled, b.tool_or_method, b.params, b.description)
           OR a.execution_count <> 0 OR b.execution_count <> 0
           OR a.last_executed_at IS NOT NULL OR b.last_executed_at IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'Divergent or executed global scheduler duplicates need manual review';
    END IF;
END $$;

-- Keep the original row ID so external references and audit history remain stable.
DELETE FROM factory_scheduler_configs duplicate
USING factory_scheduler_configs original
WHERE duplicate.factory_id IS NULL AND original.factory_id IS NULL
  AND duplicate.task_code = original.task_code AND duplicate.id > original.id;

-- These templates have never been valid runtime schedules without a concrete factory.
UPDATE factory_scheduler_configs
SET enabled = false,
    last_execution_status = 'CONFIG_INVALID',
    last_execution_error = 'global template requires a concrete factory scope'
WHERE factory_id IS NULL AND enabled = true;

CREATE UNIQUE INDEX uk_scheduler_global_task_code
    ON factory_scheduler_configs (task_code)
    WHERE factory_id IS NULL;
