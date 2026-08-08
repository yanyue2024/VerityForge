ALTER TABLE agent_run_checkpoint
    ADD COLUMN checkpoint_version INTEGER NOT NULL DEFAULT 1,
    ALTER COLUMN stage TYPE VARCHAR(48);

ALTER TABLE agent_run_checkpoint
    ADD CONSTRAINT ck_agent_run_checkpoint_version CHECK (checkpoint_version IN (1, 2));

ALTER TABLE conversation_message
    ADD COLUMN run_id UUID;

WITH candidate AS (
    SELECT message.id,
           (message.metadata ->> 'runId')::uuid AS run_id,
           row_number() OVER (
               PARTITION BY message.metadata ->> 'runId', message.role
               ORDER BY message.created_at DESC, message.id DESC
           ) AS ordinal
    FROM conversation_message message
    WHERE message.role IN ('user', 'assistant')
      AND message.metadata ->> 'runId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND EXISTS (
          SELECT 1 FROM rag_run run WHERE run.id = (message.metadata ->> 'runId')::uuid
      )
)
UPDATE conversation_message message
SET run_id = candidate.run_id
FROM candidate
WHERE message.id = candidate.id AND candidate.ordinal = 1;

UPDATE conversation_message message
SET run_id = (message.metadata ->> 'runId')::uuid
WHERE message.role NOT IN ('user', 'assistant')
  AND message.metadata ->> 'runId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
  AND EXISTS (
      SELECT 1 FROM rag_run run WHERE run.id = (message.metadata ->> 'runId')::uuid
  );

ALTER TABLE conversation_message
    ADD CONSTRAINT fk_conversation_message_run
        FOREIGN KEY (run_id) REFERENCES rag_run(id) ON DELETE SET NULL;

CREATE INDEX idx_conversation_message_run
    ON conversation_message (run_id, created_at)
    WHERE run_id IS NOT NULL;

CREATE UNIQUE INDEX uq_conversation_message_run_role
    ON conversation_message (run_id, role)
    WHERE run_id IS NOT NULL AND role IN ('user', 'assistant');

CREATE TABLE agent_react_step (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES rag_run(id) ON DELETE CASCADE,
    step_number INTEGER NOT NULL CHECK (step_number > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    action_summary TEXT NOT NULL DEFAULT '',
    assistant_content TEXT NOT NULL DEFAULT '',
    finish_reason VARCHAR(80),
    provider_metadata JSONB NOT NULL DEFAULT '{}',
    token_usage JSONB NOT NULL DEFAULT '{}',
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    UNIQUE (run_id, step_number),
    UNIQUE (id, run_id)
);

CREATE INDEX idx_agent_react_step_run
    ON agent_react_step (run_id, step_number);

CREATE TABLE agent_tool_call (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES rag_run(id) ON DELETE CASCADE,
    step_id UUID NOT NULL,
    provider_call_id VARCHAR(500) NOT NULL,
    call_index INTEGER NOT NULL CHECK (call_index >= 0),
    tool_name VARCHAR(120) NOT NULL,
    arguments JSONB NOT NULL DEFAULT '{}',
    status VARCHAR(16) NOT NULL
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    result_output TEXT,
    result_data JSONB NOT NULL DEFAULT '{}',
    error JSONB NOT NULL DEFAULT '{}',
    result_count INTEGER CHECK (result_count IS NULL OR result_count >= 0),
    latency_ms BIGINT CHECK (latency_ms IS NULL OR latency_ms >= 0),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    UNIQUE (run_id, provider_call_id),
    UNIQUE (step_id, call_index),
    UNIQUE (id, run_id),
    CONSTRAINT fk_agent_tool_call_step
        FOREIGN KEY (step_id, run_id) REFERENCES agent_react_step(id, run_id) ON DELETE CASCADE
);

CREATE INDEX idx_agent_tool_call_run_status
    ON agent_tool_call (run_id, status, step_id, call_index);

CREATE SEQUENCE agent_knowledge_discovery_sequence;
CREATE SEQUENCE agent_knowledge_deep_read_sequence;

CREATE TABLE agent_knowledge_reference (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES rag_run(id) ON DELETE CASCADE,
    tool_call_id UUID,
    reference_key VARCHAR(100) NOT NULL,
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id),
    document_id UUID NOT NULL REFERENCES document(id),
    document_version_id UUID NOT NULL REFERENCES document_version(id),
    chunk_id UUID REFERENCES chunk(id),
    document_title VARCHAR(500) NOT NULL DEFAULT '',
    excerpt TEXT NOT NULL DEFAULT '',
    source_start INTEGER,
    source_end INTEGER,
    first_source VARCHAR(40) NOT NULL CHECK (
        first_source IN ('KNOWLEDGE_SEARCH', 'GREP_CHUNKS', 'LIST_KNOWLEDGE_CHUNKS', 'GET_DOCUMENT_INFO')
    ),
    sources TEXT[] NOT NULL DEFAULT '{}',
    deep_read BOOLEAN NOT NULL DEFAULT false,
    score DOUBLE PRECISION,
    metadata JSONB NOT NULL DEFAULT '{}',
    first_discovery_order BIGINT NOT NULL DEFAULT nextval('agent_knowledge_discovery_sequence'),
    first_deep_read_order BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, reference_key),
    CONSTRAINT fk_agent_knowledge_reference_tool_call
        FOREIGN KEY (tool_call_id) REFERENCES agent_tool_call(id) ON DELETE SET NULL
);

CREATE INDEX idx_agent_knowledge_reference_run_order
    ON agent_knowledge_reference (run_id, deep_read DESC, first_deep_read_order, first_discovery_order);

CREATE INDEX idx_agent_knowledge_reference_run_document
    ON agent_knowledge_reference (run_id, document_id);

ALTER TABLE evaluation_run
    ADD COLUMN request_snapshot JSONB NOT NULL DEFAULT '{}',
    ADD COLUMN lineage_root_id UUID,
    ADD COLUMN resumed_from_run_id UUID,
    ADD COLUMN attempt_number INTEGER NOT NULL DEFAULT 1 CHECK (attempt_number > 0);

UPDATE evaluation_run SET lineage_root_id = id WHERE lineage_root_id IS NULL;

ALTER TABLE evaluation_run
    ADD CONSTRAINT fk_evaluation_run_lineage_root
        FOREIGN KEY (lineage_root_id) REFERENCES evaluation_run(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_evaluation_run_resumed_from
        FOREIGN KEY (resumed_from_run_id) REFERENCES evaluation_run(id) ON DELETE RESTRICT;

CREATE OR REPLACE FUNCTION evaluation_run_lineage_defaults()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.lineage_root_id IS NULL THEN
        NEW.lineage_root_id := NEW.id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_evaluation_run_lineage_defaults
    BEFORE INSERT ON evaluation_run
    FOR EACH ROW EXECUTE FUNCTION evaluation_run_lineage_defaults();

CREATE INDEX idx_evaluation_run_lineage
    ON evaluation_run (lineage_root_id, attempt_number, created_at);

CREATE TABLE evaluation_case_attempt (
    id UUID PRIMARY KEY,
    evaluation_run_id UUID NOT NULL REFERENCES evaluation_run(id) ON DELETE CASCADE,
    evaluation_case_id UUID NOT NULL REFERENCES evaluation_case(id) ON DELETE CASCADE,
    rag_run_id UUID REFERENCES rag_run(id) ON DELETE SET NULL,
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    previous_attempt_id UUID REFERENCES evaluation_case_attempt(id) ON DELETE SET NULL,
    metrics JSONB NOT NULL DEFAULT '{}',
    error_message TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (evaluation_run_id, evaluation_case_id, attempt_number)
);

CREATE INDEX idx_evaluation_case_attempt_lineage
    ON evaluation_case_attempt (evaluation_run_id, evaluation_case_id, attempt_number);

UPDATE rag_run
SET status = 'FAILED',
    error_message = COALESCE(error_message, 'Legacy DEEP run interrupted by agentic-react-v1 migration; retry required'),
    completed_at = now()
WHERE status IN ('QUEUED', 'RUNNING')
  AND pipeline_version <> 'agentic-react-v1'
  AND (selected_mode = 'DEEP' OR requested_mode = 'DEEP');
