CREATE TABLE conversation_turn (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    active_run_id UUID REFERENCES rag_run(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE rag_run
    ADD COLUMN turn_id UUID REFERENCES conversation_turn(id) ON DELETE SET NULL,
    ADD COLUMN reprocessed_from_run_id UUID REFERENCES rag_run(id) ON DELETE SET NULL;

ALTER TABLE conversation_message
    ADD COLUMN turn_id UUID REFERENCES conversation_turn(id) ON DELETE CASCADE;

-- Existing conversations already have one user/assistant pair per Run. Reusing
-- the Run id as the initial Turn id keeps the backfill deterministic.
INSERT INTO conversation_turn (id, conversation_id, active_run_id, created_at, updated_at)
SELECT run.id, run.conversation_id, run.id, run.created_at,
       COALESCE(run.completed_at, run.created_at)
FROM rag_run run
WHERE run.conversation_id IS NOT NULL
ON CONFLICT (id) DO NOTHING;

UPDATE rag_run run
SET turn_id = run.id
WHERE run.conversation_id IS NOT NULL AND run.turn_id IS NULL;

UPDATE conversation_message message
SET turn_id = message.run_id
WHERE message.run_id IS NOT NULL AND message.turn_id IS NULL;

CREATE UNIQUE INDEX uq_conversation_turn_user_message
    ON conversation_message (turn_id, role)
    WHERE turn_id IS NOT NULL AND role = 'user';

CREATE INDEX idx_conversation_turn_history
    ON conversation_turn (conversation_id, created_at, id);

CREATE INDEX idx_rag_run_turn_history
    ON rag_run (turn_id, created_at DESC);

CREATE INDEX idx_rag_run_reprocessed_from
    ON rag_run (reprocessed_from_run_id)
    WHERE reprocessed_from_run_id IS NOT NULL;
