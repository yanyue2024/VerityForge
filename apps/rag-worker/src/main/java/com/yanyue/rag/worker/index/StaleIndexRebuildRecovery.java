package com.yanyue.rag.worker.index;

import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class StaleIndexRebuildRecovery {
    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final long staleAfterSeconds;

    public StaleIndexRebuildRecovery(
            DSLContext dsl,
            TransactionTemplate transactions,
            @Value("${rag.index-rebuild.stale-after-seconds:900}") long staleAfterSeconds
    ) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.staleAfterSeconds = Math.max(1, staleAfterSeconds);
    }

    @Scheduled(fixedDelayString = "${rag.index-rebuild.recovery-delay-ms:60000}")
    public void recover() {
        transactions.executeWithoutResult(ignored -> {
            dsl.execute("""
                    UPDATE index_rebuild_job
                    SET status = 'QUEUED', next_attempt_at = now(),
                        error_message = 'Recovered after worker heartbeat timeout', updated_at = now()
                    WHERE status = 'RUNNING'
                      AND updated_at < now() - (? * interval '1 second')
                      AND attempt < max_attempts
                    """, staleAfterSeconds);

            var exhausted = dsl.fetch("""
                    UPDATE index_rebuild_job
                    SET status = 'FAILED',
                        failed_chunks = GREATEST(total_chunks - completed_chunks, 0),
                        error_message = 'Worker heartbeat expired after the final attempt',
                        completed_at = now(), next_attempt_at = now(), updated_at = now()
                    WHERE status = 'RUNNING'
                      AND updated_at < now() - (? * interval '1 second')
                      AND attempt >= max_attempts
                    RETURNING index_generation_id
                    """, staleAfterSeconds);
            for (var job : exhausted) {
                dsl.execute("""
                        UPDATE index_generation SET status = 'FAILED'
                        WHERE id = ? AND status = 'BUILDING'
                        """, job.get("index_generation_id", UUID.class));
            }
        });
    }
}
