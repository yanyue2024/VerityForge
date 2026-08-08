CREATE UNIQUE INDEX uq_metadata_schema_active
    ON metadata_schema (knowledge_base_id)
    WHERE active = true;

CREATE TABLE document_metadata_revision (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
    changed_by UUID REFERENCES app_user(id),
    previous_metadata JSONB NOT NULL,
    new_metadata JSONB NOT NULL,
    previous_valid_from TIMESTAMPTZ,
    previous_valid_to TIMESTAMPTZ,
    new_valid_from TIMESTAMPTZ,
    new_valid_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_document_metadata_revision_version
    ON document_metadata_revision (document_version_id, created_at DESC);

ALTER TABLE memory_fact
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE rag_run
    ADD COLUMN created_by UUID REFERENCES app_user(id);

UPDATE rag_run r
SET created_by = c.created_by
FROM conversation c
WHERE c.id = r.conversation_id
  AND r.created_by IS NULL;

CREATE INDEX idx_rag_run_created_by ON rag_run (created_by, created_at DESC);
