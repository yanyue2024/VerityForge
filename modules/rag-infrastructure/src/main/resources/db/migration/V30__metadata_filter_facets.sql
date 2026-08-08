-- Promote source project and license to governed document Metadata fields.
-- New schema versions preserve history instead of mutating active versions in place.
INSERT INTO organization_metadata_schema (id, organization_id, version, fields, active)
SELECT gen_random_uuid(), active_schema.organization_id,
       (SELECT max(version) + 1
        FROM organization_metadata_schema history
        WHERE history.organization_id = active_schema.organization_id),
       active_schema.fields
           || CASE WHEN active_schema.fields @> '[{"key":"source_project"}]'::jsonb
                   THEN '[]'::jsonb
                   ELSE jsonb_build_array(jsonb_build_object(
                       'key', 'source_project', 'label', '来源项目', 'type', 'TEXT',
                       'required', false, 'filterable', true, 'allowedValues', jsonb_build_array())) END
           || CASE WHEN active_schema.fields @> '[{"key":"license"}]'::jsonb
                   THEN '[]'::jsonb
                   ELSE jsonb_build_array(jsonb_build_object(
                       'key', 'license', 'label', '许可证', 'type', 'TEXT',
                       'required', false, 'filterable', true, 'allowedValues', jsonb_build_array())) END,
       false
FROM organization_metadata_schema active_schema
WHERE active_schema.active = true;

UPDATE organization_metadata_schema SET active = false WHERE active = true;

UPDATE organization_metadata_schema candidate
SET active = true
WHERE candidate.version = (
    SELECT max(latest.version)
    FROM organization_metadata_schema latest
    WHERE latest.organization_id = candidate.organization_id
);

INSERT INTO metadata_schema (id, knowledge_base_id, version, fields, active)
SELECT gen_random_uuid(), active_schema.knowledge_base_id,
       (SELECT max(version) + 1
        FROM metadata_schema history
        WHERE history.knowledge_base_id = active_schema.knowledge_base_id),
       active_schema.fields
           || CASE WHEN active_schema.fields @> '[{"key":"source_project"}]'::jsonb
                   THEN '[]'::jsonb
                   ELSE jsonb_build_array(jsonb_build_object(
                       'key', 'source_project', 'label', '来源项目', 'type', 'TEXT',
                       'required', false, 'filterable', true, 'allowedValues', jsonb_build_array())) END
           || CASE WHEN active_schema.fields @> '[{"key":"license"}]'::jsonb
                   THEN '[]'::jsonb
                   ELSE jsonb_build_array(jsonb_build_object(
                       'key', 'license', 'label', '许可证', 'type', 'TEXT',
                       'required', false, 'filterable', true, 'allowedValues', jsonb_build_array())) END,
       false
FROM metadata_schema active_schema
WHERE active_schema.active = true;

UPDATE metadata_schema SET active = false WHERE active = true;

UPDATE metadata_schema candidate
SET active = true
WHERE candidate.version = (
    SELECT max(latest.version)
    FROM metadata_schema latest
    WHERE latest.knowledge_base_id = candidate.knowledge_base_id
);
