CREATE TABLE evaluation_schedule (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    dataset_id UUID NOT NULL REFERENCES evaluation_dataset(id) ON DELETE CASCADE,
    created_by UUID NOT NULL REFERENCES app_user(id),
    name VARCHAR(120) NOT NULL,
    cadence_minutes INTEGER NOT NULL CHECK (cadence_minutes BETWEEN 15 AND 10080),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    request JSONB NOT NULL DEFAULT '{}'::jsonb,
    next_run_at TIMESTAMPTZ NOT NULL,
    last_run_at TIMESTAMPTZ,
    last_comparison_id UUID REFERENCES evaluation_comparison(id) ON DELETE SET NULL,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, dataset_id, name)
);

CREATE INDEX idx_evaluation_schedule_due
    ON evaluation_schedule (next_run_at, id)
    WHERE enabled = TRUE;

CREATE INDEX idx_evaluation_schedule_dataset
    ON evaluation_schedule (dataset_id, created_at DESC);
