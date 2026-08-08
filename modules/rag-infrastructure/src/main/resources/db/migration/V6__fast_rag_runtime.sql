ALTER TABLE pipeline_config
    ADD COLUMN chat_profile_id UUID REFERENCES model_profile(id),
    ADD COLUMN query_rewrite_profile_id UUID REFERENCES model_profile(id),
    ADD COLUMN rerank_profile_id UUID REFERENCES model_profile(id),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE UNIQUE INDEX uq_pipeline_config_active_organization
    ON pipeline_config (organization_id)
    WHERE active = true;

ALTER TABLE rag_run
    ADD COLUMN pipeline_config_id UUID REFERENCES pipeline_config(id),
    ADD COLUMN query_rewrite_profile_id UUID REFERENCES model_profile(id),
    ADD COLUMN rerank_profile_id UUID REFERENCES model_profile(id),
    ADD COLUMN runtime_snapshot JSONB NOT NULL DEFAULT '{}',
    ADD COLUMN no_answer_reason VARCHAR(120);

CREATE TABLE retrieval_candidate (
    run_id UUID NOT NULL,
    chunk_id UUID NOT NULL REFERENCES chunk(id),
    keyword_rank INTEGER,
    semantic_rank INTEGER,
    rrf_score DOUBLE PRECISION,
    rerank_score DOUBLE PRECISION,
    accepted_context BOOLEAN NOT NULL DEFAULT false,
    retrieval_sources TEXT[] NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, chunk_id)
);

CREATE INDEX idx_retrieval_candidate_run_order
    ON retrieval_candidate (run_id, accepted_context DESC, rerank_score DESC NULLS LAST, rrf_score DESC NULLS LAST);
