ALTER TABLE index_generation
    ADD COLUMN embedding_profile_id UUID REFERENCES model_profile(id);

CREATE INDEX idx_index_generation_embedding_profile
    ON index_generation (embedding_profile_id)
    WHERE embedding_profile_id IS NOT NULL;

CREATE INDEX idx_chunk_embedding_hnsw_512 ON chunk_embedding
    USING hnsw ((embedding::vector(512)) vector_cosine_ops)
    WHERE dimension = 512;

CREATE TABLE index_rebuild_job (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id),
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    index_generation_id UUID NOT NULL UNIQUE REFERENCES index_generation(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    total_chunks INTEGER NOT NULL DEFAULT 0 CHECK (total_chunks >= 0),
    completed_chunks INTEGER NOT NULL DEFAULT 0 CHECK (completed_chunks >= 0),
    reused_chunks INTEGER NOT NULL DEFAULT 0 CHECK (reused_chunks >= 0),
    failed_chunks INTEGER NOT NULL DEFAULT 0 CHECK (failed_chunks >= 0),
    error_message VARCHAR(1000),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_running_index_rebuild_per_kb
    ON index_rebuild_job (knowledge_base_id)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE INDEX idx_index_rebuild_job_status_created
    ON index_rebuild_job (status, created_at);
