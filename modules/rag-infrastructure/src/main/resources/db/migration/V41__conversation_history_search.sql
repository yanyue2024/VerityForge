CREATE INDEX IF NOT EXISTS idx_conversation_user_title_search
    ON conversation USING GIN (title gin_trgm_ops)
    WHERE conversation_kind = 'USER' AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_conversation_user_message_search
    ON conversation_message USING GIN (content gin_trgm_ops)
    WHERE role = 'user';
