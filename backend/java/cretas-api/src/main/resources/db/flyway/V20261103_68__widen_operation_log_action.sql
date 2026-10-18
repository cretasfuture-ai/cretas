-- Preserve stable business action codes; cost recomputation already exceeds the legacy 20 characters.
ALTER TABLE operation_logs ALTER COLUMN action TYPE VARCHAR(64);
