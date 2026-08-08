CREATE TABLE organization_metadata_schema (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    version INTEGER NOT NULL CHECK (version > 0),
    fields JSONB NOT NULL DEFAULT '[]'::jsonb,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, version)
);

CREATE UNIQUE INDEX uq_organization_metadata_schema_active
    ON organization_metadata_schema (organization_id)
    WHERE active = true;

WITH defaults AS (
    SELECT
        organization.id AS organization_id,
        jsonb_build_array(
            jsonb_build_object('key', 'document_key', 'label', '文档标识', 'type', 'TEXT', 'required', true, 'filterable', true, 'allowedValues', jsonb_build_array()),
            jsonb_build_object('key', 'file_name', 'label', '文件名', 'type', 'TEXT', 'required', true, 'filterable', true, 'allowedValues', jsonb_build_array()),
            jsonb_build_object('key', 'upload_time', 'label', '上传时间', 'type', 'DATETIME', 'required', true, 'filterable', true, 'allowedValues', jsonb_build_array()),
            jsonb_build_object('key', 'file_type', 'label', '文档类型', 'type', 'TEXT', 'required', true, 'filterable', true, 'allowedValues', jsonb_build_array()),
            jsonb_build_object('key', 'version', 'label', '版本号', 'type', 'TEXT', 'required', true, 'filterable', true, 'allowedValues', jsonb_build_array()),
            jsonb_build_object('key', 'organization', 'label', '所属组织', 'type', 'TEXT', 'required', false, 'filterable', true, 'allowedValues', jsonb_build_array()),
            jsonb_build_object('key', 'department', 'label', '所属部门', 'type', 'TEXT', 'required', false, 'filterable', true, 'allowedValues', jsonb_build_array()),
            jsonb_build_object('key', 'category', 'label', '所属种类', 'type', 'TEXT_LIST', 'required', false, 'filterable', true, 'allowedValues', jsonb_build_array()),
            jsonb_build_object('key', 'valid_to', 'label', '失效时间', 'type', 'DATETIME', 'required', false, 'filterable', true, 'allowedValues', jsonb_build_array())
        ) AS fields
    FROM organization
)
INSERT INTO organization_metadata_schema (organization_id, version, fields, active)
SELECT organization_id, 1, fields, true FROM defaults;

UPDATE metadata_schema AS ms
SET active = false
FROM knowledge_base kb
WHERE ms.knowledge_base_id = kb.id AND ms.active = true;

INSERT INTO metadata_schema (id, knowledge_base_id, version, fields, active)
SELECT gen_random_uuid(), kb.id,
       COALESCE((SELECT max(version) + 1 FROM metadata_schema WHERE knowledge_base_id = kb.id), 1),
       organization_schema.fields, true
FROM organization_metadata_schema organization_schema
JOIN knowledge_base kb ON kb.organization_id = organization_schema.organization_id
WHERE organization_schema.active = true;
