package com.yanyue.rag.domain.port;

public record StoredObjectInfo(String objectKey, long byteSize, String eTag) {
}
