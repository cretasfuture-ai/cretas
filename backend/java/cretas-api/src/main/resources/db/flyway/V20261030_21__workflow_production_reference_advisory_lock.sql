-- V20261030_21: serialize Workflow deactivation against production references.
--
-- A Workflow can be disabled only after the current running plan/batch references
-- have been checked.  The check and a concurrent INSERT/status transition must
-- share one transaction-scoped PostgreSQL advisory key, otherwise both transactions
-- can observe zero references and commit an unsafe interleaving.
--
-- The key is deliberately based on the existing (factory, product) identity used by
-- the activation and batch-pin triggers, and is also acquired by
-- ProductProcessWorkflowActivationServiceImpl.deactivate().
-- The z-prefix makes this trigger fire after the existing batch pin trigger, so an
-- INSERT receives its selected_workflow_id before this function evaluates the key.

CREATE OR REPLACE FUNCTION public.lock_workflow_production_reference()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
  enters_running BOOLEAN := FALSE;
BEGIN
  IF NEW.selected_workflow_id IS NULL THEN
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
      CAST(NEW.factory_id AS text) || E'\x1f' || CAST(NEW.product_type_id AS text),
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

COMMENT ON FUNCTION public.lock_workflow_production_reference() IS
  'Serializes Workflow deactivation with running production plan/batch INSERT and status transitions.';
