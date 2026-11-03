-- Held distillation rows (metadata.secrecy_recheck: captured against the last good lists, kept with
-- their raw text until re-judged) are swept from the capture path once an hour
-- (smartbi.services.process_secret_names.expire_held_rows) and re-judged by corpus_judge.
-- Both filter on the hold marker; held rows are a tiny fraction of the table, so a partial index on
-- created_at keeps the sweep (DELETE ... WHERE metadata ? 'secrecy_recheck' AND created_at < ...) and
-- the re-judge fetch off a full scan. Partial, so it costs nothing for the ordinary rows.
-- (Not CONCURRENTLY: the migration runner applies every file inside BEGIN/COMMIT.)

CREATE INDEX IF NOT EXISTS idx_distill_samples_secrecy_recheck_created
    ON smart_bi_distillation_samples (created_at)
    WHERE metadata ? 'secrecy_recheck';
