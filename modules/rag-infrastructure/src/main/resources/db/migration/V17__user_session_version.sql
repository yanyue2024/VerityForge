ALTER TABLE app_user
    ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;

CREATE INDEX idx_app_user_active_identity
    ON app_user (id, organization_id, auth_version)
    WHERE enabled = true;

CREATE UNIQUE INDEX uq_app_user_organization_username_ci
    ON app_user (organization_id, lower(username));
