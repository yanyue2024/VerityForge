package com.yanyue.rag.infrastructure.persistence;

import com.yanyue.rag.domain.port.QuestionSuggestionWarmTargetPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class JooqQuestionSuggestionWarmTargetAdapter implements QuestionSuggestionWarmTargetPort {
    private final DSLContext dsl;

    public JooqQuestionSuggestionWarmTargetAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<WarmTarget> findEnabledTargets() {
        var knowledgeBases = new LinkedHashMap<UUID, List<UUID>>();
        dsl.fetch("SELECT organization_id, id FROM knowledge_base ORDER BY organization_id, name, id")
                .forEach(record -> knowledgeBases.computeIfAbsent(
                        record.get("organization_id", UUID.class), ignored -> new ArrayList<>())
                        .add(record.get("id", UUID.class)));
        return dsl.fetch("""
                SELECT organization_id, id
                FROM app_user
                WHERE enabled = true
                ORDER BY organization_id, created_at, id
                """).map(record -> new WarmTarget(
                record.get("organization_id", UUID.class),
                record.get("id", UUID.class),
                knowledgeBases.getOrDefault(record.get("organization_id", UUID.class), List.of())
        ));
    }
}
