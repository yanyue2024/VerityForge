ALTER TABLE agent_run_checkpoint
    DROP CONSTRAINT ck_agent_run_checkpoint_version;

ALTER TABLE agent_run_checkpoint
    ADD CONSTRAINT ck_agent_run_checkpoint_version
        CHECK (checkpoint_version IN (1, 2, 3));

ALTER TABLE rag_run
    ADD COLUMN answer_mode VARCHAR(32),
    ADD COLUMN stop_reason VARCHAR(40);

ALTER TABLE rag_run
    ADD CONSTRAINT ck_rag_run_answer_mode
        CHECK (answer_mode IS NULL OR answer_mode IN ('ANSWER_WITH_EVIDENCE', 'NO_EVIDENCE')),
    ADD CONSTRAINT ck_rag_run_stop_reason
        CHECK (stop_reason IS NULL OR stop_reason IN (
            'COMPLETED_WITH_EVIDENCE', 'ZERO_ACCEPTED_EVIDENCE', 'DEADLINE_EXCEEDED',
            'CANCELLED', 'SYSTEM_FAILURE', 'BUDGET_INFEASIBLE'
        ));

ALTER TABLE chunk
    ADD COLUMN source_mapping_status VARCHAR(16) NOT NULL DEFAULT 'UNMAPPABLE',
    ADD COLUMN source_mapping_failure_reason VARCHAR(40);

ALTER TABLE chunk
    ADD CONSTRAINT ck_chunk_source_mapping_status
        CHECK (source_mapping_status IN ('MAPPED', 'UNMAPPABLE')),
    ADD CONSTRAINT ck_chunk_source_mapping_failure_reason
        CHECK (source_mapping_failure_reason IS NULL OR source_mapping_failure_reason IN (
            'SOURCE_BLOCK_MISSING', 'TEXT_MISMATCH', 'AMBIGUOUS_MATCH',
            'CROSSES_SOURCE_SEGMENTS', 'INVALID_RANGE'
        )),
    ADD CONSTRAINT ck_chunk_mapped_without_failure
        CHECK (source_mapping_status <> 'MAPPED' OR source_mapping_failure_reason IS NULL);

CREATE TABLE chunk_source_segment (
    chunk_id UUID NOT NULL REFERENCES chunk(id) ON DELETE CASCADE,
    segment_order INTEGER NOT NULL CHECK (segment_order >= 0),
    chunk_local_start INTEGER NOT NULL CHECK (chunk_local_start >= 0),
    chunk_local_end INTEGER NOT NULL CHECK (chunk_local_end > chunk_local_start),
    chunk_offset_unit VARCHAR(24) NOT NULL DEFAULT 'UTF16_CODE_UNIT'
        CHECK (chunk_offset_unit = 'UTF16_CODE_UNIT'),
    document_block_id UUID NOT NULL REFERENCES document_block(id) ON DELETE CASCADE,
    block_local_start INTEGER NOT NULL CHECK (block_local_start >= 0),
    block_local_end INTEGER NOT NULL CHECK (block_local_end > block_local_start),
    block_offset_unit VARCHAR(24) NOT NULL DEFAULT 'UTF16_CODE_UNIT'
        CHECK (block_offset_unit = 'UTF16_CODE_UNIT'),
    document_source_start INTEGER,
    document_source_end INTEGER,
    document_offset_unit VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (chunk_id, segment_order),
    CHECK (chunk_local_end - chunk_local_start = block_local_end - block_local_start),
    CHECK ((document_source_start IS NULL) = (document_source_end IS NULL)),
    CHECK (document_source_start IS NULL OR document_source_end > document_source_start),
    CHECK (document_source_start IS NULL OR document_offset_unit IS NOT NULL)
);

CREATE INDEX idx_chunk_source_segment_block
    ON chunk_source_segment (document_block_id, block_local_start, block_local_end);

ALTER TABLE evidence_item
    ADD COLUMN span_id CHAR(64),
    ADD COLUMN parent_chunk_id UUID REFERENCES chunk(id),
    ADD COLUMN first_accepted_phase VARCHAR(16),
    ADD COLUMN source_anchor JSONB;

ALTER TABLE evidence_item
    ADD CONSTRAINT ck_evidence_first_accepted_phase
        CHECK (first_accepted_phase IS NULL OR first_accepted_phase IN ('PRIMARY', 'REPAIR')),
    ADD CONSTRAINT ck_evidence_v4_span_id
        CHECK (span_id IS NULL OR span_id ~ '^[0-9a-f]{64}$');

CREATE UNIQUE INDEX uq_evidence_v4_span_identity
    ON evidence_item (run_id, sub_question_id, document_version_id, span_id)
    WHERE span_id IS NOT NULL;

CREATE TABLE evidence_requirement (
    evidence_id UUID NOT NULL REFERENCES evidence_item(id) ON DELETE CASCADE,
    requirement_id UUID NOT NULL,
    accepted_phase VARCHAR(16) NOT NULL CHECK (accepted_phase IN ('PRIMARY', 'REPAIR')),
    repair_target_id UUID,
    target_effect VARCHAR(16) CHECK (target_effect IS NULL OR target_effect IN ('COMPLETE', 'CONTRIBUTES')),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUPERSEDED')),
    superseded_by_evidence_id UUID REFERENCES evidence_item(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (evidence_id, requirement_id),
    CHECK ((status = 'SUPERSEDED') = (superseded_by_evidence_id IS NOT NULL))
);

CREATE INDEX idx_evidence_requirement_active
    ON evidence_requirement (requirement_id, evidence_id)
    WHERE status = 'ACTIVE';

CREATE TABLE evidence_query_source (
    evidence_id UUID NOT NULL REFERENCES evidence_item(id) ON DELETE CASCADE,
    retrieval_task_id UUID NOT NULL REFERENCES agent_retrieval_task(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (evidence_id, retrieval_task_id)
);

ALTER TABLE agent_retrieval_task
    ADD COLUMN research_phase VARCHAR(16),
    ADD COLUMN query_role VARCHAR(24),
    ADD COLUMN normalized_query TEXT,
    ADD COLUMN target_requirement_ids UUID[] NOT NULL DEFAULT '{}';

ALTER TABLE agent_retrieval_task
    ADD CONSTRAINT ck_agent_retrieval_task_phase
        CHECK (research_phase IS NULL OR research_phase IN ('PRIMARY', 'REPAIR')),
    ADD CONSTRAINT ck_agent_retrieval_task_role
        CHECK (query_role IS NULL OR query_role IN ('INITIAL', 'REPAIR_KEYWORD', 'REPAIR_SEMANTIC'));

CREATE UNIQUE INDEX uq_agent_v4_retrieval_task
    ON agent_retrieval_task (run_id, sub_question_id, research_phase, normalized_query, search_mode)
    WHERE research_phase IS NOT NULL AND normalized_query IS NOT NULL;

CREATE TABLE retrieval_query_candidate (
    retrieval_task_id UUID NOT NULL REFERENCES agent_retrieval_task(id) ON DELETE CASCADE,
    run_id UUID NOT NULL REFERENCES rag_run(id) ON DELETE CASCADE,
    goal_id UUID NOT NULL,
    phase VARCHAR(16) NOT NULL CHECK (phase IN ('PRIMARY', 'REPAIR')),
    chunk_id UUID NOT NULL REFERENCES chunk(id) ON DELETE CASCADE,
    candidate_rank INTEGER NOT NULL CHECK (candidate_rank > 0),
    score DOUBLE PRECISION NOT NULL,
    retrieval_source VARCHAR(16) NOT NULL CHECK (retrieval_source IN ('KEYWORD', 'SEMANTIC')),
    merged_rank INTEGER CHECK (merged_rank IS NULL OR merged_rank > 0),
    rerank_score DOUBLE PRECISION,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (retrieval_task_id, chunk_id, retrieval_source)
);

CREATE INDEX idx_retrieval_query_candidate_goal
    ON retrieval_query_candidate (run_id, goal_id, phase, merged_rank, candidate_rank);

CREATE TABLE agent_goal_research_outcome (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES rag_run(id) ON DELETE CASCADE,
    goal_id UUID NOT NULL,
    phase VARCHAR(16) NOT NULL CHECK (phase IN ('PRIMARY', 'REPAIR')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED', 'SKIPPED')),
    search_task_ids UUID[] NOT NULL DEFAULT '{}',
    deep_read_logical_call_id UUID,
    accepted_evidence_ids UUID[] NOT NULL DEFAULT '{}',
    outcome_category VARCHAR(32) NOT NULL CHECK (outcome_category IN (
        'COMPLETED_WITH_EVIDENCE', 'COMPLETED_EMPTY', 'PARTIAL_FAILURE',
        'EVIDENCE_MAY_BE_HIDDEN', 'SYSTEM_FAILURE', 'DEADLINE_EXCEEDED', 'CANCELLED'
    )),
    may_have_hidden_evidence BOOLEAN NOT NULL DEFAULT false,
    completed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, goal_id, phase)
);

CREATE INDEX idx_agent_goal_outcome_run_phase
    ON agent_goal_research_outcome (run_id, phase, completed_at);

CREATE TABLE agent_budget_reservation (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES rag_run(id) ON DELETE CASCADE,
    action_key VARCHAR(240) NOT NULL,
    status VARCHAR(16) NOT NULL
        CHECK (status IN ('RESERVED', 'DISPATCHED', 'SUCCEEDED', 'FAILED', 'RELEASED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    dispatched_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    UNIQUE (run_id, id),
    UNIQUE (run_id, action_key),
    CHECK (status <> 'DISPATCHED' OR dispatched_at IS NOT NULL),
    CHECK (status NOT IN ('SUCCEEDED', 'FAILED') OR completed_at IS NOT NULL)
);

CREATE INDEX idx_agent_budget_reservation_run_status
    ON agent_budget_reservation (run_id, status, created_at);

CREATE TABLE agent_budget_reservation_usage (
    reservation_id UUID NOT NULL REFERENCES agent_budget_reservation(id) ON DELETE CASCADE,
    dimension VARCHAR(48) NOT NULL,
    reserved_amount BIGINT NOT NULL CHECK (reserved_amount > 0),
    actual_amount BIGINT CHECK (actual_amount IS NULL OR actual_amount >= 0),
    estimated BOOLEAN NOT NULL DEFAULT true,
    PRIMARY KEY (reservation_id, dimension)
);

CREATE TABLE agent_external_action (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES rag_run(id) ON DELETE CASCADE,
    goal_id UUID,
    phase VARCHAR(24) NOT NULL,
    operation VARCHAR(48) NOT NULL,
    reservation_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    error_category VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    UNIQUE (run_id, id),
    UNIQUE (reservation_id),
    CONSTRAINT fk_agent_external_action_reservation
        FOREIGN KEY (run_id, reservation_id)
        REFERENCES agent_budget_reservation(run_id, id)
);

CREATE INDEX idx_agent_external_action_run_status
    ON agent_external_action (run_id, status, phase, operation);

CREATE TABLE agent_model_logical_call (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES rag_run(id) ON DELETE CASCADE,
    goal_id UUID,
    phase VARCHAR(24) NOT NULL,
    operation VARCHAR(48) NOT NULL,
    prompt_version VARCHAR(120) NOT NULL,
    contract_version VARCHAR(120) NOT NULL,
    prompt_hash CHAR(64) NOT NULL,
    prompt_length INTEGER NOT NULL CHECK (prompt_length >= 0),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 2),
    repair_used BOOLEAN NOT NULL DEFAULT false,
    input_tokens BIGINT NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens BIGINT NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    latency_ms BIGINT NOT NULL DEFAULT 0 CHECK (latency_ms >= 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    error_category VARCHAR(80),
    result_hash CHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    UNIQUE (run_id, id)
);

CREATE INDEX idx_agent_model_logical_call_run
    ON agent_model_logical_call (run_id, phase, operation, created_at);

CREATE TABLE agent_model_attempt (
    id UUID PRIMARY KEY,
    logical_call_id UUID NOT NULL REFERENCES agent_model_logical_call(id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL CHECK (attempt_number BETWEEN 1 AND 2),
    reservation_id UUID NOT NULL REFERENCES agent_budget_reservation(id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    input_tokens BIGINT NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens BIGINT NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    token_usage_estimated BOOLEAN NOT NULL DEFAULT true,
    latency_ms BIGINT NOT NULL DEFAULT 0 CHECK (latency_ms >= 0),
    error_category VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    UNIQUE (logical_call_id, attempt_number),
    UNIQUE (reservation_id)
);

CREATE INDEX idx_agent_model_attempt_call_status
    ON agent_model_attempt (logical_call_id, status, attempt_number);

ALTER TABLE coverage_report
    ADD COLUMN decision_source VARCHAR(24),
    ADD COLUMN logical_call_id UUID REFERENCES agent_model_logical_call(id),
    ADD COLUMN decision_schema_version INTEGER;

ALTER TABLE coverage_report
    ADD CONSTRAINT ck_coverage_decision_source
        CHECK (decision_source IS NULL OR decision_source IN ('MODEL', 'DETERMINISTIC_FALLBACK')),
    ADD CONSTRAINT ck_coverage_decision_schema_version
        CHECK (decision_schema_version IS NULL OR decision_schema_version = 4);

CREATE UNIQUE INDEX uq_coverage_v4_single_decision
    ON coverage_report (run_id)
    WHERE decision_schema_version = 4;
