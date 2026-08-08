CREATE TABLE assistant_profile_version (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id),
    version INTEGER NOT NULL CHECK (version > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    assistant_name VARCHAR(80) NOT NULL,
    identity_text VARCHAR(1000) NOT NULL,
    capabilities JSONB NOT NULL DEFAULT '[]',
    tone VARCHAR(500) NOT NULL,
    boundaries JSONB NOT NULL DEFAULT '[]',
    additional_instructions VARCHAR(4000) NOT NULL DEFAULT '',
    previewed_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, version)
);

CREATE UNIQUE INDEX uq_assistant_profile_published
    ON assistant_profile_version (organization_id) WHERE status = 'PUBLISHED';
CREATE UNIQUE INDEX uq_assistant_profile_draft
    ON assistant_profile_version (organization_id) WHERE status = 'DRAFT';

INSERT INTO assistant_profile_version (
    organization_id, version, status, assistant_name, identity_text,
    capabilities, tone, boundaries, additional_instructions, published_at
)
SELECT id, 1, 'PUBLISHED', 'VerityForge',
       '你是组织内部的可信知识助手，帮助员工和研发人员理解资料、查找依据并完成日常知识工作。',
       '["基于内部知识库回答并给出可核验引用","在没有内部依据时进行正常交流并明确知识边界","协助总结、解释、比较与梳理信息"]'::jsonb,
       '专业、直接、自然、简洁；先回答核心问题，再补充必要说明。',
       '["不得编造组织内部事实、制度、数据或项目状态","不得把模型通用知识描述为内部资料结论","证据不足时应明确说明并给出可执行的下一步"]'::jsonb,
       '', now()
FROM organization;

ALTER TABLE conversation
    ADD COLUMN assistant_profile_version_id UUID REFERENCES assistant_profile_version(id);

UPDATE conversation c
SET assistant_profile_version_id = p.id
FROM assistant_profile_version p
WHERE p.organization_id = c.organization_id AND p.status = 'PUBLISHED';

ALTER TABLE rag_run
    ADD COLUMN assistant_profile_version_id UUID REFERENCES assistant_profile_version(id),
    ADD COLUMN retrieval_health VARCHAR(16),
    ADD COLUMN evidence_count INTEGER NOT NULL DEFAULT 0 CHECK (evidence_count >= 0);

ALTER TABLE rag_run DROP CONSTRAINT IF EXISTS ck_rag_run_answer_mode;
ALTER TABLE rag_run ADD CONSTRAINT ck_rag_run_answer_mode CHECK (
    answer_mode IS NULL OR answer_mode IN (
        'GROUNDED', 'PARTIAL_GROUNDED', 'CONVERSATIONAL',
        'GENERAL_KNOWLEDGE', 'NO_ENTERPRISE_EVIDENCE',
        'ANSWER_WITH_EVIDENCE', 'NO_EVIDENCE'
    )
);

ALTER TABLE rag_run ADD CONSTRAINT ck_rag_run_retrieval_health CHECK (
    retrieval_health IS NULL OR retrieval_health IN ('SUFFICIENT', 'PARTIAL', 'EMPTY', 'DEGRADED')
);

ALTER TABLE rag_run DROP CONSTRAINT IF EXISTS ck_rag_run_stop_reason;
ALTER TABLE rag_run ADD CONSTRAINT ck_rag_run_stop_reason CHECK (
    stop_reason IS NULL OR stop_reason IN (
        'COMPLETED_WITH_EVIDENCE', 'COMPLETED_WITHOUT_EVIDENCE', 'ZERO_ACCEPTED_EVIDENCE',
        'DEADLINE_EXCEEDED', 'CANCELLED', 'SYSTEM_FAILURE', 'BUDGET_INFEASIBLE'
    )
);

ALTER TABLE pipeline_config
    ADD COLUMN lifecycle_status VARCHAR(16) NOT NULL DEFAULT 'ARCHIVED'
        CHECK (lifecycle_status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    ADD COLUMN previewed_at TIMESTAMPTZ;

UPDATE pipeline_config
SET lifecycle_status = CASE WHEN active THEN 'PUBLISHED' ELSE 'ARCHIVED' END;

CREATE UNIQUE INDEX uq_pipeline_config_draft_organization
    ON pipeline_config (organization_id) WHERE lifecycle_status = 'DRAFT';
