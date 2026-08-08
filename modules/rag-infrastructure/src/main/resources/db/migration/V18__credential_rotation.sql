CREATE TABLE credential_rotation_audit (
    id UUID PRIMARY KEY,
    active_key_id VARCHAR(40) NOT NULL,
    rotated_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    total_credentials INTEGER NOT NULL CHECK (total_credentials >= 0),
    rotated_credentials INTEGER NOT NULL CHECK (rotated_credentials >= 0),
    source_counts JSONB NOT NULL DEFAULT '{}'::jsonb,
    previous_key_counts JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_credential_rotation_audit_created
    ON credential_rotation_audit (created_at DESC, id DESC);
