CREATE TABLE evaluation_comparison (
    id UUID PRIMARY KEY,
    dataset_id UUID NOT NULL REFERENCES evaluation_dataset(id) ON DELETE CASCADE,
    fast_run_id UUID NOT NULL REFERENCES evaluation_run(id) ON DELETE CASCADE,
    deep_run_id UUID NOT NULL REFERENCES evaluation_run(id) ON DELETE CASCADE,
    judge_mode VARCHAR(32) NOT NULL
        CHECK (judge_mode IN ('NONE', 'ANSWER', 'ANSWER_AND_CITATIONS')),
    created_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (fast_run_id <> deep_run_id),
    UNIQUE (fast_run_id, deep_run_id)
);

CREATE INDEX idx_evaluation_comparison_dataset_created
    ON evaluation_comparison (dataset_id, created_at DESC);
