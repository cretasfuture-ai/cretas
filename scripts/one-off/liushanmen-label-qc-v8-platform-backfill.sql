\set ON_ERROR_STOP on

-- One-time, fail-closed reclassification for the frozen Liushanmen v8 replay.
-- Default is preview-only (ROLLBACK). Apply only after the annotation-audience
-- migration and dual-queue application release are healthy:
--   psql -v apply=true -f liushanmen-label-qc-v8-platform-backfill.sql
--
-- This script never changes a label, bounding box, confidence, evidence, or
-- human review. It only routes the frozen AI UNJUDGEABLE diagnostics to the
-- platform queue and recalculates customer-facing queue statuses.

BEGIN;

CREATE TEMP TABLE target_label_qc_photos ON COMMIT DROP AS
SELECT p.id, p.task_id
FROM label_qc_photos p
JOIN label_qc_tasks t ON t.id = p.task_id
WHERE p.factory_id = 'LIUSHANMEN'
  AND p.deleted_at IS NULL
  AND t.deleted_at IS NULL
  AND p.ai_model = 'today-human-cumulative-lowres-label-23bd164b7bc3-20260822-v1-as-v8-fallback'
  AND p.screening_detail::jsonb #>> '{runtimeAudit,pipelineId}'
      = 'liushanmen-tray-label-shallow-v8-fallback-20260822';

CREATE TEMP TABLE target_label_qc_tasks ON COMMIT DROP AS
SELECT DISTINCT task_id AS id
FROM target_label_qc_photos;

DO $$
DECLARE
    photo_total INTEGER;
    task_total INTEGER;
    outside_batch_photos INTEGER;
    no_tray_photos INTEGER;
    customer_unjudgeable INTEGER;
    customer_missing_white INTEGER;
    customer_missing_color INTEGER;
    other_annotations INTEGER;
    reviewed_annotations INTEGER;
    analyzed_photos INTEGER;
    auto_passed_photos INTEGER;
    needs_review_tasks INTEGER;
    auto_passed_tasks INTEGER;
BEGIN
    SELECT COUNT(*) INTO photo_total FROM target_label_qc_photos;
    SELECT COUNT(*) INTO task_total FROM target_label_qc_tasks;
    SELECT COUNT(*) INTO outside_batch_photos
    FROM label_qc_photos p
    JOIN target_label_qc_tasks t ON t.id = p.task_id
    LEFT JOIN target_label_qc_photos target ON target.id = p.id
    WHERE p.deleted_at IS NULL AND target.id IS NULL;
    SELECT COUNT(*) INTO no_tray_photos
    FROM label_qc_photos p
    JOIN target_label_qc_photos target ON target.id = p.id
    WHERE COALESCE((p.screening_detail::jsonb ->> 'trayCount')::INTEGER, 0) = 0;

    SELECT COUNT(*) FILTER (WHERE a.ai_label = 'UNJUDGEABLE'),
           COUNT(*) FILTER (WHERE a.ai_label = 'MISSING_WHITE_LABEL'),
           COUNT(*) FILTER (WHERE a.ai_label = 'MISSING_COLOR_LABEL'),
           COUNT(*) FILTER (WHERE a.source <> 'AI'
                              OR a.ai_label NOT IN ('UNJUDGEABLE', 'MISSING_WHITE_LABEL', 'MISSING_COLOR_LABEL')
                              OR a.audience <> 'CUSTOMER'),
           COUNT(*) FILTER (WHERE a.human_label IS NOT NULL
                              OR a.reviewed_by IS NOT NULL
                              OR a.reviewed_at IS NOT NULL)
      INTO customer_unjudgeable,
           customer_missing_white,
           customer_missing_color,
           other_annotations,
           reviewed_annotations
    FROM label_qc_annotations a
    JOIN target_label_qc_photos p ON p.id = a.photo_id
    WHERE a.deleted_at IS NULL;

    SELECT COUNT(*) FILTER (WHERE p.status = 'ANALYZED'),
           COUNT(*) FILTER (WHERE p.status = 'AUTO_PASSED')
      INTO analyzed_photos, auto_passed_photos
    FROM label_qc_photos p
    JOIN target_label_qc_photos target ON target.id = p.id;

    SELECT COUNT(*) FILTER (WHERE t.status = 'NEEDS_REVIEW'),
           COUNT(*) FILTER (WHERE t.status = 'AUTO_PASSED')
      INTO needs_review_tasks, auto_passed_tasks
    FROM label_qc_tasks t
    JOIN target_label_qc_tasks target ON target.id = t.id;

    IF photo_total <> 871 OR task_total <> 222
       OR outside_batch_photos <> 0 OR no_tray_photos <> 0
       OR customer_unjudgeable <> 188
       OR customer_missing_white <> 27
       OR customer_missing_color <> 10
       OR other_annotations <> 0 OR reviewed_annotations <> 0
       OR analyzed_photos <> 141 OR auto_passed_photos <> 730
       OR needs_review_tasks <> 99 OR auto_passed_tasks <> 123 THEN
        RAISE EXCEPTION
            'Preflight mismatch: photos %, tasks %, outside %, no_tray %, unjudgeable %, white %, color %, other %, reviewed %, analyzed %, photo_auto %, task_review %, task_auto %',
            photo_total, task_total, outside_batch_photos, no_tray_photos,
            customer_unjudgeable, customer_missing_white, customer_missing_color,
            other_annotations, reviewed_annotations, analyzed_photos,
            auto_passed_photos, needs_review_tasks, auto_passed_tasks;
    END IF;
END $$;

UPDATE label_qc_annotations a
SET audience = 'PLATFORM',
    row_version = row_version + 1,
    updated_at = CURRENT_TIMESTAMP
FROM target_label_qc_photos p
WHERE a.photo_id = p.id
  AND a.deleted_at IS NULL
  AND a.source = 'AI'
  AND a.audience = 'CUSTOMER'
  AND a.ai_label = 'UNJUDGEABLE'
  AND a.human_label IS NULL
  AND a.reviewed_by IS NULL
  AND a.reviewed_at IS NULL;

UPDATE label_qc_photos p
SET status = CASE
        WHEN EXISTS (
            SELECT 1
            FROM label_qc_annotations a
            WHERE a.photo_id = p.id
              AND a.deleted_at IS NULL
              AND a.source = 'AI'
              AND a.audience = 'CUSTOMER'
        ) THEN 'ANALYZED'
        ELSE 'AUTO_PASSED'
    END,
    updated_at = CURRENT_TIMESTAMP
FROM target_label_qc_photos target
WHERE target.id = p.id;

WITH customer_counts AS (
    SELECT target.id AS task_id,
           COUNT(a.id)::INTEGER AS customer_candidate_count
    FROM target_label_qc_tasks target
    LEFT JOIN label_qc_annotations a
      ON a.task_id = target.id
     AND a.deleted_at IS NULL
     AND a.source = 'AI'
     AND a.audience = 'CUSTOMER'
    GROUP BY target.id
)
UPDATE label_qc_tasks t
SET ai_candidate_count = counts.customer_candidate_count,
    status = CASE
        WHEN counts.customer_candidate_count = 0 THEN 'AUTO_PASSED'
        ELSE 'NEEDS_REVIEW'
    END,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
FROM customer_counts counts
WHERE counts.task_id = t.id;

DO $$
DECLARE
    platform_annotations INTEGER;
    customer_annotations INTEGER;
    platform_tasks INTEGER;
    platform_photos INTEGER;
    analyzed_photos INTEGER;
    auto_passed_photos INTEGER;
    needs_review_tasks INTEGER;
    auto_passed_tasks INTEGER;
    customer_candidate_total INTEGER;
BEGIN
    SELECT COUNT(*) FILTER (WHERE a.audience = 'PLATFORM'),
           COUNT(*) FILTER (WHERE a.audience = 'CUSTOMER'),
           COUNT(DISTINCT a.task_id) FILTER (WHERE a.audience = 'PLATFORM'),
           COUNT(DISTINCT a.photo_id) FILTER (WHERE a.audience = 'PLATFORM')
      INTO platform_annotations, customer_annotations, platform_tasks, platform_photos
    FROM label_qc_annotations a
    JOIN target_label_qc_photos p ON p.id = a.photo_id
    WHERE a.deleted_at IS NULL;

    SELECT COUNT(*) FILTER (WHERE p.status = 'ANALYZED'),
           COUNT(*) FILTER (WHERE p.status = 'AUTO_PASSED')
      INTO analyzed_photos, auto_passed_photos
    FROM label_qc_photos p
    JOIN target_label_qc_photos target ON target.id = p.id;

    SELECT COUNT(*) FILTER (WHERE t.status = 'NEEDS_REVIEW'),
           COUNT(*) FILTER (WHERE t.status = 'AUTO_PASSED'),
           SUM(t.ai_candidate_count)
      INTO needs_review_tasks, auto_passed_tasks, customer_candidate_total
    FROM label_qc_tasks t
    JOIN target_label_qc_tasks target ON target.id = t.id;

    IF platform_annotations <> 188 OR customer_annotations <> 37
       OR platform_tasks <> 89 OR platform_photos <> 124
       OR analyzed_photos <> 25 OR auto_passed_photos <> 846
       OR needs_review_tasks <> 20 OR auto_passed_tasks <> 202
       OR customer_candidate_total <> 37 THEN
        RAISE EXCEPTION
            'Postflight mismatch: platform %, customer %, platform_tasks %, platform_photos %, analyzed %, photo_auto %, task_review %, task_auto %, customer_total %',
            platform_annotations, customer_annotations, platform_tasks, platform_photos,
            analyzed_photos, auto_passed_photos, needs_review_tasks,
            auto_passed_tasks, customer_candidate_total;
    END IF;

    RAISE NOTICE
        'Validated: 188 platform diagnostics on 89 tasks; customer queue 20 tasks / 37 actionable annotations; 202 tasks auto-passed';
END $$;

\if :{?apply}
    \if :apply
        COMMIT;
        \echo 'APPLIED: Liushanmen v8 diagnostics moved to the platform queue.'
    \else
        ROLLBACK;
        \echo 'PREVIEW ONLY: all changes rolled back.'
    \endif
\else
    ROLLBACK;
    \echo 'PREVIEW ONLY: all changes rolled back. Pass -v apply=true to apply.'
\endif
