package com.yanyue.rag.worker.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

@Component
public class StoredDocumentReader {
    private final S3Client s3;
    private final String bucket;

    public StoredDocumentReader(S3Client s3, @Value("${rag.storage.bucket}") String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    public byte[] read(String objectKey) {
        return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(objectKey).build()).asByteArray();
    }
}
