package com.yanyue.rag.contract.knowledge;

import java.util.UUID;

public record CompleteUploadResponse(UUID jobId, String status) {
}
