package com.yanyue.rag.worker.index;

import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RetiredGenerationCleanup {
    private final DSLContext dsl;
    private final int retentionDays;

    public RetiredGenerationCleanup(
            DSLContext dsl,
            @Value("${rag.index-rebuild.retention-days:30}") int retentionDays
    ) {
        this.dsl = dsl;
        this.retentionDays = Math.max(1, retentionDays);
    }

    @Scheduled(
            initialDelayString = "${rag.index-rebuild.cleanup-initial-delay-ms:60000}",
            fixedDelayString = "${rag.index-rebuild.cleanup-delay-ms:86400000}"
    )
    public void deleteExpiredRetiredGenerations() {
        dsl.execute("""
                DELETE FROM index_generation
                WHERE status = 'RETIRED'
                  AND retired_at IS NOT NULL
                  AND retired_at < now() - (? * interval '1 day')
                """, retentionDays);
    }
}
