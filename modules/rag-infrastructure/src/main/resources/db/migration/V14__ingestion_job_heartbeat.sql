ALTER TABLE ingestion_job
    ADD COLUMN heartbeat_at TIMESTAMPTZ;

UPDATE ingestion_job
SET heartbeat_at = started_at
WHERE status = 'RUNNING' AND heartbeat_at IS NULL;

CREATE INDEX idx_ingestion_job_running_heartbeat
    ON ingestion_job (heartbeat_at)
    WHERE status = 'RUNNING';
