ALTER TABLE agent_goal_ranked_candidate
    DROP CONSTRAINT agent_goal_ranked_candidate_check;

ALTER TABLE agent_goal_ranked_candidate
    ADD CONSTRAINT agent_goal_ranked_candidate_check
    CHECK (NOT selected_for_parent
        OR rerank_rank IS NOT NULL
        OR (rerank_fallback AND rrf_rank <= 14));
