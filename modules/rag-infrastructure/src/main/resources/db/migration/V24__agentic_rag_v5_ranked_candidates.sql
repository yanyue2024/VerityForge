ALTER TABLE agent_run_checkpoint
    DROP CONSTRAINT ck_agent_run_checkpoint_version;

ALTER TABLE agent_run_checkpoint
    ADD CONSTRAINT ck_agent_run_checkpoint_version
        CHECK (checkpoint_version IN (1, 2, 3, 4));

ALTER TABLE agent_retrieval_task
    DROP CONSTRAINT ck_agent_retrieval_task_role;

ALTER TABLE agent_retrieval_task
    ADD CONSTRAINT ck_agent_retrieval_task_role
        CHECK (query_role IS NULL OR query_role IN (
            'INITIAL',
            'PRIMARY_KEYWORD', 'PRIMARY_SEMANTIC',
            'REPAIR_KEYWORD', 'REPAIR_SEMANTIC'
        ));

DROP INDEX uq_agent_v4_retrieval_task;

CREATE UNIQUE INDEX uq_agent_v4_v5_retrieval_task
    ON agent_retrieval_task
       (run_id, sub_question_id, research_phase, query_role, normalized_query, search_mode)
    WHERE research_phase IS NOT NULL
      AND query_role IS NOT NULL
      AND normalized_query IS NOT NULL;

CREATE TABLE agent_goal_ranked_candidate (
    run_id UUID NOT NULL REFERENCES rag_run(id) ON DELETE CASCADE,
    goal_id UUID NOT NULL,
    goal_order INTEGER NOT NULL CHECK (goal_order BETWEEN 1 AND 3),
    phase VARCHAR(16) NOT NULL CHECK (phase IN ('PRIMARY', 'REPAIR')),
    chunk_id UUID NOT NULL REFERENCES chunk(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES document(id),
    document_version_id UUID NOT NULL REFERENCES document_version(id),
    best_raw_rank INTEGER NOT NULL CHECK (best_raw_rank > 0),
    best_raw_score DOUBLE PRECISION NOT NULL,
    rrf_rank INTEGER NOT NULL CHECK (rrf_rank > 0),
    rrf_score DOUBLE PRECISION NOT NULL CHECK (rrf_score >= 0),
    rerank_rank INTEGER CHECK (rerank_rank IS NULL OR rerank_rank > 0),
    rerank_score DOUBLE PRECISION,
    rerank_fallback BOOLEAN NOT NULL DEFAULT false,
    selected_for_parent BOOLEAN NOT NULL DEFAULT false,
    retrieval_sources TEXT[] NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, goal_id, phase, chunk_id),
    CHECK (cardinality(retrieval_sources) BETWEEN 1 AND 2),
    CHECK (retrieval_sources <@ ARRAY['KEYWORD', 'SEMANTIC']::text[]),
    CHECK (NOT selected_for_parent
        OR rerank_rank IS NOT NULL
        OR (rerank_fallback AND rrf_rank <= 8)),
    CHECK ((rerank_rank IS NULL) = (rerank_score IS NULL)),
    CHECK (NOT rerank_fallback OR rerank_rank IS NULL)
);

CREATE UNIQUE INDEX uq_agent_goal_rrf_rank
    ON agent_goal_ranked_candidate (run_id, goal_id, phase, rrf_rank);

CREATE UNIQUE INDEX uq_agent_goal_rerank_rank
    ON agent_goal_ranked_candidate (run_id, goal_id, phase, rerank_rank)
    WHERE rerank_rank IS NOT NULL;

CREATE INDEX idx_agent_goal_ranked_candidate_evaluation
    ON agent_goal_ranked_candidate
       (run_id, goal_order, phase, rerank_fallback, rerank_rank, rrf_rank);

CREATE TABLE agent_goal_ranked_candidate_route (
    run_id UUID NOT NULL,
    goal_id UUID NOT NULL,
    phase VARCHAR(16) NOT NULL CHECK (phase IN ('PRIMARY', 'REPAIR')),
    chunk_id UUID NOT NULL,
    query_id UUID NOT NULL REFERENCES agent_retrieval_task(id) ON DELETE CASCADE,
    search_mode VARCHAR(16) NOT NULL CHECK (search_mode IN ('KEYWORD', 'SEMANTIC')),
    raw_rank INTEGER NOT NULL CHECK (raw_rank > 0),
    raw_score DOUBLE PRECISION NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, goal_id, phase, chunk_id, query_id),
    CONSTRAINT fk_agent_goal_ranked_candidate_route_parent
        FOREIGN KEY (run_id, goal_id, phase, chunk_id)
        REFERENCES agent_goal_ranked_candidate (run_id, goal_id, phase, chunk_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_agent_goal_ranked_candidate_route_query
    ON agent_goal_ranked_candidate_route (query_id, run_id, goal_id, phase);
