ALTER TABLE conversation
    ADD COLUMN conversation_kind VARCHAR(16) NOT NULL DEFAULT 'USER'
        CHECK (conversation_kind IN ('USER', 'EVALUATION'));

CREATE INDEX idx_conversation_visible_history
    ON conversation (organization_id, updated_at DESC)
    WHERE conversation_kind = 'USER';
