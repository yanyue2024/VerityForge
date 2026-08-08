ALTER TABLE document
    ADD COLUMN access_mode VARCHAR(16) NOT NULL DEFAULT 'ORGANIZATION',
    ADD COLUMN allowed_roles TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN allowed_user_ids UUID[] NOT NULL DEFAULT '{}';

ALTER TABLE document
    ADD CONSTRAINT ck_document_access_mode
        CHECK (access_mode IN ('ORGANIZATION', 'RESTRICTED')),
    ADD CONSTRAINT ck_document_allowed_roles
        CHECK (allowed_roles <@ ARRAY['EDITOR', 'VIEWER']::text[]),
    ADD CONSTRAINT ck_document_organization_access_empty_grants
        CHECK (access_mode = 'RESTRICTED'
            OR (cardinality(allowed_roles) = 0 AND cardinality(allowed_user_ids) = 0));

CREATE INDEX idx_document_allowed_roles ON document USING GIN (allowed_roles);
CREATE INDEX idx_document_allowed_users ON document USING GIN (allowed_user_ids);

CREATE TABLE document_access_revision (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    changed_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    previous_mode VARCHAR(16) NOT NULL,
    new_mode VARCHAR(16) NOT NULL,
    previous_roles TEXT[] NOT NULL,
    new_roles TEXT[] NOT NULL,
    previous_user_ids UUID[] NOT NULL,
    new_user_ids UUID[] NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_document_access_revision_document
    ON document_access_revision (document_id, created_at DESC);

CREATE OR REPLACE FUNCTION document_is_accessible(p_document_id UUID, p_user_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
PARALLEL SAFE
SECURITY INVOKER
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM document d
        JOIN app_user u
          ON u.id = p_user_id
         AND u.organization_id = d.organization_id
         AND u.enabled = true
        WHERE d.id = p_document_id
          AND (
              u.role = 'ADMIN'
              OR d.access_mode = 'ORGANIZATION'
              OR u.role = ANY(d.allowed_roles)
              OR u.id = ANY(d.allowed_user_ids)
          )
    );
$$;

COMMENT ON FUNCTION document_is_accessible(UUID, UUID) IS
    'Authoritative current document ACL check. Administrators retain recovery access.';
COMMENT ON COLUMN document_version.acl_scope IS
    'Deprecated compatibility field. Current access is governed by document access_mode and grants.';
