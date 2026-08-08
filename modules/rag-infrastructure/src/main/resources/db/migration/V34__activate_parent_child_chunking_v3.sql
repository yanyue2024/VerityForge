UPDATE knowledge_base
SET chunk_policy = jsonb_set(
        chunk_policy,
        '{version}',
        '"parent-child-250-1000-v3"'::jsonb,
        true
    ),
    updated_at = now()
WHERE chunk_policy ->> 'version' IN (
    'parent-child-250-1000-v2',
    'parent-child-250-1000-v2.1'
)
  AND (chunk_policy ->> 'parentTargetTokens')::integer = 1000
  AND (chunk_policy ->> 'parentMaxTokens')::integer = 1200
  AND (chunk_policy ->> 'childTargetTokens')::integer = 250
  AND (chunk_policy ->> 'childMaxTokens')::integer = 384;

UPDATE index_generation generation
SET chunk_policy_version = 'parent-child-250-1000-v3'
FROM knowledge_base knowledge_base
WHERE generation.knowledge_base_id = knowledge_base.id
  AND generation.status = 'ACTIVE'
  AND generation.chunk_policy_version IN (
      'parent-child-250-1000-v2',
      'parent-child-250-1000-v2.1'
  )
  AND knowledge_base.chunk_policy ->> 'version' = 'parent-child-250-1000-v3';
