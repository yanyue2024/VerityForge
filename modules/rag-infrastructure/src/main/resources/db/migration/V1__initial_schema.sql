CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE organization (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id),
    username VARCHAR(80) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('ADMIN', 'EDITOR', 'VIEWER')),
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, username)
);

CREATE TABLE knowledge_base (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id),
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    chunk_policy JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (organization_id, name)
);

CREATE TABLE metadata_schema (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    fields JSONB NOT NULL,
    active BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (knowledge_base_id, version)
);

CREATE TABLE index_generation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    generation_number INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('BUILDING', 'ACTIVE', 'RETIRED', 'FAILED')),
    embedding_model_id VARCHAR(160) NOT NULL,
    embedding_model_version VARCHAR(80) NOT NULL,
    embedding_dimension INTEGER NOT NULL CHECK (embedding_dimension > 0),
    chunk_policy_version VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at TIMESTAMPTZ,
    retired_at TIMESTAMPTZ,
    UNIQUE (knowledge_base_id, generation_number)
);

CREATE UNIQUE INDEX uq_active_index_generation
    ON index_generation (knowledge_base_id) WHERE status = 'ACTIVE';

CREATE TABLE document (
    id UUID PRIMARY KEY,
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id),
    organization_id UUID NOT NULL REFERENCES organization(id),
    title VARCHAR(500) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE', 'DELETED')),
    current_version_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_document_kb_status ON document (knowledge_base_id, status);

CREATE TABLE document_version (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES document(id),
    version_number INTEGER NOT NULL CHECK (version_number > 0),
    source_name VARCHAR(500) NOT NULL,
    source_type VARCHAR(40),
    version_label VARCHAR(80),
    owner_name VARCHAR(120),
    business_domain VARCHAR(120),
    tags TEXT[] NOT NULL DEFAULT '{}',
    acl_scope TEXT[] NOT NULL DEFAULT '{}',
    content_hash CHAR(64) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}',
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT', 'PROCESSING', 'READY', 'PUBLISHED', 'SUPERSEDED', 'FAILED', 'EXPIRED')),
    valid_from TIMESTAMPTZ,
    valid_to TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, version_number)
);

ALTER TABLE document ADD CONSTRAINT fk_document_current_version
    FOREIGN KEY (current_version_id) REFERENCES document_version(id);

CREATE INDEX idx_document_version_effective
    ON document_version (document_id, status, valid_from, valid_to);
CREATE INDEX idx_document_version_metadata ON document_version USING GIN (metadata jsonb_path_ops);
CREATE INDEX idx_document_version_tags ON document_version USING GIN (tags);

CREATE TABLE document_asset (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
    object_key VARCHAR(900) NOT NULL,
    file_name VARCHAR(500) NOT NULL,
    content_type VARCHAR(160) NOT NULL,
    byte_size BIGINT NOT NULL CHECK (byte_size >= 0),
    file_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_version_id, object_key)
);

CREATE TABLE document_block (
    id UUID PRIMARY KEY,
    document_version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
    block_type VARCHAR(24) NOT NULL,
    order_index INTEGER NOT NULL,
    block_text TEXT NOT NULL,
    page_number INTEGER,
    heading_path JSONB NOT NULL DEFAULT '[]',
    bounding_box JSONB,
    source_start INTEGER,
    source_end INTEGER,
    block_hash CHAR(64) NOT NULL,
    attributes JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_version_id, order_index)
);

CREATE INDEX idx_document_block_version_order ON document_block (document_version_id, order_index);
CREATE INDEX idx_document_block_hash ON document_block (block_hash);

CREATE TABLE chunk (
    id UUID PRIMARY KEY,
    document_version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
    parent_chunk_id UUID REFERENCES chunk(id) ON DELETE CASCADE,
    chunk_type VARCHAR(12) NOT NULL CHECK (chunk_type IN ('PARENT', 'CHILD')),
    order_index INTEGER NOT NULL,
    chunk_text TEXT NOT NULL,
    embedding_text TEXT NOT NULL,
    estimated_tokens INTEGER NOT NULL CHECK (estimated_tokens >= 0),
    source_block_ids UUID[] NOT NULL DEFAULT '{}',
    chunk_hash CHAR(64) NOT NULL,
    chunk_policy_version VARCHAR(80) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', coalesce(embedding_text, ''))) STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_version_id, chunk_type, order_index)
);

CREATE INDEX idx_chunk_version_type ON chunk (document_version_id, chunk_type, enabled);
CREATE INDEX idx_chunk_parent ON chunk (parent_chunk_id);
CREATE INDEX idx_chunk_hash_policy ON chunk (chunk_hash, chunk_policy_version);
CREATE INDEX idx_chunk_search_vector ON chunk USING GIN (search_vector);

CREATE TABLE chunk_embedding (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chunk_id UUID NOT NULL REFERENCES chunk(id) ON DELETE CASCADE,
    index_generation_id UUID NOT NULL REFERENCES index_generation(id) ON DELETE CASCADE,
    model_id VARCHAR(160) NOT NULL,
    model_version VARCHAR(80) NOT NULL,
    dimension INTEGER NOT NULL CHECK (dimension > 0),
    embedding VECTOR NOT NULL,
    embedding_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (chunk_id, index_generation_id)
);

CREATE INDEX idx_chunk_embedding_generation ON chunk_embedding (index_generation_id, chunk_id);
CREATE INDEX idx_chunk_embedding_hnsw_384 ON chunk_embedding
    USING hnsw ((embedding::vector(384)) vector_cosine_ops)
    WHERE dimension = 384;

CREATE TABLE ingestion_job (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id),
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id),
    document_id UUID NOT NULL REFERENCES document(id),
    document_version_id UUID NOT NULL REFERENCES document_version(id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    current_stage VARCHAR(16),
    attempt INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    idempotency_key VARCHAR(180) NOT NULL,
    error_code VARCHAR(80),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    UNIQUE (idempotency_key)
);

CREATE TABLE ingestion_job_stage (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL REFERENCES ingestion_job(id) ON DELETE CASCADE,
    stage VARCHAR(16) NOT NULL CHECK (stage IN ('PARSE', 'NORMALIZE', 'CHUNK', 'EMBED', 'PUBLISH')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    attempt INTEGER NOT NULL DEFAULT 0,
    input_hash CHAR(64),
    output_hash CHAR(64),
    metrics JSONB NOT NULL DEFAULT '{}',
    error_message TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    UNIQUE (job_id, stage)
);

CREATE TABLE outbox_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_dispatch ON outbox_event (status, available_at, created_at);

CREATE TABLE conversation (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id),
    title VARCHAR(200) NOT NULL DEFAULT '新对话',
    created_by UUID REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE conversation_message (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL CHECK (role IN ('system', 'user', 'assistant', 'tool')),
    content TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_message_conversation_time ON conversation_message (conversation_id, created_at DESC);

CREATE TABLE memory_fact (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id),
    user_id UUID REFERENCES app_user(id),
    fact_text TEXT NOT NULL,
    source_message_id UUID REFERENCES conversation_message(id),
    confidence NUMERIC(5,4) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    confirmation_status VARCHAR(16) NOT NULL CHECK (confirmation_status IN ('INFERRED', 'CONFIRMED', 'REJECTED')),
    valid_from TIMESTAMPTZ,
    valid_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE rag_run (
    id UUID PRIMARY KEY,
    conversation_id UUID REFERENCES conversation(id) ON DELETE SET NULL,
    organization_id UUID NOT NULL REFERENCES organization(id),
    requested_mode VARCHAR(8) NOT NULL CHECK (requested_mode IN ('AUTO', 'FAST', 'DEEP')),
    selected_mode VARCHAR(8) CHECK (selected_mode IN ('FAST', 'DEEP')),
    query_text TEXT NOT NULL,
    scope JSONB NOT NULL DEFAULT '{}',
    filters JSONB NOT NULL DEFAULT '[]',
    model_profile_id UUID,
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    pipeline_version VARCHAR(80) NOT NULL,
    prompt_version VARCHAR(80) NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE rag_run_event (
    event_id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    sequence BIGINT NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (run_id, sequence)
);

CREATE TABLE rag_run_sequence (
    run_id UUID PRIMARY KEY,
    next_sequence BIGINT NOT NULL CHECK (next_sequence > 0)
);

CREATE INDEX idx_run_event_replay ON rag_run_event (run_id, sequence);

CREATE TABLE retrieval_query (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL,
    sub_question_id UUID,
    query_text TEXT NOT NULL,
    strategy VARCHAR(20) NOT NULL,
    filters JSONB NOT NULL DEFAULT '[]',
    result_count INTEGER NOT NULL DEFAULT 0,
    latency_ms INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE evidence_item (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    sub_question_id UUID,
    document_id UUID NOT NULL REFERENCES document(id),
    document_version_id UUID NOT NULL REFERENCES document_version(id),
    chunk_id UUID NOT NULL REFERENCES chunk(id),
    quote_text TEXT NOT NULL,
    source_start INTEGER,
    source_end INTEGER,
    retrieval_score DOUBLE PRECISION NOT NULL,
    deep_read BOOLEAN NOT NULL DEFAULT false,
    retrieval_sources TEXT[] NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE fact_item (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    sub_question_id UUID,
    statement TEXT NOT NULL,
    evidence_ids UUID[] NOT NULL,
    confidence NUMERIC(5,4) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PROPOSED', 'ACCEPTED', 'CONFLICTING', 'REJECTED')),
    conflict_group_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE coverage_report (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL,
    round_number INTEGER NOT NULL,
    sufficient BOOLEAN NOT NULL,
    report JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, round_number)
);

CREATE TABLE citation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL,
    citation_index INTEGER NOT NULL,
    document_id UUID NOT NULL REFERENCES document(id),
    document_version_id UUID NOT NULL REFERENCES document_version(id),
    chunk_id UUID NOT NULL REFERENCES chunk(id),
    quote_text TEXT NOT NULL,
    source_start INTEGER,
    source_end INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, citation_index)
);

CREATE TABLE model_profile (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id),
    profile_type VARCHAR(24) NOT NULL CHECK (profile_type IN ('CHAT', 'EMBEDDING', 'RERANK', 'QUERY_REWRITE')),
    provider VARCHAR(24) NOT NULL CHECK (provider IN ('OPENAI_COMPATIBLE', 'OLLAMA')),
    name VARCHAR(120) NOT NULL,
    model_name VARCHAR(160) NOT NULL,
    base_url VARCHAR(500),
    encrypted_api_key BYTEA,
    settings JSONB NOT NULL DEFAULT '{}',
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, profile_type, name)
);

CREATE TABLE pipeline_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id),
    name VARCHAR(120) NOT NULL,
    pipeline_version VARCHAR(80) NOT NULL,
    parser_version VARCHAR(80) NOT NULL,
    chunk_policy_version VARCHAR(80) NOT NULL,
    embedding_model_version VARCHAR(80) NOT NULL,
    prompt_version VARCHAR(80) NOT NULL,
    settings JSONB NOT NULL DEFAULT '{}',
    active BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE evaluation_dataset (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id),
    name VARCHAR(160) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE evaluation_case (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_id UUID NOT NULL REFERENCES evaluation_dataset(id) ON DELETE CASCADE,
    question TEXT NOT NULL,
    expected_answer TEXT,
    expected_document_ids UUID[] NOT NULL DEFAULT '{}',
    metadata JSONB NOT NULL DEFAULT '{}'
);

CREATE TABLE evaluation_run (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_id UUID NOT NULL REFERENCES evaluation_dataset(id),
    pipeline_config_id UUID REFERENCES pipeline_config(id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    aggregate_metrics JSONB NOT NULL DEFAULT '{}',
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE evaluation_result (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_run_id UUID NOT NULL REFERENCES evaluation_run(id) ON DELETE CASCADE,
    evaluation_case_id UUID NOT NULL REFERENCES evaluation_case(id),
    rag_run_id UUID,
    metrics JSONB NOT NULL DEFAULT '{}',
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (evaluation_run_id, evaluation_case_id)
);

INSERT INTO organization (id, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'Default Organization')
ON CONFLICT (id) DO NOTHING;
