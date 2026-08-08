ALTER TABLE evaluation_run
    ADD COLUMN cancellation_requested BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE evaluation_run
    DROP CONSTRAINT IF EXISTS evaluation_run_status_check;

ALTER TABLE evaluation_run
    ADD CONSTRAINT evaluation_run_status_check
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'));

CREATE UNIQUE INDEX uq_evaluation_run_lineage_attempt
    ON evaluation_run (lineage_root_id, attempt_number);
