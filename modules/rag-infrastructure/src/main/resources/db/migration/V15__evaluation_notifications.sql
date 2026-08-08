ALTER TABLE evaluation_schedule
    ADD COLUMN webhook_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN webhook_url TEXT,
    ADD COLUMN webhook_secret_ciphertext TEXT;

ALTER TABLE evaluation_schedule
    ADD CONSTRAINT chk_evaluation_schedule_webhook_pair CHECK (
        (webhook_url IS NULL AND webhook_secret_ciphertext IS NULL)
        OR (webhook_url IS NOT NULL AND webhook_secret_ciphertext IS NOT NULL)
    ),
    ADD CONSTRAINT chk_evaluation_schedule_webhook_enabled CHECK (
        webhook_enabled = FALSE OR webhook_url IS NOT NULL
    );

CREATE TABLE evaluation_notification_delivery (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    schedule_id UUID NOT NULL REFERENCES evaluation_schedule(id) ON DELETE CASCADE,
    comparison_id UUID NOT NULL REFERENCES evaluation_comparison(id) ON DELETE CASCADE,
    dataset_id UUID NOT NULL REFERENCES evaluation_dataset(id) ON DELETE CASCADE,
    schedule_name VARCHAR(120) NOT NULL,
    dataset_name VARCHAR(180) NOT NULL,
    webhook_url TEXT NOT NULL,
    webhook_secret_ciphertext TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('WAITING', 'DELIVERING', 'RETRY', 'SUCCEEDED', 'FAILED')),
    attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    max_attempts INTEGER NOT NULL DEFAULT 5 CHECK (max_attempts BETWEEN 1 AND 10),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    response_status INTEGER,
    response_body TEXT,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (comparison_id)
);

CREATE INDEX idx_evaluation_notification_ready
    ON evaluation_notification_delivery (next_attempt_at, id)
    WHERE status IN ('WAITING', 'RETRY');

CREATE INDEX idx_evaluation_notification_stale
    ON evaluation_notification_delivery (claimed_at, id)
    WHERE status = 'DELIVERING';

CREATE INDEX idx_evaluation_notification_schedule
    ON evaluation_notification_delivery (schedule_id, created_at DESC);
