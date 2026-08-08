package com.yanyue.rag.infrastructure.storage;

import com.yanyue.rag.domain.port.ObjectStoragePort;
import com.yanyue.rag.domain.port.PresignedUpload;
import com.yanyue.rag.domain.port.StoredObjectInfo;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Component
public class S3ObjectStorageAdapter implements ObjectStoragePort {
    private final S3Client s3;
    private final S3Presigner presigner;
    private final Clock clock;
    private final String bucket;

    public S3ObjectStorageAdapter(S3Client s3, S3Presigner presigner, Clock clock,
                                  @Value("${rag.storage.bucket:rag-assets}") String bucket) {
        this.s3 = s3;
        this.presigner = presigner;
        this.clock = clock;
        this.bucket = bucket;
    }

    @Override
    public PresignedUpload presignPut(String objectKey, String contentType, Duration lifetime) {
        var objectRequest = PutObjectRequest.builder().bucket(bucket).key(objectKey).contentType(contentType).build();
        var request = PutObjectPresignRequest.builder().signatureDuration(lifetime).putObjectRequest(objectRequest).build();
        var result = presigner.presignPutObject(request);
        return new PresignedUpload(result.url().toString(), Map.of("Content-Type", contentType),
                clock.instant().plus(lifetime));
    }

    @Override
    public StoredObjectInfo head(String objectKey) {
        var response = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
        return new StoredObjectInfo(objectKey, response.contentLength(), response.eTag());
    }

    @Override
    public void deleteObject(String objectKey) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
    }
}
