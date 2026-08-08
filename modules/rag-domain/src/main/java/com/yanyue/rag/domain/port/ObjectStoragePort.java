package com.yanyue.rag.domain.port;

import java.time.Duration;

public interface ObjectStoragePort {
    PresignedUpload presignPut(String objectKey, String contentType, Duration lifetime);
    StoredObjectInfo head(String objectKey);
    void deleteObject(String objectKey);
}
