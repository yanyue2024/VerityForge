ALTER TABLE model_profile
    ADD COLUMN api_key TEXT;

ALTER TABLE model_profile
    ADD CONSTRAINT chk_model_profile_api_key_length
        CHECK (api_key IS NULL OR char_length(api_key) <= 4096);

COMMENT ON COLUMN model_profile.api_key IS
    'Administrator-managed model API key stored as plaintext for persistent local configuration.';
