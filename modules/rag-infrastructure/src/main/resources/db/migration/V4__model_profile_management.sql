ALTER TABLE model_profile
    DROP CONSTRAINT IF EXISTS model_profile_provider_check;

ALTER TABLE model_profile
    ADD CONSTRAINT model_profile_provider_check
        CHECK (provider IN ('OPENAI_COMPATIBLE', 'OLLAMA', 'LOCAL_BGE', 'DEMO'));

ALTER TABLE model_profile
    ADD COLUMN test_status VARCHAR(16) NOT NULL DEFAULT 'NOT_TESTED'
        CHECK (test_status IN ('NOT_TESTED', 'PASSED', 'FAILED')),
    ADD COLUMN last_tested_at TIMESTAMPTZ,
    ADD COLUMN last_test_message VARCHAR(500),
    ADD COLUMN capabilities JSONB NOT NULL DEFAULT '{}';

CREATE INDEX idx_model_profile_org_type_enabled
    ON model_profile (organization_id, profile_type, enabled);
