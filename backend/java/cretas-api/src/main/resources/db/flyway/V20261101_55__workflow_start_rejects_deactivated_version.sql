-- V20261101_55: production start refuses the Workflow version that was just deactivated.
--
-- V20261030_21 serializes deactivation and "plan/batch enters running" on one
-- transaction-scoped advisory key, but the lock only orders them; it does not
-- decide the loser.  Running-first is decided by the deactivation recheck
-- (P0001).  Deactivation-first was not decided at all: the queued start acquired
-- the key after the deactivation committed and still succeeded, so both
-- transactions committed and production ran on a disabled Workflow.
--
-- After acquiring the key, the start path now reads the committed activation
-- state (READ COMMITTED: each statement in plpgsql takes a fresh snapshot, so
-- this SELECT sees the deactivation that released the lock).  If the selected
-- Workflow is exactly the version on the owner's activation record and that
-- activation is disabled, entering running is rejected with 23514 (the same
-- code V20261029_77/_83 use to reject retired LEGACY production).
--
-- Only the explicitly deactivated version is refused.  A version switch (the
-- activation now points at a newer version while old plans stay pinned to the
-- older one) keeps starting as before.
--
-- Function body below is V20261030_21 verbatim plus the guarded block marked
-- "V20261101_55".  Triggers and indexes from V20261030_21 are unchanged and
-- already bound to this function name, so CREATE OR REPLACE is sufficient.
-- No rows are modified; only later writes are affected.
-- Ported from cretasfuture-ai/cretas-factory-assembly V20261103_33 (gap D).

CREATE OR REPLACE FUNCTION public.lock_workflow_production_reference()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
  lock_product_type_id VARCHAR(64);
  active_reference_count BIGINT;
  enters_running BOOLEAN := FALSE;
  deactivated_version BOOLEAN := FALSE;
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
             AND plan.deleted_at IS NULL
             AND plan.status IN ('IN_PROGRESS', 'PRODUCING', 'PAUSED')
          UNION ALL
          SELECT 1
            FROM public.production_batches batch
           WHERE batch.factory_id = NEW.factory_id
             AND batch.selected_workflow_id = NEW.active_workflow_id
             AND batch.deleted_at IS NULL
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
     AND workflow.definition_version = NEW.selected_workflow_version
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

    -- V20261101_55: recheck under the key; this statement sees the committed
    -- deactivation that held the key before us.
    SELECT EXISTS (
      SELECT 1
        FROM public.product_process_workflow_activations activation
       WHERE activation.factory_id = NEW.factory_id
         AND activation.product_type_id = lock_product_type_id
         AND activation.active_workflow_id = NEW.selected_workflow_id
         AND activation.enabled IS FALSE
         AND activation.deleted_at IS NULL
    )
      INTO deactivated_version;
  END IF;
  RETURN NEW;
END;
$function$;

COMMENT ON FUNCTION public.lock_workflow_production_reference() IS
  'Serializes Workflow deactivation with running production plan/batch INSERT and status transitions; deactivation rechecks running references and a queued start refuses the deactivated version (V20261101_55).';
