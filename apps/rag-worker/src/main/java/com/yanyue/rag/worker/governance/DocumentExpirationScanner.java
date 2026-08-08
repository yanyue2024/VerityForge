package com.yanyue.rag.worker.governance;

import org.jooq.DSLContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class DocumentExpirationScanner {
    private final DSLContext dsl;
    private final TransactionTemplate transactions;

    public DocumentExpirationScanner(DSLContext dsl, TransactionTemplate transactions) {
        this.dsl = dsl;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelayString = "${rag.governance.expiration-scan-interval-ms:60000}")
    public void expireDocuments() {
        transactions.executeWithoutResult(ignored -> {
            dsl.execute("""
                    UPDATE document_version
                    SET status = 'EXPIRED', updated_at = now()
                    WHERE status = 'PUBLISHED' AND valid_to IS NOT NULL AND valid_to <= now()
                    """);
            dsl.execute("""
                    UPDATE chunk c SET enabled = false
                    FROM document_version dv
                    WHERE c.document_version_id = dv.id AND dv.status = 'EXPIRED' AND c.enabled = true
                    """);
            dsl.execute("""
                    UPDATE document d
                    SET status = 'INACTIVE', updated_at = now()
                    FROM document_version dv
                    WHERE d.current_version_id = dv.id AND dv.status = 'EXPIRED' AND d.status = 'ACTIVE'
                    """);
        });
    }
}
