-- V20261030_21: serialize Workflow deactivation against production references.
--
-- A Workflow can be disabled only after the current running plan/batch references
-- have been checked.  The check and a concurrent INSERT/status transition must
-- share one transaction-scoped PostgreSQL advisory key, otherwise both transactions
-- can observe zero references and commit an unsafe interleaving.
--
-- The key is based on (factory, Workflow owner product), resolved from the selected
-- Workflow identity.  This remains the same key acquired by
-- ProductProcessWorkflowActivationServiceImpl.deactivate().
-- The z-prefix makes this trigger fire after the existing batch pin trigger, so an
-- INSERT receives its selected_workflow_id before this function evaluates the key.

CREATE OR REPLACE FUNCTION public.lock_workflow_production_reference()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
  lock_product_type_id VARCHAR(64);
  active_reference_count BIGINT;
  enters_running BOOLEAN := FALSE;
BEGIN
  IF TG_TABLE_NAME = 'product_process_workflow_activations' THEN
    IF TG_OP = 'UPDATE'
       AND OLD.enabled IS TRUE
       AND NEW.enabled IS FALSE THEN
      lock_product_type_id := NEW.product_type_id;
      PERFORM pg_advisory_xact_lock(hashtextextended(
        CAST(NEW.factory_id AS text) || E'\\x1f' || CAST(lock_product_type_id AS text),
        0
      ));

      SELECT COUNT(*)
        INTO active_reference_count
        FROM (
          SELECT 1
            FROM public.production_plans plan
           WHERE plan.factory_id = NEW.factory_id
             AND plan.selected_workflow_id = NEW.active_workflow_id
             AND plan.status IN ('IN_PROGRESS', 'PRODUCING', 'PAUSED')
          UNION ALL
          SELECT 1
            FROM public.production_batches batch
           WHERE batch.factory_id = NEW.factory_id
             AND batch.selected_workflow_id = NEW.active_workflow_id
             AND batch.status IN ('IN_PROGRESS', 'PRODUCING', 'PAUSED')
        ) references_in_use;

      IF active_reference_count > 0 THEN
        RAISE EXCEPTION
          'Workflow % still has % running production references; deactivate rejected after recheck',
          NEW.active_workflow_id, active_reference_count
          USING ERRCODE = 'P0001';
      END IF;
    END IF;
    RETURN NEW;
  END IF;

  IF NEW.selected_workflow_id IS NULL THEN
    RETURN NEW;
  END IF;

  -- Joint-output rows may carry an output SKU different from the Workflow owner.
  SELECT workflow.product_type_id
    INTO lock_product_type_id
    FROM public.product_process_workflows workflow
   WHERE workflow.id = NEW.selected_workflow_id
     AND workflow.factory_id = NEW.factory_id
   LIMIT 1;
  IF lock_product_type_id IS NULL THEN
    RETURN NEW;
  END IF;

  IF TG_OP = 'INSERT' THEN
    enters_running := NEW.status IN ('IN_PROGRESS', 'PRODUCING', 'PAUSED');
  ELSE
    enters_running := NEW.status IN ('IN_PROGRESS', 'PRODUCING', 'PAUSED')
                      AND (OLD.status IS DISTINCT FROM NEW.status
                           OR OLD.selected_workflow_id IS DISTINCT FROM NEW.selected_workflow_id);
  END IF;
  IF enters_running THEN
    PERFORM pg_advisory_xact_lock(hashtextextended(
      CAST(NEW.factory_id AS text) || E'\\x1f' || CAST(lock_product_type_id AS text),
      0
    ));
  END IF;
  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS trg_z_lock_workflow_production_plan_reference
  ON public.production_plans;
CREATE TRIGGER trg_z_lock_workflow_production_plan_reference
BEFORE INSERT OR UPDATE OF status, selected_workflow_id ON public.production_plans
FOR EACH ROW
EXECUTE FUNCTION public.lock_workflow_production_reference();

DROP TRIGGER IF EXISTS trg_z_lock_workflow_production_batch_reference
  ON public.production_batches;
CREATE TRIGGER trg_z_lock_workflow_production_batch_reference
BEFORE INSERT OR UPDATE OF status, selected_workflow_id ON public.production_batches
FOR EACH ROW
EXECUTE FUNCTION public.lock_workflow_production_reference();

DROP TRIGGER IF EXISTS trg_z_guard_workflow_activation_deactivation
  ON public.product_process_workflow_activations;
CREATE TRIGGER trg_z_guard_workflow_activation_deactivation
BEFORE UPDATE OF enabled ON public.product_process_workflow_activations
FOR EACH ROW
EXECUTE FUNCTION public.lock_workflow_production_reference();

COMMENT ON FUNCTION public.lock_workflow_production_reference() IS
  'Serializes Workflow deactivation with running production plan/batch INSERT and status transitions.';
