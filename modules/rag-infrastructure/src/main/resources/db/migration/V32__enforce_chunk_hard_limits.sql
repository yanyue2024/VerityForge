UPDATE knowledge_base
SET chunk_policy = jsonb_set(
        chunk_policy,
        '{version}',
        '"parent-child-250-1000-v2.1"'::jsonb,
        true
    ),
    updated_at = now()
WHERE chunk_policy ->> 'version' = 'parent-child-250-1000-v2';
