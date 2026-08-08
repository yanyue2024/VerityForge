package com.yanyue.rag.worker.ingestion;

import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class StaleJobRecovery {
    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final long staleAfterSeconds;

    public StaleJobRecovery(DSLContext dsl, TransactionTemplate transactions,
                            @Value("${rag.ingestion.stale-after-seconds:300}") long staleAfterSeconds) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.staleAfterSeconds = staleAfterSeconds;
    }

    @Scheduled(fixedDelay = 60_000)
    public void recover() {
        transactions.executeWithoutResult(ignored -> {
            var jobs = dsl.fetch("""
                    SELECT id, document_version_id, current_stage, attempt, max_attempts
                    FROM ingestion_job
                    WHERE status = 'RUNNING'
                      AND COALESCE(heartbeat_at, started_at) < now() - (? * interval '1 second')
                    FOR UPDATE SKIP LOCKED
                    """, staleAfterSeconds);
            for (var job : jobs) {
                var jobId = job.get("id", java.util.UUID.class);
                var versionId = job.get("document_version_id", java.util.UUID.class);
                var currentStage = job.get("current_stage", String.class);
                int attempt = job.get("attempt", Integer.class);
                int maximum = job.get("max_attempts", Integer.class);
                boolean retry = attempt < maximum;
                dsl.execute("""
                        UPDATE ingestion_job_stage
                        SET status = 'FAILED', completed_at = now(),
                            error_message = 'Worker lease expired before the stage completed'
                        WHERE job_id = ? AND stage = ? AND status = 'RUNNING'
                        """, jobId, currentStage);
                dsl.execute("""
                        UPDATE ingestion_job
                        SET status = ?, heartbeat_at = NULL, error_code = 'STALE_WORKER',
                            error_message = ?, completed_at = CASE WHEN ? THEN NULL ELSE now() END
                        WHERE id = ?
                        """, retry ? "PENDING" : "FAILED",
                        retry ? "Recovered after worker heartbeat timeout"
                                : "Worker heartbeat expired and the retry limit was reached",
                        retry, jobId);
                if (retry) {
                    dsl.execute("""
                            INSERT INTO outbox_event (aggregate_type, aggregate_id, event_type, payload)
                            VALUES ('IngestionJob', ?, 'ingestion.recovered',
                                    jsonb_build_object('jobId', ?::text, 'attempt', ?))
                            """, jobId, jobId.toString(), attempt);
                } else {
                    dsl.execute("""
                            UPDATE document_version SET status = 'FAILED', updated_at = now()
                            WHERE id = ? AND status IN ('DRAFT', 'PROCESSING', 'READY')
                            """, versionId);
                }
            }
        });
    }
}
