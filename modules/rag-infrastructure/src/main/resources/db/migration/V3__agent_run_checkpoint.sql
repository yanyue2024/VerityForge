CREATE TABLE agent_run_checkpoint (
    run_id UUID PRIMARY KEY REFERENCES rag_run(id) ON DELETE CASCADE,
    stage VARCHAR(24) NOT NULL,
    state JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_checkpoint_stage ON agent_run_checkpoint (stage, updated_at);
