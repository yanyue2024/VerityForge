CREATE TABLE ingestion_artifact (
    job_id UUID NOT NULL REFERENCES ingestion_job(id) ON DELETE CASCADE,
    artifact_type VARCHAR(40) NOT NULL,
    payload JSONB NOT NULL,
    artifact_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (job_id, artifact_type)
);

ALTER TABLE rag_run ADD COLUMN IF NOT EXISTS error_message TEXT;
