DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM model_profile WHERE api_key IS NOT NULL) THEN
        RAISE EXCEPTION
            'model_profile.api_key contains plaintext credentials; clear and re-enter them before applying V43';
    END IF;
END
$$;

ALTER TABLE model_profile
    DROP CONSTRAINT IF EXISTS chk_model_profile_api_key_length;

ALTER TABLE model_profile
    DROP COLUMN IF EXISTS api_key;
