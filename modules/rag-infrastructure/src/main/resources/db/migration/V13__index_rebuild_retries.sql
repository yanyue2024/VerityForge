ALTER TABLE index_rebuild_job
    ADD COLUMN attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    ADD COLUMN max_attempts INTEGER NOT NULL DEFAULT 3 CHECK (max_attempts > 0),
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now();

DROP INDEX idx_index_rebuild_job_status_created;

CREATE INDEX idx_index_rebuild_job_dispatch
    ON index_rebuild_job (status, next_attempt_at, created_at);
