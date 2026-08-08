ALTER TABLE rag_run
    ADD COLUMN cancellation_requested BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE agent_retrieval_task (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES rag_run(id) ON DELETE CASCADE,
    sub_question_id UUID NOT NULL,
    round_number INTEGER NOT NULL CHECK (round_number > 0),
    query_text TEXT NOT NULL,
    search_mode VARCHAR(16) NOT NULL CHECK (search_mode IN ('KEYWORD', 'SEMANTIC', 'HYBRID')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    result_count INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    UNIQUE (run_id, sub_question_id, round_number, query_text, search_mode)
);

CREATE INDEX idx_agent_retrieval_task_run_status
    ON agent_retrieval_task (run_id, status, round_number);

ALTER TABLE fact_item
    ADD COLUMN rejection_reason TEXT,
    ADD COLUMN supports JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN valid_from TIMESTAMPTZ,
    ADD COLUMN valid_to TIMESTAMPTZ;
