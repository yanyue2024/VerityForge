ALTER TABLE ingestion_job
    DROP CONSTRAINT IF EXISTS ingestion_job_status_check;

ALTER TABLE ingestion_job
    ADD CONSTRAINT ingestion_job_status_check
        CHECK (status IN ('PENDING', 'RUNNING', 'AWAITING_REVIEW', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    ADD COLUMN parser_profile VARCHAR(32) NOT NULL DEFAULT 'AUTO',
    ADD COLUMN parser_options JSONB NOT NULL DEFAULT '{}',
    ADD COLUMN quality_approved_at TIMESTAMPTZ,
    ADD COLUMN quality_approved_by UUID REFERENCES app_user(id);

ALTER TABLE ingestion_job_stage
    DROP CONSTRAINT IF EXISTS ingestion_job_stage_stage_check,
    DROP CONSTRAINT IF EXISTS ingestion_job_stage_status_check;

ALTER TABLE ingestion_job_stage
    ADD CONSTRAINT ingestion_job_stage_stage_check
        CHECK (stage IN ('PARSE', 'NORMALIZE', 'QUALITY', 'CHUNK', 'EMBED', 'PUBLISH')),
    ADD CONSTRAINT ingestion_job_stage_status_check
        CHECK (status IN ('PENDING', 'RUNNING', 'REVIEW_REQUIRED', 'SUCCEEDED', 'FAILED', 'SKIPPED'));

INSERT INTO ingestion_job_stage (job_id, stage, status)
SELECT id, 'QUALITY', CASE WHEN status = 'SUCCEEDED' THEN 'SKIPPED' ELSE 'PENDING' END
FROM ingestion_job
ON CONFLICT (job_id, stage) DO NOTHING;

ALTER TABLE document_version
    DROP CONSTRAINT IF EXISTS document_version_status_check;

ALTER TABLE document_version
    ADD CONSTRAINT document_version_status_check
        CHECK (status IN ('DRAFT', 'PROCESSING', 'REVIEW_REQUIRED', 'READY', 'PUBLISHED',
                          'SUPERSEDED', 'FAILED', 'EXPIRED')),
    ADD COLUMN normalized_markdown TEXT NOT NULL DEFAULT '',
    ADD COLUMN normalized_content_hash CHAR(64),
    ADD COLUMN parser_name VARCHAR(120),
    ADD COLUMN parser_version VARCHAR(80),
    ADD COLUMN parser_schema_version VARCHAR(32),
    ADD COLUMN parse_quality_status VARCHAR(16),
    ADD COLUMN parse_quality_score INTEGER,
    ADD COLUMN parse_quality_report JSONB NOT NULL DEFAULT '{}';

ALTER TABLE document_version
    ADD CONSTRAINT document_version_parse_quality_status_check
        CHECK (parse_quality_status IS NULL OR parse_quality_status IN ('PASS', 'WARNING', 'FAIL')),
    ADD CONSTRAINT document_version_parse_quality_score_check
        CHECK (parse_quality_score IS NULL OR parse_quality_score BETWEEN 0 AND 100);

ALTER TABLE document_block
    ADD COLUMN source_offset_unit VARCHAR(32) NOT NULL DEFAULT 'UTF16_CODE_UNIT';

ALTER TABLE chunk
    ADD COLUMN context_header TEXT NOT NULL DEFAULT '',
    ADD COLUMN tokenizer_name VARCHAR(160) NOT NULL DEFAULT 'verityforge-lexical-v2',
    ADD COLUMN token_count_method VARCHAR(32) NOT NULL DEFAULT 'ESTIMATED';

UPDATE knowledge_base
SET chunk_policy = jsonb_build_object(
        'parentTargetTokens', 1000,
        'parentMaxTokens', 1200,
        'parentOverlapTokens', 100,
        'childTargetTokens', 250,
        'childMaxTokens', 384,
        'childOverlapTokens', 40,
        'version', 'parent-child-250-1000-v2'
    ),
    updated_at = now();
