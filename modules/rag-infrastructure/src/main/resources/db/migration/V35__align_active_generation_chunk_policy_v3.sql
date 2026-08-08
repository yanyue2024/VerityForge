UPDATE index_generation generation
SET chunk_policy_version = 'parent-child-250-1000-v3'
FROM knowledge_base knowledge_base
WHERE generation.knowledge_base_id = knowledge_base.id
  AND generation.status = 'ACTIVE'
  AND generation.chunk_policy_version <> 'parent-child-250-1000-v3'
  AND knowledge_base.chunk_policy ->> 'version' = 'parent-child-250-1000-v3';
