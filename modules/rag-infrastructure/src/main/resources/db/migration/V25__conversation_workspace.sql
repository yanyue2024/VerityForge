ALTER TABLE conversation
    ADD COLUMN settings JSONB NOT NULL DEFAULT '{"mode":"AUTO","scope":{"knowledgeBaseIds":[],"documentIds":[]},"filters":[]}'::jsonb,
    ADD COLUMN pinned_at TIMESTAMPTZ,
    ADD COLUMN deleted_at TIMESTAMPTZ;

DROP INDEX IF EXISTS idx_conversation_visible_history;

CREATE INDEX idx_conversation_user_history
    ON conversation (
        organization_id,
        created_by,
        (CASE WHEN pinned_at IS NULL THEN 1 ELSE 0 END),
        (COALESCE(pinned_at, updated_at)) DESC,
        id DESC
    )
    WHERE conversation_kind = 'USER' AND deleted_at IS NULL;
