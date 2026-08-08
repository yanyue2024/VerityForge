ALTER TABLE document_version
    ADD COLUMN search_index_cleaned_at TIMESTAMPTZ,
    ADD COLUMN content_cleaned_at TIMESTAMPTZ;

CREATE INDEX idx_document_version_cleanup_candidates
    ON document_version (updated_at)
    WHERE status IN ('SUPERSEDED', 'EXPIRED') AND content_cleaned_at IS NULL;
